package com.example.data.local

/**
 * Step 1.1, legacy data.
 *
 * Before this step a failed send was written back as SENDING, so every message
 * that ever failed to go out is still labelled "sending" with no send in flight.
 * The rules live here so they can be tested without a database; the startup
 * sweep applies them.
 */
object StaleSendingSweep {

    /** Older than this, with nothing in flight, the SENDING label is a lie. */
    const val STALE_AFTER_MS = 3 * 60 * 1000L

    /** A candidate row, reduced to what the rule needs. */
    data class Candidate(val status: MessageStatus, val ageMs: Long)

    fun isStale(ageMs: Long, staleAfterMs: Long = STALE_AFTER_MS): Boolean =
        ageMs > staleAfterMs

    fun isCandidate(
        status: MessageStatus,
        ageMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Boolean = status == MessageStatus.SENDING && isStale(ageMs, staleAfterMs)

    fun countStale(
        rows: List<Candidate>,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Int = rows.count { isCandidate(it.status, it.ageMs, staleAfterMs) }
}