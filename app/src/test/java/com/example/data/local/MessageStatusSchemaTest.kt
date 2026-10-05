package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.1, still true after 1.5 moved the schema to version 10.
 *
 * `MessageStatus` gained QUEUED and FAILED, and that was the whole change. In
 * the exported schema `status` is TEXT with no TypeConverter, so Room never
 * stored it as an integer and a new enum constant cannot move the schema.
 *
 * The database has since advanced to version 10 for an unrelated reason
 * (1.5 added the seen_ids replay table), so this can no longer be shown by
 * opening a v9 file with no migrations. Instead it is shown directly: the
 * messages table definition is byte-identical between the exported 9.json and
 * 10.json. If a future change ever does touch the messages table, this fails.
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
    private val dbName = "step11-schema.db"

    private fun exportedSchema(version: Int): JSONObject {
        val path = "com.example.data.local.ChatDatabase/$version.json"
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(path).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    }

    private fun createSqlFor(schema: JSONObject, table: String): String {
        val entities = schema.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == table) return entity.getString("createSql")
        }
        throw AssertionError("table $table is absent from the exported schema")
    }

    /**
     * The claim itself: adding QUEUED and FAILED changed nothing about the
     * messages table, so it needed no migration. Proven by comparing the two
     * exported schemas rather than by assertion.
     */
    @Test
    fun `the messages table is unchanged between version 9 and version 10`() {
        val atV9 = createSqlFor(exportedSchema(9), "messages")
        val atV10 = createSqlFor(exportedSchema(10), "messages")

        assertEquals(
            "QUEUED and FAILED must not have altered the messages table; if they " +
                "ever do, the database version has to move for that reason and this " +
                "test should be rewritten to say so",
            atV9,
            atV10
        )
    }

    @Test
    fun `version 10 differs from version 9 only by the seen_ids table`() {
        fun tables(version: Int): Set<String> {
            val entities = exportedSchema(version).getJSONObject("database")
                .getJSONArray("entities")
            return (0 until entities.length())
                .map { entities.getJSONObject(it).getString("tableName") }
                .toSet()
        }

        assertEquals(
            "the only table 1.5 adds is the replay record",
            setOf("seen_ids"),
            tables(10) - tables(9)
        )
    }

    @Test
    fun `status is declared TEXT so new enum constants need no migration`() {
        val sql = createSqlFor(exportedSchema(10), "messages")
        assertTrue(
            "status must stay TEXT for the no-migration claim to hold, was: $sql",
            // Room quotes identifiers with backticks in its exported DDL.
            sql.contains("`status` TEXT NOT NULL")
        )
    }

    /**
     * Behavioural counterpart: a row carrying the new statuses survives a round
     * trip through a real database. The migration path itself is covered by
     * SeenIdsMigrationTest.
     */
    @Test
    fun `QUEUED and FAILED round-trip through the current schema`() {
        context.deleteDatabase(dbName)
        val room = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val dao = room.chatMessageDao()
                for (status in listOf(MessageStatus.QUEUED, MessageStatus.FAILED)) {
                    val id = "msg_${status.name.lowercase()}"
                    dao.insertMessage(
                        ChatMessageEntity(
                            id = id, conversationId = "peer_a", senderId = "peer_a",
                            senderName = "Peer", recipientId = "me", text = id,
                            isFromMe = false, status = status,
                        )
                    )
                    val row = dao.getMessageById(id)
                    assertNotNull("row $id exists", row)
                    assertEquals(status, row!!.status)
                }
            }
        } finally {
            room.close()
        }
    }
}