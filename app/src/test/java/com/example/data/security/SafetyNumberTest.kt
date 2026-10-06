package com.example.data.security
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * Phase 1.7, audit finding C9, and R7.
 *
 * Verification is voice-first: two people read the same short code to each other.
 * That only works if the code is short enough to say and long enough that nobody
 * can manufacture a match.
 *
 * A short code over a static key fingerprint is brute-forceable. An attacker
 * generates keys until one produces a fingerprint matching the six digits the
 * victim reads out, and the collision is found long before the code space is
 * exhausted. The fix is length: thirty decimal digits is about 100 bits, so
 * grinding a match is not something anyone does while waiting on a phone call.
 *
 * The grouping matters too. Five digits per group is sayable in one breath, and
 * grouping means a misread digit is caught rather than silently changing the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyNumberTest {

    @Test
    fun `a code is thirty digits in six groups of five`() {
        val code = SafetyNumber.fromKeys(publicKeyA, publicKeyB)
        assertTrue("precondition: two real keys give a code", code != null)

        val digits = code!!.filter { it.isDigit() }
        assertEquals(
            "thirty decimal digits is about 100 bits, which is what stops a " +
                "grinding attack against a short code",
            30,
            digits.length
        )
        assertEquals(
            "six groups of five is readable in one breath",
            listOf(5, 5, 5, 5, 5, 5),
            code.split(" ").map { it.length }
        )
    }

    @Test
    fun `the same two keys always produce the same code`() {
        assertEquals(
            SafetyNumber.fromKeys(publicKeyA, publicKeyB),
            SafetyNumber.fromKeys(publicKeyA, publicKeyB)
        )
    }

    @Test
    fun `the order the keys are given in does not change the code`() {
        assertEquals(
            "both sides must read the same digits regardless of who looks it up",
            SafetyNumber.fromKeys(publicKeyA, publicKeyB),
            SafetyNumber.fromKeys(publicKeyB, publicKeyA)
        )
    }

    @Test
    fun `a different key produces a different code`() {
        assertNotEquals(
            "otherwise two different peers could be mistaken for each other",
            SafetyNumber.fromKeys(publicKeyA, publicKeyB),
            SafetyNumber.fromKeys(publicKeyA, publicKeyC)
        )
    }

    @Test
    fun `a code is digits and separators only`() {
        val code = SafetyNumber.fromKeys(publicKeyA, publicKeyB)!!
        assertTrue(
            "a code is read aloud, so anything else in it is a mistake waiting to happen: $code",
            code.all { it.isDigit() || it == ' ' }
        )
    }

    @Test
    fun `a blank key is refused rather than producing a weak code`() {
        assertEquals(
            "no code from missing material",
            null,
            SafetyNumber.fromKeys("", publicKeyB)
        )
        assertEquals(null, SafetyNumber.fromKeys(publicKeyA, null))
        assertEquals(null, SafetyNumber.fromKeys("   ", publicKeyB))
    }

    @Test
    fun `a fingerprint is still available for a narrow display`() {
        val short = SafetyNumber.shortFingerprint(publicKeyA, publicKeyB)
        assertTrue("a short form is still useful in a cramped space", short.isNotBlank())
        assertEquals("and it is stable", short, SafetyNumber.shortFingerprint(publicKeyA, publicKeyB))
    }

    /**
     * A single flipped bit has to change the whole code. This is the property
     * that makes the code worth reading: if small key differences produced similar
     * or identical codes, a mishearing would look like a match.
     */
    @Test
    fun `a single flipped bit anywhere in the key changes the code`() {
        val base = Base64.getDecoder().decode(publicKeyA)
        val flipped = base.copyOf()
        // Flip one bit in the middle of the key material.
        flipped[flipped.size / 2] = (flipped[flipped.size / 2].toInt() xor 0x01).toByte()
        val flippedKey = Base64.getEncoder().encodeToString(flipped)

        assertNotEquals(
            "a one bit change must not produce the same code",
            SafetyNumber.fromKeys(publicKeyA, publicKeyB),
            SafetyNumber.fromKeys(flippedKey, publicKeyB)
        )
    }

    /**
     * A known-answer vector. This pins the exact digits, so a change to the
     * domain label, the canonicalisation, or the digit conversion shows up as a
     * failure here rather than as every device quietly disagreeing with every
     * other. Anyone changing this implementation must update this line deliberately.
     */
    @Test
    fun `known answer vector`() {
        assertEquals(
            "the code for these two fixed keys is fixed; if you changed the " +
                "implementation on purpose, update this line and say why in the commit",
            SafetyNumber.fromKeys(publicKeyA, publicKeyB),
            "72157 16283 71570 33129 18971 18999"
        )
    }

    /**
     * Both sides must produce the same code regardless of how the Base64 happens
     * to be written. The same key wrapped across lines, or with different padding,
     * is still the same key.
     */
    @Test
    fun `line wrapping in the Base64 does not change the code`() {
        val raw = Base64.getDecoder().decode(publicKeyA)
        val wrapped = Base64.getEncoder().encodeToString(raw)
            .chunked(40)
            .joinToString("\n")

        assertEquals(
            "canonicalisation has to see through formatting",
            SafetyNumber.fromKeys(publicKeyA, publicKeyB),
            SafetyNumber.fromKeys(wrapped, publicKeyB)
        )
    }

    /**
     * The domain label has to be inside the hash. These are the same two keys, so
     * a digest computed without the label would otherwise be reproducible by any
     * other code that hashes a pair of keys.
     */
    @Test
    fun `the domain label is part of what is hashed`() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(
                (SafetyNumber.DOMAIN + "\u0000").toByteArray() +
                    // sorted, as the implementation does, so both sides agree
                    listOf(publicKeyA, publicKeyB).sorted().fold(ByteArray(0)) { acc, k ->
                        acc + Base64.getDecoder().decode(k)
                    }
            )
        val digits = BigInteger(1, expected)
            .mod(java.math.BigInteger.TEN.pow(30))
            .toString().padStart(30, '0')

        assertEquals(
            "the code must be the hash of the domain label and the canonical keys",
            digits.chunked(5).joinToString(" "),
            SafetyNumber.fromKeys(publicKeyA, publicKeyB)
        )
    }

    companion object {
        /**
         * Valid Base64, 65 bytes each, so the canonicalisation path is really
         * exercised. Earlier revisions used unpadded strings, which silently fell
         * back to hashing the text and meant the Base64 handling was never tested.
         */
        const val publicKeyA =
            "MFkBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val publicKeyB =
            "MFkCAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val publicKeyC =
            "MFkDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}