package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResolverTest {

    @Test
    fun `routable private ip is accepted`() {
        assertTrue(RouteResolver.isRoutableIp("192.168.0.113"))
        assertTrue(RouteResolver.isRoutableIp("10.1.2.3"))
        assertTrue(RouteResolver.isRoutableIp("172.16.4.9"))
    }

    @Test
    fun `blank and unspecified are not routable but loopback is`() {
        assertFalse(RouteResolver.isRoutableIp("0.0.0.0"))
        assertFalse(RouteResolver.isRoutableIp(""))
        assertTrue(RouteResolver.isRoutableIp("127.0.0.1"))
    }

    @Test
    fun `mesh placeholders are not routable`() {
        assertFalse(RouteResolver.isRoutableIp("p2p-AB12CD34"))
        assertFalse(RouteResolver.isRoutableIp("qr-device123"))
    }

    @Test
    fun `public ip is not routable for direct lan`() {
        assertFalse(RouteResolver.isRoutableIp("8.8.8.8"))
    }

    @Test
    fun `mesh address prefixes are recognised`() {
        assertTrue(RouteResolver.isMeshAddress("p2p-AB12CD34"))
        assertTrue(RouteResolver.isMeshAddress("qr-device123"))
        assertFalse(RouteResolver.isMeshAddress("192.168.0.113"))
    }

    @Test
    fun `lan is offered first when a routable ip is known`() {
        val c = RouteResolver.candidates("192.168.0.113", 9999, "EP1")
        assertEquals(2, c.size)
        assertEquals(SendTransport.LAN, c[0].transport)
        assertEquals("192.168.0.113", c[0].address)
        assertEquals(9999, c[0].port)
        assertEquals(SendTransport.MESH, c[1].transport)
        assertEquals("EP1", c[1].meshEndpointId)
    }

    @Test
    fun `mesh only is returned when no routable ip exists`() {
        val c = RouteResolver.candidates("p2p-AB12CD34", 9999, "EP1")
        assertEquals(1, c.size)
        assertEquals(SendTransport.MESH, c[0].transport)
    }

    @Test
    fun `mesh only is returned when there is no endpoint either`() {
        val c = RouteResolver.candidates("p2p-AB12CD34", 9999, null)
        assertEquals(1, c.size)
        assertEquals(SendTransport.MESH, c[0].transport)
    }

    @Test
    fun `lan only is returned when there is no endpoint`() {
        val c = RouteResolver.candidates("192.168.0.5", 9999, null)
        assertEquals(1, c.size)
        assertEquals(SendTransport.LAN, c[0].transport)
    }

    @Test
    fun `no address at all yields no candidates`() {
        assertTrue(RouteResolver.candidates(null, 9999, null).isEmpty())
    }

    @Test
    fun `a non default port is preserved for lan`() {
        val c = RouteResolver.candidates("10.0.0.4", 7777, "EP9")
        assertEquals(7777, c[0].port)
    }
}
