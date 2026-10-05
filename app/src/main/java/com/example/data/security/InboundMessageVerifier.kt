package com.example.data.security

import com.example.data.network.ProtocolVersion

/**
 * Phase 1.3, audit finding C1: what to do with an inbound message packet.
 *
 * Before this, a packet whose signature did not verify was logged and then
 * delivered anyway, a packet with no signature at all was delivered, and a
 * sender whose key we had never seen was not checked. All three are now refusals,
 * because a signature that is not checked is not a signature.
 *
 * The outcomes are distinguished rather than collapsed into a boolean so the
 * caller can tell the user something useful: a peer that is merely old needs a
 * different message from one that looks like an attacker.
 */
sealed interface InboundVerdict {
    /** Authenticated and inside the replay window. Process it. */
    data class Accepted(val counter: Long) : InboundVerdict

    /** No signature at all, so nothing was proven about the sender. */
    data object MissingSignature : InboundVerdict

    /** A signature was present and did not verify. */
    data object BadSignature : InboundVerdict

    /** We hold no public key for this sender, so nothing can be verified. */
    data object UnknownSender : InboundVerdict

    /** Older than [ProtocolVersion.MINIMUM]. */
    data class UnsupportedProtocol(val version: Int) : InboundVerdict

    /** A message id already recorded, inside the retention window. */
    data object Duplicate : InboundVerdict

    /** A counter too far below the high-water mark to be plausible reordering. */
    data object TooOld : InboundVerdict
}

object InboundMessageVerifier {

    /**
     * The fields every signed message carries, plus the content digest the
     * signature commits to.
     */
    data class Candidate(
        val senderId: String,
        val recipientId: String,
        val messageId: String,
        val timestamp: Long,
        val counter: Long,
        val protocolVersion: Int,
        val signatureBase64: String?,
        val contentDigestHex: String,
        val alreadySeen: Boolean,
    )

    /**
     * Order matters and is deliberate. Protocol and signature are checked before
     * the replay window, because a packet we cannot authenticate should not be
     * allowed to move our high-water mark, and an old client's counter means
     * something different anyway.
     */
    fun verify(
        candidate: Candidate,
        senderPublicKeyBase64: String?,
        replayDecision: ReplayDecision,
    ): InboundVerdict {
        if (!ProtocolVersion.isSupported(candidate.protocolVersion)) {
            return InboundVerdict.UnsupportedProtocol(candidate.protocolVersion)
        }
        val signature = candidate.signatureBase64
            ?: return InboundVerdict.MissingSignature
        val publicKey = senderPublicKeyBase64
            ?: return InboundVerdict.UnknownSender

        val fields = MessageSigningPayload.Fields(
            senderId = candidate.senderId,
            recipientId = candidate.recipientId,
            messageId = candidate.messageId,
            timestamp = candidate.timestamp,
            counter = candidate.counter,
            contentDigestHex = candidate.contentDigestHex,
        )
        // The digest is computed by the caller from the content that actually
        // arrived, so altering the body changes the digest and the signature stops
        // matching. There is no separate content check to perform here.
        if (!MessageSigningPayload.verify(publicKey, fields, signature)) {
            return InboundVerdict.BadSignature
        }

        return when (replayDecision) {
            ReplayDecision.ACCEPT -> InboundVerdict.Accepted(candidate.counter)
            ReplayDecision.DUPLICATE -> InboundVerdict.Duplicate
            ReplayDecision.TOO_OLD -> InboundVerdict.TooOld
        }
    }
}