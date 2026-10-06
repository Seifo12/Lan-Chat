package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Lifecycle of a message we tried to send.
 *
 * QUEUED and FAILED were added in Phase 1 step 1.1 because a failed send used to
 * be written back as SENDING, which made a message that could never be delivered
 * look like it was still going out, with no terminal state and nothing to tell
 * the user.
 */
enum class MessageStatus {
    /** A send is in flight right now. */
    SENDING,

    /** The peer is not reachable. Normal for a LAN messenger, and not an error.
     *  Attempts are not consumed while a message sits in this state. */
    QUEUED,

    /** Delivered to the peer transport. */
    SENT,

    DELIVERED,

    READ,

    /** The send gave up: the attempts were exhausted while the peer was
     *  reachable, or something refused it outright (for example a changed
     *  identity key). Terminal until the user retries. */
    FAILED;

    companion object {

        /** A reachable peer that answered stops here, per decision C2. */
        const val MAX_SEND_ATTEMPTS = 5

        /** Decision C2: queued messages younger than this are auto-sent when
         *  the peer reappears; older ones wait for the user. */
        const val QUEUED_AUTO_SEND_WINDOW_MS = 24L * 60 * 60 * 1000

        /**
         * Maps a send outcome onto a status.
         *
         * Reachability and success are separate inputs on purpose. A peer that
         * answered but refused the message is a real failure, while a peer that
         * never answered only means "not now", and conflating the two marks a
         * failed message as sent.
         *
         * @param success whether the peer accepted the message.
         * @param reachable whether the peer answered on the network at all. An
         *   unreachable peer means QUEUED, because being offline is the normal
         *   case for this app rather than a fault.
         * @param attempts how many attempts have been made *while reachable*.
         * @param blocked when true something refused the send outright, so it
         *   fails immediately instead of burning the remaining attempts.
         */
        fun forSendOutcome(
            success: Boolean,
            reachable: Boolean,
            attempts: Int,
            blocked: Boolean = false,
        ): MessageStatus = when {
            success -> SENT
            blocked -> FAILED
            !reachable -> QUEUED
            attempts <= 0 -> QUEUED
            attempts >= MAX_SEND_ATTEMPTS -> FAILED
            // A reachable peer that refused the message and still has attempts
            // left: keep it queued so the worker or the user tries again.
            else -> QUEUED
        }

        /** Decision C2: only recent queued messages are sent automatically. */
        fun shouldAutoSendQueued(createdAt: Long, now: Long = System.currentTimeMillis()): Boolean =
            now - createdAt <= QUEUED_AUTO_SEND_WINDOW_MS
    }
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
    val meshEndpointId: String? = null,
    /**
     * Phase 1.6: the key this device decided to trust for [deviceId].
     *
     * Written only when the user explicitly adds the contact, never on first
     * contact. [publicKeyBase64] is whatever the peer last presented and can be
     * overwritten by whoever the peer turns out to be; this is the value we agreed
     * on, so a difference between the two means the peer's key changed.
     */
    val pinnedPublicKey: String? = null,
    /**
     * Phase 1.6: true once a key different from [pinnedPublicKey] has been seen.
     * While set, nothing is sent to this peer until the user accepts the new key.
     */
    val hasKeyChanged: Boolean = false
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
