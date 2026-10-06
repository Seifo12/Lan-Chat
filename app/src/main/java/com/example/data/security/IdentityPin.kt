package com.example.data.security

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Phase 1.6, audit findings C2 and M9.
 *
 * The rules for whether a peer may be trusted, kept separate from storage and from
 * the UI so they can be read and tested on their own.
 *
 * A peer's key can change. Sometimes that is an attack, so a key that differs from
 * the one this device already trusted has to stop being usable immediately rather
 * than quietly replacing the old one. A silent re-pin is the failure mode worth
 * naming: if a contact's key is overwritten the first time a different key
 * appears, an attacker only has to be believed once and the user is never told.
 *
 * The pin is written when the user explicitly adds the contact, never
 * automatically on first connection, and a mismatch blocks rather than updates.
 */
object IdentityPin {

    /** How a peer's presented key relates to the one this device already trusted. */
    enum class IdentityState {
        /** No pin exists. The peer is not trusted yet. */
        UNPINNED,

        /** The presented key is the pinned key. */
        TRUSTED,

        /** A different key was presented. Nothing may be sent until the user decides. */
        KEY_CHANGED;

        /**
         * Whether ordinary traffic may be sent to this peer.
         *
         * Only TRUSTED passes. UNPINNED is deliberately not enough: a peer that has
         * never been pinned by the user cannot be given full rights on the strength
         * of a key it just presented.
         */
        fun allowsTraffic(): Boolean = this == TRUSTED
    }

    data class KeyChange(
        val pinnedKey: String,
        val observedKey: String,
        /** A short stable form, so a user can read it out or scan it. */
        val fingerprint: String,
    )

    fun evaluate(pinnedKey: String?, observedKey: String?): IdentityState {
        val pinned = pinnedKey?.takeIf { it.isNotBlank() } ?: return IdentityState.UNPINNED
        val observed = observedKey?.takeIf { it.isNotBlank() } ?: return IdentityState.UNPINNED
        return if (pinned == observed) IdentityState.TRUSTED else IdentityState.KEY_CHANGED
    }

    /** What the user needs in order to judge a reported change. Null when nothing changed. */
    fun describeChange(pinnedKey: String?, observedKey: String?): KeyChange? {
        val pinned = pinnedKey?.takeIf { it.isNotBlank() } ?: return null
        val observed = observedKey?.takeIf { it.isNotBlank() } ?: return null
        if (pinned == observed) return null
        return KeyChange(
            pinnedKey = pinned,
            observedKey = observed,
            fingerprint = fingerprint(observed),
        )
    }

    /**
     * A short, stable form of a key.
     *
     * Grouped in fives so it can be read aloud, which is the primary way this
     * project expects a user to compare two keys.
     */
    fun fingerprint(publicKeyBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBase64.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }.uppercase()
        return hex.take(20).chunked(5).joinToString(" ")
    }
}