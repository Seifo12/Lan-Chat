package com.example.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: schema 13 to 14, and the legacy flag it writes.
 *
 * Every group that exists before this migration is unattributable by
 * construction: it has no member list, and its createdBy column holds a
 * display name rather than a device id. None of them can ever be given a
 * signed member list, so the migration marks them all legacy. Legacy means
 * readable history that can never become writable, and nothing is deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupMigration13To14Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "phase18-groups.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun seedV13() {
        val db = helper.createDatabase(dbName, 13)
        try {
            db.execSQL(
                "INSERT INTO contacts (deviceId, displayName, ipAddress, tcpPort, " +
                    "avatarColorIndex, lastSeen, isOnline, isDeveloper, appVersionCode, " +
                    "appVersionName, isMeshPeer, publicKeyBase64, pinnedPublicKey, " +
                    "hasKeyChanged, verifiedAt) " +
                    "VALUES ('peer_pinned', 'Pinned', '10.0.0.1', 9999, 0, 1000, 1, 0, 3, " +
                    "'3.0.0', 0, 'pk_pinned', 'pk_pinned', 0, 1700)"
            )
            db.execSQL(
                "INSERT INTO contacts (deviceId, displayName, ipAddress, tcpPort, " +
                    "avatarColorIndex, lastSeen, isOnline, isDeveloper, appVersionCode, " +
                    "appVersionName, isMeshPeer, publicKeyBase64, pinnedPublicKey, " +
                    "hasKeyChanged, verifiedAt) " +
                    "VALUES ('peer_changed', 'Changed', '10.0.0.2', 9999, 0, 1000, 1, 0, 3, " +
                    "'3.0.0', 0, 'pk_new', 'pk_old', 1, NULL)"
            )
            db.execSQL(
                "INSERT INTO groups (groupId, groupName, description, createdBy, " +
                    "createdAt, avatarColorIndex) " +
                    "VALUES ('group_legacy_a', 'Legacy A', 'desc', 'Someone', 1000, 3)"
            )
            db.execSQL(
                "INSERT INTO groups (groupId, groupName, description, createdBy, " +
                    "createdAt, avatarColorIndex) " +
                    "VALUES ('group_legacy_b', 'Legacy B', '', 'Someone Else', 2000, 0)"
            )
        } finally {
            db.close()
        }
    }

    private fun migrate(): ChatDatabase {
        helper.runMigrationsAndValidate(
            dbName, SCHEMA_VERSION, true,
            ChatDatabase.MIGRATION_13_14,
        ).close()
        return androidx.room.Room.databaseBuilder(context, ChatDatabase::class.java, dbName).build()
    }

    @Test
    fun `every pre-existing group is marked legacy and nothing is deleted`() = runBlocking {
        seedV13()
        val room = migrate()
        try {
            val dao = room.groupDao()
            val a = dao.getGroupById("group_legacy_a")
            val b = dao.getGroupById("group_legacy_b")
            assertTrue("pre-existing groups are legacy", a!!.isLegacy)
            assertTrue(b!!.isLegacy)
            // History is intact: name, description, creator string, colour.
            assertEquals("Legacy A", a.groupName)
            assertEquals("desc", a.description)
            assertEquals("Someone", a.createdBy)
            assertEquals(3, a.avatarColorIndex)
            assertEquals(1000L, a.createdAt)
            // There is no signed member list, so no version and no creator device.
            assertEquals(0L, a.memberListVersion)
            assertNull(a.creatorDeviceId)
        } finally {
            room.close()
        }
    }

    @Test
    fun `the identity pin and the key-change block survive the migration`() = runBlocking {
        seedV13()
        val room = migrate()
        try {
            val dao = room.contactDao()
            val pinned = dao.getContactById("peer_pinned")!!
            assertEquals("pk_pinned", pinned.pinnedPublicKey)
            assertFalse(pinned.hasKeyChanged)
            assertEquals(1700L, pinned.verifiedAt)

            val changed = dao.getContactById("peer_changed")!!
            assertEquals("pk_old", changed.pinnedPublicKey)
            assertTrue(
                "the key-change block is security state; clearing it would " +
                    "silently unblock a key the user never agreed to",
                changed.hasKeyChanged
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `the new tables exist and start empty`() = runBlocking {
        seedV13()
        val room = migrate()
        try {
            val dao = room.groupMembershipDao()
            assertEquals(emptyList<GroupMemberEntity>(), dao.membersOf("group_legacy_a"))
            assertNull(dao.getInvite("group_legacy_a", "any-nonce"))
        } finally {
            room.close()
        }
    }

    @Test
    fun `members and invites round-trip`() = runBlocking {
        seedV13()
        val room = migrate()
        try {
            val dao = room.groupMembershipDao()
            dao.replaceMembers(
                "g_new",
                listOf(
                    GroupMemberEntity("g_new", "peer_pinned", "pk_pinned", "Pinned"),
                    GroupMemberEntity("g_new", "peer_stranger", "pk_vouched", "Stranger"),
                )
            )
            val members = dao.membersOf("g_new")
            assertEquals(2, members.size)
            assertEquals(listOf("peer_pinned", "peer_stranger"), members.map { it.deviceId })
            assertTrue(dao.isMember("g_new", "peer_stranger"))
            assertFalse(dao.isMember("g_new", "peer_absent"))

            dao.insertInvite(
                GroupInviteEntity(
                    groupId = "g_new", nonce = "n1", creatorId = "peer_pinned",
                    groupName = "New", memberListVersion = 3L, expiry = 1700,
                    rawBytes = "{\"creatorId\":\"peer_pinned\"}".toByteArray(),
                )
            )
            val invite = dao.getInvite("g_new", "n1")!!
            assertEquals(InviteState.PENDING, invite.state)
            assertEquals(3L, invite.memberListVersion)
            assertEquals("{\"creatorId\":\"peer_pinned\"}", invite.rawBytes.toString(Charsets.UTF_8))
        } finally {
            room.close()
        }
    }
}