package com.tunnelac.app.engine

import com.tunnelac.app.hvac.HvacState

enum class EngineEventType { ENTRY, EXIT, FORCE_EXIT, MERGE, SAFETY_EXIT }

data class EngineEvent(
    val type: EngineEventType,
    val timestampMs: Long,
    val tunnelId: Long?,
    val tunnelName: String?,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val speedKmh: Double,
    val gpsAccuracyM: Float?,
    val hvacBefore: HvacState,
    val hvacAfter: HvacState,
    /** 공조 명령 전송 실패 사유 등 부가 설명(없으면 null). */
    val hvacNote: String? = null,
    /** 진입 직전 차량 공조: 1=내기, 0=외기(내기 아님), -1=확인 불가. */
    val carRecircBefore: Int = -1
)

sealed class EngineState {
    object Idle : EngineState()
    data class InTunnel(val tunnelName: String?, val enteredAtMs: Long) : EngineState()
}

interface EngineListener {
    fun onEvent(event: EngineEvent)
    fun onStateChanged(state: EngineState)
}
