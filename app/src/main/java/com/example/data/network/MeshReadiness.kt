package com.example.data.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MeshBlockingStep { NONE, BLUETOOTH_OFF, PERMISSIONS_MISSING, PLAY_SERVICES_UNAVAILABLE }

data class MeshReadiness(
    val bluetoothEnabled: Boolean,
    val hasNearbyPermissions: Boolean,
    val playServicesAvailable: Boolean
) {
    val isReady: Boolean get() = bluetoothEnabled && hasNearbyPermissions && playServicesAvailable

    fun blockingStep(): MeshBlockingStep = when {
        !bluetoothEnabled -> MeshBlockingStep.BLUETOOTH_OFF
        !hasNearbyPermissions -> MeshBlockingStep.PERMISSIONS_MISSING
        !playServicesAvailable -> MeshBlockingStep.PLAY_SERVICES_UNAVAILABLE
        else -> MeshBlockingStep.NONE
    }

    companion object {
        fun requiredPermissions(): Array<String> = arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        fun check(context: Context): MeshReadiness {
            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requiredPermissions().toList()
            } else {
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            val granted = needed.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            val bluetooth = try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                        as? android.bluetooth.BluetoothManager
                manager?.adapter?.isEnabled == true
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                false
            }
            val playServices = try {
                com.google.android.gms.common.GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context) ==
                    com.google.android.gms.common.ConnectionResult.SUCCESS
            } catch (e: Exception) {
                false
            }
            return MeshReadiness(bluetooth, granted, playServices)
        }
    }
}
