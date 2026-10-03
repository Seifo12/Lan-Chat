package com.example.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class ReconnectTarget(
    val deviceId: String,
    val endpointId: String,
    val attempt: Int,
    val isKnown: Boolean,
)

private data class PeerRecord(
    val endpointId: String,
    val isKnown: Boolean,
    val attempt: Int,
    val phase: ConnectionPhase,
    val nextDueAt: Long,
)

/**
 * Owns desired-vs-actual peer state and decides when a peer should be
 * re-requested.
 *
 * Every teardown path funnels through here so a drop schedules a retry instead
 * of giving up, and so repeated failures back off instead of hammering Nearby.
 * A peer never reaches a terminal FAILED state on its own: after the fast
 * attempts are exhausted it keeps retrying on a slow cadence, because a phone
 * that comes back into range must recover with no user action.
 */
class ConnectionSupervisor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val maxFastAttempts: Int = 8,
    private val slowRetryMs: Long = 120_000L,
) {

    private val peers = ConcurrentHashMap<String, PeerRecord>()
    private val _phases = MutableStateFlow<Map<String, ConnectionPhase>>(emptyMap())

    val phases: StateFlow<Map<String, ConnectionPhase>> = _phases.asStateFlow()

    fun onDiscovered(deviceId: String, endpointId: String, isKnown: Boolean) {
        peers.compute(deviceId) { _, existing ->
            if (existing == null) {
                PeerRecord(endpointId, isKnown, 0, ConnectionPhase.DISCOVERING, 0L)
            } else {
                existing.copy(endpointId = endpointId, isKnown = existing.isKnown || isKnown)
            }
        }
        publish(deviceId)
    }

    fun onConnectRequested(deviceId: String, endpointId: String) {
        peers.compute(deviceId) { _, existing ->
            if (existing == null) {
                PeerRecord(endpointId, false, 0, ConnectionPhase.CONNECTING, 0L)
            } else {
                existing.copy(endpointId = endpointId, phase = ConnectionPhase.CONNECTING)
            }
        }
        publish(deviceId)
    }

    fun onConnected(deviceId: String, endpointId: String) {
        peers.compute(deviceId) { _, existing ->
            if (existing == null) {
                PeerRecord(endpointId, false, 0, ConnectionPhase.CONNECTED, Long.MAX_VALUE)
            } else {
                existing.copy(
                    endpointId = endpointId,
                    attempt = 0,
                    phase = ConnectionPhase.CONNECTED,
                    nextDueAt = Long.MAX_VALUE,
                )
            }
        }
        publish(deviceId)
    }

    fun onDisconnected(deviceId: String, reason: String) = scheduleRetry(deviceId)

    fun onFailure(deviceId: String, reason: String) = scheduleRetry(deviceId)

    fun onEndpointLost(deviceId: String) = scheduleRetry(deviceId)

    fun onGaveUp(deviceId: String) {
        peers.computeIfPresent(deviceId) { _, r -> r.copy(phase = ConnectionPhase.FAILED) }
        publish(deviceId)
    }

    private fun scheduleRetry(deviceId: String) {
        peers.compute(deviceId) { _, existing ->
            if (existing == null) return@compute null
            val attempt = existing.attempt + 1
            existing.copy(
                attempt = attempt,
                phase = ConnectionPhase.RETRYING,
                nextDueAt = clock() + delayFor(attempt),
            )
        }
        publish(deviceId)
    }

    internal fun nextDelayMsForTest(deviceId: String): Long {
        val record = peers[deviceId] ?: return 0L
        return delayFor(record.attempt)
    }

    private fun delayFor(attempt: Int): Long {
        if (attempt >= maxFastAttempts) return slowRetryMs
        var delay = baseDelayMs
        repeat((attempt - 1).coerceAtLeast(0)) { delay *= 2 }
        return delay.coerceAtMost(maxDelayMs)
    }

    fun dueForReconnect(now: Long = clock()): List<ReconnectTarget> =
        peers.entries
            .filter { it.value.phase == ConnectionPhase.RETRYING && it.value.nextDueAt <= now }
            .map { ReconnectTarget(it.key, it.value.endpointId, it.value.attempt, it.value.isKnown) }

    fun phaseOf(deviceId: String): ConnectionPhase = peers[deviceId]?.phase ?: ConnectionPhase.IDLE

    fun isGivingUp(deviceId: String): Boolean = peers[deviceId]?.phase == ConnectionPhase.FAILED

    fun forget(deviceId: String) {
        peers.remove(deviceId)
        publish(deviceId)
    }

    private fun publish(deviceId: String) {
        _phases.value = peers.mapValues { it.value.phase }
    }
}
