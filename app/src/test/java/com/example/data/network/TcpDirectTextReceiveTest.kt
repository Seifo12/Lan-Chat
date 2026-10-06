package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import com.example.data.security.MessageSigningPayload
import com.example.data.security.PairwiseSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.5/1.3 wiring: a genuine direct text message must be delivered, and a
 * redelivery of the same message id must be refused as a replay.
 *
 * The replay pre-check used to record the message id before verifying it. The
 * verifier then saw the id it had just been given as already seen and refused
 * the message as a duplicate, so every first delivery over TCP was dropped
 * silently. A forged packet could also plant a record that blocked the genuine
 * message arriving after it. The check has to be a read; the write belongs to
 * the accepted branch only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TcpDirectTextReceiveTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ChatDatabase
    private lateinit var prefs: UserPreferences
    private lateinit var manager: TcpMessagingManager
    private lateinit var sender: PairwiseSessionManager

    private val peerId = "peer_tcp_1"

    @Before
    fun setUp() {
        EncryptionManager.initializePairwiseManager(context)
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = UserPreferences(context)
        manager = TcpMessagingManager(context, database, prefs)
        sender = PairwiseSessionManager(context)
        runBlocking {
            database.contactDao().insertOrUpdateContact(
                ContactEntity(
                    deviceId = peerId,
                    displayName = "TCP Peer",
                    ipAddress = "10.0.0.6",
                    publicKeyBase64 = sender.getMyPublicKeyBase64(),
                )
            )
        }
        // The notification path is not what is under test; an open conversation
        // takes the delivery branch without touching it.
        manager.setActiveConversation(peerId)
    }

    private fun signedDirectText(
        text: String,
        messageId: String,
        counter: Long = 1L,
    ): TextMessagePacket {
        val timestamp = 1_700_000_000_000L
        val fields = MessageSigningPayload.Fields(
            senderId = peerId,
            recipientId = prefs.deviceId,
            messageId = messageId,
            timestamp = timestamp,
            counter = counter,
            contentDigestHex = MessageSigningPayload.digestHex(text.toByteArray()),
        )
        return TextMessagePacket(
            messageId = messageId,
            senderId = peerId,
            senderName = "TCP Peer",
            recipientId = prefs.deviceId,
            text = text,
            timestamp = timestamp,
            signatureBase64 = MessageSigningPayload.signWith(sender, fields),
            counter = counter,
            protocolVersion = ProtocolVersion.CURRENT,
        )
    }

    @Test
    fun `a genuine direct text is delivered, not refused as a duplicate`() = runBlocking {
        manager.processReceivedPacket(
            signedDirectText("hello over tcp", messageId = "t_first"),
            senderIp = "10.0.0.6",
        )

        assertEquals(
            "the replay pre-check must be a read; recording before verifying " +
                "makes every first delivery look like a duplicate",
            "hello over tcp",
            database.chatMessageDao().getMessageById("t_first")?.text,
        )
    }

    @Test
    fun `a redelivered message id is refused as a replay`() = runBlocking {
        val packet = signedDirectText("only once", messageId = "t_replay")
        manager.processReceivedPacket(packet, senderIp = "10.0.0.6")
        assertEquals("only once", database.chatMessageDao().getMessageById("t_replay")?.text)

        val tamperedReplay = packet.copy(text = "the same id with different words")
        manager.processReceivedPacket(tamperedReplay, senderIp = "10.0.0.6")

        // The first delivery stands and the replay changes nothing, not even
        // when the replay carries different words under the same id.
        assertEquals("only once", database.chatMessageDao().getMessageById("t_replay")?.text)
    }

    @Test
    fun `a forged packet cannot plant a record that blocks the genuine message`() =
        runBlocking {
            val genuine = signedDirectText("the real words", messageId = "t_race")
            val forged = genuine.copy(text = "forged words first")

            // The forgery arrives first and must be refused without recording.
            manager.processReceivedPacket(forged, senderIp = "10.0.0.6")
            assertNull(database.chatMessageDao().getMessageById("t_race")?.text)

            // The genuine message arrives after and must still be delivered.
            manager.processReceivedPacket(genuine, senderIp = "10.0.0.6")
            assertEquals(
                "a refused forgery must not leave a record behind that blocks " +
                    "the message it was imitating",
                "the real words",
                database.chatMessageDao().getMessageById("t_race")?.text,
            )
        }
}