package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Phase 1.5: durable replay records.
 *
 * These rows are independent of any conversation on purpose, so deleting a chat
 * cannot clear the evidence that a message was already delivered.
 */
@Dao
interface SeenIdDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: SeenIdEntity)

    /**
     * Insert and report how many rows changed, which is how the guard tells a
     * first delivery from a duplicate without a read-then-write race. Zero means
     * the id was already recorded.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAndGetChange(record: SeenIdEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM seen_ids WHERE senderId = :senderId AND messageId = :messageId)")
    suspend fun wasSeen(senderId: String, messageId: String): Boolean

    /** Retention sweep. Returns how many rows were dropped. */
    @Query("DELETE FROM seen_ids WHERE receivedAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM seen_ids")
    suspend fun count(): Int

    @Query("DELETE FROM seen_ids")
    suspend fun clear()
}