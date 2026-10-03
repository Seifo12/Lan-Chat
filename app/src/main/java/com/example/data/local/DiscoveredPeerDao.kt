package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscoveredPeerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: DiscoveredPeerEntity)

    @Query("SELECT * FROM discovered_peers ORDER BY lastSeen DESC")
    suspend fun getAll(): List<DiscoveredPeerEntity>

    @Query("SELECT * FROM discovered_peers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<DiscoveredPeerEntity>>

    @Query("SELECT * FROM discovered_peers WHERE deviceId = :deviceId")
    suspend fun getById(deviceId: String): DiscoveredPeerEntity?

    @Query("DELETE FROM discovered_peers WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)

    @Query("DELETE FROM discovered_peers")
    suspend fun clear()

    @Query("DELETE FROM discovered_peers WHERE lastSeen < :threshold")
    suspend fun pruneOlderThan(threshold: Long)
}
