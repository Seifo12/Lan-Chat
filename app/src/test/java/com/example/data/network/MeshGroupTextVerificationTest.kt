package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.MeshTextReceiver
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import com.example.data.security.MessageSigningPayload
import com.example.data.security.PairwiseSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.3 gap fix: a group message arriving over the mesh whose signature does
 * not verify must never reach the database.
 *
 * The mesh path used to log "Security Alert: Signature mismatch" and then insert
 * the message anyway, so the alert was decorative. Two things were wrong at once
 * and neither could be seen from a test: the mismatch was ignored, and the check
 * verified the message text alone while the sender signs the whole 1.3 field
 * set. Fixing only the first half would have refused every legitimate mesh
 * message, so both halves move together here.
 *
 * The packet is signed exactly as the sender signs it and serialised through the
 * real dispatcher, so the wire encoding is covered too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeshGroupTextVerificationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ChatDatabase
    private lateinit var prefs: UserPreferences
    private lateinit var sender: PairwiseSessionManager
    private lateinit var receiver: MeshTextReceiver

    private val peerId = "peer_mesh_1"
    private val groupId = "group_under_test"

    @Before
    fun setUp() {
        EncryptionManager.initializePairwiseManager(context)
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = UserPreferences(context)
        sender = PairwiseSessionManager(context)
        runBlocking {
            database.contactDao().insertOrUpdateContact(
                ContactEntity(
                    deviceId = peerId,
                    displayName = "Mesh Peer",
                    ipAddress = "10.0.0.5",
                    publicKeyBase64 = sender.getMyPublicKeyBase64(),
                )
            )
        }
        receiver = MeshTextReceiver(
            database = database,
            userPreferences = prefs,
            onAccepted = { _, _ -> /* acks are not what this test is about */ },
        )
    }

    /** Signed over the whole 1.3 field set, exactly as TcpMessagingManager does. */
    private fun signedGroupTextOnWire(
        text: String,
        messageId: String,
        counter: Long = 1L,
    ): TextMessagePacket {
        val timestamp = 1_700_000_000_000L
        // The receiver verifies against its own device id, so the signature has
        // to name this device rather than a placeholder.
        val recipientId = prefs.deviceId
        val fields = MessageSigningPayload.Fields(
            senderId = peerId,
            recipientId = recipientId,
            messageId = messageId,
            timestamp = timestamp,
            counter = counter,
            contentDigestHex = MessageSigningPayload.digestHex(text.toByteArray()),
        )
        val signature = MessageSigningPayload.signWith(sender, fields)
        return TextMessagePacket(
            messageId = messageId,
            senderId = peerId,
            senderName = "Mesh Peer",
            recipientId = recipientId,
            text = text,
            timestamp = timestamp,
            isGroup = true,
            groupId = groupId,
            groupName = "The Group",
            signatureBase64 = signature,
            counter = counter,
            protocolVersion = ProtocolVersion.CURRENT,
        )
    }

    private suspend fun storedText(messageId: String): String? =
        database.chatMessageDao().getMessageById(messageId)?.text

    @Test
    fun `a group message whose body changed after signing is not inserted`() = runBlocking {
        val onWire = signedGroupTextOnWire("the original group message", messageId = "m_tampered")
        val parsed = NetworkPacket.fromJson(onWire.toJson()) as TextMessagePacket
        val tampered = parsed.copy(text = "the replaced group message")

        val accepted = receiver.receive(tampered, fromEndpointId = "ep_1")

        assertFalse("a message that does not verify must be refused", accepted)
        assertNull(
            "the mismatch used to be logged and the row inserted anyway, so the " +
                "alert was decorative; nothing may reach the database",
            storedText("m_tampered"),
        )
    }

    @Test
    fun `an unsigned group message is not inserted`() = runBlocking {
        val onWire = signedGroupTextOnWire("no signature at all", messageId = "m_unsigned")
        val parsed = NetworkPacket.fromJson(onWire.toJson()) as TextMessagePacket
        val stripped = parsed.copy(signatureBase64 = null)

        val accepted = receiver.receive(stripped, fromEndpointId = "ep_1")

        assertFalse("a packet with no signature must be refused", accepted)
        assertNull(storedText("m_unsigned"))
    }

    @Test
    fun `a valid signed group message is still accepted`() = runBlocking {
        val onWire = signedGroupTextOnWire("hello group", messageId = "m_good")
        val parsed = NetworkPacket.fromJson(onWire.toJson()) as TextMessagePacket

        val accepted = receiver.receive(parsed, fromEndpointId = "ep_1")

        assertTrue("a genuine message must still get through", accepted)
        assertEquals("hello group", storedText("m_good"))
    }

    @Test
    fun `a replayed group message is not stored twice`() = runBlocking {
        val onWire = signedGroupTextOnWire("only once", messageId = "m_replay")
        val parsed = NetworkPacket.fromJson(onWire.toJson()) as TextMessagePacket

        assertTrue(receiver.receive(parsed, fromEndpointId = "ep_1"))
        val secondAccepted = receiver.receive(parsed, fromEndpointId = "ep_1")

        assertFalse("the same message id twice is a replay", secondAccepted)
        assertEquals("only once", storedText("m_replay"))
    }

    @Test
    fun `a message from a peer with no known key is not inserted`() = runBlocking {
        val onWire = signedGroupTextOnWire("who am I", messageId = "m_unknown")
        val parsed = NetworkPacket.fromJson(onWire.toJson()) as TextMessagePacket
        val stranger = parsed.copy(senderId = "peer_nobody_knows")

        val accepted = receiver.receive(stranger, fromEndpointId = "ep_1")

        assertFalse("an unknown sender cannot be verified, so it is refused", accepted)
        assertNull(storedText("m_unknown"))
    }

    @Test
    fun `an out of date protocol version is refused rather than stored`() = runBlocking {
        val onWire = signedGroupTextOnWire("from the past", messageId = "m_old")
        val parsed = NetworkPacket.fromJson(onWire.toJson()) as TextMessagePacket
        val old = parsed.copy(protocolVersion = ProtocolVersion.MINIMUM - 1)

        val accepted = receiver.receive(old, fromEndpointId = "ep_1")

        assertFalse(accepted)
        assertNull(storedText("m_old"))
    }
}