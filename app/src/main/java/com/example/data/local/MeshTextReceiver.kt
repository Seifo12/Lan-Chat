package com.example.data.local

import android.util.Log
import com.example.data.network.TextMessagePacket
import com.example.data.security.EncryptionManager
import com.example.data.security.GroupInviteCodec
import com.example.data.security.InboundMessageVerifier
import com.example.data.security.InboundVerdict
import com.example.data.security.MessageCounters
import com.example.data.security.MessageSigningPayload
import com.example.data.security.ReplayWindow

/**
 * Phase 1.3 gap fix: the mesh receive path for text.
 *
 * Extracted so the rules can be exercised without standing up Bluetooth.
 *
 * The mesh used to log "Security Alert: Signature mismatch" and then insert the
 * message anyway, so the alert was decorative. Two things were wrong at once.
 * The mismatch was ignored, and the check verified the message text alone while
 * the sender signs the whole 1.3 field set, so the check could never have
 * passed against a genuine sender either. Both halves move together here: the
 * decision is the same [InboundMessageVerifier] the TCP path uses, over the
 * same field set the sender signs, and only [InboundVerdict.Accepted] reaches
 * the database.
 */
internal class MeshTextReceiver(
    private val database: ChatDatabase,
    private val userPreferences: UserPreferences,
    private val isConversationActive: (conversationId: String) -> Boolean = { false },
    private val onAccepted: suspend (TextMessagePacket, String) -> Unit,
) {

    private val replayGuard = ReplayGuard(database.seenIdDao())
    private val counters = MessageCounters(database.peerCounterDao())

    suspend fun receive(packet: TextMessagePacket, fromEndpointId: String): Boolean {
        // A message id already recorded inside the retention window is a replay.
        // This is a pure read on purpose: recording happens only for a message
        // that verified, further down, so a forged packet cannot plant a record
        // that blocks the genuine message arriving after it.
        if (replayGuard.wasRecorded(packet.senderId, packet.messageId)) {
            Log.w(TAG, "Dropping replayed mesh message ${packet.messageId} " +
                "from ${packet.senderId}")
            return false
        }

        val senderKey = database.contactDao().getContactById(packet.senderId)
            ?.publicKeyBase64
            ?: EncryptionManager.getPairwiseManager()
                ?.peerPublicKeyFor(packet.senderId)

        val verdict = InboundMessageVerifier.verify(
            candidate = InboundMessageVerifier.Candidate(
                senderId = packet.senderId,
                recipientId = userPreferences.deviceId,
                messageId = packet.messageId,
                timestamp = packet.timestamp,
                counter = packet.counter,
                protocolVersion = packet.protocolVersion,
                signatureBase64 = packet.signatureBase64,
                // Phase 1.8: v3 group text binds its groupId, so a signed
                // message cannot move between groups. Older digests still
                // verify, so v2 peers stay readable.
                contentDigestHex = if (packet.isGroup && packet.groupId != null &&
                    packet.protocolVersion >= 3
                ) {
                    GroupInviteCodec.groupMessageDigestHex(
                        packet.groupId, packet.text.toByteArray(Charsets.UTF_8)
                    )
                } else {
                    MessageSigningPayload.digestHex(
                        packet.text.toByteArray(Charsets.UTF_8)
                    )
                },
                alreadySeen = false,
            ),
            senderPublicKeyBase64 = senderKey,
            replayDecision = ReplayWindow(
                restoredHighWaterMark = counters.highWaterMarkOf(packet.senderId)
            ).observe(packet.counter, alreadySeen = false),
        )

        when (verdict) {
            is InboundVerdict.Accepted -> {
                replayGuard.tryAccept(packet.senderId, packet.messageId)
                counters.raiseHighWaterMark(packet.senderId, packet.counter)
            }
            is InboundVerdict.UnsupportedProtocol -> {
                Log.w(TAG, "Refusing mesh ${packet.messageId}: protocol " +
                    "${verdict.version} is older than supported")
                return false
            }
            InboundVerdict.MissingSignature -> {
                Log.w(TAG, "Refusing unsigned mesh message ${packet.messageId} " +
                    "from ${packet.senderId}")
                return false
            }
            InboundVerdict.UnknownSender -> {
                Log.w(TAG, "Refusing mesh ${packet.messageId}: no key for ${packet.senderId}")
                return false
            }
            InboundVerdict.BadSignature -> {
                Log.w(TAG, "Refusing mesh ${packet.messageId}: signature does not verify")
                return false
            }
            InboundVerdict.Duplicate -> {
                Log.w(TAG, "Refusing duplicate mesh ${packet.messageId}")
                return false
            }
            InboundVerdict.TooOld -> {
                Log.w(TAG, "Refusing mesh ${packet.messageId}: counter outside the replay window")
                return false
            }
        }

        // Phase 1.8: groups are received only by mutual members, and unknown
        // ids never materialise a group. Legacy ids are history. A refusal
        // here never reaches onAccepted, so no ack leaks presence.
        if (packet.isGroup && packet.groupId != null &&
            !canReceiveGroupMessage(
                database, packet.groupId, packet.senderId, userPreferences.deviceId
            )
        ) {
            Log.w(TAG, "Refusing mesh message for ${packet.groupId}: not a mutual membership")
            return false
        }

        val convId = if (packet.isGroup && !packet.groupId.isNullOrBlank()) {
            packet.groupId
        } else {
            packet.senderId
        }
        val entity = ChatMessageEntity(
            id = packet.messageId,
            conversationId = convId,
            senderId = packet.senderId,
            senderName = packet.senderName,
            recipientId = packet.recipientId,
            text = packet.text,
            isFromMe = false,
            status = if (isConversationActive(convId)) MessageStatus.READ else MessageStatus.DELIVERED,
            isMeshRelayed = true,
            isGroup = packet.isGroup,
            groupName = packet.groupName,
            isSenderDeveloper = packet.isDeveloper,
            timestamp = packet.timestamp,
        )
        database.chatMessageDao().insertMessage(entity)
        onAccepted(packet, fromEndpointId)
        return true
    }

    private companion object {
        const val TAG = "MeshTextReceiver"
    }
}
