package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatMessageTransportTest {

    private fun msg(relayed: Boolean = false, hops: Int = 0) = ChatMessageEntity(
        id = "m1", conversationId = "c1", senderId = "s1", senderName = "S",
        recipientId = "r1", text = "hi", isFromMe = true,
        isMeshRelayed = relayed, meshHops = hops
    )

    @Test
    fun `default is lan with zero hops`() {
        val m = msg()
        assertFalse(m.isMeshRelayed)
        assertEquals(0, m.meshHops)
    }

    @Test
    fun `direct mesh send records one hop`() {
        assertEquals(1, msg(relayed = true, hops = 1).meshHops)
    }

    @Test
    fun `relayed mesh send records multiple hops`() {
        assertEquals(3, msg(relayed = true, hops = 3).meshHops)
    }

    @Test
    fun `hop count is stored verbatim`() {
        assertEquals(2, msg(relayed = true, hops = 2).meshHops)
    }
}
