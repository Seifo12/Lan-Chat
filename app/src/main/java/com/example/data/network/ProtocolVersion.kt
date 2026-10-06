package com.example.data.network

/**
 * Phase 1.3.
 *
 * Every message packet carries the protocol version it was built with. Version 1
 * is what shipped before signatures were enforced, and those packets covered only
 * the message text, so they cannot be trusted.
 *
 * The design is explicit that an old client is never dropped silently: a refusal
 * has to say why, so [MINIMUM] is paired with a notice the user can act on.
 *
 * Phase 1.8: version 3 adds signed group invitations and group-bound message
 * digests. MINIMUM stays 2 so old peers keep chatting; anything that needs
 * version 3 is refused toward a v2 peer before sending, with the update
 * notice, instead of vanishing into the silent drop unknown types get.
 */
object ProtocolVersion {
    /** What this build sends. */
    const val CURRENT = 3

    /**
     * The oldest peer we will talk to. Anything below this is refused, because
     * accepting it would mean accepting packets whose signatures do not cover the
     * fields that make them meaningful.
     */
    const val MINIMUM = 2

    fun isSupported(version: Int): Boolean = version >= MINIMUM
}