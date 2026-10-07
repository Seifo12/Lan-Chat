package com.example.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferRateLimiterTest {

    private var clock = 1_000_000L

    private fun limiter() = TransferRateLimiter { clock }

    @Test
    fun `twenty offers inside the window are allowed and the twenty first is not`() {
        val limiter = limiter()
        repeat(20) {
            assertEquals(
                "request $it should be allowed",
                RateDecision.ALLOW,
                limiter.record("peer_a", "10.0.0.1", 1024, autoAccepted = true)
            )
        }
        assertEquals(RateDecision.REFUSE, limiter.record("peer_a", "10.0.0.1", 1024, true))
    }

    @Test
    fun `a refused offer still spends a request so the limit cannot be evaded`() {
        val limiter = limiter()
        repeat(20) { limiter.record("peer_a", "10.0.0.1", 0, autoAccepted = false) }
        assertEquals(
            "an offer nobody would accept must not be free",
            RateDecision.REFUSE,
            limiter.record("peer_a", "10.0.0.1", 0, autoAccepted = false)
        )
    }

    @Test
    fun `the window slides`() {
        val limiter = limiter()
        repeat(20) { limiter.record("peer_a", "10.0.0.1", 0, autoAccepted = false) }
        assertEquals(RateDecision.REFUSE, limiter.record("peer_a", "10.0.0.1", 0, false))
        clock += TransferAdmissionLimits.RATE_WINDOW_MS + 1
        assertEquals(RateDecision.ALLOW, limiter.record("peer_a", "10.0.0.1", 0, false))
    }

    @Test
    fun `peers are limited independently`() {
        val limiter = limiter()
        repeat(20) { limiter.record("peer_a", "10.0.0.1", 0, autoAccepted = false) }
        assertEquals(RateDecision.ALLOW, limiter.record("peer_b", "10.0.0.2", 0, false))
    }

    @Test
    fun `the key is peer id and ip so a new address does not reset the budget`() {
        val limiter = limiter()
        repeat(20) { limiter.record("peer_a", "10.0.0.1", 0, autoAccepted = false) }
        assertEquals(
            "changing address must not buy a fresh budget",
            RateDecision.REFUSE,
            limiter.record("peer_a", "10.0.0.2", 0, autoAccepted = false)
        )
    }

    @Test
    fun `the auto accepted byte budget applies`() {
        val limiter = limiter()
        val mb = 1024L * 1024
        // Ten 20 MB auto-accepts land exactly on the 200 MB budget, so the
        // eleventh byte over is refused while the request count is still fine.
        repeat(10) {
            assertEquals(
                RateDecision.ALLOW,
                limiter.record("peer_a", "10.0.0.1", 20L * mb, autoAccepted = true)
            )
        }
        assertEquals(
            RateDecision.REFUSE,
            limiter.record("peer_a", "10.0.0.1", 1, autoAccepted = true)
        )
    }

    @Test
    fun `the byte budget decays with the window`() {
        val limiter = limiter()
        val mb = 1024L * 1024
        repeat(10) { limiter.record("peer_a", "10.0.0.1", 20L * mb, autoAccepted = true) }
        assertEquals(
            RateDecision.REFUSE,
            limiter.record("peer_a", "10.0.0.1", 1, autoAccepted = true)
        )
        clock += TransferAdmissionLimits.RATE_WINDOW_MS + 1
        assertEquals(
            "old bytes must not count against a new window",
            RateDecision.ALLOW,
            limiter.record("peer_a", "10.0.0.1", 20L * mb, autoAccepted = true)
        )
    }

    @Test
    fun `a user accepted transfer does not spend the auto accepted byte budget`() {
        val limiter = limiter()
        val gb = 1024L * 1024 * 1024
        repeat(5) {
            assertEquals(
                "the user already approved this file",
                RateDecision.ALLOW,
                limiter.record("peer_a", "10.0.0.1", gb, autoAccepted = false)
            )
        }
    }

    @Test
    fun `concurrency is counted per peer and released`() {
        val limiter = limiter()
        assertEquals(0, limiter.concurrentFrom("peer_a"))
        limiter.acquire("peer_a")
        limiter.acquire("peer_a")
        assertEquals(2, limiter.concurrentFrom("peer_a"))
        limiter.release("peer_a")
        assertEquals(1, limiter.concurrentFrom("peer_a"))
    }

    @Test
    fun `entries are evicted by age so the map cannot grow forever`() {
        val limiter = limiter()
        repeat(50) { limiter.record("peer_$it", "10.0.0.$it", 0, autoAccepted = false) }
        clock += TransferAdmissionLimits.RATE_WINDOW_MS * 10
        assertEquals(0, limiter.trackedPeerCount())
    }
}