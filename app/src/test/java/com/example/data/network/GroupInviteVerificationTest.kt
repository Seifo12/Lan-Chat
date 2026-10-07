package com.example.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.ContactEntity
import com.example.data.local.GroupInviteEntity
import com.example.data.local.GroupInviteReceiver
import com.example.data.local.InviteVerdict
import com.example.data.local.InviteState
import com.example.data.local.UserPreferences
import com.example.data.security.EncryptionManager
import com.example.data.security.GroupInviteCodec
import com.example.data.security.MessageSigningPayload
import com.example.data.security.PairwiseSessionManager
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
 * Phase 1.8: nothing is recorded before the signature verifies, and only an
 * explicit accept writes membership.
 *
 * The order of the eight checks is the security property, not a style choice:
 * a refusal that had already written a nonce record would let an attacker
 * block the genuine invitation that follows, which is the same shape as the TCP
 * replay pre-check bug fixed earlier in this phase.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupInviteVerificationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: ChatDatabase
    private lateinit var prefs: UserPreferences
    private lateinit var sender: PairwiseSessionManager

    private val creatorId = "peer_creator"
    private val myId get() = prefs.deviceId
    private val groupId = "g_00112233445566778899aabbccddeeff"
    private var counter = 0L

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
                    publicKeyBase64 = sender.getMyPublicKeyBase64(),
                    pinnedPublicKey = sender.getMyPublicKeyBase64(),
                )
            )
        }
    }

    private fun inviteJson(
        creator: String = creatorId,
        members: List<GroupInviteCodec.Member> = listOf(
            GroupInviteCodec.Member(creatorId, sender.getMyPublicKeyBase64(), "Creator"),
            GroupInviteCodec.Member(myId, "my-own-key", "Me"),
        ),
        version: Long = 1L,
        nonce: String = "0123456789abcdef0123456789abcdef",
        expiry: Long = System.currentTimeMillis() + GroupInviteCodec.INVITE_TTL_MS,
    ): String = GroupInviteCodec.buildCanonical(
        GroupInviteCodec.Invite(
            creatorId = creator, groupId = groupId, groupName = "The Group",
            description = "", members = members, memberListVersion = version,
            nonce = nonce, expiry = expiry,
        )
    )

    /** Signed over the arrival bytes under the invite label, as the send side will. */
    private fun packet(
        inviteJson: String,
        signer: PairwiseSessionManager = sender,
        senderIdOverride: String? = null,
        signatureOverride: String? = null,
        protocolVersion: Int = ProtocolVersion.CURRENT,
        omitSignature: Boolean = false,
    ): GroupInvitePacket {
        counter++
        val senderId = senderIdOverride ?: creatorId
        val timestamp = 1_700_000_000_000L + counter
        val fields = MessageSigningPayload.Fields(
            senderId = senderId,
            recipientId = myId,
            messageId = "inv_$counter",
            timestamp = timestamp,
            counter = counter,
            contentDigestHex = GroupInviteCodec.contentDigestHex(
                inviteJson.toByteArray(Charsets.UTF_8)
            ),
        )
        return GroupInvitePacket(
            messageId = fields.messageId,
            senderId = senderId,
            senderName = "Creator",
            recipientId = myId,
            inviteJson = inviteJson,
            timestamp = timestamp,
            signatureBase64 = signatureOverride
                ?: if (omitSignature) null else MessageSigningPayload.signWith(signer, fields),
            counter = counter,
            protocolVersion = protocolVersion,
        )
    }

    private suspend fun receive(p: GroupInvitePacket) =
        GroupInviteReceiver(database, prefs).receive(p)

    // ---- accepted ----

    @Test
    fun `a valid invitation becomes pending with the exact arrival bytes`() = runBlocking {
        val json = inviteJson()
        val verdict = receive(packet(json))

        assertTrue("expected Pending, got $verdict", verdict is InviteVerdict.Pending)
        val stored = database.groupMembershipDao().getInvite(groupId, "0123456789abcdef0123456789abcdef")
        assertNotNull("a verified invitation is stored", stored)
        assertEquals(InviteState.PENDING, stored!!.state)
        assertEquals(creatorId, stored.creatorId)
        assertEquals(json, stored.rawBytes.toString(Charsets.UTF_8))
        assertEquals(1L, stored.memberListVersion)
    }

    // ---- refusals: nothing may be written ----

    @Test
    fun `a tampered member list is refused and writes nothing`() = runBlocking {
        val json = inviteJson()
        // Signed first, altered after: what the sender committed to is no
        // longer what arrived, so the digest stops matching.
        val onWire = packet(json)
        val tampered = onWire.copy(inviteJson = json.replace("my-own-key", "attacker-key"))
        val verdict = receive(tampered)
        assertTrue(verdict is InviteVerdict.Refused)
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `an invitation whose creator is not the sender is refused`() = runBlocking {
        val verdict = receive(packet(inviteJson(creator = "peer_someone_else")))
        assertTrue(
            "a packet signed by A naming B as creator must not enrol us",
            verdict is InviteVerdict.Refused
        )
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `a creator whose key changed is refused`() = runBlocking {
        // A second manager would load the same identity key from shared
        // prefs, so a "different" key has to be a different string. It does
        // not need to be a real key: verification runs against the pin, and
        // the pin check below only compares strings.
        val rotatedKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAErotated0000000000000000000000000000="
        runBlocking {
            database.contactDao().insertOrUpdateContact(
                ContactEntity(
                    deviceId = creatorId, displayName = "Creator", ipAddress = "10.0.0.1",
                    publicKeyBase64 = rotatedKey,
                    pinnedPublicKey = sender.getMyPublicKeyBase64(),
                    hasKeyChanged = true,
                )
            )
        }
        val verdict = receive(packet(inviteJson()))
        assertTrue(
            "the 1.6 block exists precisely for this moment",
            verdict is InviteVerdict.Refused
        )
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `an invitation that does not list this device is refused`() = runBlocking {
        val verdict = receive(
            packet(
                inviteJson(
                    members = listOf(
                        GroupInviteCodec.Member(creatorId, sender.getMyPublicKeyBase64(), "Creator")
                    )
                )
            )
        )
        assertTrue(
            "an invitation not naming us is not an invitation for us",
            verdict is InviteVerdict.Refused
        )
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `an expired invitation is refused`() = runBlocking {
        val verdict = receive(
            packet(
                inviteJson(expiry = System.currentTimeMillis() - GroupInviteCodec.INVITE_TTL_MS)
            )
        )
        assertTrue(verdict is InviteVerdict.Refused)
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `an unsigned invitation is refused`() = runBlocking {
        val verdict = receive(packet(inviteJson(), omitSignature = true))
        assertTrue(verdict is InviteVerdict.Refused)
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `a forged signature is refused`() = runBlocking {
        val verdict = receive(packet(inviteJson(), signatureOverride = "bm90LWEtc2lnbmF0dXJl"))
        assertTrue(verdict is InviteVerdict.Refused)
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `an old protocol version is refused`() = runBlocking {
        val verdict = receive(packet(inviteJson(), protocolVersion = ProtocolVersion.MINIMUM - 1))
        assertTrue(verdict is InviteVerdict.Refused)
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    @Test
    fun `a sender we hold no key for is refused`() = runBlocking {
        val verdict = receive(
            packet(inviteJson(), senderIdOverride = "peer_stranger")
        )
        assertTrue(verdict is InviteVerdict.Refused)
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))
    }

    // ---- nonce: recorded on sight, so a replay cannot return after a decline ----

    @Test
    fun `the same nonce twice is refused the second time`() = runBlocking {
        val json = inviteJson()
        val first = receive(packet(json))
        assertTrue(first is InviteVerdict.Pending)

        val receiver = GroupInviteReceiver(database, prefs)
        val second = receiver.receive(packet(json))
        assertTrue(
            "the same invitation presented twice is a replay",
            second is InviteVerdict.Refused
        )
    }

    @Test
    fun `a forgery arriving first does not block the genuine invitation`() = runBlocking {
        val json = inviteJson()
        val receiver = GroupInviteReceiver(database, prefs)

        // A forged packet with a fresh nonce must be refused without writing.
        val forged = receiver.receive(packet(json, signatureOverride = "Zm9yZ2Vk"))
        assertTrue(forged is InviteVerdict.Refused)
        assertNull(database.groupMembershipDao().latestInviteFor(groupId))

        // The genuine one, carrying the same nonce, must still be accepted.
        val genuine = receiver.receive(packet(json))
        assertTrue(
            "a refused forgery must not leave a nonce record that blocks the " +
                "genuine invitation it was imitating; got $genuine",
            genuine is InviteVerdict.Pending
        )
    }

    // ---- accept and decline: the only writers of membership ----

    @Test
    fun `accepting writes the member list and marks the invite accepted`() = runBlocking {
        val receiver = GroupInviteReceiver(database, prefs)
        val json = inviteJson()
        assertTrue(receiver.receive(packet(json)) is InviteVerdict.Pending)

        receiver.acceptInvite(groupId, "0123456789abcdef0123456789abcdef")

        val members = database.groupMembershipDao().membersOf(groupId)
        assertEquals(2, members.size)
        assertTrue(database.groupMembershipDao().isMember(groupId, myId))
        assertTrue(database.groupMembershipDao().isMember(groupId, creatorId))
        assertEquals(
            InviteState.ACCEPTED,
            database.groupMembershipDao().getInvite(groupId, "0123456789abcdef0123456789abcdef")!!.state
        )
        val group = database.groupDao().getGroupById(groupId)
        assertNotNull("accepting creates the secured group", group)
        assertEquals(creatorId, group!!.creatorDeviceId)
        assertEquals(false, group.isLegacy)
    }

    @Test
    fun `accepting writes no membership before the tap`() = runBlocking {
        val receiver = GroupInviteReceiver(database, prefs)
        assertTrue(receiver.receive(packet(inviteJson())) is InviteVerdict.Pending)
        assertEquals(
            "a pending invitation is not a membership",
            emptyList<String>(),
            database.groupMembershipDao().membersOf(groupId).map { it.deviceId }
        )
    }

    @Test
    fun `declining leaves no membership and cannot be undone by the same invite`() = runBlocking {
        val receiver = GroupInviteReceiver(database, prefs)
        val json = inviteJson()
        assertTrue(receiver.receive(packet(json)) is InviteVerdict.Pending)

        receiver.declineInvite(groupId, "0123456789abcdef0123456789abcdef")

        assertEquals(emptyList<String>(), database.groupMembershipDao().membersOf(groupId).map { it.deviceId })
        assertEquals(
            InviteState.DECLINED,
            database.groupMembershipDao().getInvite(groupId, "0123456789abcdef0123456789abcdef")!!.state
        )
        // Re-presenting the declined invitation changes nothing.
        receiver.acceptInvite(groupId, "0123456789abcdef0123456789abcdef")
        assertEquals(emptyList<String>(), database.groupMembershipDao().membersOf(groupId).map { it.deviceId })
    }

    @Test
    fun `accepting an expired invitation marks it expired rather than joining`() = runBlocking {
        val json = inviteJson(
            expiry = System.currentTimeMillis() + GroupInviteCodec.EXPIRY_TOLERANCE_MS / 2
        )
        val receiver = GroupInviteReceiver(database, prefs)
        assertTrue(receiver.receive(packet(json)) is InviteVerdict.Pending)

        // Pretend time passed past the tolerance by re-inserting an expired copy.
        runBlocking {
            database.groupMembershipDao().insertInvite(
                GroupInviteEntity(
                    groupId = "g_expired", nonce = "n", creatorId = creatorId,
                    groupName = "Old", memberListVersion = 1L,
                    expiry = System.currentTimeMillis() - GroupInviteCodec.EXPIRY_TOLERANCE_MS - 1,
                    rawBytes = json.toByteArray(), state = InviteState.PENDING,
                )
            )
        }
        receiver.acceptInvite("g_expired", "n")

        assertEquals(emptyList<String>(), database.groupMembershipDao().membersOf("g_expired").map { it.deviceId })
        assertEquals(
            InviteState.EXPIRED,
            database.groupMembershipDao().getInvite("g_expired", "n")!!.state
        )
    }
}