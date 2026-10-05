package com.example.data.security

import java.security.MessageDigest

/**
 * Phase 1.3, audit finding C1.
 *
 * The bytes a message signature commits to.
 *
 * Until this, a signature covered only the message text, so a valid signature
 * could be lifted off one message and presented on another. Everything an
 * attacker would want to change is now inside the covered set: the sender, the
 * recipient, the message id, the claimed time, the sequence number, and a digest
 * of the content. The counter in particular must be signed, because a counter
 * that is not covered can be stripped and rewritten to look like a fresh message.
 *
 * The domain prefix is there so a message signature can never be replayed as
 * some other kind of signature, such as a TLS certificate proof.
 *
 * Fields are newline separated so the encoding is unambiguous. A bare
 * concatenation would let a character be moved from one field into the next and
 * leave the payload unchanged, which is exactly the ambiguity to avoid here.
 */
object MessageSigningPayload {

    /** Bumped when the covered set changes, so old and new signatures cannot mix. */
    const val DOMAIN_PREFIX = "LanChat-msg-v2"

    data class Fields(
        val senderId: String,
        val recipientId: String,
        val messageId: String,
        val timestamp: Long,
        val counter: Long,
        val contentDigestHex: String,
    )

    fun digestHex(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    fun build(fields: Fields): String = buildString {
        append(DOMAIN_PREFIX).append('\n')
        append(fields.senderId).append('\n')
        append(fields.recipientId).append('\n')
        append(fields.messageId).append('\n')
        append(fields.timestamp).append('\n')
        append(fields.counter).append('\n')
        append(fields.contentDigestHex)
    }

    fun sign(fields: Fields): String? =
        EncryptionManager.getPairwiseManager()
            ?.signData(build(fields).toByteArray(Charsets.UTF_8))

    fun verify(peerPublicKeyBase64: String, fields: Fields, signatureBase64: String): Boolean =
        EncryptionManager.getPairwiseManager()
            ?.verifySignature(
                peerPublicKeyBase64,
                build(fields).toByteArray(Charsets.UTF_8),
                signatureBase64,
            )
            ?: false
}

/** Test and call-site alias so call sites read clearly. */
typealias SignedMessageFields = MessageSigningPayload.Fields

/** What the receiver decided about an inbound counter. */
enum class ReplayDecision { ACCEPT, DUPLICATE, TOO_OLD }

/**
 * Phase 1.3, from R4.
 *
 * A strictly increasing counter is not enough, because mesh delivery reorders: a
 * packet that arrives late looks identical to a replay. So the receiver keeps a
 * high-water mark and tolerates bounded lateness below it. Anything further
 * below the mark is treated as too old to be genuine.
 *
 * The mark is supplied on construction from persisted state so it survives a
 * restart, which is what stops an attacker from replaying a capture simply by
 * waiting for the app to close.
 */
class ReplayWindow(
    private val WINDOW: Long = DEFAULT_WINDOW,
    restoredHighWaterMark: Long = 0L,
) {
    companion object {
        /**
         * From R5: a 64-frame window, the size used for call replay defence.
         * Large enough for mesh reordering, small enough that a capture older
         * than the window cannot be presented as merely late.
         */
        const val DEFAULT_WINDOW = 64L
    }

    var highWaterMark: Long = restoredHighWaterMark
        private set

    fun observe(counter: Long, alreadySeen: Boolean): ReplayDecision {
        if (alreadySeen) return ReplayDecision.DUPLICATE
        if (counter <= 0L) return ReplayDecision.TOO_OLD

        return if (counter > highWaterMark) {
            highWaterMark = counter
            ReplayDecision.ACCEPT
        } else if (highWaterMark - counter <= WINDOW) {
            // Late but plausibly reordered. The mark stays where it is.
            ReplayDecision.ACCEPT
        } else {
            ReplayDecision.TOO_OLD
        }
    }
}