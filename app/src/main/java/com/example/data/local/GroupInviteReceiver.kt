package com.example.data.local

import android.util.Log
import com.example.data.network.GroupInvitePacket
import com.example.data.security.EncryptionManager
import com.example.data.security.GroupInviteCodec
import com.example.data.security.InboundMessageVerifier
import com.example.data.security.InboundVerdict
import com.example.data.security.MessageCounters
import com.example.data.security.ReplayWindow

/** What became of an invitation, or why it was refused. */
sealed interface InviteVerdict {
    /** Verified, and waiting for an explicit user decision. Nothing was joined. */
    data object Pending : InviteVerdict

    /** A creator-signed higher-version list replaced the roster. */
    data object UpdateApplied : InviteVerdict

    data class Refused(val reason: String) : InviteVerdict
}

/**
 * Phase 1.8: the ordered checks from the spec, then the state machine.
 *
 * The order is the security property, not a style choice. Nothing is recorded
 * before the signature verifies: a refusal that had already written a nonce
 * record would let an attacker plant one and block the genuine invitation that
 * follows, which is the same failure shape as the TCP replay pre-check fixed in
 * the same phase. Only a verified invitation writes a row, and only an explicit
 * accept writes membership.
 */
internal class GroupInviteReceiver(
    private val database: ChatDatabase,
    private val userPreferences: UserPreferences,
) {

    private val replayGuard = ReplayGuard(database.seenIdDao())
    private val counters = MessageCounters(database.peerCounterDao())

    suspend fun receive(packet: GroupInvitePacket): InviteVerdict {
        // 1. A delivery we already recorded is a replay.
        if (replayGuard.wasRecorded(packet.senderId, packet.messageId)) {
            return refuse("duplicate delivery of ${packet.messageId}")
        }

        // 2. The creator must be pin-verified. A changed key is refused here,
        // where the 1.6 block matters most, rather than anywhere later.
        val contact = database.contactDao().getContactById(packet.senderId)
        val pinned = contact?.pinnedPublicKey
        val presenting = contact?.publicKeyBase64
            ?: EncryptionManager.getPairwiseManager()?.peerPublicKeyFor(packet.senderId)
        if (pinned == null || presenting == null || pinned != presenting) {
            return refuse("creator ${packet.senderId} is not pin-verified")
        }

        // 3. The transport rules: signature, counter, replay window. All of them
        //    over the arrival bytes, never a re-serialisation.
        val inviteBytes = packet.inviteJson.toByteArray(Charsets.UTF_8)
        val transport = InboundMessageVerifier.verify(
            candidate = InboundMessageVerifier.Candidate(
                senderId = packet.senderId,
                recipientId = userPreferences.deviceId,
                messageId = packet.messageId,
                timestamp = packet.timestamp,
                counter = packet.counter,
                protocolVersion = packet.protocolVersion,
                signatureBase64 = packet.signatureBase64,
                contentDigestHex = GroupInviteCodec.contentDigestHex(inviteBytes),
                alreadySeen = false,
            ),
            senderPublicKeyBase64 = pinned,
            replayDecision = ReplayWindow(
                restoredHighWaterMark = counters.highWaterMarkOf(packet.senderId)
            ).observe(packet.counter, alreadySeen = false),
        )
        if (transport !is InboundVerdict.Accepted) {
            return refuse("transport verification: $transport")
        }

        val invite = GroupInviteCodec.parse(packet.inviteJson)
            ?: return refuse("invitation does not parse")

        // 4. The named creator is the sender we just verified.
        if (invite.creatorId != packet.senderId) {
            return refuse("creator ${invite.creatorId} is not the verified sender")
        }

        val membership = database.groupMembershipDao()
        val isMember = membership.isMember(invite.groupId, userPreferences.deviceId)

        // 5. Membership versus update, decided by local state. An update that
        //    omits this device is a removal, so it must not be refused for that.
        if (!isMember && invite.members.none { it.deviceId == userPreferences.deviceId }) {
            return refuse("this device is not listed as a member")
        }

        // 6. Expiry, with a skew tolerance. A missing or unparseable expiry never
        //    parses at all, so it was already refused at step 3.
        val now = System.currentTimeMillis()
        if (invite.expiry + GroupInviteCodec.EXPIRY_TOLERANCE_MS < now) {
            return refuse("invitation expired")
        }

        // 7. Record on sight, accepted or not, so a replayed invitation cannot be
        //    re-presented after a decline. Namespaced to stay disjoint from ids.
        if (!replayGuard.tryAccept(packet.senderId, "invite:${invite.nonce}")) {
            return refuse("invitation nonce already used")
        }
        replayGuard.tryAccept(packet.senderId, packet.messageId)
        counters.raiseHighWaterMark(packet.senderId, packet.counter)

        // 8. Version, relative to what this device has seen. A device invited at
        //    version 3 has no earlier version and must not be refused for that.
        val seenVersion = maxOf(
            if (isMember) {
                database.groupDao().getGroupById(invite.groupId)?.memberListVersion ?: 0L
            } else {
                0L
            },
            membership.latestInviteFor(invite.groupId)?.takeIf { it.state != InviteState.SUPERSEDED }
                ?.memberListVersion ?: 0L,
        )
        if (isMember && invite.memberListVersion <= seenVersion) {
            return refuse("version ${invite.memberListVersion} is not newer than $seenVersion")
        }

        val existing = database.groupDao().getGroupById(invite.groupId)

        if (isMember) {
            // An update: authoritative, so the roster is replaced wholesale and
            // absence means removal. No tap, because the creator already holds
            // the authority to say who is in the group.
            applyMemberList(invite)
            membership.insertInvite(
                GroupInviteEntity(
                    groupId = invite.groupId, nonce = invite.nonce,
                    creatorId = invite.creatorId, groupName = invite.groupName,
                    memberListVersion = invite.memberListVersion, expiry = invite.expiry,
                    rawBytes = inviteBytes, state = InviteState.SUPERSEDED,
                )
            )
            if (existing != null) {
                database.groupDao().insertOrUpdateGroup(
                    existing.copy(
                        groupName = invite.groupName,
                        memberListVersion = invite.memberListVersion,
                    )
                )
            }
            return InviteVerdict.UpdateApplied
        }

        // First contact, or a re-invite after a terminal state. Either way the
        // user decides: this row is an invitation, not a membership.
        membership.insertInvite(
            GroupInviteEntity(
                groupId = invite.groupId, nonce = invite.nonce,
                creatorId = invite.creatorId, groupName = invite.groupName,
                memberListVersion = invite.memberListVersion, expiry = invite.expiry,
                rawBytes = inviteBytes, state = InviteState.PENDING,
            )
        )
        if (existing != null && existing.isLegacy) {
            // A legacy row keeps its history and its legacy flag. Accepting an
            // invitation creates a secured group; it does not convert history.
            Log.w(TAG, "Invitation for legacy group ${invite.groupId}: history stays legacy")
        }
        return InviteVerdict.Pending
    }

    /** The explicit accept tap. The only writer of membership from an invitation. */
    suspend fun acceptInvite(groupId: String, nonce: String) {
        val membership = database.groupMembershipDao()
        val invite = membership.getInvite(groupId, nonce) ?: return
        if (invite.state != InviteState.PENDING) return
        if (invite.expiry + GroupInviteCodec.EXPIRY_TOLERANCE_MS < System.currentTimeMillis()) {
            membership.setInviteState(groupId, nonce, InviteState.EXPIRED)
            return
        }
        val parsed = GroupInviteCodec.parse(invite.rawBytes.toString(Charsets.UTF_8)) ?: return
        applyMemberList(parsed)
        val existing = database.groupDao().getGroupById(groupId)
        database.groupDao().insertOrUpdateGroup(
            GroupEntity(
                groupId = groupId,
                groupName = invite.groupName,
                description = existing?.description ?: "",
                createdBy = invite.creatorId,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                avatarColorIndex = existing?.avatarColorIndex ?: 0,
                creatorDeviceId = invite.creatorId,
                memberListVersion = parsed.memberListVersion,
                // Never converts a legacy row: history stays readable and
                // unwritable, and the secured group is a separate thing.
                isLegacy = existing?.isLegacy ?: false,
            )
        )
        membership.setInviteState(groupId, nonce, InviteState.ACCEPTED)
    }

    suspend fun declineInvite(groupId: String, nonce: String) {
        val membership = database.groupMembershipDao()
        if (membership.getInvite(groupId, nonce)?.state == InviteState.PENDING) {
            membership.setInviteState(groupId, nonce, InviteState.DECLINED)
        }
    }

    /**
     * Leaving is local only: no packet, no signature, no counter, because
     * removing yourself grants nobody anything.
     */
    suspend fun leaveGroup(groupId: String) {
        database.groupMembershipDao().removeMembership(groupId, userPreferences.deviceId)
    }

    private suspend fun applyMemberList(invite: GroupInviteCodec.Invite) {
        database.groupMembershipDao().replaceMembers(
            invite.groupId,
            invite.members.map {
                GroupMemberEntity(
                    groupId = invite.groupId,
                    deviceId = it.deviceId,
                    publicKeyBase64 = it.publicKeyBase64,
                    displayName = it.displayName,
                )
            }
        )
    }

    private fun refuse(reason: String): InviteVerdict.Refused {
        Log.w(TAG, "Refusing group invite: $reason")
        return InviteVerdict.Refused(reason)
    }



    private companion object {
        const val TAG = "GroupInviteReceiver"
    }
}

/**
 * Phase 1.8: whether a group message may be stored. True only for mutual
 * membership: a group row that is not legacy, with a member row for this
 * device and one for the sender. Everything else -- unknown ids, legacy ids,
 * non-members -- is dropped. One implementation shared by both transports so
 * the two receive paths cannot drift.
 */
internal suspend fun canReceiveGroupMessage(
    database: ChatDatabase,
    groupId: String,
    senderId: String,
    myDeviceId: String,
): Boolean {
    val group = database.groupDao().getGroupById(groupId) ?: return false
    if (group.isLegacy) return false
    val membership = database.groupMembershipDao()
    if (!membership.isMember(groupId, myDeviceId)) return false
    return membership.isMember(groupId, senderId)
}
