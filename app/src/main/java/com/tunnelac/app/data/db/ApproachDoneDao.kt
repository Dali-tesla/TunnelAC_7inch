package com.tunnelac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ApproachDoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ApproachDoneEntity>)

    @Query("SELECT tunnelId FROM approach_done WHERE tunnelId IN (:ids)")
    suspend fun getDone(ids: List<Long>): List<Long>

    @Query("DELETE FROM approach_done WHERE tunnelId IN (:ids)")
    suspend fun deleteDone(ids: List<Long>)

    @Query("DELETE FROM approach_done")
    suspend fun clearAll()
}
