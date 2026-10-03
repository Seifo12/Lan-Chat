package com.example.data.network

/**
 * Pure address resolution. Knows nothing about sockets, Room, or Nearby so the
 * ordering rules can be asserted directly in tests.
 *
 * LAN is preferred when a routable IP is known because it is cheaper and higher
 * bandwidth, but MESH is always offered alongside it as a fallback. Neither
 * transport gates the other: a peer reachable only over MESH yields a MESH-only
 * list, and a peer reachable only over LAN yields a LAN-only list. Turning MESH
 * off therefore never costs us LAN reachability.
 */
object RouteResolver {

    fun isRoutableIp(ip: String): Boolean {
        val octets = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4) return false
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        // 0.0.0.0 is the "unspecified" wildcard, never a destination.
        if (a == 0) return false
        // 127.0.0.0/8 is a real, directly connectable destination (the device
        // itself), so it must not be filtered out here.
        if (a == 127) return true
        // 10.0.0.0/8
        if (a == 10) return true
        // 172.16.0.0/12
        if (a == 172 && b in 16..31) return true
        // 192.168.0.0/16
        if (a == 192 && b == 168) return true
        // 100.64.0.0/10 carrier-grade NAT is common on mobile hotspots and
        // carrier LANs, both of which we can reach directly.
        if (a == 100 && b in 64..127) return true
        return false
    }

    fun isMeshAddress(address: String): Boolean =
        address.startsWith("p2p") || address.startsWith("qr-")

    /**
     * True when there is no direct LAN route for this peer, so an inline payload
     * cannot be sent over TCP and must be streamed through MESH instead. Used
     * because Nearby caps inline BYTES payloads at roughly 32 KB.
     */
    fun hasNoLanRoute(ipAddress: String?, meshEndpointId: String? = null): Boolean =
        candidates(ipAddress, null, meshEndpointId).none { it.transport == SendTransport.LAN }

    /**
     * Recovers the Nearby endpoint id from a legacy "p2p-<endpointId>" address.
     * Rows written before [com.example.data.local.ContactEntity.meshEndpointId]
     * existed only carry the endpoint inside the address, so without this those
     * peers would have no MESH route at all.
     */
    fun endpointFromAddress(ipAddress: String?): String? {
        val raw = ipAddress?.trim().orEmpty()
        if (raw.startsWith("p2p-")) {
            val id = raw.removePrefix("p2p-")
            return id.ifBlank { null }
        }
        if (raw.startsWith("qr-")) {
            val id = raw.removePrefix("qr-")
            return id.ifBlank { null }
        }
        return null
    }

    fun candidates(
        ipAddress: String?,
        tcpPort: Int?,
        meshEndpointId: String?,
    ): List<TransportCandidate> {
        val result = mutableListOf<TransportCandidate>()
        if (ipAddress != null && isRoutableIp(ipAddress)) {
            result.add(TransportCandidate(SendTransport.LAN, ipAddress, tcpPort ?: 9999))
        }
        val endpoint = meshEndpointId?.ifBlank { null } ?: endpointFromAddress(ipAddress)
        if (endpoint != null) {
            result.add(TransportCandidate(SendTransport.MESH, "p2p-$endpoint", 0, endpoint))
        }
        return result
    }
}
