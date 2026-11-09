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

/** 터널 입구로 이어지는 접근도로 선. endIdx 0/1 = 터널 폴리라인의 시작/마지막 끝. points 는 입구에서 먼 쪽 -> 입구 순서. */
data class ApproachPath(val endIdx: Int, val points: List<LatLon>) {
    /** 선의 길이(m). 접근도로가 너무 짧은 입구는 접근도로 검사를 건너뛰는 데 쓴다. */
    val lengthM: Double by lazy {
        var s = 0.0
        for (i in 1 until points.size) s += GeoMath.distanceMeters(points[i - 1], points[i])
        s
    }
}

data class TunnelSegment(
    val id: Long,
    val name: String?,
    val points: List<LatLon>,
    val lengthM: Double,
    /** 지도에서 받아 둔 접근도로. 비어 있으면(데이터 없음) 엔진은 기존 방식(거리·방향)으로 판정한다. */
    val approaches: List<ApproachPath> = emptyList(),
    /** 터널·접근도로 곁을 지나는 일반 도로 선들(인접도로). 비어 있으면 인접도로 비교는 하지 않는다. */
    val adjacent: List<List<LatLon>> = emptyList()
) {
    /** [end] 입구(시작 끝=0, 마지막 끝=1)에 해당하는 접근도로 선들. */
    fun approachesFor(end: LatLon): List<ApproachPath> {
        val idx = if (end == points.first()) 0 else 1
        return approaches.filter { it.endIdx == idx }
    }

    /**
     * 접근도로가 한쪽 끝에만 있으면(일방통행 터널의 출구 쪽은 접근도로를 만들지 않는다) 비어 있는 끝은
     * 출구 전용으로 본다. 맞은편 방향 터널의 출구 끝으로 잘못 진입하는 것을 막는다. 양쪽 모두 비면 판단 불가.
     */
    fun isExitOnlyEnd(end: LatLon): Boolean = approaches.isNotEmpty() && approachesFor(end).isEmpty()

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
