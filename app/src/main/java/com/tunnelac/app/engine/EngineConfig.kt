package com.tunnelac.app.engine

import com.tunnelac.app.hvac.HvacState

/** 감지 엔진의 조정 가능한 파라미터. 설정 화면에서 값을 바꿀 수 있다. */
data class EngineConfig(
    val entryTriggerM: Double = 200.0,
    val exitTriggerM: Double = 100.0,
    val corridorWidthM: Double = 30.0,
    val headingToleranceDeg: Double = 60.0,
    val minSpeedKmh: Double = 10.0,
    val mergeGapM: Double = 600.0,
    val maxHoldMinutes: Double = 30.0,
    val minTunnelLengthM: Double = 200.0,
    // 이 정확도(m)보다 나쁘거나 fix 자체가 없으면 "GPS 신호 저하(=터널 안일 가능성)"로 보고
    // 마지막 정상 위치 기준으로 속도를 이용한 추정(dead reckoning)으로 넘어간다.
    val gpsLostAccuracyThresholdM: Float = 60f,
    // 터널 진출 시 전환할 공조 모드. 진입 시에는 항상 내기순환(RECIRCULATION)으로 전환하고,
    // 진출 시에는 이 값으로 전환한다. 기본값은 FRESH_AIR(외기), 끄면 AUTO(내기 강제 해제만).
    val exitMode: HvacState = HvacState.FRESH_AIR
)

data class TunnelSegment(
    val id: Long,
    val name: String?,
    val points: List<LatLon>,
    val lengthM: Double
) {
    val nearEndCandidates: List<LatLon> get() = listOf(points.first(), points.last())
    fun otherEnd(end: LatLon): LatLon = if (end == points.first()) points.last() else points.first()

    /**
     * [end] 입구에서 터널 안쪽으로 들어가는 진행 방위(도). 입구에서 폴리라인을 따라 약 [probeM] 미터 지난
     * 점(없으면 반대쪽 끝)을 향하는 방위로 정한다. 입구 근처를 지나는 다른 도로(직각 교차 등)를 걸러낼 때 쓴다.
     */
    fun directionBearingFrom(end: LatLon, probeM: Double = 40.0): Double {
        val seq = if (end == points.first()) points else points.asReversed()
        var acc = 0.0
        var target = seq.last()
        for (i in 1 until seq.size) {
            acc += GeoMath.distanceMeters(seq[i - 1], seq[i])
            if (acc >= probeM) { target = seq[i]; break }
        }
        return GeoMath.bearingDeg(end, target)
    }
}
