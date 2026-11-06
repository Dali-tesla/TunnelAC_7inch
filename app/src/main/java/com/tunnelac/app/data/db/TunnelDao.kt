package com.tunnelac.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TunnelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tunnels: List<TunnelEntity>)

    @Query("DELETE FROM tunnels")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM tunnels")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tunnels")
    suspend fun count(): Int

    @Query("SELECT * FROM tunnels")
    suspend fun getAll(): List<TunnelEntity>

    /**
     * 주어진 bbox(여유 margin 포함)와 겹치는 터널만 조회한다.
     * 위치 감지 엔진과 지도 화면 모두 이 쿼리로 "근방 터널"을 가져온다.
     */
    @Query(
        """
        SELECT * FROM tunnels
        WHERE maxLat >= :minLat AND minLat <= :maxLat
          AND maxLon >= :minLon AND minLon <= :maxLon
        """
    )
    suspend fun getInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<TunnelEntity>
}
