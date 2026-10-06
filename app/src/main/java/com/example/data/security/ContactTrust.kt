package com.example.data.security

/**
 * Phase 1.7b: what a contact's security state is, and how it is decided.
 *
 * 1.6 could block a contact whose key changed and nothing could unblock it, so a
 * peer that reinstalled the app was stuck permanently. This is the state machine
 * that ends that dead end.
 *
 * The transitions are deliberately narrow. The only way out of [TrustState.KEY_CHANGED]
 * is accepting the new key, and accepting is an explicit user action. Marking a
 * contact verified does not accept a key change, because a user who has not
 * compared the codes has not agreed to trust a different identity.
 *
 * [TrustState.UNVERIFIED] is not a fault. First contact in a serverless mesh is
 * unverified by nature; the design says to say so in plain language rather than
 * showing a badge that implies more than has happened.
 */
enum class TrustState {
    /** First contact. Honest, not alarming, and not yet confirmed. */
    UNVERIFIED,

    /** The user has compared the safety code and said so. */
    VERIFIED,

    /** The peer is presenting a different key than the one pinned. Sending is blocked. */
    KEY_CHANGED,
}

/**
 * The parts of a contact that the trust decision needs, kept free of Room and of
 * Android so the rules can be read and tested on their own.
 */
data class ContactTrust(
    val deviceId: String,
    val displayName: String,
    /** The key the user agreed to. Null when the contact was never pinned. */
    val pinnedPublicKey: String?,
    /** Whatever the peer last presented. */
    val observedPublicKey: String?,
    /** When the user marked this contact verified, or null. */
    val verifiedAt: Long?,
) {
    fun state(): TrustState {
        // A key change outranks everything, including a previous verification. A
        // contact that verified yesterday and presents a different key today is not
        // still verified, whatever it used to be.
        return when (IdentityPin.evaluate(pinnedPublicKey, observedPublicKey)) {
            IdentityPin.IdentityState.KEY_CHANGED -> TrustState.KEY_CHANGED
            else -> if (verifiedAt != null) TrustState.VERIFIED else TrustState.UNVERIFIED
        }
    }

    /** Whether sending is refused. Only a key change blocks. */
    fun blocksSending(): Boolean = state() == TrustState.KEY_CHANGED

    /**
     * Whether the UI should offer to mark this contact verified. Refused while a
     * key change is outstanding, because comparing codes for a peer that has just
     * changed identity is not the same decision as accepting that change.
     */
    fun allowsClaimingVerified(): Boolean = state() == TrustState.UNVERIFIED

    /**
     * The code to read aloud, or null when there is nothing meaningful to compare.
     *
     * A key change has no code. The two sides no longer agree on a pair of
     * identities, so a number derived from them would be read out as if it meant
     * something. The decision is made here rather than left to the digest refusing
     * to produce output, because that is a property of the state and not of the
     * hashing.
     */
    fun safetyCode(): String? {
        if (state() == TrustState.KEY_CHANGED) return null
        return SafetyNumber.fromKeys(pinnedPublicKey, observedPublicKey)
    }
}