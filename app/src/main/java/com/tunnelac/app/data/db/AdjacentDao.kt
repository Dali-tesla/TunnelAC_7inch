package com.tunnelac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AdjacentDao {
    @Insert
    suspend fun insertAll(items: List<AdjacentEntity>)

    @Query("SELECT * FROM adjacent_roads WHERE tunnelId IN (:tunnelIds)")
    suspend fun getForTunnels(tunnelIds: List<Long>): List<AdjacentEntity>

    @Query("SELECT * FROM adjacent_roads")
    suspend fun getAll(): List<AdjacentEntity>

    @Query("DELETE FROM adjacent_roads")
    suspend fun clearAll()
}
