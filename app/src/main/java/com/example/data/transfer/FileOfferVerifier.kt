package com.example.data.transfer

import com.example.data.security.GroupInviteCodec
import com.example.data.security.MessageSigningPayload

sealed interface OfferVerdict {
    object Accepted : OfferVerdict
    data class Refused(val reason: RefusedReason, val detail: String? = null) : OfferVerdict
}

/**
 * Single-use transfer ids, so a captured offer cannot admit a second stream.
 *
 * Pruned at twice the consent hold, which is the longest a genuine transfer
 * can legitimately remain in flight: the sender waits SENDER_OFFER_TIMEOUT_MS,
 * the receiver holds for PENDING_HOLD_MS, and anything older than both cannot
 * still be legitimate.
 */
class OfferReplayGuard {

    private val consumed = HashMap<String, Long>()

    fun tryConsume(transferId: String, nowMs: Long): Boolean {
        prune(nowMs)
        if (consumed.containsKey(transferId)) return false
        consumed[transferId] = nowMs
        return true
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - TransferAdmissionLimits.PENDING_HOLD_MS * 2
        val it = consumed.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value <= cutoff) it.remove()
        }
    }

    /** Test and diagnostics hook. */
    fun trackedCount(): Int = consumed.size
}

/**
 * Decides whether an offer may be believed.
 *
 * `canonicalJson` is passed in rather than recomputed here. The digest has to
 * be taken over the bytes that actually arrived; re-serialising the parsed
 * offer would let a caller verify something other than what it received, which
 * is precisely the failure that 1.3 shipped.
 */
object FileOfferVerifier {

    fun verify(
        offer: FileOffer,
        canonicalJson: String,
        senderPinnedKeyBase64: String?,
        alreadySeen: Boolean,
        nowMs: Long,
    ): OfferVerdict {
        // 1. Identity first. An offer nobody can authenticate must not be
        //    allowed to consume a replay slot or move any state, or a stranger
        //    could burn transfer ids and lock the genuine sender out.
        if (senderPinnedKeyBase64.isNullOrBlank()) {
            return OfferVerdict.Refused(RefusedReason.UNVERIFIED_OFFER, "no pinned key for ${offer.senderId}")
        }
        val signature = offer.signatureBase64
            ?: return OfferVerdict.Refused(RefusedReason.UNVERIFIED_OFFER, "offer carries no signature")
        // 2. Expiry, with the same tolerance the invitation codec allows, so a
        //    modest clock difference between two phones is not a refusal.
        if (nowMs > offer.expiry + GroupInviteCodec.EXPIRY_TOLERANCE_MS) {
            return OfferVerdict.Refused(RefusedReason.OFFER_EXPIRED)
        }
        // 3. Replay.
        if (alreadySeen) {
            return OfferVerdict.Refused(RefusedReason.OFFER_REPLAY)
        }
        // 4. The signature itself, over the canonical bytes and the labelled
        //    digest of them.
        val fields = MessageSigningPayload.Fields(
            senderId = offer.senderId,
            recipientId = offer.recipientId,
            messageId = offer.transferId,
            timestamp = offer.createdAt,
            counter = 0,
            contentDigestHex = FileOfferCodec.contentDigestHex(
                canonicalJson.toByteArray(Charsets.UTF_8)
            ),
        )
        if (!MessageSigningPayload.verify(senderPinnedKeyBase64, fields, signature)) {
            return OfferVerdict.Refused(RefusedReason.UNVERIFIED_OFFER, "signature does not match the offered bytes")
        }
        return OfferVerdict.Accepted
    }
}