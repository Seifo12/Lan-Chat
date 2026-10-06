package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY receivedAt ASC, timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM messages ORDER BY receivedAt DESC, timestamp DESC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY receivedAt DESC, timestamp DESC LIMIT 1")
    fun getLastMessageForConversation(conversationId: String): Flow<ChatMessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET isMeshRelayed = :isMeshRelayed, meshHops = :meshHops WHERE id = :messageId")
    suspend fun updateMessageTransport(messageId: String, isMeshRelayed: Boolean, meshHops: Int)

    @Query("UPDATE messages SET status = :status WHERE conversationId = :conversationId AND isFromMe = 0 AND status != 'READ'")
    suspend fun markIncomingMessagesAsRead(conversationId: String, status: MessageStatus = MessageStatus.READ)

    /**
     * Rows the drain worker may send. SENDING is a send in flight or a legacy
     * stuck row; QUEUED is a message that could not go out because the peer was
     * unreachable, which is the normal case for a LAN messenger and is retried
     * automatically when the peer returns. FAILED is deliberately absent: it is
     * terminal until the user asks for a retry.
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isFromMe = 1 AND status IN ('SENDING', 'QUEUED') ORDER BY receivedAt ASC, timestamp ASC")
    suspend fun getPendingMessagesForConversation(conversationId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM messages WHERE isFromMe = 1 AND isGroup = 0 AND status IN ('SENDING', 'QUEUED') ORDER BY receivedAt ASC, timestamp ASC")
    suspend fun getAllPendingDirectMessages(): List<ChatMessageEntity>

    /**
     * Step 1.1 legacy sweep: rows left as SENDING by an older build, where a
     * failed send was written back as SENDING instead of a real terminal state.
     * Only rows older than the window are returned so a send that is genuinely
     * in flight is never touched.
     */
    @Query(
        "SELECT * FROM messages WHERE isFromMe = 1 AND status = 'SENDING' " +
            "AND receivedAt < :threshold"
    )
    suspend fun getStaleSendingMessages(threshold: Long): List<ChatMessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: String)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY isOnline DESC, lastSeen DESC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    suspend fun getAllContactsList(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getContactById(deviceId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId LIMIT 1")
    fun observeContactById(deviceId: String): Flow<ContactEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateContact(contact: ContactEntity)

    @Query("UPDATE contacts SET isOnline = :isOnline WHERE deviceId = :deviceId")
    suspend fun updateOnlineStatus(deviceId: String, isOnline: Boolean)

    @Query("UPDATE contacts SET customNickname = :nickname, avatarPath = :avatarPath WHERE deviceId = :deviceId")
    suspend fun updateContactProfile(deviceId: String, nickname: String?, avatarPath: String?)

    @Query("UPDATE contacts SET isOnline = 0 WHERE :currentTime - lastSeen > :timeoutMs")
    suspend fun markInactiveContactsOffline(currentTime: Long, timeoutMs: Long = 10000L)

    /**
     * Phase 1.6: record the key a peer presented, and raise the block if it is not
     * the key the user pinned.
     *
     * The flag is set here rather than at the call sites so it cannot be forgotten
     * on one path. The pin itself is never written by this method: overwriting it
     * automatically is the silent re-pin that 1.6 exists to prevent.
     */
    @Query(
        "UPDATE contacts SET publicKeyBase64 = :publicKeyBase64, " +
            "hasKeyChanged = CASE WHEN pinnedPublicKey IS NOT NULL " +
            "AND pinnedPublicKey != :publicKeyBase64 THEN 1 ELSE 0 END " +
            "WHERE deviceId = :deviceId"
    )
    suspend fun updatePublicKey(deviceId: String, publicKeyBase64: String?)

    /**
     * Phase 1.6: the user explicitly accepted a new key. This is the only path
     * that moves the pin, and it is never called automatically.
     */
    @Query(
        "UPDATE contacts SET pinnedPublicKey = :publicKeyBase64, " +
            "publicKeyBase64 = :publicKeyBase64, hasKeyChanged = 0 " +
            "WHERE deviceId = :deviceId"
    )
    suspend fun acceptNewKey(deviceId: String, publicKeyBase64: String)

    @Query("DELETE FROM contacts WHERE deviceId = :deviceId")
    suspend fun deleteContact(deviceId: String)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupById(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    fun observeGroupById(groupId: String): Flow<GroupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateGroup(group: GroupEntity)

    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun deleteGroup(groupId: String)
}
