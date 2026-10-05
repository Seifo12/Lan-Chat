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
 * Phase 1.5: replay defence that survives a restart.
 *
 * The seen-nonce set lives in RAM and is discarded when a session is
 * re-established, so a recorded capture replays cleanly after a restart. This
 * table is the durable half: every accepted inbound message id is remembered for
 * a retention window, independently of whether the conversation still exists.
 *
 * That independence is the point. If the record lived inside the conversation,
 * deleting the chat would hand an attacker a clean slate, so the guard has to
 * outlive the message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayGuardTest {

    private lateinit var db: ChatDatabase
    private lateinit var guard: ReplayGuard
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val now: Long get() = System.currentTimeMillis()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        guard = ReplayGuard(db.seenIdDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a message id is accepted once and rejected on replay`() = runBlocking {
        assertTrue("first delivery is accepted", guard.tryAccept("peer_a", "msg_1"))
        assertFalse("the same id again is a replay", guard.tryAccept("peer_a", "msg_1"))
    }

    @Test
    fun `the same message id from a different sender is a different message`() = runBlocking {
        assertTrue(guard.tryAccept("peer_a", "msg_1"))
        assertTrue(
            "two peers may legitimately use the same local id",
            guard.tryAccept("peer_b", "msg_1")
        )
    }

    @Test
    fun `a replay is rejected even after the conversation is deleted`() = runBlocking {
        assertTrue(guard.tryAccept("peer_a", "msg_1"))

        db.chatMessageDao().insertMessage(
            ChatMessageEntity(
                id = "msg_1", conversationId = "peer_a", senderId = "peer_a",
                senderName = "Peer", recipientId = "me", text = "original", isFromMe = false,
            )
        )
        db.chatMessageDao().clearConversation("peer_a")
        assertEquals(0, db.chatMessageDao().getMessageById("msg_1")?.let { 1 } ?: 0)

        assertFalse(
            "deleting the chat must not hand an attacker a clean slate",
            guard.tryAccept("peer_a", "msg_1")
        )
    }

    @Test
    fun `records older than the retention window are purged and may be seen again`() = runBlocking {
        val old = now - (ReplayGuard.RETENTION_MS + 60_000L)
        db.seenIdDao().insert(SeenIdEntity("peer_a", "msg_old", old))

        assertEquals(1, guard.purgeExpired(now))
        assertTrue(
            "a record outside the window is intentionally forgotten",
            guard.tryAccept("peer_a", "msg_old")
        )
    }

    @Test
    fun `records inside the retention window are kept`() = runBlocking {
        db.seenIdDao().insert(SeenIdEntity("peer_a", "msg_fresh", now - 60_000L))

        assertEquals(
            "a record from a minute ago is nowhere near the retention window",
            0,
            guard.purgeExpired(now)
        )
        assertFalse(
            "a recent record must still block a replay",
            guard.tryAccept("peer_a", "msg_fresh")
        )
    }

    @Test
    fun `retention is thirty days`() {
        assertEquals(
            "the agreed retention window",
            30L * 24 * 60 * 60 * 1000,
            ReplayGuard.RETENTION_MS
        )
    }

    @Test
    fun `the purge removes only what is outside the window`() = runBlocking {
        val dao = db.seenIdDao()
        dao.insert(SeenIdEntity("peer_a", "old_1", now - 40L * 24 * 60 * 60 * 1000))
        dao.insert(SeenIdEntity("peer_a", "old_2", now - 31L * 24 * 60 * 60 * 1000))
        dao.insert(SeenIdEntity("peer_a", "edge", now - 29L * 24 * 60 * 60 * 1000))
        dao.insert(SeenIdEntity("peer_a", "new_1", now - 1000L))

        assertEquals(
            "only the two entries past thirty days are dropped",
            2,
            guard.purgeExpired(now)
        )
        assertEquals(2, dao.count())
    }

    @Test
    fun `a concurrent double delivery admits exactly one`() = runBlocking {
        // Two delivery paths can race for the same message, for example a direct
        // LAN arrival and a mesh relay of the same packet. The primary key makes
        // the second insert fail, which must be reported as a replay.
        val dao = db.seenIdDao()
        val first = dao.insertAndGetChange(SeenIdEntity("peer_a", "msg_1", now))
        val second = dao.insertAndGetChange(SeenIdEntity("peer_a", "msg_1", now))

        assertTrue("the first insert reports a change", first > 0)
        assertTrue(
            "a conflicting insert reports no change, which Room signals as -1",
            second <= 0
        )
        assertEquals("only one row exists", 1, dao.count())
        assertFalse(
            "and the guard must therefore refuse the second delivery",
            guard.tryAccept("peer_a", "msg_1")
        )
    }
}