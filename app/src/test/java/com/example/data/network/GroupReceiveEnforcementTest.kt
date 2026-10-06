package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.GroupEntity
import com.example.data.local.GroupMemberEntity
import com.example.data.local.InviteState
import com.example.data.local.MeshTextReceiver
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import com.example.data.security.GroupInviteCodec
import com.example.data.security.MessageSigningPayload
import com.example.data.security.PairwiseSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.8: groups are received only by mutual members and never created.
 *
 * The four auto-create blocks are gone: an unknown groupId is dropped on every
 * transport instead of materialising a group, and legacy ids accept no new
 * arrivals. Group text verifies only when both sides hold member rows, and v3
 * group text binds its groupId in the digest so a signed message cannot move
 * between groups. v2 group messages still verify under the old digest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupReceiveEnforcementTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ChatDatabase
    private lateinit var prefs: UserPreferences
    private lateinit var tcp: TcpMessagingManager
    private lateinit var mesh: MeshTextReceiver
    private lateinit var sender: PairwiseSessionManager
    private lateinit var outsider: PairwiseSessionManager

    private val peerId = "peer_member"
    private val outsiderId = "peer_outsider"
    private val groupId = "g_00112233445566778899aabbccddeeff"
    private val legacyId = "group_legacy"
    private var counter = 100L

    @Before
    fun setUp() {
        EncryptionManager.initializePairwiseManager(context)
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = UserPreferences(context)
        tcp = TcpMessagingManager(context, database, prefs)
        mesh = MeshTextReceiver(
            database = database,
            userPreferences = prefs,
            onAccepted = { _, _ -> },
        )
        sender = PairwiseSessionManager(context)
        outsider = PairwiseSessionManager(context)
        runBlocking {
            val dao = database.contactDao()
            dao.insertOrUpdateContact(
                ContactEntity(
                    deviceId = peerId, displayName = "Member", ipAddress = "10.0.0.1",
                    publicKeyBase64 = sender.getMyPublicKeyBase64(),
                    pinnedPublicKey = sender.getMyPublicKeyBase64(),
                )
            )
            dao.insertOrUpdateContact(
                ContactEntity(
                    deviceId = outsiderId, displayName = "Outsider", ipAddress = "10.0.0.2",
                    publicKeyBase64 = outsider.getMyPublicKeyBase64(),
                    pinnedPublicKey = outsider.getMyPublicKeyBase64(),
                )
            )
            database.groupDao().insertOrUpdateGroup(
                GroupEntity(
                    groupId = groupId, groupName = "The Group", createdBy = peerId,
                    creatorDeviceId = peerId, memberListVersion = 1L, isLegacy = false,
                )
            )
            database.groupDao().insertOrUpdateGroup(
                GroupEntity(
                    groupId = legacyId, groupName = "Legacy", createdBy = "Someone",
                    isLegacy = true,
                )
            )
            val membership = database.groupMembershipDao()
            membership.replaceMembers(
                groupId,
                listOf(
                    GroupMemberEntity(groupId, peerId, sender.getMyPublicKeyBase64(), "Member"),
                    GroupMemberEntity(groupId, prefs.deviceId, "my-own-key", "Me"),
                )
            )
        }
        tcp.setActiveConversation(groupId)
    }

    private fun v3GroupText(
        text: String,
        messageId: String,
        fromId: String = peerId,
        toGroup: String = groupId,
        signer: PairwiseSessionManager = sender,
    ): TextMessagePacket {
        counter++
        val timestamp = 1_700_000_000_000L + counter
        val fields = MessageSigningPayload.Fields(
            senderId = fromId,
            recipientId = prefs.deviceId,
            messageId = messageId,
            timestamp = timestamp,
            counter = counter,
            contentDigestHex = GroupInviteCodec.groupMessageDigestHex(
                toGroup, text.toByteArray()
            ),
        )
        return TextMessagePacket(
            messageId = messageId, senderId = fromId, senderName = "Member",
            recipientId = prefs.deviceId, text = text, timestamp = timestamp,
            isGroup = true, groupId = toGroup, groupName = "The Group",
            signatureBase64 = MessageSigningPayload.signWith(signer, fields),
            counter = counter, protocolVersion = ProtocolVersion.CURRENT,
        )
    }

    /**
     * Signed the old way: content digest over the text alone, no group
     * binding. The old code verifies these, which is what makes the refusal
     * tests prove the membership hole instead of a signature mismatch: before
     * the fix they are accepted and stored (red), after the fix the gate
     * refuses them while the signature still verifies (green).
     */
    private fun v2GroupText(
        text: String,
        messageId: String,
        fromId: String = peerId,
        toGroup: String = groupId,
        signer: PairwiseSessionManager = sender,
    ): TextMessagePacket {
        counter++
        val timestamp = 1_700_000_000_000L + counter
        val fields = MessageSigningPayload.Fields(
            senderId = fromId,
            recipientId = prefs.deviceId,
            messageId = messageId,
            timestamp = timestamp,
            counter = counter,
            contentDigestHex = MessageSigningPayload.digestHex(text.toByteArray()),
        )
        return TextMessagePacket(
            messageId = messageId, senderId = fromId, senderName = "Member",
            recipientId = prefs.deviceId, text = text, timestamp = timestamp,
            isGroup = true, groupId = toGroup, groupName = "The Group",
            signatureBase64 = MessageSigningPayload.signWith(signer, fields),
            counter = counter, protocolVersion = ProtocolVersion.CURRENT,
        )
    }

    private suspend fun storedText(id: String): String? =
        database.chatMessageDao().getMessageById(id)?.text

    // ---- TCP ----

    @Test
    fun `a member text to our group still arrives over tcp`() = runBlocking {
        tcp.processReceivedPacket(
            v3GroupText("hello members", messageId = "g_ok_1"), senderIp = "10.0.0.1"
        )
        assertEquals("hello members", storedText("g_ok_1"))
    }

    @Test
    fun `a non-member text to our group is dropped over tcp`() = runBlocking {
        tcp.processReceivedPacket(
            v2GroupText(
                "let me in", messageId = "g_no_1", fromId = outsiderId, signer = outsider
            ),
            senderIp = "10.0.0.2"
        )
        assertNull(
            "no member row for the sender means no delivery",
            storedText("g_no_1")
        )
    }

    @Test
    fun `an unknown group id creates nothing over tcp`() = runBlocking {
        // Signed for the unknown id, so verification itself passes: the drop
        // must come from the membership rule, not from a bad signature.
        tcp.processReceivedPacket(
            v2GroupText("new group?", messageId = "g_unk_1", toGroup = "g_nonexistent"),
            senderIp = "10.0.0.1"
        )
        assertNull(storedText("g_unk_1"))
        assertNull(
            "an unknown groupId never materialises a group",
            database.groupDao().getGroupById("g_nonexistent")
        )
    }

    @Test
    fun `a legacy group id accepts no new arrivals over tcp`() = runBlocking {
        tcp.processReceivedPacket(
            v2GroupText("history only", messageId = "g_leg_1", toGroup = legacyId),
            senderIp = "10.0.0.1"
        )
        assertNull(
            "legacy is readable history; nothing is appended",
            storedText("g_leg_1")
        )
    }

    @Test
    fun `a v3 message moved between groups fails verification over tcp`() = runBlocking {
        val onWire = v3GroupText("for group one", messageId = "g_bind_1")
        // The signature commits to the original group; the moved copy keeps
        // the signature but names no group binding of its own.
        val moved = onWire.copy(groupId = "g_nonexistent")
        tcp.processReceivedPacket(moved, senderIp = "10.0.0.1")
        assertNull(
            "moving a signed message between groups must break it",
            storedText("g_bind_1")
        )
    }

    @Test
    fun `an invite packet becomes pending through the tcp branch`() = runBlocking {
        val creator = peerId
        val inviteJson = GroupInviteCodec.buildCanonical(
            GroupInviteCodec.Invite(
                creatorId = creator, groupId = "g_inv_tcp", groupName = "Invited",
                description = "", members = listOf(
                    GroupInviteCodec.Member(creator, sender.getMyPublicKeyBase64(), "Creator"),
                    GroupInviteCodec.Member(prefs.deviceId, "my-own-key", "Me"),
                ),
                memberListVersion = 1L,
                nonce = "aaabbbcccdddeeefffggghhhiiijjjkk",
                expiry = System.currentTimeMillis() + GroupInviteCodec.INVITE_TTL_MS,
            )
        )
        counter++
        val timestamp = 1_700_000_000_000L + counter
        val fields = MessageSigningPayload.Fields(
            senderId = creator, recipientId = prefs.deviceId,
            messageId = "inv_tcp_1", timestamp = timestamp, counter = counter,
            contentDigestHex = GroupInviteCodec.contentDigestHex(
                inviteJson.toByteArray(Charsets.UTF_8)
            ),
        )
        tcp.processReceivedPacket(
            GroupInvitePacket(
                messageId = fields.messageId, senderId = creator, senderName = "Creator",
                recipientId = prefs.deviceId, inviteJson = inviteJson, timestamp = timestamp,
                signatureBase64 = MessageSigningPayload.signWith(sender, fields),
                counter = counter, protocolVersion = ProtocolVersion.CURRENT,
            ),
            senderIp = "10.0.0.1"
        )
        assertEquals(
            InviteState.PENDING,
            database.groupMembershipDao().getInvite(
                "g_inv_tcp", "aaabbbcccdddeeefffggghhhiiijjjkk"
            )?.state
        )
    }

    // ---- mesh ----

    @Test
    fun `a member text to our group still arrives over mesh`() = runBlocking {
        val accepted = mesh.receive(v3GroupText("hello mesh", messageId = "m_ok_1"), "ep_1")
        assertTrue(accepted)
        assertEquals("hello mesh", storedText("m_ok_1"))
    }

    @Test
    fun `a non-member text to our group is dropped over mesh`() = runBlocking {
        val accepted = mesh.receive(
            v2GroupText(
                "let me in", messageId = "m_no_1", fromId = outsiderId, signer = outsider
            ),
            "ep_1"
        )
        assertTrue("refused, not stored", !accepted)
        assertNull(storedText("m_no_1"))
    }

    @Test
    fun `an unknown group id creates nothing over mesh`() = runBlocking {
        val accepted = mesh.receive(
            v2GroupText("new group?", messageId = "m_unk_1", toGroup = "g_mesh_ghost"), "ep_1"
        )
        assertTrue(!accepted)
        assertNull(storedText("m_unk_1"))
        assertNull(database.groupDao().getGroupById("g_mesh_ghost"))
    }

    @Test
    fun `a legacy group id accepts no new arrivals over mesh`() = runBlocking {
        val accepted = mesh.receive(
            v2GroupText("history only", messageId = "m_leg_1", toGroup = legacyId), "ep_1"
        )
        assertTrue(!accepted)
        assertNull(storedText("m_leg_1"))
    }
}
