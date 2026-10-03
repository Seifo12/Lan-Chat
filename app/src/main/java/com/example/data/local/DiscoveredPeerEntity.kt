package com.example.data.local

import androidx.room.Entity

@Entity(tableName = "discovered_peers", primaryKeys = ["deviceId"])
data class DiscoveredPeerEntity(
    val deviceId: String,
    val displayName: String,
    val endpointId: String = "",
    val ipAddress: String? = null,
    val publicKeyBase64: String?,
    val avatarColorIndex: Int = 0,
    val isDeveloper: Boolean = false,
    val appVersionCode: Int = 1,
    val transportIsLan: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)
