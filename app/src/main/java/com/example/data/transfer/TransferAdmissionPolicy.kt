package com.example.data.transfer

/**
 * Why an inbound transfer was refused.
 *
 * A closed enum on purpose: every refusal path is then testable, and every
 * refusal logs its own reason rather than a generic "rejected". The order of
 * the policy's checks is the order of this list.
 */
enum class RefusedReason {
    NOT_A_CONTACT,
    KEY_CHANGED,
    WRONG_TARGET,
    NOT_A_GROUP_MEMBER,
    TOO_LARGE,
    APK_TOO_LARGE,
    NO_FREE_SPACE,
    RATE_LIMITED,
    CONCURRENCY_LIMIT,
    PENDING_QUEUE_FULL,
    UNVERIFIED_OFFER,
    OFFER_EXPIRED,
    OFFER_REPLAY,
}

enum class TransferKind { PHOTO, VIDEO, FILE, VOICE, APK }

enum class RateDecision { ALLOW, REFUSE }

/**
 * Proof that a transfer passed the gate.
 *
 * The constructor is `internal`, so only this module can build one. Every
 * function that writes bytes takes one of these, which makes "all four receive
 * paths go through the gate" a compile-time property rather than a test-only
 * seam somebody has to remember to maintain.
 */
class AdmittedTransfer internal constructor(
    val peerId: String,
    val peerIp: String,
    val transferId: String,
    val fileName: String,
    val sizeBytes: Long,
    val kind: TransferKind,
    val fileSha256: String,
    /** True when the user was not asked. Only auto-accepted bytes count against the rate budget. */
    val autoAccepted: Boolean,
)

sealed interface TransferDecision {
    data class Refused(val reason: RefusedReason, val detail: String? = null) : TransferDecision
    data class NeedsConsent(val transferId: String) : TransferDecision
    data class Accepted(val transfer: AdmittedTransfer) : TransferDecision
}

/**
 * Everything the policy is allowed to know. Assembled by the caller from the
 * database, the verified offer and the rate limiter, so this function itself
 * needs no Android, no database and no sockets.
 */
data class AdmissionRequest(
    val peerId: String,
    val peerIp: String,
    val transferId: String,
    val fileName: String,
    val sizeBytes: Long,
    val kind: TransferKind,
    val recipientIsMe: Boolean,
    val iAmCurrentGroupMember: Boolean,
    val senderIsContact: Boolean,
    val senderHasKeyChanged: Boolean,
    val concurrentFromPeer: Int,
    val pendingFromPeer: Int,
    val pendingOverall: Int,
    val rateDecision: RateDecision,
    val freeSpaceBytes: Long,
    /** Taken from the verified offer, never from the stream that follows it. */
    val fileSha256: String,
)

object TransferAdmissionPolicy {

    private const val PENDING_FULL = "pending queue is full"

    /**
     * First match wins, so the log names the first thing wrong with a transfer
     * rather than the last. The order also matters for trust: contact and
     * key-change checks come before any accounting, so a stranger cannot spend
     * a peer's rate budget by making offers nobody will accept.
     */
    fun decide(request: AdmissionRequest): TransferDecision {
        // 1. The target must be me, or a group I am a current member of. The
        //    stream meta's recipientId is parsed today and never validated.
        if (!request.recipientIsMe && !request.iAmCurrentGroupMember) {
            return TransferDecision.Refused(RefusedReason.WRONG_TARGET)
        }
        // 2. Strangers are refused outright, with no prompt.
        if (!request.senderIsContact) {
            return TransferDecision.Refused(RefusedReason.NOT_A_CONTACT)
        }
        // 3. A key-changed peer is refused outright, with no prompt. This is the
        //    1.6 block, which files used to walk straight past.
        if (request.senderHasKeyChanged) {
            return TransferDecision.Refused(RefusedReason.KEY_CHANGED)
        }
        // 4. Size ceilings. An APK has its own, lower one.
        if (request.kind == TransferKind.APK) {
            if (request.sizeBytes > TransferAdmissionLimits.MAX_APK_BYTES) {
                return TransferDecision.Refused(RefusedReason.APK_TOO_LARGE)
            }
        } else if (request.sizeBytes > TransferAdmissionLimits.MAX_TRANSFER_BYTES) {
            return TransferDecision.Refused(RefusedReason.TOO_LARGE)
        }
        // 5. Free space, with headroom so the device is not left at zero bytes.
        val required = request.sizeBytes + TransferAdmissionLimits.FREE_SPACE_MARGIN_BYTES
        if (request.freeSpaceBytes < required) {
            return TransferDecision.Refused(
                RefusedReason.NO_FREE_SPACE,
                "need $required bytes, have ${request.freeSpaceBytes}",
            )
        }
        // 6. Per-peer concurrency, which is distinct from the global connection
        //    semaphore that stays as the outer bound.
        if (request.concurrentFromPeer >= TransferAdmissionLimits.MAX_CONCURRENT_PER_PEER) {
            return TransferDecision.Refused(RefusedReason.CONCURRENCY_LIMIT)
        }
        // 7. The rate limiter's verdict. Refused offers are counted by the
        //    limiter itself, so this cannot be evaded by offering junk.
        if (request.rateDecision == RateDecision.REFUSE) {
            return TransferDecision.Refused(RefusedReason.RATE_LIMITED)
        }
        // 8. Pending caps. The newest is dropped rather than the oldest, so the
        //    user is not losing a request they may already have looked at.
        if (request.pendingFromPeer >= TransferAdmissionLimits.PENDING_PER_PEER ||
            request.pendingOverall >= TransferAdmissionLimits.PENDING_TOTAL
        ) {
            return TransferDecision.Refused(RefusedReason.PENDING_QUEUE_FULL, PENDING_FULL)
        }
        // 9. Auto-accept needs all four: a saved contact, no key change, not an
        //    executable, and small enough to be unremarkable.
        val autoAcceptable = request.sizeBytes <= TransferAdmissionLimits.AUTO_ACCEPT_MAX_BYTES &&
            request.kind != TransferKind.APK
        return admit(request, autoAccepted = autoAcceptable)
    }

    private fun admit(request: AdmissionRequest, autoAccepted: Boolean): TransferDecision =
        if (autoAccepted) {
            TransferDecision.Accepted(build(request, autoAccepted = true))
        } else {
            TransferDecision.NeedsConsent(request.transferId)
        }

    /**
     * The only place an AdmittedTransfer is constructed.
     *
     * fileSha256 is carried in from the verified offer rather than invented
     * here: the caller passes it on AdmissionRequest, and this function has no
     * way to compute it because the bytes have not been read yet. It is filled
     * in by the caller from the offer it already verified, never from the
     * stream, because comparing the stream against itself proves nothing.
     */
    private fun build(request: AdmissionRequest, autoAccepted: Boolean) = AdmittedTransfer(
        peerId = request.peerId,
        peerIp = request.peerIp,
        transferId = request.transferId,
        fileName = request.fileName,
        sizeBytes = request.sizeBytes,
        kind = request.kind,
        fileSha256 = request.fileSha256,
        autoAccepted = autoAccepted,
    )
}