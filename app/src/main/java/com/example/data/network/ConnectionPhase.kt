package com.example.data.network

/**
 * Where a peer currently stands from this device's point of view.
 * Surfaced to the UI so connection changes are visible and animatable.
 */
enum class ConnectionPhase {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    RETRYING,
    FAILED,
}
