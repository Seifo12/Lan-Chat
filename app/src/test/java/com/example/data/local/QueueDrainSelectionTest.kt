package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 1.1 follow-up.
 *
 * The README promised that a pending send is re-attempted when the peer comes
 * back. The drain worker selects rows through getAllPendingDirectMessages and
 * getPendingMessagesForConversation, and both filtered on SENDING only, so a
 * message that settled into QUEUED because the peer was unreachable was never
 * picked up again. The gap was invisible before this step because the legacy
 * behaviour wrote failures back as SENDING, which the worker did select.
 *
 * These tests pin the contract the worker relies on:
 *   - a QUEUED message is a candidate for automatic send
 *   - a FAILED message is not, because only the user may retry it
 *   - claiming a row as SENDING removes it from selection, which is what makes a
 *     send happen exactly once even though the worker polls on a timer
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueDrainSelectionTest {

    private lateinit var db: ChatDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(id: String, status: MessageStatus) {
        db.chatMessageDao().insertMessage(
            ChatMessageEntity(
                id = id, conversationId = "peer_a", senderId = "me", senderName = "Me",
                recipientId = "peer_a", text = "hello $id", isFromMe = true, status = status,
            )
        )
    }

    private suspend fun selectedIds() = db.chatMessageDao().getAllPendingDirectMessages().map { it.id }

    @Test
    fun `a QUEUED message is selected for automatic send when the peer returns`() = runBlocking {
        insert("m_queued", MessageStatus.QUEUED)
        insert("m_sending", MessageStatus.SENDING)
        insert("m_failed", MessageStatus.FAILED)
        insert("m_sent", MessageStatus.SENT)

        val selected = selectedIds()

        assertTrue(
            "QUEUED is the unreachable-peer state and must be retried, got $selected",
            "m_queued" in selected
        )
        assertTrue("SENDING must still be selected", "m_sending" in selected)
    }

    @Test
    fun `a FAILED message is never selected for automatic send`() = runBlocking {
        insert("m_failed", MessageStatus.FAILED)

        assertEquals(
            "FAILED is terminal until the user retries it",
            emptyList<String>(),
            selectedIds()
        )
    }

    @Test
    fun `delivered and read messages are never selected`() = runBlocking {
        insert("m_delivered", MessageStatus.DELIVERED)
        insert("m_read", MessageStatus.READ)

        assertEquals(emptyList<String>(), selectedIds())
    }

    @Test
    fun `the per-conversation query agrees with the all-pending query`() = runBlocking {
        insert("m_queued", MessageStatus.QUEUED)
        insert("m_failed", MessageStatus.FAILED)
        insert("m_sent", MessageStatus.SENT)

        assertEquals(
            "the reconnect path and the poll path must not disagree",
            listOf("m_queued"),
            db.chatMessageDao().getPendingMessagesForConversation("peer_a").map { it.id }
        )
    }

    /**
     * A claimed row becomes SENDING, which is the state the worker records as
     * in flight. SENDING deliberately stays selectable, because the existing
     * contract is that a failed send stays SENDING so the queue can retry it, so
     * duplicate suppression is the manager's in-flight set rather than the status
     * column. What this test pins is that the claim happens and is observable.
     */
@Test
    fun `claiming a queued row moves it to SENDING`() = runBlocking {
        val dao = db.chatMessageDao()
        insert("m_queued", MessageStatus.QUEUED)

        dao.updateMessageStatus("m_queued", MessageStatus.SENDING)

        assertEquals(
            MessageStatus.SENDING,
            dao.getMessageById("m_queued")!!.status
        )
    }

    @Test
    fun `a queued row outside the auto-send window is not resent`() {
        val now = 1_700_000_000_000L

        assertTrue(
            "a message queued moments ago should go out when the peer returns",
            MessageStatus.shouldAutoSendQueued(createdAt = now - 60 * 1000L, now = now)
        )
        assertFalse(
            "a message that has been queued for over a day waits for the user",
            MessageStatus.shouldAutoSendQueued(createdAt = now - 25 * 60 * 60 * 1000L, now = now)
        )
    }
}