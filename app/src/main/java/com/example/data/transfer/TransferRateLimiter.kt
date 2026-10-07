package com.example.data.transfer

import java.util.ArrayDeque

/**
 * Per-peer request and byte accounting over a sliding window, in memory.
 *
 * Deliberately not persisted. A restart is not the attack this defends
 * against, and a persisted window would be a denial-of-service on this
 * device's own storage. Entries are evicted by age so the map cannot grow
 * without bound.
 *
 * Each offer is recorded against the peer id and the peer ip separately, and
 * either budget being over is a refusal. Keying on the id alone would let a
 * peer buy a fresh budget by changing address, and keying on the address alone
 * would let one peer behind a NAT exhaust another's budget, so both are
 * checked. The remaining asymmetry is fail-closed on purpose: sharing an
 * address means sharing the address budget, which can refuse a legitimate
 * transfer but can never admit a malicious one.
 */
class TransferRateLimiter(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    private data class Entry(
        val requests: ArrayDeque<Long> = ArrayDeque(),
        val byteEvents: ArrayDeque<Pair<Long, Long>> = ArrayDeque(),
        var autoBytes: Long = 0L,
    )

    private val byId = HashMap<String, Entry>()
    private val byIp = HashMap<String, Entry>()
    private val concurrency = HashMap<String, Int>()

    private fun windowMs() = TransferAdmissionLimits.RATE_WINDOW_MS

    private fun prune(entry: Entry, cutoff: Long) {
        while (entry.requests.isNotEmpty() && entry.requests.peekFirst() <= cutoff) {
            entry.requests.pollFirst()
        }
        while (entry.byteEvents.isNotEmpty() && entry.byteEvents.peekFirst().first <= cutoff) {
            entry.autoBytes -= entry.byteEvents.pollFirst().second
        }
    }

    private fun evict() {
        val cutoff = nowMs() - windowMs()
        for (map in listOf(byId, byIp)) {
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value
                prune(e, cutoff)
                if (e.requests.isEmpty() && e.autoBytes <= 0L) it.remove()
            }
        }
    }

    /**
     * Records one offer and decides whether it is within budget.
     *
     * The request is always counted in both buckets, even when the answer is
     * REFUSE: an offer nobody would accept still cost the parse and the
     * lookup, so making it free would invite offers made purely to burn budget.
     * Only auto-accepted bytes consume the byte budget, and they decay with
     * the window like everything else, otherwise a peer that sent 200 MB a
     * year ago would be over budget today.
     */
    fun record(peerId: String, peerIp: String, sizeBytes: Long, autoAccepted: Boolean): RateDecision {
        evict()
        val now = nowMs()
        val idEntry = byId.getOrPut(peerId) { Entry() }
        val ipEntry = byIp.getOrPut(peerIp) { Entry() }
        for (entry in listOf(idEntry, ipEntry)) {
            entry.requests.addLast(now)
            if (autoAccepted) {
                entry.byteEvents.addLast(now to sizeBytes)
                entry.autoBytes += sizeBytes
            }
        }
        val over = ::overBudget
        return if (over(idEntry) || over(ipEntry)) RateDecision.REFUSE else RateDecision.ALLOW
    }

    private fun overBudget(entry: Entry): Boolean =
        entry.requests.size > TransferAdmissionLimits.RATE_MAX_REQUESTS ||
            entry.autoBytes > TransferAdmissionLimits.RATE_MAX_AUTOBYTES

    fun concurrentFrom(peerId: String): Int = concurrency[peerId] ?: 0

    fun acquire(peerId: String) {
        concurrency[peerId] = concurrentFrom(peerId) + 1
    }

    fun release(peerId: String) {
        val current = concurrency[peerId] ?: return
        if (current <= 1) concurrency.remove(peerId) else concurrency[peerId] = current - 1
    }

    /** Test and diagnostics hook; the size of the live map. */
    fun trackedPeerCount(): Int {
        evict()
        return byId.size
    }
}
