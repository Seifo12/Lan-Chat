package com.example.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: the invitation content both sides hash without re-serialising.
 *
 * The sender emits byte-exact canonical JSON; the receiver hashes the arrival
 * bytes under the invite label and parses them. What is verified and what is
 * read are the same bytes by construction, and ordinary text can never parse
 * as an invitation because it never hashes under that label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupInviteCodecTest {

    private fun invite() = GroupInviteCodec.Invite(
        creatorId = "peer_a", groupId = "g_0123456789abcdef0123456789abcdef",
        groupName = "Hiking \"the\" ridge\nteam", description = "",
        members = listOf(
            GroupInviteCodec.Member("peer_b", "cGsta2V5", "B"),
            GroupInviteCodec.Member("peer_a", "cGsta2V5", "A"),
        ),
        memberListVersion = 1L, nonce = "abcdef0123456789abcdef0123456789",
        expiry = 1_730_000_000_000L,
    )

    @Test
    fun `canonical form is byte-exact and sorts members`() {
        val expected = "{\"creatorId\":\"peer_a\"," +
            "\"description\":\"\"," +
            "\"expiry\":1730000000000," +
            "\"groupId\":\"g_0123456789abcdef0123456789abcdef\"," +
            "\"groupName\":\"Hiking \\\"the\\\" ridge\\nteam\"," +
            "\"memberListVersion\":1," +
            "\"members\":[{\"id\":\"peer_a\",\"name\":\"A\",\"pk\":\"cGsta2V5\"}," +
            "{\"id\":\"peer_b\",\"name\":\"B\",\"pk\":\"cGsta2V5\"}]," +
            "\"nonce\":\"abcdef0123456789abcdef0123456789\"}"
        assertEquals(expected, GroupInviteCodec.buildCanonical(invite()))
    }

    @Test
    fun `parse inverts build including escapes`() {
        val parsed = GroupInviteCodec.parse(GroupInviteCodec.buildCanonical(invite()))
        assertEquals(
            invite().copy(members = invite().members.sortedBy { it.deviceId }),
            parsed
        )
    }

    @Test
    fun `parse refuses missing and mistyped fields`() {
        assertNull(
            "an invitation without content is not an invitation",
            GroupInviteCodec.parse("{\"creatorId\":\"peer_a\"}")
        )
        assertNull(GroupInviteCodec.parse("not json"))
        assertNull(GroupInviteCodec.parse(""))
        assertNull(
            "a version below 1 is not a version",
            GroupInviteCodec.parse(
                GroupInviteCodec.buildCanonical(invite().copy(memberListVersion = 0))
            )
        )
    }

    @Test
    fun `the label changes the digest`() {
        val bytes = "{\"creatorId\":\"peer_a\"}".toByteArray()
        val labeled = GroupInviteCodec.contentDigestHex(bytes)
        val plain = MessageSigningPayload.digestHex(bytes)
        assertTrue(
            "a label-less digest must never equal an invitation digest",
            labeled != plain
        )
    }

    @Test
    fun `minted ids are 128-bit hex and unique`() {
        val ids = (1..100).map { GroupInviteCodec.newGroupId() }.toSet()
        assertEquals(100, ids.size)
        assertTrue(ids.all { it.matches(Regex("g_[0-9a-f]{32}")) })
        val nonces = (1..100).map { GroupInviteCodec.newNonce() }.toSet()
        assertEquals(100, nonces.size)
        assertTrue(nonces.all { it.matches(Regex("[0-9a-f]{32}")) })
    }
}
