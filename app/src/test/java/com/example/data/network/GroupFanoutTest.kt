package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.data.local.GroupMemberEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: a group send goes to current members, not to every contact.
 *
 * The old code passed the whole contact list into every group sender, so a
 * group message reached whoever happened to be online regardless of
 * membership. Recipients are the member table intersected with routable
 * reality: contacts, online, pin-verified. No sockets are involved; the
 * resolution is pure database reads, which is what makes it testable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupFanoutTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ChatDatabase

    private val groupId = "g_fanout"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val contacts = database.contactDao()
            contacts.insertOrUpdateContact(
                ContactEntity(
                    deviceId = "peer_ok", displayName = "OK", ipAddress = "10.0.0.1",
                    publicKeyBase64 = "pk_ok", pinnedPublicKey = "pk_ok",
                )
            )
            contacts.insertOrUpdateContact(
                ContactEntity(
                    deviceId = "peer_offline", displayName = "Offline", ipAddress = "10.0.0.2",
                    isOnline = false,
                    publicKeyBase64 = "pk_off", pinnedPublicKey = "pk_off",
                )
            )
            contacts.insertOrUpdateContact(
                ContactEntity(
                    deviceId = "peer_changed", displayName = "Changed", ipAddress = "10.0.0.3",
                    publicKeyBase64 = "pk_new", pinnedPublicKey = "pk_old",
                    hasKeyChanged = true,
                )
            )
            contacts.insertOrUpdateContact(
                ContactEntity(
                    deviceId = "peer_stranger", displayName = "Stranger", ipAddress = "10.0.0.4",
                    publicKeyBase64 = "pk_stranger", pinnedPublicKey = "pk_stranger",
                )
            )
            database.groupDao().insertOrUpdateGroup(
                GroupEntity(
                    groupId = groupId, groupName = "Fanout", createdBy = "me",
                    creatorDeviceId = "me", isLegacy = false,
                )
            )
            database.groupMembershipDao().replaceMembers(
                groupId,
                listOf(
                    GroupMemberEntity(groupId, "peer_ok", "pk_ok", "OK"),
                    GroupMemberEntity(groupId, "peer_offline", "pk_off", "Offline"),
                    GroupMemberEntity(groupId, "peer_changed", "pk_old", "Changed"),
                    GroupMemberEntity(groupId, "peer_ghost", "pk_ghost", "Ghost"),
                    GroupMemberEntity(groupId, "me", "pk_me", "Me"),
                )
            )
        }
    }

    @Test
    fun `only online verified member contacts receive`() = runBlocking {
        val recipients = GroupFanout.resolve(database, groupId)

        assertEquals(
            "offline members cannot be reached, key-changed members must not " +
                "be messaged, non-contacts have no route, and non-members " +
                "never receive group traffic",
            listOf("peer_ok"),
            recipients.map { it.deviceId }
        )
    }

    @Test
    fun `an unknown group resolves to nobody`() = runBlocking {
        assertEquals(
            emptyList<String>(),
            GroupFanout.resolve(database, "g_nothing").map { it.deviceId }
        )
    }
}
