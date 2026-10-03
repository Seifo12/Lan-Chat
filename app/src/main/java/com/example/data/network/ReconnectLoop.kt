package com.example.data.network

object ReconnectLoop {

    /**
     * Peers to re-request this tick, saved contacts first so a contact the user
     * actually talks to never queues behind an unrequested stranger.
     */
    fun selectDue(supervisor: ConnectionSupervisor, now: Long): List<ReconnectTarget> =
        supervisor.dueForReconnect(now).sortedByDescending { it.isKnown }
}
