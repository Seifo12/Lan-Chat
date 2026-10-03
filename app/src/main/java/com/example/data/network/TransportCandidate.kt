package com.example.data.network

/**
 * One addressable way to reach a peer. A peer can yield several of these and
 * they are tried in order.
 */
data class TransportCandidate(
    val transport: SendTransport,
    val address: String,
    val port: Int,
    val meshEndpointId: String? = null,
)
