package com.example.data.local

/**
 * Phase 1.5: durable replay protection.
 *
 * The in-memory seen-nonce set cannot answer "have I seen this before?" after a
 * restart, and it is discarded whenever a session is re-established. This guard
 * is the durable half: it records the id of every inbound message it accepts and
 * refuses one it has recorded before.
 *
 * It is keyed on the message id rather than a counter because message packets
 * carry no sequence number, and adding one is a wire change that belongs to the
 * step that introduces a protocol version. A message id is already unique per
 * sender and already travels inside the authenticated payload, so keying on it
 * gives the same replay guarantee without touching the protocol.
 *
 * The table has no foreign key to any conversation, so deleting a chat does not
 * clear the record.
 */
class ReplayGuard(private val dao: SeenIdDao) {

    companion object {
        /** Agreed retention: thirty days. */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    /** Whether this message id is already recorded, without recording anything. */
    suspend fun wasRecorded(senderId: String, messageId: String): Boolean =
        dao.wasSeen(senderId, messageId)

    /**
     * Records an inbound message id, returning false when it has been seen
     * inside the retention window. A concurrent double delivery is resolved by
     * the primary key, so exactly one caller wins.
     */
    suspend fun tryAccept(senderId: String, messageId: String): Boolean {
        val inserted = dao.insertAndGetChange(
            SeenIdEntity(
                senderId = senderId,
                messageId = messageId,
                receivedAt = System.currentTimeMillis()
            )
        )
        return inserted > 0
    }

    /**
     * Drops records that have aged out. Called at startup so the table cannot
     * grow without bound, and cheap enough to be unconditional.
     */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int =
        dao.purgeOlderThan(now - RETENTION_MS)
}