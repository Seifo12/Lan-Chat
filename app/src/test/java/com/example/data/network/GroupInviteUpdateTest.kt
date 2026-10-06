package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.GroupInviteReceiver
import com.example.data.local.InviteState
import com.example.data.local.InviteVerdict
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import com.example.data.security.GroupInviteCodec
import com.example.data.security.MessageSigningPayload
import com.example.data.security.PairwiseSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: version is relative to what this device has seen, updates replace
 * the roster wholesale, and leaving is a local act.
 *
 * A device invited at version 3 has no earlier version and must not be refused
 * for being new; an update at an equal or lower version than seen is a replay
 * or a rollback and is refused. None of this moves without the creator's key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupInviteUpdateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ChatDatabase
    private lateinit var prefs: UserPreferences
    private lateinit var sender: PairwiseSessionManager

    private val creatorId = "peer_creator"
    private val myId get() = prefs.deviceId
    private val groupId = "g_00112233445566778899aabbccddeeff"
    private val creatorKey get() = sender.getMyPublicKeyBase64()
    private var counter = 0L
    private var nonceCounter = 0

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
                    deviceId = creatorId,
                    displayName = "Creator",
                    ipAddress = "10.0.0.1",
                    publicKeyBase64 = creatorKey,
                    pinnedPublicKey = creatorKey,
                )
            )
        }
    }

    /** Exactly 32 chars with the counter intact: truncating it once made every nonce identical. */
    private fun freshNonce(): String = "nonce%027d".format(++nonceCounter)

    private fun inviteJson(
        members: List<GroupInviteCodec.Member> = listOf(
            GroupInviteCodec.Member(creatorId, creatorKey, "Creator"),
            GroupInviteCodec.Member(myId, "my-own-key", "Me"),
        ),
        version: Long = 1L,
        nonce: String = freshNonce(),
    ): String = GroupInviteCodec.buildCanonical(
        GroupInviteCodec.Invite(
            creatorId = creatorId, groupId = groupId, groupName = "The Group",
            description = "", members = members, memberListVersion = version,
            nonce = nonce,
            expiry = System.currentTimeMillis() + GroupInviteCodec.INVITE_TTL_MS,
        )
    )

    private fun packet(inviteJson: String): GroupInvitePacket {
        counter++
        val timestamp = 1_700_000_000_000L + counter
        val fields = MessageSigningPayload.Fields(
            senderId = creatorId,
            recipientId = myId,
            messageId = "upd_$counter",
            timestamp = timestamp,
            counter = counter,
            contentDigestHex = GroupInviteCodec.contentDigestHex(
                inviteJson.toByteArray(Charsets.UTF_8)
            ),
        )
        return GroupInvitePacket(
            messageId = fields.messageId,
            senderId = creatorId,
            senderName = "Creator",
            recipientId = myId,
            inviteJson = inviteJson,
            timestamp = timestamp,
            signatureBase64 = MessageSigningPayload.signWith(sender, fields),
            counter = counter,
            protocolVersion = ProtocolVersion.CURRENT,
        )
    }

    private suspend fun acceptV1(): GroupInviteReceiver {
        val receiver = GroupInviteReceiver(database, prefs)
        val json = inviteJson()
        assertTrue(receiver.receive(packet(json)) is InviteVerdict.Pending)
        val nonce = GroupInviteCodec.parse(json)!!.nonce
        receiver.acceptInvite(groupId, nonce)
        assertTrue(
            "setup: accepting v1 makes this device a member",
            database.groupMembershipDao().isMember(groupId, myId)
        )
        return receiver
    }

    @Test
    fun `a higher version from the creator replaces the roster`() = runBlocking {
        val receiver = acceptV1()

        val v2 = inviteJson(
            members = listOf(
                GroupInviteCodec.Member(creatorId, creatorKey, "Creator"),
                GroupInviteCodec.Member(myId, "my-own-key", "Me"),
                GroupInviteCodec.Member("peer_new", "pk_new", "New"),
            ),
            version = 2L,
        )
        val verdict = receiver.receive(packet(v2))

        assertTrue("expected UpdateApplied, got $verdict", verdict is InviteVerdict.UpdateApplied)
        assertEquals(
            // membersOf orders by deviceId; the roster, not the order, is asserted.
            listOf(creatorId, myId, "peer_new").sorted(),
            database.groupMembershipDao().membersOf(groupId).map { it.deviceId }
        )
        assertEquals(2L, database.groupDao().getGroupById(groupId)?.memberListVersion)
    }

    @Test
    fun `an equal or lower version is refused and changes nothing`() = runBlocking {
        val receiver = acceptV1()

        val same = inviteJson(version = 1L)
        assertTrue(receiver.receive(packet(same)) is InviteVerdict.Refused)
        assertEquals(
            "a replayed version must not touch the roster",
            listOf(creatorId, myId).sorted(),
            database.groupMembershipDao().membersOf(groupId).map { it.deviceId }
        )
        assertEquals(1L, database.groupDao().getGroupById(groupId)?.memberListVersion)
    }

    @Test
    fun `an update that omits this device removes it`() = runBlocking {
        val receiver = acceptV1()

        val v2 = inviteJson(
            members = listOf(
                GroupInviteCodec.Member(creatorId, creatorKey, "Creator"),
            ),
            version = 2L,
        )
        val verdict = receiver.receive(packet(v2))

        assertTrue("expected UpdateApplied, got $verdict", verdict is InviteVerdict.UpdateApplied)
        assertEquals(
            "absence from the new list is removal, not a refusal",
            listOf(creatorId),
            database.groupMembershipDao().membersOf(groupId).map { it.deviceId }
        )
    }

    @Test
    fun `leaving is local and needs no packet`() = runBlocking {
        val receiver = acceptV1()

        receiver.leaveGroup(groupId)

        assertEquals(
            listOf(creatorId),
            database.groupMembershipDao().membersOf(groupId).map { it.deviceId }
        )
        // Leaving twice is harmless.
        receiver.leaveGroup(groupId)
        assertEquals(
            listOf(creatorId),
            database.groupMembershipDao().membersOf(groupId).map { it.deviceId }
        )
    }

    @Test
    fun `a fresh invitation after a decline returns to pending`() = runBlocking {
        val receiver = GroupInviteReceiver(database, prefs)
        val first = inviteJson()
        assertTrue(receiver.receive(packet(first)) is InviteVerdict.Pending)
        receiver.declineInvite(groupId, GroupInviteCodec.parse(first)!!.nonce)

        val second = inviteJson()
        val verdict = receiver.receive(packet(second))

        assertTrue(
            "a re-invite with an unused nonce is a new decision, got $verdict",
            verdict is InviteVerdict.Pending
        )
        assertEquals(
            InviteState.PENDING,
            database.groupMembershipDao()
                .getInvite(groupId, GroupInviteCodec.parse(second)!!.nonce)!!.state
        )
    }

    @Test
    fun `joining an established group at version 3 is accepted`() = runBlocking {
        val receiver = GroupInviteReceiver(database, prefs)
        val verdict = receiver.receive(packet(inviteJson(version = 3L)))

        assertTrue(
            "version is relative to what was seen, and nothing was; got $verdict",
            verdict is InviteVerdict.Pending
        )
    }
}
