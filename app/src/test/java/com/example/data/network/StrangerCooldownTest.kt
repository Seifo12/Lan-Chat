package com.example.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrangerCooldownTest {

    @Test
    fun `a refused stranger is in cooldown and cannot take a slot`() {
        val c = ConnectionSlotPolicy()
        c.noteRefused("s1", now = 0L)
        assertTrue(c.isCoolingDown("s1", now = 0L))
        assertFalse(c.mayAdmit("s1", isKnown = false, now = 0L))
    }

    @Test
    fun `cooldown expires and the stranger may be admitted again`() {
        val c = ConnectionSlotPolicy(coolDownMs = 30_000L)
        c.noteRefused("s1", now = 0L)
        assertFalse(c.mayAdmit("s1", isKnown = false, now = 29_999L))
        assertTrue(c.mayAdmit("s1", isKnown = false, now = 30_000L))
        assertFalse("the entry must be dropped once the cooldown elapses", c.isCoolingDown("s1", now = 60_000L))
    }

    @Test
    fun `known contacts are never blocked by cooldown`() {
        val c = ConnectionSlotPolicy()
        c.noteRefused("k1", now = 0L)
        assertTrue(c.mayAdmit("k1", isKnown = true, now = 0L))
    }

    @Test
    fun `a stranger never refused is admitted immediately`() {
        val c = ConnectionSlotPolicy()
        assertTrue(c.mayAdmit("fresh", isKnown = false, now = 0L))
    }

    @Test
    fun `a refusal is re-armed rather than extended by a repeat refusal`() {
        val c = ConnectionSlotPolicy(coolDownMs = 10_000L)
        c.noteRefused("s1", now = 0L)
        c.noteRefused("s1", now = 5_000L)
        assertTrue("cooldown restarts from the latest refusal", c.isCoolingDown("s1", now = 12_000L))
    }
}
