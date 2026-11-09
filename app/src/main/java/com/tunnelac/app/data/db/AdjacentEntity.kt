package com.tunnelac.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 터널선·접근도로선 곁(약 40m 이내)을 지나는 일반 도로의 선 조각 하나(인접도로).
 * 진입 판정과 진입 후 판정에서 "터널/접근도로 대신 옆도로를 달리고 있는가"를 거리 비교로 가리는 데 쓴다.
 * pointsJson 은 "lat,lon;..." 형식이다. PC 계산기(tools/tac_approach_builder.py)가 만들어 .tac 로 가져온다.
 */
@Entity(tableName = "adjacent_roads", indices = [Index("tunnelId")])
data class AdjacentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tunnelId: Long,
    val pointsJson: String
)
