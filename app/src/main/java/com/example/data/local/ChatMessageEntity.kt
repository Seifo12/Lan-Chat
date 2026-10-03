package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ
}

@Entity(tableName = "messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String, // Device ID of the peer OR groupId
    val senderId: String,
    val senderName: String,
    val recipientId: String,
    val text: String,
    val photoPath: String? = null, // Local URI / file path if photo message
    val isPhoto: Boolean = false,
    val filePath: String? = null, // Local file path for video / document / movie
    val fileName: String? = null, // Display file name
    val fileSize: Long = 0L, // Size in bytes
    val mimeType: String? = null, // Mime type
    val isVideo: Boolean = false,
    val isFile: Boolean = false,
    val isVoice: Boolean = false,
    val audioDurationSeconds: Int = 0,
    val isMeshRelayed: Boolean = false,
    val meshHops: Int = 0,
    // وقت وصول الرسالة لينا. الترتيب بيتبني عليه عشان ساعات الأجهزة مختلفة.
    val receivedAt: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean,
    val status: MessageStatus = MessageStatus.SENT,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val isSenderDeveloper: Boolean = false
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val deviceId: String,
    val displayName: String,
    val ipAddress: String,
    val tcpPort: Int = 9999,
    val avatarPath: String? = null,
    val avatarColorIndex: Int = 0,
    val lastSeen: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true,
    val customNickname: String? = null,
    val isDeveloper: Boolean = false,
    val appVersionCode: Int = 1,
    val appVersionName: String = "1.0.0",
    val isMeshPeer: Boolean = false,
    val publicKeyBase64: String? = null,
    /**
     * Nearby endpoint id for this peer, or null when we have never seen it over
     * MESH. Stored separately from [ipAddress] so a peer always keeps a usable
     * LAN address instead of being reduced to a "p2p-..." placeholder that no
     * longer has any LAN route at all.
     */
    val meshEndpointId: String? = null
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val groupId: String,
    val groupName: String,
    val description: String = "",
    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis(),
    val avatarColorIndex: Int = 0
)
