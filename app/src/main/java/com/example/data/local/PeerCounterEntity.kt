package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Phase 1.3 counters.
 *
 * Both directions are persisted, and both have to be:
 *
 *  - [outgoingCounter] is the sender's next sequence number for this peer. If it
 *    reset on restart it would reissue counters the peer has already seen, and a
 *    peer applying the replay window would refuse genuine messages.
 *  - [highWaterMark] is the receiver's memory of the highest counter it accepted
 *    from this peer. If it reset on restart, an attacker could replay an old
 *    capture after the app closed and be believed again.
 *
 * `highWaterMark` doubles as the replay-window boundary, so a counter more than
 * the window below it is treated as too old to be genuine.
 */
@Entity(tableName = "peer_counters")
data class PeerCounterEntity(
    @PrimaryKey val peerDeviceId: String,
    val outgoingCounter: Long = 0,
    val highWaterMark: Long = 0,
)

@Dao
interface PeerCounterDao {

    @Query("SELECT * FROM peer_counters WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun get(peerDeviceId: String): PeerCounterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PeerCounterEntity)

    /**
     * Claims the next outgoing counter for a peer. Doing it as a single
     * conditional update rather than read-then-write is what stops two concurrent
     * sends claiming the same number.
     */
    @Query(
        "UPDATE peer_counters SET outgoingCounter = outgoingCounter + 1 " +
            "WHERE peerDeviceId = :peerDeviceId"
    )
    suspend fun incrementOutgoing(peerDeviceId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: PeerCounterEntity): Long

    @Query("SELECT outgoingCounter FROM peer_counters WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun outgoingCounter(peerDeviceId: String): Long?

    @Query(
        "UPDATE peer_counters SET highWaterMark = :mark " +
            "WHERE peerDeviceId = :peerDeviceId AND highWaterMark < :mark"
    )
    suspend fun raiseHighWaterMark(peerDeviceId: String, mark: Long): Int

    @Query("SELECT highWaterMark FROM peer_counters WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun highWaterMark(peerDeviceId: String): Long?
}