package com.example.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshStartLatchTest {

    @Test
    fun `service is not running before either result arrives`() {
        assertFalse(MeshStartLatch().isRunning)
    }

    @Test
    fun `service is running only when advertising and discovery both succeeded`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        assertFalse("half-started must not read as running", s.isRunning)
        s.onDiscoveryStarted()
        assertTrue(s.isRunning)
    }

    @Test
    fun `a benign start failure leaves the service not running so a retry is possible`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        s.onStartFailed(code = 8002)
        assertFalse("a stale-true latch is what forced a process restart", s.isRunning)
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        assertTrue("restart must be possible without a process restart", s.isRunning)
    }

    @Test
    fun `a hard start failure also clears the latch`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        s.onStartFailed(code = 8000)
        assertFalse(s.isRunning)
    }

    @Test
    fun `losing all peers does not mark the service stopped`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        s.onPeerCountChanged(0)
        assertTrue("peer count is not the same thing as running", s.isRunning)
    }

    @Test
    fun `stopping clears everything`() {
        val s = MeshStartLatch()
        s.onAdvertisingStarted()
        s.onDiscoveryStarted()
        s.onStopped()
        assertFalse(s.isRunning)
    }

    @Test
    fun `repeated stops are safe`() {
        val s = MeshStartLatch()
        s.onStopped()
        s.onStopped()
        assertFalse(s.isRunning)
    }
}
