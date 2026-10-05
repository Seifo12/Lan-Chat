package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 1.1, legacy data.
 *
 * Before this step a send that failed was written back as SENDING, so every
 * message that ever failed to go out is sitting in the database labelled
 * "sending" with no send in flight. Those rows would stay that way forever. The
 * startup sweep moves the stale ones to QUEUED, which tells the truth: we are
 * waiting for the peer, and it will be retried.
 *
 * The sweep must not touch a row whose send is genuinely in flight, so the
 * staleness test is deliberately conservative.
 */
class StaleSendingSweepTest {

    /** Older than this and nothing is in flight, so the label is a lie. */
    private val staleAfterMs = 3 * 60 * 1000L

    @Test
    fun `a fresh SENDING row is left alone because a send may be in flight`() {
        assertFalse(StaleSendingSweep.isStale(ageMs = 1_000L, staleAfterMs = staleAfterMs))
    }

    @Test
    fun `a SENDING row older than the window is stale`() {
        assertTrue(StaleSendingSweep.isStale(ageMs = staleAfterMs + 1, staleAfterMs = staleAfterMs))
    }

    @Test
    fun `a row exactly at the boundary is not yet stale`() {
        assertFalse(StaleSendingSweep.isStale(ageMs = staleAfterMs, staleAfterMs = staleAfterMs))
    }

    @Test
    fun `only SENDING rows are candidates`() {
        for (status in listOf(
            MessageStatus.SENT,
            MessageStatus.DELIVERED,
            MessageStatus.READ,
            MessageStatus.QUEUED,
            MessageStatus.FAILED,
        )) {
            assertFalse(
                "only SENDING can be a stale legacy row",
                StaleSendingSweep.isCandidate(status, ageMs = staleAfterMs + 1, staleAfterMs = staleAfterMs)
            )
        }
        assertTrue(
            StaleSendingSweep.isCandidate(MessageStatus.SENDING, staleAfterMs + 1, staleAfterMs)
        )
    }

    @Test
    fun `the sweep reports how many rows it would move`() {
        val rows = listOf(
            candidate(MessageStatus.SENDING, staleAfterMs + 10_000),
            candidate(MessageStatus.SENDING, 500),                  // in flight
            candidate(MessageStatus.SENDING, staleAfterMs + 1),    // just over
            candidate(MessageStatus.SENT, staleAfterMs + 10_000),  // not SENDING
        )
        assertEquals(2, StaleSendingSweep.countStale(rows, staleAfterMs))
    }

    @Test
    fun `an empty list moves nothing`() {
        assertEquals(0, StaleSendingSweep.countStale(emptyList(), staleAfterMs))
    }

    private fun candidate(status: MessageStatus, ageMs: Long) =
        StaleSendingSweep.Candidate(status, ageMs)
}