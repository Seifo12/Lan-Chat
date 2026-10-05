package com.example.data.network

import android.content.Context
import android.util.Log
import com.example.data.local.ChatDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.data.local.MessageStatus
import com.lanchat.offline.messenger.R
import com.example.data.local.ReplayGuard
import com.example.data.security.InboundMessageVerifier
import com.example.data.security.InboundVerdict
import com.example.data.security.MessageCounters
import com.example.data.security.MessageSigningPayload
import com.example.data.security.ReplayWindow
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import com.example.service.LanNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream

class TcpMessagingManager(
    private val context: Context,
    private val database: ChatDatabase,
    private val userPreferences: UserPreferences
) {

    companion object {
        const val TCP_PORT = 9999
        private const val TAG = "TcpMessaging"
        private const val SOCKET_TIMEOUT = 7000
        private const val MAGIC_STREAM_HEADER = 0x5354524D // "STRM"
        private const val MAGIC_STREAM_ACK = 0x41434B31    // "ACK1"
        const val CHUNK_BUFFER_SIZE = 256 * 1024          // 256KB
        private const val MAX_JSON_PACKET_SIZE = 2 * 1024 * 1024
        private const val MAX_META_SIZE = 65_536
        private const val MAX_FILE_TRANSFER_SIZE = 5L * 1024 * 1024 * 1024 // 5GB
        private const val GROUP_FANOUT_TIMEOUT_MS = 8_000L
        private const val MAX_VOICE_BASE64_BYTES = 300 * 1024L             // 300KB حد أقصى للـ JSON
        private const val MAX_CONCURRENT_CONNECTIONS = 25
        private const val CONNECTION_TIMEOUT_MS = 30_000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var queueDrainJob: Job? = null

/**
 * Message ids this process is currently sending from the drain queue. The worker
 * polls on a timer, so without this a send slower than the poll interval would be
 * started twice. It is in memory by design: after a restart nothing is in flight,
 * and the startup sweep moves any leftover SENDING row to QUEUED.
 */
private val inFlightSends = ConcurrentHashMap.newKeySet<String>()

/**
 * Phase 1.5 durable replay protection. The in-memory nonce set cannot answer
 * "have I seen this before?" after a restart, so inbound message ids are also
 * recorded on disk for the retention window.
 */
private val replayGuard: ReplayGuard by lazy { ReplayGuard(database.seenIdDao()) }

/** Phase 1.3 persisted per-peer counters for the replay window. */
private val messageCounters: MessageCounters by lazy { MessageCounters(database.peerCounterDao()) }
    private var serverSocket: ServerSocket? = null
    private val activeConversationId = AtomicReference<String?>(null)
    private val connectionSemaphore = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    var callPacketListener: ((packet: NetworkPacket, senderIp: String) -> Unit)? = null

    var callSignalListener: ((packet: NetworkPacket, senderIp: String) -> Unit)?
        get() = callPacketListener
        set(value) { callPacketListener = value }

    var meshPacketListener: ((packet: MeshRelayPacket, senderIp: String) -> Unit)? = null
    var nearbyFallbackSender: ((targetDeviceId: String, packet: NetworkPacket) -> Boolean)? = null
    var nearbyFileFallbackSender: ((targetDeviceId: String, file: File, metaPacket: NetworkPacket) -> Boolean)? = null

    private val _activeTransfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, TransferProgress>> = _activeTransfers.asStateFlow()

    private val cancelledTransferIds = Collections.synchronizedSet(HashSet<String>())

    fun getActiveConversationId(): String? = activeConversationId.get()

    fun setActiveConversation(conversationId: String?) {
        activeConversationId.set(conversationId)
        if (conversationId != null) {
            scope.launch {
                database.chatMessageDao().markIncomingMessagesAsRead(conversationId)
                val peer = database.contactDao().getContactById(conversationId)
                if (peer != null && peer.isOnline) {
                    sendReadReceipt(peer.ipAddress, peer.tcpPort, conversationId)
                    retryPendingMessagesForPeer(peer.deviceId, peer.ipAddress, peer.tcpPort)
                }
            }
        }
    }

    fun start() {
        startServer()
        startQueueDrainWorker()
    }

    fun stop() {
        serverJob?.cancel()
        queueDrainJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun startQueueDrainWorker() {
        queueDrainJob?.cancel()
        queueDrainJob = scope.launch {
            while (isActive) {
                delay(4000)
                try {
                    val pendingList = database.chatMessageDao().getAllPendingDirectMessages()
                    if (pendingList.isNotEmpty()) {
                        val grouped = pendingList.groupBy { it.conversationId }
                        for ((peerId, messages) in grouped) {
                            val contact = database.contactDao().getContactById(peerId)
                            if (contact != null && contact.isOnline) {
                                deliverPendingMessages(contact.deviceId, contact.ipAddress, contact.tcpPort, messages)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in queue drain worker: ${e.message}")
                }
            }
        }
    }

    fun retryPendingMessagesForPeer(deviceId: String, ip: String, port: Int) {
        scope.launch {
            try {
                val pending = database.chatMessageDao().getPendingMessagesForConversation(deviceId)
                if (pending.isNotEmpty()) {
                    deliverPendingMessages(deviceId, ip, port, pending)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error retrying pending messages for $deviceId: ${e.message}")
            }
        }
    }

    private suspend fun deliverPendingMessages(
        peerId: String, ip: String, port: Int, messages: List<ChatMessageEntity>
    ) {
        val pairwise = EncryptionManager.getPairwiseManager()
        if (pairwise != null && !pairwise.hasSession(peerId)) {
            val contact = database.contactDao().getContactById(peerId)
            if (!contact?.publicKeyBase64.isNullOrBlank()) {
                pairwise.establishSession(peerId, contact.publicKeyBase64!!)
            }
        }

        for (msg in messages) {
            // Skip a row this worker is already sending. The poll runs every few
            // seconds, so a send slower than the interval would otherwise be
            // started a second time and the peer would receive it twice. SENDING
            // stays selectable because the legacy contract is that a failed send
            // stays SENDING so the queue can retry it, so the in-flight set rather
            // than the status column is what prevents a duplicate send.
            if (!inFlightSends.add(msg.id)) continue

            // QUEUED rows older than the auto-send window are left for the user.
            if (msg.status == MessageStatus.QUEUED &&
                !MessageStatus.shouldAutoSendQueued(msg.receivedAt)
            ) {
                inFlightSends.remove(msg.id)
                continue
            }

            database.chatMessageDao().updateMessageStatus(msg.id, MessageStatus.SENDING)

            val success = try {
                sendPendingMessage(msg, peerId, ip, port)
            } finally {
                inFlightSends.remove(msg.id)
            }

            if (success) {
                database.chatMessageDao().updateMessageStatus(msg.id, MessageStatus.SENT)
            } else {
                break
            }
        }
    }

    private suspend fun sendPendingMessage(
        msg: ChatMessageEntity, peerId: String, ip: String, port: Int
    ): Boolean {
        return when {
                msg.isPhoto && !msg.photoPath.isNullOrBlank() -> {
                    val file = File(msg.photoPath)
                    if (file.exists()) {
                        streamFileDirect(
                            targetIp = ip, targetPort = port, transferId = "xfer_${msg.id}",
                            messageId = msg.id, senderId = userPreferences.deviceId,
                            senderName = userPreferences.displayName, recipientId = peerId,
                            fileName = file.name, fileSize = file.length(),
                            mimeType = "image/jpeg", caption = msg.text,
                            isVideo = false, isPhoto = true, isDeveloper = userPreferences.isDeveloper, file = file
                        )
                    } else false
                }
                msg.isVideo && !msg.filePath.isNullOrBlank() -> {
                    val file = File(msg.filePath)
                    if (file.exists()) {
                        streamFileDirect(
                            targetIp = ip, targetPort = port, transferId = "xfer_${msg.id}",
                            messageId = msg.id, senderId = userPreferences.deviceId,
                            senderName = userPreferences.displayName, recipientId = peerId,
                            fileName = msg.fileName ?: "video.mp4", fileSize = file.length(),
                            mimeType = msg.mimeType ?: "video/mp4", caption = msg.text,
                            isVideo = true, isPhoto = false, isDeveloper = userPreferences.isDeveloper, file = file
                        )
                    } else false
                }
                msg.isVoice && !msg.filePath.isNullOrBlank() -> {
                    val file = File(msg.filePath)
                    if (file.exists()) {
                        if (file.length() > MAX_VOICE_BASE64_BYTES) {
                            streamFileDirect(
                                targetIp = ip, targetPort = port, transferId = "xfer_${msg.id}",
                                messageId = msg.id, senderId = userPreferences.deviceId,
                                senderName = userPreferences.displayName, recipientId = peerId,
                                fileName = "voice_${msg.id}.m4a", fileSize = file.length(),
                                mimeType = "audio/mp4", caption = msg.text,
                                isVideo = false, isPhoto = false, isVoice = true,
                                audioDurationSeconds = msg.audioDurationSeconds,
                                isDeveloper = userPreferences.isDeveloper, file = file
                            )
                        } else {
                            val bytes = file.readBytes()
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            val sig = EncryptionManager.getPairwiseManager()?.signData(bytes)
                            val packet = VoiceMessagePacket(
                                messageId = msg.id, senderId = userPreferences.deviceId,
                                senderName = userPreferences.displayName, recipientId = peerId,
                                audioBase64 = base64, durationSeconds = msg.audioDurationSeconds,
                                isDeveloper = userPreferences.isDeveloper, timestamp = msg.timestamp,
                                signatureBase64 = sig
                            )
                            sendPacketDirect(ip, port, packet)
                        }
                    } else false
                }
                msg.isFile && !msg.filePath.isNullOrBlank() -> {
                    val file = File(msg.filePath)
                    if (file.exists()) {
                        val isVideo = FileUtils.isVideoMime(msg.mimeType, msg.fileName ?: "")
                        streamFileDirect(
                            targetIp = ip, targetPort = port, transferId = "xfer_${msg.id}",
                            messageId = msg.id, senderId = userPreferences.deviceId,
                            senderName = userPreferences.displayName, recipientId = peerId,
                            fileName = msg.fileName ?: "file", fileSize = file.length(),
                            mimeType = msg.mimeType ?: "*/*", caption = msg.text,
                            isVideo = isVideo, isPhoto = false, isDeveloper = userPreferences.isDeveloper, file = file
                        )
                    } else false
                }
                else -> {
                    // Phase 1.3: sign the full field set, including the counter.
                    val signed = signForRecipient(
                        recipientId = peerId,
                        messageId = msg.id,
                        timestamp = msg.timestamp,
                        content = msg.text.toByteArray(Charsets.UTF_8),
                    )
                    if (signed == null) {
                        false
                    } else {
                        val packet = TextMessagePacket(
                            messageId = msg.id, senderId = userPreferences.deviceId,
                            senderName = userPreferences.displayName, recipientId = peerId,
                            text = msg.text, isDeveloper = userPreferences.isDeveloper,
                            timestamp = msg.timestamp,
                            signatureBase64 = signed.second, counter = signed.first
                        )
                        sendPacketDirect(ip, port, packet, peerId)
                    }
                }
            }
    }

    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(TCP_PORT))
                }
                Log.d(TAG, "TCP Server listening on port $TCP_PORT")
                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: continue
                    launch { handleIncomingConnection(clientSocket) }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Server socket loop error: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            if (!connectionSemaphore.tryAcquire()) {
                Log.w(TAG, "Max TCP connections reached, closing socket from ${socket.inetAddress.hostAddress}")
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }
            try {
                socket.soTimeout = CONNECTION_TIMEOUT_MS
                val dataInputStream = DataInputStream(socket.getInputStream())
                val headerOrLength = dataInputStream.readInt()
                if (headerOrLength == MAGIC_STREAM_HEADER) {
                    handleIncomingFileStream(socket, dataInputStream)
                } else if (headerOrLength in 1..MAX_JSON_PACKET_SIZE) {
                    val buffer = ByteArray(headerOrLength)
                    dataInputStream.readFully(buffer)
                    val rawPayload = String(buffer, Charsets.UTF_8)
                    val packet = NetworkPacket.fromJson(rawPayload)
                    if (packet != null) {
                        val senderIp = socket.inetAddress.hostAddress ?: ""
                        processReceivedPacket(packet, senderIp)
                    }
                } else {
                    Log.w(TAG, "Packet rejected due to size limit: $headerOrLength bytes")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Incoming connection ended: ${e.message}")
            } finally {
                connectionSemaphore.release()
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun handleIncomingFileStream(socket: Socket, dataInputStream: DataInputStream) {
        val senderIp = socket.inetAddress.hostAddress ?: ""
        val metaLength = dataInputStream.readInt()
        if (metaLength <= 0 || metaLength > MAX_META_SIZE) {
            Log.w(TAG, "Rejected stream metadata size: $metaLength")
            return
        }
        val metaBytes = ByteArray(metaLength)
        dataInputStream.readFully(metaBytes)
        val rawMeta = String(metaBytes, Charsets.UTF_8)

        val decryptedMeta = if (EncryptionManager.isEncrypted(rawMeta)) {
            EncryptionManager.decrypt(rawMeta)
        } else {
            rawMeta
        }

        val obj = JSONObject(decryptedMeta)
        val transferId = obj.getString("transferId")
        val messageId = obj.getString("messageId")
        val senderId = obj.getString("senderId")
        val senderName = obj.getString("senderName")
        val recipientId = obj.getString("recipientId")
        val fileName = obj.getString("fileName")
        val fileSize = obj.optLong("fileSize", 0L)
        val mimeType = obj.optString("mimeType", "*/*")
        val caption = obj.optString("caption", "")
        val isVideo = obj.optBoolean("isVideo", false)
        val isPhoto = obj.optBoolean("isPhoto", false)
        val isVoice = obj.optBoolean("isVoice", false)
        val audioDurationSeconds = obj.optInt("audioDurationSeconds", 0)
        val isGroup = obj.optBoolean("isGroup", false)
        val groupId = if (obj.has("groupId")) obj.getString("groupId") else null
        val groupName = if (obj.has("groupName")) obj.getString("groupName") else null
        val isDeveloper = obj.optBoolean("isDeveloper", false)
        val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

        if (fileSize <= 0 || fileSize > MAX_FILE_TRANSFER_SIZE) {
            Log.w(TAG, "Rejected incoming file size: $fileSize")
            return
        }

        val ivHex = obj.getString("ivHex")
        val baseIv = ByteArray(ivHex.length / 2) { i ->
            ivHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val pairwise = EncryptionManager.getPairwiseManager()
        // Phase 1.2: fail closed. This used to fall back to the device storage
        // key, so an incoming file could be written to disk under a key that has
        // nothing to do with the sender, and the receiver was never told.
        val streamKey = pairwise?.getStreamKeyForPeer(senderId)
        if (streamKey == null) {
            Log.e(TAG, "Refusing inbound transfer from $senderId: no pairwise " +
                "stream key, so it cannot be decrypted for this sender")
            return
        }

        val subFolder = when {
            isPhoto -> "chat_photos"
            isVideo -> "chat_videos"
            isVoice -> "received_voices"
            else -> "chat_files"
        }
        val dir = File(context.filesDir, subFolder).apply { if (!exists()) mkdirs() }
        val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._\\-\u0600-\u06FF]"), "_")

        val tempFile = File(dir, "temp_${transferId}_$sanitizedName")
        val rawExistingBytes = if (tempFile.exists()) tempFile.length() else 0L

        // محاذاة الاستئناف التشفيرية عند كتل 16 بايت
        val validResumeOffset = (rawExistingBytes / 16L) * 16L
        if (validResumeOffset < rawExistingBytes && tempFile.exists()) {
            try {
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.setLength(validResumeOffset)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error truncating file to 16-byte boundary: ${e.message}")
            }
        }

        val dataOutputStream = DataOutputStream(socket.getOutputStream())
        dataOutputStream.writeLong(validResumeOffset)
        dataOutputStream.flush()

        val blockOffset = validResumeOffset / 16L
        val cipher = EncryptionManager.createStreamDecryptCipher(streamKey, baseIv, blockOffset)
        val cipherIn = CipherInputStream(socket.getInputStream(), cipher)
        val fileOut = FileOutputStream(tempFile, true)

        _activeTransfers.value = _activeTransfers.value + (transferId to TransferProgress(
            transferId = transferId, fileName = fileName, bytesTransferred = validResumeOffset,
            totalBytes = fileSize, isUpload = false, isVideo = isVideo
        ))

        val buffer = ByteArray(CHUNK_BUFFER_SIZE)
        var bytesReceived = validResumeOffset
        val startTime = System.currentTimeMillis()
        var lastProgressTime = startTime

        try {
            while (bytesReceived < fileSize) {
                if (cancelledTransferIds.contains(transferId)) {
                    tempFile.delete()
                    break
                }
                val toRead = minOf(buffer.size.toLong(), fileSize - bytesReceived).toInt()
                val read = cipherIn.read(buffer, 0, toRead)
                if (read == -1) break
                fileOut.write(buffer, 0, read)
                bytesReceived += read

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 250 || bytesReceived == fileSize) {
                    val elapsedSec = maxOf(0.001, (now - startTime) / 1000.0)
                    val speed = ((bytesReceived - validResumeOffset) / elapsedSec).toLong().coerceAtLeast(0L)
                    val remaining = maxOf(0L, fileSize - bytesReceived)
                    val eta = if (speed > 0) remaining / speed else 0L
                    _activeTransfers.value = _activeTransfers.value + (transferId to TransferProgress(
                        transferId = transferId, fileName = fileName, bytesTransferred = bytesReceived,
                        totalBytes = fileSize, speedBytesPerSec = speed, etaSeconds = eta,
                        isUpload = false, isVideo = isVideo, isCompleted = bytesReceived == fileSize
                    ))
                    lastProgressTime = now
                }
            }
        } finally {
            try { fileOut.flush(); fileOut.close() } catch (_: Exception) {}
        }

        if (bytesReceived == fileSize) {
            val finalFile = File(dir, "${System.currentTimeMillis()}_$sanitizedName")
            tempFile.renameTo(finalFile)

            try {
                val ackOut = DataOutputStream(socket.getOutputStream())
                ackOut.writeInt(MAGIC_STREAM_ACK)
                ackOut.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Notice sending stream ACK: ${e.message}")
            }

            val conversationKey = if (isGroup) (groupId ?: "group_general") else senderId
            if (isGroup && groupId != null) {
                val existingGroup = database.groupDao().getGroupById(groupId)
                if (existingGroup == null) {
                    database.groupDao().insertOrUpdateGroup(GroupEntity(
                        groupId = groupId, groupName = groupName ?: "مجموعة عائمة",
                        createdBy = senderName, createdAt = timestamp
                    ))
                }
            }

            val entity = ChatMessageEntity(
                id = messageId, conversationId = conversationKey, senderId = senderId,
                senderName = senderName, recipientId = recipientId,
                text = if (isVoice) "🎤 رسالة صوتية ($audioDurationSeconds ث)" else caption,
                photoPath = if (isPhoto) finalFile.absolutePath else null,
                filePath = if (!isPhoto) finalFile.absolutePath else null,
                fileName = fileName, fileSize = fileSize,
                mimeType = mimeType, isVideo = isVideo, isFile = (!isVideo && !isPhoto && !isVoice),
                isPhoto = isPhoto, isVoice = isVoice, audioDurationSeconds = audioDurationSeconds,
                timestamp = timestamp, isFromMe = false,
                status = if (activeConversationId.get() == conversationKey) MessageStatus.READ else MessageStatus.DELIVERED,
                isGroup = isGroup, groupName = groupName, isSenderDeveloper = isDeveloper
            )
            database.chatMessageDao().insertMessage(entity)
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageReceived(context)
            if (userPreferences.isSoundEnabled) FeedbackUtils.playNotificationTone()
            if (activeConversationId.get() != conversationKey) {
                val notifText = when {
                    isVoice -> "🎤 رسالة صوتية ($audioDurationSeconds ث)"
                    isPhoto -> "📷 صورة: ${caption.ifBlank { fileName }}"
                    isVideo -> "🎬 فيديو: $fileName"
                    else -> "📄 ملف: $fileName"
                }
                LanNotificationHelper.showMessageNotification(
                    context = context,
                    senderName = if (isGroup) "$senderName [$groupName]" else senderName,
                    messageText = notifText,
                    conversationId = conversationKey, isPhoto = isPhoto
                )
            }
            if (!isGroup) {
                sendDeliveredReceipt(senderIp, TCP_PORT, messageId, senderId)
                if (activeConversationId.get() == senderId) {
                    sendSingleReadReceipt(senderIp, TCP_PORT, messageId, senderId)
                }
            }
            scope.launch {
                delay(2000)
                _activeTransfers.value = _activeTransfers.value - transferId
            }
        }
    }

    private suspend fun processReceivedPacket(packet: NetworkPacket, senderIp: String) {
        when (packet) {
            is BeaconPacket -> {
                if (packet.deviceId != userPreferences.deviceId) {
                    val known = recordLanPeer(
                        deviceId = packet.deviceId, displayName = packet.displayName,
                        senderIp = senderIp, tcpPort = packet.tcpPort,
                        avatarColorIndex = packet.avatarColorIndex, isDeveloper = packet.isDeveloper,
                        versionCode = packet.versionCode, versionName = packet.versionName,
                        publicKeyBase64 = packet.publicKeyBase64,
                    )
                    // Only answer people we already know. Replying to every beacon
                    // is what made each phone announce itself to every other one.
                    if (known) {
                    val ack = BeaconAckPacket(
                        deviceId = userPreferences.deviceId, displayName = userPreferences.displayName,
                        avatarColorIndex = userPreferences.avatarColorIndex, tcpPort = TCP_PORT,
                        isDeveloper = userPreferences.isDeveloper, versionCode = userPreferences.appVersionCode,
                        versionName = userPreferences.appVersionName, isMeshSupported = userPreferences.isMeshModeEnabled,
                        publicKeyBase64 = EncryptionManager.getPairwiseManager()?.getMyPublicKeyBase64()
                    )
                    sendPacketDirect(senderIp, packet.tcpPort, ack)
                    }
                }
            }
            is BeaconAckPacket -> {
                if (packet.deviceId != userPreferences.deviceId) {
                    recordLanPeer(
                        deviceId = packet.deviceId, displayName = packet.displayName,
                        senderIp = senderIp, tcpPort = packet.tcpPort,
                        avatarColorIndex = packet.avatarColorIndex, isDeveloper = packet.isDeveloper,
                        versionCode = packet.versionCode, versionName = packet.versionName,
                        publicKeyBase64 = packet.publicKeyBase64,
                    )
                }
            }
            is CallOfferPacket, is CallAnswerPacket, is CallRingingPacket, is CallEndPacket -> {
                callPacketListener?.invoke(packet, senderIp)
            }
            is TextMessagePacket -> {
                // Phase 1.5: a message id already recorded inside the retention
                // window is a replay. This is checked before any processing, and
                // the record deliberately outlives the conversation so deleting a
                // chat does not hand an attacker a clean slate.
                if (!replayGuard.tryAccept(packet.senderId, packet.messageId)) {
                    Log.w(TAG, "Dropping replayed message ${packet.messageId} " +
                        "from ${packet.senderId}")
                    return
                }

                // Phase 1.3: the signature is enforced, not merely logged. A
                // packet that cannot be authenticated, or that falls outside the
                // replay window, is refused here and never reaches the database.
                // Before this a mismatch produced a log line and the message was
                // delivered anyway, and a missing signature was not checked at all.
                val inboundSender = database.contactDao().getContactById(packet.senderId)
                val senderKey = inboundSender?.publicKeyBase64
                    ?: EncryptionManager.getPairwiseManager()
                        ?.peerPublicKeyFor(packet.senderId)

                val verdict = InboundMessageVerifier.verify(
                    candidate = InboundMessageVerifier.Candidate(
                        senderId = packet.senderId,
                        recipientId = userPreferences.deviceId,
                        messageId = packet.messageId,
                        timestamp = packet.timestamp,
                        counter = packet.counter,
                        protocolVersion = packet.protocolVersion,
                        signatureBase64 = packet.signatureBase64,
                        contentDigestHex = MessageSigningPayload.digestHex(
                            packet.text.toByteArray(Charsets.UTF_8)
                        ),
                        alreadySeen = replayGuard.wasRecorded(packet.senderId, packet.messageId),
                    ),
                    senderPublicKeyBase64 = senderKey,
                    replayDecision = ReplayWindow(
                        restoredHighWaterMark = messageCounters.highWaterMarkOf(packet.senderId)
                    ).observe(
                        packet.counter,
                        alreadySeen = replayGuard.wasRecorded(packet.senderId, packet.messageId),
                    ),
                )

                when (verdict) {
                    is InboundVerdict.Accepted -> {
                        replayGuard.tryAccept(packet.senderId, packet.messageId)
                        messageCounters.raiseHighWaterMark(packet.senderId, packet.counter)
                    }
                    is InboundVerdict.UnsupportedProtocol -> {
                        Log.w(TAG, "Refusing ${packet.messageId}: protocol ${verdict.version} is older than " +
                            ProtocolVersion.MINIMUM)
                        notePeerNeedsUpdate(packet.senderId, packet.recipientId, inboundSender?.displayName)
                        return
                    }
                    InboundVerdict.MissingSignature -> {
                        Log.w(TAG, "Refusing unsigned message ${packet.messageId} from ${packet.senderId}")
                        return
                    }
                    InboundVerdict.UnknownSender -> {
                        Log.w(TAG, "Refusing ${packet.messageId}: no key for ${packet.senderId}")
                        return
                    }
                    InboundVerdict.BadSignature -> {
                        Log.w(TAG, "Refusing ${packet.messageId}: signature does not verify")
                        return
                    }
                    InboundVerdict.Duplicate -> {
                        Log.w(TAG, "Refusing duplicate ${packet.messageId}")
                        return
                    }
                    InboundVerdict.TooOld -> {
                        Log.w(TAG, "Refusing ${packet.messageId}: counter outside the replay window")
                        return
                    }
                }

                val conversationKey = if (packet.isGroup) (packet.groupId ?: "group_general") else packet.senderId
                if (packet.isGroup && packet.groupId != null) {
                    val existingGroup = database.groupDao().getGroupById(packet.groupId)
                    if (existingGroup == null) {
                        database.groupDao().insertOrUpdateGroup(GroupEntity(
                            groupId = packet.groupId, groupName = packet.groupName ?: "مجموعة عائمة",
                            createdBy = packet.senderName, createdAt = packet.timestamp
                        ))
                    }
                }
                val entity = ChatMessageEntity(
                    id = packet.messageId, conversationId = conversationKey, senderId = packet.senderId,
                    senderName = packet.senderName, recipientId = packet.recipientId, text = packet.text,
                    isPhoto = false, timestamp = packet.timestamp, isFromMe = false,
                    status = if (activeConversationId.get() == conversationKey) MessageStatus.READ else MessageStatus.DELIVERED,
                    isGroup = packet.isGroup, groupName = packet.groupName, isSenderDeveloper = packet.isDeveloper
                )
                database.chatMessageDao().insertMessage(entity)
                if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageReceived(context)
                if (userPreferences.isSoundEnabled) FeedbackUtils.playNotificationTone()
                if (activeConversationId.get() != conversationKey) {
                    LanNotificationHelper.showMessageNotification(
                        context = context,
                        senderName = if (packet.isGroup) "${packet.senderName} [${packet.groupName}]" else packet.senderName,
                        messageText = packet.text, conversationId = conversationKey, isPhoto = false
                    )
                }
                if (!packet.isGroup) {
                    sendDeliveredReceipt(senderIp, TCP_PORT, packet.messageId, packet.senderId)
                    if (activeConversationId.get() == packet.senderId) {
                        sendSingleReadReceipt(senderIp, TCP_PORT, packet.messageId, packet.senderId)
                    }
                }
            }
            is PhotoMessagePacket -> {
                if (packet.photoBase64.isNotBlank()) {
                    val conversationKey = if (packet.isGroup) (packet.groupId ?: "group_general") else packet.senderId
                    val photoFile = ImageUtils.base64ToImageFile(context, packet.photoBase64)
                    val entity = ChatMessageEntity(
                        id = packet.messageId, conversationId = conversationKey, senderId = packet.senderId,
                        senderName = packet.senderName, recipientId = packet.recipientId, text = packet.caption,
                        photoPath = photoFile?.absolutePath, isPhoto = true, timestamp = packet.timestamp,
                        isFromMe = false,
                        status = if (activeConversationId.get() == conversationKey) MessageStatus.READ else MessageStatus.DELIVERED,
                        isGroup = packet.isGroup, groupName = packet.groupName, isSenderDeveloper = packet.isDeveloper
                    )
                    database.chatMessageDao().insertMessage(entity)
                    if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageReceived(context)
                    if (userPreferences.isSoundEnabled) FeedbackUtils.playNotificationTone()
                    if (activeConversationId.get() != conversationKey) {
                        LanNotificationHelper.showMessageNotification(
                            context = context,
                            senderName = if (packet.isGroup) "${packet.senderName} [${packet.groupName}]" else packet.senderName,
                            messageText = packet.caption.ifBlank { "📷 صورة جديدة" },
                            conversationId = conversationKey, isPhoto = true
                        )
                    }
                if (!packet.isGroup) {
                    sendDeliveredReceipt(senderIp, TCP_PORT, packet.messageId, packet.senderId)
                    if (activeConversationId.get() == packet.senderId) {
                        sendSingleReadReceipt(senderIp, TCP_PORT, packet.messageId, packet.senderId)
                    }
                }
                }
            }
            // سد ثغرة حزم الفيديوهات والملفات المسقطة عبر الحفظ المباشر
            is VideoMessagePacket, is FileMessagePacket -> {
                val isVid = packet is VideoMessagePacket
                val fName = if (packet is VideoMessagePacket) packet.fileName else (packet as FileMessagePacket).fileName
                val fSize = if (packet is VideoMessagePacket) packet.fileSize else (packet as FileMessagePacket).fileSize
                val mMime = if (packet is VideoMessagePacket) packet.mimeType else (packet as FileMessagePacket).mimeType
                val fCaption = if (packet is VideoMessagePacket) packet.caption else (packet as FileMessagePacket).caption
                val mId = if (packet is VideoMessagePacket) packet.messageId else (packet as FileMessagePacket).messageId
                val sId = if (packet is VideoMessagePacket) packet.senderId else (packet as FileMessagePacket).senderId
                val sName = if (packet is VideoMessagePacket) packet.senderName else (packet as FileMessagePacket).senderName
                val rId = if (packet is VideoMessagePacket) packet.recipientId else (packet as FileMessagePacket).recipientId
                val isGrp = if (packet is VideoMessagePacket) packet.isGroup else (packet as FileMessagePacket).isGroup
                val grpId = if (packet is VideoMessagePacket) packet.groupId else (packet as FileMessagePacket).groupId
                val grpName = if (packet is VideoMessagePacket) packet.groupName else (packet as FileMessagePacket).groupName
                val pTimestamp = if (packet is VideoMessagePacket) packet.timestamp else (packet as FileMessagePacket).timestamp
                val pIsDeveloper = if (packet is VideoMessagePacket) packet.isDeveloper else (packet as FileMessagePacket).isDeveloper
                val conversationKey = if (isGrp && !grpId.isNullOrBlank()) grpId else sId

                if (isGrp && grpId != null) {
                    val existingGroup = database.groupDao().getGroupById(grpId)
                    if (existingGroup == null) {
                        database.groupDao().insertOrUpdateGroup(GroupEntity(
                            groupId = grpId, groupName = grpName ?: "مجموعة عائمة",
                            createdBy = sName, createdAt = pTimestamp
                        ))
                    }
                }

                val entity = ChatMessageEntity(
                    id = mId, conversationId = conversationKey, senderId = sId,
                    senderName = sName, recipientId = rId, text = fCaption,
                    fileName = fName, fileSize = fSize, mimeType = mMime,
                    isVideo = isVid, isFile = !isVid, isPhoto = false,
                    isFromMe = false,
                    status = if (activeConversationId.get() == conversationKey) MessageStatus.READ else MessageStatus.DELIVERED,
                    isGroup = isGrp, groupName = grpName, isSenderDeveloper = pIsDeveloper,
                    timestamp = pTimestamp
                )
                database.chatMessageDao().insertMessage(entity)
                if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageReceived(context)
                if (userPreferences.isSoundEnabled) FeedbackUtils.playNotificationTone()
                if (activeConversationId.get() != conversationKey) {
                    LanNotificationHelper.showMessageNotification(
                        context = context,
                        senderName = if (isGrp) "$sName [$grpName]" else sName,
                        messageText = if (isVid) "🎬 مقطع فيديو: $fName" else "📎 ملف: $fName",
                        conversationId = conversationKey, isPhoto = false
                    )
                }
                if (!isGrp) {
                    sendDeliveredReceipt(senderIp, TCP_PORT, mId, sId)
                    if (activeConversationId.get() == sId) {
                        sendSingleReadReceipt(senderIp, TCP_PORT, mId, sId)
                    }
                }
            }
            is VoiceMessagePacket -> {
                val conversationKey = if (packet.isGroup) (packet.groupId ?: "group_general") else packet.senderId
                if (packet.isGroup && packet.groupId != null) {
                    val existingGroup = database.groupDao().getGroupById(packet.groupId)
                    if (existingGroup == null) {
                        database.groupDao().insertOrUpdateGroup(GroupEntity(
                            groupId = packet.groupId, groupName = packet.groupName ?: "مجموعة عائمة",
                            createdBy = packet.senderName, createdAt = packet.timestamp
                        ))
                    }
                }
                val voiceBytes = android.util.Base64.decode(packet.audioBase64, android.util.Base64.DEFAULT)
                val alreadyAtRestEncrypted = EncryptionManager.isEncryptedBytes(voiceBytes)
                val voiceFile: File?
                if (alreadyAtRestEncrypted) {
                    // مشفّر بمفتاح المُرسِل — مستحيل نحلّه هنا، فبنرفضه بدل ما نخزّن
                    // ملف مش فايحل playback بصمت على جهاز المستقبِل.
                    Log.w(TAG, "Rejecting voice ${packet.messageId}: encrypted at rest by the sender's key")
                    voiceFile = null
                } else {
                    val dir = File(context.filesDir, "received_voices").apply { mkdirs() }
                    val f = File(dir, "voice_${packet.messageId}.m4a")
                    // القناة نفسها مشفّرة، فبنخزّن بمفتاح الجهاز ده عشان نقدر نلعبه.
                    f.writeBytes(EncryptionManager.encryptBytes(voiceBytes))
                    voiceFile = f
                }

                if (voiceFile != null) {
                val entity = ChatMessageEntity(
                    id = packet.messageId, conversationId = conversationKey, senderId = packet.senderId,
                    senderName = packet.senderName, recipientId = packet.recipientId,
                    text = "🎤 رسالة صوتية (${packet.durationSeconds} ث)",
                    filePath = voiceFile.absolutePath, isVoice = true,
                    audioDurationSeconds = packet.durationSeconds, timestamp = packet.timestamp,
                    isFromMe = false,
                    status = if (activeConversationId.get() == conversationKey) MessageStatus.READ else MessageStatus.DELIVERED,
                    isGroup = packet.isGroup, groupName = packet.groupName, isSenderDeveloper = packet.isDeveloper
                )
                database.chatMessageDao().insertMessage(entity)
                if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageReceived(context)
                if (userPreferences.isSoundEnabled) FeedbackUtils.playNotificationTone()
                if (activeConversationId.get() != conversationKey) {
                    LanNotificationHelper.showMessageNotification(
                        context = context,
                        senderName = if (packet.isGroup) "${packet.senderName} [${packet.groupName}]" else packet.senderName,
                        messageText = "🎤 رسالة صوتية (${packet.durationSeconds} ث)",
                        conversationId = conversationKey, isPhoto = false
                    )
                }
                if (!packet.isGroup) {
                    sendDeliveredReceipt(senderIp, TCP_PORT, packet.messageId, packet.senderId)
                    if (activeConversationId.get() == packet.senderId) {
                        sendSingleReadReceipt(senderIp, TCP_PORT, packet.messageId, packet.senderId)
                    }
                }
                }
            }
            is MeshRelayPacket -> {
                meshPacketListener?.invoke(packet, senderIp)
            }
            is TransferCancelPacket -> {
                cancelledTransferIds.add(packet.transferId)
                _activeTransfers.value = _activeTransfers.value - packet.transferId
            }
            is AckDeliveredPacket -> {
                val existing = database.chatMessageDao().getMessageById(packet.messageId)
                if (existing != null && existing.status != MessageStatus.READ) {
                    database.chatMessageDao().updateMessageStatus(packet.messageId, MessageStatus.DELIVERED)
                }
            }
            is AckReadPacket -> {
                if (packet.messageId == "ALL") {
                    database.chatMessageDao().markIncomingMessagesAsRead(packet.senderId)
                } else {
                    database.chatMessageDao().updateMessageStatus(packet.messageId, MessageStatus.READ)
                }
            }
            is AppUpdateRequestPacket -> {
                scope.launch {
                    val localSourceApk = File(context.applicationInfo.sourceDir)
                    if (localSourceApk.exists() && userPreferences.appVersionCode >= packet.requesterVersionCode) {
                        streamFileDirect(
                            targetIp = senderIp, targetPort = TCP_PORT,
                            transferId = "apk_transfer_${System.currentTimeMillis()}",
                            messageId = "apk_update_${System.currentTimeMillis()}",
                            senderId = userPreferences.deviceId, senderName = userPreferences.displayName,
                            recipientId = packet.requesterDeviceId,
                            fileName = "LAN_Chat_v${userPreferences.appVersionName}.apk",
                            fileSize = localSourceApk.length(),
                            mimeType = "application/vnd.android.package-archive",
                            caption = "مشاركة التطبيق عبر الشبكة المحلية",
                            isVideo = false, isPhoto = false, isDeveloper = userPreferences.isDeveloper, file = localSourceApk,
                            targetPeerId = packet.requesterDeviceId
                        )
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Step 1.1: resend a message the user explicitly retried, keeping the
     * original messageId so the peer recognises it as the same message rather
     * than a new one.
     *
     * There is no fallback here by design. Each media type is dispatched through
     * its own normal send path, and whatever that path returns is mapped onto a
     * status by [MessageStatus.forSendOutcome]. A peer that cannot be reached
     * becomes QUEUED, not SENT.
     */
    suspend fun retryMessage(message: ChatMessageEntity): MessageStatus {
        if (message.isGroup) {
            // Group fan-out has its own status handling and is retried by the
            // queue worker, not by the per-message button.
            return MessageStatus.QUEUED
        }
        // This method is the single writer of the status for a retry: it marks
        // SENDING on the way in and the real outcome on the way out, so the row
        // is updated exactly once per outcome. It also owns the failure path, so
        // the row can never be stranded on SENDING.
        database.chatMessageDao().updateMessageStatus(message.id, MessageStatus.SENDING)

        return try {
            retryMessageAttempt(message)
        } catch (error: Exception) {
            Log.e(TAG, "Retry of ${message.id} failed: ${error.message}")
            database.chatMessageDao().updateMessageStatus(message.id, MessageStatus.FAILED)
            MessageStatus.FAILED
        }
    }

    private suspend fun retryMessageAttempt(message: ChatMessageEntity): MessageStatus {
        val contact = database.contactDao().getContactById(message.recipientId)
        val status = if (contact == null ||
            (!RouteResolver.isRoutableIp(contact.ipAddress) && contact.meshEndpointId == null)
        ) {
            // We do not even have a usable address for this peer, so the send
            // cannot be attempted. Treat it as unreachable rather than failed:
            // the address may still become routable when they are back.
            MessageStatus.QUEUED
        } else {
            val reachable = runCatching {
                NetworkUtils.testTcpPort(contact.ipAddress, contact.tcpPort)
            }.getOrDefault(false)

            val result: Result<ChatMessageEntity> = when {
                message.isVoice && message.filePath != null ->
                    sendVoiceMessage(
                        recipientIp = contact.ipAddress, recipientPort = contact.tcpPort,
                        recipientId = contact.deviceId, voiceFile = File(message.filePath),
                        durationSeconds = message.audioDurationSeconds,
                    existingMessageId = message.id,
                )

            message.isPhoto && message.filePath != null ->
                sendPhotoMessage(
                    recipientIp = contact.ipAddress, recipientPort = contact.tcpPort,
                    recipientId = contact.deviceId, localPhotoPath = message.filePath,
                    caption = message.text, existingMessageId = message.id,
                )

            message.isVideo && message.filePath != null ->
                sendVideoMessage(
                    recipientIp = contact.ipAddress, recipientPort = contact.tcpPort,
                    recipientId = contact.deviceId, localFilePath = message.filePath,
                    fileName = message.fileName ?: "video.mp4", fileSize = message.fileSize,
                    mimeType = message.mimeType ?: "video/mp4", caption = message.text,
                    existingMessageId = message.id,
                )

            message.isFile && message.filePath != null ->
                sendDocFileMessage(
                    recipientIp = contact.ipAddress, recipientPort = contact.tcpPort,
                    recipientId = contact.deviceId, localFilePath = message.filePath,
                    fileName = message.fileName ?: "file", fileSize = message.fileSize,
                    mimeType = message.mimeType ?: "application/octet-stream",
                    caption = message.text, existingMessageId = message.id,
                )

            else ->
                sendTextMessage(
                    recipientIp = contact.ipAddress, recipientPort = contact.tcpPort,
                    recipientId = contact.deviceId, text = message.text,
                    existingMessageId = message.id,
                )
        }

            if (result.isSuccess) {
                MessageStatus.forSendOutcome(success = true, reachable = true, attempts = 1)
            } else {
                // `reachable` was measured before the attempt, so a failure against
                // a live peer is a real failure, while a failure against a peer
                // that never answered only means "not now".
                MessageStatus.forSendOutcome(success = false, reachable = reachable, attempts = 1)
            }
        }

        database.chatMessageDao().updateMessageStatus(message.id, status)
        return status
    }

    suspend fun sendTextMessage(
        recipientIp: String, recipientPort: Int, recipientId: String, text: String,
        existingMessageId: String? = null
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = existingMessageId
            ?: "msg_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val entity = ChatMessageEntity(
            id = messageId, conversationId = recipientId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId, text = text,
            isPhoto = false, timestamp = System.currentTimeMillis(), isFromMe = true,
            status = MessageStatus.SENDING, isSenderDeveloper = userPreferences.isDeveloper
        )
        // On a retry the row already exists; inserting again would reset its
        // timestamp and lose the original ordering.
        if (existingMessageId == null) database.chatMessageDao().insertMessage(entity)

        // Phase 1.3: sign the full field set, not just the text.
        val signed = signForRecipient(
            recipientId = recipientId,
            messageId = messageId,
            timestamp = entity.timestamp,
            content = text.toByteArray(Charsets.UTF_8),
        )
        if (signed == null) {
            // Fail closed, as 1.2 established: a message that cannot be protected
            // for its recipient is not sent, and the row says why.
            database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.FAILED)
            return@withContext Result.failure(
                java.io.IOException("Cannot sign message for $recipientId: no pairwise session")
            )
        }

        val packet = TextMessagePacket(
            messageId = messageId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId,
            text = text, isDeveloper = userPreferences.isDeveloper, timestamp = entity.timestamp,
            signatureBase64 = signed.second, counter = signed.first
        )
        val transport = sendPacketDirectWithTransport(recipientIp, recipientPort, packet)
        val viaMesh = transport == SendTransport.MESH
        database.chatMessageDao().updateMessageTransport(messageId, viaMesh, if (viaMesh) 1 else 0)
        if (transport != SendTransport.FAILED) {
            database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.SENT)
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity.copy(status = MessageStatus.SENT, isMeshRelayed = viaMesh, meshHops = if (viaMesh) 1 else 0))
        } else {
            Result.failure(java.io.IOException("تعذّر الإرسال — الشبكة المحلية و MESH الاتنين فشلوا"))
        }
    }

    /**
     * بعت لكل الرسائل/الصور/الملفات/الصوت في الجروب لازم fans-out، والنتيجة
     * الوحيدة الصادقة هي: هل وصل لحد واحد على الأقل؟ بنستخدم async مش launch
     * عشان نقدر نـawait ونعرف النجاح، وبـtimeout عشان الـ peer الميت ما يعلّقش
     * الإرسال كله.
     */
    private suspend fun fanOutToGroup(
        contacts: List<ContactEntity>,
        deliver: suspend (ContactEntity) -> Boolean
    ): Int = coroutineScope {
        val online = contacts.filter { it.isOnline }
        if (online.isEmpty()) return@coroutineScope 0
        online
            .map { peer -> async { withTimeoutOrNull(GROUP_FANOUT_TIMEOUT_MS) { deliver(peer) } ?: false } }
            .count { runCatching { it.await() }.getOrDefault(false) }
    }

    private suspend fun groupFanoutFailure(messageId: String, entity: ChatMessageEntity): Result<ChatMessageEntity> {
        // مفيش enum لـ FAILED، فبنرجّعها SENDING عشان تعرض كأنها مستنية إعادة
        // إرسال والـ resend sweep يلتقطها بدل ما نكسر الـ UI بقيمة مش معروفة.
        database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.SENDING)
        return Result.failure(java.io.IOException("تعذّر الإرسال — مفيش عضو في الجروب اتوصل"))
    }

    // ---------- group senders ----------

    suspend fun sendGroupTextMessage(
        groupId: String, groupName: String, text: String, contacts: List<ContactEntity>
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = "grp_msg_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val entity = ChatMessageEntity(
            id = messageId, conversationId = groupId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = groupId, text = text,
            isPhoto = false, timestamp = System.currentTimeMillis(), isFromMe = true,
            status = MessageStatus.SENT, isGroup = true, groupName = groupName,
            isSenderDeveloper = userPreferences.isDeveloper
        )
        database.chatMessageDao().insertMessage(entity)

        // Phase 1.3: the signed field set contains the recipient and the counter,
        // so a group send cannot reuse one packet across peers. Each leg is signed
        // separately for the peer it is going to, with its own counter.
        val delivered = fanOutToGroup(contacts) { peer ->
            val signed = signForRecipient(
                recipientId = peer.deviceId,
                messageId = messageId,
                timestamp = entity.timestamp,
                content = text.toByteArray(Charsets.UTF_8),
            ) ?: return@fanOutToGroup false
            sendPacketDirect(
                peer.ipAddress, peer.tcpPort,
                TextMessagePacket(
                    messageId = messageId, senderId = userPreferences.deviceId,
                    senderName = userPreferences.displayName, recipientId = groupId, text = text,
                    isGroup = true, groupId = groupId, groupName = groupName,
                    isDeveloper = userPreferences.isDeveloper, timestamp = entity.timestamp,
                    signatureBase64 = signed.second, counter = signed.first
                ),
                peer.deviceId,
            )
        }
        if (delivered > 0) {
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity)
        } else {
            groupFanoutFailure(messageId, entity)
        }
    }

    suspend fun sendPhotoMessage(
        recipientIp: String, recipientPort: Int, recipientId: String,
        localPhotoPath: String, caption: String = "", existingMessageId: String? = null
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = existingMessageId ?: "photo_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val file = File(localPhotoPath)
        val actualSize = if (file.exists()) file.length() else 0L

        val entity = ChatMessageEntity(
            id = messageId, conversationId = recipientId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId, text = caption,
            photoPath = localPhotoPath, isPhoto = true, timestamp = System.currentTimeMillis(),
            isFromMe = true, status = MessageStatus.SENDING, isSenderDeveloper = userPreferences.isDeveloper
        )
        if (existingMessageId == null) database.chatMessageDao().insertMessage(entity)

        val success = if (file.exists()) {
            streamFileDirect(
                targetIp = recipientIp, targetPort = recipientPort, transferId = "xfer_$messageId",
                messageId = messageId, senderId = userPreferences.deviceId,
                senderName = userPreferences.displayName, recipientId = recipientId,
                fileName = file.name, fileSize = actualSize, mimeType = "image/jpeg",
                caption = caption, isVideo = false, isPhoto = true,
                isDeveloper = userPreferences.isDeveloper, file = file
            )
        } else false

        if (success) {
            database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.SENT)
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(java.io.IOException("تعذّر الإرسال — الشبكة المحلية و MESH الاتنين فشلوا"))
        }
    }

    suspend fun sendGroupPhotoMessage(
        groupId: String, groupName: String, localPhotoPath: String,
        caption: String = "", contacts: List<ContactEntity>
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = "grp_photo_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val file = File(localPhotoPath)
        val actualSize = if (file.exists()) file.length() else 0L

        val entity = ChatMessageEntity(
            id = messageId, conversationId = groupId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = groupId, text = caption,
            photoPath = localPhotoPath, isPhoto = true, timestamp = System.currentTimeMillis(),
            isFromMe = true, status = MessageStatus.SENT, isGroup = true, groupName = groupName,
            isSenderDeveloper = userPreferences.isDeveloper
        )
        database.chatMessageDao().insertMessage(entity)

        val delivered = if (file.exists()) {
            fanOutToGroup(contacts) { peer ->
                streamFileDirect(
                    targetIp = peer.ipAddress, targetPort = peer.tcpPort,
                    transferId = "xfer_${messageId}_${peer.deviceId.take(4)}",
                    messageId = messageId, senderId = userPreferences.deviceId,
                    senderName = userPreferences.displayName, recipientId = groupId,
                    fileName = file.name, fileSize = actualSize, mimeType = "image/jpeg",
                    caption = caption, isVideo = false, isPhoto = true,
                    isGroup = true, groupId = groupId, groupName = groupName,
                    isDeveloper = userPreferences.isDeveloper, file = file,
                    targetPeerId = peer.deviceId
                )
            }
        } else 0
        if (delivered > 0) {
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity)
        } else {
            groupFanoutFailure(messageId, entity)
        }
    }

    suspend fun streamFileDirect(
        targetIp: String, targetPort: Int, transferId: String, messageId: String,
        senderId: String, senderName: String, recipientId: String, fileName: String,
        fileSize: Long, mimeType: String, caption: String, isVideo: Boolean,
        isPhoto: Boolean = false, isVoice: Boolean = false, audioDurationSeconds: Int = 0,
        isGroup: Boolean = false, groupId: String? = null, groupName: String? = null,
        isDeveloper: Boolean = false, file: File, targetPeerId: String? = null,
        meshEndpointId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (cancelledTransferIds.contains(transferId)) return@withContext false
        if (!file.exists() || !file.canRead()) return@withContext false
        if (file.length() > MAX_FILE_TRANSFER_SIZE) return@withContext false

        val actualTargetDevice = targetPeerId ?: recipientId

        val metaPacket: NetworkPacket = when {
                isPhoto -> PhotoMessagePacket(
                    messageId = messageId, senderId = senderId, senderName = senderName,
                    recipientId = recipientId, photoBase64 = "", caption = caption,
                    fileName = fileName, fileSize = fileSize,
                    isGroup = isGroup, groupId = groupId, groupName = groupName,
                    isDeveloper = isDeveloper, timestamp = System.currentTimeMillis()
                )
                isVideo -> VideoMessagePacket(
                    messageId = messageId, senderId = senderId, senderName = senderName,
                    recipientId = recipientId, fileName = fileName, fileSize = fileSize,
                    mimeType = mimeType, fileBase64 = "", caption = caption,
                    isGroup = isGroup, groupId = groupId, groupName = groupName,
                    isDeveloper = isDeveloper, timestamp = System.currentTimeMillis()
                )
                else -> FileMessagePacket(
                    messageId = messageId, senderId = senderId, senderName = senderName,
                    recipientId = recipientId, fileName = fileName, fileSize = fileSize,
                    mimeType = mimeType, fileBase64 = "", caption = caption,
                    isGroup = isGroup, groupId = groupId, groupName = groupName,
                    isDeveloper = isDeveloper, timestamp = System.currentTimeMillis()
                )
            }

        // MESH is the primary route only when we have no routable LAN address for
        // this peer. Otherwise LAN goes first and MESH gets its turn if TCP fails.
        val primaryRoute = RouteResolver.candidates(targetIp, targetPort, meshEndpointId)
            .firstOrNull()?.transport
        var meshAlreadyTried = false
        if (primaryRoute == SendTransport.MESH) {
            meshAlreadyTried = true
            val meshSent = nearbyFileFallbackSender?.invoke(actualTargetDevice, file, metaPacket) ?: false
            if (meshSent) return@withContext true
        }

        var socket: Socket? = null
        var fileIn: FileInputStream? = null
        val lanDelivered = try {
            socket = Socket()
            socket.setSoLinger(true, 5)
            socket.connect(InetSocketAddress(targetIp, targetPort), SOCKET_TIMEOUT)
            socket.soTimeout = CONNECTION_TIMEOUT_MS

            val baseIv = EncryptionManager.generateStreamIv()
            val ivHex = baseIv.joinToString("") { "%02x".format(it) }

            val pairwise = EncryptionManager.getPairwiseManager()
            if (pairwise != null && !pairwise.hasSession(actualTargetDevice)) {
                val contact = database.contactDao().getContactById(actualTargetDevice)
                if (!contact?.publicKeyBase64.isNullOrBlank()) {
                    pairwise.establishSession(actualTargetDevice, contact.publicKeyBase64!!)
                }
            }
            // Phase 1.2: fail closed, as on the inbound path.
            val streamKey = pairwise?.getStreamKeyForPeer(actualTargetDevice)
            if (streamKey == null) {
                Log.e(TAG, "Refusing outbound transfer to $actualTargetDevice: no " +
                    "pairwise stream key, so the file cannot be protected for it")
                return@withContext false
            }

            val metaObj = JSONObject().apply {
                put("transferId", transferId)
                put("messageId", messageId)
                put("senderId", senderId)
                put("senderName", senderName)
                put("recipientId", recipientId)
                put("fileName", fileName)
                put("fileSize", fileSize)
                put("mimeType", mimeType)
                put("caption", caption)
                put("isVideo", isVideo)
                put("isPhoto", isPhoto)
                put("isVoice", isVoice)
                put("audioDurationSeconds", audioDurationSeconds)
                put("isGroup", isGroup)
                if (groupId != null) put("groupId", groupId)
                if (groupName != null) put("groupName", groupName)
                put("isDeveloper", isDeveloper)
                put("timestamp", System.currentTimeMillis())
                put("ivHex", ivHex)
            }

            val plainMeta = metaObj.toString()
            // Phase 1.2: fail closed.
            val encryptedMeta = pairwise?.encryptForPeer(actualTargetDevice, plainMeta)
            if (encryptedMeta == null) {
                Log.e(TAG, "Refusing outbound transfer to $actualTargetDevice: " +
                    "transfer metadata could not be protected for it")
                return@withContext false
            }
            val metaBytes = encryptedMeta.toByteArray(Charsets.UTF_8)

            val rawOut = socket.getOutputStream()
            val dataOut = DataOutputStream(rawOut)
            dataOut.writeInt(MAGIC_STREAM_HEADER)
            dataOut.writeInt(metaBytes.size)
            dataOut.write(metaBytes)
            dataOut.flush()

            val dataIn = DataInputStream(socket.getInputStream())
            val existingRemoteBytes = dataIn.readLong()
            val validResumeOffset = if (existingRemoteBytes in 0 until fileSize && existingRemoteBytes % 16L == 0L) {
                existingRemoteBytes
            } else {
                0L
            }

            fileIn = FileInputStream(file)
            if (validResumeOffset > 0L) {
                fileIn.skip(validResumeOffset)
                Log.i(TAG, "Resuming transfer $transferId from offset: $validResumeOffset bytes")
            }

            val blockOffset = validResumeOffset / 16L
            val cipher = EncryptionManager.createStreamEncryptCipher(streamKey, baseIv, blockOffset)
            val cipherOut = CipherOutputStream(rawOut, cipher)

            _activeTransfers.value = _activeTransfers.value + (transferId to TransferProgress(
                transferId = transferId, fileName = fileName, bytesTransferred = validResumeOffset,
                totalBytes = fileSize, isUpload = true, isVideo = isVideo
            ))

            val buffer = ByteArray(CHUNK_BUFFER_SIZE)
            var bytesSent = validResumeOffset
            val startTime = System.currentTimeMillis()
            var lastProgressTime = startTime

            while (bytesSent < fileSize) {
                if (cancelledTransferIds.contains(transferId)) break
                val toRead = minOf(buffer.size.toLong(), fileSize - bytesSent).toInt()
                val read = fileIn.read(buffer, 0, toRead)
                if (read == -1) break
                cipherOut.write(buffer, 0, read)
                bytesSent += read

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 250 || bytesSent == fileSize) {
                    val elapsedSec = maxOf(0.001, (now - startTime) / 1000.0)
                    val speed = ((bytesSent - validResumeOffset) / elapsedSec).toLong().coerceAtLeast(0L)
                    val remaining = maxOf(0L, fileSize - bytesSent)
                    val eta = if (speed > 0) remaining / speed else 0L
                    _activeTransfers.value = _activeTransfers.value + (transferId to TransferProgress(
                        transferId = transferId, fileName = fileName, bytesTransferred = bytesSent,
                        totalBytes = fileSize, speedBytesPerSec = speed, etaSeconds = eta,
                        isUpload = true, isVideo = isVideo, isCompleted = bytesSent == fileSize
                    ))
                    lastProgressTime = now
                }
            }
            cipherOut.flush()
            rawOut.flush()

            var completed = bytesSent == fileSize
            if (completed) {
                try {
                    val ackIn = DataInputStream(socket.getInputStream())
                    val ack = ackIn.readInt()
                    completed = (ack == MAGIC_STREAM_ACK)
                } catch (e: Exception) {
                    Log.w(TAG, "Notice waiting for stream ACK: ${e.message}")
                }
                scope.launch {
                    delay(2000)
                    _activeTransfers.value = _activeTransfers.value - transferId
                }
            }
            completed
        } catch (e: Exception) {
            Log.e(TAG, "Stream transfer error: ${e.message}")
            _activeTransfers.value = _activeTransfers.value + (transferId to TransferProgress(
                transferId = transferId, fileName = fileName, bytesTransferred = 0L,
                totalBytes = fileSize, isUpload = true, isVideo = isVideo,
                error = e.message ?: "فشل الإرسال"
            ))
            false
        } finally {
            try { fileIn?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }

        // LAN was tried and did not deliver, and MESH has not had its turn yet.
        if (!meshAlreadyTried) {
            val meshSent = nearbyFileFallbackSender?.invoke(actualTargetDevice, file, metaPacket) ?: false
            if (meshSent) return@withContext true
        }
        lanDelivered
    }

    suspend fun sendVideoMessage(
        recipientIp: String, recipientPort: Int, recipientId: String,
        localFilePath: String, fileName: String, fileSize: Long,
        mimeType: String, caption: String = "", existingMessageId: String? = null
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = existingMessageId ?: "vid_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val file = File(localFilePath)
        val actualSize = if (file.exists()) file.length() else fileSize
        val entity = ChatMessageEntity(
            id = messageId, conversationId = recipientId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId, text = caption,
            filePath = localFilePath, fileName = fileName, fileSize = actualSize,
            mimeType = mimeType, isVideo = true, isFile = false, isPhoto = false,
            timestamp = System.currentTimeMillis(), isFromMe = true, status = MessageStatus.SENDING,
            isSenderDeveloper = userPreferences.isDeveloper
        )
        if (existingMessageId == null) database.chatMessageDao().insertMessage(entity)
        val success = if (file.exists()) {
            streamFileDirect(
                targetIp = recipientIp, targetPort = recipientPort, transferId = "xfer_$messageId",
                messageId = messageId, senderId = userPreferences.deviceId,
                senderName = userPreferences.displayName, recipientId = recipientId,
                fileName = fileName, fileSize = actualSize, mimeType = mimeType,
                caption = caption, isVideo = true, isPhoto = false, isDeveloper = userPreferences.isDeveloper, file = file
            )
        } else false
        if (success) {
            database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.SENT)
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(java.io.IOException("تعذّر الإرسال — الشبكة المحلية و MESH الاتنين فشلوا"))
        }
    }

    suspend fun sendGroupVideoMessage(
        groupId: String, groupName: String, localFilePath: String, fileName: String,
        fileSize: Long, mimeType: String, caption: String = "", contacts: List<ContactEntity>
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = "grp_vid_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val file = File(localFilePath)
        val actualSize = if (file.exists()) file.length() else fileSize
        val entity = ChatMessageEntity(
            id = messageId, conversationId = groupId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = groupId, text = caption,
            filePath = localFilePath, fileName = fileName, fileSize = actualSize,
            mimeType = mimeType, isVideo = true, isFile = false, isPhoto = false,
            timestamp = System.currentTimeMillis(), isFromMe = true, status = MessageStatus.SENT,
            isGroup = true, groupName = groupName, isSenderDeveloper = userPreferences.isDeveloper
        )
        database.chatMessageDao().insertMessage(entity)
        val delivered = fanOutToGroup(contacts) { peer ->
            streamFileDirect(
                targetIp = peer.ipAddress, targetPort = peer.tcpPort,
                transferId = "xfer_${messageId}_${peer.deviceId.take(4)}",
                messageId = messageId, senderId = userPreferences.deviceId,
                senderName = userPreferences.displayName, recipientId = groupId,
                fileName = fileName, fileSize = actualSize, mimeType = mimeType,
                caption = caption, isVideo = true, isPhoto = false, isGroup = true,
                groupId = groupId, groupName = groupName,
                isDeveloper = userPreferences.isDeveloper, file = file,
                targetPeerId = peer.deviceId
            )
        }
        if (delivered > 0) {
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity)
        } else {
            groupFanoutFailure(messageId, entity)
        }
    }

    suspend fun sendDocFileMessage(
        recipientIp: String, recipientPort: Int, recipientId: String,
        localFilePath: String, fileName: String, fileSize: Long,
        mimeType: String, caption: String = "", existingMessageId: String? = null
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = existingMessageId ?: "file_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val file = File(localFilePath)
        val actualSize = if (file.exists()) file.length() else fileSize
        val isVideo = FileUtils.isVideoMime(mimeType, fileName)
        val entity = ChatMessageEntity(
            id = messageId, conversationId = recipientId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId, text = caption,
            filePath = localFilePath, fileName = fileName, fileSize = actualSize,
            mimeType = mimeType, isVideo = isVideo, isFile = !isVideo, isPhoto = false,
            timestamp = System.currentTimeMillis(), isFromMe = true, status = MessageStatus.SENDING,
            isSenderDeveloper = userPreferences.isDeveloper
        )
        if (existingMessageId == null) database.chatMessageDao().insertMessage(entity)
        val success = if (file.exists()) {
            streamFileDirect(
                targetIp = recipientIp, targetPort = recipientPort, transferId = "xfer_$messageId",
                messageId = messageId, senderId = userPreferences.deviceId,
                senderName = userPreferences.displayName, recipientId = recipientId,
                fileName = fileName, fileSize = actualSize, mimeType = mimeType,
                caption = caption, isVideo = isVideo, isPhoto = false, isDeveloper = userPreferences.isDeveloper, file = file
            )
        } else false
        if (success) {
            database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.SENT)
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(java.io.IOException("تعذّر الإرسال — الشبكة المحلية و MESH الاتنين فشلوا"))
        }
    }

    suspend fun sendGroupDocFileMessage(
        groupId: String, groupName: String, localFilePath: String, fileName: String,
        fileSize: Long, mimeType: String, caption: String = "", contacts: List<ContactEntity>
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = "grp_file_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val file = File(localFilePath)
        val actualSize = if (file.exists()) file.length() else fileSize
        val isVideo = FileUtils.isVideoMime(mimeType, fileName)
        val entity = ChatMessageEntity(
            id = messageId, conversationId = groupId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = groupId, text = caption,
            filePath = localFilePath, fileName = fileName, fileSize = actualSize,
            mimeType = mimeType, isVideo = isVideo, isFile = !isVideo, isPhoto = false,
            timestamp = System.currentTimeMillis(), isFromMe = true, status = MessageStatus.SENT,
            isGroup = true, groupName = groupName, isSenderDeveloper = userPreferences.isDeveloper
        )
        database.chatMessageDao().insertMessage(entity)
        val delivered = fanOutToGroup(contacts) { peer ->
            streamFileDirect(
                targetIp = peer.ipAddress, targetPort = peer.tcpPort,
                transferId = "xfer_${messageId}_${peer.deviceId.take(4)}",
                messageId = messageId, senderId = userPreferences.deviceId,
                senderName = userPreferences.displayName, recipientId = groupId,
                fileName = fileName, fileSize = actualSize, mimeType = mimeType,
                caption = caption, isVideo = isVideo, isPhoto = false, isGroup = true,
                groupId = groupId, groupName = groupName,
                isDeveloper = userPreferences.isDeveloper, file = file,
                targetPeerId = peer.deviceId
            )
        }
        if (delivered > 0) {
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity)
        } else {
            groupFanoutFailure(messageId, entity)
        }
    }

    private fun sendDeliveredReceipt(targetIp: String, targetPort: Int, messageId: String, senderId: String) {
        scope.launch {
            val ack = AckDeliveredPacket(messageId = messageId, senderId = userPreferences.deviceId, recipientId = senderId)
            sendPacketDirect(targetIp, targetPort, ack)
        }
    }

    private fun sendSingleReadReceipt(targetIp: String, targetPort: Int, messageId: String, senderId: String) {
        scope.launch {
            val ack = AckReadPacket(messageId = messageId, senderId = userPreferences.deviceId, recipientId = senderId)
            sendPacketDirect(targetIp, targetPort, ack)
        }
    }

    private fun sendReadReceipt(targetIp: String, targetPort: Int, recipientId: String) {
        scope.launch {
            val ack = AckReadPacket(messageId = "ALL", senderId = userPreferences.deviceId, recipientId = recipientId)
            sendPacketDirect(targetIp, targetPort, ack)
        }
    }

    fun cancelTransfer(transferId: String, recipientIp: String? = null, recipientPort: Int = TCP_PORT) {
        cancelledTransferIds.add(transferId)
        _activeTransfers.value = _activeTransfers.value - transferId
        if (recipientIp != null) {
            scope.launch {
                val cancelPacket = TransferCancelPacket(transferId = transferId, senderId = userPreferences.deviceId)
                sendPacketDirect(recipientIp, recipientPort, cancelPacket)
            }
        }
    }

    /**
     * إرسال الرسائل الصوتية: إذا زاد حجم التسجيل عن 300KB يُبث كملف متدفق لحماية الذاكرة والـ JSON
     */
    suspend fun sendVoiceMessage(
        recipientIp: String, recipientPort: Int, recipientId: String,
        voiceFile: File, durationSeconds: Int, existingMessageId: String? = null
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = existingMessageId
            ?: "voice_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val entity = ChatMessageEntity(
            id = messageId, conversationId = recipientId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = recipientId,
            text = "🎤 رسالة صوتية ($durationSeconds ث)", filePath = voiceFile.absolutePath,
            isVoice = true, audioDurationSeconds = durationSeconds,
            timestamp = System.currentTimeMillis(), isFromMe = true, status = MessageStatus.SENDING,
            isSenderDeveloper = userPreferences.isDeveloper
        )
        if (existingMessageId == null) database.chatMessageDao().insertMessage(entity)

        // Nearby بحد أقصى ~32KB للـ BYTES payload، فأي تسجيل بيتسلّم عبر MESH
        // لازم يروح stream مش base64 JSON، وإلا Payload.fromBytes بيرمي والرسالة بتضيع.
        val mustStream = voiceFile.length() > MAX_VOICE_BASE64_BYTES ||
            RouteResolver.hasNoLanRoute(recipientIp)

        val success = if (mustStream) {
            // للتسجيلات الصوتية الطويلة: استخدام تدفق الملفات المجزأ
            streamFileDirect(
                targetIp = recipientIp, targetPort = recipientPort, transferId = "xfer_$messageId",
                messageId = messageId, senderId = userPreferences.deviceId,
                senderName = userPreferences.displayName, recipientId = recipientId,
                fileName = "voice_$messageId.m4a", fileSize = voiceFile.length(),
                mimeType = "audio/mp4", caption = "🎤 رسالة صوتية ($durationSeconds ث)",
                isVideo = false, isPhoto = false, isVoice = true, audioDurationSeconds = durationSeconds,
                isDeveloper = userPreferences.isDeveloper, file = voiceFile
            )
        } else {
            // للتسجيلات القصيرة: الإرسال المباشر السريع بحزمة واحدة
            val bytes = voiceFile.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val signature = EncryptionManager.getPairwiseManager()?.signData(bytes)
            val packet = VoiceMessagePacket(
                messageId = messageId, senderId = userPreferences.deviceId,
                senderName = userPreferences.displayName, recipientId = recipientId,
                audioBase64 = base64, durationSeconds = durationSeconds,
                isDeveloper = userPreferences.isDeveloper, timestamp = entity.timestamp,
                signatureBase64 = signature
            )
            sendPacketDirect(recipientIp, recipientPort, packet)
        }

        if (success) {
            database.chatMessageDao().updateMessageStatus(messageId, MessageStatus.SENT)
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity.copy(status = MessageStatus.SENT))
        } else {
            Result.failure(java.io.IOException("تعذّر الإرسال — الشبكة المحلية و MESH الاتنين فشلوا"))
        }
    }

    suspend fun sendGroupVoiceMessage(
        groupId: String, groupName: String, voiceFile: File, durationSeconds: Int,
        contacts: List<ContactEntity>
    ): Result<ChatMessageEntity> = withContext(Dispatchers.IO) {
        val messageId = "grp_voice_${System.currentTimeMillis()}_${userPreferences.deviceId.take(4)}"
        val entity = ChatMessageEntity(
            id = messageId, conversationId = groupId, senderId = userPreferences.deviceId,
            senderName = userPreferences.displayName, recipientId = groupId,
            text = "🎤 رسالة صوتية ($durationSeconds ث)", filePath = voiceFile.absolutePath,
            isVoice = true, audioDurationSeconds = durationSeconds,
            timestamp = System.currentTimeMillis(), isFromMe = true, status = MessageStatus.SENT,
            isGroup = true, groupName = groupName, isSenderDeveloper = userPreferences.isDeveloper
        )
        database.chatMessageDao().insertMessage(entity)

        val smallEnoughForTcp = voiceFile.length() <= MAX_VOICE_BASE64_BYTES
        val bytes = if (smallEnoughForTcp) voiceFile.readBytes() else ByteArray(0)
        val audioBase64 = if (smallEnoughForTcp) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } else ""

        val delivered = fanOutToGroup(contacts) { peer ->
            // Nearby بحدّ الـ BYTES payload بحوالي 32KB، فأي تسجيل بيتسلّم عبر
            // MESH لازم ينزل stream. base64 جوّه JSON كان بيرمي في Payload.fromBytes
            // والرسالة كانت بتضيع بصمت.
            val mustStream = !smallEnoughForTcp ||
                RouteResolver.hasNoLanRoute(peer.ipAddress, peer.meshEndpointId)
            if (mustStream) {
                streamFileDirect(
                    targetIp = peer.ipAddress, targetPort = peer.tcpPort,
                    transferId = "xfer_${messageId}_${peer.deviceId.take(4)}",
                    messageId = messageId, senderId = userPreferences.deviceId,
                    senderName = userPreferences.displayName, recipientId = groupId,
                    fileName = "voice_$messageId.m4a", fileSize = voiceFile.length(),
                    mimeType = "audio/mp4", caption = "رسالة صوتية ($durationSeconds ث)",
                    isVideo = false, isPhoto = false, isVoice = true, audioDurationSeconds = durationSeconds,
                    isGroup = true, groupId = groupId, groupName = groupName,
                    isDeveloper = userPreferences.isDeveloper, file = voiceFile,
                    targetPeerId = peer.deviceId
                )
            } else {
                // Phase 1.3: the signed field set holds the recipient and the
                // counter, so each leg of a group send is signed separately.
                val signed = signForRecipient(
                    recipientId = peer.deviceId,
                    messageId = messageId,
                    timestamp = entity.timestamp,
                    content = audioBase64.toByteArray(Charsets.UTF_8),
                ) ?: return@fanOutToGroup false
                sendPacketDirect(
                    peer.ipAddress, peer.tcpPort,
                    VoiceMessagePacket(
                        messageId = messageId, senderId = userPreferences.deviceId,
                        senderName = userPreferences.displayName, recipientId = groupId,
                        audioBase64 = audioBase64,
                        durationSeconds = durationSeconds, isGroup = true,
                        groupId = groupId, groupName = groupName,
                        isDeveloper = userPreferences.isDeveloper, timestamp = entity.timestamp,
                        signatureBase64 = signed.second, counter = signed.first
                    ),
                    peer.deviceId,
                )
            }
        }

        if (delivered > 0) {
            if (userPreferences.isHapticEnabled) FeedbackUtils.vibrateMessageSent(context)
            Result.success(entity)
        } else {
            groupFanoutFailure(messageId, entity)
        }
    }

    suspend fun sendPacketDirect(
        targetIp: String,
        targetPort: Int,
        packet: NetworkPacket,
        encryptForPeerId: String? = null
    ): Boolean = sendPacketDirectWithTransport(
        targetIp, targetPort, packet, null, encryptForPeerId
    ) != SendTransport.FAILED

    suspend fun sendPacketDirectWithTransport(
        targetIp: String, targetPort: Int, packet: NetworkPacket,
        meshEndpointId: String? = null,
        // Phase 1.2: the identity the payload is encrypted for. This is not always
        // the packet's own recipientId: a group packet carries the group id, so
        // each fan-out leg needs the individual peer's device id instead.
        encryptForPeerId: String? = null
    ): SendTransport = withContext(Dispatchers.IO) {
        val recipientId = extractRecipientId(packet)
        val encryptionPeerId = encryptForPeerId ?: recipientId

        // The endpoint id lives on the contact now, so MESH stays available as a
        // fallback even when the peer has a perfectly good LAN address. Resolving
        // it here means every sender (direct, group, and the call signals) gets
        // both routes without threading a new parameter through each one.
        val resolvedEndpoint = meshEndpointId ?: recipientId?.let { rid ->
            runCatching { database.contactDao().getContactById(rid)?.meshEndpointId }.getOrNull()
        }

        val candidates = RouteResolver.candidates(targetIp, targetPort, resolvedEndpoint).toMutableList()

        // MESH stays reachable even when we have never cached this peer's
        // endpoint id: sendPacketToPeer resolves it from the live connection map
        // at send time. Gating MESH on a cached id is what made a peer
        // unreachable until it had been seen before.
        if (candidates.none { it.transport == SendTransport.MESH } &&
            recipientId != null && nearbyFallbackSender != null
        ) {
            candidates.add(TransportCandidate(SendTransport.MESH, "p2p-$recipientId", 0, resolvedEndpoint))
        }
        val transport = sendViaCandidates(candidates) { candidate ->
            when (candidate.transport) {
                SendTransport.LAN ->
                    sendOverTcp(
                    candidate.address, candidate.port, packet, recipientId, encryptionPeerId
                )
                SendTransport.MESH ->
                    if (recipientId != null) {
                        nearbyFallbackSender?.invoke(recipientId, packet) ?: false
                    } else {
                        false
                    }
                SendTransport.FAILED -> false
            }
        }
        if (transport == SendTransport.FAILED) {
            Log.w(
                TAG,
                "send to $targetIp failed after ${candidates.size} candidate(s): " +
                    candidates.joinToString { it.transport.name }
            )
        }
        transport
    }

    /**
     * Phase 1.3: says in the conversation that a peer is too old to talk to.
     *
     * The design is explicit that an old client is never dropped silently, so the
     * refusal has to leave a trace the user can act on. The row id is stable and
     * the insert replaces, so repeated attempts from the same peer refresh one
     * notice instead of filling the conversation with them.
     */
    private suspend fun notePeerNeedsUpdate(
        senderId: String,
        claimedRecipientId: String,
        displayName: String?,
    ) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: senderId
        val conversationId = if (claimedRecipientId == userPreferences.deviceId) {
            senderId
        } else {
            senderId
        }
        database.chatMessageDao().insertMessage(
            ChatMessageEntity(
                id = "sys_update_${senderId.take(48)}",
                conversationId = conversationId,
                senderId = senderId,
                senderName = name,
                recipientId = userPreferences.deviceId,
                text = context.getString(
                    R.string.system_peer_needs_update, name
                ),
                isPhoto = false,
                timestamp = System.currentTimeMillis(),
                receivedAt = System.currentTimeMillis(),
                isFromMe = false,
                status = MessageStatus.DELIVERED,
            )
        )
    }

    /**
     * Phase 1.3: claims the next counter for [recipientId] and signs the message
     * with it.
     *
     * The signature has to cover the counter, or a counter can be stripped and
     * rewritten to look like a fresh message. Claiming and signing happen together
     * so a message can never go out with a counter nobody recorded.
     *
     * Returns null when no counter could be claimed or no signature produced, in
     * which case the caller must not send: 1.2 already established that a message
     * which cannot be protected is not sent.
     */
    private suspend fun signForRecipient(
        recipientId: String,
        messageId: String,
        timestamp: Long,
        content: ByteArray,
    ): Pair<Long, String?>? {
        val counter = messageCounters.claimOutgoing(recipientId) ?: return null
        val signature = MessageSigningPayload.sign(
            MessageSigningPayload.Fields(
                senderId = userPreferences.deviceId,
                recipientId = recipientId,
                messageId = messageId,
                timestamp = timestamp,
                counter = counter,
                contentDigestHex = MessageSigningPayload.digestHex(content),
            )
        ) ?: return null
        return counter to signature
    }

    /** Returns true when the packet reached the peer over direct LAN TCP. */
    private suspend fun sendOverTcp(
        targetIp: String,
        targetPort: Int,
        packet: NetworkPacket,
        recipientId: String?,
        // Phase 1.2: for a group fan-out leg this is the individual peer, while
        // recipientId is the group the packet nominally belongs to.
        encryptForPeerId: String? = null
    ): Boolean {
        val encryptionPeerId = encryptForPeerId ?: recipientId
        val isCallSignal = packet is CallOfferPacket || packet is CallAnswerPacket ||
                packet is CallRingingPacket || packet is CallEndPacket

        var socket: Socket? = null
        return try {
            socket = Socket()
            val timeout = if (isCallSignal) 1200 else SOCKET_TIMEOUT
            socket.connect(InetSocketAddress(targetIp, targetPort), timeout)

            val plainJson = packet.toJson()
            val pairwise = EncryptionManager.getPairwiseManager()

            if (recipientId != null && pairwise != null && !pairwise.hasSession(recipientId)) {
                val contact = database.contactDao().getContactById(recipientId)
                if (!contact?.publicKeyBase64.isNullOrBlank()) {
                    pairwise.establishSession(recipientId, contact.publicKeyBase64!!)
                }
            }

            val payloadToSend = if (packet is BeaconPacket || packet is BeaconAckPacket) {
                // Beacons stay readable so discovery keeps working.
                plainJson
            } else {
                // Phase 1.2: fail closed. A payload that cannot be protected for
                // its recipient is not sent at all.
                val sealed = encryptionPeerId?.let { pairwise?.encryptForPeer(it, plainJson) }
                if (sealed == null) {
                    Log.e(TAG, "Refusing mesh payload: no pairwise session for " +
                        "the recipient, so it cannot be protected")
                    return false
                }
                sealed
            }

            val jsonBytes = payloadToSend.toByteArray(Charsets.UTF_8)
            val dataOutputStream = DataOutputStream(socket.getOutputStream())
            dataOutputStream.writeInt(jsonBytes.size)
            dataOutputStream.write(jsonBytes)
            dataOutputStream.flush()
            true
        } catch (e: Exception) {
            Log.d(TAG, "TCP send direct to $targetIp:$targetPort failed: ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Records a peer heard on the direct LAN and reports whether it is one the
     * user already knows.
     *
     * A beacon is an advertisement, not an invitation. This path used to write a
     * contact row unconditionally, and because the beacon is answered with a TCP
     * ack that hits the same branch, every phone in range created a chat on both
     * sides with no user action. Unknown peers go to the transient
     * `discovered_peers` table instead and only become contacts when the user
     * picks them from the nearby sheet.
     */
    private suspend fun recordLanPeer(
        deviceId: String,
        displayName: String,
        senderIp: String,
        tcpPort: Int,
        avatarColorIndex: Int,
        isDeveloper: Boolean,
        versionCode: Int,
        versionName: String,
        publicKeyBase64: String?,
    ): Boolean {
        val existing = database.contactDao().getContactById(deviceId)
        if (existing == null) {
            runCatching {
                database.discoveredPeerDao().upsert(
                    com.example.data.local.DiscoveredPeerEntity(
                        deviceId = deviceId,
                        displayName = displayName,
                        endpointId = "",
                        ipAddress = senderIp,
                        publicKeyBase64 = publicKeyBase64,
                        avatarColorIndex = avatarColorIndex,
                        isDeveloper = isDeveloper,
                        appVersionCode = versionCode,
                        transportIsLan = true,
                    )
                )
            }.onFailure { Log.w(TAG, "Could not record discovered peer $deviceId: ${it.message}") }
            // No pairwise session for someone the user has not chosen: that would
            // let any nearby phone establish a session with us uninvited.
            return false
        }

        if (publicKeyBase64 != null && publicKeyBase64 != existing.publicKeyBase64) {
            Log.i(TAG, "Detected key change for $deviceId, renewing pairwise session.")
            EncryptionManager.getPairwiseManager()?.establishSession(deviceId, publicKeyBase64)
        }

        val contact = ContactEntity(
            deviceId = deviceId,
            displayName = if (!existing.customNickname.isNullOrBlank()) existing.customNickname else displayName,
            ipAddress = senderIp, tcpPort = tcpPort,
            avatarColorIndex = avatarColorIndex, avatarPath = existing.avatarPath,
            lastSeen = System.currentTimeMillis(), isOnline = true,
            customNickname = existing.customNickname, isDeveloper = isDeveloper,
            appVersionCode = versionCode, appVersionName = versionName,
            isMeshPeer = false,
            meshEndpointId = existing.meshEndpointId,
            publicKeyBase64 = publicKeyBase64 ?: existing.publicKeyBase64
        )
        database.contactDao().insertOrUpdateContact(contact)
        return true
    }

    private fun extractRecipientId(packet: NetworkPacket): String? {
        return when (packet) {
            is TextMessagePacket -> packet.recipientId
            is PhotoMessagePacket -> packet.recipientId
            is VoiceMessagePacket -> packet.recipientId
            is VideoMessagePacket -> packet.recipientId
            is FileMessagePacket -> packet.recipientId
            is CallOfferPacket -> packet.calleeId
            is CallAnswerPacket -> packet.callerId
            is CallRingingPacket -> packet.callerId
            is CallEndPacket -> packet.targetId
            is AckDeliveredPacket -> packet.recipientId
            is AckReadPacket -> packet.recipientId
            else -> null
        }
    }
}

