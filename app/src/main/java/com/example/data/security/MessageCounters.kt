package com.example.data.security

import com.example.data.local.PeerCounterDao
import com.example.data.local.PeerCounterEntity

/**
 * Phase 1.3: the persisted side of the replay window.
 *
 * [ReplayWindow] holds the decision logic in memory; this holds the state that
 * has to outlive the process, because both halves are worthless if they reset.
 *
 * The sender claims each counter with a conditional update rather than a read
 * followed by a write, so two concurrent sends cannot be handed the same number.
 * A number reused within a window would be rejected by the peer's own replay
 * window, which shows up to the user as a message that mysteriously never
 * arrives.
 */
class MessageCounters(
    private val dao: PeerCounterDao,
    private val window: Long = ReplayWindow.DEFAULT_WINDOW,
) {

    /** The highest counter accepted so far from this peer, or zero. */
    suspend fun highWaterMarkOf(peerDeviceId: String): Long =
        dao.highWaterMark(peerDeviceId) ?: 0L

    /**
     * Moves the mark forward after a packet has been authenticated. Never moves
     * it backwards, so a late packet cannot rewind it.
     */
    suspend fun raiseHighWaterMark(peerDeviceId: String, counter: Long) {
        dao.insertIfAbsent(PeerCounterEntity(peerDeviceId = peerDeviceId))
        dao.raiseHighWaterMark(peerDeviceId, counter)
    }

    /** Returns the next outgoing counter for [peerDeviceId], or null if unavailable. */
    suspend fun claimOutgoing(peerDeviceId: String): Long? {
        dao.insertIfAbsent(PeerCounterEntity(peerDeviceId = peerDeviceId))
        if (dao.incrementOutgoing(peerDeviceId) == 0) return null
        return dao.outgoingCounter(peerDeviceId)
    }

    /**
     * Decides what to do with an inbound counter, and persists the mark when it
     * moves. [alreadySeen] comes from the durable message-id record, which is the
     * only half that survives a long outage.
     */
    suspend fun observeInbound(peerDeviceId: String, counter: Long, alreadySeen: Boolean): ReplayDecision {
        val restored = dao.highWaterMark(peerDeviceId) ?: 0L
        val replayWindow = ReplayWindow(WINDOW = window, restoredHighWaterMark = restored)
        val decision = replayWindow.observe(counter, alreadySeen)
        if (decision == ReplayDecision.ACCEPT) {
            dao.insertIfAbsent(PeerCounterEntity(peerDeviceId = peerDeviceId))
            dao.raiseHighWaterMark(peerDeviceId, replayWindow.highWaterMark)
        }
        return decision
    }
}