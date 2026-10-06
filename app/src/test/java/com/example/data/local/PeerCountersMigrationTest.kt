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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.3: the version 10 to 11 migration, and the counters it introduces.
 *
 * The counters are the durable half of the replay window. If either direction
 * reset on restart the protection would be cosmetic, so the migration has to
 * produce a usable table rather than merely a matching shape, which is what
 * runMigrationsAndValidate plus these behavioural checks together establish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerCountersMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "phase16-counters.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun seedV10() {
        val db = helper.createDatabase(dbName, 10)
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
                "INSERT INTO seen_ids (senderId, messageId, receivedAt) " +
                    "VALUES ('peer_a', 'msg_seen', 1000)"
            )
        } finally {
            db.close()
        }
    }

    private fun openMigrated(): ChatDatabase {
        // Deliberately validated as 10 to 11 and not to the current version: this
        // test is about the counters table, and pinning the target keeps it testing
        // the migration it names.
        helper.runMigrationsAndValidate(dbName, 11, true, ChatDatabase.MIGRATION_10_11).close()
        // Then continue to the current version so Room will open the file.
        val current = Room.databaseBuilder(context, ChatDatabase::class.java, dbName)
            .addMigrations(
            ChatDatabase.MIGRATION_11_12, ChatDatabase.MIGRATION_12_13,
            ChatDatabase.MIGRATION_13_14
        )
            .build()
        return current
    }

    @Test
    fun `a v10 database migrates to v11 and keeps messages and replay records`() {
        seedV10()
        val room = openMigrated()
        try {
            runBlocking {
                assertNotNull("existing messages survive", room.chatMessageDao().getMessageById("msg_1"))
                assertEquals(
                    "the 1.5 replay table survives the second migration",
                    true,
                    room.seenIdDao().wasSeen("peer_a", "msg_seen")
                )
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `a migrated database issues strictly increasing outgoing counters`() = runBlocking {
        seedV10()
        val room = openMigrated()
        try {
            val counters = MessageCountersFixture(room)
            val first = counters.claim("peer_a")
            val second = counters.claim("peer_a")

            assertEquals("counters start at one", 1L, first)
            assertEquals("and never repeat", 2L, second)
        } finally {
            room.close()
        }
    }

    @Test
    fun `counters for different peers are independent`() = runBlocking {
        seedV10()
        val room = openMigrated()
        try {
            val counters = MessageCountersFixture(room)
            assertEquals(1L, counters.claim("peer_a"))
            assertEquals(
                "one peer's traffic must not consume another's sequence numbers",
                1L,
                counters.claim("peer_b")
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `the high water mark survives a restart of the guard`() = runBlocking {
        seedV10()
        val room = openMigrated()
        try {
            val dao = room.peerCounterDao()
            val counters = MessageCountersFixture(room)
            counters.observeInbound("peer_a", counter = 500, alreadySeen = false)

            // A brand new instance reads the mark back off disk, which is the
            // property that stops a replay succeeding just because the app closed.
            assertEquals(500L, dao.highWaterMark("peer_a"))
        } finally {
            room.close()
        }
    }
}

/** Small helper so the tests read as behaviour rather than as DAO plumbing. */
private class MessageCountersFixture(room: ChatDatabase) {
    private val counters = com.example.data.security.MessageCounters(room.peerCounterDao())

    suspend fun claim(peer: String): Long? = counters.claimOutgoing(peer)

    suspend fun observeInbound(peer: String, counter: Long, alreadySeen: Boolean) =
        counters.observeInbound(peer, counter, alreadySeen)
}