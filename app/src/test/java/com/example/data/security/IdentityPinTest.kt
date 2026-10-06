package com.example.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.6, audit findings C2 and M9: pinning the identity pair.
 *
 * A peer's key can change. Sometimes that is an attack, so a key that differs
 * from the one this device already trusted has to stop being usable immediately
 * rather than quietly replacing the old one. A silent re-pin is the failure mode
 * worth naming: if a contact's key is overwritten the first time a different key
 * appears, then an attacker only has to be believed once and the user is never
 * told anything happened.
 *
 * The pin is therefore written only when the user explicitly adds the contact,
 * never on first contact, and a mismatch blocks rather than updates.
 */
class IdentityPinTest {

    private val knownKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEknown0000000000000000000000000000000000000000="
    private val otherKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEother000000000000000000000000000000000000000="

    @Test
    fun `no pin yet means unpinned, and the peer is not trusted`() {
        val state = IdentityPin.evaluate(pinnedKey = null, observedKey = knownKey)

        assertEquals(IdentityPin.IdentityState.UNPINNED, state)
        assertFalse(
            "an unpinned peer must not get full rights on the strength of a key it " +
                "just presented",
            state.allowsTraffic()
        )
    }

    @Test
    fun `a matching key is trusted`() {
        val state = IdentityPin.evaluate(pinnedKey = knownKey, observedKey = knownKey)

        assertEquals(IdentityPin.IdentityState.TRUSTED, state)
        assertTrue(state.allowsTraffic())
    }

    @Test
    fun `a differing key blocks and reports the change`() {
        val state = IdentityPin.evaluate(pinnedKey = knownKey, observedKey = otherKey)

        assertEquals(
            "a changed key must stop being usable, not replace the old one",
            IdentityPin.IdentityState.KEY_CHANGED,
            state
        )
        assertFalse(
            "nothing may be sent to a peer whose key changed until the user says so",
            state.allowsTraffic()
        )
    }

    @Test
    fun `a key change is reported with both keys so the user can compare`() {
        val detail = IdentityPin.describeChange(pinnedKey = knownKey, observedKey = otherKey)

        assertEquals(knownKey, detail?.pinnedKey)
        assertEquals(otherKey, detail?.observedKey)
        assertTrue(
            "the user needs a stable short form to read out or scan",
            detail!!.fingerprint.isNotBlank()
        )
    }

    @Test
    fun `no change to describe when the keys agree`() {
        assertNull(IdentityPin.describeChange(pinnedKey = knownKey, observedKey = knownKey))
    }

    @Test
    fun `a peer presenting no key at all cannot be trusted`() {
        val state = IdentityPin.evaluate(pinnedKey = knownKey, observedKey = null)

        assertEquals(IdentityPin.IdentityState.UNPINNED, state)
        assertFalse(state.allowsTraffic())
    }

    @Test
    fun `a blank key is treated as no key`() {
        val state = IdentityPin.evaluate(pinnedKey = knownKey, observedKey = "   ")

        assertEquals(IdentityPin.IdentityState.UNPINNED, state)
    }

    @Test
    fun `accepting a changed key is the only way out of KEY_CHANGED`() {
        val blocked = IdentityPin.evaluate(pinnedKey = knownKey, observedKey = otherKey)
        assertFalse(blocked.allowsTraffic())

        // The user explicitly accepted the new key, so it becomes the pin.
        val afterAcceptance = IdentityPin.evaluate(pinnedKey = otherKey, observedKey = otherKey)
        assertEquals(IdentityPin.IdentityState.TRUSTED, afterAcceptance)
        assertTrue(afterAcceptance.allowsTraffic())
    }

    @Test
    fun `the fingerprint is stable and distinguishes different keys`() {
        val a = IdentityPin.fingerprint(knownKey)
        val b = IdentityPin.fingerprint(knownKey)
        val c = IdentityPin.fingerprint(otherKey)

        assertEquals("the same key must always read the same", a, b)
        assertFalse("different keys must not share a fingerprint", a == c)
    }
}