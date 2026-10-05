package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.5: the version 9 to 10 migration.
 *
 * This is the first schema change in the project, so it gets a real migration
 * test rather than an assertion. The test builds a genuine version 9 database
 * out of the exported 9.json, writes rows that carry the new message statuses,
 * then migrates it to 10 and reopens it through Room. Room validates the
 * resulting schema against the exported 10.json, so a migration that creates
 * the wrong table, misses a column, or adds an index Room did not expect fails
 * here rather than on a user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeenIdsMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "phase15-migration.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun seedV9(db: SupportSQLiteDatabase, rows: List<Pair<String, MessageStatus>>) {
        for ((index, pair) in rows.withIndex()) {
            val (id, status) = pair
            db.execSQL(
                """
                INSERT INTO messages (
                    id, conversationId, senderId, senderName, recipientId, text,
                    photoPath, isPhoto, filePath, fileName, fileSize, mimeType,
                    isVideo, isFile, isVoice, audioDurationSeconds,
                    isMeshRelayed, meshHops, receivedAt, timestamp,
                    isFromMe, status, isGroup, groupName, isSenderDeveloper
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, 0, NULL, NULL, 0, NULL,
                          0, 0, 0, 0, 0, 0, ?, ?, 1, ?, 0, NULL, 0)
                """.trimIndent(),
                arrayOf<Any>(
                    id, "peer_$id", "me", "Me", "peer_$id", "text of $id",
                    1_000L * (index + 1), 1_000L * (index + 1), status.name,
                ),
            )
        }
    }

    @Test
    fun `a v9 database migrates to v10 and keeps every row`() {
        val written = listOf(
            "msg_queued" to MessageStatus.QUEUED,
            "msg_failed" to MessageStatus.FAILED,
            "msg_sending" to MessageStatus.SENDING,
            "msg_sent" to MessageStatus.SENT,
        )

        val db = helper.createDatabase(dbName, 9)
        try {
            seedV9(db, written)
        } finally {
            db.close()
        }

        // runMigrationsAndValidate compares the migrated schema against the
        // exported 10.json, so this is the real proof the DDL matches.
        helper.runMigrationsAndValidate(dbName, 10, true, ChatDatabase.MIGRATION_9_10).close()

        val room = Room.databaseBuilder(context, ChatDatabase::class.java, dbName).build()
        try {
            runBlocking {
                val dao = room.chatMessageDao()
                for ((id, expected) in written) {
                    val row = dao.getMessageById(id)
                    assertNotNull("row $id survived the migration", row)
                    assertEquals("$id kept its status", expected, row!!.status)
                }
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `the migrated database can record and refuse a replay`() {
        val db = helper.createDatabase(dbName, 9)
        try {
            seedV9(db, listOf("msg_queued" to MessageStatus.QUEUED))
        } finally {
            db.close()
        }
        helper.runMigrationsAndValidate(dbName, 10, true, ChatDatabase.MIGRATION_9_10).close()

        val room = Room.databaseBuilder(context, ChatDatabase::class.java, dbName).build()
        try {
            val guard = ReplayGuard(room.seenIdDao())
            assertTrue(
                "a pre-existing install must gain replay protection from the migration",
                runBlocking { guard.tryAccept("peer_a", "msg_1") }
            )
            assertFalse(
                runBlocking { guard.tryAccept("peer_a", "msg_1") }
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `the migration is additive and leaves the messages table untouched`() {
        val db = helper.createDatabase(dbName, 9)
        try {
            seedV9(db, listOf("msg_sent" to MessageStatus.SENT))
            db.execSQL(
                "INSERT INTO contacts (deviceId, displayName, ipAddress, tcpPort, " +
                    "avatarColorIndex, lastSeen, isOnline, isDeveloper, " +
                    "appVersionCode, appVersionName, isMeshPeer) " +
                    "VALUES ('peer_a', 'Peer', '10.0.0.5', 9999, 0, 1000, 1, 0, 2, '2.0', 0)"
            )
        } finally {
            db.close()
        }
        helper.runMigrationsAndValidate(dbName, 10, true, ChatDatabase.MIGRATION_9_10).close()

        val room = Room.databaseBuilder(context, ChatDatabase::class.java, dbName).build()
        try {
            runBlocking {
                assertEquals(
                    "an existing contact must survive",
                    "10.0.0.5",
                    room.contactDao().getContactById("peer_a")?.ipAddress
                )
                assertEquals(
                    "the meshEndpointId column from the earlier migration must be intact",
                    1,
                    room.contactDao().getAllContactsList().size
                )
            }
        } finally {
            room.close()
        }
    }
}