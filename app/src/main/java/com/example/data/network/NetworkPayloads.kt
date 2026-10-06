package com.example.data.network

import com.example.data.security.EncryptionManager
import org.json.JSONArray
import org.json.JSONObject

enum class PacketType {
    BEACON, BEACON_ACK, TEXT_MESSAGE, PHOTO_MESSAGE, VIDEO_MESSAGE,
    FILE_MESSAGE, VOICE_MESSAGE, ACK_DELIVERED, ACK_READ, GROUP_ANNOUNCE,
    CALL_OFFER, CALL_ANSWER, CALL_RINGING, CALL_END, MESH_RELAY,
    APP_UPDATE_REQUEST, APP_UPDATE_RESPONSE, APP_UPDATE_CHUNK, TRANSFER_CANCEL,
    GROUP_INVITE
}

sealed class NetworkPacket(val type: PacketType) {
    abstract fun toJson(): String

    companion object {
        private const val MAX_TEXT_LENGTH = 10_000
        private const val MAX_BASE64_LENGTH = 2_000_000
        private const val MAX_NAME_LENGTH = 200
        private const val MAX_ID_LENGTH = 128
        private const val MAX_FILE_NAME_LENGTH = 255
        private const val MAX_GROUP_NAME_LENGTH = 200
        private const val MAX_REASON_LENGTH = 100
        private const val MAX_MESH_PAYLOAD_LENGTH = 500_000
        private const val MAX_VISITED_NODES = 30
        private const val MAX_RAW_INPUT_LENGTH = 2_500_000
        private const val MAX_INVITE_JSON_LENGTH = 64_000

        private fun limitStr(value: String, max: Int): String = value.take(max)

        fun fromJson(rawJsonStr: String): NetworkPacket? {
            return try {
                if (rawJsonStr.isBlank() || rawJsonStr.length > MAX_RAW_INPUT_LENGTH) return null

                val jsonStr = if (EncryptionManager.isEncrypted(rawJsonStr)) {
                    EncryptionManager.decrypt(rawJsonStr)
                } else {
                    rawJsonStr
                }

                if (jsonStr.isBlank()) return null
                val obj = JSONObject(jsonStr)
                val typeStr = obj.optString("type", "")
                if (typeStr.isBlank()) return null

                val packetType = try {
                    PacketType.valueOf(typeStr)
                } catch (e: IllegalArgumentException) {
                    return null
                }

                when (packetType) {
                    PacketType.BEACON -> BeaconPacket(
                        deviceId = limitStr(obj.getString("deviceId"), MAX_ID_LENGTH),
                        displayName = limitStr(obj.getString("displayName"), MAX_NAME_LENGTH),
                        avatarColorIndex = obj.optInt("avatarColorIndex", 0).coerceIn(0, 100),
                        avatarBase64 = optLimited(obj, "avatarBase64", 200_000),
                        tcpPort = obj.optInt("tcpPort", 9999).coerceIn(1, 65535),
                        isDeveloper = obj.optBoolean("isDeveloper", false),
                        versionCode = obj.optInt("versionCode", 1).coerceAtLeast(1),
                        versionName = obj.optString("versionName", "1.0.0").take(50),
                        isMeshSupported = obj.optBoolean("isMeshSupported", true),
                        publicKeyBase64 = optLimited(obj, "publicKeyBase64", 2000),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.BEACON_ACK -> BeaconAckPacket(
                        deviceId = limitStr(obj.getString("deviceId"), MAX_ID_LENGTH),
                        displayName = limitStr(obj.getString("displayName"), MAX_NAME_LENGTH),
                        avatarColorIndex = obj.optInt("avatarColorIndex", 0).coerceIn(0, 100),
                        avatarBase64 = optLimited(obj, "avatarBase64", 200_000),
                        tcpPort = obj.optInt("tcpPort", 9999).coerceIn(1, 65535),
                        isDeveloper = obj.optBoolean("isDeveloper", false),
                        versionCode = obj.optInt("versionCode", 1).coerceAtLeast(1),
                        versionName = obj.optString("versionName", "1.0.0").take(50),
                        isMeshSupported = obj.optBoolean("isMeshSupported", true),
                        publicKeyBase64 = optLimited(obj, "publicKeyBase64", 2000),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.TEXT_MESSAGE -> TextMessagePacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        senderName = limitStr(obj.getString("senderName"), MAX_NAME_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        text = limitStr(obj.getString("text"), MAX_TEXT_LENGTH),
                        isGroup = obj.optBoolean("isGroup", false),
                        groupId = optLimited(obj, "groupId", MAX_ID_LENGTH),
                        groupName = optLimited(obj, "groupName", MAX_GROUP_NAME_LENGTH),
                        isDeveloper = obj.optBoolean("isDeveloper", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        signatureBase64 = optLimited(obj, "signatureBase64", 512),
                        counter = obj.optLong("counter", 0),
                        protocolVersion = obj.optInt("protocolVersion", 1)
                    )
                    PacketType.PHOTO_MESSAGE -> PhotoMessagePacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        senderName = limitStr(obj.getString("senderName"), MAX_NAME_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        photoBase64 = optLimited(obj, "photoBase64", MAX_BASE64_LENGTH) ?: "",
                        caption = limitStr(obj.optString("caption", ""), MAX_TEXT_LENGTH),
                        fileName = obj.optString("fileName", "photo.jpg").take(MAX_FILE_NAME_LENGTH),
                        fileSize = obj.optLong("fileSize", 0L).coerceIn(0L, 100L * 1024 * 1024),
                        isGroup = obj.optBoolean("isGroup", false),
                        groupId = optLimited(obj, "groupId", MAX_ID_LENGTH),
                        groupName = optLimited(obj, "groupName", MAX_GROUP_NAME_LENGTH),
                        isDeveloper = obj.optBoolean("isDeveloper", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        signatureBase64 = optLimited(obj, "signatureBase64", 512),
                        counter = obj.optLong("counter", 0),
                        protocolVersion = obj.optInt("protocolVersion", 1)
                    )
                    PacketType.VIDEO_MESSAGE -> VideoMessagePacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        senderName = limitStr(obj.getString("senderName"), MAX_NAME_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        fileName = obj.optString("fileName", "video.mp4").take(MAX_FILE_NAME_LENGTH),
                        fileSize = obj.optLong("fileSize", 0L).coerceIn(0L, 5L * 1024 * 1024 * 1024),
                        mimeType = obj.optString("mimeType", "video/mp4").take(100),
                        fileBase64 = "",
                        caption = limitStr(obj.optString("caption", ""), MAX_TEXT_LENGTH),
                        isGroup = obj.optBoolean("isGroup", false),
                        groupId = optLimited(obj, "groupId", MAX_ID_LENGTH),
                        groupName = optLimited(obj, "groupName", MAX_GROUP_NAME_LENGTH),
                        isDeveloper = obj.optBoolean("isDeveloper", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        signatureBase64 = optLimited(obj, "signatureBase64", 512),
                        counter = obj.optLong("counter", 0),
                        protocolVersion = obj.optInt("protocolVersion", 1)
                    )
                    PacketType.FILE_MESSAGE -> FileMessagePacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        senderName = limitStr(obj.getString("senderName"), MAX_NAME_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        fileName = obj.optString("fileName", "file").take(MAX_FILE_NAME_LENGTH),
                        fileSize = obj.optLong("fileSize", 0L).coerceIn(0L, 5L * 1024 * 1024 * 1024),
                        mimeType = obj.optString("mimeType", "*/*").take(100),
                        fileBase64 = "",
                        caption = limitStr(obj.optString("caption", ""), MAX_TEXT_LENGTH),
                        isGroup = obj.optBoolean("isGroup", false),
                        groupId = optLimited(obj, "groupId", MAX_ID_LENGTH),
                        groupName = optLimited(obj, "groupName", MAX_GROUP_NAME_LENGTH),
                        isDeveloper = obj.optBoolean("isDeveloper", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        signatureBase64 = optLimited(obj, "signatureBase64", 512),
                        counter = obj.optLong("counter", 0),
                        protocolVersion = obj.optInt("protocolVersion", 1)
                    )
                    PacketType.VOICE_MESSAGE -> VoiceMessagePacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        senderName = limitStr(obj.getString("senderName"), MAX_NAME_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        audioBase64 = limitStr(obj.getString("audioBase64"), MAX_BASE64_LENGTH),
                        durationSeconds = obj.optInt("durationSeconds", 0).coerceIn(0, 7200),
                        isGroup = obj.optBoolean("isGroup", false),
                        groupId = optLimited(obj, "groupId", MAX_ID_LENGTH),
                        groupName = optLimited(obj, "groupName", MAX_GROUP_NAME_LENGTH),
                        isDeveloper = obj.optBoolean("isDeveloper", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        signatureBase64 = optLimited(obj, "signatureBase64", 512),
                        counter = obj.optLong("counter", 0),
                        protocolVersion = obj.optInt("protocolVersion", 1)
                    )
                    PacketType.ACK_DELIVERED -> AckDeliveredPacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.ACK_READ -> AckReadPacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.GROUP_ANNOUNCE -> GroupAnnouncePacket(
                        groupId = limitStr(obj.getString("groupId"), MAX_ID_LENGTH),
                        groupName = limitStr(obj.getString("groupName"), MAX_GROUP_NAME_LENGTH),
                        description = obj.optString("description", "").take(1000),
                        createdBy = limitStr(obj.getString("createdBy"), MAX_ID_LENGTH),
                        avatarColorIndex = obj.optInt("avatarColorIndex", 0).coerceIn(0, 100),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                    PacketType.CALL_OFFER -> CallOfferPacket(
                        callId = limitStr(obj.getString("callId"), MAX_ID_LENGTH),
                        callerId = limitStr(obj.getString("callerId"), MAX_ID_LENGTH),
                        callerName = limitStr(obj.getString("callerName"), MAX_NAME_LENGTH),
                        calleeId = limitStr(obj.getString("calleeId"), MAX_ID_LENGTH),
                        callerAudioPort = obj.optInt("callerAudioPort", 10002).coerceIn(1, 65535),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.CALL_ANSWER -> CallAnswerPacket(
                        callId = limitStr(obj.getString("callId"), MAX_ID_LENGTH),
                        callerId = limitStr(obj.getString("callerId"), MAX_ID_LENGTH),
                        calleeId = limitStr(obj.getString("calleeId"), MAX_ID_LENGTH),
                        accepted = obj.getBoolean("accepted"),
                        calleeAudioPort = obj.optInt("calleeAudioPort", 10004).coerceIn(0, 65535),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.CALL_RINGING -> CallRingingPacket(
                        callId = limitStr(obj.getString("callId"), MAX_ID_LENGTH),
                        callerId = limitStr(obj.getString("callerId"), MAX_ID_LENGTH),
                        calleeId = limitStr(obj.getString("calleeId"), MAX_ID_LENGTH),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.CALL_END -> CallEndPacket(
                        callId = limitStr(obj.getString("callId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        targetId = limitStr(obj.getString("targetId"), MAX_ID_LENGTH),
                        reason = obj.optString("reason", "ENDED").take(MAX_REASON_LENGTH),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.MESH_RELAY -> {
                        val visitedArr = obj.optJSONArray("visitedNodes") ?: JSONArray()
                        val visitedList = mutableListOf<String>()
                        for (i in 0 until minOf(visitedArr.length(), MAX_VISITED_NODES)) {
                            visitedList.add(visitedArr.getString(i).take(MAX_ID_LENGTH))
                        }
                        MeshRelayPacket(
                            meshPacketId = limitStr(obj.getString("meshPacketId"), MAX_ID_LENGTH),
                            originSenderId = limitStr(obj.getString("originSenderId"), MAX_ID_LENGTH),
                            originSenderName = limitStr(obj.getString("originSenderName"), MAX_NAME_LENGTH),
                            targetRecipientId = limitStr(obj.getString("targetRecipientId"), MAX_ID_LENGTH),
                            hopsRemaining = obj.optInt("hopsRemaining", 3).coerceIn(0, 15),
                            visitedNodes = visitedList,
                            encryptedPayload = limitStr(obj.getString("encryptedPayload"), MAX_MESH_PAYLOAD_LENGTH),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    }
                    PacketType.APP_UPDATE_REQUEST -> AppUpdateRequestPacket(
                        requesterDeviceId = limitStr(obj.getString("requesterDeviceId"), MAX_ID_LENGTH),
                        requesterVersionCode = obj.getInt("requesterVersionCode").coerceAtLeast(1),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.APP_UPDATE_RESPONSE -> AppUpdateResponsePacket(
                        providerDeviceId = limitStr(obj.getString("providerDeviceId"), MAX_ID_LENGTH),
                        versionCode = obj.getInt("versionCode").coerceAtLeast(1),
                        versionName = obj.getString("versionName").take(50),
                        apkSizeBytes = obj.getLong("apkSizeBytes").coerceAtLeast(0L),
                        isUpdateAvailable = obj.getBoolean("isUpdateAvailable"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.APP_UPDATE_CHUNK -> AppUpdateChunkPacket(
                        transferId = limitStr(obj.getString("transferId"), MAX_ID_LENGTH),
                        chunkIndex = obj.getInt("chunkIndex").coerceAtLeast(0),
                        totalChunks = obj.getInt("totalChunks").coerceAtLeast(1),
                        chunkDataHex = obj.getString("chunkDataHex").take(200_000),
                        isLastChunk = obj.getBoolean("isLastChunk")
                    )
                    PacketType.TRANSFER_CANCEL -> TransferCancelPacket(
                        transferId = limitStr(obj.getString("transferId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        reason = obj.optString("reason", "USER_CANCELLED").take(MAX_REASON_LENGTH),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                    PacketType.GROUP_INVITE -> GroupInvitePacket(
                        messageId = limitStr(obj.getString("messageId"), MAX_ID_LENGTH),
                        senderId = limitStr(obj.getString("senderId"), MAX_ID_LENGTH),
                        senderName = limitStr(obj.getString("senderName"), MAX_NAME_LENGTH),
                        recipientId = limitStr(obj.getString("recipientId"), MAX_ID_LENGTH),
                        inviteJson = limitStr(obj.getString("inviteJson"), MAX_INVITE_JSON_LENGTH),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        signatureBase64 = optLimited(obj, "signatureBase64", 512),
                        counter = obj.optLong("counter", 0),
                        protocolVersion = obj.optInt("protocolVersion", 1)
                    )
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun optLimited(obj: JSONObject, key: String, maxLen: Int): String? {
            return if (obj.has(key)) obj.optString(key, "").take(maxLen).ifBlank { null } else null
        }
    }
}

data class BeaconPacket(
    val deviceId: String, val displayName: String, val avatarColorIndex: Int,
    val avatarBase64: String? = null, val tcpPort: Int = 9999,
    val isDeveloper: Boolean = false, val versionCode: Int = 2,
    val versionName: String = "2.0.0", val isMeshSupported: Boolean = true,
    val publicKeyBase64: String? = null, val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.BEACON) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("deviceId", deviceId)
        obj.put("displayName", displayName); obj.put("avatarColorIndex", avatarColorIndex)
        if (avatarBase64 != null) obj.put("avatarBase64", avatarBase64)
        obj.put("tcpPort", tcpPort); obj.put("isDeveloper", isDeveloper)
        obj.put("versionCode", versionCode); obj.put("versionName", versionName)
        obj.put("isMeshSupported", isMeshSupported)
        if (publicKeyBase64 != null) obj.put("publicKeyBase64", publicKeyBase64)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class BeaconAckPacket(
    val deviceId: String, val displayName: String, val avatarColorIndex: Int,
    val avatarBase64: String? = null, val tcpPort: Int = 9999,
    val isDeveloper: Boolean = false, val versionCode: Int = 2,
    val versionName: String = "2.0.0", val isMeshSupported: Boolean = true,
    val publicKeyBase64: String? = null, val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.BEACON_ACK) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("deviceId", deviceId)
        obj.put("displayName", displayName); obj.put("avatarColorIndex", avatarColorIndex)
        if (avatarBase64 != null) obj.put("avatarBase64", avatarBase64)
        obj.put("tcpPort", tcpPort); obj.put("isDeveloper", isDeveloper)
        obj.put("versionCode", versionCode); obj.put("versionName", versionName)
        obj.put("isMeshSupported", isMeshSupported)
        if (publicKeyBase64 != null) obj.put("publicKeyBase64", publicKeyBase64)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class TextMessagePacket(
    val messageId: String, val senderId: String, val senderName: String,
    val recipientId: String, val text: String, val isGroup: Boolean = false,
    val groupId: String? = null, val groupName: String? = null,
    val isDeveloper: Boolean = false, val timestamp: Long = System.currentTimeMillis(),
    val signatureBase64: String? = null,
        val counter: Long = 0,
        val protocolVersion: Int = ProtocolVersion.CURRENT
) : NetworkPacket(PacketType.TEXT_MESSAGE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("senderName", senderName)
        obj.put("recipientId", recipientId); obj.put("text", text)
        obj.put("isGroup", isGroup)
        if (groupId != null) obj.put("groupId", groupId)
        if (groupName != null) obj.put("groupName", groupName)
        obj.put("isDeveloper", isDeveloper); obj.put("timestamp", timestamp)
        if (signatureBase64 != null) obj.put("signatureBase64", signatureBase64)
        obj.put("counter", counter)
        obj.put("protocolVersion", protocolVersion)
        return obj.toString()
    }
}

data class PhotoMessagePacket(
    val messageId: String, val senderId: String, val senderName: String,
    val recipientId: String, val photoBase64: String = "", val caption: String = "",
    val fileName: String = "photo.jpg", val fileSize: Long = 0L,
    val isGroup: Boolean = false, val groupId: String? = null,
    val groupName: String? = null, val isDeveloper: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val signatureBase64: String? = null,
        val counter: Long = 0,
        val protocolVersion: Int = ProtocolVersion.CURRENT
) : NetworkPacket(PacketType.PHOTO_MESSAGE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("senderName", senderName)
        obj.put("recipientId", recipientId); obj.put("photoBase64", photoBase64)
        obj.put("caption", caption); obj.put("fileName", fileName)
        obj.put("fileSize", fileSize); obj.put("isGroup", isGroup)
        if (groupId != null) obj.put("groupId", groupId)
        if (groupName != null) obj.put("groupName", groupName)
        obj.put("isDeveloper", isDeveloper); obj.put("timestamp", timestamp)
        if (signatureBase64 != null) obj.put("signatureBase64", signatureBase64)
        obj.put("counter", counter)
        obj.put("protocolVersion", protocolVersion)
        return obj.toString()
    }
}

data class VideoMessagePacket(
    val messageId: String, val senderId: String, val senderName: String,
    val recipientId: String, val fileName: String, val fileSize: Long,
    val mimeType: String, val fileBase64: String = "", val caption: String = "",
    val isGroup: Boolean = false, val groupId: String? = null,
    val groupName: String? = null, val isDeveloper: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val signatureBase64: String? = null,
        val counter: Long = 0,
        val protocolVersion: Int = ProtocolVersion.CURRENT
) : NetworkPacket(PacketType.VIDEO_MESSAGE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("senderName", senderName)
        obj.put("recipientId", recipientId); obj.put("fileName", fileName)
        obj.put("fileSize", fileSize); obj.put("mimeType", mimeType)
        obj.put("fileBase64", "")
        obj.put("caption", caption); obj.put("isGroup", isGroup)
        if (groupId != null) obj.put("groupId", groupId)
        if (groupName != null) obj.put("groupName", groupName)
        obj.put("isDeveloper", isDeveloper); obj.put("timestamp", timestamp)
        if (signatureBase64 != null) obj.put("signatureBase64", signatureBase64)
        obj.put("counter", counter)
        obj.put("protocolVersion", protocolVersion)
        return obj.toString()
    }
}

data class FileMessagePacket(
    val messageId: String, val senderId: String, val senderName: String,
    val recipientId: String, val fileName: String, val fileSize: Long,
    val mimeType: String, val fileBase64: String = "", val caption: String = "",
    val isGroup: Boolean = false, val groupId: String? = null,
    val groupName: String? = null, val isDeveloper: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val signatureBase64: String? = null,
        val counter: Long = 0,
        val protocolVersion: Int = ProtocolVersion.CURRENT
) : NetworkPacket(PacketType.FILE_MESSAGE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("senderName", senderName)
        obj.put("recipientId", recipientId); obj.put("fileName", fileName)
        obj.put("fileSize", fileSize); obj.put("mimeType", mimeType)
        obj.put("fileBase64", "")
        obj.put("caption", caption); obj.put("isGroup", isGroup)
        if (groupId != null) obj.put("groupId", groupId)
        if (groupName != null) obj.put("groupName", groupName)
        obj.put("isDeveloper", isDeveloper); obj.put("timestamp", timestamp)
        if (signatureBase64 != null) obj.put("signatureBase64", signatureBase64)
        obj.put("counter", counter)
        obj.put("protocolVersion", protocolVersion)
        return obj.toString()
    }
}

data class VoiceMessagePacket(
    val messageId: String, val senderId: String, val senderName: String,
    val recipientId: String, val audioBase64: String, val durationSeconds: Int,
    val isGroup: Boolean = false, val groupId: String? = null,
    val groupName: String? = null, val isDeveloper: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val signatureBase64: String? = null,
        val counter: Long = 0,
        val protocolVersion: Int = ProtocolVersion.CURRENT
) : NetworkPacket(PacketType.VOICE_MESSAGE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("senderName", senderName)
        obj.put("recipientId", recipientId); obj.put("audioBase64", audioBase64)
        obj.put("durationSeconds", durationSeconds); obj.put("isGroup", isGroup)
        if (groupId != null) obj.put("groupId", groupId)
        if (groupName != null) obj.put("groupName", groupName)
        obj.put("isDeveloper", isDeveloper); obj.put("timestamp", timestamp)
        if (signatureBase64 != null) obj.put("signatureBase64", signatureBase64)
        obj.put("counter", counter)
        obj.put("protocolVersion", protocolVersion)
        return obj.toString()
    }
}

data class AckDeliveredPacket(
    val messageId: String, val senderId: String, val recipientId: String,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.ACK_DELIVERED) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("recipientId", recipientId)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class AckReadPacket(
    val messageId: String, val senderId: String, val recipientId: String,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.ACK_READ) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("recipientId", recipientId)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class GroupAnnouncePacket(
    val groupId: String, val groupName: String, val description: String = "",
    val createdBy: String, val avatarColorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.GROUP_ANNOUNCE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("groupId", groupId)
        obj.put("groupName", groupName); obj.put("description", description)
        obj.put("createdBy", createdBy); obj.put("avatarColorIndex", avatarColorIndex)
        obj.put("createdAt", createdAt)
        return obj.toString()
    }
}

data class CallOfferPacket(
    val callId: String, val callerId: String, val callerName: String,
    val calleeId: String, val callerAudioPort: Int = 10002,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.CALL_OFFER) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("callId", callId)
        obj.put("callerId", callerId); obj.put("callerName", callerName)
        obj.put("calleeId", calleeId); obj.put("callerAudioPort", callerAudioPort)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class CallAnswerPacket(
    val callId: String, val callerId: String, val calleeId: String,
    val accepted: Boolean, val calleeAudioPort: Int = 10004,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.CALL_ANSWER) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("callId", callId)
        obj.put("callerId", callerId); obj.put("calleeId", calleeId)
        obj.put("accepted", accepted); obj.put("calleeAudioPort", calleeAudioPort)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class CallRingingPacket(
    val callId: String, val callerId: String, val calleeId: String,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.CALL_RINGING) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("callId", callId)
        obj.put("callerId", callerId); obj.put("calleeId", calleeId)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class CallEndPacket(
    val callId: String, val senderId: String, val targetId: String,
    val reason: String = "ENDED", val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.CALL_END) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("callId", callId)
        obj.put("senderId", senderId); obj.put("targetId", targetId)
        obj.put("reason", reason); obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class MeshRelayPacket(
    val meshPacketId: String, val originSenderId: String, val originSenderName: String,
    val targetRecipientId: String, val hopsRemaining: Int = 3,
    val visitedNodes: List<String> = emptyList(), val encryptedPayload: String,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.MESH_RELAY) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("meshPacketId", meshPacketId)
        obj.put("originSenderId", originSenderId); obj.put("originSenderName", originSenderName)
        obj.put("targetRecipientId", targetRecipientId); obj.put("hopsRemaining", hopsRemaining)
        val arr = JSONArray()
        visitedNodes.forEach { arr.put(it) }
        obj.put("visitedNodes", arr)
        obj.put("encryptedPayload", encryptedPayload); obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class AppUpdateRequestPacket(
    val requesterDeviceId: String, val requesterVersionCode: Int,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.APP_UPDATE_REQUEST) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("requesterDeviceId", requesterDeviceId)
        obj.put("requesterVersionCode", requesterVersionCode); obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class AppUpdateResponsePacket(
    val providerDeviceId: String, val versionCode: Int, val versionName: String,
    val apkSizeBytes: Long, val isUpdateAvailable: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.APP_UPDATE_RESPONSE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("providerDeviceId", providerDeviceId)
        obj.put("versionCode", versionCode); obj.put("versionName", versionName)
        obj.put("apkSizeBytes", apkSizeBytes); obj.put("isUpdateAvailable", isUpdateAvailable)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

data class AppUpdateChunkPacket(
    val transferId: String, val chunkIndex: Int, val totalChunks: Int,
    val chunkDataHex: String, val isLastChunk: Boolean
) : NetworkPacket(PacketType.APP_UPDATE_CHUNK) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("transferId", transferId)
        obj.put("chunkIndex", chunkIndex); obj.put("totalChunks", totalChunks)
        obj.put("chunkDataHex", chunkDataHex); obj.put("isLastChunk", isLastChunk)
        return obj.toString()
    }
}

data class TransferCancelPacket(
    val transferId: String, val senderId: String,
    val reason: String = "USER_CANCELLED", val timestamp: Long = System.currentTimeMillis()
) : NetworkPacket(PacketType.TRANSFER_CANCEL) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("transferId", transferId)
        obj.put("senderId", senderId); obj.put("reason", reason)
        obj.put("timestamp", timestamp)
        return obj.toString()
    }
}

/**
 * Phase 1.8: a signed group invitation (or membership update) for one
 * recipient. Signed per recipient like every message, because recipientId is
 * in the covered set; the invitation content itself is inviteJson, whose
 * digest is computed under the invite label, never from a re-serialisation.
 */
data class GroupInvitePacket(
    val messageId: String, val senderId: String, val senderName: String,
    val recipientId: String, val inviteJson: String,
    val timestamp: Long = System.currentTimeMillis(),
    val signatureBase64: String? = null,
    val counter: Long = 0,
    val protocolVersion: Int = ProtocolVersion.CURRENT
) : NetworkPacket(PacketType.GROUP_INVITE) {
    override fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type.name); obj.put("messageId", messageId)
        obj.put("senderId", senderId); obj.put("senderName", senderName)
        obj.put("recipientId", recipientId); obj.put("inviteJson", inviteJson)
        obj.put("timestamp", timestamp)
        if (signatureBase64 != null) obj.put("signatureBase64", signatureBase64)
        obj.put("counter", counter)
        obj.put("protocolVersion", protocolVersion)
        return obj.toString()
    }
}