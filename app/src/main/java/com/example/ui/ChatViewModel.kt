package com.example.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.example.LanChatApplication
import com.example.data.call.ActiveCallInfo
import com.example.data.call.LanAudioCallManager
import com.example.data.local.ChatDatabase
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.data.local.MessageStatus
import com.example.data.security.ContactTrust
import com.example.data.local.UserPreferences
import com.example.data.network.AppShareState
import com.example.data.network.AudioPlayerHelper
import com.example.data.network.AudioRecorderHelper
import com.example.data.network.AvailableUpdateInfo
import com.example.data.network.FileUtils
import com.example.data.network.ImageUtils
import com.example.data.network.MeshStatus
import com.example.data.network.NearbyMeshManager
import com.example.data.network.NetworkPermissionHelper
import com.example.data.network.NetworkUtils
import com.example.data.network.P2PAppUpdateManager
import com.example.data.network.PlaybackState
import com.example.data.network.RecordingState
import com.example.data.network.TcpMessagingManager
import com.example.data.network.TransferProgress
import com.example.data.network.UdpDiscoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UserProfileState(
    val deviceId: String = "",
    val displayName: String = "",
    val avatarPath: String? = null,
    val avatarColorIndex: Int = 0,
    val isSetupCompleted: Boolean = false,
    val isHapticEnabled: Boolean = true,
    val isSoundEnabled: Boolean = true,
    val isDeveloper: Boolean = false,
    val themeMode: String = "DARK",
    val isMeshModeEnabled: Boolean = true
)

data class ConversationUiItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val isGroup: Boolean,
    val isOnline: Boolean,
    val isMeshPeer: Boolean,
    val avatarPath: String?,
    val avatarColorIndex: Int,
    val lastMessage: ChatMessageEntity?,
    val unreadCount: Int,
    val lastTimestamp: Long,
    val lastMessageWasMesh: Boolean = false,
    val lastMessageHops: Int = 0,
    val contact: ContactEntity? = null,
    val group: GroupEntity? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as? LanChatApplication ?: LanChatApplication.instance
    val userPrefs: UserPreferences = app.userPreferences
    private val database: ChatDatabase = app.database
    val tcpMessaging: TcpMessagingManager = app.tcpMessagingManager
    val udpDiscovery: UdpDiscoveryManager = app.udpDiscoveryManager
    val audioCallManager: LanAudioCallManager = app.audioCallManager
    val meshManager: NearbyMeshManager = app.meshManager
    val appShareManager: P2PAppUpdateManager = app.appShareManager
    val audioRecorder: AudioRecorderHelper = app.audioRecorderHelper
    val audioPlayer: AudioPlayerHelper = app.audioPlayerHelper

    val currentCall: StateFlow<ActiveCallInfo?> = audioCallManager.currentCall
    val meshStatus: StateFlow<MeshStatus> = meshManager.meshStatus
    val appShareState: StateFlow<AppShareState> = appShareManager.shareState
    val availableUpdates: StateFlow<List<AvailableUpdateInfo>> = appShareManager.availableUpdates
    val recordingState: StateFlow<RecordingState> = audioRecorder.recordingState
    val playbackState: StateFlow<PlaybackState> = audioPlayer.playbackState
    val activeTransfers: StateFlow<Map<String, TransferProgress>> = tcpMessaging.activeTransfers

    private val _userProfile = MutableStateFlow(
        UserProfileState(
            deviceId = userPrefs.deviceId,
            displayName = userPrefs.displayName,
            avatarPath = userPrefs.avatarPath,
            avatarColorIndex = userPrefs.avatarColorIndex,
            isSetupCompleted = userPrefs.isSetupCompleted,
            isHapticEnabled = userPrefs.isHapticEnabled,
            isSoundEnabled = userPrefs.isSoundEnabled,
            isDeveloper = userPrefs.isDeveloper,
            themeMode = userPrefs.themeMode,
            isMeshModeEnabled = userPrefs.isMeshModeEnabled
        )
    )
    val userProfile: StateFlow<UserProfileState> = _userProfile.asStateFlow()

    val contacts: StateFlow<List<ContactEntity>> = database.contactDao().getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<GroupEntity>> = database.groupDao().getAllGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMessagesForConversations: StateFlow<List<ChatMessageEntity>> = database.chatMessageDao().getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<ConversationUiItem>> = combine(
        contacts,
        groups,
        allMessagesForConversations
    ) { contactList, groupList, allMessages ->
        val messagesByConv = allMessages.groupBy { it.conversationId }
        val items = mutableListOf<ConversationUiItem>()

        for (contact in contactList) {
            val msgs = messagesByConv[contact.deviceId] ?: emptyList()
            val lastMsg = msgs.firstOrNull()
            val unread = msgs.count { !it.isFromMe && it.status != MessageStatus.READ }
            val name = contact.customNickname ?: contact.displayName
            val timestamp = lastMsg?.timestamp ?: contact.lastSeen
            items.add(
                ConversationUiItem(
                    id = contact.deviceId,
                    title = name,
                    subtitle = when {
                        lastMsg != null -> when {
                            lastMsg.isVoice -> "🎤 رسالة صوتية"
                            lastMsg.isPhoto -> "📷 صورة"
                            lastMsg.isVideo -> "🎬 مقطع فيديو"
                            lastMsg.isFile -> "📎 ${lastMsg.fileName ?: "ملف"}"
                            else -> lastMsg.text
                        }
                        contact.isOnline -> if (contact.isMeshPeer) "متصل عبر P2P Mesh 🟢" else "متاح الآن على الشبكة المحلية"
                        else -> "آخر ظهور: جهاز غير متصل"
                    },
                    isGroup = false,
                    isOnline = contact.isOnline,
                    isMeshPeer = contact.isMeshPeer,
                    avatarPath = contact.avatarPath,
                    avatarColorIndex = contact.avatarColorIndex,
                    lastMessage = lastMsg,
                    unreadCount = unread,
                    lastTimestamp = lastMsg?.receivedAt ?: contact.lastSeen,
                    lastMessageWasMesh = lastMsg?.isFromMe == true && lastMsg.isMeshRelayed,
                    lastMessageHops = if (lastMsg?.isFromMe == true) lastMsg.meshHops else 0,
                    contact = contact,
                    group = null
                )
            )
        }

        for (group in groupList) {
            val msgs = messagesByConv[group.groupId] ?: emptyList()
            val lastMsg = msgs.firstOrNull()
            val unread = msgs.count { !it.isFromMe && it.status != MessageStatus.READ }
            val timestamp = lastMsg?.timestamp ?: group.createdAt
            items.add(
                ConversationUiItem(
                    id = group.groupId,
                    title = group.groupName,
                    subtitle = when {
                        lastMsg != null -> {
                            val sender = if (lastMsg.isFromMe) "أنت" else lastMsg.senderName
                            val content = when {
                                lastMsg.isVoice -> "🎤 رسالة صوتية"
                                lastMsg.isPhoto -> "📷 صورة"
                                lastMsg.isVideo -> "🎬 فيديو"
                                lastMsg.isFile -> "📎 ${lastMsg.fileName ?: "ملف"}"
                                else -> lastMsg.text
                            }
                            "$sender: $content"
                        }
                        group.description.isNotBlank() -> group.description
                        else -> "مجموعة محلية بدون إنترنت"
                    },
                    isGroup = true,
                    isOnline = true,
                    isMeshPeer = false,
                    avatarPath = null,
                    avatarColorIndex = group.avatarColorIndex,
                    lastMessage = lastMsg,
                    unreadCount = unread,
                    lastTimestamp = lastMsg?.receivedAt ?: group.createdAt,
                    contact = null,
                    group = group
                )
            )
        }

        items.sortedWith(
            compareByDescending<ConversationUiItem> { it.lastMessage != null }
                .thenByDescending { it.unreadCount > 0 }
                .thenByDescending { it.lastTimestamp }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeContact = MutableStateFlow<ContactEntity?>(null)
    val activeContact: StateFlow<ContactEntity?> = _activeContact.asStateFlow()

    private val _activeGroup = MutableStateFlow<GroupEntity?>(null)
    val activeGroup: StateFlow<GroupEntity?> = _activeGroup.asStateFlow()

    private val _showSettingsScreen = MutableStateFlow(false)
    val showSettingsScreen: StateFlow<Boolean> = _showSettingsScreen.asStateFlow()

    /**
     * Phase 1.7b: the contact whose security screen is open, or null when none is.
     * Held as a [ContactTrust] rather than a device id so the screen renders from a
     * single source of truth instead of re-deriving the state itself.
     */
    private val _contactSecurity = MutableStateFlow<ContactTrust?>(null)
    val contactSecurity: StateFlow<ContactTrust?> = _contactSecurity.asStateFlow()

    /** How many of that contact's messages are failed and waiting to be resent. */
    private val _failedMessageCount = MutableStateFlow(0)
    val failedMessageCount: StateFlow<Int> = _failedMessageCount.asStateFlow()

    private val _localIpAddress = MutableStateFlow<String?>(null)
    val localIpAddress: StateFlow<String?> = _localIpAddress.asStateFlow()

    private val _networkDiagnostic = MutableStateFlow(
        NetworkPermissionHelper.checkNetworkAndPermissions(application)
    )
    val networkDiagnostic: StateFlow<com.example.data.network.NetworkDiagnosticState> = _networkDiagnostic.asStateFlow()

    private val _isSubnetScanning = MutableStateFlow(false)
    val isSubnetScanning: StateFlow<Boolean> = _isSubnetScanning.asStateFlow()

    private val _subnetScanProgress = MutableStateFlow(Pair(0, 0))
    val subnetScanProgress: StateFlow<Pair<Int, Int>> = _subnetScanProgress.asStateFlow()

    private val _scanResultNotice = MutableStateFlow<String?>(null)
    val scanResultNotice: StateFlow<String?> = _scanResultNotice.asStateFlow()

    private val _networkLogs = MutableStateFlow<List<String>>(
        listOf(
            "🟢 تم تشغيل محرك الشبكة المحلية بنجاح",
            "🟡 خادم TCP يستمع على المنفذ 9999",
            "🔵 مستشعر UDP يستمع على المنفذ 8888"
        )
    )
    val networkLogs: StateFlow<List<String>> = _networkLogs.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeConversationMessages: StateFlow<List<ChatMessageEntity>> = combine(
        _activeContact,
        _activeGroup
    ) { contact, group ->
        Pair(contact, group)
    }.flatMapLatest { (contact, group) ->
        when {
            contact != null -> database.chatMessageDao().getMessagesForConversation(contact.deviceId)
            group != null -> database.chatMessageDao().getMessagesForConversation(group.groupId)
            else -> flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var autoDiscoveryJob: Job? = null

    init {
        updateNetworkInfo()
        startBackgroundAutoDiscovery()
    }

    fun openConversationById(conversationId: String) {
        viewModelScope.launch {
            val contact = database.contactDao().getContactById(conversationId)
            if (contact != null) {
                selectContact(contact)
                return@launch
            }
            val group = database.groupDao().getGroupById(conversationId)
            if (group != null) {
                selectGroup(group)
            }
        }
    }

    private fun startBackgroundAutoDiscovery() {
        autoDiscoveryJob?.cancel()
        autoDiscoveryJob = viewModelScope.launch(Dispatchers.IO) {
            udpDiscovery.triggerImmediateBeacon()
            delay(2000)
            val myIp = _localIpAddress.value
            //contacts.value على StateFlow من نوع WhileSubscribed يعيد القائمة الفارغة
            //قبل وجود أي مشترك، لذلك نقرأ القاعدة مباشرة
            // No automatic full-subnet scan here. A scan beacons up to 254 hosts on
            // every cold start, which is both slow and pointless now that unknown
            // peers are not promoted to contacts. Scanning is an explicit action.
            val currentPeers = database.contactDao().getAllContactsList()
            if (currentPeers.isNotEmpty() && currentPeers.none { it.isOnline } &&
                myIp != null && myIp != "127.0.0.1" && !_isSubnetScanning.value
            ) {
                // Only nudge peers we already talk to, instead of sweeping the room.
                udpDiscovery.requestAckFromKnownContacts()
            }
            while (isActive) {
                delay(30_000)
                if (_networkDiagnostic.value.isNetworkConnected) {
                    udpDiscovery.triggerImmediateBeacon()
                }
            }
        }
    }

    fun updateNetworkInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        _localIpAddress.value = ip
        _networkDiagnostic.value = NetworkPermissionHelper.checkNetworkAndPermissions(getApplication())
    }

    fun refreshNetworkAndPermissions() {
        updateNetworkInfo()
        triggerBroadcastPing()
    }

    fun triggerBroadcastPing() {
        udpDiscovery.triggerImmediateBeacon()
        addNetworkLog("🔵 تم إرسال نبضة استكشاف جديدة عبر UDP Broadcast")
    }

    fun startSubnetScan() {
        if (_isSubnetScanning.value) return
        val myIp = _localIpAddress.value
        if (myIp == null || myIp == "127.0.0.1") {
            _scanResultNotice.value = "لا يوجد اتصال بشبكة محلية مشتركة (Wi-Fi أو Hotspot)"
            return
        }
        viewModelScope.launch {
            _isSubnetScanning.value = true
            _scanResultNotice.value = null
            addNetworkLog("🔍 بدء فحص شامل للشبكة الفرعية (${NetworkUtils.getSubnetPrefix() ?: myIp})...")
            val foundCount = udpDiscovery.scanSubnet(
                onProgress = { current, total ->
                    _subnetScanProgress.value = Pair(current, total)
                },
                onPeerFound = { peer ->
                    addNetworkLog("✅ تم الاتصال بجهاز جديد: ${peer.displayName} (${peer.ipAddress})")
                }
            )
            _isSubnetScanning.value = false
            _scanResultNotice.value = if (foundCount > 0) {
                "اكتمل الفحص: تم العثور على $foundCount جهاز متصل بنجاح!"
            } else {
                "اكتمل الفحص: لم يتم العثور على أجهزة جديدة حالياً."
            }
            addNetworkLog("🏁 اكتمل الفحص الشامل للشبكة: الأجهزة الجديدة المتصلة ($foundCount)")
        }
    }

    fun connectToManualIp(targetIp: String, onResult: (success: Boolean, message: String) -> Unit) {
        val trimmed = targetIp.trim()
        if (trimmed.isBlank() || !trimmed.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
            onResult(false, "صيغة عنوان IP غير صالحة. مثال: 192.168.43.15")
            return
        }
        viewModelScope.launch {
            addNetworkLog("🔍 محاولة الاتصال المباشر بالعنوان: $trimmed:9999...")
            val result = udpDiscovery.connectToManualIpDirectly(trimmed)
            if (result.isSuccess) {
                val contact = result.getOrNull()
                addNetworkLog("✅ تم الاتصال المباشر بنجاح بالجهاز: ${contact?.displayName} ($trimmed)")
                onResult(true, "تم الاتصال بنجاح! تم إضافة الجهاز إلى قائمة المحادثات.")
            } else {
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "فشل الاتصال"
                addNetworkLog("❌ فشل الاتصال المباشر بـ $trimmed: $errorMsg")
                onResult(false, errorMsg)
            }
        }
    }

    fun pingPeer(ip: String, onResult: (latencyMs: Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val latency = NetworkUtils.pingHostLatency(ip)
            withContext(Dispatchers.Main) {
                if (latency > 0) {
                    addNetworkLog("⚡ فحص Ping لـ $ip: $latency ms")
                } else {
                    addNetworkLog("🚫 فحص Ping لـ $ip: لا استجابة")
                }
                onResult(latency)
            }
        }
    }

    fun addNetworkLog(log: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _networkLogs.value = (listOf("[$time] $log") + _networkLogs.value).take(50)
    }

    fun clearNetworkLogs() {
        _networkLogs.value = emptyList()
    }

    fun saveUserProfile(name: String, avatarColorIndex: Int) {
        userPrefs.displayName = name
        userPrefs.avatarColorIndex = avatarColorIndex
        userPrefs.isSetupCompleted = true
        _userProfile.value = _userProfile.value.copy(
            displayName = name,
            avatarColorIndex = avatarColorIndex,
            isSetupCompleted = true
        )
    }

    fun updateUserProfile(name: String, avatarPath: String?, colorIndex: Int, isDev: Boolean) {
        userPrefs.displayName = name
        userPrefs.avatarPath = avatarPath
        userPrefs.avatarColorIndex = colorIndex
        userPrefs.isDeveloper = isDev
        userPrefs.isSetupCompleted = true
        _userProfile.value = _userProfile.value.copy(
            displayName = name,
            avatarPath = avatarPath,
            avatarColorIndex = colorIndex,
            isDeveloper = isDev,
            isSetupCompleted = true
        )
    }

    fun updateContactCustomization(deviceId: String, nickname: String?, avatarPath: String?) {
        viewModelScope.launch {
            database.contactDao().updateContactProfile(deviceId, nickname, avatarPath)
        }
    }

    fun deleteContactLocally(deviceId: String) {
        viewModelScope.launch {
            database.contactDao().deleteContact(deviceId)
            database.chatMessageDao().clearConversation(deviceId)
        }
    }

    fun clearConversationLocally(conversationId: String) {
        viewModelScope.launch {
            database.chatMessageDao().clearConversation(conversationId)
        }
    }

    fun refreshDiscovery() {
        udpDiscovery.triggerImmediateBroadcast()
        if (userPrefs.isMeshModeEnabled) {
            // A real restart, not startMeshService(): that one early-returned on
            // a stale running flag, which is why pull-to-refresh did nothing.
            meshManager.forceRestartMesh()
        }
    }

    fun toggleHaptic(enabled: Boolean) {
        userPrefs.isHapticEnabled = enabled
        _userProfile.value = _userProfile.value.copy(isHapticEnabled = enabled)
    }

    fun toggleSound(enabled: Boolean) {
        userPrefs.isSoundEnabled = enabled
        _userProfile.value = _userProfile.value.copy(isSoundEnabled = enabled)
    }

    fun setThemeMode(mode: String) {
        userPrefs.themeMode = mode
        _userProfile.value = _userProfile.value.copy(themeMode = mode)
    }

    fun toggleMeshMode(enabled: Boolean) {
        meshManager.toggleMeshMode(enabled)
        _userProfile.value = _userProfile.value.copy(isMeshModeEnabled = enabled)
    }

    fun toggleDeveloperMode(enabled: Boolean) {
        userPrefs.isDeveloper = enabled
        _userProfile.value = _userProfile.value.copy(isDeveloper = enabled)
    }

    fun setShowSettingsScreen(show: Boolean) {
        _showSettingsScreen.value = show
    }

    // ----- Phase 1.7b: contact security -----

    /** Opens the security screen for a contact, or closes it when given null. */
    fun openContactSecurity(deviceId: String?) {
        if (deviceId == null) {
            _contactSecurity.value = null
            _failedMessageCount.value = 0
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val contact = database.contactDao().getContactById(deviceId) ?: return@launch
            refreshContactSecurity(contact)
        }
    }

    fun closeContactSecurity() {
        _contactSecurity.value = null
        _failedMessageCount.value = 0
    }

    private suspend fun refreshContactSecurity(contact: ContactEntity) {
        _contactSecurity.value = ContactTrust(
            deviceId = contact.deviceId,
            displayName = contact.customNickname?.takeIf { it.isNotBlank() }
                ?: contact.displayName,
            pinnedPublicKey = contact.pinnedPublicKey,
            observedPublicKey = contact.publicKeyBase64,
            verifiedAt = contact.verifiedAt,
        )
        _failedMessageCount.value =
            database.chatMessageDao().getFailedMessagesIn(contact.deviceId).size
    }

    /**
     * The user compared the safety code and says so. This only records that the
     * comparison happened; it never moves the pin.
     */
    fun markContactVerified(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            database.contactDao().markVerified(deviceId, System.currentTimeMillis())
            database.contactDao().getContactById(deviceId)?.let { refreshContactSecurity(it) }
        }
    }

    /**
     * The user accepted a different key. This is the only way out of a key change
     * and it is never called automatically, which is why it lives here as an
     * explicit action rather than inside the key update path.
     */
    fun acceptChangedKey(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = database.contactDao().getContactById(deviceId) ?: return@launch
            val observed = contact.publicKeyBase64 ?: return@launch
            database.contactDao().acceptNewKey(deviceId, observed)
            database.contactDao().getContactById(deviceId)?.let { refreshContactSecurity(it) }
        }
    }

    /** Puts every failed message for this contact back on the wire, in order. */
    fun resendFailedMessages(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val failed = database.chatMessageDao().getFailedMessagesIn(deviceId)
            for (message in failed) {
                tcpMessaging.retryMessage(message)
            }
            database.contactDao().getContactById(deviceId)?.let { refreshContactSecurity(it) }
        }
    }

    fun buildMyQrPayload(): String? {
        val myKey = com.example.data.security.EncryptionManager.getPairwiseManager()
            ?.getMyPublicKeyBase64() ?: return null
        val card = com.example.data.network.IdentityCard(
            deviceId = userPrefs.deviceId,
            displayName = userPrefs.displayName,
            publicKeyBase64 = myKey,
            appVersionCode = userPrefs.appVersionCode
        )
        return com.example.data.network.IdentityCardCodec.encode(
            card,
            com.example.data.security.EncryptionManager.getPairwiseManager()
        )
    }

    /**
     * مسح QR جهاز تاني: بيتحقق من التوقيع، يحفظه كجهة اتصال، ويبني جلسة تشفير.
     * الجهاز المكتشف بيترقى لجهة اتصال هنا، لأن المستخدم اختاره صريح.
     */
    fun addContactFromQr(raw: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val verifier = com.example.data.security.EncryptionManager.getPairwiseManager()
            when (val decoded = com.example.data.network.IdentityCardCodec.decode(raw, verifier)) {
                is com.example.data.network.IdentityCardResult.Invalid -> {
                    onResult(false, decoded.reason)
                }
                is com.example.data.network.IdentityCardResult.Success -> {
                    val card = decoded.card
                    if (card.deviceId == userPrefs.deviceId) {
                        onResult(false, "ده جهازك أنت")
                        return@launch
                    }
                    val pairwise = com.example.data.security.EncryptionManager.getPairwiseManager()
                    try {
                        pairwise?.establishSession(card.deviceId, card.publicKeyBase64)
                    } catch (e: Exception) {
                        onResult(false, "فشل إنشاء جلسة التشفير")
                        return@launch
                    }
                    val existing = database.contactDao().getContactById(card.deviceId)
                    val lanIp = existing?.ipAddress?.takeIf { isRoutableAddress(it) }
                    database.contactDao().insertOrUpdateContact(
                        ContactEntity(
                            deviceId = card.deviceId,
                            displayName = existing?.customNickname ?: card.displayName,
                            ipAddress = lanIp ?: "qr-${card.deviceId}",
                            tcpPort = 9999,
                            avatarPath = existing?.avatarPath,
                            avatarColorIndex = existing?.avatarColorIndex ?: 0,
                            lastSeen = System.currentTimeMillis(),
                            isOnline = true,
                            customNickname = existing?.customNickname,
                            isDeveloper = existing?.isDeveloper ?: false,
                            appVersionCode = card.appVersionCode,
                            appVersionName = existing?.appVersionName ?: "2.0.0",
                            isMeshPeer = lanIp == null,
                            publicKeyBase64 = card.publicKeyBase64
                        )
                    )
                    database.discoveredPeerDao().delete(card.deviceId)
                    _networkDiagnostic.value =
                        com.example.data.network.NetworkPermissionHelper.checkNetworkAndPermissions(getApplication())
                    onResult(true, "تمت إضافة ${card.displayName}")
                }
            }
        }
    }

    val discoveredPeers: StateFlow<List<com.example.data.local.DiscoveredPeerEntity>> =
        database.discoveredPeerDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun isRoutableAddress(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.startsWith("p2p-") || value.startsWith("qr-")) return false
        return value.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
    }

    /**
     * ترقية جهاز مكتشف لجهة اتصال: المستخدم اختاره صريحاً من شاشة "قريبين".
     * بعدها بيبان في المحادثات وبيقدر يستقبل رسائل زي أي جهة اتصال.
     */
    fun startChatWithDiscoveredPeer(deviceId: String) {
        viewModelScope.launch {
            val peer = database.discoveredPeerDao().getById(deviceId) ?: return@launch
            val existing = database.contactDao().getContactById(deviceId)
            if (existing == null) {
                peer.publicKeyBase64?.let { key ->
                    try {
                        com.example.data.security.EncryptionManager.getPairwiseManager()
                            ?.establishSession(deviceId, key)
                    } catch (e: Exception) {
                        Log.w("ChatViewModel", "pairwise setup failed: ${e.message}")
                    }
                }
                val ip = peer.ipAddress ?: "p2p-${peer.endpointId}"
                val lan = isRoutableAddress(peer.ipAddress)
                database.contactDao().insertOrUpdateContact(
                    ContactEntity(
                        deviceId = peer.deviceId,
                        displayName = peer.displayName,
                        ipAddress = ip,
                        tcpPort = if (lan) 9999 else 9999,
                        avatarColorIndex = peer.avatarColorIndex,
                        lastSeen = System.currentTimeMillis(),
                        isOnline = true,
                        isDeveloper = peer.isDeveloper,
                        appVersionCode = peer.appVersionCode,
                        appVersionName = "2.0.0",
                        isMeshPeer = !lan,
                        publicKeyBase64 = peer.publicKeyBase64
                    )
                )
            }
            database.discoveredPeerDao().delete(deviceId)
            val contact = database.contactDao().getContactById(deviceId) ?: return@launch
            openContactChat(contact)
        }
    }

    fun openSettings() = setShowSettingsScreen(true)
    fun closeSettings() = setShowSettingsScreen(false)

    fun selectContact(contact: ContactEntity?) {
        if (contact == null) {
            closeChat()
        } else {
            openContactChat(contact)
        }
    }

    fun selectGroup(group: GroupEntity?) {
        if (group == null) {
            closeChat()
        } else {
            openGroupChat(group)
        }
    }

    fun openContactChat(contact: ContactEntity) {
        _activeGroup.value = null
        _activeContact.value = contact
        tcpMessaging.setActiveConversation(contact.deviceId)
    }

    fun openGroupChat(group: GroupEntity) {
        _activeContact.value = null
        _activeGroup.value = group
        tcpMessaging.setActiveConversation(group.groupId)
    }

    fun closeChat() {
        _activeContact.value = null
        _activeGroup.value = null
        tcpMessaging.setActiveConversation(null)
        audioPlayer.stop()
    }

    fun createGroup(name: String, description: String = "", avatarColorIndex: Int = 0) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val groupId = "group_${System.currentTimeMillis()}_${userPrefs.deviceId.take(4)}"
            val group = GroupEntity(
                groupId = groupId,
                groupName = name.trim(),
                description = description.trim(),
                createdBy = userPrefs.displayName,
                avatarColorIndex = avatarColorIndex
            )
            database.groupDao().insertOrUpdateGroup(group)
            val currentContacts = contacts.value
            for (peer in currentContacts) {
                if (peer.isOnline) {
                    val packet = com.example.data.network.GroupAnnouncePacket(
                        groupId = group.groupId,
                        groupName = group.groupName,
                        description = group.description,
                        createdBy = group.createdBy,
                        avatarColorIndex = group.avatarColorIndex,
                        createdAt = group.createdAt
                    )
                    launch {
                        tcpMessaging.sendPacketDirect(peer.ipAddress, peer.tcpPort, packet)
                    }
                }
            }
        }
    }

    /** Whether the microphone is available. Without it the caller ends up fully silent. */
    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED


    /**
     * Nearby needs the Bluetooth scan/advertise permissions on Android 12+ (API 31).
     * They were declared in the manifest but never requested at runtime, so on a
     * fresh install the mesh failed with Nearby 8037/8038 and the user only saw
     * "MESH unavailable" with no way to fix it.
     */
    fun missingMeshPermissions(): Array<String> {
        val needed = mutableListOf(
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += android.Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return needed.filter {
            ContextCompat.checkSelfPermission(getApplication(), it) !=
                PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }
    fun hasMeshPermissions(): Boolean = missingMeshPermissions().isEmpty()
    private val _pendingCallRequest = MutableStateFlow<ContactEntity?>(null)
    val pendingCallRequest: StateFlow<ContactEntity?> = _pendingCallRequest.asStateFlow()

    fun startCallWithContact(contact: ContactEntity) {
        if (!hasRecordAudioPermission()) {
            // من غير الإذن LanAudioCallManager بيخرج قبل ما يعمل AudioRecord
            // و AudioTrack، فالمكالمة بتتوصّل بصمت تام من ناحيتنا.
            _pendingCallRequest.value = contact
            return
        }

        actuallyStartCall(contact)
    }

    fun startPendingCallIfPermitted() {
        val pending = _pendingCallRequest.value ?: return
        _pendingCallRequest.value = null
        if (hasRecordAudioPermission()) {
            actuallyStartCall(pending)
        } else {
            _errorMessage.value = "يلزم إذن الميكروفون لإجراء المكالمات"
        }
    }

    private fun actuallyStartCall(contact: ContactEntity) {
        audioCallManager.startCall(
            peerId = contact.deviceId,
            peerName = contact.displayName,
            peerIp = contact.ipAddress,
            peerTcpPort = contact.tcpPort
        )
    }

    fun startVoiceCall(contact: ContactEntity) {
        startCallWithContact(contact)
    }

    fun acceptIncomingCall() {
        audioCallManager.acceptCall()
    }

    fun declineIncomingCall() {
        audioCallManager.declineCall()
    }

    fun endCurrentCall() {
        audioCallManager.endCall()
    }

    fun toggleCallMute() {
        audioCallManager.toggleMute()
    }

    fun toggleCallSpeaker() {
        audioCallManager.toggleSpeaker()
    }

    fun startVoiceRecording(): Boolean {
        return audioRecorder.startRecording()
    }

    fun stopVoiceRecordingAndSend(discard: Boolean = false) {
        val file = audioRecorder.stopRecording(discard = discard)
        if (!discard && file != null && file.exists()) {
            val duration = audioRecorder.recordingState.value.durationSeconds.coerceAtLeast(1)
            sendVoiceFile(file, duration)
        }
    }

    fun cancelVoiceRecording() {
        stopVoiceRecordingAndSend(discard = true)
    }

    fun playAudio(filePath: String) {
        audioPlayer.playOrPause(filePath)
    }

    fun toggleAudioPlayback(filePath: String) {
        playAudio(filePath)
    }

    fun seekAudio(progressFraction: Float) {
        audioPlayer.seekTo(progressFraction)
    }

    fun cancelTransfer(transferId: String) {
        val contact = _activeContact.value
        tcpMessaging.cancelTransfer(transferId, contact?.ipAddress, contact?.tcpPort ?: 9999)
    }

    private fun sendVoiceFile(file: File, durationSeconds: Int) {
        val contact = _activeContact.value
        val group = _activeGroup.value
        viewModelScope.launch {
            _isSending.value = true
            if (contact != null) {
                val result = tcpMessaging.sendVoiceMessage(
                    recipientIp = contact.ipAddress,
                    recipientPort = contact.tcpPort,
                    recipientId = contact.deviceId,
                    voiceFile = file,
                    durationSeconds = durationSeconds
                )
                if (result.isFailure) {
                    _errorMessage.value = "تعذر إرسال التسجيل الصوتي."
                }
            } else if (group != null) {
                val currentContacts = contacts.value
                tcpMessaging.sendGroupVoiceMessage(
                    groupId = group.groupId,
                    groupName = group.groupName,
                    voiceFile = file,
                    durationSeconds = durationSeconds,
                    contacts = currentContacts
                )
            }
            _isSending.value = false
        }
    }

    /**
     * Step 1.1: retry a message the user explicitly asked us to try again.
     *
     * FAILED is terminal by design, so a retry is always a user action. Only our
     * own FAILED direct messages qualify, and the resend keeps the original
     * messageId. The status is decided by the send path itself, so an
     * unreachable peer becomes QUEUED rather than being reported as sent.
     */
    fun retryFailedMessage(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = database.chatMessageDao().getMessageById(messageId) ?: return@launch
            if (!message.isFromMe || message.isGroup) return@launch
            if (message.status != MessageStatus.FAILED) return@launch

            // TcpMessagingManager owns the status for a retry, including the
            // intermediate SENDING, so the row is written exactly once per
            // outcome rather than from two places.
            runCatching { tcpMessaging.retryMessage(message) }
                .onFailure { error ->
                    Log.w("ChatViewModel", "Retry of $messageId threw: ${error.message}")
                }
        }
    }

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val contact = _activeContact.value
        val group = _activeGroup.value
        if (contact != null) {
            viewModelScope.launch {
                _isSending.value = true
                val result = tcpMessaging.sendTextMessage(
                    recipientIp = contact.ipAddress,
                    recipientPort = contact.tcpPort,
                    recipientId = contact.deviceId,
                    text = text.trim()
                )
                if (result.isFailure) {
                    _errorMessage.value = "تعذر إرسال الرسالة. تم حفظها وسيتم إعادة الإرسال تلقائياً."
                }
                _isSending.value = false
            }
        } else if (group != null) {
            viewModelScope.launch {
                _isSending.value = true
                val currentContacts = contacts.value
                tcpMessaging.sendGroupTextMessage(
                    groupId = group.groupId,
                    groupName = group.groupName,
                    text = text.trim(),
                    contacts = currentContacts
                )
                _isSending.value = false
            }
        }
    }

    fun sendPhotoUri(uri: Uri, caption: String = "") {
        val contact = _activeContact.value
        val group = _activeGroup.value
        val context = getApplication<Application>()
        viewModelScope.launch {
            _isSending.value = true
            val localFile = FileUtils.copyUriToLocalFile(context, uri, "sent_photos")
            if (localFile != null && localFile.exists()) {
                if (contact != null) {
                    val result = tcpMessaging.sendPhotoMessage(
                        recipientIp = contact.ipAddress,
                        recipientPort = contact.tcpPort,
                        recipientId = contact.deviceId,
                        localPhotoPath = localFile.absolutePath,
                        caption = caption
                    )
                    if (result.isFailure) {
                        _errorMessage.value = "تعذر إرسال الصورة."
                    }
                } else if (group != null) {
                    val currentContacts = contacts.value
                    tcpMessaging.sendGroupPhotoMessage(
                        groupId = group.groupId,
                        groupName = group.groupName,
                        localPhotoPath = localFile.absolutePath,
                        caption = caption,
                        contacts = currentContacts
                    )
                }
            } else {
                _errorMessage.value = "تعذر معالجة الصورة."
            }
            _isSending.value = false
        }
    }

    fun sendPhotoBitmap(bitmap: Bitmap, caption: String = "") {
        val contact = _activeContact.value
        val group = _activeGroup.value
        val context = getApplication<Application>()
        viewModelScope.launch {
            _isSending.value = true
            val localFile = ImageUtils.saveBitmapToFile(context, bitmap, "sent_")
            if (localFile != null && localFile.exists()) {
                if (contact != null) {
                    val result = tcpMessaging.sendPhotoMessage(
                        recipientIp = contact.ipAddress,
                        recipientPort = contact.tcpPort,
                        recipientId = contact.deviceId,
                        localPhotoPath = localFile.absolutePath,
                        caption = caption
                    )
                    if (result.isFailure) {
                        _errorMessage.value = "تعذر إرسال الصورة."
                    }
                } else if (group != null) {
                    val currentContacts = contacts.value
                    tcpMessaging.sendGroupPhotoMessage(
                        groupId = group.groupId,
                        groupName = group.groupName,
                        localPhotoPath = localFile.absolutePath,
                        caption = caption,
                        contacts = currentContacts
                    )
                }
            }
            _isSending.value = false
        }
    }

    fun sendVideoUri(uri: Uri, caption: String = "") {
        val contact = _activeContact.value
        val group = _activeGroup.value
        val context = getApplication<Application>()
        viewModelScope.launch {
            _isSending.value = true
            val localFile = com.example.data.network.FileUtils.copyUriToLocalFile(context, uri, "sent_videos")
            if (localFile != null && localFile.exists()) {
                val meta = com.example.data.network.FileUtils.getFileMeta(context, uri)
                if (contact != null) {
                    val result = tcpMessaging.sendVideoMessage(
                        recipientIp = contact.ipAddress,
                        recipientPort = contact.tcpPort,
                        recipientId = contact.deviceId,
                        localFilePath = localFile.absolutePath,
                        fileName = meta.fileName,
                        fileSize = localFile.length(),
                        mimeType = meta.mimeType ?: "video/mp4",
                        caption = caption
                    )
                    if (result.isFailure) {
                        _errorMessage.value = "تعذر إرسال الفيديو."
                    }
                } else if (group != null) {
                    val currentContacts = contacts.value
                    tcpMessaging.sendGroupVideoMessage(
                        groupId = group.groupId,
                        groupName = group.groupName,
                        localFilePath = localFile.absolutePath,
                        fileName = meta.fileName,
                        fileSize = localFile.length(),
                        mimeType = meta.mimeType ?: "video/mp4",
                        caption = caption,
                        contacts = currentContacts
                    )
                }
            }
            _isSending.value = false
        }
    }

    fun sendFileUri(uri: Uri, caption: String = "") {
        val contact = _activeContact.value
        val group = _activeGroup.value
        val context = getApplication<Application>()
        viewModelScope.launch {
            _isSending.value = true
            val localFile = com.example.data.network.FileUtils.copyUriToLocalFile(context, uri, "sent_files")
            if (localFile != null && localFile.exists()) {
                val meta = com.example.data.network.FileUtils.getFileMeta(context, uri)
                val isVideo = com.example.data.network.FileUtils.isVideoMime(meta.mimeType, meta.fileName)
                if (isVideo) {
                    if (contact != null) {
                        tcpMessaging.sendVideoMessage(
                            recipientIp = contact.ipAddress,
                            recipientPort = contact.tcpPort,
                            recipientId = contact.deviceId,
                            localFilePath = localFile.absolutePath,
                            fileName = meta.fileName,
                            fileSize = localFile.length(),
                            mimeType = meta.mimeType ?: "video/mp4",
                            caption = caption
                        )
                    } else if (group != null) {
                        val currentContacts = contacts.value
                        tcpMessaging.sendGroupVideoMessage(
                            groupId = group.groupId,
                            groupName = group.groupName,
                            localFilePath = localFile.absolutePath,
                            fileName = meta.fileName,
                            fileSize = localFile.length(),
                            mimeType = meta.mimeType ?: "video/mp4",
                            caption = caption,
                            contacts = currentContacts
                        )
                    }
                } else {
                    if (contact != null) {
                        tcpMessaging.sendDocFileMessage(
                            recipientIp = contact.ipAddress,
                            recipientPort = contact.tcpPort,
                            recipientId = contact.deviceId,
                            localFilePath = localFile.absolutePath,
                            fileName = meta.fileName,
                            fileSize = localFile.length(),
                            mimeType = meta.mimeType ?: "*/*",
                            caption = caption
                        )
                    } else if (group != null) {
                        val currentContacts = contacts.value
                        tcpMessaging.sendGroupDocFileMessage(
                            groupId = group.groupId,
                            groupName = group.groupName,
                            localFilePath = localFile.absolutePath,
                            fileName = meta.fileName,
                            fileSize = localFile.length(),
                            mimeType = meta.mimeType ?: "*/*",
                            caption = caption,
                            contacts = currentContacts
                        )
                    }
                }
            }
            _isSending.value = false
        }
    }

    fun prepareAppForSharing() {
        appShareManager.prepareApkForSharing()
    }

    fun shareApp() {
        appShareManager.shareApk()
    }

    fun shareAppDirectly(targetIp: String, targetPort: Int) {
        appShareManager.shareApkDirectly(targetIp, targetPort)
    }

    fun clearConversation(conversationId: String) {
        viewModelScope.launch {
            database.chatMessageDao().clearConversation(conversationId)
        }
    }

    fun clearPlaybackError() {
        audioPlayer.clearError()
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        autoDiscoveryJob?.cancel()
        autoDiscoveryJob = null
        try {
            audioPlayer.stop()
        } catch (_: Exception) {}
        try {
            audioRecorder.stopRecording(discard = true)
        } catch (_: Exception) {}
    }
}