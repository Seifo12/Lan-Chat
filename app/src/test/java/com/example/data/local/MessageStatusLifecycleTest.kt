package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 1.1: the message lifecycle needs a real terminal state.
 *
 * Previously a failed send was written back as SENDING, so a message that could
 * never be delivered looked like it was still going out, forever, with no way to
 * tell the user anything. QUEUED means "the peer is not reachable right now",
 * which is normal for a LAN messenger and must not be counted as an attempt.
 * FAILED means the peer was reachable and still refused the message, or the
 * attempts ran out, or something blocked it outright.
 */
class MessageStatusLifecycleTest {

    @Test
    fun `statuses include QUEUED and FAILED`() {
        val values = MessageStatus.entries.map { it.name }
        assertTrue("QUEUED is required for an unreachable peer", values.contains("QUEUED"))
        assertTrue("FAILED is required for an exhausted or blocked send", values.contains("FAILED"))
    }

    @Test
    fun `the legacy statuses are preserved`() {
        val values = MessageStatus.entries.map { it.name }
        assertTrue(values.containsAll(listOf("SENDING", "SENT", "DELIVERED", "READ")))
    }

    @Test
    fun `a queued message is distinguishable from a failed one`() {
        assertTrue(MessageStatus.QUEUED != MessageStatus.FAILED)
        assertTrue(MessageStatus.QUEUED != MessageStatus.SENDING)
        assertTrue(MessageStatus.FAILED != MessageStatus.SENDING)
    }

    @Test
    fun `an unreachable peer produces QUEUED and never FAILED`() {
        assertEquals(
            MessageStatus.QUEUED,
            MessageStatus.forSendOutcome(success = false, reachable = false, attempts = 0)
        )
        assertEquals(
            "being offline must not burn an attempt",
            MessageStatus.QUEUED,
            MessageStatus.forSendOutcome(success = false, reachable = false, attempts = 5)
        )
    }

    @Test
    fun `a successful send is SENT`() {
        assertEquals(
            MessageStatus.SENT,
            MessageStatus.forSendOutcome(success = true, reachable = true, attempts = 1)
        )
    }

    @Test
    fun `a reachable peer that keeps refusing exhausts its attempts into FAILED`() {
        assertEquals(
            "still attempts left, so keep it queued for another try",
            MessageStatus.QUEUED,
            MessageStatus.forSendOutcome(success = false, reachable = true, attempts = 4)
        )
        assertEquals(
            MessageStatus.FAILED,
            MessageStatus.forSendOutcome(success = false, reachable = true, attempts = 5)
        )
        assertEquals(
            MessageStatus.FAILED,
            MessageStatus.forSendOutcome(success = false, reachable = true, attempts = 6)
        )
    }

    @Test
    fun `a security block fails immediately without spending attempts`() {
        assertEquals(
            MessageStatus.FAILED,
            MessageStatus.forSendOutcome(success = false, reachable = true, attempts = 0, blocked = true)
        )
        assertEquals(
            MessageStatus.FAILED,
            MessageStatus.forSendOutcome(success = false, reachable = false, attempts = 1, blocked = true)
        )
    }

    /**
     * The retry button is the only path a user takes to a second attempt, so its
     * mapping has to be exact. This is the contract TcpMessagingManager.retryMessage
     * relies on, spelled out as the three cases that matter.
     */
    @Test
    fun `manual retry against a reachable peer that refuses the send is FAILED`() {
        assertEquals(
            MessageStatus.FAILED,
            MessageStatus.forSendOutcome(success = false, reachable = true, attempts = 1, blocked = true)
        )
    }

    @Test
    fun `manual retry against an unreachable peer is QUEUED`() {
        assertEquals(
            MessageStatus.QUEUED,
            MessageStatus.forSendOutcome(success = false, reachable = false, attempts = 1)
        )
    }

    @Test
    fun `manual retry that succeeds is SENT`() {
        assertEquals(
            MessageStatus.SENT,
            MessageStatus.forSendOutcome(success = true, reachable = true, attempts = 1)
        )
    }

    @Test
    fun `unreachable never consumes attempts, so a long outage cannot fail a message`() {
        repeat(50) {
            assertEquals(
                MessageStatus.QUEUED,
                MessageStatus.forSendOutcome(success = false, reachable = false, attempts = it + 1)
            )
        }
    }

    @Test
    fun `queued messages older than the replay window are not auto-sent`() {
        val now = 1_700_000_000_000L
        assertTrue(
            "a stale queue entry must not be sent when the peer reappears",
            !MessageStatus.shouldAutoSendQueued(createdAt = now - 25 * 60 * 60 * 1000L, now = now)
        )
        assertTrue(
            MessageStatus.shouldAutoSendQueued(createdAt = now - 60 * 1000L, now = now)
        )
    }
}