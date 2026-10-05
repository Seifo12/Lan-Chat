package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.2: fail closed.
 *
 * Every one of these cases describes a path where a security step failed and the
 * code carried on with a weaker mechanism instead of refusing. That is the one
 * behaviour this phase exists to delete: a message that cannot be protected the
 * way the sender intended must not be sent at all, because the recipient would
 * either read garbage or, worse, read it under a key that was never meant to
 * protect it.
 *
 * These tests are expected to fail before the fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FailClosedEncryptionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var alice: PairwiseSessionManager
    private lateinit var bob: PairwiseSessionManager

    @Before
    fun setUp() {
        alice = PairwiseSessionManager(context)
        bob = PairwiseSessionManager(context)
    }

    /**
     * The sharpest version of the bug. Bob encrypts for himself; Alice asks to
     * decrypt it "from Carol", a peer she has no session with. The old code fell
     * back to trying every session key it held, so Alice's request succeeded with
     * Bob's key. That is not decryption, it is guessing, and it means a ciphertext
     * is not bound to the peer it was addressed to.
     */
    @Test
    fun `a ciphertext addressed to one peer does not decrypt for a different peer`() {
        val alicePublic = alice.getMyPublicKeyBase64()
        val bobPublic = bob.getMyPublicKeyBase64()
        assertTrue(bob.establishSession("bob_peer", bobPublic))
        assertTrue(alice.establishSession("alice_peer", alicePublic))

        val sealedForBob = bob.encryptForPeer("bob_peer", "secret for bob")
        assertNotNull("precondition: encryption for a known peer works", sealedForBob)

        assertNull(
            "a peer with no session must not decrypt another peer's traffic",
            alice.decryptFromPeer("carol_peer", sealedForBob!!)
        )
    }

    @Test
    fun `decryption without any session returns nothing rather than guessing`() {
        val bobPublic = bob.getMyPublicKeyBase64()
        bob.establishSession("bob_peer", bobPublic)
        val sealed = bob.encryptForPeer("bob_peer", "secret")

        val stranger = PairwiseSessionManager(context)

        assertNull(
            "with no sessions at all there is nothing to try, and nothing to return",
            stranger.decryptFromPeer("bob_peer", sealed!!)
        )
    }

    @Test
    fun `a correct peer still decrypts its own traffic`() {
        // Guards against fixing the above by breaking the happy path.
        val alicePublic = alice.getMyPublicKeyBase64()
        assertTrue(alice.establishSession("bob_peer", alicePublic))
        val sealed = alice.encryptForPeer("bob_peer", "hello bob")

        assertEquals("hello bob", alice.decryptFromPeer("bob_peer", sealed!!))
    }

    @Test
    fun `encrypting for a peer with no session fails instead of substituting a key`() {
        assertNull(
            "there is no session, so there is nothing to encrypt with",
            alice.encryptForPeer("never_met", "text")
        )
    }

    @Test
    fun `a stream key is only produced for a peer with a session`() {
        assertNull(
            "substituting the local storage key would encrypt a stream the peer " +
                "cannot read, and would hide that from the sender",
            alice.getStreamKeyForPeer("never_met")
        )
    }

    @Test
    fun `a stream key is produced for a peer with a session`() {
        val alicePublic = alice.getMyPublicKeyBase64()
        assertTrue(alice.establishSession("bob_peer", alicePublic))

        assertNotNull(
            "a peer with a session must still get a stream key",
            alice.getStreamKeyForPeer("bob_peer")
        )
    }

    @Test
    fun `an established stream key is distinct from the local storage key`() {
        val alicePublic = alice.getMyPublicKeyBase64()
        alice.establishSession("bob_peer", alicePublic)

        val streamKey = alice.getStreamKeyForPeer("bob_peer")
        assertNotNull("precondition: a session exists", streamKey)
        assertFalse(
            "if these matched, the fallback would be undetectable and messages " +
                "would be encrypted under the device key instead of the pairwise key",
            streamKey!!.encoded.contentEquals(EncryptionManager.getLocalStorageKey().encoded)
        )
    }

    /**
     * Identity must not change silently. A stored identity that will not load is
     * an error the operator needs to see, because a regenerated identity looks
     * exactly like a new device to every peer and silently breaks every session.
     */
    @Test
    fun `a corrupted stored identity surfaces an error instead of silently regenerating`() {
        val prefs = context.getSharedPreferences("basata_identity_keys_v3", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("identity_private_enc_v3", "not-valid-ciphertext")
            .putString("identity_public_b64_v3", "also-not-valid")
            .commit()

        val reloaded = PairwiseSessionManager(context)

        val error = runCatching { reloaded.getMyPublicKeyBase64() }.exceptionOrNull()
        assertNotNull(
            "loading must fail loudly rather than quietly minting a new identity",
            error
        )
    }

    @Test
    fun `an identity that has never been stored still loads cleanly`() {
        context.getSharedPreferences("basata_identity_keys_v3", Context.MODE_PRIVATE)
            .edit().clear().commit()

        val fresh = PairwiseSessionManager(context)

        assertNotNull("first run has no stored identity and must generate one", fresh.getMyPublicKeyBase64())
    }
}