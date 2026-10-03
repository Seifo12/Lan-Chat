package com.example.data.network

import android.content.Context
import android.util.Log
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class UdpDiscoveryManager(
    private val context: Context,
    private val database: ChatDatabase,
    private val userPreferences: UserPreferences
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var broadcastJob: Job? = null
    private var receiverJob: Job? = null
    private var cleanupJob: Job? = null
    private var receiverSocket: DatagramSocket? = null
    private val activeBurstCounter = AtomicInteger(0)

    var onPeerDiscoveredListener: ((deviceId: String, ip: String, port: Int) -> Unit)? = null

    companion object {
        const val UDP_PORT = 8888
    private const val DISCOVERED_PEER_TTL_MS = 10 * 60 * 1000L
        private const val TAG = "UdpDiscovery"
        private const val MAX_UDP_PACKET_SIZE = 8192
        private const val MAX_DEVICE_ID_LENGTH = 128
        private const val MAX_DISPLAY_NAME_LENGTH = 200
        private const val FAST_BROADCAST_INTERVAL_MS = 3000L
        private const val RELAXED_BROADCAST_INTERVAL_MS = 30000L // 30 ثانية لترشيد استهلاك البطارية
        private const val SUBNET_CHUNK_SIZE = 8
        private const val SUBNET_SOCKET_TIMEOUT = 350
        private const val SUBNET_DB_WAIT_MS = 1500L
    }

    fun start() {
        NetworkUtils.acquireMulticastLock(context)
        startReceiver()
        startBroadcaster()
        startCleanupTimer()
    }

    fun stop() {
        broadcastJob?.cancel()
        receiverJob?.cancel()
        cleanupJob?.cancel()
        try {
            receiverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        receiverSocket = null
        NetworkUtils.releaseMulticastLock()
    }

    fun triggerImmediateBroadcast() {
        activeBurstCounter.set(5)
        scope.launch {
            sendBeacon()
        }
    }

    fun broadcastGroupAnnounce(group: GroupEntity) {
        scope.launch {
            val announce = GroupAnnouncePacket(
                groupId = group.groupId,
                groupName = group.groupName,
                description = group.description,
                createdBy = group.createdBy,
                avatarColorIndex = group.avatarColorIndex,
                createdAt = group.createdAt
            )
            val jsonBytes = announce.toJson().toByteArray(Charsets.UTF_8)
            val broadcastAddresses = NetworkUtils.getBroadcastAddresses()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                for (address in broadcastAddresses) {
                    val packet = DatagramPacket(jsonBytes, jsonBytes.size, address, UDP_PORT)
                    socket.send(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error announcing group: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startBroadcaster() {
        broadcastJob?.cancel()
        activeBurstCounter.set(6)
        broadcastJob = scope.launch {
            while (isActive) {
                try {
                    sendBeacon()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in broadcast loop: ${e.message}")
                }
                
                val remainingBursts = activeBurstCounter.getAndUpdate { if (it > 0) it - 1 else 0 }
                val interval = if (remainingBursts > 0) FAST_BROADCAST_INTERVAL_MS else RELAXED_BROADCAST_INTERVAL_MS
                delay(interval)
            }
        }
    }

    private fun sendBeacon() {
        val localIp = NetworkUtils.getLocalIpAddress()
        if (localIp == null) {
            Log.w(TAG, "Skipping beacon: no local LAN address available")
            return
        }
        val beacon = BeaconPacket(
            deviceId = userPreferences.deviceId,
            displayName = userPreferences.displayName,
            avatarColorIndex = userPreferences.avatarColorIndex,
            tcpPort = TcpMessagingManager.TCP_PORT,
            isDeveloper = userPreferences.isDeveloper,
            versionCode = userPreferences.appVersionCode,
            versionName = userPreferences.appVersionName,
            isMeshSupported = userPreferences.isMeshModeEnabled,
            publicKeyBase64 = EncryptionManager.getPairwiseManager()?.getMyPublicKeyBase64()
        )
        // حزمة الاستكشاف ترسل JSON مفتوحاً يحمل الهوية والمفتاح العام فقط
        val jsonBytes = beacon.toJson().toByteArray(Charsets.UTF_8)
        val broadcastAddresses = NetworkUtils.getBroadcastAddresses()
        if (broadcastAddresses.isEmpty()) {
            Log.w(TAG, "Skipping beacon: no broadcast address could be resolved for local IP $localIp")
            return
        }
        var sent = 0
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            for (address in broadcastAddresses) {
                try {
                    val packet = DatagramPacket(jsonBytes, jsonBytes.size, address, UDP_PORT)
                    socket.send(packet)
                    sent++
                } catch (e: Exception) {
                    Log.w(TAG, "Beacon send failed to ${address.hostAddress}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening beacon socket: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        Log.d(TAG, "Beacon sent from $localIp to $sent/${broadcastAddresses.size} broadcast address(es)")
    }

    private fun startReceiver() {
        receiverJob?.cancel()
        receiverJob = scope.launch {
            val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
            var bindFailures = 0
            while (isActive) {
                try {
                    if (receiverSocket == null || receiverSocket?.isClosed == true) {
                        receiverSocket = DatagramSocket(null).apply {
                            reuseAddress = true
                            bind(InetSocketAddress(UDP_PORT))
                            broadcast = true
                        }
                        if (bindFailures > 0) {
                            Log.i(TAG, "UDP receiver bound to port $UDP_PORT after $bindFailures failed attempts")
                            bindFailures = 0
                        }
                    }
                    val socket = receiverSocket ?: continue
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    if (packet.length <= 0 || packet.length > MAX_UDP_PACKET_SIZE) continue

                    val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderIp = packet.address.hostAddress ?: continue
                    val networkPacket = NetworkPacket.fromJson(jsonStr) ?: continue

                    when (networkPacket) {
                        is BeaconPacket -> {
                            if (networkPacket.deviceId.isBlank() ||
                                networkPacket.deviceId.length > MAX_DEVICE_ID_LENGTH
                            ) continue
                            if (networkPacket.deviceId != userPreferences.deviceId) {
                                handlePeerDiscovered(
                                    deviceId = networkPacket.deviceId,
                                    name = networkPacket.displayName.take(MAX_DISPLAY_NAME_LENGTH),
                                    avatarColorIndex = networkPacket.avatarColorIndex.coerceIn(0, 100),
                                    ip = senderIp,
                                    port = networkPacket.tcpPort.coerceIn(1, 65535),
                                    isDeveloper = networkPacket.isDeveloper,
                                    versionCode = networkPacket.versionCode.coerceAtLeast(1),
                                    versionName = networkPacket.versionName.take(50),
                                    isMesh = networkPacket.isMeshSupported,
                                    publicKeyBase64 = networkPacket.publicKeyBase64
                                )
                                sendBeaconAck(packet.address, networkPacket.tcpPort.coerceIn(1, 65535))
                            }
                        }
                        is BeaconAckPacket -> {
                            if (networkPacket.deviceId.isBlank() ||
                                networkPacket.deviceId.length > MAX_DEVICE_ID_LENGTH
                            ) continue
                            if (networkPacket.deviceId != userPreferences.deviceId) {
                                handlePeerDiscovered(
                                    deviceId = networkPacket.deviceId,
                                    name = networkPacket.displayName.take(MAX_DISPLAY_NAME_LENGTH),
                                    avatarColorIndex = networkPacket.avatarColorIndex.coerceIn(0, 100),
                                    ip = senderIp,
                                    port = networkPacket.tcpPort.coerceIn(1, 65535),
                                    isDeveloper = networkPacket.isDeveloper,
                                    versionCode = networkPacket.versionCode.coerceAtLeast(1),
                                    versionName = networkPacket.versionName.take(50),
                                    isMesh = networkPacket.isMeshSupported,
                                    publicKeyBase64 = networkPacket.publicKeyBase64
                                )
                            }
                        }
                        is GroupAnnouncePacket -> {
                            if (networkPacket.groupId.isBlank()) continue
                            val group = GroupEntity(
                                groupId = networkPacket.groupId,
                                groupName = networkPacket.groupName.take(MAX_DISPLAY_NAME_LENGTH),
                                description = networkPacket.description.take(1000),
                                createdBy = networkPacket.createdBy,
                                createdAt = networkPacket.createdAt,
                                avatarColorIndex = networkPacket.avatarColorIndex.coerceIn(0, 100)
                            )
                            database.groupDao().insertOrUpdateGroup(group)
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        if (e is java.net.BindException || e is java.net.SocketException) {
                            bindFailures++
                            val msg = e.message ?: e.javaClass.simpleName
                            Log.e(TAG, "Cannot listen on UDP port $UDP_PORT (attempt $bindFailures): $msg")
                            if (bindFailures == 1) {
                                Log.e(TAG, "Another app is most likely holding port $UDP_PORT. Discovery will not work until it is free.")
                            }
                            try { receiverSocket?.close() } catch (_: Exception) {}
                            receiverSocket = null
                        } else {
                            Log.w(TAG, "UDP receive error: ${e.message}")
                        }
                        delay(1000)
                    }
                }
            }
        }
    }

    fun triggerImmediateBeacon() {
        activeBurstCounter.set(3)
        scope.launch(Dispatchers.IO) {
            sendBeacon()
        }
    }

    /**
     * فحص الشبكة الفرعية: يرسل نبضة إلى كل عنوان ثم ينتظر أن يسجل الجهاز الآخر
     * نفسه في قاعدة البيانات المحلية، لأن الاستجابة تصل عبر نفس قناة TCP.
     */
    suspend fun scanSubnet(
        onProgress: (scanned: Int, total: Int) -> Unit,
        onPeerFound: (ContactEntity) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val subnetIps = NetworkUtils.getSubnetIps()
        if (subnetIps.isEmpty()) return@withContext 0
        val total = subnetIps.size
        var scanned = 0
        val found = java.util.Collections.synchronizedList(mutableListOf<ContactEntity>())

        val beacon = BeaconPacket(
            deviceId = userPreferences.deviceId,
            displayName = userPreferences.displayName,
            avatarColorIndex = userPreferences.avatarColorIndex,
            tcpPort = TcpMessagingManager.TCP_PORT,
            isDeveloper = userPreferences.isDeveloper,
            versionCode = userPreferences.appVersionCode,
            versionName = userPreferences.appVersionName,
            isMeshSupported = userPreferences.isMeshModeEnabled,
            publicKeyBase64 = EncryptionManager.getPairwiseManager()?.getMyPublicKeyBase64()
        )
        val jsonBytes = beacon.toJson().toByteArray(Charsets.UTF_8)

        val chunks = subnetIps.chunked(SUBNET_CHUNK_SIZE)
        for (chunk in chunks) {
            coroutineScope {
                chunk.map { targetIp ->
                    async {
                        try {
                            try {
                                val addr = InetAddress.getByName(targetIp)
                                val udpSocket = DatagramSocket()
                                udpSocket.soTimeout = SUBNET_SOCKET_TIMEOUT
                                val packet = DatagramPacket(jsonBytes, jsonBytes.size, addr, UDP_PORT)
                                udpSocket.send(packet)
                                udpSocket.close()
                            } catch (_: Exception) {}

                            val reachable = try {
                                val probe = Socket()
                                probe.connect(InetSocketAddress(targetIp, TcpMessagingManager.TCP_PORT), SUBNET_SOCKET_TIMEOUT)
                                probe.close()
                                true
                            } catch (_: Exception) {
                                false
                            }

                            if (reachable) {
                                // نقرة على منفذ TCP للتأكد من وجود جهاز، ثم إرسال النبضة وانتظار تسجيله
                                val before = database.contactDao().getAllContactsList().size
                                var tcpSocket: Socket? = null
                                try {
                                    tcpSocket = Socket()
                                    tcpSocket.connect(InetSocketAddress(targetIp, TcpMessagingManager.TCP_PORT), SUBNET_SOCKET_TIMEOUT)
                                    val outStream = DataOutputStream(tcpSocket.getOutputStream())
                                    outStream.writeInt(jsonBytes.size)
                                    outStream.write(jsonBytes)
                                    outStream.flush()

                                    // انتظار ظهور الجهاز في قاعدة البيانات بعد استجابته
                                    var waited = 0
                                    while (waited < SUBNET_DB_WAIT_MS) {
                                        delay(100)
                                        waited += 100
                                        val contact = database.contactDao().getAllContactsList()
                                            .find { it.ipAddress == targetIp }
                                        if (contact != null) {
                                            synchronized(found) {
                                                if (found.none { it.deviceId == contact.deviceId }) {
                                                    found.add(contact)
                                                }
                                            }
                                            break
                                        }
                                        if (contact == null && database.contactDao().getAllContactsList().size > before) {
                                            break
                                        }
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    try { tcpSocket?.close() } catch (_: Exception) {}
                                }
                            }
                        } finally {
                            synchronized(this@UdpDiscoveryManager) {
                                scanned++
                                onProgress(scanned, total)
                            }
                        }
                    }
                }.awaitAll()
            }
            delay(25)
        }

        synchronized(found) { found.toList() }.forEach { onPeerFound(it) }
        found.size
    }

    suspend fun connectToManualIpDirectly(
        targetIp: String,
        port: Int = TcpMessagingManager.TCP_PORT
    ): Result<ContactEntity> = withContext(Dispatchers.IO) {
        val trimmedIp = targetIp.trim()
        if (trimmedIp.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("عنوان IP غير صالح"))
        }

        val ipRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        if (!trimmedIp.matches(ipRegex)) {
            return@withContext Result.failure(IllegalArgumentException("صيغة IP غير صالحة"))
        }

        val safePort = port.coerceIn(1, 65535)

        val beacon = BeaconPacket(
            deviceId = userPreferences.deviceId,
            displayName = userPreferences.displayName,
            avatarColorIndex = userPreferences.avatarColorIndex,
            tcpPort = TcpMessagingManager.TCP_PORT,
            isDeveloper = userPreferences.isDeveloper,
            versionCode = userPreferences.appVersionCode,
            versionName = userPreferences.appVersionName,
            isMeshSupported = userPreferences.isMeshModeEnabled,
            publicKeyBase64 = EncryptionManager.getPairwiseManager()?.getMyPublicKeyBase64()
        )
        val jsonBytes = beacon.toJson().toByteArray(Charsets.UTF_8)

        try {
            val addr = InetAddress.getByName(trimmedIp)
            val udpSocket = DatagramSocket()
            val packet = DatagramPacket(jsonBytes, jsonBytes.size, addr, UDP_PORT)
            udpSocket.send(packet)
            udpSocket.close()
        } catch (_: Exception) {}

        var tcpSocket: Socket? = null
        try {
            tcpSocket = Socket()
            tcpSocket.connect(InetSocketAddress(trimmedIp, safePort), 2500)
            val outStream = DataOutputStream(tcpSocket.getOutputStream())
            outStream.writeInt(jsonBytes.size)
            outStream.write(jsonBytes)
            outStream.flush()

            delay(500)
            val resolved = database.contactDao().getAllContactsList().find { it.ipAddress == trimmedIp }
            if (resolved != null) {
                Result.success(resolved)
            } else {
                // The peer answered our beacon, so it is in the transient table with
                // its real device id and public key. Use those instead of a
                // placeholder, otherwise this contact can never hold an encrypted
                // session with it.
                val discovered = runCatching {
                    database.discoveredPeerDao().getAll().find { it.ipAddress == trimmedIp }
                }.getOrNull()
                val tempContact = ContactEntity(
                    deviceId = discovered?.deviceId ?: "dev_${trimmedIp.replace(".", "_")}",
                    displayName = discovered?.displayName ?: "جهاز ($trimmedIp)",
                    ipAddress = trimmedIp,
                    tcpPort = safePort,
                    isOnline = true,
                    isDeveloper = discovered?.isDeveloper ?: false,
                    appVersionCode = discovered?.appVersionCode ?: 1,
                    publicKeyBase64 = discovered?.publicKeyBase64
                )
                Result.success(tempContact)
            }
        } catch (e: Exception) {
            Result.failure(Exception("تعذر الاتصال بـ $trimmedIp:$safePort: ${e.localizedMessage ?: e.message}"))
        } finally {
            try { tcpSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun sendBeaconAck(targetAddress: InetAddress, port: Int) {
        scope.launch {
            var socket: DatagramSocket? = null
            try {
                val ack = BeaconAckPacket(
                    deviceId = userPreferences.deviceId,
                    displayName = userPreferences.displayName,
                    avatarColorIndex = userPreferences.avatarColorIndex,
                    tcpPort = TcpMessagingManager.TCP_PORT,
                    isDeveloper = userPreferences.isDeveloper,
                    versionCode = userPreferences.appVersionCode,
                    versionName = userPreferences.appVersionName,
                    isMeshSupported = userPreferences.isMeshModeEnabled,
                    publicKeyBase64 = EncryptionManager.getPairwiseManager()?.getMyPublicKeyBase64()
                )
                val jsonBytes = ack.toJson().toByteArray(Charsets.UTF_8)
                socket = DatagramSocket()
                val packet = DatagramPacket(jsonBytes, jsonBytes.size, targetAddress, UDP_PORT)
                socket.send(packet)
            } catch (e: Exception) {
                Log.d(TAG, "Notice sending beacon ACK: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * معالجة واكتشاف الأجهزة مع التجديد الفوري التلقائي لجلسة التشفير في حال تغير المفتاح العام
     */
    internal suspend fun handlePeerDiscovered(
        deviceId: String,
        name: String,
        avatarColorIndex: Int,
        ip: String,
        port: Int,
        isDeveloper: Boolean = false,
        versionCode: Int = 1,
        versionName: String = "1.0.0",
        isMesh: Boolean = false,
        publicKeyBase64: String? = null
    ): ContactEntity {
        val existing = database.contactDao().getContactById(deviceId)
        val newKey = publicKeyBase64 ?: existing?.publicKeyBase64

        // إذا وصل مفتاح عام جديد لنفس المعرف العتادي (Clear Data)، نجدد الجلسة فوراً في الكاش
        if (publicKeyBase64 != null) {
            val isNewKey = existing == null || existing.publicKeyBase64 != publicKeyBase64
            if (isNewKey) {
                Log.i(TAG, "Peer $deviceId rotated its public key. Rebuilding pairwise session instantly.")
                EncryptionManager.getPairwiseManager()?.establishSession(deviceId, publicKeyBase64)
            }
        }

        if (existing == null) {
            // جهاز جديد على نفس الشبكة: سجّله كقريب فقط، مش كجهة اتصال.
            // كده محل عام فيه 1000 شخص ما بيظهرش في المحادثات غير اللي المستخدم اختاره.
            database.discoveredPeerDao().upsert(
                com.example.data.local.DiscoveredPeerEntity(
                    deviceId = deviceId,
                    displayName = name,
                    endpointId = "",
                    ipAddress = ip,
                    publicKeyBase64 = publicKeyBase64,
                    avatarColorIndex = avatarColorIndex,
                    isDeveloper = isDeveloper,
                    appVersionCode = versionCode,
                    transportIsLan = true
                )
            )
            onPeerDiscoveredListener?.invoke(deviceId, ip, port)
            return ContactEntity(
                deviceId = deviceId,
                displayName = name,
                ipAddress = ip,
                tcpPort = port,
                avatarColorIndex = avatarColorIndex,
                lastSeen = System.currentTimeMillis(),
                isOnline = true,
                isDeveloper = isDeveloper,
                appVersionCode = versionCode,
                appVersionName = versionName,
                isMeshPeer = false,
                publicKeyBase64 = newKey
            )
        }

        val contact = ContactEntity(
            deviceId = deviceId,
            displayName = if (!existing.customNickname.isNullOrBlank()) existing.customNickname!! else name,
            ipAddress = ip,
            tcpPort = port,
            avatarColorIndex = avatarColorIndex,
            avatarPath = existing.avatarPath,
            lastSeen = System.currentTimeMillis(),
            isOnline = true,
            customNickname = existing.customNickname,
            isDeveloper = isDeveloper,
            appVersionCode = versionCode,
            appVersionName = versionName,
            isMeshPeer = false,
            publicKeyBase64 = newKey
        )
        database.contactDao().insertOrUpdateContact(contact)
        onPeerDiscoveredListener?.invoke(deviceId, ip, port)
        return contact
    }

    /**
     * Asks only the peers already in the chat list to re-announce themselves.
     *
     * This replaces the old cold-start behaviour of sweeping the entire subnet:
     * beacons to every address on the network are slow, and with unknown peers no
     * longer promoted they could not produce a contact anyway. Probing people the
     * user actually has conversations with is both cheaper and useful, because it
     * is what refreshes their online state.
     */
    suspend fun requestAckFromKnownContacts() {
        val known = runCatching { database.contactDao().getAllContactsList() }.getOrNull()
            ?: return
        for (contact in known) {
            val host = contact.ipAddress
            if (host.isBlank() || !RouteResolver.isRoutableIp(host)) continue
            val target = runCatching {
                java.net.InetAddress.getByName(host)
            }.getOrNull() ?: continue
            val beacon = BeaconPacket(
                deviceId = userPreferences.deviceId,
                displayName = userPreferences.displayName,
                avatarColorIndex = userPreferences.avatarColorIndex,
                tcpPort = TcpMessagingManager.TCP_PORT,
                isDeveloper = userPreferences.isDeveloper,
                versionCode = userPreferences.appVersionCode,
                versionName = userPreferences.appVersionName,
                isMeshSupported = userPreferences.isMeshModeEnabled,
                publicKeyBase64 = EncryptionManager.getPairwiseManager()?.getMyPublicKeyBase64()
            )
            val jsonBytes = beacon.toJson().toByteArray(Charsets.UTF_8)
            runCatching {
                java.net.DatagramSocket().use { socket ->
                    socket.send(
                        java.net.DatagramPacket(jsonBytes, jsonBytes.size, target, UDP_PORT)
                    )
                }
            }.onFailure { Log.d(TAG, "Ack request to $host failed: ${it.message}") }
        }
    }

    private fun startCleanupTimer() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isActive) {
                delay(15000)
                try {
                    val now = System.currentTimeMillis()
                    database.contactDao().markInactiveContactsOffline(now, 45000L)
                    // Without this the transient table grows forever in a busy room.
                    database.discoveredPeerDao().pruneOlderThan(now - DISCOVERED_PEER_TTL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating inactive contacts: ${e.message}")
                }
            }
        }
    }
}