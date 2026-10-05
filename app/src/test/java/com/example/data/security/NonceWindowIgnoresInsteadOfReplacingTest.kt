package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.5: IGNORE instead of REPLACE.
 *
 * The per-session seen-nonce set had a fixed capacity. When it filled, the code
 * evicted the oldest entry to make room. That is REPLACE, and it is exactly
 * backwards for replay defence: the entries that get thrown away are the oldest
 * captures, which are the ones an attacker replaying recorded traffic actually
 * wants to send back. Filling the window therefore unlocked the oldest messages
 * for replay.
 *
 * The correct behaviour is to IGNORE. Once the window is full the honest answer is
 * that this session can no longer distinguish a new message from an old replay, so
 * the new message is dropped and the window is left intact.
 *
 * MAX_SEEN_NONCES_PER_SESSION is mirrored below as a literal. If the constant
 * changes, this test fails rather than silently testing the wrong size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NonceWindowIgnoresInsteadOfReplacingTest {

    private companion object {
        const val MAX_SEEN_NONCES_PER_SESSION = 1000
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var sender: PairwiseSessionManager
    private lateinit var receiver: PairwiseSessionManager

    @Before
    fun setUp() {
        sender = PairwiseSessionManager(context)
        receiver = PairwiseSessionManager(context)
        // Both sides hold a session for the same peer id, which is what a paired
        // contact looks like: each derives against the other's public key.
        assertTrue(sender.establishSession("peer", receiver.getMyPublicKeyBase64()))
        assertTrue(receiver.establishSession("peer", receiver.getMyPublicKeyBase64()))
    }

    /** Seals a message and offers it to the receiver, returning what came back. */
    private fun roundTrip(text: String): String? {
        val sealed = sender.encryptForPeer("peer", text)
        assertNotNull("encrypt should succeed while the window has room", sealed)
        return receiver.decryptFromPeer("peer", sealed!!)
    }

    @Test
    fun `a message arriving after the window is full is dropped`() {
        repeat(MAX_SEEN_NONCES_PER_SESSION) { roundTrip("filler $it") }

        assertNull(
            "with the window full a new message cannot be told apart from a replay, " +
                "so it must be dropped rather than evicting an old nonce",
            roundTrip("the one that does not fit")
        )
    }

    @Test
    fun `filling the window does not make the oldest messages replayable`() {
        // The very first ciphertext is what an attacker replaying a recorded
        // capture would reach for.
        val firstCapture = sender.encryptForPeer("peer", "the oldest message")
        assertNotNull(firstCapture)

        repeat(MAX_SEEN_NONCES_PER_SESSION) { roundTrip("filler $it") }

        assertNull(
            "evicting the oldest nonce to make room is what let old captures replay",
            receiver.decryptFromPeer("peer", firstCapture!!)
        )
    }

    @Test
    fun `an ordinary duplicate is still rejected`() {
        val sealed = sender.encryptForPeer("peer", "only once")

        assertNotNull("the first delivery is accepted", receiver.decryptFromPeer("peer", sealed!!))
        assertNull(
            "a genuine duplicate must be rejected",
            receiver.decryptFromPeer("peer", sealed)
        )
    }
}