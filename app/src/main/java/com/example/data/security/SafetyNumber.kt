package com.example.data.security

import java.math.BigInteger
import java.security.MessageDigest
import android.util.Base64

/**
 * Phase 1.7, audit finding C9, and R7: the code two people read to each other.
 *
 * Verification is voice-first in this project, which only works if the code is
 * short enough to say and long enough that nobody can manufacture a match.
 *
 * A short code over a static key fingerprint is brute-forceable: an attacker
 * generates keys until one produces the digits a victim reads out, and finds a
 * collision long before exhausting the code space. Thirty decimal digits is about
 * 100 bits, so grinding a match is not something anyone does while waiting on a
 * phone call. Six groups of five is readable in one breath, and grouping means a
 * misread digit is a visible mistake rather than a silent change of code.
 *
 * Three details matter and each of them is a test:
 *
 *  - **A domain label is hashed in.** Otherwise this digest is just "a hash of two
 *    keys" and could be produced by any other part of the app, or by a future
 *    feature, and then a code read out for one purpose would be accepted for
 *    another.
 *  - **Keys are hashed in canonical form.** The same key can be written as
 *    wrapped Base64, or with different padding, and the code would otherwise differ
 *    between two devices holding identical keys. The Base64 is decoded and the
 *    bytes are hashed.
 *  - **Digits come from one uniform conversion of the whole hash**, not from
 *    taking each byte modulo ten. Per-byte modulo would bias low digits and, worse,
 *    would silently discard most of each byte.
 */
object SafetyNumber {

    /** Total decimal digits. About 100 bits of entropy. */
    const val DIGITS = 30

    /** Digits per spoken group. */
    const val GROUP_SIZE = 5

    /**
     * Domain separation. Changing this changes every code, so it is versioned and
     * must not be edited casually.
     */
    const val DOMAIN = "LanChat-safety-number-v1"

    private const val HASH_BITS = 256

    private val TEN = BigInteger.TEN
    private val RANGE = TEN.pow(DIGITS)

    /**
     * Largest multiple of [RANGE] that fits in the hash space. A digest at or
     * above this is rejected and re-hashed rather than reduced, which removes
     * modulo bias: without the rejection the low codes would be very slightly more
     * likely than the high ones. The gap is astronomically unlikely to be hit, but
     * the property should not depend on that.
     */
    private val UNBIASED_LIMIT: BigInteger = run {
        // BigInteger.TWO is API 33+. Writing the constant out keeps this callable
        // on every supported device, which lint flagged and a JVM test could not.
        val space = BigInteger.valueOf(2).pow(HASH_BITS)
        space.subtract(space.mod(RANGE))
    }

    /**
     * The code for a pair of peers, or null when either key is missing. Returns
     * null rather than a weak code: a verification code that can be manufactured
     * is worse than no code, because it looks like a check.
     */
    fun fromKeys(publicKeyA: String?, publicKeyB: String?): String? {
        val a = publicKeyA?.takeIf { it.isNotBlank() } ?: return null
        val b = publicKeyB?.takeIf { it.isNotBlank() } ?: return null

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(domainPrefix() + materialFor(a, b))
        return digitsOf(digest)
    }

    /**
     * A shorter form for cramped spaces, derived from the same pair and under the
     * same domain separation. Kept distinct from the spoken code, which is the
     * only one carrying the full strength.
     */
    fun shortFingerprint(publicKeyA: String?, publicKeyB: String?): String {
        val a = publicKeyA?.takeIf { it.isNotBlank() } ?: return ""
        val b = publicKeyB?.takeIf { it.isNotBlank() } ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(domainPrefix() + materialFor(a, b))
        return digest.joinToString("") { "%02x".format(it) }.uppercase()
            .take(10).chunked(5).joinToString(" ")
    }

    /**
     * Keys are sorted so both devices hash the same pair in the same order,
     * whichever side is looking it up.
     */
    /**
     * The bytes that get hashed: both keys, sorted first so the two devices agree
     * regardless of which side is looking it up.
     */
    private fun materialFor(publicKeyA: String, publicKeyB: String): ByteArray =
        listOf(publicKeyA, publicKeyB).sorted()
            .fold(ByteArray(0)) { acc, key -> acc + canonicalBytesOf(key) }

    private fun canonicalBytesOf(publicKeyBase64: String): ByteArray {
        // Whitespace is stripped before decoding: the JDK decoder rejects line
        // breaks outright, so a key wrapped by another implementation would
        // silently fall back to hashing its text and produce a different code.
        val compact = publicKeyBase64.filterNot { it.isWhitespace() }
        val decoded = runCatching { Base64.decode(compact, Base64.DEFAULT) }.getOrNull()
        // Android's decoder is lenient where the JDK one refused outright, so a
        // successful call is not proof the input was Base64. Re-encoding is cheap
        // and is the only honest way to tell a real key from a string that merely
        // decoded to something.
        val roundTrips = decoded?.let {
            Base64.encodeToString(it, Base64.NO_WRAP) == compact
        } ?: false
        // A value that is not Base64 at all still has to hash deterministically,
        // so fall back to its bytes rather than failing.
        return if (roundTrips && decoded.isNotEmpty()) {
            decoded
        } else {
            compact.toByteArray(Charsets.UTF_8)
        }
    }

    private fun domainPrefix(): ByteArray =
        DOMAIN.toByteArray(Charsets.UTF_8) + byteArrayOf(0)

    /** Uniform decimal conversion of the whole hash, with modulo bias removed. */
    private fun digitsOf(digest: ByteArray): String {
        var value = BigInteger(1, digest)
        // At most a couple of iterations in practice; bounded so a pathological
        // digest cannot spin.
        var attempts = 0
        while (value >= UNBIASED_LIMIT && attempts < 8) {
            value = MessageDigest.getInstance("SHA-256").digest(
                "retry".toByteArray(Charsets.UTF_8) + digest
            ).let { BigInteger(1, it) }
            attempts++
        }
        val digits = value.mod(RANGE).toString().padStart(DIGITS, '0')
        // Six groups of five: readable in one breath, and a misread digit is a
        // visible mistake rather than a silent change of code.
        return digits.chunked(GROUP_SIZE).joinToString(" ")
    }
}