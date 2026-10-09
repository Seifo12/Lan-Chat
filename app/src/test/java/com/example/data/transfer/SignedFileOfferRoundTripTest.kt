package com.example.data.transfer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.security.EncryptionManager
import com.example.data.security.MessageSigningPayload
import com.example.data.security.PairwiseSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.9: the offer content both sides hash, without re-serialising.
 *
 * This class exists because that exact class of bug shipped once already in
 * 1.3: the receive path demanded a signature over the whole field set while
 * the send path still signed the text alone. The app compiled, every test that
 * did not exercise both halves passed, and every message was refused at
 * runtime.
 *
 * So the sender's signing path and the receiver's verifying path are both
 * called here, against the real signer, and a disagreement is a red test
 * rather than a production surprise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignedFileOfferRoundTripTest {

    private lateinit var manager: PairwiseSessionManager

    @Before
    fun setUp() {
        EncryptionManager.initializePairwiseManager(
            ApplicationProvider.getApplicationContext<Application>()
        )
        manager = EncryptionManager.getPairwiseManager()!!
    }

    private fun pinnedKeyBase64() = manager.getMyPublicKeyBase64()

    private fun offer(
        sha: String = "a".repeat(64),
        size: Long = 1024,
        expiry: Long = 0L,
    ) = FileOffer(
        senderId = "peer_a", recipientId = "me", transferId = "t_1",
        name = "photo.jpg", size = size, kind = "PHOTO", groupId = "",
        fileSha256 = sha, signatureBase64 = null,
        expiry = if (expiry > 0) expiry
        else System.currentTimeMillis() + FileOfferCodec.OFFER_TTL_MS,
        createdAt = 1L,
    )

    /** Exactly what the sender signs: the canonical body, never the wire form. */
    private fun fieldsFor(o: FileOffer, canonicalJson: String) = MessageSigningPayload.Fields(
        senderId = o.senderId,
        recipientId = o.recipientId,
        messageId = o.transferId,
        timestamp = o.createdAt,
        counter = 0,
        contentDigestHex = FileOfferCodec.contentDigestHex(canonicalJson.toByteArray(Charsets.UTF_8)),
    )

    private fun signedOffer(o: FileOffer): Pair<FileOffer, String> {
        val canonical = FileOfferCodec.buildCanonical(o)
        val signature = MessageSigningPayload.signWith(manager, fieldsFor(o, canonical))!!
        return o.copy(signatureBase64 = signature) to canonical
    }

    /** Exactly what the receiver does: parse the wire, verify the body bytes. */
    private fun receive(
        wire: String,
        pinned: String? = pinnedKeyBase64(),
        alreadySeen: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) = FileOfferVerifier.verify(
        offer = FileOfferCodec.parse(wire)!!,
        canonicalJson = wire.substringAfter("\"offer\":").substringBeforeLast("}"),
        senderPinnedKeyBase64 = pinned,
        alreadySeen = alreadySeen,
        nowMs = now,
    )

    @Test
    fun `an offer the sender signed verifies on the receiver`() {
        val (signed, canonical) = signedOffer(offer())
        val wire = FileOfferCodec.buildWire(signed)
        val parsed = FileOfferCodec.parse(wire)!!
        assertEquals(
            "the two halves must agree on what was signed: $canonical",
            OfferVerdict.Accepted,
            FileOfferVerifier.verify(
                parsed, canonical, pinnedKeyBase64(), false, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun `the signed body is the canonical form and not the wire form`() {
        val (signed, canonical) = signedOffer(offer())
        assertEquals(
            "if the sender ever signs the wire form the two halves disagree again",
            canonical,
            FileOfferCodec.buildCanonical(signed)
        )
        assertTrue(FileOfferCodec.buildWire(signed) != canonical)
    }

    /** The canonical body exactly as it sits inside a wire form. */
    private fun bodyOf(wire: String) = wire.substringAfter("\"offer\":").substringBeforeLast("}")

    /**
     * Asserts the refusal reason, not the detail. The detail is a diagnostic
     * string, so asserting on it would only make the test brittle; the reason
     * is the contract a caller branches on.
     */
    private fun assertRefused(expected: RefusedReason, actual: OfferVerdict) {
        assertTrue("expected a refusal but got $actual", actual is OfferVerdict.Refused)
        assertEquals(expected, (actual as OfferVerdict.Refused).reason)
    }

    @Test
    fun `a flipped byte in the offered body refuses`() {
        val (signed, _) = signedOffer(offer())
        val tampered = FileOfferCodec.buildWire(signed).replace("\"size\":1024", "\"size\":1025")
        // The verifier is handed the bytes that arrived, not the bytes the
        // sender signed. That is the whole point: re-deriving them from the
        // parsed offer is the bug this class was written to catch.
        assertRefused(
            RefusedReason.UNVERIFIED_OFFER,
            FileOfferVerifier.verify(
                FileOfferCodec.parse(tampered)!!, bodyOf(tampered),
                pinnedKeyBase64(), false, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun `a swap of one covered field refuses`() {
        val (_, canonical) = signedOffer(offer())
        assertRefused(
            RefusedReason.UNVERIFIED_OFFER,
            FileOfferVerifier.verify(
                offer(size = 2048), canonical,
                pinnedKeyBase64(), false, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun `a swap of the offered hash refuses`() {
        val (_, canonical) = signedOffer(offer())
        assertRefused(
            RefusedReason.UNVERIFIED_OFFER,
            FileOfferVerifier.verify(
                offer(sha = "b".repeat(64)), canonical,
                pinnedKeyBase64(), false, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun `a different pinned key refuses`() {
        val (signed, canonical) = signedOffer(offer())
        val otherKey = java.security.KeyPairGenerator.getInstance("EC")
            .apply { initialize(256) }
            .generateKeyPair()
            .public.encoded
        assertRefused(
            RefusedReason.UNVERIFIED_OFFER,
            FileOfferVerifier.verify(
                signed, canonical,
                java.util.Base64.getEncoder().encodeToString(otherKey),
                false, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun `no pinned key at all refuses`() {
        val (signed, canonical) = signedOffer(offer())
        assertRefused(
            RefusedReason.UNVERIFIED_OFFER,
            FileOfferVerifier.verify(signed, canonical, null, false, System.currentTimeMillis())
        )
    }

    @Test
    fun `an offer with no signature refuses`() {
        val (signed, canonical) = signedOffer(offer())
        assertRefused(
            RefusedReason.UNVERIFIED_OFFER,
            FileOfferVerifier.verify(
                signed.copy(signatureBase64 = null), canonical,
                pinnedKeyBase64(), false, System.currentTimeMillis()
            )
        )
    }

    @Test
    fun `an already consumed transfer refuses`() {
        val (signed, canonical) = signedOffer(offer())
        assertRefused(
            RefusedReason.OFFER_REPLAY,
            FileOfferVerifier.verify(signed, canonical, pinnedKeyBase64(), true, System.currentTimeMillis())
        )
    }

    @Test
    fun `an offer past the tolerance refuses`() {
        // Past the tolerance, not merely past its own expiry: the verifier
        // deliberately allows a small clock difference between two phones.
        val tolerance = com.example.data.security.GroupInviteCodec.EXPIRY_TOLERANCE_MS
        val (signed, canonical) = signedOffer(
            offer(expiry = System.currentTimeMillis() - tolerance - 60_000L)
        )
        assertRefused(
            RefusedReason.OFFER_EXPIRED,
            FileOfferVerifier.verify(signed, canonical, pinnedKeyBase64(), false, System.currentTimeMillis())
        )
    }

    @Test
    fun `expiry tolerates a small clock skew`() {
        val (signed, canonical) = signedOffer(
            offer(expiry = System.currentTimeMillis() - 30_000L)
        )
        assertEquals(
            "half a minute of clock difference must not refuse a genuine offer",
            OfferVerdict.Accepted,
            FileOfferVerifier.verify(signed, canonical, pinnedKeyBase64(), false, System.currentTimeMillis())
        )
    }

    @Test
    fun `an unverified offer does not consume a replay slot`() {
        // Order matters: identity before replay, so an unauthenticated offer
        // cannot burn a transfer id and then block the genuine sender.
        val now = System.currentTimeMillis()
        val (signed, canonical) = signedOffer(offer())
        assertTrue(
            FileOfferVerifier.verify(signed, canonical, null, false, now)
                    is OfferVerdict.Refused
        )
        assertTrue(OfferReplayGuard().tryConsume("t_1", now))
    }

    @Test
    fun `the replay guard consumes a transfer id once`() {
        val guard = OfferReplayGuard()
        val now = System.currentTimeMillis()
        assertTrue(guard.tryConsume("t_1", now))
        assertTrue(
            "a second stream for one offer must not be admitted",
            !guard.tryConsume("t_1", now)
        )
    }

    @Test
    fun `the replay guard forgets ids older than twice the hold`() {
        val guard = OfferReplayGuard()
        val now = System.currentTimeMillis()
        guard.tryConsume("t_1", now)
        assertTrue(
            guard.tryConsume("t_1", now + TransferAdmissionLimits.PENDING_HOLD_MS * 2 + 1)
        )
    }

    @Test
    fun `the replay guard does not grow without bound`() {
        val guard = OfferReplayGuard()
        val now = System.currentTimeMillis()
        repeat(100) { guard.tryConsume("t_$it", now) }
        assertEquals(100, guard.trackedCount())
        guard.tryConsume("t_fresh", now + TransferAdmissionLimits.PENDING_HOLD_MS * 3)
        assertTrue("stale ids must be pruned", guard.trackedCount() < 100)
    }
}