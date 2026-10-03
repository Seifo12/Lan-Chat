package com.example.service

import android.Manifest
import android.app.Service
import com.example.data.network.ConnectivityWatcher
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

class LanBackgroundService : Service() {

    companion object {
        private const val TAG = "LanBackgroundService"
        const val ACTION_START = "ACTION_START_LAN_SERVICE"
        const val ACTION_STOP = "ACTION_STOP_LAN_SERVICE"
        const val ACTION_CALL_ACTIVE = "ACTION_LAN_CALL_ACTIVE"
        const val ACTION_CALL_IDLE = "ACTION_LAN_CALL_IDLE"

        fun start(context: Context) {
            val intent = Intent(context, LanBackgroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LanBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }

        fun updateCallState(context: Context, isCallActive: Boolean) {
            val intent = Intent(context, LanBackgroundService::class.java).apply {
                action = if (isCallActive) ACTION_CALL_ACTIVE else ACTION_CALL_IDLE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notice: Handled background FGS transition safely: ${e.message}")
            }
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else {
                true
            }
        }

        fun openBatterySettings(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open battery settings: ${e.message}")
                }
            }
        }
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var isCallInProgress = false
    private var isScreenReceiverRegistered = false

    // مستقبل بث لمراقبة حالة الشاشة وإيقاف قفل الواي فاي فور إطفائها لتوفير البطارية
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (!isCallInProgress) {
                        releaseMulticastLock()
                        Log.d(TAG, "Screen OFF: MulticastLock released for battery saving")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    acquireMulticastLock()
                    Log.d(TAG, "Screen ON: MulticastLock re-acquired")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LanNotificationHelper.createNotificationChannels(this)
        acquireMulticastLock()
        registerScreenReceiver()
        connectivityWatcher.register()
    }

    /**
     * When Wi-Fi or Bluetooth genuinely comes back, make sure the mesh is
     * advertising. Without this the mesh never recovered from a connectivity
     * drop on its own and the user had to force-kill the app.
     */
    private val connectivityWatcher by lazy {
        ConnectivityWatcher(this) {
            Thread {
                runCatching {
                    val app = applicationContext as? com.example.LanChatApplication ?: return@runCatching
                    // Start-if-not-running, never a full restart: tearing MESH
                    // down here churns network state and re-fires this callback.
                    // Reconnecting peers is ConnectionSupervisor's job.
                    app.meshManager.startMeshService()
                }.onFailure { Log.w(TAG, "mesh start on connectivity return failed: ${it.message}") }
            }.start()
        }
    }

    private fun registerScreenReceiver() {
        try {
            if (!isScreenReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                registerReceiver(screenStateReceiver, filter)
                isScreenReceiverRegistered = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering screen receiver: ${e.message}")
        }
    }

    private fun unregisterScreenReceiver() {
        try {
            if (isScreenReceiverRegistered) {
                unregisterReceiver(screenStateReceiver)
                isScreenReceiverRegistered = false
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALL_ACTIVE -> {
                isCallInProgress = true
                acquireMulticastLock()
                promoteToForeground(isCallActive = true)
                return START_STICKY
            }
            ACTION_CALL_IDLE -> {
                isCallInProgress = false
                promoteToForeground(isCallActive = false)
                return START_STICKY
            }
            else -> {
                promoteToForeground(isCallActive = isCallInProgress)
                return START_STICKY
            }
        }
    }

    /**
     * ترقية نوع الخدمة بأمان تام وفقاً لقيود أندرويد 14 فما فوق
     */
    private fun promoteToForeground(isCallActive: Boolean) {
        val notification = LanNotificationHelper.buildServiceNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (isCallActive && hasMicPermission) {
                    try {
                        startForeground(
                            LanNotificationHelper.NOTIFICATION_ID_SERVICE,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Microphone FGS promotion fallback to connectedDevice: ${e.message}")
                        startForeground(
                            LanNotificationHelper.NOTIFICATION_ID_SERVICE,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                        )
                    }
                } else {
                    startForeground(
                        LanNotificationHelper.NOTIFICATION_ID_SERVICE,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    LanNotificationHelper.NOTIFICATION_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(LanNotificationHelper.NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "promoteToForeground safe catch: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null || multicastLock?.isHeld == false) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("LANChat::ServiceMulticastLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MulticastLock error: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MulticastLock: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterScreenReceiver()
        connectivityWatcher.unregister()
        releaseMulticastLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}