package com.example.data.network

/**
 * Tracks whether Nearby is genuinely running instead of assuming it is.
 *
 * The previous boolean was set optimistically before the async start results
 * arrived, and was deliberately kept set on benign failure codes. Once it went
 * stale-true, every later start — including pull-to-refresh — became a no-op,
 * which is exactly why the app had to be force-killed to recover.
 *
 * Peer count is tracked separately and never influences running state: losing
 * the last peer must not make the service look stopped.
 */
class MeshStartLatch {

    private var advertising = false
    private var discovery = false

    val isRunning: Boolean get() = advertising && discovery

    fun onAdvertisingStarted() {
        advertising = true
    }

    fun onDiscoveryStarted() {
        discovery = true
    }

    fun onStartFailed(code: Int) {
        advertising = false
        discovery = false
    }

    fun onPeerCountChanged(count: Int) = Unit

    fun onStopped() {
        advertising = false
        discovery = false
    }
}
