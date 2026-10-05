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
 */
object ProtocolVersion {
    /** What this build sends. */
    const val CURRENT = 2

    /**
     * The oldest peer we will talk to. Anything below this is refused, because
     * accepting it would mean accepting packets whose signatures do not cover the
     * fields that make them meaningful.
     */
    const val MINIMUM = 2

    fun isSupported(version: Int): Boolean = version >= MINIMUM
}