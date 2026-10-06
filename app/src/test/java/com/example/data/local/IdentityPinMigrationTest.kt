package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.6: the version 11 to 12 migration, and the pin it backfills.
 *
 * The backfill is the part worth proving. An install upgrading from before 1.6
 * already has contacts with a public key, and those have to arrive at version 12
 * already pinned to that same key. If the backfill did not run, every existing
 * contact would come back unpinned, which under the new rules means not trusted,
 * and the user's existing contacts would silently stop working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdentityPinMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "phase16-pin.db"

    private val knownKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEknown0000000000000000000000000000000000="
    private val otherKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEother0000000000000000000000000000000000="

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun insertV11Contact(deviceId: String, publicKey: String?) {
        val db = helper.createDatabase(dbName, 11)
        try {
            db.execSQL(
                "INSERT INTO contacts (deviceId, displayName, ipAddress, tcpPort, " +
                    "avatarColorIndex, lastSeen, isOnline, isDeveloper, " +
                    "appVersionCode, appVersionName, isMeshPeer, publicKeyBase64) " +
                    "VALUES (?, 'Peer', '10.0.0.5', 9999, 0, 1000, 1, 0, 2, '2.0', 0, ?)",
                arrayOf<Any?>(deviceId, publicKey)
            )
        } finally {
            db.close()
        }
    }

    private fun migrateToCurrent(): ChatDatabase {
        helper.runMigrationsAndValidate(
            dbName, SCHEMA_VERSION, true,
            ChatDatabase.MIGRATION_11_12,
            ChatDatabase.MIGRATION_12_13,
            ChatDatabase.MIGRATION_13_14,
        ).close()
        return Room.databaseBuilder(context, ChatDatabase::class.java, dbName).build()
    }

    @Test
    fun `an existing contact is pinned to the key it already had`() = runBlocking {
        insertV11Contact("peer_a", knownKey)

        val room = migrateToCurrent()
        try {
            val contact = room.contactDao().getContactById("peer_a")
            assertEquals(
                "an install upgrading from before 1.6 must arrive already pinned, " +
                    "or every existing contact silently stops being trusted",
                knownKey,
                contact?.pinnedPublicKey
            )
            assertFalse(
                "nothing has changed yet, so no key-change block",
                contact!!.hasKeyChanged
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `a contact that never had a key comes back unpinned rather than pinned to nothing`() = runBlocking {
        insertV11Contact("peer_b", null)

        val room = migrateToCurrent()
        try {
            val contact = room.contactDao().getContactById("peer_b")
            assertEquals(null, contact?.pinnedPublicKey)
            assertFalse(contact!!.hasKeyChanged)
        } finally {
            room.close()
        }
    }

    @Test
    fun `the device id is unchanged by the migration`() = runBlocking {
        insertV11Contact("peer_c", knownKey)

        val room = migrateToCurrent()
        try {
            assertEquals(
                "conversationId is derived from deviceId, so changing it would make " +
                    "every contact see a different person and lose all history",
                "peer_c",
                room.contactDao().getContactById("peer_c")?.deviceId
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `messages and counters survive the migration`() = runBlocking {
        val db = helper.createDatabase(dbName, 11)
        try {
            db.execSQL(
                "INSERT INTO messages (" +
                    "id, conversationId, senderId, senderName, recipientId, text, " +
                    "photoPath, isPhoto, filePath, fileName, fileSize, mimeType, " +
                    "isVideo, isFile, isVoice, audioDurationSeconds, " +
                    "isMeshRelayed, meshHops, receivedAt, timestamp, " +
                    "isFromMe, status, isGroup, groupName, isSenderDeveloper" +
                    ") VALUES ('msg_1', 'peer_a', 'me', 'Me', 'peer_a', 'hi', " +
                    "NULL, 0, NULL, NULL, 0, NULL, 0, 0, 0, 0, 0, 0, 1000, 1000, " +
                    "1, 'SENT', 0, NULL, 0)"
            )
            db.execSQL(
                "INSERT INTO peer_counters (peerDeviceId, outgoingCounter, highWaterMark) " +
                    "VALUES ('peer_a', 42, 17)"
            )
        } finally {
            db.close()
        }

        val room = migrateToCurrent()
        try {
            assertTrue(room.chatMessageDao().getMessageById("msg_1") != null)
            assertEquals(42L, room.peerCounterDao().outgoingCounter("peer_a"))
            assertEquals(17L, room.peerCounterDao().highWaterMark("peer_a"))
        } finally {
            room.close()
        }
    }

    @Test
    fun `a contact presenting a different key is blocked until the user accepts`() = runBlocking {
        insertV11Contact("peer_d", knownKey)

        val room = migrateToCurrent()
        try {
            val dao = room.contactDao()
            assertTrue(
                "the pin blocks nothing while the key still matches",
                com.example.data.security.IdentityPin
                    .evaluate(dao.getContactById("peer_d")!!.pinnedPublicKey, knownKey)
                    .allowsTraffic()
            )

            // The peer comes back with a different key.
            dao.updatePublicKey("peer_d", otherKey)
            val updated = dao.getContactById("peer_d")!!

            assertTrue("a differing key must set the block", updated.hasKeyChanged)
            assertFalse(
                "and traffic must be refused until the user decides",
                com.example.data.security.IdentityPin
                    .evaluate(updated.pinnedPublicKey, updated.publicKeyBase64)
                    .allowsTraffic()
            )
        } finally {
            room.close()
        }
    }
}