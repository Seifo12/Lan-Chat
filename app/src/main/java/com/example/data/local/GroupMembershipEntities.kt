package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 1.8: who belongs to a group, and what key each member is known by.
 *
 * Deliberately no foreign key to groups or contacts. Deleting a group row or
 * a contact must not cascade into membership evidence, and a member who is not
 * a contact on this device has no contact row to point at: their key is
 * vouched by the creator and applies inside that group only.
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "deviceId"],
    indices = [Index(value = ["deviceId"])]
)
data class GroupMemberEntity(
    val groupId: String,
    val deviceId: String,
    val publicKeyBase64: String,
    val displayName: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Invite states are plain strings, following the MessageStatus precedent that
 * keeps status changes out of the schema version.
 */
object InviteState {
    /** Verified and waiting for an explicit user decision. */
    const val PENDING = "PENDING"

    /** The user accepted; membership exists. */
    const val ACCEPTED = "ACCEPTED"

    /** The user declined. Terminal until a fresh invitation arrives. */
    const val DECLINED = "DECLINED"

    /** Past its expiry before anyone decided. Terminal. */
    const val EXPIRED = "EXPIRED"

    /** Replaced by a higher-version creator-signed list. */
    const val SUPERSEDED = "SUPERSEDED"
}

/**
 * One received invitation, kept with the exact bytes that arrived.
 *
 * rawBytes is what an audit re-verifies. Re-serialising the parsed form would
 * verify something that was never received, which is the failure mode the
 * arrival-bytes rule exists to prevent.
 */
@Entity(
    tableName = "group_invites",
    primaryKeys = ["groupId", "nonce"]
)
data class GroupInviteEntity(
    val groupId: String,
    val nonce: String,
    val creatorId: String,
    val groupName: String,
    val memberListVersion: Long,
    val expiry: Long,
    val rawBytes: ByteArray,
    val state: String = InviteState.PENDING,
    val receivedAt: Long = System.currentTimeMillis(),
) {
    // BLOB identity is the pair, not the content, so two invites for the same
    // group and nonce are the same row regardless of what arrived.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupInviteEntity) return false
        return groupId == other.groupId && nonce == other.nonce
    }

    override fun hashCode(): Int = 31 * groupId.hashCode() + nonce.hashCode()
}