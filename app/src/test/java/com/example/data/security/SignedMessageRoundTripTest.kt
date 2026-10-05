package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.network.ProtocolVersion
import com.example.data.network.NetworkPacket
import com.example.data.network.TextMessagePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.3: the two ends have to agree.
 *
 * The failure this guards against is not subtle. During development the receive
 * path was switched to demand a signature over the whole field set while the send
 * path still produced a signature over the text alone. Nothing threw, the app
 * compiled, and every single message was refused at runtime with no way to tell
 * from the outside that the two halves had drifted.
 *
 * These tests sign exactly as the sender does and verify exactly as the receiver
 * does, over a real packet that has been serialised and parsed, so the wire
 * encoding is covered rather than just the two functions in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignedMessageRoundTripTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var sender: PairwiseSessionManager
    private lateinit var receiver: PairwiseSessionManager

    @Before
    fun setUp() {
        EncryptionManager.initializePairwiseManager(context)
        sender = PairwiseSessionManager(context)
        receiver = PairwiseSessionManager(context)
        assertTrue(receiver.establishSession("peer_b", receiver.getMyPublicKeyBase64()))
    }

    /** Signs as TcpMessagingManager.signForRecipient does. */
    private fun signedPacketOnWire(text: String, counter: Long, messageId: String): TextMessagePacket {
        val timestamp = 1_700_000_000_000L
        val fields = MessageSigningPayload.Fields(
            senderId = sender.getMyPublicKeyBase64().let { "me" },
            recipientId = "peer_b",
            messageId = messageId,
            timestamp = timestamp,
            counter = counter,
            contentDigestHex = MessageSigningPayload.digestHex(text.toByteArray()),
        )
        // Signed with this test's own manager rather than the process-wide one.
        // EncryptionManager holds a static manager, and under Robolectric the
        // preferences it read in an earlier test class can differ from this one's,
        // so signing through it would verify against a key the test never held.
        val sig = MessageSigningPayload.signWith(sender, fields)
        return TextMessagePacket(
            messageId = messageId, senderId = "me", senderName = "Me",
            recipientId = "peer_b", text = text, timestamp = timestamp,
            signatureBase64 = sig,
            counter = counter,
            protocolVersion = ProtocolVersion.CURRENT,
        )
    }

    private fun verify(
        packet: TextMessagePacket,
        peerKey: String?,
        decision: ReplayDecision = ReplayDecision.ACCEPT,
    ) = InboundMessageVerifier.verify(
        candidate = InboundMessageVerifier.Candidate(
            senderId = packet.senderId,
            recipientId = "peer_b",
            messageId = packet.messageId,
            timestamp = packet.timestamp,
            counter = packet.counter,
            protocolVersion = packet.protocolVersion,
            signatureBase64 = packet.signatureBase64,
            contentDigestHex = MessageSigningPayload.digestHex(
                packet.text.toByteArray()
            ),
            alreadySeen = false,
        ),
        senderPublicKeyBase64 = peerKey,
        replayDecision = decision,
    )

    @Test
    fun `a signed message survives serialisation and verifies`() {
        val packet = signedPacketOnWire("hello over the air", counter = 1, messageId = "msg_1")

        // Round-trip through JSON exactly as it goes on the wire. Parsing goes
        // through the dispatcher, which is how the app actually reads a packet.
        val parsed = NetworkPacket.fromJson(packet.toJson()) as? TextMessagePacket

        assertTrue("the packet must survive the wire encoding", parsed != null)
        assertEquals(1L, parsed!!.counter)
        assertEquals(ProtocolVersion.CURRENT, parsed.protocolVersion)
        assertTrue(
            "the signature the sender produced must verify after the round trip, got " +
                verify(parsed, peerKey = sender.getMyPublicKeyBase64()),
            verify(parsed, peerKey = sender.getMyPublicKeyBase64()) is InboundVerdict.Accepted
        )
    }

    @Test
    fun `a message whose body was changed after signing is refused`() {
        val packet = signedPacketOnWire("the original text", counter = 1, messageId = "msg_2")
        val tampered = packet.copy(text = "the replaced text")

        val verdict = verify(tampered, peerKey = sender.getMyPublicKeyBase64())

        assertTrue("expected a refusal, got $verdict", verdict is InboundVerdict.BadSignature)
    }

    @Test
    fun `a message with the counter rewritten is refused`() {
        val packet = signedPacketOnWire("text", counter = 1, messageId = "msg_3")
        val tampered = packet.copy(counter = 9999)

        val verdict = verify(tampered, peerKey = sender.getMyPublicKeyBase64())

        assertTrue("expected a refusal, got $verdict", verdict is InboundVerdict.BadSignature)
    }

    @Test
    fun `a message re-addressed to another recipient is refused`() {
        val packet = signedPacketOnWire("text", counter = 1, messageId = "msg_4")

        // Verify with a different recipient in the covered set, which is what a
        // captured packet replayed at a third party would look like.
        val verdict = InboundMessageVerifier.verify(
            candidate = InboundMessageVerifier.Candidate(
                senderId = packet.senderId,
                recipientId = "peer_c",
                messageId = packet.messageId,
                timestamp = packet.timestamp,
                counter = packet.counter,
                protocolVersion = packet.protocolVersion,
                signatureBase64 = packet.signatureBase64,
                contentDigestHex = MessageSigningPayload.digestHex(
                    packet.text.toByteArray()
                ),
                alreadySeen = false,
            ),
            senderPublicKeyBase64 = sender.getMyPublicKeyBase64(),
            replayDecision = ReplayDecision.ACCEPT,
        )

        assertTrue("expected a refusal, got $verdict", verdict is InboundVerdict.BadSignature)
    }

    @Test
    fun `an unsigned message is refused`() {
        val packet = signedPacketOnWire("text", counter = 1, messageId = "msg_5")
            .copy(signatureBase64 = null)

        val verdict = verify(packet, peerKey = sender.getMyPublicKeyBase64())

        assertEquals(InboundVerdict.MissingSignature, verdict)
    }

    @Test
    fun `a message from a sender we hold no key for is refused`() {
        val packet = signedPacketOnWire("text", counter = 1, messageId = "msg_6")

        val verdict = verify(packet, peerKey = null)

        assertEquals(InboundVerdict.UnknownSender, verdict)
    }

    @Test
    fun `a message from a build older than the minimum is refused and named`() {
        val packet = signedPacketOnWire("text", counter = 1, messageId = "msg_7")
            .copy(protocolVersion = 1)

        val verdict = verify(packet, peerKey = sender.getMyPublicKeyBase64())

        assertEquals(InboundVerdict.UnsupportedProtocol(1), verdict)
    }

    @Test
    fun `a duplicate is refused even though the signature is good`() {
        val packet = signedPacketOnWire("text", counter = 1, messageId = "msg_8")

        val verdict = verify(
            packet,
            peerKey = sender.getMyPublicKeyBase64(),
            decision = ReplayDecision.DUPLICATE,
        )

        assertEquals(InboundVerdict.Duplicate, verdict)
    }

    @Test
    fun `a counter below the window is refused even though the signature is good`() {
        val packet = signedPacketOnWire("text", counter = 1, messageId = "msg_9")

        val verdict = verify(
            packet,
            peerKey = sender.getMyPublicKeyBase64(),
            decision = ReplayDecision.TOO_OLD,
        )

        assertEquals(InboundVerdict.TooOld, verdict)
    }

    @Test
    fun `a message signed by a different key is refused`() {
        val fields = MessageSigningPayload.Fields(
            senderId = "me", recipientId = "peer_b", messageId = "msg_10",
            timestamp = 1_700_000_000_000L, counter = 1,
            contentDigestHex = MessageSigningPayload.digestHex("text".toByteArray()),
        )
        // A freshly generated key, so this is a genuinely different identity and
        // not another view of the shared test preference file.
        val impostor = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val dsa = java.security.Signature.getInstance("SHA256withECDSA")
        dsa.initSign(impostor.private)
        dsa.update(MessageSigningPayload.build(fields).toByteArray(Charsets.UTF_8))
        val forged = java.util.Base64.getEncoder().encodeToString(dsa.sign())

        val packet = TextMessagePacket(
            messageId = "msg_10", senderId = "me", senderName = "Me",
            recipientId = "peer_b", text = "text", timestamp = 1_700_000_000_000L,
            signatureBase64 = forged,
            counter = 1, protocolVersion = ProtocolVersion.CURRENT,
        )

        val verdict = verify(packet, peerKey = sender.getMyPublicKeyBase64())

        assertTrue("expected a refusal, got $verdict", verdict is InboundVerdict.BadSignature)
    }
}