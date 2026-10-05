package com.example.data.network

import android.content.Context
import android.util.Log
import com.example.data.local.ChatDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ContactEntity
import com.example.data.local.MessageStatus
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager

import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
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
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class MeshPeerInfo(
    val deviceId: String,
    val displayName: String,
    val ipAddress: String,
    val hops: Int = 1,
    val lastSeen: Long = System.currentTimeMillis(),
    val isNearbyConnected: Boolean = true
)

data class MeshStatus(
    val isEnabled: Boolean = true,
    val isDiscovering: Boolean = false,
    val connectedNodesCount: Int = 0,
    val relayedPacketsCount: Int = 0,
    val activePeers: List<MeshPeerInfo> = emptyList(),
    val isNearbyP2PActive: Boolean = false,
    val unavailableReason: String? = null
) {
    val activeMeshNodes: Int get() = connectedNodesCount
    val totalPacketsRelayed: Int get() = relayedPacketsCount
}

class NearbyMeshManager(
    private val context: Context,
    private val database: ChatDatabase,
    private val userPreferences: UserPreferences,
    private val tcpMessagingManager: TcpMessagingManager
) {

    companion object {
        private const val TAG = "NearbyMeshManager"
        const val SERVICE_ID = "com.example.lan.mesh"
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        private const val MAX_HOPS = 8
        private const val MAX_SEEN_CACHE = 5000
        private const val MAX_INCOMING_FILE_PAYLOADS = 50
        private const val SEEN_PACKET_TTL_MS = 10 * 60 * 1000L
        const val PLAY_SERVICES_UNAVAILABLE = "Google Play services unavailable"
        const val MAX_DIRECT_CONNECTIONS = 4
        const val RESERVED_STRANGER_SLOTS = 1
    private const val RECONNECT_TICK_MS = 1_000L
    private const val RECONNECT_REQUEST_GAP_MS = 250L
        private const val PENDING_FILE_META_TTL_MS = 30 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context.applicationContext)
    }

    private val seenMeshPackets = ConcurrentHashMap<String, Long>()
    private var meshHeartbeatJob: Job? = null

    private val endpointToPeer = ConcurrentHashMap<String, DiscoveredPeerInfo>()
    private val deviceIdToEndpoint = ConcurrentHashMap<String, String>()
    private val incomingFilePayloads = ConcurrentHashMap<Long, Payload>()
    private val incomingFileMetaMap = ConcurrentHashMap<Long, FileMetaInfo>()

    /**
     * endpointToPeer فيها كل الـ endpoints المكتشفة حتى لو ما اتصلناش بيهم،
     * فاستخدام حجمها كعدد خانات كان بيخلي 4 غرباء مكتشفين يغلقوا الباب في
     * وجه جهة اتصال محفوظة. خانات الاتصال دي بتتعدّ بالاتصال المحجوز فعلاً
     * بس، وكل خانة فيها lastActiveAt عشان نطرد أقدم غريب (LRU) لما تحب
     * جهة اتصال محفوظة تدخل.
     */
    private val connectionSlots = ConcurrentHashMap<String, ConnectionSlot>()
    private val connectionSlotLock = ReentrantLock()

    /**
     * الميتا كان بيتخزّن بـ messageId.hashCode() وبينقرأ بـ payloadId بتاع Nearby،
     * فالمفتاح مش بيطابق أبداً وmeta كان بيفضل null — يعني finalizeReceivedFile
     * مكانش بتتنفّذ خالص وأي ملف/صورة عبر MESH كان بيضيع بصمت.
     *Nearby بيبعت الـ payloads بالترتيب على نفس الاتصال، فبنربط الميتا
     * بالـ payload وقت ما الملف يوصل فعلاً.
     */
    private val pendingFileMeta = ConcurrentLinkedQueue<Pair<Long, FileMetaInfo>>()

    data class DiscoveredPeerInfo(
        val endpointId: String,
        val deviceId: String,
        val displayName: String,
        val avatarColorIndex: Int = 0,
        val isDeveloper: Boolean = false,
        val appVersionCode: Int = 1,
        val publicKeyBase64: String? = null
    )

    /** خانة اتصال محجوزة: isKnown بتتخان وقت التشغيل لما الجهاز يبقى جهة اتصال محفوظة. */
    private class ConnectionSlot(
        val endpointId: String,
        @Volatile var isKnown: Boolean,
        @Volatile var lastActiveAt: Long
    )

    data class FileMetaInfo(
        val messageId: String,
        val senderId: String,
        val senderName: String,
        val recipientId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val caption: String,
        val isVideo: Boolean,
        val isPhoto: Boolean = false,
        val isGroup: Boolean,
        val groupId: String?,
        val groupName: String?
    )

    private val _meshStatus = MutableStateFlow(
        MeshStatus(isEnabled = userPreferences.isMeshModeEnabled)
    )
    val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

    private val startLatch = MeshStartLatch()
    private val supervisor = ConnectionSupervisor()
    private val slotPolicy = ConnectionSlotPolicy()
    private var reconnectJob: Job? = null

    /** Per-peer connection state for the UI to animate. */
    val connectionPhases: kotlinx.coroutines.flow.StateFlow<Map<String, ConnectionPhase>>
        get() = supervisor.phases

    private fun publishRunningState() {
        val running = startLatch.isRunning
        if (_meshStatus.value.isNearbyP2PActive != running) {
            _meshStatus.value = _meshStatus.value.copy(isNearbyP2PActive = running)
        }
    }

    var liveAudioListener: ((bytes: ByteArray, offset: Int, length: Int) -> Unit)? = null

    private fun isRoutableIp(value: String?): Boolean = RouteResolver.isRoutableIp(value ?: "")

    private fun getMyEndpointName(): String {
        val cleanName = userPreferences.displayName.replace("|", "").take(20)
        return "${userPreferences.deviceId}|$cleanName|${userPreferences.avatarColorIndex}|${if (userPreferences.isDeveloper) 1 else 0}|${userPreferences.appVersionCode}"
    }

    private fun parseEndpointName(raw: String, endpointId: String): DiscoveredPeerInfo {
        return try {
            val parts = raw.split("|")
            if (parts.size >= 5) {
                DiscoveredPeerInfo(
                    endpointId = endpointId,
                    deviceId = parts[0],
                    displayName = parts[1],
                    avatarColorIndex = parts[2].toIntOrNull() ?: 0,
                    isDeveloper = parts[3] == "1",
                    appVersionCode = parts[4].toIntOrNull() ?: 1,
                    publicKeyBase64 = null
                )
            } else if (parts.size >= 2) {
                DiscoveredPeerInfo(
                    endpointId = endpointId,
                    deviceId = parts[0],
                    displayName = parts[1]
                )
            } else {
                DiscoveredPeerInfo(
                    endpointId = endpointId,
                    deviceId = "p2p_${endpointId.take(6)}",
                    displayName = raw.take(15)
                )
            }
        } catch (e: Exception) {
            DiscoveredPeerInfo(
                endpointId = endpointId,
                deviceId = "p2p_${endpointId.take(6)}",
                displayName = "مستخدم جديد"
            )
        }
    }

    fun toggleMeshMode(enabled: Boolean) {
        userPreferences.isMeshModeEnabled = enabled
        _meshStatus.value = _meshStatus.value.copy(isEnabled = enabled)
        if (enabled) startMeshService() else stopMeshService()
    }

    fun startMeshService() {
        if (!userPreferences.isMeshModeEnabled) return
        if (startLatch.isRunning) {
            Log.d(TAG, "Mesh already running, skipping restart")
            return
        }
        if (!isPlayServicesAvailable()) {
            _meshStatus.value = _meshStatus.value.copy(
                isDiscovering = false,
                isNearbyP2PActive = false,
                unavailableReason = PLAY_SERVICES_UNAVAILABLE
            )
            Log.w(TAG, "Mesh not started: Google Play services unavailable on this device")
            return
        }
        _meshStatus.value = _meshStatus.value.copy(
            isDiscovering = true,
            isNearbyP2PActive = false,
            unavailableReason = null
        )
        startAdvertising()
        startDiscovery()
        startMeshHeartbeat()
        startReconnectLoop()
    }

    /**
     * The one place that re-dials peers. Every teardown callback funnels into
     * the supervisor, which decides when a peer is due; this loop just performs
     * the request. Backoff and the "known contacts first" ordering live in
     * [ConnectionSupervisor] and [ReconnectLoop] so they stay testable.
     */
    private fun startReconnectLoop() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (isActive) {
                delay(RECONNECT_TICK_MS)
                if (!userPreferences.isMeshModeEnabled) continue
                if (!startLatch.isRunning) continue
                for (target in ReconnectLoop.selectDue(supervisor, System.currentTimeMillis())) {
                    val known = endpointToPeer.values.any { it.deviceId == target.deviceId }
                    if (!slotPolicy.mayAdmit(target.deviceId, target.isKnown || known, System.currentTimeMillis())) {
                        continue
                    }
                    if (!reserveConnectionSlot(target.endpointId, target.isKnown)) continue
                    supervisor.onConnectRequested(target.deviceId, target.endpointId)
                    runCatching {
                        connectionsClient.requestConnection(
                            getMyEndpointName(), target.endpointId, connectionLifecycleCallback
                        )
                    }.onFailure { e ->
                        Log.w(TAG, "Reconnect request to ${target.endpointId} failed: ${e.message}")
                        supervisor.onFailure(target.deviceId, "reconnect request failed")
                    }
                    delay(RECONNECT_REQUEST_GAP_MS)
                }
            }
        }
    }

    /**
     * A genuine restart. Pull-to-refresh used to call startMeshService(), which
     * early-returned on a stale flag and therefore did nothing.
     */
    fun forceRestartMesh() {
        if (!userPreferences.isMeshModeEnabled) return
        stopMeshService()
        startMeshService()
    }

    private fun isPlayServicesAvailable(): Boolean = try {
        val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        availability.isGooglePlayServicesAvailable(context) ==
            com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (e: Exception) {
        Log.w(TAG, "Play services availability check failed: ${e.message}")
        false
    }

    /**
     * Nearby ترجع 8001/8002 لما يكون الاكتشاف أو الإعلان شغال بالفعل، وده مش عطل.
     * كان الكود بيعتبره فشل فبيطلع بانر أحمر فاضل والـ Mesh بيبقى شغال فعلاً.
     */
    private fun isBenignNearbyStatus(statusCode: Int): Boolean =
        statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING ||
            statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING

    private fun exceptionStatusCode(e: Exception): Int =
        (e as? com.google.android.gms.common.api.ApiException)?.status?.statusCode ?: -1

    private fun onMeshStartFailed(reason: String, statusCode: Int = -1) {
        if (isBenignNearbyStatus(statusCode)) {
            // 8001/8002 mean Nearby is already up. Record that as a success
            // rather than treating it as a failure, so the latch reflects reality
            // instead of being stuck on whichever branch ran last.
            Log.d(TAG, "Mesh already running ($statusCode)")
            if (reason.startsWith("advertising")) startLatch.onAdvertisingStarted()
            else startLatch.onDiscoveryStarted()
            publishRunningState()
            return
        }
        startLatch.onStartFailed(statusCode)
        _meshStatus.value = _meshStatus.value.copy(
            isDiscovering = false,
            isNearbyP2PActive = false,
            unavailableReason = reason
        )
        Log.e(TAG, "Mesh unavailable: $reason")
    }

    fun stopMeshService() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Nearby services: ${e.message}")
        }
        endpointToPeer.clear()
        deviceIdToEndpoint.clear()
        connectionSlots.clear()
        meshHeartbeatJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        startLatch.onStopped()
        _meshStatus.value = _meshStatus.value.copy(
            isDiscovering = false, connectedNodesCount = 0,
            activePeers = emptyList(), isNearbyP2PActive = false
        )
    }

    private fun startAdvertising() {
        try {
            val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
            connectionsClient.startAdvertising(
                getMyEndpointName(), SERVICE_ID, connectionLifecycleCallback, options
            ).addOnSuccessListener {
                startLatch.onAdvertisingStarted()
                publishRunningState()
                Log.d(TAG, "Nearby Advertising started")
            }.addOnFailureListener { e ->
                onMeshStartFailed("advertising failed: ${e.message}", exceptionStatusCode(e))
            }
        } catch (e: Exception) {
            onMeshStartFailed("advertising exception: ${e.message}")
        }
    }

    private fun startDiscovery() {
        try {
            val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
            connectionsClient.startDiscovery(
                SERVICE_ID, endpointDiscoveryCallback, options
            ).addOnSuccessListener {
                startLatch.onDiscoveryStarted()
                publishRunningState()
                Log.d(TAG, "Nearby Discovery started")
            }.addOnFailureListener { e ->
                onMeshStartFailed("discovery failed: ${e.message}", exceptionStatusCode(e))
            }
        } catch (e: Exception) {
            onMeshStartFailed("discovery exception: ${e.message}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerInfo = parseEndpointName(info.endpointName, endpointId)
            endpointToPeer[endpointId] = peerInfo
            deviceIdToEndpoint[peerInfo.deviceId] = endpointId
            registerDiscoveredPeer(peerInfo)
            updateMeshPeersState()
            considerConnection(endpointId, peerInfo)
        }

        override fun onEndpointLost(endpointId: String) {
            val peer = endpointToPeer.remove(endpointId)
            if (peer != null) deviceIdToEndpoint.remove(peer.deviceId)
            // الـ endpoint ده مش هيتشاف تاني، ف freeing الخانة لازم يحصل فوراً
            // عشان ما تفضل محجوزة للأبد وتمنع أي اتصال جاي.
            connectionSlots.remove(endpointId)
            updateMeshPeersState()
        }
    }

    /**
     * الأولوية: جهات الاتصال المحفوظة أولاً، والغرباء يملأون السslots المتبقية بس.
     * P2P_CLUSTER فوق Bluetooth يدعم 3-4 اتصالات حقيقية فقط، فلو اتصلنا
     * بالغرباء بتخلي اللي عايز تكلمه مايلقاش خانة.
     */
    private fun considerConnection(endpointId: String, peer: DiscoveredPeerInfo) {
        scope.launch {
            val myDeviceId = userPreferences.deviceId
            // الطرف التاني (deviceId الأكبر) هو اللي بيبعت طلب الاتصال،
            // فماحناش خانة قبل ما نعرف إننا هنتصل أصلاً.
            if (myDeviceId >= peer.deviceId) return@launch

            val known = isKnownContact(peer.deviceId)
            supervisor.onDiscovered(peer.deviceId, endpointId, known)
            if (!slotPolicy.mayAdmit(peer.deviceId, known, System.currentTimeMillis())) {
                Log.d(TAG, "Skip connection to $endpointId (cooldown after a previous refusal)")
                return@launch
            }
            if (!reserveConnectionSlot(endpointId, known)) {
                Log.d(TAG, "Skip connection to $endpointId (known=$known, slots=${connectionSlots.size})")
                return@launch
            }
            supervisor.onConnectRequested(peer.deviceId, endpointId)
            try {
                connectionsClient.requestConnection(
                    getMyEndpointName(), endpointId, connectionLifecycleCallback
                ).addOnSuccessListener {
                    Log.d(TAG, "Auto-connection requested to $endpointId (known=$known)")
                }.addOnFailureListener { e ->
                    // Without this the peer stays CONNECTING forever: the request
                    // failed before any lifecycle callback fired, so nothing would
                    // ever schedule a retry.
                    Log.w(TAG, "Auto-connection request failed: ${e.message}")
                    connectionSlots.remove(endpointId)
                    supervisor.onFailure(peer.deviceId, "request failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating auto-connection: ${e.message}")
                connectionSlots.remove(endpointId)
                supervisor.onFailure(peer.deviceId, "request threw: ${e.message}")
            }
        }
    }

    private suspend fun isKnownContact(deviceId: String): Boolean = try {
        database.contactDao().getContactById(deviceId) != null
    } catch (e: Exception) {
        false
    }

    /**
     * سياسة الخانات: جهة الاتصال المحفوظة دايماً بتتقبل (حتى لو السعة امتلأت
     * بجهات اتصال تانية)، ولو السعة ضاقت بنطرد أقدم غريب LRU عشان نعمل له
     * مكان. الغريب ماشي غير على الخانة اللي فاضلة بس (RESERVED_STRANGER_SLOTS)،
     * فمقدرش يزيح جهة اتصال محفوظة عن آخر خانة متاحة.
     *
     * الـ check والحجز لازم يبقوا في نفس القفل، وإلا اتصالات Nearby المتوازية
     * هيعدّي كل واحدة على بعض.
     */
    private fun reserveConnectionSlot(endpointId: String, isKnown: Boolean): Boolean {
        val evicted = mutableListOf<String>()
        val admitted = connectionSlotLock.withLock {
            connectionSlots[endpointId]?.let { slot ->
                slot.isKnown = slot.isKnown || isKnown
                slot.lastActiveAt = System.currentTimeMillis()
                return@withLock true
            }
            if (isKnown) {
                while (connectionSlots.size >= MAX_DIRECT_CONNECTIONS) {
                    val lruStranger = connectionSlots.values
                        .filter { !it.isKnown }
                        .minByOrNull { it.lastActiveAt }
                        ?: break
                    connectionSlots.remove(lruStranger.endpointId)
                    evicted.add(lruStranger.endpointId)
                }
            } else if (connectionSlots.size >= MAX_DIRECT_CONNECTIONS - RESERVED_STRANGER_SLOTS) {
                return@withLock false
            }
            connectionSlots[endpointId] = ConnectionSlot(endpointId, isKnown, System.currentTimeMillis())
            true
        }
        for (victim in evicted) disconnectEvictedStranger(victim)
        return admitted
    }

    /** بنستخدمها في LRU: أي تبادل بيانات مع الطرف بحدّث نشاطه. */
    private fun touchConnectionSlot(endpointId: String) {
        connectionSlots[endpointId]?.lastActiveAt = System.currentTimeMillis()
    }

    /** الجهاز بقى جهة اتصال محفوظة: من دلوقتي ما بيتطردش أبداً. */
    private fun markConnectionSlotAsKnown(endpointId: String) {
        connectionSlots[endpointId]?.isKnown = true
    }

    /** الخانة بتتشال جوه القفل قبل هنا، ف دي بتقطع الاتصال فعلاً وبس. */
    private fun disconnectEvictedStranger(endpointId: String) {
        // Damps the retry cadence so the evicted peer does not immediately
        // re-request the slot it just lost.
        endpointToPeer[endpointId]?.let {
            slotPolicy.noteRefused(it.deviceId, System.currentTimeMillis())
        }
        try {
            connectionsClient.disconnectFromEndpoint(endpointId)
            Log.d(TAG, "Dropped stranger connection $endpointId to free a slot for a known contact")
        } catch (e: Exception) {
            Log.w(TAG, "Error dropping endpoint $endpointId: ${e.message}")
        }
    }

    /**
     * الطرف المستقبِل هو المرجع على سعته هو، فبنقبل الاتصال فوراً (مش بنستنى
     * قراءة الداتابيز عشان نضيع نافذة القبول) وبعدها بنطبّق الحصة: لو اتصلنا
     * غربي والسعة مش مكفية بنقطعه. جهة الاتصال المحفوظة مبيتقطعش أبداً.
     */
    private fun enforceInboundSlot(endpointId: String, deviceId: String) {
        scope.launch {
            if (reserveConnectionSlot(endpointId, isKnownContact(deviceId))) return@launch
            Log.d(TAG, "Refusing over-cap stranger connection from $endpointId")
            slotPolicy.noteRefused(deviceId, System.currentTimeMillis())
            try {
                connectionsClient.disconnectFromEndpoint(endpointId)
            } catch (e: Exception) {
                Log.w(TAG, "Error refusing endpoint $endpointId: ${e.message}")
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            val peerInfo = parseEndpointName(connectionInfo.endpointName, endpointId)
            endpointToPeer[endpointId] = peerInfo
            deviceIdToEndpoint[peerInfo.deviceId] = endpointId
            supervisor.onConnectRequested(peerInfo.deviceId, endpointId)
            try {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnSuccessListener { Log.d(TAG, "Accepted connection with $endpointId") }
                    .addOnFailureListener { e -> Log.e(TAG, "Failed to accept: ${e.message}") }
            } catch (e: Exception) {
                Log.e(TAG, "Exception accepting connection: ${e.message}")
            }
            enforceInboundSlot(endpointId, peerInfo.deviceId)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                val peer = endpointToPeer[endpointId]
                if (peer != null) {
                    deviceIdToEndpoint[peer.deviceId] = endpointId
                    registerDiscoveredPeer(peer)
                }
                touchConnectionSlot(endpointId)
                supervisor.onConnected(peer?.deviceId ?: endpointId, endpointId)
                sendHandshakePacket(endpointId)
                updateMeshPeersState()
            } else {
                val peer = endpointToPeer.remove(endpointId)
                if (peer != null) deviceIdToEndpoint.remove(peer.deviceId)
                connectionSlots.remove(endpointId)
                // Previously this just deleted state and gave up, so recovery
                // depended on Nearby spontaneously re-firing onEndpointFound.
                if (peer != null) supervisor.onFailure(peer.deviceId, "connection failed")
                updateMeshPeersState()
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peer = endpointToPeer.remove(endpointId)
            if (peer != null) deviceIdToEndpoint.remove(peer.deviceId)
            connectionSlots.remove(endpointId)
            if (peer != null) supervisor.onDisconnected(peer.deviceId, "disconnected")
            updateMeshPeersState()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            touchConnectionSlot(endpointId)
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    if (bytes.isNotEmpty() && bytes[0] == 0x01.toByte()) {
                        liveAudioListener?.invoke(bytes, 1, bytes.size - 1)
                    } else {
                        val rawJson = String(bytes, Charsets.UTF_8)
                        handleIncomingRawPacket(rawJson, endpointId)
                    }
                }
                Payload.Type.FILE -> {
                    if (incomingFilePayloads.size < MAX_INCOMING_FILE_PAYLOADS) {
                        incomingFilePayloads[payload.id] = payload
                        // الميتا بتتبعت قبل الملف على نفس الاتصال، فبنربط أول
                        // ميتا في الطابور بالـ payload ده.
                        val queued = pollPendingFileMeta()
                        if (queued != null) {
                            incomingFileMetaMap[payload.id] = queued.second
                            Log.d(TAG, "Bound meta ${queued.second.messageId} to payload ${payload.id}")
                        } else {
                            Log.w(TAG, "File payload ${payload.id} arrived with no pending meta")
                        }
                    } else {
                        Log.w(TAG, "Too many incoming file payloads, rejecting")
                    }
                }
                Payload.Type.STREAM -> {
                    Log.d(TAG, "Incoming stream payload from $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val payloadId = update.payloadId
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val filePayload = incomingFilePayloads.remove(payloadId)
                    val meta = incomingFileMetaMap.remove(payloadId)
                    if (filePayload != null && meta != null) {
                        finalizeReceivedFile(filePayload, meta)
                    } else {
                        Log.w(TAG, "Transfer $payloadId finished but payload/meta missing (payload=${filePayload != null}, meta=${meta != null})")
                    }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    incomingFilePayloads.remove(payloadId)
                    incomingFileMetaMap.remove(payloadId)
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {}
            }
        }
    }

    /**
     * الميتا بتيجي قبل الملف على نفس الاتصال، بس لو الملف موصلش (الجهاز قفل
     * أو التحويل اترفض) الميتا كانت هتفضل متعلّقة: الطابور بيكبر من غير ما
     * يخلص، وملف جاي بعد ساعة ياخد ميتا رسايل تانية غلط. بنDiscard أي ميتا
     * عدّت على TTL قبل ما نربط.
     */
    private fun pollPendingFileMeta(): Pair<Long, FileMetaInfo>? {
        val now = System.currentTimeMillis()
        while (true) {
            val head = pendingFileMeta.peek() ?: return null
            if (now - head.first > PENDING_FILE_META_TTL_MS) {
                pendingFileMeta.poll()
                Log.w(TAG, "Dropped stale file meta for ${head.second.fileName}")
                continue
            }
            return pendingFileMeta.poll()
        }
    }

    /**
     * جهاز قريب جديد: بيتسجل في جدول مؤقت (مش في جهات الاتصال) عشان ما يظهرش
     * في قائمة المحادثات من غير ما المستخدم يختاره. لو كان بالفعل جهة اتصال
     * هنحدّث سجله عادي.
     */
    private fun registerDiscoveredPeer(peer: DiscoveredPeerInfo) {
        scope.launch {
            if (!peer.publicKeyBase64.isNullOrBlank()) {
                EncryptionManager.getPairwiseManager()?.registerPeerPublicKey(peer.deviceId, peer.publicKeyBase64)
            }
            val existing = database.contactDao().getContactById(peer.deviceId)
            if (existing == null) {
                val lanRow = database.discoveredPeerDao().getById(peer.deviceId)
                database.discoveredPeerDao().upsert(
                    com.example.data.local.DiscoveredPeerEntity(
                        deviceId = peer.deviceId,
                        displayName = peer.displayName,
                        endpointId = peer.endpointId,
                        ipAddress = lanRow?.ipAddress,
                        publicKeyBase64 = peer.publicKeyBase64 ?: lanRow?.publicKeyBase64,
                        avatarColorIndex = peer.avatarColorIndex,
                        isDeveloper = peer.isDeveloper,
                        appVersionCode = peer.appVersionCode,
                        transportIsLan = lanRow?.transportIsLan ?: false
                    )
                )
                return@launch
            }
            val lanIp = existing.ipAddress.takeIf { isRoutableIp(it) }
            val updated = existing.copy(
                displayName = existing.customNickname ?: peer.displayName,
                lastSeen = System.currentTimeMillis(),
                isOnline = true,
                isDeveloper = peer.isDeveloper,
                appVersionCode = peer.appVersionCode,
                // Mesh فقط لو مفيش عنوان LAN، وإلا الاتصال بيتم عبر الشبكة العادية
                isMeshPeer = lanIp == null,
                publicKeyBase64 = peer.publicKeyBase64 ?: existing.publicKeyBase64
            )
            database.contactDao().insertOrUpdateContact(updated)
        }
    }

    private fun sendHandshakePacket(endpointId: String) {
        scope.launch {
            val publicKey = EncryptionManager.getPairwiseManager()?.getMyPublicKeyBase64()
            val beacon = BeaconPacket(
                deviceId = userPreferences.deviceId,
                displayName = userPreferences.displayName,
                avatarColorIndex = userPreferences.avatarColorIndex,
                tcpPort = 9999,
                isDeveloper = userPreferences.isDeveloper,
                versionCode = userPreferences.appVersionCode,
                versionName = userPreferences.appVersionName,
                publicKeyBase64 = publicKey
            )
            // حزم الـ Beacon العامة تظل JSON مفتوحاً لبناء الجلسات
            sendPayloadBytes(endpointId, beacon.toJson())
        }
    }

    private fun handleIncomingRawPacket(jsonString: String, fromEndpointId: String) {
        scope.launch {
            try {
                val decrypted = if (EncryptionManager.isEncrypted(jsonString)) {
                    val peer = endpointToPeer[fromEndpointId]
                    if (peer != null && jsonString.startsWith("PENC:")) {
                        // Phase 1.2: a pairwise blob that will not decrypt for
                        // the peer it is addressed to is dropped rather than
                        // retried under the device key.
                        EncryptionManager.getPairwiseManager()
                            ?.decryptFromPeer(peer.deviceId, jsonString)
                    } else {
                        EncryptionManager.decrypt(jsonString)
                    }
                } else {
                    jsonString
                }

                // Phase 1.2: a null here means the payload was not protected for
                // this peer, so it is dropped instead of being parsed or retried.
                val packet = decrypted?.let { NetworkPacket.fromJson(it) } ?: return@launch

                when (packet) {
                    is BeaconPacket -> {
                        val peer = DiscoveredPeerInfo(
                            endpointId = fromEndpointId, deviceId = packet.deviceId,
                            displayName = packet.displayName, avatarColorIndex = packet.avatarColorIndex,
                            isDeveloper = packet.isDeveloper, appVersionCode = packet.versionCode,
                            publicKeyBase64 = packet.publicKeyBase64
                        )
                        endpointToPeer[fromEndpointId] = peer
                        deviceIdToEndpoint[packet.deviceId] = fromEndpointId
                        if (packet.publicKeyBase64 != null) {
                            EncryptionManager.getPairwiseManager()?.registerPeerPublicKey(packet.deviceId, packet.publicKeyBase64)
                        }
                        registerDiscoveredPeer(peer)
                        updateMeshPeersState()
                    }
                    is BeaconAckPacket -> {
                        val peer = DiscoveredPeerInfo(
                            endpointId = fromEndpointId, deviceId = packet.deviceId,
                            displayName = packet.displayName, avatarColorIndex = packet.avatarColorIndex,
                            isDeveloper = packet.isDeveloper, appVersionCode = packet.versionCode,
                            publicKeyBase64 = packet.publicKeyBase64
                        )
                        endpointToPeer[fromEndpointId] = peer
                        deviceIdToEndpoint[packet.deviceId] = fromEndpointId
                        if (packet.publicKeyBase64 != null) {
                            EncryptionManager.getPairwiseManager()?.registerPeerPublicKey(packet.deviceId, packet.publicKeyBase64)
                        }
                        registerDiscoveredPeer(peer)
                        updateMeshPeersState()
                    }
                    is MeshRelayPacket -> {
                        handleIncomingMeshPacket(packet, senderIp = "p2p-$fromEndpointId")
                    }
                    is TextMessagePacket -> {
                        if (packet.signatureBase64 != null) {
                            val peer = database.contactDao().getContactById(packet.senderId)
                            if (peer?.publicKeyBase64 != null) {
                                val valid = EncryptionManager.getPairwiseManager()?.verifySignature(
                                    peer.publicKeyBase64, packet.text.toByteArray(Charsets.UTF_8), packet.signatureBase64
                                ) ?: false
                                if (!valid) {
                                    Log.w(TAG, "Security Alert: Signature mismatch for text from ${packet.senderId}")
                                }
                            }
                        }

                        val convId = if (packet.isGroup && !packet.groupId.isNullOrBlank()) packet.groupId else packet.senderId
                        val isCurrentConv = tcpMessagingManager.getActiveConversationId() == convId
                        val initialStatus = if (isCurrentConv) MessageStatus.READ else MessageStatus.DELIVERED
                        val entity = ChatMessageEntity(
                            id = packet.messageId, conversationId = convId, senderId = packet.senderId,
                            senderName = packet.senderName, recipientId = packet.recipientId,
                            text = packet.text, isFromMe = false, status = initialStatus,
                            isMeshRelayed = true, isGroup = packet.isGroup, groupName = packet.groupName,
                            isSenderDeveloper = packet.isDeveloper, timestamp = packet.timestamp
                        )
                        database.chatMessageDao().insertMessage(entity)
                        sendAckToSender(packet.senderId, packet.messageId, fromEndpointId)
                        if (isCurrentConv) sendReadAckToSender(packet.senderId, packet.messageId, fromEndpointId)
                    }
                    is VoiceMessagePacket -> {
                        val convId = if (packet.isGroup && !packet.groupId.isNullOrBlank()) packet.groupId else packet.senderId
                        val isCurrentConv = tcpMessagingManager.getActiveConversationId() == convId
                        val initialStatus = if (isCurrentConv) MessageStatus.READ else MessageStatus.DELIVERED
                        val voiceBytes = android.util.Base64.decode(packet.audioBase64, android.util.Base64.DEFAULT)
                        val dir = File(context.filesDir, "received_voices").apply { mkdirs() }
                        val file = File(dir, "voice_${packet.messageId}.m4a")
                        file.writeBytes(voiceBytes)
                        val entity = ChatMessageEntity(
                            id = packet.messageId, conversationId = convId, senderId = packet.senderId,
                            senderName = packet.senderName, recipientId = packet.recipientId,
                            text = "🎤 رسالة صوتية (${packet.durationSeconds} ث)",
                            filePath = file.absolutePath, isVoice = true,
                            audioDurationSeconds = packet.durationSeconds, isFromMe = false,
                            status = initialStatus, isMeshRelayed = true, isGroup = packet.isGroup,
                            groupName = packet.groupName, isSenderDeveloper = packet.isDeveloper,
                            timestamp = packet.timestamp
                        )
                        database.chatMessageDao().insertMessage(entity)
                        sendAckToSender(packet.senderId, packet.messageId, fromEndpointId)
                        if (isCurrentConv) sendReadAckToSender(packet.senderId, packet.messageId, fromEndpointId)
                    }
                    is PhotoMessagePacket -> {
                        if (packet.photoBase64.isNotBlank()) {
                            val convId = if (packet.isGroup && !packet.groupId.isNullOrBlank()) packet.groupId else packet.senderId
                            val isCurrentConv = tcpMessagingManager.getActiveConversationId() == convId
                            val initialStatus = if (isCurrentConv) MessageStatus.READ else MessageStatus.DELIVERED
                            val photoBytes = android.util.Base64.decode(packet.photoBase64, android.util.Base64.DEFAULT)
                            val dir = File(context.filesDir, "received_photos").apply { mkdirs() }
                            val file = File(dir, "photo_${packet.messageId}.jpg")
                            file.writeBytes(photoBytes)
                            val entity = ChatMessageEntity(
                                id = packet.messageId, conversationId = convId, senderId = packet.senderId,
                                senderName = packet.senderName, recipientId = packet.recipientId,
                                text = packet.caption, photoPath = file.absolutePath, isPhoto = true,
                                isFromMe = false, status = initialStatus, isMeshRelayed = true,
                                isGroup = packet.isGroup, groupName = packet.groupName,
                                isSenderDeveloper = packet.isDeveloper, timestamp = packet.timestamp
                            )
                            database.chatMessageDao().insertMessage(entity)
                            sendAckToSender(packet.senderId, packet.messageId, fromEndpointId)
                            if (isCurrentConv) sendReadAckToSender(packet.senderId, packet.messageId, fromEndpointId)
                        } else {
                            val meta = FileMetaInfo(
                                messageId = packet.messageId, senderId = packet.senderId,
                                senderName = packet.senderName, recipientId = packet.recipientId,
                                fileName = packet.fileName, fileSize = packet.fileSize,
                                mimeType = "image/jpeg", caption = packet.caption,
                                isVideo = false, isPhoto = true, isGroup = packet.isGroup,
                                groupId = packet.groupId, groupName = packet.groupName
                            )
                            pendingFileMeta.add(System.currentTimeMillis() to meta)
                        }
                    }
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
                        val meta = FileMetaInfo(
                            messageId = mId, senderId = sId, senderName = sName, recipientId = rId,
                            fileName = fName, fileSize = fSize, mimeType = mMime, caption = fCaption,
                            isVideo = isVid, isPhoto = false, isGroup = isGrp, groupId = grpId, groupName = grpName
                        )
                        pendingFileMeta.add(System.currentTimeMillis() to meta)
                    }
                    is GroupAnnouncePacket -> {
                        database.groupDao().insertOrUpdateGroup(
                            com.example.data.local.GroupEntity(
                                groupId = packet.groupId, groupName = packet.groupName,
                                description = packet.description, createdBy = packet.createdBy,
                                createdAt = packet.createdAt, avatarColorIndex = packet.avatarColorIndex
                            )
                        )
                    }
                    is CallOfferPacket, is CallRingingPacket, is CallAnswerPacket, is CallEndPacket -> {
                        tcpMessagingManager.callSignalListener?.invoke(packet, "p2p-$fromEndpointId")
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
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming Nearby packet: ${e.message}")
            }
        }
    }

    private fun sendAckToSender(senderId: String, messageId: String, endpointId: String) {
        scope.launch {
            val ack = AckDeliveredPacket(messageId = messageId, senderId = userPreferences.deviceId, recipientId = senderId)
            sendPayloadBytes(endpointId, ack.toJson())
        }
    }

    private fun sendReadAckToSender(senderId: String, messageId: String, endpointId: String) {
        scope.launch {
            val ack = AckReadPacket(messageId = messageId, senderId = userPreferences.deviceId, recipientId = senderId)
            sendPayloadBytes(endpointId, ack.toJson())
        }
    }

    private fun finalizeReceivedFile(payload: Payload, meta: FileMetaInfo) {
        scope.launch {
            try {
                val sourceFile = payload.asFile()?.asJavaFile() ?: return@launch
                val subDir = when {
                    meta.isPhoto -> "received_photos"
                    meta.isVideo -> "received_videos"
                    else -> "received_files"
                }
                val targetDir = File(context.filesDir, subDir).apply { mkdirs() }
                val targetFile = File(targetDir, "${meta.messageId}_${meta.fileName}")
                sourceFile.copyTo(targetFile, overwrite = true)
                val convId = if (meta.isGroup && !meta.groupId.isNullOrBlank()) meta.groupId else meta.senderId
                val isCurrentConv = tcpMessagingManager.getActiveConversationId() == convId
                val initialStatus = if (isCurrentConv) MessageStatus.READ else MessageStatus.DELIVERED
                val entity = ChatMessageEntity(
                    id = meta.messageId, conversationId = convId, senderId = meta.senderId,
                    senderName = meta.senderName, recipientId = meta.recipientId, text = meta.caption,
                    photoPath = if (meta.isPhoto) targetFile.absolutePath else null,
                    filePath = if (!meta.isPhoto) targetFile.absolutePath else null,
                    fileName = meta.fileName, fileSize = meta.fileSize,
                    mimeType = meta.mimeType, isVideo = meta.isVideo, isFile = (!meta.isVideo && !meta.isPhoto),
                    isPhoto = meta.isPhoto, isFromMe = false, status = initialStatus, isMeshRelayed = true,
                    isGroup = meta.isGroup, groupName = meta.groupName, timestamp = System.currentTimeMillis()
                )
                database.chatMessageDao().insertMessage(entity)
                val endpoint = deviceIdToEndpoint[meta.senderId]
                if (endpoint != null) {
                    sendAckToSender(meta.senderId, meta.messageId, endpoint)
                    if (isCurrentConv) sendReadAckToSender(meta.senderId, meta.messageId, endpoint)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing received file: ${e.message}")
            }
        }
    }

    fun sendPacketToPeer(targetDeviceId: String, packet: NetworkPacket): Boolean {
        val endpointId = deviceIdToEndpoint[targetDeviceId]
            ?: if (targetDeviceId.startsWith("p2p-")) targetDeviceId.removePrefix("p2p-") else null

        val pairwise = EncryptionManager.getPairwiseManager()
        val plainJson = packet.toJson()

        // استعادة الجلسة محلياً إن لم تكن في الرام
        if (pairwise != null && !pairwise.hasSession(targetDeviceId)) {
            scope.launch {
                val contact = database.contactDao().getContactById(targetDeviceId)
                if (!contact?.publicKeyBase64.isNullOrBlank()) {
                    pairwise.establishSession(targetDeviceId, contact.publicKeyBase64!!)
                }
            }
        }

        // تشفير الحزمة بمفتاح الطرف المستهدف PENC مباشرة دون أي تسريب
        val payloadToSend = if (packet is BeaconPacket || packet is BeaconAckPacket) {
            // Beacons stay readable so discovery keeps working.
            plainJson
        } else {
            // Phase 1.2: fail closed. A payload that cannot be protected for its
            // recipient is not sent at all. This used to fall back to the device
            // storage key, which put the payload under a key the recipient was
            // never meant to use, and told nobody.
            val sealed = pairwise?.encryptForPeer(targetDeviceId, plainJson)
            if (sealed == null) {
                Log.e(TAG, "Refusing to send to $targetDeviceId: no pairwise " +
                    "session, so the payload cannot be protected for it")
                return false
            }
            sealed
        }

        if (endpointId != null && endpointToPeer.containsKey(endpointId)) {
            return sendPayloadBytes(endpointId, payloadToSend)
        }

        return sendMeshRelayPacket(targetDeviceId, payloadToSend)
    }

    fun sendLiveAudioFrame(targetDeviceId: String, buffer: ByteArray, length: Int): Boolean {
        val endpointId = deviceIdToEndpoint[targetDeviceId]
            ?: if (targetDeviceId.startsWith("p2p-")) targetDeviceId.removePrefix("p2p-") else null
        if (endpointId == null || !endpointToPeer.containsKey(endpointId)) return false
        return try {
            val frameBytes = ByteArray(length + 1)
            frameBytes[0] = 0x01.toByte()
            System.arraycopy(buffer, 0, frameBytes, 1, length)
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(frameBytes))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending live audio frame: ${e.message}")
            false
        }
    }

    fun sendFileToPeerNearby(targetDeviceId: String, file: File, metaPacket: NetworkPacket): Boolean {
        val endpointId = deviceIdToEndpoint[targetDeviceId] ?: return false
        return try {
            sendPayloadBytes(endpointId, metaPacket.toJson())
            val filePayload = Payload.fromFile(file)
            connectionsClient.sendPayload(endpointId, filePayload)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending file over Nearby: ${e.message}")
            false
        }
    }

    fun broadcastPacketToAllConnected(packet: NetworkPacket) {
        val json = packet.toJson()
        for (ep in endpointToPeer.keys().toList()) {
            sendPayloadBytes(ep, json)
        }
    }

    private fun sendPayloadBytes(endpointId: String, data: String): Boolean {
        touchConnectionSlot(endpointId)
        return try {
            val payload = Payload.fromBytes(data.toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending payload to $endpointId: ${e.message}")
            false
        }
    }

    fun sendMeshRelayPacket(targetRecipientId: String, rawInnerPacketJson: String): Boolean {
        if (!userPreferences.isMeshModeEnabled) return false
        val meshPacketId = UUID.randomUUID().toString().substring(0, 10)
        markPacketSeen(meshPacketId)

        val encryptedPayload = if (rawInnerPacketJson.startsWith("PENC:") || rawInnerPacketJson.startsWith("ENC:")) {
            rawInnerPacketJson
        } else {
            val pairwise = EncryptionManager.getPairwiseManager()
            if (pairwise != null && !pairwise.hasSession(targetRecipientId)) {
                scope.launch {
                    val contact = database.contactDao().getContactById(targetRecipientId)
                    if (!contact?.publicKeyBase64.isNullOrBlank()) {
                        pairwise.establishSession(targetRecipientId, contact.publicKeyBase64!!)
                    }
                }
            }
            // Phase 1.2: fail closed, as in sendPacketToPeer.
            val sealedInner = pairwise?.encryptForPeer(targetRecipientId, rawInnerPacketJson)
            if (sealedInner == null) {
                Log.e(TAG, "Refusing to relay to $targetRecipientId: no pairwise " +
                    "session, so the packet cannot be protected for it")
                return false
            }
            sealedInner
        }

        val meshPacket = MeshRelayPacket(
            meshPacketId = meshPacketId,
            originSenderId = userPreferences.deviceId,
            originSenderName = userPreferences.displayName,
            targetRecipientId = targetRecipientId,
            hopsRemaining = MAX_HOPS,
            visitedNodes = listOf(userPreferences.deviceId),
            encryptedPayload = encryptedPayload
        )
        scope.launch { broadcastMeshPacketToDirectNeighbors(meshPacket) }
        return true
    }

    fun handleIncomingMeshPacket(packet: MeshRelayPacket, senderIp: String) {
        if (!userPreferences.isMeshModeEnabled) return
        if (isPacketSeen(packet.meshPacketId)) return
        markPacketSeen(packet.meshPacketId)

        // إذا كنت أنا الهدف النهائي، قم بفك تشفير الصندوق الداخلي بمفتاح جلسة الراسل
        if (packet.targetRecipientId == userPreferences.deviceId) {
            try {
                val pairwise = EncryptionManager.getPairwiseManager()
                val decryptedJson = if (packet.encryptedPayload.startsWith("PENC:")) {
                    // Phase 1.2: drop rather than retry under the device key.
                    pairwise?.decryptFromPeer(packet.originSenderId, packet.encryptedPayload)
                } else {
                    EncryptionManager.decrypt(packet.encryptedPayload)
                }

                // Phase 1.2: as above, drop rather than retry under another key.
                val innerPacket = decryptedJson?.let { NetworkPacket.fromJson(it) }
                if (innerPacket != null) {
                    processInnerMeshPacket(innerPacket, packet.originSenderId, packet.originSenderName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt mesh payload: ${e.message}")
            }
        } else {
            // الجهاز وسيط (Relay): تمرير الصندوق المغلق دون لمسه طالما بقي قفزات متبقية
            if (packet.hopsRemaining > 1 &&
                !packet.visitedNodes.contains(userPreferences.deviceId) &&
                packet.visitedNodes.size < MAX_HOPS + 2
            ) {
                _meshStatus.value = _meshStatus.value.copy(
                    relayedPacketsCount = _meshStatus.value.relayedPacketsCount + 1
                )
                val updatedPacket = packet.copy(
                    hopsRemaining = packet.hopsRemaining - 1,
                    visitedNodes = packet.visitedNodes + userPreferences.deviceId
                )
                scope.launch {
                    broadcastMeshPacketToDirectNeighbors(updatedPacket, excludeIp = senderIp)
                }
            }
        }
    }

    private suspend fun broadcastMeshPacketToDirectNeighbors(packet: MeshRelayPacket, excludeIp: String? = null) {
        for (ep in endpointToPeer.keys().toList()) {
            if ("p2p-$ep" != excludeIp) {
                sendPayloadBytes(ep, packet.toJson())
            }
        }
        val contacts = database.contactDao().getAllContactsList()
        val neighbors = contacts.filter {
            it.isOnline && it.ipAddress != excludeIp &&
                    it.deviceId != userPreferences.deviceId && !it.ipAddress.startsWith("p2p")
        }
        for (peer in neighbors) {
            try {
                tcpMessagingManager.sendPacketDirect(peer.ipAddress, peer.tcpPort, packet)
            } catch (_: Exception) {}
        }
    }

    private fun processInnerMeshPacket(packet: NetworkPacket, senderId: String, senderName: String) {
        scope.launch {
            when (packet) {
                is TextMessagePacket -> {
                    if (packet.signatureBase64 != null) {
                        val peer = database.contactDao().getContactById(packet.senderId)
                        if (peer?.publicKeyBase64 != null) {
                            val valid = EncryptionManager.getPairwiseManager()?.verifySignature(
                                peer.publicKeyBase64, packet.text.toByteArray(Charsets.UTF_8), packet.signatureBase64
                            ) ?: false
                            if (!valid) {
                                Log.w(TAG, "Security Alert: Signature mismatch for mesh text from ${packet.senderId}")
                            }
                        }
                    }

                    val entity = ChatMessageEntity(
                        id = packet.messageId, conversationId = senderId, senderId = senderId,
                        senderName = senderName, recipientId = userPreferences.deviceId,
                        text = packet.text, isFromMe = false, status = MessageStatus.DELIVERED,
                        isMeshRelayed = true, timestamp = packet.timestamp
                    )
                    database.chatMessageDao().insertMessage(entity)
                }
                is VoiceMessagePacket -> {
                    val voiceBytes = android.util.Base64.decode(packet.audioBase64, android.util.Base64.DEFAULT)
                    val dir = File(context.filesDir, "received_voices").apply { mkdirs() }
                    val file = File(dir, "voice_${packet.messageId}.m4a")
                    file.writeBytes(voiceBytes)
                    val entity = ChatMessageEntity(
                        id = packet.messageId, conversationId = senderId, senderId = senderId,
                        senderName = senderName, recipientId = userPreferences.deviceId,
                        text = "🎤 رسالة صوتية (${packet.durationSeconds} ث)",
                        filePath = file.absolutePath, isVoice = true,
                        audioDurationSeconds = packet.durationSeconds, isFromMe = false,
                        status = MessageStatus.DELIVERED, isMeshRelayed = true, timestamp = packet.timestamp
                    )
                    database.chatMessageDao().insertMessage(entity)
                }
                else -> {}
            }
        }
    }

    private fun startMeshHeartbeat() {
        meshHeartbeatJob?.cancel()
        meshHeartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    for ((endpointId, peer) in endpointToPeer) {
                        val existing = database.contactDao().getContactById(peer.deviceId)
                        if (existing != null) {
                            // الجهاز ده بقى جهة اتصال محفوظة: الخانة بتاعته
                            // بتتعلّم إنها دايم known عشان ما تتطردش LRU.
                            markConnectionSlotAsKnown(endpointId)
                            // ما نكتبش عنوان p2p- الوهمي فوق عنوان LAN حقيقي،
                            // ولا نخلي نفس الجار نرجعله عنوان p2p- تاني.
                            // neighbour को फिर से p2p-xxx पर ला देता था
                            // The endpoint id goes in its own column and a routable LAN
                            // address is never overwritten with a "p2p-" placeholder.
                            // That placeholder used to replace the only address we had, so
                            // LAN stopped being attempted for peers first seen over MESH.
                            val keepLanIp = existing.ipAddress.takeIf { RouteResolver.isRoutableIp(it) }
                            database.contactDao().insertOrUpdateContact(
                                existing.copy(
                                    isOnline = true, lastSeen = now,
                                    ipAddress = keepLanIp ?: existing.ipAddress,
                                    isMeshPeer = keepLanIp == null,
                                    meshEndpointId = existing.meshEndpointId ?: endpointId
                                )
                            )
                        }
                    }
                    updateMeshPeersState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in mesh heartbeat: ${e.message}")
                }
                delay(2500)
            }
        }
    }

    private fun updateMeshPeersState() {
        scope.launch {
            val contacts = database.contactDao().getAllContactsList()
            val onlinePeers = contacts.filter { it.isOnline }
            val nearbyPeersList = endpointToPeer.values.map {
                MeshPeerInfo(
                    deviceId = it.deviceId, displayName = it.displayName,
                    ipAddress = "p2p-${it.endpointId}", hops = 1,
                    lastSeen = System.currentTimeMillis(), isNearbyConnected = true
                )
            }
            val merged = (nearbyPeersList + onlinePeers.map {
                MeshPeerInfo(
                    deviceId = it.deviceId, displayName = it.displayName,
                    ipAddress = it.ipAddress, hops = 1, lastSeen = it.lastSeen,
                    isNearbyConnected = deviceIdToEndpoint.containsKey(it.deviceId)
                )
            }).distinctBy { it.deviceId }

            _meshStatus.value = _meshStatus.value.copy(
                connectedNodesCount = merged.size,
                activePeers = merged,
                isNearbyP2PActive = startLatch.isRunning
            )
        }
    }

    private fun isPacketSeen(packetId: String): Boolean {
        val timestamp = seenMeshPackets[packetId] ?: return false
        if (System.currentTimeMillis() - timestamp > SEEN_PACKET_TTL_MS) {
            seenMeshPackets.remove(packetId)
            return false
        }
        return true
    }

    private fun markPacketSeen(packetId: String) {
        if (seenMeshPackets.size > MAX_SEEN_CACHE) {
            val now = System.currentTimeMillis()
            val expiredKeys = seenMeshPackets.filter { now - it.value > 5 * 60 * 1000L }.keys
            expiredKeys.forEach { seenMeshPackets.remove(it) }
            if (seenMeshPackets.size > MAX_SEEN_CACHE) {
                val oldestKeys = seenMeshPackets.entries
                    .sortedBy { it.value }
                    .take(seenMeshPackets.size - MAX_SEEN_CACHE)
                    .map { it.key }
                oldestKeys.forEach { seenMeshPackets.remove(it) }
            }
        }
        seenMeshPackets[packetId] = System.currentTimeMillis()
    }
}
