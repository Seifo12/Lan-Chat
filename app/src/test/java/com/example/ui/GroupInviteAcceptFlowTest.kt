package com.example.ui

import android.app.Application
import com.example.LanChatApplication
import com.example.data.local.GroupEntity
import com.example.data.local.GroupInviteEntity
import com.example.data.local.InviteState
import com.example.data.security.GroupInviteCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: the user-facing actions move the same rows the receiver owns.
 *
 * Accept is the only writer of membership from an invitation, decline is
 * terminal for that nonce, leaving is local, and recreation never converts a
 * legacy row. The ViewModel actions are thin delegations, so these tests pin
 * the delegation rather than re-proving the receiver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupInviteAcceptFlowTest {

    private lateinit var application: LanChatApplication
    private lateinit var viewModel: ChatViewModel

    /**
     * A `launch` inside viewModelScope whose body throws hands the throwable to
     * the default uncaught-exception handler and the coroutine simply never
     * calls back. Under a full-suite run that produced a bare "timed out
     * waiting" with the real cause discarded, which pointed the investigation
     * at thread starvation instead of at the exception. Capture them so a
     * timeout always reports why.
     */
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

    /**
     * Every test mints its own ids. The app database can outlive a single
     * test method, JUnit rebuilds this class per method (so a counter would
     * reset), and invites insert with IGNORE, so sharing one id lets a
     * previous test's terminal row silently block the next test's seed.
     */
    private fun nextIds(): Pair<String, String> {
        val uuid = java.util.UUID.randomUUID().toString().replace("-", "")
        return Pair("g_flow_$uuid", "flow${uuid.take(28)}")
    }

    @Before
    fun setUp() {
        uncaught.clear()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            uncaught.add(error)
            previous?.uncaughtException(thread, error)
        }
        application =
            androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>() as LanChatApplication
        // The recreate path mints keys through the pairwise manager. Relying
        // on ambient static state passes in isolation and starves in suite.
        com.example.data.security.EncryptionManager.initializePairwiseManager(application)
        // Unconfined: the actions would otherwise compete for the single
        // shared IO pool with hundreds of leaked threads from other test
        // classes in a full-suite run and starve. Production still gets IO.
        viewModel = ChatViewModel(application, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    private fun inviteJson(groupId: String, nonce: String): String {
        val me = viewModel.userPrefs.deviceId
        return GroupInviteCodec.buildCanonical(
            GroupInviteCodec.Invite(
                creatorId = "peer_creator", groupId = groupId, groupName = "Flow",
                description = "", members = listOf(
                    GroupInviteCodec.Member("peer_creator", "pk_creator", "Creator"),
                    GroupInviteCodec.Member(me, "pk_me", "Me"),
                ),
                memberListVersion = 1L, nonce = nonce,
                expiry = System.currentTimeMillis() + GroupInviteCodec.INVITE_TTL_MS,
            )
        )
    }

    private fun seedPending(groupId: String, nonce: String) {
        val db = application.database
        runBlocking {
            db.groupMembershipDao().insertInvite(
                GroupInviteEntity(
                    groupId = groupId, nonce = nonce, creatorId = "peer_creator",
                    groupName = "Flow", memberListVersion = 1L,
                    expiry = System.currentTimeMillis() + GroupInviteCodec.INVITE_TTL_MS,
                    rawBytes = inviteJson(groupId, nonce).toByteArray(),
                    state = InviteState.PENDING,
                )
            )
        }
    }

    /**
     * Thirty seconds, not five: these actions dispatch through
     * viewModelScope, and under a full-suite run the shared pools can be
     * saturated by leaked threads from other classes. A genuine hang still
     * fails, only slower. Five seconds passed in isolation and timed out in
     * the suite, which is how this number was earned.
     */
    private fun waitFor(message: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000L
        do {
            if (runBlocking { condition() }) return
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        val causes = if (uncaught.isEmpty()) "" else "\nuncaught while waiting:\n  " + uncaught.joinToString("\n  ") { it.toString() }
        throw AssertionError("timed out waiting: $message$causes" + threadSnapshot())
    }

    /**
     * When a wait times out, the useful question is what every other thread is
     * blocked on, not that one thread made no progress. Grouped by top frame so
     * a hundred identical leaked loops read as one line.
     */
    private fun threadSnapshot(): String {
        val skip = setOf("Reference Handler", "Finalizer", "Signal Dispatcher", "process reaper", "Common-Cleaner")
        val snapshots = Thread.getAllStackTraces()
        val counts = HashMap<String, Int>()
        for (entry in snapshots.entries) {
            if (entry.key.name in skip) continue
            val stack = entry.value
            val frame = stack.firstOrNull {
                it.className.startsWith("kotlin") || it.className.startsWith("com.example") ||
                    it.className.startsWith("android")
            } ?: stack.firstOrNull()
            val key = frame?.let { it.className.substringAfterLast('.') + "." + it.methodName } ?: "other"
            counts[key] = (counts[key] ?: 0) + 1
        }
        return "\nthreads alive: ${snapshots.size}, grouped by top frame: " +
            counts.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.value}x ${it.key}" }
    }

    @Test
    fun `accepting the shown invite writes membership`() {
        val (groupId, nonce) = nextIds()
        seedPending(groupId, nonce)

        viewModel.acceptGroupInvite(groupId, nonce)

        val db = application.database
        waitFor("invite accepted") {
            db.groupMembershipDao().getInvite(groupId, nonce)?.state == InviteState.ACCEPTED
        }
        runBlocking {
            assertTrue(
                "accepting makes this device a member",
                db.groupMembershipDao().isMember(groupId, viewModel.userPrefs.deviceId)
            )
            assertNotNull(db.groupDao().getGroupById(groupId))
        }
    }

    @Test
    fun `declining leaves no membership`() {
        val (groupId, nonce) = nextIds()
        seedPending(groupId, nonce)

        viewModel.declineGroupInvite(groupId, nonce)

        val db = application.database
        waitFor("invite declined") {
            db.groupMembershipDao().getInvite(groupId, nonce)?.state == InviteState.DECLINED
        }
        runBlocking {
            assertEquals(
                emptyList<String>(),
                db.groupMembershipDao().membersOf(groupId).map { it.deviceId }
            )
        }
    }

    @Test
    fun `recreating a legacy group mints a secured one and keeps history`() {
        val db = application.database
        runBlocking {
            db.groupDao().insertOrUpdateGroup(
                GroupEntity(
                    groupId = "g_legacy_flow", groupName = "Old Days",
                    description = "history", createdBy = "Someone",
                    createdAt = 1000L, avatarColorIndex = 2, isLegacy = true,
                )
            )
        }

        var callbackFired = false
        var created: GroupEntity? = null
        viewModel.recreateLegacyGroup("g_legacy_flow", emptyList()) {
            created = it
            callbackFired = true
        }
        waitFor("recreate answered") { callbackFired }
        assertNotNull(
            "recreate answered but produced no group; pairwise manager present=" +
                "(com.example.data.security.EncryptionManager.getPairwiseManager() != null)",
            created
        )

        runBlocking {
            assertEquals("Old Days", created!!.groupName)
            assertEquals("history", created!!.description)
            assertEquals(2, created!!.avatarColorIndex)
            assertEquals(false, created!!.isLegacy)
            assertTrue(created!!.groupId != "g_legacy_flow")
            // History is untouched: the legacy row is still there, still legacy.
            val legacy = db.groupDao().getGroupById("g_legacy_flow")!!
            assertEquals(true, legacy.isLegacy)
            assertEquals("Old Days", legacy.groupName)
        }
    }

    @Test
    fun `recreating a non-legacy group refuses`() {
        var called = false
        var result: GroupEntity? = GroupEntity(
            groupId = "x", groupName = "x", createdBy = "x"
        )
        viewModel.recreateLegacyGroup("g_no_such_group", emptyList()) {
            called = true
            result = it
        }
        waitFor("recreate answered") { called }
        assertNull("nothing to recreate means nothing created", result)
    }
}
