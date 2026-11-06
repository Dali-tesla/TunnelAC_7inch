package com.tunnelac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TunnelEngine 이 발생시킨 ENTRY/EXIT/FORCE_EXIT/MERGE 이벤트를 그대로 기록한다.
 * 터널 감지가 실제로 동작하는지 사용자가 로그 화면/CSV 로 검증할 수 있도록 하기 위함.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // EngineEventType.name
    val timestampMs: Long,
    val tunnelId: Long?,
    val tunnelName: String?,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val speedKmh: Double,
    val gpsAccuracyM: Float?,
    val hvacBefore: String, // HvacState.name
    val hvacAfter: String,
    val hvacNote: String? = null // 공조 전송 실패 사유 / 릴레이 확인 결과 등
)
