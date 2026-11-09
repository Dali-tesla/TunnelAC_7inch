package com.tunnelac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ApproachDao {
    @Insert
    suspend fun insertAll(items: List<ApproachEntity>)

    @Query("DELETE FROM approach_roads WHERE tunnelId IN (:tunnelIds)")
    suspend fun deleteForTunnels(tunnelIds: List<Long>)

    @Query("SELECT * FROM approach_roads WHERE tunnelId IN (:tunnelIds)")
    suspend fun getForTunnels(tunnelIds: List<Long>): List<ApproachEntity>

    @Query("DELETE FROM approach_roads")
    suspend fun clearAll()

    @Query("SELECT * FROM approach_roads")
    suspend fun getAll(): List<ApproachEntity>

    @Query("SELECT COUNT(*) FROM approach_roads")
    suspend fun count(): Int
}
