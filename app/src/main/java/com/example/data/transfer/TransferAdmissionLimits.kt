package com.example.data.transfer

/**
 * Every numeric limit the inbound transfer gate enforces, in one place.
 *
 * These values are load-bearing and were approved with their rationale rather
 * than chosen opportunistically: a legitimate burst of twenty photos plus voice
 * notes is roughly 40 to 60 MB, comfortably inside RATE_MAX_AUTOBYTES, so an
 * ordinary user never meets the limit. Each value is boundary-tested in
 * TransferAdmissionLimitsTest and TransferAdmissionPolicyTest, because a limit
 * that is never tested at its edge is a limit nobody actually knows.
 */
object TransferAdmissionLimits {
    /** A saved contact's transfer up to this size auto-accepts. Above it, the user is asked. */
    const val AUTO_ACCEPT_MAX_BYTES = 20L * 1024 * 1024

    /** Hard ceiling for any single transfer. Was 5 GB, which let one request fill a phone. */
    const val MAX_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024

    /** Ceiling for an APK offer, unchanged from the pre-existing private constant. */
    const val MAX_APK_BYTES = 500L * 1024 * 1024

    /** Simultaneous admitted incoming transfers from one peer. */
    const val MAX_CONCURRENT_PER_PEER = 2

    /** Offers a peer may make inside one window. Refused offers count, or they would be free. */
    const val RATE_MAX_REQUESTS = 20

    /** Length of the rate-limit window. */
    const val RATE_WINDOW_MS = 10L * 60 * 1000

    /**
     * Bytes of auto-accepted transfer one peer may push inside one window. A
     * transfer the user explicitly approved is deliberately not counted here:
     * it is bounded by free space and MAX_TRANSFER_BYTES instead, because
     * refusing a file the user just approved is the wrong failure.
     */
    const val RATE_MAX_AUTOBYTES = 200L * 1024 * 1024

    /** How long a consent request waits before it declines itself. */
    const val PENDING_HOLD_MS = 5L * 60 * 1000

    /** Held requests from one peer. The newest is dropped beyond this. */
    const val PENDING_PER_PEER = 2

    /** Held requests overall. The newest is dropped beyond this. */
    const val PENDING_TOTAL = 5

    /** Headroom kept free when accepting, so the device is not left at zero bytes. */
    const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    /**
     * Admission pool size. It must exceed PENDING_TOTAL: a parked transfer is a
     * suspended coroutine rather than a blocked thread, but a pool smaller than
     * the pending cap would still let parked transfers starve each other.
     */
    const val ADMISSION_POOL_SLOTS = 8

    /** How long a sender waits for consent. Strictly greater than PENDING_HOLD_MS. */
    const val SENDER_OFFER_TIMEOUT_MS = 6L * 60 * 1000
}
