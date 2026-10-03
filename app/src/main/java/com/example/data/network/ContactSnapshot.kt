package com.example.data.network

/**
 * Test-visible mirror of the contact update the mesh heartbeat performs, so the
 * "keep the LAN address, store the endpoint separately" rule can be asserted
 * without a Room database.
 */
data class ContactSnapshot(
    val ipAddress: String,
    val tcpPort: Int,
    val meshEndpointId: String?,
) {
    fun peerOnline(endpointId: String): ContactSnapshot = copy(
        ipAddress = ipAddress.takeIf { RouteResolver.isRoutableIp(it) } ?: ipAddress,
        meshEndpointId = meshEndpointId ?: endpointId,
    )
}
