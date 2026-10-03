package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendTransportTest {

    /**
     * Replaces the old chooseInitialRoute() tests. The rule is the same but stated
     * correctly: MESH is the primary route when the peer has no routable LAN
     * address, not merely when the address happens to start with "p2p-".
     */
    @Test
    fun `a peer with no lan address routes to mesh first`() {
        assertTrue(RouteResolver.hasNoLanRoute("p2p-abc123"))
        assertTrue(RouteResolver.hasNoLanRoute("p2p-wlan0-0-1"))
        assertTrue(RouteResolver.hasNoLanRoute(null))
        assertTrue(RouteResolver.hasNoLanRoute(""))
        assertTrue(RouteResolver.hasNoLanRoute("0.0.0.0"))
    }

    @Test
    fun `routable ipv4 goes to lan first`() {
        assertFalse(RouteResolver.hasNoLanRoute("192.168.0.134"))
        assertFalse(RouteResolver.hasNoLanRoute("10.0.0.7"))
        assertFalse(RouteResolver.hasNoLanRoute("172.20.1.4"))
    }

    @Test
    fun `lan stays primary even when a mesh endpoint is also known`() {
        val c = RouteResolver.candidates("192.168.0.134", 9999, "EP7")
        assertEquals(SendTransport.LAN, c.first().transport)
    }

    @Test
    fun `send transport enum has the three expected cases`() {
        assertEquals(3, SendTransport.entries.size)
        assertEquals(SendTransport.FAILED, SendTransport.valueOf("FAILED"))
    }
}
