package com.tunnelac.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 터널 입구로 이어지는 접근도로 선 하나(본선 또는 합류 옆길 + 본선 연결부).
 * endIdx: 0 = 터널 폴리라인 시작 끝, 1 = 마지막 끝. pointsJson 은 "lat,lon;..." 이고
 * 입구에서 먼 쪽 -> 터널 입구 순서(= 차량이 입구로 향하는 방향)로 저장한다.
 */
@Entity(tableName = "approach_roads", indices = [Index("tunnelId")])
data class ApproachEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tunnelId: Long,
    val endIdx: Int,
    val pointsJson: String
)
