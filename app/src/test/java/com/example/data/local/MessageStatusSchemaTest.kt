package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Step 1.1.
 *
 * `MessageStatus` gained QUEUED and FAILED and that is the entire change. In the
 * exported v9 schema `status` is `TEXT NOT NULL` with no TypeConverter, so Room
 * never stored it as an integer and a new enum constant cannot move the schema.
 * The database therefore stays at version 9 and no migration is invented.
 *
 * This test is the evidence for that claim rather than an assertion of it: it
 * builds a real version 9 database out of the exported 9.json, writes rows
 * carrying the new statuses, then opens that same file with the current schema
 * and no migrations at all. Room validates the stored schema against the
 * generated one on open, so a mismatch would throw IllegalStateException and
 * fail this test.
 *
 * The real migrations arrive with 1.5 (`seen_ids`) and 1.6 (the pin columns).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageStatusSchemaTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "step11-no-migration.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `a v9 database reopens against the current schema with no migration`() {
        val written = listOf(
            "msg_queued" to MessageStatus.QUEUED,
            "msg_failed" to MessageStatus.FAILED,
            "msg_sending" to MessageStatus.SENDING,
            "msg_sent" to MessageStatus.SENT,
        )

        helper.createDatabase(dbName, 9).let { db ->
            try {
                seed(db, written)
            } finally {
                db.close()
            }
        }

        // No migrations passed on purpose: the current schema must match v9.
        val room = Room.databaseBuilder(context, ChatDatabase::class.java, dbName).build()
        try {
            runBlocking {
                val dao = room.chatMessageDao()
                for ((id, expected) in written) {
                    val row = dao.getMessageById(id)
                    assertNotNull("row $id survived the reopen", row)
                    assertEquals("$id kept its status", expected, row!!.status)
                }
                // The pending worker selects SENDING only, so it must pick up the
                // one SENDING row and leave QUEUED, FAILED and SENT alone.
                val pending = dao.getAllPendingDirectMessages()
                assertEquals(listOf("msg_sending"), pending.map { it.id })
            }
        } finally {
            room.close()
        }
    }

    /**
     * The status column is TEXT, so QUEUED and FAILED are stored and read back as
     * their enum names rather than ordinals. This is the property that makes a
     * migration unnecessary, asserted directly against the v9 schema.
     */
    @Test
    fun `status is stored as text in v9 so new constants need no migration`() {
        val db = helper.createDatabase(dbName, 9)
        try {
            seed(db, listOf("msg_queued" to MessageStatus.QUEUED))

            val cursor = db.query("SELECT status FROM messages WHERE id = 'msg_queued'")
            try {
                assertTrue(cursor.moveToFirst())
                assertEquals(MessageStatus.QUEUED.name, cursor.getString(0))
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
        }
    }

    /** Seeds rows using every NOT NULL column, matching the exported v9 DDL. */
    private fun seed(db: SupportSQLiteDatabase, rows: List<Pair<String, MessageStatus>>) {
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
}