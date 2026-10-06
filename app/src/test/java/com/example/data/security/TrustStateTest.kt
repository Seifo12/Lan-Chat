package com.example.data.security
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.7b: the trust state a contact can be in, and the only ways between them.
 *
 * 1.6 could block a contact whose key changed and nothing could unblock it, so a
 * peer that reinstalled the app was stuck forever. This is the state machine that
 * ends that dead end, and the transitions are deliberately narrow: the only way out
 * of a key change is the user saying so.
 *
 * Three states, and the wording matters. UNVERIFIED is not a failure and not a
 * warning about the user; it is the honest description of first contact in a
 * serverless mesh, and it is what the design says the UI should say in plain
 * language until verification has actually happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustStateTest {

    private val pinnedKey = "pinned-key-value"
    private val observedKey = "observed-key-value"
    private val otherKey = "a-completely-different-key"

    /**
     * The default is a contact whose presented key matches the pin. `observedKey`
     * is the same value on purpose: a default that differed would silently make
     * every default case a key change, which is exactly the mistake this test file
     * had before.
     */
    private fun contact(
        pinned: String? = pinnedKey,
        observed: String? = pinnedKey,
        verifiedAt: Long? = null,
    ) = ContactTrust(
        deviceId = "peer_a",
        displayName = "Peer",
        pinnedPublicKey = pinned,
        observedPublicKey = observed,
        verifiedAt = verifiedAt,
    )

    @Test
    fun `a contact with a matching key and no verification is unverified`() {
        assertEquals(TrustState.UNVERIFIED, contact().state())
    }

    @Test
    fun `a contact the user has verified shows verified`() {
        assertEquals(TrustState.VERIFIED, contact(verifiedAt = 1_700_000_000_000L).state())
    }

    @Test
    fun `a changed key outranks verification`() {
        val wasVerified = contact(
            observed = otherKey,
            verifiedAt = 1_700_000_000_000L,
        )

        assertEquals(
            "a contact that verified before and now presents a different key is not " +
                "still verified, whatever it used to be",
            TrustState.KEY_CHANGED,
            wasVerified.state()
        )
    }

    @Test
    fun `a contact with no pin is unverified, not trusted`() {
        assertEquals(
            TrustState.UNVERIFIED,
            contact(pinned = null, observed = observedKey).state()
        )
    }

    @Test
    fun `claiming verified is offered only to an unverified contact`() {
        assertTrue(
            "an unverified contact is precisely the one the user should be able " +
                "to confirm, otherwise there is no path to VERIFIED at all",
            contact().allowsClaimingVerified()
        )
        assertFalse(
            "a changed key is not confirmable: comparing codes is a different " +
                "decision from accepting a new identity",
            contact(observed = otherKey).allowsClaimingVerified()
        )
        assertFalse(
            "already verified, nothing to claim",
            contact(verifiedAt = 1L).allowsClaimingVerified()
        )
    }

    @Test
    fun `a key changed contact is blocked from sending`() {
        assertTrue(contact(observed = otherKey).blocksSending())
    }

    @Test
    fun `only a key changed contact is blocked`() {
        assertFalse("a verified contact sends normally", contact(verifiedAt = 1L).blocksSending())
        assertFalse("an unverified contact sends normally, it is just unverified",
            contact().blocksSending())
    }

    @Test
    fun `the code is available whenever both keys are known`() {
        val code = contact().safetyCode()
        assertNotNull("two real keys give a code", code)
        assertEquals("six groups of five", 6, code!!.split(" ").size)
    }

    @Test
    fun `no code while the keys disagree, because there is nothing to compare`() {
        assertNull(
            "a code over two different keys would be meaningless",
            contact(observed = otherKey).safetyCode()
        )
    }

    @Test
    fun `no code without a pin`() {
        assertNull(contact(pinned = null).safetyCode())
    }

    @Test
    fun `accepting a new key makes the contact verified again`() {
        val changed = contact(observed = otherKey)

        val afterAcceptance = changed.copy(
            pinnedPublicKey = changed.observedPublicKey,
            verifiedAt = 1_700_000_000_000L,
        )

        assertEquals(TrustState.VERIFIED, afterAcceptance.state())
        assertFalse(
            "and it must actually send again, which is the whole point",
            afterAcceptance.blocksSending()
        )
    }

    @Test
    fun `marking verified does not resurrect a changed key`() {
        val changed = contact(observed = otherKey, verifiedAt = 1L)

        assertEquals(
            "marking verified is not the same as accepting a new key",
            TrustState.KEY_CHANGED,
            changed.state()
        )
        assertTrue(
            "and a changed key stays blocked regardless",
            changed.blocksSending()
        )
    }

    @Test
    fun `the code needs both halves of the pair`() {
        assertNull(contact(pinned = pinnedKey, observed = null).safetyCode())
        assertNull(contact(pinned = null, observed = observedKey).safetyCode())
    }
}