package com.example.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Phase 1.5 replay record.
 *
 * Deliberately not a foreign key to a conversation. If it were, deleting a chat
 * would delete the record that the message had already been seen, and a recorded
 * capture would replay cleanly. The record has to outlive the message it
 * describes, so it is keyed only by sender and message id.
 *
 * `receivedAt` exists so entries can be purged once they are older than the
 * retention window; it is not used for ordering or for rejecting on clock skew,
 * because devices in this app are frequently offline and their clocks disagree.
 * It is indexed because the purge scans on it and would otherwise be a full
 * table scan on every startup.
 */
@Entity(
    tableName = "seen_ids",
    primaryKeys = ["senderId", "messageId"],
    indices = [Index(value = ["receivedAt"])]
)
data class SeenIdEntity(
    val senderId: String,
    val messageId: String,
    val receivedAt: Long
)