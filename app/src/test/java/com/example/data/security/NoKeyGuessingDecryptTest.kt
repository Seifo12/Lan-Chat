package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.4, audit finding C3.
 *
 * `decryptAny` tried every session key the device held. That is not decryption,
 * it is guessing: a payload addressed to one peer could be opened with another
 * peer's key, so nothing about a `PENC:` blob bound it to its recipient. The
 * generic decrypt path went through it, so anything routed that way was unbound.
 *
 * A payload is now only opened with the key of the peer it names. If that peer is
 * not known, it stays sealed.
 *
 * These tests fail against the old behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoKeyGuessingDecryptTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var alice: PairwiseSessionManager
    private lateinit var bob: PairwiseSessionManager
    private lateinit var carol: PairwiseSessionManager

    @Before
    fun setUp() {
        alice = PairwiseSessionManager(context)
        bob = PairwiseSessionManager(context)
        carol = PairwiseSessionManager(context)
        // Each party holds a session for its counterpart, as paired contacts do.
        assertNotNull(alice.establishSession("bob", bob.getMyPublicKeyBase64()))
        assertNotNull(bob.establishSession("alice", alice.getMyPublicKeyBase64()))
        assertNotNull(carol.establishSession("alice", alice.getMyPublicKeyBase64()))
    }

    @Test
    fun `a payload sealed for one peer is not opened for another`() {
        val sealed = alice.encryptForPeer("bob", "for bob only")
        assertNotNull("precondition: a paired peer can be sealed to", sealed)

        assertNull(
            "carol has a session with alice too, and must still not open bob's payload",
            alice.decryptFromPeer("carol", sealed!!)
        )
    }

    @Test
    fun `the intended peer can still open its own payload`() {
        val sealed = alice.encryptForPeer("bob", "for bob only")

        assertEquals("for bob only", alice.decryptFromPeer("bob", sealed!!))
    }

    @Test
    fun `there is no generic any key decrypt left to call`() {
        // The removal itself is the property. If a future change reintroduces a
        // try-every-key helper, this fails and forces the question to be answered.
        val methods = PairwiseSessionManager::class.java.declaredMethods
            .map { it.name }
        assertEquals(
            "decryptAny must not come back",
            listOf<String>(),
            methods.filter { it.contains("ecryptAny", ignoreCase = true) }
        )
    }

    @Test
    fun `a payload for a peer we have never met stays sealed`() {
        val sealed = alice.encryptForPeer("bob", "hello")

        assertNull(
            "an unknown peer has no key, so the payload stays sealed",
            alice.decryptFromPeer("stranger", sealed!!)
        )
    }

    @Test
    fun `a payload whose sender field was swapped does not open`() {
        val sealed = alice.encryptForPeer("bob", "hello")
        // Swapping who the payload is claimed to be for must not help an attacker:
        // the recipient is inside the sealed material, so it cannot be re-pointed.
        assertNull(alice.decryptFromPeer("stranger", sealed!!))
    }
}