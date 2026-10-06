package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * BUG 5 (الجزء الثاني): ترقية قاعدة البيانات من 7 لـ 8 بتضيف عمود `receivedAt`
 * وبتملأه بـ `timestamp` عشان الرسائل القديمة ترتّب زي ما كانت من غير ما تضيع.
 * الاختبار ده بيبني ملف v7 حقيقي على القرص، بيفتحه بـ Room (فـ MIGRATION_7_8
 * هي اللي بتشتغل فعلاً)، وبعدين بيتأكد إن الترتيب بقى على وقت الوصول المحلي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatDatabaseMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var databaseFile: File? = null
    private var opened: ChatDatabase? = null

    @After
    fun tearDown() {
        opened?.close()
        opened = null
        databaseFile?.delete()
        databaseFile = null
    }

    /** نفس DDL اللي Room بيولّده للنسخة 8، ناقصة عمود `receivedAt` بتاع v7. */
    private val version7MessagesTable = """
        CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `senderName` TEXT NOT NULL, `recipientId` TEXT NOT NULL, `text` TEXT NOT NULL, `photoPath` TEXT, `isPhoto` INTEGER NOT NULL, `filePath` TEXT, `fileName` TEXT, `fileSize` INTEGER NOT NULL, `mimeType` TEXT, `isVideo` INTEGER NOT NULL, `isFile` INTEGER NOT NULL, `isVoice` INTEGER NOT NULL, `audioDurationSeconds` INTEGER NOT NULL, `isMeshRelayed` INTEGER NOT NULL, `meshHops` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `isFromMe` INTEGER NOT NULL, `status` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, `groupName` TEXT, `isSenderDeveloper` INTEGER NOT NULL, PRIMARY KEY(`id`))
    """.trimIndent()

    private fun createVersion7Database(
        file: File,
        messages: List<Triple<String, Long, Boolean>>
    ) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(version7MessagesTable)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contacts` (`deviceId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `ipAddress` TEXT NOT NULL, `tcpPort` INTEGER NOT NULL, `avatarPath` TEXT, `avatarColorIndex` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, `isOnline` INTEGER NOT NULL, `customNickname` TEXT, `isDeveloper` INTEGER NOT NULL, `appVersionCode` INTEGER NOT NULL, `appVersionName` TEXT NOT NULL, `isMeshPeer` INTEGER NOT NULL, `publicKeyBase64` TEXT, PRIMARY KEY(`deviceId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `groups` (`groupId` TEXT NOT NULL, `groupName` TEXT NOT NULL, `description` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `avatarColorIndex` INTEGER NOT NULL, PRIMARY KEY(`groupId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `discovered_peers` (`deviceId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `endpointId` TEXT NOT NULL, `ipAddress` TEXT, `publicKeyBase64` TEXT, `avatarColorIndex` INTEGER NOT NULL, `isDeveloper` INTEGER NOT NULL, `appVersionCode` INTEGER NOT NULL, `transportIsLan` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, PRIMARY KEY(`deviceId`))"
        )
        for ((id, timestamp, isFromMe) in messages) {
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderId, senderName, recipientId, text, " +
                    "photoPath, isPhoto, filePath, fileName, fileSize, mimeType, isVideo, isFile, isVoice, " +
                    "audioDurationSeconds, isMeshRelayed, meshHops, timestamp, isFromMe, status, isGroup, " +
                    "groupName, isSenderDeveloper) VALUES " +
                    "('$id', 'peer_1', 'peer_1', 'Peer', 'me_1', 'msg $id', NULL, 0, NULL, NULL, 0, NULL, " +
                    "0, 0, 0, 0, 0, 0, $timestamp, ${if (isFromMe) 1 else 0}, 'DELIVERED', 0, NULL, 0)"
            )
        }
        db.version = 7
        db.close()
    }

    private fun openWithRoom(file: File): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, file.absolutePath)
            .addMigrations(
                ChatDatabase.MIGRATION_5_6,
                ChatDatabase.MIGRATION_6_7,
                ChatDatabase.MIGRATION_7_8,
                ChatDatabase.MIGRATION_8_9,
        ChatDatabase.MIGRATION_9_10,
        ChatDatabase.MIGRATION_10_11,
        ChatDatabase.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()
            .also { opened = it }

    private fun version7File(name: String): File {
        val file = File(context.getDatabasePath(name).absolutePath)
        file.parentFile?.mkdirs()
        file.delete()
        databaseFile = file
        return file
    }

    @Test
    fun `migration adds receivedAt and backfills it from timestamp`() = runBlocking {
        val file = version7File("migration_backfill.db")
        createVersion7Database(file, listOf(Triple("old_a", 1_000L, false), Triple("old_b", 2_000L, true)))

        val database = openWithRoom(file)
        val dao = database.chatMessageDao()

        // The current schema version, not a literal: this test is about the
    // migration that ran, not about which number the version happens to be.
    assertEquals(SCHEMA_VERSION, database.openHelper.readableDatabase.version)
        assertEquals(1_000L, dao.getMessageById("old_a")?.receivedAt)
        assertEquals(2_000L, dao.getMessageById("old_b")?.receivedAt)
        assertEquals(
            "no message may be lost or reordered by the upgrade",
            listOf("old_a", "old_b"),
            dao.getMessagesForConversation("peer_1").first().map { it.id }
        )
    }

    @Test
    fun `upgraded rows order by arrival time so a future sender clock cannot jump ahead`() = runBlocking {
        val file = version7File("migration_order.db")
        createVersion7Database(file, listOf(Triple("old_msg", 1_000L, false)))

        val database = openWithRoom(file)
        val dao = database.chatMessageDao()
        dao.insertMessage(
            ChatMessageEntity(
                id = "from_skewed_peer",
                conversationId = "peer_1",
                senderId = "peer_1",
                senderName = "Peer",
                recipientId = "me_1",
                text = "sent from a device whose clock is broken",
                receivedAt = 2_000L,
                timestamp = 4_102_444_800_000L,
                isFromMe = false
            )
        )

        assertEquals(
            listOf("old_msg", "from_skewed_peer"),
            dao.getMessagesForConversation("peer_1").first().map { it.id }
        )
        assertEquals("from_skewed_peer", dao.getLastMessageForConversation("peer_1").first()?.id)
    }

    @Test
    fun `a new incoming row after the upgrade records arrival time independently of the sender clock`() = runBlocking {
        val file = version7File("migration_new_rows.db")
        createVersion7Database(file, listOf(Triple("old_msg", 1_000L, false)))

        val database = openWithRoom(file)
        val dao = database.chatMessageDao()
        val before = System.currentTimeMillis()
        dao.insertMessage(
            ChatMessageEntity(
                id = "fresh_msg",
                conversationId = "peer_1",
                senderId = "peer_1",
                senderName = "Peer",
                recipientId = "me_1",
                text = "arrives now",
                timestamp = 4_102_444_800_000L,
                isFromMe = false
            )
        )

        val stored = dao.getMessageById("fresh_msg")
        assertEquals(4_102_444_800_000L, stored?.timestamp)
        assertTrue("arrival time must be local, not the sender clock", (stored?.receivedAt ?: 0L) >= before)
        assertEquals("fresh_msg", dao.getLastMessageForConversation("peer_1").first()?.id)
    }
}
