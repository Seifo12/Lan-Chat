package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG 5: ترتيب الرسائل كان بيتبني على `timestamp` بتاع جهاز المُرسِل، فأي جهاز
 * ساعته متأخرة/متقدمة كان بيخلي الرسائل تقفز في المحادثة. الترتيب دلوقتي
 * `receivedAt ASC, timestamp ASC` و `receivedAt DESC, timestamp DESC`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatMessageReceivedAtOrderingTest {

    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun message(
        id: String,
        receivedAt: Long,
        senderTimestamp: Long,
        isFromMe: Boolean = false,
        status: MessageStatus = MessageStatus.DELIVERED
    ) = ChatMessageEntity(
        id = id,
        conversationId = "peer_1",
        senderId = "peer_1",
        senderName = "Peer",
        recipientId = "me_1",
        text = "msg $id",
        receivedAt = receivedAt,
        timestamp = senderTimestamp,
        isFromMe = isFromMe,
        status = status
    )

    /**
     * الأوقات هنا معكوسة عن بعضها بالكامل: لو الترتيب اتحسب من `timestamp`
     * (ساعة المُرسِل) هتطلع reversed_1..3، واللازم تطلع a..c.
     */
    @Test
    fun `conversation order follows local arrival time not the sender clock`() = runBlocking {
        dao.insertMessage(message("a", receivedAt = 1_000L, senderTimestamp = 9_000L))
        dao.insertMessage(message("b", receivedAt = 2_000L, senderTimestamp = 100L))
        dao.insertMessage(message("c", receivedAt = 3_000L, senderTimestamp = 5_000L))

        val ids = dao.getMessagesForConversation("peer_1").first().map { it.id }

        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `a message from a device with a future clock does not jump ahead`() = runBlocking {
        val before = System.currentTimeMillis()
        dao.insertMessage(message("local", receivedAt = 1_000L, senderTimestamp = 1_000L))
        dao.insertMessage(message("future", receivedAt = 2_000L, senderTimestamp = 4_102_444_800_000L))
        val after = System.currentTimeMillis()

        val ids = dao.getMessagesForConversation("peer_1").first().map { it.id }

        assertEquals(listOf("local", "future"), ids)
        assertEquals(4_102_444_800_000L, dao.getMessageById("future")?.timestamp)
        assertEquals(2_000L, dao.getMessageById("future")?.receivedAt)
        // sanity: the skewed sender clock really is in the future of the test run
        assertEquals(true, dao.getMessageById("future")!!.timestamp > after)
        assertEquals(true, dao.getMessageById("local")!!.receivedAt < before)
    }

    @Test
    fun `equal arrival time falls back to the sender timestamp`() = runBlocking {
        dao.insertMessage(message("late_clock", receivedAt = 5_000L, senderTimestamp = 800L))
        dao.insertMessage(message("early_clock", receivedAt = 5_000L, senderTimestamp = 300L))

        val ids = dao.getMessagesForConversation("peer_1").first().map { it.id }

        assertEquals(listOf("early_clock", "late_clock"), ids)
    }

    @Test
    fun `all messages come back newest arrival first`() = runBlocking {
        dao.insertMessage(message("oldest", receivedAt = 10L, senderTimestamp = 10L))
        dao.insertMessage(message("middle", receivedAt = 20L, senderTimestamp = 99_000L))
        dao.insertMessage(message("newest", receivedAt = 30L, senderTimestamp = 5L))

        val ids = dao.getAllMessages().first().map { it.id }

        assertEquals(listOf("newest", "middle", "oldest"), ids)
    }

    @Test
    fun `last message of a conversation is the newest arrival not the newest sender clock`() =
        runBlocking {
            dao.insertMessage(message("arrived_first", receivedAt = 100L, senderTimestamp = 100L))
            dao.insertMessage(
                message(
                    "arrived_last", receivedAt = 200L,
                    senderTimestamp = 4_102_444_800_000L
                )
            )

            val last = dao.getLastMessageForConversation("peer_1").first()

            assertEquals("arrived_last", last?.id)
            assertEquals(200L, last?.receivedAt)
        }

    @Test
    fun `pending outgoing queue is drained in arrival order`() = runBlocking {
        dao.insertMessage(
            message("queued_a", receivedAt = 100L, senderTimestamp = 90_000L, isFromMe = true, status = MessageStatus.SENDING)
        )
        dao.insertMessage(
            message("queued_b", receivedAt = 200L, senderTimestamp = 10L, isFromMe = true, status = MessageStatus.SENDING)
        )
        dao.insertMessage(
            message("already_sent", receivedAt = 150L, senderTimestamp = 150L, isFromMe = true, status = MessageStatus.SENT)
        )

        val pendingIds = dao.getPendingMessagesForConversation("peer_1").map { it.id }
        val allPendingIds = dao.getAllPendingDirectMessages().map { it.id }

        assertEquals(listOf("queued_a", "queued_b"), pendingIds)
        assertEquals(listOf("queued_a", "queued_b"), allPendingIds)
    }

    @Test
    fun `a new incoming message records its own arrival time by default`() = runBlocking {
        val skewedSenderTimestamp = 4_102_444_800_000L
        val before = System.currentTimeMillis()
        dao.insertMessage(
            ChatMessageEntity(
                id = "default_arrival",
                conversationId = "peer_1",
                senderId = "peer_1",
                senderName = "Peer",
                recipientId = "me_1",
                text = "no explicit receivedAt",
                timestamp = skewedSenderTimestamp,
                isFromMe = false
            )
        )

        val stored = dao.getMessageById("default_arrival")

        assertEquals(true, stored != null)
        assertEquals(skewedSenderTimestamp, stored?.timestamp)
        assertEquals(true, (stored?.receivedAt ?: 0L) >= before)
        assertEquals(true, (stored?.receivedAt ?: Long.MAX_VALUE) < skewedSenderTimestamp)
    }
}
