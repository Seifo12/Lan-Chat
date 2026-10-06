package com.example.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    companion object {
        const val publicKeyA = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEalpha0000000000000000000000000000000000"
        const val publicKeyB = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbravo00000000000000000000000000000000000"
        const val publicKeyC = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEdelta00000000000000000000000000000000000"
    }
}