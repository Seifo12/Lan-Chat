package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Phase 1.8: the roster and the pending decisions. Nothing here decides
 * anything; it stores what verification already allowed.
 */
@Dao
interface GroupMembershipDao {

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY deviceId ASC")
    suspend fun membersOf(groupId: String): List<GroupMemberEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM group_members " +
            "WHERE groupId = :groupId AND deviceId = :deviceId)"
    )
    suspend fun isMember(groupId: String, deviceId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: String)

    /** Whole-table replace: a creator-signed list is authoritative, not merged. */
    @Transaction
    suspend fun replaceMembers(groupId: String, members: List<GroupMemberEntity>) {
        clearMembers(groupId)
        upsertMembers(members)
    }

    @Query("SELECT * FROM group_invites WHERE groupId = :groupId AND nonce = :nonce LIMIT 1")
    suspend fun getInvite(groupId: String, nonce: String): GroupInviteEntity?

    @Query(
        "SELECT * FROM group_invites WHERE groupId = :groupId AND state != 'SUPERSEDED' " +
            "ORDER BY receivedAt DESC LIMIT 1"
    )
    suspend fun latestInviteFor(groupId: String): GroupInviteEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInvite(invite: GroupInviteEntity)

    @Query("UPDATE group_invites SET state = :state WHERE groupId = :groupId AND nonce = :nonce")
    suspend fun setInviteState(groupId: String, nonce: String, state: String)

    @Query("SELECT * FROM group_invites WHERE state = 'PENDING' ORDER BY receivedAt ASC")
    fun pendingInvites(): Flow<List<GroupInviteEntity>>
}