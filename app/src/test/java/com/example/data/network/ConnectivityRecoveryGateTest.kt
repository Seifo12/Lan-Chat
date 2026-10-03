package com.example.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityRecoveryGateTest {

    @Test
    fun `nothing happens on the first sighting of a network`() {
        val g = ConnectivityRecoveryGate()
        assertFalse(g.onNetworkState(hasInternet = true))
    }

    @Test
    fun `no action while the network stays up`() {
        val g = ConnectivityRecoveryGate()
        g.onNetworkState(hasInternet = true)
        assertFalse(g.onNetworkState(hasInternet = true))
        assertFalse(g.onNetworkState(hasInternet = true))
    }

    @Test
    fun `recovery fires exactly once after a loss`() {
        val g = ConnectivityRecoveryGate()
        g.onNetworkState(hasInternet = true)
        g.onNetworkState(hasInternet = false)
        assertTrue(g.onNetworkState(hasInternet = true))
        assertFalse("a still-present network must not re-trigger", g.onNetworkState(hasInternet = true))
    }

    @Test
    fun `repeated losses each produce one recovery`() {
        val g = ConnectivityRecoveryGate(debounceMs = 0L)
        g.onNetworkState(hasInternet = true, now = 0L)
        repeat(3) { i ->
            g.onNetworkState(hasInternet = false, now = i * 100L)
            assertTrue("cycle $i should fire once", g.onNetworkState(hasInternet = true, now = i * 100L + 1))
        }
    }

    @Test
    fun `an offline device never triggers a recovery`() {
        val g = ConnectivityRecoveryGate()
        g.onNetworkState(hasInternet = false)
        assertFalse(g.onNetworkState(hasInternet = false))
    }

    @Test
    fun `flapping inside the debounce window fires once`() {
        val g = ConnectivityRecoveryGate(debounceMs = 5_000L)
        g.onNetworkState(hasInternet = true, now = 0L)
        g.onNetworkState(hasInternet = false, now = 500L)
        assertTrue(g.onNetworkState(hasInternet = true, now = 1_000L))
        g.onNetworkState(hasInternet = false, now = 1_500L)
        assertFalse("too soon after the last recovery", g.onNetworkState(hasInternet = true, now = 2_000L))
        g.onNetworkState(hasInternet = false, now = 6_000L)
        assertTrue("past the debounce window", g.onNetworkState(hasInternet = true, now = 7_000L))
    }

    @Test
    fun `a still-present network is not a recovery even when time has passed`() {
        val g = ConnectivityRecoveryGate(debounceMs = 0L)
        g.onNetworkState(hasInternet = true, now = 0L)
        g.onNetworkState(hasInternet = false, now = 1L)
        assertTrue(g.onNetworkState(hasInternet = true, now = 2L))
        assertFalse("no loss means nothing to recover from", g.onNetworkState(hasInternet = true, now = 999_999L))
    }
}
