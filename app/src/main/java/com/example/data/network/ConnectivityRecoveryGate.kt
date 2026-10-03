package com.example.data.network

/**
 * Decides whether a connectivity notification represents a genuine recovery.
 *
 * The first version restarted MESH on every `NetworkCallback.onAvailable`. That
 * is wrong twice over: onAvailable means "a network exists right now", not
 * "connectivity came back", and tearing MESH down changes network state, which
 * fires onAvailable again — a self-sustaining ~2s restart loop.
 *
 * This gate fires only on an unavailable -> available transition, and rate
 * limits it so a flapping network cannot storm the mesh service.
 */
class ConnectivityRecoveryGate(private val debounceMs: Long = 10_000L) {

    private var lastKnown: Boolean? = null
    private var hasRecovered = false
    private var lastRecoveryAt: Long = 0L

    /** Returns true when the caller should react. */
    fun onNetworkState(hasInternet: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val previous = lastKnown
        lastKnown = hasInternet
        if (!hasInternet) return false
        if (previous != false) return false
        if (hasRecovered && now - lastRecoveryAt < debounceMs) return false
        hasRecovered = true
        lastRecoveryAt = now
        return true
    }
}
