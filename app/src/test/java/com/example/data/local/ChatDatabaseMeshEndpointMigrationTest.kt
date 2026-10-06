package com.example.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * WS-1: قبل v9 كان_contact بينحفظ عنوانه كـ "p2p-<endpointId>" وده كان بيمنع
 * أي محاولة LAN خالص. دلوقتي الـ endpoint بيتخزّن في عمود مستقل
 * `meshEndpointId` والعنوان الحقيقي بيفضل محفوظ، والـ migration دي بتطلّع
 * الـ endpoint من العناوين القديمة عشان ما نضيّعش الوصول ليهم.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatDatabaseMeshEndpointMigrationTest {

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

    private val version8MessagesTable = """
        CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `senderName` TEXT NOT NULL, `recipientId` TEXT NOT NULL, `text` TEXT NOT NULL, `photoPath` TEXT, `isPhoto` INTEGER NOT NULL, `filePath` TEXT, `fileName` TEXT, `fileSize` INTEGER NOT NULL, `mimeType` TEXT, `isVideo` INTEGER NOT NULL, `isFile` INTEGER NOT NULL, `isVoice` INTEGER NOT NULL, `audioDurationSeconds` INTEGER NOT NULL, `isMeshRelayed` INTEGER NOT NULL, `meshHops` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `isFromMe` INTEGER NOT NULL, `status` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, `groupName` TEXT, `isSenderDeveloper` INTEGER NOT NULL, PRIMARY KEY(`id`))
    """.trimIndent()

    private val version8ContactsTable =
        "CREATE TABLE IF NOT EXISTS `contacts` (`deviceId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
            "`ipAddress` TEXT NOT NULL, `tcpPort` INTEGER NOT NULL, `avatarPath` TEXT, " +
            "`avatarColorIndex` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, `isOnline` INTEGER NOT NULL, " +
            "`customNickname` TEXT, `isDeveloper` INTEGER NOT NULL, `appVersionCode` INTEGER NOT NULL, " +
            "`appVersionName` TEXT NOT NULL, `isMeshPeer` INTEGER NOT NULL, `publicKeyBase64` TEXT, " +
            "PRIMARY KEY(`deviceId`))"

    private fun createVersion8Database(file: File, contacts: List<Triple<String, String, Boolean>>) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(version8MessagesTable)
        db.execSQL(version8ContactsTable)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `groups` (`groupId` TEXT NOT NULL, `groupName` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`avatarColorIndex` INTEGER NOT NULL, PRIMARY KEY(`groupId`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `discovered_peers` (`deviceId` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`endpointId` TEXT NOT NULL, `ipAddress` TEXT, `publicKeyBase64` TEXT, " +
                "`avatarColorIndex` INTEGER NOT NULL, `isDeveloper` INTEGER NOT NULL, " +
                "`appVersionCode` INTEGER NOT NULL, `transportIsLan` INTEGER NOT NULL, " +
                "`lastSeen` INTEGER NOT NULL, PRIMARY KEY(`deviceId`))"
        )
        for ((deviceId, ipAddress, isMeshPeer) in contacts) {
            db.execSQL(
                "INSERT INTO contacts (deviceId, displayName, ipAddress, tcpPort, avatarPath, " +
                    "avatarColorIndex, lastSeen, isOnline, customNickname, isDeveloper, appVersionCode, " +
                    "appVersionName, isMeshPeer, publicKeyBase64) VALUES " +
                    "('$deviceId', 'Peer $deviceId', '$ipAddress', 9999, NULL, 0, 1000, 1, NULL, 0, 2, " +
                    "'2.0', ${if (isMeshPeer) 1 else 0}, NULL)"
            )
        }
        db.version = 8
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

    private fun version8File(name: String): File {
        val file = File(context.getDatabasePath(name).absolutePath)
        file.parentFile?.mkdirs()
        file.delete()
        databaseFile = file
        return file
    }

    private suspend fun contactById(database: ChatDatabase, deviceId: String): ContactEntity? =
        database.contactDao().getAllContactsList().firstOrNull { it.deviceId == deviceId }

    @Test
    fun `migration extracts the endpoint id from a legacy p2p address`() = kotlinx.coroutines.runBlocking {
        val file = version8File("migration_endpoint_from_p2p.db")
        createVersion8Database(
            file,
            listOf(Triple("mesh_peer", "p2p-AB12CD34", true))
        )

        val database = openWithRoom(file)

        // The current schema version, not a literal: this test is about the
    // migration that ran, not about which number the version happens to be.
    assertEquals(SCHEMA_VERSION, database.openHelper.readableDatabase.version)
        val contact = contactById(database, "mesh_peer")
        assertEquals("AB12CD34", contact?.meshEndpointId)
    }

    @Test
    fun `migration leaves a real lan address without an endpoint`() = kotlinx.coroutines.runBlocking {
        val file = version8File("migration_endpoint_lan_untouched.db")
        createVersion8Database(
            file,
            listOf(Triple("lan_peer", "192.168.0.113", false))
        )

        val database = openWithRoom(file)

        val contact = contactById(database, "lan_peer")
        assertEquals("192.168.0.113", contact?.ipAddress)
        assertNull(contact?.meshEndpointId)
    }

    @Test
    fun `migration keeps every contact row and the lan address intact`() = kotlinx.coroutines.runBlocking {
        val file = version8File("migration_endpoint_no_loss.db")
        createVersion8Database(
            file,
            listOf(
                Triple("mesh_a", "p2p-AAA", true),
                Triple("lan_b", "10.0.0.7", false),
                Triple("mesh_c", "p2p-BBB", true),
            )
        )

        val database = openWithRoom(file)
        val all = database.contactDao().getAllContactsList()

        assertEquals("no contact may be lost by the upgrade", 3, all.size)
        assertEquals("p2p-AAA", all.first { it.deviceId == "mesh_a" }.ipAddress)
        assertEquals("10.0.0.7", all.first { it.deviceId == "lan_b" }.ipAddress)
        assertEquals("BBB", all.first { it.deviceId == "mesh_c" }.meshEndpointId)
    }
}
