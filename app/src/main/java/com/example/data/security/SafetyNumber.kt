package com.example.data.security

import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Phase 1.7, audit finding C9, and R7: the code two people read to each other.
 *
 * Verification is voice-first in this project, which only works if the code is
 * short enough to say and long enough that nobody can manufacture a match.
 *
 * A short code over a static key fingerprint is brute-forceable: an attacker
 * generates keys until one produces the digits the victim reads out, and finds a
 * collision long before exhausting the code space. Thirty decimal digits is about
 * 100 bits, so grinding a match is not something anyone does while waiting on a
 * phone call. Six groups of five is readable in one breath, and grouping means a
 * misread digit is a visible mistake rather than a silent change of code.
 *
 * The two keys are sorted before hashing so both sides read the same digits no
 * matter who looks it up.
 */
object SafetyNumber {

    /** Total decimal digits. About 100 bits of entropy. */
    const val DIGITS = 30

    /** Digits per spoken group. */
    const val GROUP_SIZE = 5

    private val BIG = BigInteger.TEN.pow(DIGITS)

    /**
     * The code for a pair of peers, or null when either key is missing. Returns
     * null rather than a weak code: a verification code that can be manufactured
     * is worse than no code, because it looks like a check.
     */
    fun fromKeys(publicKeyA: String?, publicKeyB: String?): String? {
        val a = publicKeyA?.takeIf { it.isNotBlank() } ?: return null
        val b = publicKeyB?.takeIf { it.isNotBlank() } ?: return null

        // Sorted so both devices hash the same pair in the same order.
        val material = listOf(a, b).sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        val number = BigInteger(1, digest).mod(BIG)

        val digits = number.toString().padStart(DIGITS, '0')
        return digits.chunked(GROUP_SIZE).joinToString(" ")
    }

    /**
     * A shorter form for cramped spaces, derived from the same pair. Kept separate
     * so it can never be mistaken for the spoken code, which is the only one that
     * carries the full strength.
     */
    fun shortFingerprint(publicKeyA: String?, publicKeyB: String?): String {
        val a = publicKeyA?.takeIf { it.isNotBlank() } ?: return ""
        val b = publicKeyB?.takeIf { it.isNotBlank() } ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(listOf(a, b).sorted().joinToString("|").toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.uppercase()
        return hex.take(10).chunked(5).joinToString(" ")
    }
}