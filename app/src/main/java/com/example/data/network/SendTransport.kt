package com.example.data.network

/**
 * Describes the transport a message actually traveled over.
 * LAN = local network TCP, MESH = Google Nearby, including multi-hop relay.
 * FAILED = neither path worked.
 */
enum class SendTransport { LAN, MESH, FAILED }

/**
 * Tries each candidate in order and returns the transport that actually
 * delivered. A throwing transport counts as a failed attempt so one broken
 * route cannot abort the walk — this is the difference between "the peer was
 * unreachable" and "we never tried the other transport".
 */
internal suspend fun sendViaCandidates(
    candidates: List<TransportCandidate>,
    send: suspend (TransportCandidate) -> Boolean,
): SendTransport {
    for (candidate in candidates) {
        val delivered = runCatching { send(candidate) }.getOrDefault(false)
        if (delivered) return candidate.transport
    }
    return SendTransport.FAILED
}
