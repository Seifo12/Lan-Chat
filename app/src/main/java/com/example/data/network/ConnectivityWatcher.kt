package com.example.data.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Tells the mesh layer that connectivity genuinely came back.
 *
 * Two rules matter here, both learned the hard way:
 *
 * 1. Only act on a real unavailable -> available transition. `onAvailable`
 *    fires whenever a network satisfies the request, not on a recovery, and
 *    acting on it caused a ~2s restart loop.
 * 2. Never tear the mesh down. Restarting is the caller's decision and
 *    [NearbyMeshManager.startMeshService] is already a no-op while the mesh is
 *    running. A teardown here would churn network state and re-trigger this
 *    callback. Dropped peers are already handled by ConnectionSupervisor.
 */
class ConnectivityWatcher(
    private val context: Context,
    private val onRecovered: () -> Unit,
) {

    var isRegistered: Boolean = false
        private set

    private val gate = ConnectivityRecoveryGate()

    private fun currentState(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun evaluate() {
        if (gate.onNetworkState(currentState())) onRecovered()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            evaluate()
        }

        override fun onLost(network: Network) {
            evaluate()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_WIFI_STATE, ACTION_BLUETOOTH_STATE -> evaluate()
            }
        }
    }

    fun register() {
        if (isRegistered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Prime the gate with the state we already have, so registering does not
        // look like a fresh "network appeared" event.
        gate.onNetworkState(currentState())
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback,
            )
        }
        runCatching {
            context.registerReceiver(
                stateReceiver,
                IntentFilter().apply {
                    addAction(ACTION_WIFI_STATE)
                    addAction(ACTION_BLUETOOTH_STATE)
                },
            )
        }
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(stateReceiver) }
        isRegistered = false
    }

    private companion object {
        const val ACTION_WIFI_STATE = "android.net.wifi.STATE_CHANGE"
        const val ACTION_BLUETOOTH_STATE = "android.bluetooth.adapter.action.STATE_CHANGED"
    }
}
