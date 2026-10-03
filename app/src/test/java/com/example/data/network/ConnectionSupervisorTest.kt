package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSupervisorTest {

    private fun supervisor() = ConnectionSupervisor(
        clock = { 0L },
        baseDelayMs = 1_000L,
        maxDelayMs = 30_000L,
        maxFastAttempts = 8,
        slowRetryMs = 120_000L,
    )

    @Test
    fun `a connected peer reports CONNECTED`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnectRequested("d1", "EP1")
        s.onConnected("d1", "EP1")
        assertEquals(ConnectionPhase.CONNECTED, s.phaseOf("d1"))
    }

    @Test
    fun `discovery alone reports DISCOVERING`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        assertEquals(ConnectionPhase.DISCOVERING, s.phaseOf("d1"))
    }

    @Test
    fun `a request in flight reports CONNECTING`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnectRequested("d1", "EP1")
        assertEquals(ConnectionPhase.CONNECTING, s.phaseOf("d1"))
    }

    @Test
    fun `an unknown peer starts IDLE`() {
        assertEquals(ConnectionPhase.IDLE, supervisor().phaseOf("nobody"))
    }

    @Test
    fun `disconnect schedules a retry and reports RETRYING`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnected("d1", "EP1")
        s.onDisconnected("d1", "binder death")
        assertEquals(ConnectionPhase.RETRYING, s.phaseOf("d1"))
        assertTrue(s.dueForReconnect(1_000L).any { it.deviceId == "d1" })
    }

    @Test
    fun `a disconnect does not fire an unbounded retry storm`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnected("d1", "EP1")
        s.onDisconnected("d1", "d")
        // The first retry is not due until the backoff elapses, so a peer that
        // is still down cannot be re-requested in a tight loop.
        assertTrue(s.dueForReconnect(0L).none { it.deviceId == "d1" })
    }

    @Test
    fun `backoff grows exponentially then caps`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        val delays = mutableListOf<Long>()
        repeat(7) {
            s.onFailure("d1", "timeout")
            delays += s.nextDelayMsForTest("d1")
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `after max fast attempts the peer keeps retrying slowly rather than giving up`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        repeat(8) { s.onFailure("d1", "timeout") }
        assertFalse(s.isGivingUp("d1"))
        assertEquals(ConnectionPhase.RETRYING, s.phaseOf("d1"))
        assertEquals(120_000L, s.nextDelayMsForTest("d1"))
    }

    @Test
    fun `a successful connect resets the attempt counter`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        repeat(3) { s.onFailure("d1", "timeout") }
        s.onConnected("d1", "EP1")
        s.onDisconnected("d1", "dropped")
        val due = s.dueForReconnect(1_000L).first { it.deviceId == "d1" }
        assertEquals(1, due.attempt)
    }

    @Test
    fun `endpoint lost schedules a reconnect using the last known endpoint`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnected("d1", "EP1")
        s.onEndpointLost("d1")
        val due = s.dueForReconnect(1_000L).first { it.deviceId == "d1" }
        assertEquals("EP1", due.endpointId)
    }

    @Test
    fun `a lost endpoint is not marked as connected`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnected("d1", "EP1")
        s.onEndpointLost("d1")
        assertEquals(ConnectionPhase.RETRYING, s.phaseOf("d1"))
    }

    @Test
    fun `a peer once known stays known after a rediscovery as a stranger`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onDiscovered("d1", "EP2", isKnown = false)
        assertTrue(s.dueForReconnect(0L).isEmpty())
        s.onFailure("d1", "t")
        assertTrue(s.dueForReconnect(1_000L).first { it.deviceId == "d1" }.isKnown)
    }

    @Test
    fun `phases map exposes every tracked peer`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onDiscovered("d2", "EP2", isKnown = false)
        assertEquals(2, s.phases.value.size)
    }

    @Test
    fun `phases map reflects a state change`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnected("d1", "EP1")
        assertEquals(ConnectionPhase.CONNECTED, s.phases.value["d1"])
    }

    @Test
    fun `a teardown for an unknown peer is ignored instead of crashing`() {
        val s = supervisor()
        s.onDisconnected("ghost", "never seen")
        s.onEndpointLost("ghost")
        assertEquals(0, s.phases.value.size)
    }

    @Test
    fun `a failed outbound request is retried instead of hanging in CONNECTING`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnectRequested("d1", "EP1")
        // The request failed before any lifecycle callback fired, so the only
        // signal is this explicit failure notification.
        s.onFailure("d1", "request failed")
        assertEquals(ConnectionPhase.RETRYING, s.phaseOf("d1"))
        assertTrue(s.dueForReconnect(1_000L).any { it.deviceId == "d1" })
    }

    @Test
    fun `a thrown outbound request is retried too`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onConnectRequested("d1", "EP1")
        s.onFailure("d1", "request threw")
        assertEquals(ConnectionPhase.RETRYING, s.phaseOf("d1"))
    }

    @Test
    fun `only peers whose backoff has elapsed are due`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onFailure("d1", "t")
        assertTrue(s.dueForReconnect(999L).none { it.deviceId == "d1" })
        assertTrue(s.dueForReconnect(1_000L).any { it.deviceId == "d1" })
    }
}
