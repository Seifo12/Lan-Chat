package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: the invitation needs a packet kind old clients drop and new
 * clients gate. CURRENT is 3 with MINIMUM staying 2, so direct chat keeps
 * flowing while anything version-3 is refused toward a v2 peer before
 * sending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupInviteWireTest {

    private fun packet() = GroupInvitePacket(
        messageId = "m1", senderId = "peer_a", senderName = "A",
        recipientId = "peer_b", inviteJson = "{\"creatorId\":\"peer_a\"}",
        timestamp = 1_700_000_000_000L, signatureBase64 = "sig",
        counter = 4L, protocolVersion = 3,
    )

    @Test
    fun `invite survives the wire encoding`() {
        val parsed = NetworkPacket.fromJson(packet().toJson()) as? GroupInvitePacket
        assertTrue("GROUP_INVITE must parse", parsed != null)
        assertEquals(3, parsed!!.protocolVersion)
        assertEquals(4L, parsed.counter)
        assertEquals("{\"creatorId\":\"peer_a\"}", parsed.inviteJson)
    }

    @Test
    fun `missing inviteJson nulls the whole packet`() {
        val raw = packet().toJson()
        val stripped = org.json.JSONObject(raw).also { it.remove("inviteJson") }.toString()
        assertNull(
            "an invitation without content is not an invitation",
            NetworkPacket.fromJson(stripped)
        )
    }

    @Test
    fun `protocol version still defaults to 1 when absent`() {
        val raw = org.json.JSONObject(packet().toJson()).also { it.remove("protocolVersion") }.toString()
        val parsed = NetworkPacket.fromJson(raw) as? GroupInvitePacket
        assertEquals(
            "an unversioned field reads as unsupported, never as current",
            1,
            parsed!!.protocolVersion
        )
    }

    @Test
    fun `protocol moved while minimum stayed`() {
        assertEquals(3, ProtocolVersion.CURRENT)
        assertEquals(2, ProtocolVersion.MINIMUM)
    }
}
