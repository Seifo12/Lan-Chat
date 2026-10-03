package com.example.data.network

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AvailableUpdateInfo(
    val providerDeviceId: String,
    val versionCode: Int,
    val versionName: String,
    val apkSizeBytes: Long,
    val isUpdateAvailable: Boolean = true
)

data class AppShareState(
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val apkFile: File? = null,
    val errorMessage: String? = null
) {
    val status: String
        get() = when {
            isPreparing -> "جاري تحضير ملف المشاركة..."
            isReady -> "جاهز للمشاركة"
            errorMessage != null -> "تعذر التحضير"
            else -> "جاهز"
        }
}

class P2PAppUpdateManager(
    private val context: Context,
    private val database: ChatDatabase,
    private val userPreferences: UserPreferences,
    private val tcpMessagingManager: TcpMessagingManager
) {

    companion object {
        private const val TAG = "P2PAppShareManager"
        private const val MAX_APK_SIZE = 500L * 1024 * 1024
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _shareState = MutableStateFlow(AppShareState())
    val shareState: StateFlow<AppShareState> = _shareState.asStateFlow()

    private val _availableUpdates = MutableStateFlow<List<AvailableUpdateInfo>>(emptyList())
    val availableUpdates: StateFlow<List<AvailableUpdateInfo>> = _availableUpdates.asStateFlow()

    fun onUpdateDiscovered(packet: AppUpdateResponsePacket) {
        if (packet.versionCode > userPreferences.appVersionCode) {
            val updateInfo = AvailableUpdateInfo(
                providerDeviceId = packet.providerDeviceId,
                versionCode = packet.versionCode,
                versionName = packet.versionName,
                apkSizeBytes = packet.apkSizeBytes,
                isUpdateAvailable = packet.isUpdateAvailable
            )
            _availableUpdates.value = (_availableUpdates.value.filter { it.providerDeviceId != packet.providerDeviceId } + updateInfo)
        }
    }

    fun prepareApkForSharing() {
        scope.launch {
            try {
                _shareState.value = AppShareState(isPreparing = true)

                val sourceApk = File(context.applicationInfo.sourceDir)
                if (!sourceApk.exists()) {
                    _shareState.value = AppShareState(errorMessage = "لم يتم العثور على ملف التطبيق المصدر")
                    return@launch
                }

                if (sourceApk.length() > MAX_APK_SIZE) {
                    _shareState.value = AppShareState(errorMessage = "حجم الحزمة يتجاوز الحد المسموح")
                    return@launch
                }

                val shareDir = File(context.cacheDir, "shared_apk").apply { mkdirs() }
                val shareFile = File(shareDir, "LAN_Chat_v${userPreferences.appVersionName}.apk")
                sourceApk.copyTo(shareFile, overwrite = true)

                _shareState.value = AppShareState(
                    isReady = true,
                    apkFile = shareFile
                )
                Log.i(TAG, "APK ready for sharing: ${shareFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing APK: ${e.message}", e)
                _shareState.value = AppShareState(errorMessage = "فشل تحضير الملف: ${e.message}")
            }
        }
    }

    fun shareApk() {
        val file = _shareState.value.apkFile
        if (file == null || !file.exists()) {
            prepareApkForSharing()
            return
        }
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "تطبيق LAN Chat v${userPreferences.appVersionName}")
                putExtra(Intent.EXTRA_TEXT, "تطبيق محادثة بدون إنترنت عبر الشبكة المحلية")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val chooser = Intent.createChooser(shareIntent, "مشاركة التطبيق عبر...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share: ${e.message}", e)
            _shareState.value = _shareState.value.copy(errorMessage = "تعذر فتح قائمة المشاركة: ${e.message}")
        }
    }

    fun shareApkDirectly(targetIp: String, targetPort: Int) {
        val file = _shareState.value.apkFile
        if (file == null || !file.exists()) {
            prepareApkForSharing()
            return
        }
        scope.launch {
            try {
                val transferId = "apk_share_${System.currentTimeMillis()}"
                val messageId = "apk_share_msg_${System.currentTimeMillis()}"
                tcpMessagingManager.streamFileDirect(
                    targetIp = targetIp,
                    targetPort = targetPort,
                    transferId = transferId,
                    messageId = messageId,
                    senderId = userPreferences.deviceId,
                    senderName = userPreferences.displayName,
                    recipientId = "any",
                    fileName = "LAN_Chat_v${userPreferences.appVersionName}.apk",
                    fileSize = file.length(),
                    mimeType = "application/vnd.android.package-archive",
                    caption = "تطبيق LAN Chat بدون إنترنت",
                    isVideo = false,
                    file = file
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed direct APK send: ${e.message}", e)
                _shareState.value = _shareState.value.copy(errorMessage = "فشل الإرسال المباشر: ${e.message}")
            }
        }
    }

    fun cleanup() {
        try {
            val shareDir = File(context.cacheDir, "shared_apk")
            shareDir.deleteRecursively()
            _shareState.value = AppShareState()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }
}