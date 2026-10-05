package com.example

import android.app.Application
import android.util.Log
import com.example.data.call.LanAudioCallManager
import com.example.data.local.ChatDatabase
import com.example.data.local.StaleSendingSweep
import com.example.data.local.MessageStatus
import com.example.data.local.UserPreferences
import com.example.data.network.AudioPlayerHelper
import com.example.data.network.AudioRecorderHelper
import com.example.data.network.NearbyMeshManager
import com.example.data.network.P2PAppUpdateManager
import com.example.data.network.TcpMessagingManager
import com.example.data.network.UdpDiscoveryManager
import com.example.data.security.EncryptionManager
import com.example.service.LanBackgroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LanChatApplication : Application() {

    companion object {
        private const val TAG = "LanChatApplication"
        lateinit var instance: LanChatApplication
            private set
    }

    private val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var database: ChatDatabase
        private set
    lateinit var userPreferences: UserPreferences
        private set
    lateinit var tcpMessagingManager: TcpMessagingManager
        private set
    lateinit var udpDiscoveryManager: UdpDiscoveryManager
        private set
    lateinit var audioCallManager: LanAudioCallManager
        private set
    lateinit var meshManager: NearbyMeshManager
        private set
    lateinit var appShareManager: P2PAppUpdateManager
        private set
    lateinit var audioRecorderHelper: AudioRecorderHelper
        private set
    lateinit var audioPlayerHelper: AudioPlayerHelper
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize Database and Preferences
        database = ChatDatabase.getDatabase(this)
        userPreferences = UserPreferences(this)

        // 2. Initialize Hardware KeyStore and Pairwise ECDH Engine
        EncryptionManager.initializePairwiseManager(this)

        // 3. Initialize Shared Network Engines
        tcpMessagingManager = TcpMessagingManager(this, database, userPreferences)
        udpDiscoveryManager = UdpDiscoveryManager(this, database, userPreferences)
        audioCallManager = LanAudioCallManager(this, userPreferences, tcpMessagingManager)
        meshManager = NearbyMeshManager(this, database, userPreferences, tcpMessagingManager)
        appShareManager = P2PAppUpdateManager(this, database, userPreferences, tcpMessagingManager)
        audioRecorderHelper = AudioRecorderHelper(this)
        audioPlayerHelper = AudioPlayerHelper(this)

        // 4. Restore Saved Identity Keys and Pairwise Sessions
        applicationScope.launch {
            try {
                val savedContacts = database.contactDao().getAllContactsList()
                val pairwise = EncryptionManager.getPairwiseManager()
                if (pairwise != null) {
                    for (contact in savedContacts) {
                        if (!contact.publicKeyBase64.isNullOrBlank()) {
                            pairwise.establishSession(contact.deviceId, contact.publicKeyBase64)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring pairwise sessions: ${e.message}")
            }
        }

        // 4b. Step 1.1 legacy sweep: older builds wrote a failed send back as
        // SENDING, so any such row is stuck claiming to be in flight. Move the
        // stale ones to QUEUED so they tell the truth and get retried.
        applicationScope.launch {
            runCatching { sweepStaleSendingMessages() }
                .onFailure { Log.e(TAG, "Stale SENDING sweep failed: ${it.message}") }
        }

        // 5. Connect Network Listeners Across Engines
        wireEngineListeners()

        // 6. Start Persistent Background Network Services
        udpDiscoveryManager.start()
        tcpMessagingManager.start()
        if (userPreferences.isMeshModeEnabled) {
            try {
                meshManager.startMeshService()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh service: ${e.message}")
            }
        }

        // 7. Launch Persistent Foreground Notification Service
        try {
            LanBackgroundService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LanBackgroundService: ${e.message}")
        }
    }

    /**
     * Step 1.1 legacy sweep. Anything an older build left as SENDING with no send
     * in flight becomes QUEUED, which is the honest state: we are waiting for
     * the peer, and the queue worker will pick it up.
     */
    private suspend fun sweepStaleSendingMessages() {
        val now = System.currentTimeMillis()
        val threshold = now - StaleSendingSweep.STALE_AFTER_MS
        val stale = database.chatMessageDao().getStaleSendingMessages(threshold)
        if (stale.isEmpty()) return
        for (message in stale) {
            database.chatMessageDao().updateMessageStatus(message.id, MessageStatus.QUEUED)
        }
        Log.i(TAG, "Moved ${stale.size} legacy SENDING row(s) to QUEUED")
    }

    private fun wireEngineListeners() {
        udpDiscoveryManager.onPeerDiscoveredListener = { deviceId, ip, port ->
            tcpMessagingManager.retryPendingMessagesForPeer(deviceId, ip, port)
        }

        tcpMessagingManager.meshPacketListener = { packet, senderIp ->
            meshManager.handleIncomingMeshPacket(packet, senderIp)
        }

        tcpMessagingManager.nearbyFallbackSender = { targetDeviceId, packet ->
            meshManager.sendPacketToPeer(targetDeviceId, packet)
        }

        tcpMessagingManager.nearbyFileFallbackSender = { targetDeviceId, file, metaPacket ->
            meshManager.sendFileToPeerNearby(targetDeviceId, file, metaPacket)
        }

        audioCallManager.nearbyAudioSender = { peerId, buffer, length ->
            meshManager.sendLiveAudioFrame(peerId, buffer, length)
        }

        audioCallManager.nearbySignalSender = { peerId, packet ->
            meshManager.sendPacketToPeer(peerId, packet)
        }

        meshManager.liveAudioListener = { bytes, offset, length ->
            audioCallManager.playAudioChunk(bytes, offset, length)
        }
    }
}