package com.example.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TcpMessagingRouteFallbackTest {

    private fun candidates(lan: Boolean, mesh: Boolean) = buildList {
        if (lan) add(TransportCandidate(SendTransport.LAN, "192.168.0.1", 9999))
        if (mesh) add(TransportCandidate(SendTransport.MESH, "p2p-EP1", 0, "EP1"))
    }

    @Test
    fun `lan success short circuits and never touches mesh`() = runBlocking {
        val tried = mutableListOf<SendTransport>()
        val result = sendViaCandidates(candidates(lan = true, mesh = true)) {
            tried += it.transport
            it.transport == SendTransport.LAN
        }
        assertEquals(listOf(SendTransport.LAN), tried)
        assertEquals(SendTransport.LAN, result)
    }

    @Test
    fun `lan failure falls through to mesh`() = runBlocking {
        val tried = mutableListOf<SendTransport>()
        val result = sendViaCandidates(candidates(lan = true, mesh = true)) {
            tried += it.transport
            it.transport == SendTransport.MESH
        }
        assertEquals(listOf(SendTransport.LAN, SendTransport.MESH), tried)
        assertEquals(SendTransport.MESH, result)
    }

    @Test
    fun `mesh only candidate still sends`() = runBlocking {
        val tried = mutableListOf<SendTransport>()
        val result = sendViaCandidates(candidates(lan = false, mesh = true)) {
            tried += it.transport
            true
        }
        assertEquals(listOf(SendTransport.MESH), tried)
        assertEquals(SendTransport.MESH, result)
    }

    @Test
    fun `lan only candidate still sends`() = runBlocking {
        val tried = mutableListOf<SendTransport>()
        val result = sendViaCandidates(candidates(lan = true, mesh = false)) {
            tried += it.transport
            true
        }
        assertEquals(listOf(SendTransport.LAN), tried)
        assertEquals(SendTransport.LAN, result)
    }

    @Test
    fun `empty candidate list is a failure not a crash`() = runBlocking {
        assertEquals(SendTransport.FAILED, sendViaCandidates(emptyList()) { true })
    }

    @Test
    fun `a throwing transport is treated as failed and does not abort the walk`() = runBlocking {
        val tried = mutableListOf<SendTransport>()
        val result = sendViaCandidates(candidates(lan = true, mesh = true)) {
            tried += it.transport
            if (it.transport == SendTransport.LAN) throw java.io.IOException("no route to host")
            true
        }
        assertEquals(listOf(SendTransport.LAN, SendTransport.MESH), tried)
        assertEquals(SendTransport.MESH, result)
    }

    @Test
    fun `all transports failing reports FAILED`() = runBlocking {
        val result = sendViaCandidates(candidates(lan = true, mesh = true)) { false }
        assertEquals(SendTransport.FAILED, result)
    }

    @Test
    fun `route resolver on a same-subnet peer offers lan then mesh so mesh stays a fallback`() {
        val c = RouteResolver.candidates("192.168.0.113", 9999, "EP1")
        assertEquals(listOf(SendTransport.LAN, SendTransport.MESH), c.map { it.transport })
    }
}
