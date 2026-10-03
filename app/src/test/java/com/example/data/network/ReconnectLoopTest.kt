package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectLoopTest {

    private fun supervisor() = ConnectionSupervisor(clock = { 0L }, baseDelayMs = 1_000L)

    @Test
    fun `only peers whose backoff has elapsed are selected`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onDiscovered("d2", "EP2", isKnown = true)
        s.onFailure("d1", "t")
        s.onConnected("d2", "EP2")

        assertTrue(ReconnectLoop.selectDue(s, now = 0L).none { it.deviceId == "d1" })
        assertEquals(listOf("d1"), ReconnectLoop.selectDue(s, now = 1_000L).map { it.deviceId })
    }

    @Test
    fun `a peer that reconnected is not selected again`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        s.onFailure("d1", "t")
        s.onConnected("d1", "EP1")
        assertTrue(ReconnectLoop.selectDue(s, now = 10_000L).isEmpty())
    }

    @Test
    fun `known peers are ordered before strangers`() {
        val s = supervisor()
        s.onDiscovered("s1", "EP1", isKnown = false)
        s.onDiscovered("k1", "EP2", isKnown = true)
        s.onFailure("s1", "t")
        s.onFailure("k1", "t")

        assertEquals(listOf("k1", "s1"), ReconnectLoop.selectDue(s, now = 1_000L).map { it.deviceId })
    }

    @Test
    fun `a selected target carries the endpoint and attempt so the caller can dial it`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP9", isKnown = true)
        s.onFailure("d1", "t")
        val target = ReconnectLoop.selectDue(s, now = 1_000L).single()
        assertEquals("EP9", target.endpointId)
        assertEquals(1, target.attempt)
    }

    @Test
    fun `nothing is selected when there are no retrying peers`() {
        val s = supervisor()
        s.onDiscovered("d1", "EP1", isKnown = true)
        assertTrue(ReconnectLoop.selectDue(s, now = 0L).isEmpty())
    }
}
