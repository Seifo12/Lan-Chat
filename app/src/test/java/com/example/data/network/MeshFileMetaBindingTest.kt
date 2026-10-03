package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.UserPreferences
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BUG 3: ميتا الملفات كانت بتتخزّن under `messageId.hashCode().toLong()` وبتتنقرأ
 * under `payload.id` بتاع Nearby، فالمفتاحَين عمرهم ما يطابقوا. النتيجة إن `meta`
 * كانت null دايماً و `finalizeReceivedFile` ما كنتش بتتنفّذ: أي ملف/صورة/فيديو
 * مبعوت عبر MESH كان بيضيع بصمت. الإصلاح ربط الميتا بالـ payload وقت وصوله.
 *
 * الاختبارات هنا بتFeed الـ callbacks بتاعت Nearby نفسها (مش double) عشان نتحقق
 * من الربط والتنظيف بالمفتاح الحقيقي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MeshFileMetaBindingTest {

    private lateinit var context: Context
    private lateinit var database: ChatDatabase
    private lateinit var meshManager: NearbyMeshManager
    private var transferFile: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val preferences = UserPreferences(context)
        val tcpMessaging = TcpMessagingManager(context, database, preferences)
        meshManager = NearbyMeshManager(context, database, preferences, tcpMessaging)
    }

    @After
    fun tearDown() {
        transferFile?.delete()
        transferFile = null
        database.close()
    }

    private fun readPrivateField(name: String): Any {
        val field = NearbyMeshManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(meshManager) ?: error("field $name is null")
    }

    @Suppress("UNCHECKED_CAST")
    private val payloadCallback: PayloadCallback get() = readPrivateField("payloadCallback") as PayloadCallback

    @Suppress("UNCHECKED_CAST")
    private val incomingFileMetaMap: ConcurrentHashMap<Long, Any>
        get() = readPrivateField("incomingFileMetaMap") as ConcurrentHashMap<Long, Any>

    @Suppress("UNCHECKED_CAST")
    private val incomingFilePayloads: ConcurrentHashMap<Long, Payload>
        get() = readPrivateField("incomingFilePayloads") as ConcurrentHashMap<Long, Payload>

    @Suppress("UNCHECKED_CAST")
    private val pendingFileMeta: ConcurrentLinkedQueue<Pair<Long, Any>>
        get() = readPrivateField("pendingFileMeta") as ConcurrentLinkedQueue<Pair<Long, Any>>

    private fun fileMetaOf(entry: Any?): Any? =
        (entry as? Pair<*, *>)?.second

    private fun queuedMeta(): Any? = fileMetaOf(pendingFileMeta.peek())

    private fun metaField(meta: Any, field: String): Any {
        val declared = meta.javaClass.getDeclaredField(field)
        declared.isAccessible = true
        return declared.get(meta) ?: error("field $field is null")
    }

    private fun filePayloadOf(bytes: ByteArray, name: String): Payload {
        val file = File(context.cacheDir, name).apply { writeBytes(bytes) }
        transferFile = file
        return Payload.fromFile(file)
    }

    private fun deliverMetaPacket(packet: NetworkPacket, endpointId: String = "endpoint-1") {
        payloadCallback.onPayloadReceived(endpointId, Payload.fromBytes(packet.toJson().toByteArray()))
    }

    private fun fileMetaPacket(messageId: String, fileName: String, fileSize: Long) = FileMessagePacket(
        messageId = messageId,
        senderId = "peer_sender",
        senderName = "Sender",
        recipientId = "me_local",
        fileName = fileName,
        fileSize = fileSize,
        mimeType = "application/pdf",
        fileBase64 = "",
        caption = "Monthly report",
        timestamp = 1_700_000_000_000L
    )

    private fun awaitCondition(what: String, timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("timed out after ${timeoutMs}ms waiting until $what")
    }

    /** PayloadTransferUpdate مالهوش public constructor، فبنبنّيه reflection. */
    private fun transferUpdate(
        payloadId: Long,
        status: Int,
        bytesTransferred: Long,
        totalBytes: Long
    ): PayloadTransferUpdate {
        val constructor = PayloadTransferUpdate::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        return constructor.newInstance(payloadId, status, bytesTransferred, totalBytes)
    }

    @Test
    fun `meta packet queues metadata that binds to the real nearby payload id`() {
        deliverMetaPacket(fileMetaPacket("msg_file_1", "report.pdf", 2048L))
        awaitCondition("the meta packet is queued") { pendingFileMeta.isNotEmpty() }

        val payload = filePayloadOf(ByteArray(2048) { (it % 251).toByte() }, "incoming_file_1.bin")
        payloadCallback.onPayloadReceived("endpoint-1", payload)

        val bound = incomingFileMetaMap[payload.id]
        assertNotNull("meta must be bound to the payload id, not to a messageId hash", bound)
        assertEquals("msg_file_1", metaField(bound!!, "messageId"))
        assertEquals("report.pdf", metaField(bound, "fileName"))
        assertEquals(2048L, metaField(bound, "fileSize"))
        assertEquals("Monthly report", metaField(bound, "caption"))
        assertEquals(setOf(payload.id), incomingFileMetaMap.keys.toSet())
        assertTrue("the queued meta must be consumed by the file payload", pendingFileMeta.isEmpty())
    }

    @Test
    fun `two metas bind to two file payloads in arrival order`() {
        deliverMetaPacket(fileMetaPacket("msg_first", "first.pdf", 11L))
        awaitCondition("first meta queued") { pendingFileMeta.size == 1 }
        deliverMetaPacket(fileMetaPacket("msg_second", "second.mp4", 22L))
        awaitCondition("second meta queued") { pendingFileMeta.size == 2 }

        val firstPayload = filePayloadOf(ByteArray(11), "mesh_first.bin")
        val secondPayload = filePayloadOf(ByteArray(22), "mesh_second.bin")
        payloadCallback.onPayloadReceived("endpoint-1", firstPayload)
        payloadCallback.onPayloadReceived("endpoint-1", secondPayload)

        assertEquals("msg_first", metaField(incomingFileMetaMap[firstPayload.id]!!, "messageId"))
        assertEquals("msg_second", metaField(incomingFileMetaMap[secondPayload.id]!!, "messageId"))
        assertEquals("second.mp4", metaField(incomingFileMetaMap[secondPayload.id]!!, "fileName"))
        assertTrue(pendingFileMeta.isEmpty())
    }

    @Test
    fun `a photo sent as a file keeps its photo metadata`() {
        deliverMetaPacket(
            PhotoMessagePacket(
                messageId = "photo_1",
                senderId = "peer_sender",
                senderName = "Sender",
                recipientId = "me_local",
                photoBase64 = "",
                caption = "Sunset",
                fileName = "sunset.jpg",
                fileSize = 4096L
            )
        )
        awaitCondition("photo meta queued") { pendingFileMeta.isNotEmpty() }

        val payload = filePayloadOf(ByteArray(4096) { 0x5A }, "mesh_photo.jpg")
        payloadCallback.onPayloadReceived("endpoint-1", payload)

        val bound = incomingFileMetaMap[payload.id]
        assertNotNull(bound)
        assertEquals("photo_1", metaField(bound!!, "messageId"))
        assertEquals("sunset.jpg", metaField(bound, "fileName"))
        assertEquals(true, metaField(bound, "isPhoto"))
    }

    @Test
    fun `a file payload with no queued meta is kept without a meta binding and does not crash`() {
        val payload = filePayloadOf(ByteArray(64), "mesh_orphan.bin")

        payloadCallback.onPayloadReceived("endpoint-1", payload)

        assertNull(
            "no meta can be bound when none was queued",
            incomingFileMetaMap[payload.id]
        )
        assertTrue("the payload is still tracked so the transfer update can clean it up", incomingFilePayloads.containsKey(payload.id))

        payloadCallback.onPayloadTransferUpdate(
            "endpoint-1",
            transferUpdate(payload.id, PayloadTransferUpdate.Status.SUCCESS, 64L, 64L)
        )

        assertFalse(incomingFilePayloads.containsKey(payload.id))
        assertFalse(incomingFileMetaMap.containsKey(payload.id))
    }

    @Test
    fun `a completed transfer finalises the received file and cleans both maps by payload id`() {
        val fileBytes = ByteArray(512) { (it * 3 % 199).toByte() }
        deliverMetaPacket(fileMetaPacket("msg_done", "done.pdf", 512L))
        awaitCondition("meta queued") { pendingFileMeta.isNotEmpty() }

        val payload = filePayloadOf(fileBytes, "mesh_done.bin")
        payloadCallback.onPayloadReceived("endpoint-1", payload)
        val payloadId = payload.id

        payloadCallback.onPayloadTransferUpdate(
            "endpoint-1",
            transferUpdate(payloadId, PayloadTransferUpdate.Status.SUCCESS, 512L, 512L)
        )

        awaitCondition("the mesh file message is stored in the database") {
            runBlocking { database.chatMessageDao().getMessageById("msg_done") } != null
        }
        val stored = runBlocking { database.chatMessageDao().getMessageById("msg_done") }
        assertNotNull(stored)
        assertEquals("peer_sender", stored!!.conversationId)
        assertEquals("done.pdf", stored.fileName)
        assertTrue("a mesh file must be marked as mesh relayed", stored.isMeshRelayed)
        assertTrue(stored.filePath!!.startsWith(context.filesDir.absolutePath))
        assertArrayEqualsOnDisk(fileBytes, stored.filePath!!)
        assertEquals(
            "cleanup must be keyed by the payload id",
            false,
            incomingFileMetaMap.containsKey(payloadId) || incomingFilePayloads.containsKey(payloadId)
        )
    }

    @Test
    fun `a failed transfer drops the payload and its meta`() {
        deliverMetaPacket(fileMetaPacket("msg_failed", "failed.pdf", 128L))
        awaitCondition("meta queued") { pendingFileMeta.isNotEmpty() }
        val payload = filePayloadOf(ByteArray(128), "mesh_failed.bin")
        payloadCallback.onPayloadReceived("endpoint-1", payload)
        val payloadId = payload.id

        payloadCallback.onPayloadTransferUpdate(
            "endpoint-1",
            transferUpdate(payloadId, PayloadTransferUpdate.Status.FAILURE, 64L, 128L)
        )

        assertFalse(incomingFilePayloads.containsKey(payloadId))
        assertFalse(incomingFileMetaMap.containsKey(payloadId))
        assertNull(runBlocking { database.chatMessageDao().getMessageById("msg_failed") })
    }

    @Test
    fun `a meta queued but never followed by a file stays pending`() {
        deliverMetaPacket(fileMetaPacket("msg_orphan_meta", "orphan.pdf", 10L))
        awaitCondition("meta queued") { pendingFileMeta.isNotEmpty() }

        assertEquals("msg_orphan_meta", metaField(queuedMeta()!!, "messageId"))
        assertTrue(incomingFileMetaMap.isEmpty())
        assertTrue(incomingFilePayloads.isEmpty())
    }

    private fun assertArrayEqualsOnDisk(expected: ByteArray, path: String) {
        val actual = File(path).readBytes()
        assertTrue("the received file must be stored byte for byte", actual.contentEquals(expected))
    }
}
