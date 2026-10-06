package com.example.data.security

import java.security.SecureRandom
import org.json.JSONObject

/**
 * Phase 1.8: the invitation content both sides hash without re-serialising.
 *
 * The sender emits byte-exact canonical JSON in a fixed field order; the
 * receiver hashes the arrival bytes under the invite label and parses them.
 * Order on the wire is the sender's business, so parsing stays
 * order-independent while the digest stays byte-exact.
 */
object GroupInviteCodec {

    /** Starts the digest preimage, so ordinary text can never parse as an invitation. */
    const val DOMAIN_LABEL = "LanChat-group-invite-v1"

    /** Binds a group text message to its group (spec condition 4). */
    const val MESSAGE_LABEL = "LanChat-group-msg-v1"

    /** Clocks skew in this mesh; expiry is a refusal with tolerance, not a promise. */
    const val EXPIRY_TOLERANCE_MS = 15L * 60 * 1000

    /** How long an invitation stays usable after minting. */
    const val INVITE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    data class Member(
        val deviceId: String,
        val publicKeyBase64: String,
        val displayName: String = "",
    )

    data class Invite(
        val creatorId: String,
        val groupId: String,
        val groupName: String,
        val description: String,
        val members: List<Member>,
        val memberListVersion: Long,
        val nonce: String,
        val expiry: Long,
    )

    private fun escape(value: String): String = buildString {
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /** Fixed field order, no whitespace, members sorted: byte-exact by construction. */
    fun buildCanonical(invite: Invite): String {
        val members = invite.members.sortedBy { it.deviceId }.joinToString(",") {
            "{\"id\":\"${escape(it.deviceId)}\"," +
                "\"name\":\"${escape(it.displayName)}\"," +
                "\"pk\":\"${escape(it.publicKeyBase64)}\"}"
        }
        return "{\"creatorId\":\"${escape(invite.creatorId)}\"," +
            "\"description\":\"${escape(invite.description)}\"," +
            "\"expiry\":${invite.expiry}," +
            "\"groupId\":\"${escape(invite.groupId)}\"," +
            "\"groupName\":\"${escape(invite.groupName)}\"," +
            "\"memberListVersion\":${invite.memberListVersion}," +
            "\"members\":[$members]," +
            "\"nonce\":\"${escape(invite.nonce)}\"}"
    }

    /** Order-independent read; blank or mistyped required fields refuse. */
    fun parse(json: String): Invite? {
        try {
            if (json.isBlank()) return null
            val obj = JSONObject(json)
            val members = obj.getJSONArray("members")
            return Invite(
                creatorId = obj.getString("creatorId").takeIf { it.isNotBlank() } ?: return null,
                groupId = obj.getString("groupId").takeIf { it.isNotBlank() } ?: return null,
                groupName = obj.getString("groupName"),
                description = obj.optString("description", ""),
                members = (0 until members.length()).map { i ->
                    val m = members.getJSONObject(i)
                    Member(
                        deviceId = m.getString("id").takeIf { it.isNotBlank() } ?: return null,
                        publicKeyBase64 = m.getString("pk").takeIf { it.isNotBlank() } ?: return null,
                        displayName = m.optString("name", ""),
                    )
                }.takeIf { it.isNotEmpty() } ?: return null,
                memberListVersion = obj.getLong("memberListVersion").takeIf { it >= 1 } ?: return null,
                nonce = obj.getString("nonce").takeIf { it.isNotBlank() } ?: return null,
                expiry = obj.getLong("expiry"),
            )
        } catch (_: Exception) {
            return null
        }
    }

    /** The digest the sender signs: label, separator, then the arrival bytes. */
    fun contentDigestHex(inviteJsonBytes: ByteArray): String =
        MessageSigningPayload.digestHex(
            DOMAIN_LABEL.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + inviteJsonBytes
        )

    /** Binds a group text message to its group (spec condition 4). */
    fun groupMessageDigestHex(groupId: String, textBytes: ByteArray): String =
        MessageSigningPayload.digestHex(
            MESSAGE_LABEL.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                groupId.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + textBytes
        )

    private val random = SecureRandom()

    /** 128 unpredictable bits, never a timestamp. */
    fun newGroupId(): String = "g_" + ByteArray(16)
        .also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun newNonce(): String = ByteArray(16)
        .also { random.nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
}
