package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS-1: a peer must never lose its LAN address. Previously the mesh heartbeat
 * wrote "p2p-<endpointId>" into ipAddress whenever no routable IP was on hand,
 * which permanently removed that peer's only LAN route.
 */
class MeshAddressNotStoredTest {

    @Test
    fun `a routable lan address is preserved and the endpoint recorded separately`() {
        val updated = ContactSnapshot(
            ipAddress = "192.168.0.113",
            tcpPort = 9999,
            meshEndpointId = null,
        ).peerOnline(endpointId = "EP1")

        assertEquals("192.168.0.113", updated.ipAddress)
        assertEquals("EP1", updated.meshEndpointId)
        assertEquals(2, RouteResolver.candidates(updated.ipAddress, updated.tcpPort, updated.meshEndpointId).size)
    }

    @Test
    fun `an existing endpoint id is not overwritten by a later heartbeat`() {
        val updated = ContactSnapshot(
            ipAddress = "192.168.0.113",
            tcpPort = 9999,
            meshEndpointId = "EP_FIRST",
        ).peerOnline(endpointId = "EP_SECOND")

        assertEquals("EP_FIRST", updated.meshEndpointId)
    }

    @Test
    fun `a legacy p2p address keeps working as a mesh route instead of being erased`() {
        val updated = ContactSnapshot(
            ipAddress = "p2p-OLD",
            tcpPort = 9999,
            meshEndpointId = null,
        ).peerOnline(endpointId = "EP2")

        assertEquals("EP2", updated.meshEndpointId)
        val c = RouteResolver.candidates(updated.ipAddress, updated.tcpPort, updated.meshEndpointId)
        assertEquals(1, c.size)
        assertEquals(SendTransport.MESH, c[0].transport)
    }

    @Test
    fun `a peer with no lan address still exposes a mesh route`() {
        val updated = ContactSnapshot(
            ipAddress = "10.0.0.1",
            tcpPort = 9999,
            meshEndpointId = "EP3",
        ).peerOnline(endpointId = "EP3")

        val c = RouteResolver.candidates(updated.ipAddress, updated.tcpPort, updated.meshEndpointId)
        assertEquals(listOf(SendTransport.LAN, SendTransport.MESH), c.map { it.transport })
    }

    @Test
    fun `a blank stored address does not produce a lan candidate`() {
        val c = RouteResolver.candidates("", 9999, "EP4")
        assertTrue(c.none { it.transport == SendTransport.LAN })
        assertEquals(1, c.size)
    }

    @Test
    fun `loopback is offered as a direct lan route`() {
        val c = RouteResolver.candidates("127.0.0.1", 9999, "EP5")
        assertEquals(listOf(SendTransport.LAN, SendTransport.MESH), c.map { it.transport })
    }
}
