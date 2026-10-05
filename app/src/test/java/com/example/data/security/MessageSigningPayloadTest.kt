package com.example.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.3, audit finding C1.
 *
 * Until now a message signature covered only the message text, so a valid
 * signature could be lifted off one message and presented on another. The
 * covered set has to pin who sent it, who it was for, which message it is, when
 * it claims to be from, its place in the sender's sequence, and the content
 * itself. The counter must be inside the covered set, because a counter that is
 * not signed can be stripped and rewritten to look like a fresh message.
 *
 * The domain prefix stops a message signature being reinterpreted as some other
 * kind of signature, such as a TLS certificate proof.
 *
 * Both ends build this payload from the same function, which is the point: if the
 * sender and the receiver ever disagree by one byte, nothing verifies and every
 * message is dropped. The tests below are what keep that true.
 */
class MessageSigningPayloadTest {

    private val message = SignedMessageFields(
        senderId = "peer_a",
        recipientId = "peer_b",
        messageId = "msg_1",
        timestamp = 1_700_000_000_000L,
        counter = 7,
        contentDigestHex = "ab".repeat(32),
    )

    @Test
    fun `the payload starts with the domain prefix`() {
        assertTrue(
            "a signature must not be reusable as another kind of signature",
            MessageSigningPayload.build(message).startsWith(
                MessageSigningPayload.DOMAIN_PREFIX + "\n"
            )
        )
    }

    @Test
    fun `every covered field appears in the payload`() {
        val payload = MessageSigningPayload.build(message)
        for (value in listOf(
            message.senderId, message.recipientId, message.messageId,
            message.timestamp.toString(), message.counter.toString(),
            message.contentDigestHex,
        )) {
            assertTrue("payload is missing $value: $payload", payload.contains(value))
        }
    }

    @Test
    fun `the same fields always produce the same payload`() {
        assertEquals(MessageSigningPayload.build(message), MessageSigningPayload.build(message))
    }

    @Test
    fun `changing the counter changes the payload`() {
        val tampered = message.copy(counter = 8)
        assertNotEquals(
            "an unsigned counter could be rewritten to defeat the replay window",
            MessageSigningPayload.build(message),
            MessageSigningPayload.build(tampered)
        )
    }

    @Test
    fun `changing the recipient changes the payload`() {
        assertNotEquals(
            "a signature lifted from one recipient onto another must not verify",
            MessageSigningPayload.build(message),
            MessageSigningPayload.build(message.copy(recipientId = "peer_c"))
        )
    }

    @Test
    fun `changing the sender changes the payload`() {
        assertNotEquals(
            MessageSigningPayload.build(message),
            MessageSigningPayload.build(message.copy(senderId = "peer_x"))
        )
    }

    @Test
    fun `changing the message id changes the payload`() {
        assertNotEquals(
            MessageSigningPayload.build(message),
            MessageSigningPayload.build(message.copy(messageId = "msg_2"))
        )
    }

    @Test
    fun `changing the timestamp changes the payload`() {
        assertNotEquals(
            MessageSigningPayload.build(message),
            MessageSigningPayload.build(message.copy(timestamp = message.timestamp + 1))
        )
    }

    @Test
    fun `changing the content changes the payload`() {
        assertNotEquals(
            "swapping the body while keeping the id must not verify",
            MessageSigningPayload.build(message),
            MessageSigningPayload.build(message.copy(contentDigestHex = "cd".repeat(32)))
        )
    }

    @Test
    fun `fields cannot be shifted across the separators`() {
        // If the fields were joined without separators, moving a character from
        // one field to the next would produce the same payload.
        val a = message.copy(senderId = "ab", recipientId = "c")
        val b = message.copy(senderId = "a", recipientId = "bc")
        assertNotEquals(
            "the encoding must be unambiguous, not a bare concatenation",
            MessageSigningPayload.build(a),
            MessageSigningPayload.build(b)
        )
    }

    @Test
    fun `the digest of content is stable and content dependent`() {
        assertEquals(
            MessageSigningPayload.digestHex("hello".toByteArray()),
            MessageSigningPayload.digestHex("hello".toByteArray())
        )
        assertNotEquals(
            MessageSigningPayload.digestHex("hello".toByteArray()),
            MessageSigningPayload.digestHex("hellp".toByteArray())
        )
    }
}

/**
 * Phase 1.3, from R4: a monotonic counter on its own breaks on the mesh because
 * delivery order varies, so the receiver keeps a high-water mark and tolerates
 * bounded lateness below it rather than demanding strictly increasing counters.
 */
class ReplayWindowTest {

    private val window = ReplayWindow(WINDOW = 64)

    @Test
    fun `a counter above the mark is accepted and raises the mark`() {
        assertEquals(ReplayDecision.ACCEPT, window.observe(counter = 1, alreadySeen = false))
        assertEquals(1L, window.highWaterMark)

        assertEquals(ReplayDecision.ACCEPT, window.observe(counter = 2, alreadySeen = false))
        assertEquals(2L, window.highWaterMark)
    }

    @Test
    fun `a counter already recorded is a duplicate`() {
        window.observe(counter = 5, alreadySeen = false)
        assertEquals(
            ReplayDecision.DUPLICATE,
            window.observe(counter = 5, alreadySeen = true)
        )
    }

    @Test
    fun `a late counter inside the window is tolerated`() {
        window.observe(counter = 100, alreadySeen = false)
        assertEquals(
            "mesh delivery reorders, so a late packet inside the window must be accepted",
            ReplayDecision.ACCEPT,
            window.observe(counter = 90, alreadySeen = false)
        )
    }

    @Test
    fun `a counter far below the mark is refused as too old`() {
        window.observe(counter = 1000, alreadySeen = false)
        assertEquals(
            ReplayDecision.TOO_OLD,
            window.observe(counter = 1000 - (64 + 10), alreadySeen = false)
        )
    }

    @Test
    fun `a late packet that is already recorded is still a duplicate`() {
        window.observe(counter = 100, alreadySeen = false)
        assertEquals(
            ReplayDecision.DUPLICATE,
            window.observe(counter = 99, alreadySeen = true)
        )
    }

    @Test
    fun `the window does not move the mark backwards on a late packet`() {
        window.observe(counter = 100, alreadySeen = false)
        window.observe(counter = 50, alreadySeen = false)
        assertEquals(
            "a late packet must not rewind the high-water mark",
            100L,
            window.highWaterMark
        )
    }

    @Test
    fun `a restored mark behaves like one built up in memory`() {
        val restored = ReplayWindow(WINDOW = 64, restoredHighWaterMark = 500)
        assertEquals(ReplayDecision.ACCEPT, restored.observe(counter = 501, alreadySeen = false))
        assertEquals(
            ReplayDecision.TOO_OLD,
            restored.observe(counter = 500 - (64 + 1), alreadySeen = false)
        )
    }

    @Test
    fun `a non positive counter is refused`() {
        assertEquals(
            ReplayDecision.TOO_OLD,
            window.observe(counter = 0, alreadySeen = false)
        )
    }
}