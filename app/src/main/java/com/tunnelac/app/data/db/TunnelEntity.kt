package com.tunnelac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Overpass 로 받아온 터널 세그먼트 하나(OSM way 하나에 대응).
 * pointsJson 은 "lat,lon;lat,lon;..." 형식으로 좌표열을 직렬화해서 저장한다.
 * minLat/maxLat/minLon/maxLon 은 지도 뷰포트/근접 검색을 위한 bbox 컬럼.
 */
@Entity(tableName = "tunnels")
data class TunnelEntity(
    @PrimaryKey val osmId: Long,
    val name: String?,
    val pointsJson: String,
    val lengthM: Double,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)
