package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.UserPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8, Gate 1: nothing unauthenticated may create or overwrite a group.
 *
 * The UDP path built group rows from undecrypted broadcast, so anyone on the
 * network could create or overwrite any group. The arm is deleted; the beacon
 * control proves discovery itself survives the refactor that made this
 * testable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UdpIgnoresGroupAnnounceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ChatDatabase
    private lateinit var manager: UdpDiscoveryManager

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = UdpDiscoveryManager(context, database, UserPreferences(context))
    }

    @Test
    fun `a group announce over udp creates nothing`() = runBlocking {
        val address = java.net.InetAddress.getByName("10.0.0.9")
        manager.handleDiscoveryPacket(
            GroupAnnouncePacket(
                groupId = "g_planted",
                groupName = "Planted",
                createdBy = "mallory",
            ).toJson(),
            senderIp = "10.0.0.9",
            senderAddress = address,
        )

        assertNull(
            "an unsigned broadcast must not materialise a group, or membership " +
                "means nothing no matter what 1.8 builds on top",
            database.groupDao().getGroupById("g_planted"),
        )
    }

    @Test
    fun `a group announce over udp overwrites nothing`() = runBlocking {
        runBlocking {
            database.groupDao().insertOrUpdateGroup(
                com.example.data.local.GroupEntity(
                    groupId = "g_real",
                    groupName = "Real",
                    createdBy = "peer_a",
                )
            )
        }
        val address = java.net.InetAddress.getByName("10.0.0.9")
        manager.handleDiscoveryPacket(
            GroupAnnouncePacket(
                groupId = "g_real",
                groupName = "Renamed by broadcast",
                createdBy = "mallory",
            ).toJson(),
            senderIp = "10.0.0.9",
            senderAddress = address,
        )

        assertEquals(
            "the stored name must survive the broadcast",
            "Real",
            database.groupDao().getGroupById("g_real")?.groupName,
        )
    }

    @Test
    fun `beacon discovery still works through the same seam`() = runBlocking {
        val address = java.net.InetAddress.getByName("10.0.0.9")
        manager.handleDiscoveryPacket(
            BeaconPacket(
                deviceId = "peer_beacon_1",
                displayName = "Beacon Peer",
                avatarColorIndex = 0,
                tcpPort = 9999,
            ).toJson(),
            senderIp = "10.0.0.9",
            senderAddress = address,
        )

        // A beacon from another device is recorded as a nearby peer. The ack
        // it triggers needs a real socket and is not under test; it fails
        // silently inside its own coroutine if there is none.
        val peer = database.discoveredPeerDao().getById("peer_beacon_1")
        assertEquals("10.0.0.9", peer?.ipAddress)
    }
}
