package com.example.data.network

import android.content.Context
import android.util.Base64
import androidx.room.Room
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.MessageStatus
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * BUG 4: عشر call sites كانوا بيرجعوا `Result.success(entity)` من غير ما
 * يهمّهم if LAN ولا MESH فشلوا، فالمستخدم كان بيشوف ✓ والمESSAGE ماحصلش.
 * الإصلاح: كل الـ call sites بترجع `Result.failure(IOException)` لما النقل
 * يشل فعلاً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TcpMessagingSendResultTest {

    private lateinit var context: Context
    private lateinit var database: ChatDatabase
    private lateinit var manager: TcpMessagingManager
    private var peer: LoopbackPeer? = null
    private var mediaFile: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = TcpMessagingManager(context, database, UserPreferences(context))
        manager.nearbyFallbackSender = { _, _ -> false }
        // Phase 1.2: a send is refused unless the recipient has a pairwise
        // session, so the tests that expect delivery need the crypto engine.
        EncryptionManager.initializePairwiseManager(context)
    }

    /**
     * Phase 1.2. A contact that has completed pairing carries the peer's public
     * key, which is what makes a pairwise session possible. A contact without one
     * can no longer be messaged, which is the intended fail-closed behaviour.
     */
    private fun newPeerPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return Base64.encodeToString(generator.generateKeyPair().public.encoded, Base64.NO_WRAP)
    }

    private suspend fun insertPairedContact(
        deviceId: String, ip: String, port: Int, isOnline: Boolean = true
    ) {
        database.contactDao().insertOrUpdateContact(
            com.example.data.local.ContactEntity(
                deviceId = deviceId,
                displayName = "Peer",
                ipAddress = ip,
                tcpPort = port,
                isOnline = isOnline,
                publicKeyBase64 = newPeerPublicKey()
            )
        )
    }

    @After
    fun tearDown() {
        peer?.close()
        peer = null
        mediaFile?.delete()
        mediaFile = null
        database.close()
    }

    /** بورت مقفول فعلاً: bind على 0 وبعدين close، فأي connect ليه هيرفض. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun newPeer(): LoopbackPeer = LoopbackPeer().also { peer = it }

    private fun mediaFile(name: String, size: Int = 64): File {
        val file = File(context.cacheDir, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
        mediaFile = file
        return file
    }

    @Test
    fun `text send fails when both lan and mesh are unavailable`() = runBlocking {
        val result = manager.sendTextMessage("127.0.0.1", closedPort(), "peer_offline", "hello")

        assertTrue("a total transport failure must not be reported as success", result.isFailure)
        assertTrue(
            "the failure must be an IOException so the UI can queue a retry",
            result.exceptionOrNull() is IOException
        )
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    fun `a failed text send stays in SENDING so the queue can retry it`() = runBlocking {
        val result = manager.sendTextMessage("127.0.0.1", closedPort(), "peer_offline", "hello")

        assertTrue(result.isFailure)
        val rows = database.chatMessageDao().getAllPendingDirectMessages()
        assertEquals("the undelivered message must stay pending", 1, rows.size)
        assertEquals(MessageStatus.SENDING, rows.first().status)
    }

    @Test
    fun `text send succeeds over mesh when there is no lan route`() = runBlocking {
        manager.nearbyFallbackSender = { _, packet ->
            packet is TextMessagePacket && packet.text == "over mesh"
        }

        val result = manager.sendTextMessage("p2p-endpoint-1", 9999, "peer_mesh", "over mesh")

        assertTrue("a mesh delivery is a real delivery", result.isSuccess)
        val entity = result.getOrNull()
        assertNotNull(entity)
        assertTrue("the message must be flagged as mesh relayed", entity!!.isMeshRelayed)
        assertEquals(1, entity.meshHops)
        assertEquals(MessageStatus.SENT, entity.status)
    }

    @Test
    fun `text send over a live lan peer succeeds`() = runBlocking {
        val livePeer = newPeer()
        insertPairedContact("peer_lan", "127.0.0.1", livePeer.port)

        val result = manager.sendTextMessage("127.0.0.1", livePeer.port, "peer_lan", "direct lan")

        assertTrue("a delivered message must be reported as sent", result.isSuccess)
        assertFalse(result.getOrNull()!!.isMeshRelayed)
        assertEquals(1, livePeer.receivedPackets.get())
        assertEquals(0, database.chatMessageDao().getAllPendingDirectMessages().size)
    }

    @Test
    fun `photo send fails when the local file is missing`() = runBlocking {
        val result = manager.sendPhotoMessage(
            "127.0.0.1", closedPort(), "peer_offline",
            File(context.cacheDir, "no_such_photo.jpg").absolutePath, caption = "pic"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, database.chatMessageDao().getAllPendingDirectMessages().size)
    }

    @Test
    fun `photo send fails when the transport fails`() = runBlocking {
        val file = mediaFile("send_photo.jpg")

        val result = manager.sendPhotoMessage("127.0.0.1", closedPort(), "peer_offline", file.absolutePath, caption = "pic")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, database.chatMessageDao().getAllPendingDirectMessages().size)
    }

    @Test
    fun `photo send succeeds against a live peer and clears the pending queue`() = runBlocking {
        val livePeer = newPeer()
        insertPairedContact("peer_lan", "127.0.0.1", livePeer.port)
        val file = mediaFile("send_photo_ok.jpg")

        val result = manager.sendPhotoMessage("127.0.0.1", livePeer.port, "peer_lan", file.absolutePath, caption = "pic")

        assertTrue("a delivered photo must be reported as sent", result.isSuccess)
        assertEquals(MessageStatus.SENT, result.getOrNull()!!.status)
        assertEquals(0, database.chatMessageDao().getAllPendingDirectMessages().size)
        assertEquals(1, livePeer.streamedFiles.get())
    }

    @Test
    fun `video send fails when the transport fails`() = runBlocking {
        val file = mediaFile("send_video.mp4", 128)

        val result = manager.sendVideoMessage(
            "127.0.0.1", closedPort(), "peer_offline", file.absolutePath,
            fileName = "clip.mp4", fileSize = 128L, mimeType = "video/mp4"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, database.chatMessageDao().getAllPendingDirectMessages().size)
    }

    @Test
    fun `document send fails when the transport fails`() = runBlocking {
        val file = mediaFile("send_doc.pdf", 96)

        val result = manager.sendDocFileMessage(
            "127.0.0.1", closedPort(), "peer_offline", file.absolutePath,
            fileName = "doc.pdf", fileSize = 96L, mimeType = "application/pdf"
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, database.chatMessageDao().getAllPendingDirectMessages().size)
    }

    @Test
    fun `voice send fails when the transport fails`() = runBlocking {
        val file = mediaFile("send_voice.m4a", 32)

        val result = manager.sendVoiceMessage("127.0.0.1", closedPort(), "peer_offline", file, 3)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, database.chatMessageDao().getAllPendingDirectMessages().size)
    }

    @Test
    fun `a voice send over mesh fallback is a success`() = runBlocking {
        manager.nearbyFallbackSender = { _, packet -> packet is VoiceMessagePacket }
        val file = mediaFile("send_voice_mesh.m4a", 32)

        val result = manager.sendVoiceMessage("127.0.0.1", closedPort(), "peer_mesh", file, 4)

        assertTrue("delivered over mesh is still delivered", result.isSuccess)
        assertEquals(0, database.chatMessageDao().getAllPendingDirectMessages().size)
    }

    @Test
    fun `group text send reports success when an online peer received it`() = runBlocking {
        val livePeer = newPeer()
        val onlineContact = com.example.data.local.ContactEntity(
            deviceId = "peer_lan",
            displayName = "Peer",
            ipAddress = "127.0.0.1",
            tcpPort = livePeer.port,
            isOnline = true,
            publicKeyBase64 = newPeerPublicKey()
        )

        // Phase 1.2: the send path resolves the recipient's public key from the
        // database, so the contact must be stored there as well as passed in.
        database.contactDao().insertOrUpdateContact(onlineContact)
        // Phase 1.8: fan-out resolves members from the database, so the group
        // and the membership must exist too; the contact list is no longer an
        // argument at all.
        database.groupDao().insertOrUpdateGroup(
            com.example.data.local.GroupEntity(
                groupId = "group_1", groupName = "Team", createdBy = "me",
                creatorDeviceId = "me", isLegacy = false,
            )
        )
        database.groupMembershipDao().replaceMembers(
            "group_1",
            listOf(
                com.example.data.local.GroupMemberEntity(
                    "group_1", "peer_lan", onlineContact.publicKeyBase64!!, "Peer"
                )
            )
        )

        val result = manager.sendGroupTextMessage(
            groupId = "group_1",
            text = "group hello",
        )

        awaitPeer("the group text packet to arrive", livePeer.receivedPackets, 1)
        assertTrue(
            "the peer received the packet, so a failed result is a lie: ${result.exceptionOrNull()}",
            result.isSuccess
        )
    }

    @Test
    fun `group text send to nobody online is reported as a failure`() = runBlocking {
        val offlineContact = com.example.data.local.ContactEntity(
            deviceId = "peer_offline",
            displayName = "Peer",
            ipAddress = "127.0.0.1",
            tcpPort = closedPort(),
            isOnline = false
        )
        database.contactDao().insertOrUpdateContact(offlineContact)
        database.groupDao().insertOrUpdateGroup(
            com.example.data.local.GroupEntity(
                groupId = "group_1", groupName = "Team", createdBy = "me",
                creatorDeviceId = "me", isLegacy = false,
            )
        )
        database.groupMembershipDao().replaceMembers(
            "group_1",
            listOf(
                com.example.data.local.GroupMemberEntity(
                    "group_1", "peer_offline", "", "Peer"
                )
            )
        )

        val result = manager.sendGroupTextMessage(
            groupId = "group_1",
            text = "nobody home",
        )

        assertTrue("nobody received the message", result.isFailure)
    }

    @Test
    fun `voice send puts the raw recording on the wire not a sender encrypted blob`() = runBlocking {
        val file = mediaFile("wire_voice.m4a", 48)
        val rawBytes = file.readBytes()
        var sentPacket: NetworkPacket? = null
        manager.nearbyFallbackSender = { _, packet ->
            sentPacket = packet
            true
        }

        val result = manager.sendVoiceMessage("127.0.0.1", closedPort(), "peer_mesh", file, 2)

        assertTrue(result.isSuccess)
        val voice = sentPacket as? VoiceMessagePacket
        assertNotNull("a voice message must reach the transport", voice)
        val onTheWire = android.util.Base64.decode(voice!!.audioBase64, android.util.Base64.DEFAULT)
        assertArrayEquals(
            "the sender must put the recording on the wire untouched",
            rawBytes,
            onTheWire
        )
        assertFalse(
            "a sender-side encrypted blob cannot be decrypted by the receiver",
            EncryptionManager.isEncryptedBytes(onTheWire)
        )
    }

    @Test
    fun `group photo send reports success when the stream reached an online peer`() = runBlocking {
        val livePeer = newPeer()
        val file = mediaFile("group_photo.jpg")
        val onlineContact = com.example.data.local.ContactEntity(
            deviceId = "peer_lan",
            displayName = "Peer",
            ipAddress = "127.0.0.1",
            tcpPort = livePeer.port,
            isOnline = true,
            publicKeyBase64 = newPeerPublicKey()
        )

        // Phase 1.2: the send path resolves the recipient's public key from the
        // database, so the contact must be stored there as well as passed in.
        database.contactDao().insertOrUpdateContact(onlineContact)
        // Phase 1.8: see the group text test above.
        database.groupDao().insertOrUpdateGroup(
            com.example.data.local.GroupEntity(
                groupId = "group_1", groupName = "Team", createdBy = "me",
                creatorDeviceId = "me", isLegacy = false,
            )
        )
        database.groupMembershipDao().replaceMembers(
            "group_1",
            listOf(
                com.example.data.local.GroupMemberEntity(
                    "group_1", "peer_lan", onlineContact.publicKeyBase64!!, "Peer"
                )
            )
        )

        val result = manager.sendGroupPhotoMessage(
            groupId = "group_1",
            groupName = "Team",
            localPhotoPath = file.absolutePath,
            caption = "team pic",
        )

        awaitPeer("the group photo stream to arrive", livePeer.streamedFiles, 1)
        assertTrue(
            "the peer received the photo stream, so a failed result is a lie: ${result.exceptionOrNull()}",
            result.isSuccess
        )
    }

    /** The group senders fan out on their own scope, so delivery lands asynchronously. */
    private fun awaitPeer(what: String, counter: AtomicInteger, expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() >= expected) return
            Thread.sleep(10)
        }
        fail("timed out waiting until $what (counter=${counter.get()})")
    }

    /**
     * A tiny real peer on loopback: it speaks the same wire protocol as the app
     * (4-byte length + JSON, or the STRM file-stream header) so nothing is mocked.
     */
    private class LoopbackPeer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val receivedPackets = AtomicInteger(0)
        val streamedFiles = AtomicInteger(0)
        val port: Int get() = server.localPort

        init {
            Thread({ acceptLoop() }, "loopback-peer").apply {
                isDaemon = true
                start()
            }
        }

        private fun acceptLoop() {
            while (!server.isClosed) {
                try {
                    server.accept().use { serve(it) }
                } catch (_: Exception) {
                    return
                }
            }
        }

        private fun serve(socket: Socket) {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val header = input.readInt()
            if (header == MAGIC_STREAM_HEADER) {
                val metaLength = input.readInt()
                val meta = ByteArray(metaLength)
                input.readFully(meta)
                output.writeLong(0L)
                output.writeInt(MAGIC_STREAM_ACK)
                output.flush()
                val buffer = ByteArray(8192)
                while (input.read(buffer) != -1) { /* drain the encrypted body */ }
                streamedFiles.incrementAndGet()
            } else if (header in 1..MAX_PACKET) {
                val body = ByteArray(header)
                input.readFully(body)
                receivedPackets.incrementAndGet()
            }
        }

        override fun close() {
            server.close()
        }

        private companion object {
            const val MAGIC_STREAM_HEADER = 0x5354524D
            const val MAGIC_STREAM_ACK = 0x41434B31
            const val MAX_PACKET = 2 * 1024 * 1024
        }
    }
}
