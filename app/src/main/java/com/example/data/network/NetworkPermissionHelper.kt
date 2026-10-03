package com.example.data.network

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class MissingPermissionItem(
    val id: String,
    val permissionKeys: List<String>,
    val titleArabic: String,
    val descriptionArabic: String,
    val isGranted: Boolean = false,
    val isCrucial: Boolean = true
) {
    val permissionKey: String get() = permissionKeys.firstOrNull() ?: ""
}

data class NetworkDiagnosticState(
    val isNetworkConnected: Boolean = true,
    val isWifiOrHotspot: Boolean = true,
    val isCellularOnly: Boolean = false,
    val isP2pMeshActive: Boolean = true,
    val isBluetoothEnabled: Boolean = true,
    val connectionType: ConnectionType = ConnectionType.OFFLINE,
    val localIpAddress: String = "127.0.0.1",
    val networkName: String = "شبكة محلية",
    val missingPermissions: List<MissingPermissionItem> = emptyList(),
    val allPermissions: List<MissingPermissionItem> = emptyList(),
    val allRequiredGranted: Boolean = true,
    val isReadyForLan: Boolean = true
)

object NetworkPermissionHelper {

    fun getRequiredPermissionsList(): List<String> {
        val list = mutableListOf<String>()
        // 1. Audio recording permission
        list.add(Manifest.permission.RECORD_AUDIO)
        // 2. Camera permission for direct photo sharing
        list.add(Manifest.permission.CAMERA)

        // 3. Nearby & Direct Wi-Fi / Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return list
    }

    fun checkNetworkAndPermissions(context: Context): NetworkDiagnosticState {
        val allList = mutableListOf<MissingPermissionItem>()

        // 1. Microphone permission (الصوت)
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val micItem = MissingPermissionItem(
            id = "audio",
            permissionKeys = listOf(Manifest.permission.RECORD_AUDIO),
            titleArabic = "إذن الميكروفون",
            descriptionArabic = "مطلوب لتسجيل الرسائل الصوتية وإجراء المكالمات الصوتية المباشرة.",
            isGranted = hasMic,
            isCrucial = true
        )
        allList.add(micItem)

        // 2. Camera permission (الكاميرا)
        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val cameraItem = MissingPermissionItem(
            id = "camera",
            permissionKeys = listOf(Manifest.permission.CAMERA),
            titleArabic = "إذن الكاميرا",
            descriptionArabic = "مطلوب لالتقاط ومشاركة الصور المباشرة ومسح الرموز داخل المحادثات.",
            isGranted = hasCamera,
            isCrucial = true
        )
        allList.add(cameraItem)

        // 3. Nearby Devices & Direct P2P (الأجهزة المجاورة والبلوتوث)
        val nearbyKeys = mutableListOf<String>()
        val hasNearbyGranted: Boolean

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyKeys.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            nearbyKeys.add(Manifest.permission.BLUETOOTH_SCAN)
            nearbyKeys.add(Manifest.permission.BLUETOOTH_CONNECT)
            nearbyKeys.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            val hasNearbyWifi = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            val hasBtScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasBtConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasBtAdv = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            hasNearbyGranted = hasNearbyWifi && hasBtScan && hasBtConnect && hasBtAdv
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nearbyKeys.add(Manifest.permission.BLUETOOTH_SCAN)
            nearbyKeys.add(Manifest.permission.BLUETOOTH_CONNECT)
            nearbyKeys.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            val hasBtScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val hasBtConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasBtAdv = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            hasNearbyGranted = hasBtScan && hasBtConnect && hasBtAdv
        } else {
            nearbyKeys.add(Manifest.permission.ACCESS_FINE_LOCATION)
            nearbyKeys.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasNearbyGranted = hasFine && hasCoarse
        }

        val nearbyItem = MissingPermissionItem(
            id = "nearby",
            permissionKeys = nearbyKeys,
            titleArabic = "إذن الأجهزة المجاورة والبلوتوث",
            descriptionArabic = "مطلوب للربط التلقائي واكتشاف الهواتف المجاورة (P2P Mesh) بدون راوتر وبدون إنترنت.",
            isGranted = hasNearbyGranted,
            isCrucial = true
        )
        allList.add(nearbyItem)

        // 4. Notifications (الإشعارات)
        val hasNotif: Boolean
        val notifKeys = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifKeys.add(Manifest.permission.POST_NOTIFICATIONS)
            hasNotif = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasNotif = true // Automatically granted on API < 33
        }

        val notifItem = MissingPermissionItem(
            id = "notifications",
            permissionKeys = if (notifKeys.isNotEmpty()) notifKeys else listOf(Manifest.permission.VIBRATE),
            titleArabic = "إذن الإشعارات",
            descriptionArabic = "مطلوب لتنبيهك بالمكالمات والرسائل الواردة أثناء تشغيل التطبيق في الخلفية.",
            isGranted = hasNotif,
            isCrucial = false
        )
        allList.add(notifItem)

        val missing = allList.filter { !it.isGranted }

        // 4. Check Bluetooth hardware/adapter state
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val isBluetoothEnabled = try {
            bluetoothManager?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }

        // 5. Accurate Network Analysis via NetworkUtils
        val netInfo = NetworkUtils.getNetworkInfoResult()
        val isLanReady = netInfo.isLanReady
        val isCellular = netInfo.connectionType == ConnectionType.CELLULAR
        val allGranted = missing.isEmpty()

        val isNearbyPermsGranted = hasNearbyGranted
        val isP2pMeshFunctional = isNearbyPermsGranted && isBluetoothEnabled

        val effectiveConnectionType = when {
            netInfo.connectionType == ConnectionType.WIFI -> ConnectionType.WIFI
            netInfo.connectionType == ConnectionType.HOTSPOT -> ConnectionType.HOTSPOT
            netInfo.connectionType == ConnectionType.WIFI_DIRECT -> ConnectionType.WIFI_DIRECT
            netInfo.connectionType == ConnectionType.ETHERNET -> ConnectionType.ETHERNET
            netInfo.connectionType == ConnectionType.CELLULAR -> ConnectionType.CELLULAR
            isP2pMeshFunctional -> ConnectionType.P2P_MESH
            else -> netInfo.connectionType
        }

        val netName = when (effectiveConnectionType) {
            ConnectionType.HOTSPOT -> "نقطة اتصال (Hotspot) نشطة"
            ConnectionType.WIFI -> "شبكة Wi-Fi متصلة"
            ConnectionType.WIFI_DIRECT -> "اتصال Wi-Fi Direct"
            ConnectionType.ETHERNET -> "شبكة سلكية Ethernet"
            ConnectionType.P2P_MESH -> "شبكة P2P Mesh (مباشر بدون راوتر)"
            ConnectionType.CELLULAR -> if (isP2pMeshFunctional) "بيانات الهاتف + P2P Mesh" else "بيانات الهاتف"
            ConnectionType.OFFLINE, ConnectionType.LOOPBACK -> {
                if (!isBluetoothEnabled && isNearbyPermsGranted) {
                    "البلوتوث متوقف (P2P Mesh غير متاح)"
                } else {
                    "غير متصل بأي شبكة"
                }
            }
        }

        val isOnline = isLanReady || isCellular || (effectiveConnectionType == ConnectionType.P2P_MESH)

        return NetworkDiagnosticState(
            isNetworkConnected = isOnline,
            isWifiOrHotspot = isLanReady,
            isCellularOnly = isCellular,
            isP2pMeshActive = isP2pMeshFunctional,
            isBluetoothEnabled = isBluetoothEnabled,
            connectionType = effectiveConnectionType,
            localIpAddress = if (effectiveConnectionType == ConnectionType.P2P_MESH && netInfo.ipAddress == "127.0.0.1") "p2p-mesh" else netInfo.ipAddress,
            networkName = netName,
            missingPermissions = missing,
            allPermissions = allList,
            allRequiredGranted = allGranted,
            isReadyForLan = isLanReady || isP2pMeshFunctional
        )
    }

    fun openBluetoothSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    fun openWifiSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Ignore
            }
        }
    }

    fun openHotspotSettings(context: Context) {
        NetworkUtils.openHotspotSettings(context)
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
