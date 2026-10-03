package com.example.data.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.local.UserPreferences
import com.example.data.network.CallAnswerPacket
import com.example.data.network.CallEndPacket
import com.example.data.network.CallOfferPacket
import com.example.data.network.CallRingingPacket
import com.example.data.network.NetworkPacket
import com.example.data.network.TcpMessagingManager
import com.example.data.security.EncryptionManager
import com.example.service.LanBackgroundService
import com.example.service.LanNotificationHelper
import com.example.ui.screens.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class CallStatus {
    IDLE,
    OUTGOING_CALLING,
    INCOMING_CALLING,
    CONNECTED,
    ENDED
}

data class ActiveCallInfo(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val peerIp: String,
    val peerTcpPort: Int = 9999,
    val peerAudioPort: Int = 10002,
    val isIncoming: Boolean,
    val status: CallStatus = CallStatus.IDLE,
    val durationSeconds: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true
) {
    val peerPort: Int get() = peerTcpPort
}

class LanAudioCallManager(
    private val context: Context,
    private val userPreferences: UserPreferences,
    private val tcpMessagingManager: TcpMessagingManager
) {

    companion object {
        private const val TAG = "LanAudioCallManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_BUFFER_SIZE = 640 // 20ms of PCM 16kHz
        private const val NEARBY_BATCH_SIZE = AUDIO_BUFFER_SIZE * 2
        private const val MAX_AUDIO_CHUNK_SIZE = AUDIO_BUFFER_SIZE * 4
        private const val CAPTURE_DIAG_INTERVAL_FRAMES = 100
        private const val CALL_TIMEOUT_MS = 45_000L
        private const val UDP_SOCKET_TIMEOUT_MS = 4000
        const val DEFAULT_AUDIO_PORT = 10002

        // بروتوكول حزم الصوت المشفرة والمحمية ضد التلاعب
        private const val VOIP_MAGIC = 0x564F4950 // 'VOIP'
        private const val HEADER_SIZE = 16 // 4 bytes Magic + 4 bytes Seq + 8 bytes Timestamp
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        private const val CIPHER_ALGO = "AES/GCM/NoPadding"
    }

    /**
     * مخزن توقيت وترتيب ذكي (Jitter Buffer)
     * يفرز الحزم بالترتيب الزمني الصحيح بعد فك تشفيرها والتحقق من سلامتها
     */
    private class AudioJitterBuffer(
        private val maxBufferedFrames: Int = 8,
        private val prebufferFrames: Int = 2
    ) {
        private val frameMap = ConcurrentSkipListMap<Int, ByteArray>()
        private var nextPlaySeq = -1
        private var isBuffering = true

        @Synchronized
        fun reset() {
            frameMap.clear()
            nextPlaySeq = -1
            isBuffering = true
        }

        @Synchronized
        fun addFrame(seq: Int, data: ByteArray) {
            if (nextPlaySeq != -1 && seq < nextPlaySeq) {
                // حزمة قديمة متأخرة جداً -> إسقاط
                return
            }
            frameMap[seq] = data

            // منع التراكم الصوتي لمواكبة البث المباشر فوراً (Anti-Lag)
            while (frameMap.size > maxBufferedFrames) {
                val oldest = frameMap.firstKey()
                frameMap.remove(oldest)
                if (nextPlaySeq != -1 && oldest >= nextPlaySeq) {
                    nextPlaySeq = oldest + 1
                }
            }
        }

        @Synchronized
        fun getNextFrame(): ByteArray? {
            if (frameMap.isEmpty()) return null

            if (isBuffering) {
                if (frameMap.size >= prebufferFrames) {
                    isBuffering = false
                    nextPlaySeq = frameMap.firstKey()
                } else {
                    return null
                }
            }

            val targetSeq = nextPlaySeq
            val frame = frameMap.remove(targetSeq)
            if (frame != null) {
                nextPlaySeq++
                return frame
            } else {
                // قفز ذكي في حال ضياع حزمة في الهواء لمنع التكتكة وتوقف البث
                val higherKey = frameMap.higherKey(targetSeq)
                if (higherKey != null) {
                    nextPlaySeq = higherKey + 1
                    return frameMap.remove(higherKey)
                }
                return null
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val secureRandom = SecureRandom()
    private val _currentCall = MutableStateFlow<ActiveCallInfo?>(null)
    val currentCall: StateFlow<ActiveCallInfo?> = _currentCall.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioSocket: DatagramSocket? = null
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var playbackDrainJob: Job? = null
    private var timerJob: Job? = null
    private var callTimeoutJob: Job? = null
    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null
    private val isStreamActive = AtomicBoolean(false)
    private var callWakeLock: PowerManager.WakeLock? = null

    private val sendSeqNumber = AtomicInteger(0)
    private val jitterBuffer = AudioJitterBuffer()
    private val missingKeyLogged = AtomicBoolean(false)
    private val undecryptableFrames = AtomicInteger(0)

    var nearbyAudioSender: ((recipientDeviceId: String, buffer: ByteArray, length: Int) -> Boolean)? = null
    var nearbySignalSender: ((recipientDeviceId: String, packet: NetworkPacket) -> Boolean)? = null

    init {
        IncomingCallActivity.activeCallManager = this
        tcpMessagingManager.callPacketListener = { packet, peerIp ->
            handleIncomingCallPacket(packet, peerIp)
        }
    }

    fun shutdown() {
        stopRingtones()
        stopAudioStream()
        timerJob?.cancel()
        callTimeoutJob?.cancel()
        _currentCall.value = null
    }

    private suspend fun sendCallPacketWithFallback(
        peerId: String,
        peerIp: String,
        peerTcpPort: Int,
        packet: NetworkPacket
    ): Boolean {
        var success = false
        if (!peerIp.startsWith("p2p") && peerIp.isNotBlank() && peerIp != "0.0.0.0") {
            try {
                success = tcpMessagingManager.sendPacketDirect(peerIp, peerTcpPort, packet)
            } catch (_: Exception) {
                success = false
            }
        }
        if (!success) {
            success = nearbySignalSender?.invoke(peerId, packet) ?: false
            if (success) {
                Log.d(TAG, "Sent ${packet::class.java.simpleName} to $peerId via Mesh")
            }
        }
        return success
    }

    fun playAudioChunk(bytes: ByteArray, offset: Int, length: Int) {
        val call = _currentCall.value ?: return
        if (call.status != CallStatus.CONNECTED) return
        if (length <= 0 || length > MAX_AUDIO_CHUNK_SIZE * 2) return
        if (offset < 0 || offset + length > bytes.size) return

        processIncomingAudioBytes(bytes, offset, length)
    }

    /**
     * تشفير حزمة الصوت عبر AES-GCM (AEAD)
     * ترويسة الـ Seq والـ Timestamp موثقة كـ AAD لضمان منع التلاعب
     */
    private fun encryptAudioFrame(
        secretKey: SecretKeySpec,
        seq: Int,
        timestamp: Long,
        pcmData: ByteArray,
        pcmLen: Int
    ): ByteArray? {
        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)

            val header = ByteBuffer.allocate(HEADER_SIZE)
            header.putInt(VOIP_MAGIC)
            header.putInt(seq)
            header.putLong(timestamp)
            val headerBytes = header.array()

            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(headerBytes)
            val cipherPayload = cipher.doFinal(pcmData, 0, pcmLen)

            val totalSize = HEADER_SIZE + GCM_IV_LENGTH + cipherPayload.size
            val packet = ByteArray(totalSize)
            System.arraycopy(headerBytes, 0, packet, 0, HEADER_SIZE)
            System.arraycopy(iv, 0, packet, HEADER_SIZE, GCM_IV_LENGTH)
            System.arraycopy(cipherPayload, 0, packet, HEADER_SIZE + GCM_IV_LENGTH, cipherPayload.size)
            packet
        } catch (e: Exception) {
            Log.e(TAG, "VoIP encrypt frame error: ${e.message}")
            null
        }
    }

    /**
     * فك تشفير حزمة الصوت والتحقق من سلامتها قبل إدخالها للـ Jitter Buffer
     */
    private fun decryptAudioFrame(
        secretKey: SecretKeySpec,
        data: ByteArray,
        offset: Int,
        length: Int
    ): Pair<Int, ByteArray>? {
        if (length < HEADER_SIZE + GCM_IV_LENGTH + GCM_TAG_LENGTH) return null
        return try {
            val byteBuf = ByteBuffer.wrap(data, offset, length)
            val magic = byteBuf.getInt()
            if (magic != VOIP_MAGIC) return null
            val seq = byteBuf.getInt()
            val timestamp = byteBuf.getLong()

            val headerBytes = ByteArray(HEADER_SIZE)
            System.arraycopy(data, offset, headerBytes, 0, HEADER_SIZE)

            val iv = ByteArray(GCM_IV_LENGTH)
            byteBuf.get(iv)

            val cipherLen = length - HEADER_SIZE - GCM_IV_LENGTH
            val cipherBytes = ByteArray(cipherLen)
            byteBuf.get(cipherBytes)

            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(headerBytes)
            val plainPcm = cipher.doFinal(cipherBytes)

            Pair(seq, plainPcm)
        } catch (_: Exception) {
            // أي حزمة تم التلاعب بها أو تالفة في الهواء تُسقط بصمت وأمان
            null
        }
    }

    private fun processIncomingAudioBytes(data: ByteArray, offset: Int, length: Int) {
        val call = _currentCall.value ?: return
        val pairwise = EncryptionManager.getPairwiseManager()
        val audioKey = pairwise?.getAudioKeyForPeer(call.peerId)

        if (audioKey == null) {
            if (missingKeyLogged.compareAndSet(false, true)) {
                Log.e(TAG, "Dropping every inbound audio frame: no pairwise audio key for ${call.peerId}")
            }
            return
        }
        missingKeyLogged.set(false)

        val decrypted = decryptAudioFrame(audioKey, data, offset, length)
        if (decrypted != null) {
            undecryptableFrames.set(0)
            jitterBuffer.addFrame(decrypted.first, decrypted.second)
        } else {
            val bad = undecryptableFrames.incrementAndGet()
            if (bad % 50 == 1) {
                Log.w(TAG, "Undecryptable inbound audio frames for ${call.peerId}: $bad so far")
            }
        }
    }

    private fun handleIncomingCallPacket(packet: NetworkPacket, peerIp: String) {
        when (packet) {
            is CallOfferPacket -> {
                if (packet.calleeId != userPreferences.deviceId) return
                if (packet.callId.isBlank() || packet.callerId.isBlank()) return

                val existingCall = _currentCall.value
                if (existingCall != null &&
                    existingCall.status != CallStatus.IDLE &&
                    existingCall.status != CallStatus.ENDED
                ) {
                    scope.launch {
                        val endPacket = CallEndPacket(
                            packet.callId,
                            userPreferences.deviceId,
                            packet.callerId,
                            "BUSY"
                        )
                        sendCallPacketWithFallback(packet.callerId, peerIp, 9999, endPacket)
                    }
                    return
                }

                _currentCall.value = ActiveCallInfo(
                    callId = packet.callId,
                    peerId = packet.callerId,
                    peerName = packet.callerName.take(200),
                    peerIp = peerIp,
                    peerTcpPort = 9999,
                    peerAudioPort = packet.callerAudioPort.coerceIn(1, 65535),
                    isIncoming = true,
                    status = CallStatus.INCOMING_CALLING
                )

                playIncomingRingtone()
                LanNotificationHelper.showIncomingCallNotification(
                    context,
                    packet.callerName.take(200),
                    packet.callerId,
                    packet.callId
                )

                startCallTimeout()

                scope.launch {
                    val ringingPacket = CallRingingPacket(
                        packet.callId,
                        packet.callerId,
                        userPreferences.deviceId
                    )
                    sendCallPacketWithFallback(packet.callerId, peerIp, 9999, ringingPacket)
                }
            }

            is CallRingingPacket -> {
                val call = _currentCall.value
                if (call != null && call.callId == packet.callId && call.status == CallStatus.OUTGOING_CALLING) {
                    playRingbackTone()
                }
            }

            is CallAnswerPacket -> {
                val call = _currentCall.value
                if (call != null && call.callId == packet.callId) {
                    stopRingtones()
                    callTimeoutJob?.cancel()
                    if (packet.accepted) {
                        val remoteAudioPort = if (packet.calleeAudioPort > 0) {
                            packet.calleeAudioPort.coerceIn(1, 65535)
                        } else {
                            DEFAULT_AUDIO_PORT + 2
                        }
                        _currentCall.value = call.copy(
                            status = CallStatus.CONNECTED,
                            peerAudioPort = remoteAudioPort
                        )
                        startAudioStream(call.peerIp, remoteAudioPort)
                        startCallTimer()
                    } else {
                        endCallInternal("DECLINED")
                    }
                }
            }

            is CallEndPacket -> {
                val call = _currentCall.value
                if (call != null && call.callId == packet.callId) {
                    endCallInternal(packet.reason.take(100))
                }
            }

            else -> {}
        }
    }

    private fun startCallTimeout() {
        callTimeoutJob?.cancel()
        callTimeoutJob = scope.launch {
            delay(CALL_TIMEOUT_MS)
            val call = _currentCall.value
            if (call != null &&
                (call.status == CallStatus.INCOMING_CALLING || call.status == CallStatus.OUTGOING_CALLING)
            ) {
                endCallInternal("TIMEOUT")
            }
        }
    }

    fun startCall(peerId: String, peerName: String, peerIp: String, peerTcpPort: Int = 9999) {
        if (peerId.isBlank() || peerIp.isBlank()) return
        val callId = UUID.randomUUID().toString().substring(0, 8)
        val myAudioPort = DEFAULT_AUDIO_PORT
        _currentCall.value = ActiveCallInfo(
            callId = callId,
            peerId = peerId,
            peerName = peerName.take(200),
            peerIp = peerIp,
            peerTcpPort = peerTcpPort.coerceIn(1, 65535),
            peerAudioPort = DEFAULT_AUDIO_PORT + 2,
            isIncoming = false,
            status = CallStatus.OUTGOING_CALLING
        )
        playRingbackTone()
        startCallTimeout()
        scope.launch {
            val offer = CallOfferPacket(
                callId = callId,
                callerId = userPreferences.deviceId,
                callerName = userPreferences.displayName,
                calleeId = peerId,
                callerAudioPort = myAudioPort
            )
            val success = sendCallPacketWithFallback(peerId, peerIp, peerTcpPort, offer)
            if (!success) {
                endCallInternal("UNREACHABLE")
            }
        }
    }

    fun acceptCall() {
        stopRingtones()
        val call = _currentCall.value ?: return
        if (!call.isIncoming || call.status != CallStatus.INCOMING_CALLING) return
        callTimeoutJob?.cancel()
        val myAudioPort = DEFAULT_AUDIO_PORT + 2
        _currentCall.value = call.copy(status = CallStatus.CONNECTED)
        scope.launch {
            try {
                val answer = CallAnswerPacket(
                    callId = call.callId,
                    callerId = call.peerId,
                    calleeId = userPreferences.deviceId,
                    accepted = true,
                    calleeAudioPort = myAudioPort
                )
                sendCallPacketWithFallback(call.peerId, call.peerIp, call.peerTcpPort, answer)
                startAudioStream(call.peerIp, call.peerAudioPort)
                startCallTimer()
            } catch (e: Exception) {
                Log.e(TAG, "Error in acceptCall: ${e.message}")
            }
        }
    }

    fun declineCall() {
        stopRingtones()
        val call = _currentCall.value ?: return
        callTimeoutJob?.cancel()
        scope.launch {
            try {
                val answer = CallAnswerPacket(
                    callId = call.callId,
                    callerId = call.peerId,
                    calleeId = userPreferences.deviceId,
                    accepted = false,
                    calleeAudioPort = 0
                )
                sendCallPacketWithFallback(call.peerId, call.peerIp, call.peerTcpPort, answer)
            } catch (e: Exception) {
                Log.e(TAG, "Error in declineCall: ${e.message}")
            }
        }
        endCallInternal("DECLINED")
    }

    fun endCall() {
        stopRingtones()
        val call = _currentCall.value ?: return
        callTimeoutJob?.cancel()
        scope.launch {
            try {
                val endPacket = CallEndPacket(
                    callId = call.callId,
                    senderId = userPreferences.deviceId,
                    targetId = call.peerId,
                    reason = "NORMAL"
                )
                sendCallPacketWithFallback(call.peerId, call.peerIp, call.peerTcpPort, endPacket)
            } catch (e: Exception) {
                Log.e(TAG, "Error in endCall: ${e.message}")
            }
        }
        endCallInternal("ENDED")
    }

    private fun endCallInternal(reason: String) {
        stopRingtones()
        stopAudioStream()
        timerJob?.cancel()
        callTimeoutJob?.cancel()
        _currentCall.value = _currentCall.value?.copy(status = CallStatus.ENDED)
        scope.launch {
            delay(1200)
            _currentCall.value = null
        }
    }

    fun toggleMute() {
        val call = _currentCall.value ?: return
        _currentCall.value = call.copy(isMuted = !call.isMuted)
    }

    fun toggleSpeaker() {
        val call = _currentCall.value ?: return
        val newSpeakerState = !call.isSpeakerOn
        _currentCall.value = call.copy(isSpeakerOn = newSpeakerState)
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isSpeakerphoneOn = newSpeakerState
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            var seconds = 0L
            while (isActive && _currentCall.value?.status == CallStatus.CONNECTED) {
                delay(1000)
                seconds++
                _currentCall.value = _currentCall.value?.copy(durationSeconds = seconds)
            }
        }
    }

    private fun startAudioStream(remoteIp: String, remotePort: Int) {
        stopAudioStream()
        if (!isStreamActive.compareAndSet(false, true)) return

        val callAtStart = _currentCall.value
        Log.i(
            TAG,
            "startAudioStream: peer=${callAtStart?.peerId} ip=$remoteIp port=$remotePort " +
                "incoming=${callAtStart?.isIncoming} muted=${callAtStart?.isMuted}"
        )

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start recording: RECORD_AUDIO permission missing")
            isStreamActive.set(false)
            return
        }

        sendSeqNumber.set(0)
        jitterBuffer.reset()

        // حماية نظام أندرويد 14+ من انهيار خدمة الميكروفون
        try {
            LanBackgroundService.updateCallState(context, isCallActive = true)
        } catch (e: Exception) {
            Log.w(TAG, "Safe notice: FGS microphone handled safely: ${e.message}")
        }
        acquireCallWakeLock()

        scope.launch {
            try {
                val currentPeerId = _currentCall.value?.peerId ?: ""
                val pairwise = EncryptionManager.getPairwiseManager()
                val audioKey = pairwise?.getAudioKeyForPeer(currentPeerId)
                if (audioKey == null) {
                    Log.e(TAG, "No pairwise audio key for $currentPeerId: every outgoing frame will be discarded")
                }

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isSpeakerphoneOn = _currentCall.value?.isSpeakerOn ?: true

                val minRecBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
                val minPlayBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
                if (minRecBuf <= 0 || minPlayBuf <= 0) {
                    Log.e(
                        TAG,
                        "Device rejects ${SAMPLE_RATE}Hz PCM16: minRecBuf=$minRecBuf minPlayBuf=$minPlayBuf"
                    )
                    isStreamActive.set(false)
                    releaseCallWakeLock()
                    LanBackgroundService.updateCallState(context, isCallActive = false)
                    return@launch
                }

                val localPort = if (_currentCall.value?.isIncoming == true) {
                    DEFAULT_AUDIO_PORT + 2
                } else {
                    DEFAULT_AUDIO_PORT
                }

                audioSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(localPort))
                    soTimeout = UDP_SOCKET_TIMEOUT_MS
                }

                try {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        CHANNEL_IN,
                        ENCODING,
                        minRecBuf.coerceAtLeast(AUDIO_BUFFER_SIZE * 4)
                    )

                    val sessionId = audioRecord?.audioSessionId ?: 0
                    if (sessionId != 0) {
                        try {
                            if (AcousticEchoCanceler.isAvailable()) {
                                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "AcousticEchoCanceler unavailable: ${e.message}")
                        }
                        try {
                            if (NoiseSuppressor.isAvailable()) {
                                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "NoiseSuppressor unavailable: ${e.message}")
                        }
                    }
                    Log.i(
                        TAG,
                        "Capture effects on session $sessionId: AEC=${echoCanceler?.enabled} " +
                            "NS=${noiseSuppressor?.enabled}"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord init failure: ${e.message}")
                    isStreamActive.set(false)
                    releaseCallWakeLock()
                    LanBackgroundService.updateCallState(context, isCallActive = false)
                    return@launch
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord did not initialize (state=${audioRecord?.state})")
                    audioRecord?.release()
                    audioRecord = null
                    isStreamActive.set(false)
                    releaseCallWakeLock()
                    LanBackgroundService.updateCallState(context, isCallActive = false)
                    return@launch
                }

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(minPlayBuf.coerceAtLeast(AUDIO_BUFFER_SIZE * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack did not initialize (state=${audioTrack?.state})")
                    audioTrack?.release()
                    audioTrack = null
                    isStreamActive.set(false)
                    releaseCallWakeLock()
                    LanBackgroundService.updateCallState(context, isCallActive = false)
                    return@launch
                }

                audioTrack?.play()
                val recordStartResult = audioRecord?.startRecording() ?: AudioRecord.ERROR
                if (recordStartResult != AudioRecord.SUCCESS ||
                    audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING
                ) {
                    Log.e(
                        TAG,
                        "AudioRecord did not start: result=$recordStartResult " +
                            "state=${audioRecord?.recordingState}"
                    )
                    stopAudioStream()
                    return@launch
                }
                Log.i(
                    TAG,
                    "Audio pipeline live: localPort=$localPort -> $remoteIp:$remotePort " +
                        "aec=${echoCanceler?.enabled} ns=${noiseSuppressor?.enabled}"
                )

                val isP2pStream = remoteIp.startsWith("p2p") || remoteIp.isBlank() || remoteIp == "0.0.0.0"
                val targetAddress: InetAddress? = if (!isP2pStream) {
                    try {
                        InetAddress.getByName(remoteIp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot resolve peer address $remoteIp: ${e.message}")
                        null
                    }
                } else null

                if (!isP2pStream && targetAddress == null) {
                    Log.e(TAG, "No UDP receive loop for $remoteIp: inbound audio cannot arrive")
                }

                // مسار تسجيل وتشفير الصوت الحي عبر AES-GCM
                recordJob = launch {
                    val rawBuffer = ByteArray(AUDIO_BUFFER_SIZE)
                    val aggregationBuffer = ByteArrayOutputStream()
                    var capturedFrames = 0

                    while (isActive && _currentCall.value?.status == CallStatus.CONNECTED) {
                        val recorder = audioRecord ?: break
                        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            Log.e(TAG, "Capture loop stopped: AudioRecord state=${recorder.recordingState}")
                            break
                        }

                        val readBytes = recorder.read(rawBuffer, 0, rawBuffer.size)
                        if (readBytes > 0) {
                            capturedFrames++
                            if (_currentCall.value?.isMuted == true) {
                                rawBuffer.fill(0, 0, readBytes)
                            }
                            val seq = sendSeqNumber.getAndIncrement()
                            val now = System.currentTimeMillis()

                            // تشفير الحزمة الصوتية بمفتاح الجلسة المشترك
                            val encryptedPacket = if (audioKey != null) {
                                encryptAudioFrame(audioKey, seq, now, rawBuffer, readBytes)
                            } else null

                            if (capturedFrames % CAPTURE_DIAG_INTERVAL_FRAMES == 0) {
                                var nonZero = 0
                                for (i in 0 until readBytes) {
                                    if (rawBuffer[i].toInt() != 0) nonZero++
                                }
                                Log.i(
                                    TAG,
                                    "Capture diag: frames=$capturedFrames nonZeroBytes=$nonZero/$readBytes " +
                                        "muted=${_currentCall.value?.isMuted} encrypted=${encryptedPacket != null}"
                                )
                            }

                            if (encryptedPacket != null) {
                                if (isP2pStream) {
                                    aggregationBuffer.write(encryptedPacket)
                                    if (aggregationBuffer.size() >= NEARBY_BATCH_SIZE) {
                                        val aggregated = aggregationBuffer.toByteArray()
                                        aggregationBuffer.reset()
                                        nearbyAudioSender?.invoke(currentPeerId, aggregated, aggregated.size)
                                    }
                                } else {
                                    if (targetAddress != null) {
                                        try {
                                            val packet = DatagramPacket(
                                                encryptedPacket,
                                                encryptedPacket.size,
                                                targetAddress,
                                                remotePort
                                            )
                                            audioSocket?.send(packet)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Audio datagram send failed: ${e.message}")
                                        }
                                    }
                                }
                            }
                        } else if (readBytes < 0) {
                            Log.e(TAG, "Capture loop stopped: AudioRecord.read error=$readBytes")
                            break
                        }
                    }
                    Log.i(TAG, "Capture loop ended after $capturedFrames frames")
                }

                // مسار استقبال الصوت وفك التشفير إلى الـ Jitter Buffer
                if (targetAddress != null) {
                    playJob = launch {
                        val recvBuffer = ByteArray(MAX_AUDIO_CHUNK_SIZE)
                        val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                        var receivedPackets = 0
                        while (isActive && _currentCall.value?.status == CallStatus.CONNECTED) {
                            try {
                                audioSocket?.receive(recvPacket)
                                val len = recvPacket.length
                                if (len > 0) {
                                    receivedPackets++
                                    processIncomingAudioBytes(recvPacket.data, recvPacket.offset, len)
                                }
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            } catch (e: Exception) {
                                Log.e(TAG, "Receive loop stopped after $receivedPackets packets: ${e.message}")
                                break
                            }
                        }
                        Log.i(TAG, "Receive loop ended after $receivedPackets packets")
                    }
                }

                // مسار تشغيل الصوت النقي بدون أي تقطيع
                playbackDrainJob = launch {
                    var playedFrames = 0
                    var failedWrites = 0
                    while (isActive && _currentCall.value?.status == CallStatus.CONNECTED) {
                        val frame = jitterBuffer.getNextFrame()
                        if (frame != null) {
                            val written = try {
                                audioTrack?.write(frame, 0, frame.size) ?: 0
                            } catch (e: Exception) {
                                failedWrites++
                                Log.e(TAG, "AudioTrack write threw: ${e.message}")
                                0
                            }
                            if (written < 0) {
                                failedWrites++
                                if (failedWrites <= 3) {
                                    Log.e(
                                        TAG,
                                        "AudioTrack write error=$written trackState=${audioTrack?.state} " +
                                            "playState=${audioTrack?.playState}"
                                    )
                                }
                            } else {
                                playedFrames++
                            }
                        } else {
                            delay(10)
                        }
                    }
                    Log.i(TAG, "Playback loop ended: played=$playedFrames failedWrites=$failedWrites")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio stream error: ${e.message}")
                isStreamActive.set(false)
                releaseCallWakeLock()
                LanBackgroundService.updateCallState(context, isCallActive = false)
            }
        }
    }

    private fun acquireCallWakeLock() {
        try {
            if (callWakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                callWakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LANChat::ActiveCallAudioLock"
                )
            }
            if (callWakeLock?.isHeld == false) {
                callWakeLock?.acquire(3600_000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    private fun releaseCallWakeLock() {
        try {
            if (callWakeLock?.isHeld == true) {
                callWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun stopAudioStream() {
        if (isStreamActive.getAndSet(false)) {
            Log.i(TAG, "stopAudioStream: tearing down audio pipeline")
        }
        recordJob?.cancel()
        playJob?.cancel()
        playbackDrainJob?.cancel()
        recordJob = null
        playJob = null
        playbackDrainJob = null

        jitterBuffer.reset()
        releaseCallWakeLock()
        LanBackgroundService.updateCallState(context, isCallActive = false)

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            audioSocket?.close()
        } catch (_: Exception) {}
        audioSocket = null
    }

    private fun playIncomingRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playRingbackTone() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 25000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRingtones() {
        try {
            ringtone?.stop()
            ringtone = null
        } catch (_: Exception) {}
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
        try {
            LanNotificationHelper.cancelCallNotification(context)
        } catch (_: Exception) {}
    }
}