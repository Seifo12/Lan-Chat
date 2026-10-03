package com.example.data.network

import java.util.concurrent.ConcurrentHashMap

/**
 * A stranger that is refused or evicted is put in a short cooldown.
 *
 * Without it the peer re-requests on the next discovery cycle, gets refused
 * again, and the pair flaps visibly to the user. This damps the retry cadence
 * only — MESH capacity is unchanged and saved contacts bypass cooldown entirely.
 */
class ConnectionSlotPolicy(private val coolDownMs: Long = 30_000L) {

    private val refusedAt = ConcurrentHashMap<String, Long>()

    fun noteRefused(deviceId: String, now: Long) {
        refusedAt[deviceId] = now
    }

    fun isCoolingDown(deviceId: String, now: Long): Boolean {
        val at = refusedAt[deviceId] ?: return false
        if (now - at >= coolDownMs) {
            refusedAt.remove(deviceId, at)
            return false
        }
        return true
    }

    fun mayAdmit(deviceId: String, isKnown: Boolean, now: Long): Boolean {
        if (isKnown) return true
        return !isCoolingDown(deviceId, now)
    }
}
