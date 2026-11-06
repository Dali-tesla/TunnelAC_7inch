package com.tunnelac.app.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 순수 Kotlin 지리 계산 유틸. Android 프레임워크에 의존하지 않아 JVM 유닛 테스트로 검증 가능하다.
 */
data class LatLon(val lat: Double, val lon: Double)

object GeoMath {
    private const val EARTH_RADIUS_M = 6371000.0

    fun toRad(deg: Double) = deg * PI / 180.0
    fun toDeg(rad: Double) = rad * 180.0 / PI

    /** 두 좌표 사이의 haversine 거리(m). */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = toRad(b.lat - a.lat)
        val dLon = toRad(b.lon - a.lon)
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val h = sin(dLat / 2).let { it * it } +
            cos(la1) * cos(la2) * sin(dLon / 2).let { it * it }
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_M * c
    }

    /** a에서 b를 바라보는 초기 방위각(0~360, 정북 기준 시계방향). */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val la1 = toRad(a.lat)
        val la2 = toRad(b.lat)
        val dLon = toRad(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        val brng = toDeg(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    /** 두 방위각 사이의 최소 각도차(0~180). */
    fun angleDiffDeg(a: Double, b: Double): Double {
        var diff = kotlin.math.abs(a - b) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff
    }

    /**
     * origin 을 원점으로 하는 로컬 평면좌표(m)로 변환한다. 짧은 거리(수 km 이내)에서는
     * 지구 곡률 오차가 무시할 수준이라 터널 근방 계산에 충분하다.
     */
    fun toLocalXY(origin: LatLon, p: LatLon): DoubleArray {
        val mPerDegLat = 111320.0
        val mPerDegLon = 111320.0 * cos(toRad(origin.lat))
        val x = (p.lon - origin.lon) * mPerDegLon
        val y = (p.lat - origin.lat) * mPerDegLat
        return doubleArrayOf(x, y)
    }

    /** 점 p에서 선분 a-b 까지의 최단 거리(m). */
    fun distancePointToSegmentMeters(p: LatLon, a: LatLon, b: LatLon): Double {
        val origin = a
        val px = toLocalXY(origin, p)
        val ax = doubleArrayOf(0.0, 0.0)
        val bx = toLocalXY(origin, b)
        return distancePointToSegmentXY(px, ax, bx)
    }

    private fun distancePointToSegmentXY(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val abx = b[0] - a[0]
        val aby = b[1] - a[1]
        val lenSq = abx * abx + aby * aby
        val t = if (lenSq == 0.0) 0.0 else (((p[0] - a[0]) * abx + (p[1] - a[1]) * aby) / lenSq).coerceIn(0.0, 1.0)
        val cx = a[0] + t * abx
        val cy = a[1] + t * aby
        val dx = p[0] - cx
        val dy = p[1] - cy
        return sqrt(dx * dx + dy * dy)
    }

    /** 폴리라인(여러 선분)까지의 최단 거리(m). */
    fun distancePointToPolylineMeters(p: LatLon, points: List<LatLon>): Double {
        if (points.size < 2) return if (points.isEmpty()) Double.MAX_VALUE else distanceMeters(p, points[0])
        var best = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = distancePointToSegmentMeters(p, points[i], points[i + 1])
            if (d < best) best = d
        }
        return best
    }

    /**
     * nearEnd -> farEnd 축을 기준으로 p 가 축 방향으로 얼마나 진행했는지(m)를 계산한다.
     * 0 이면 nearEnd 지점, 축 길이(=nearEnd-farEnd 직선거리) 를 넘으면 farEnd 를 지나쳤다는 뜻이다.
     * 이 직선(코드) 투영은 곡선 터널에서는 부정확하므로, 실제 진행거리 판정에는
     * [projectAlongPolylineMeters] 를 대신 쓴다. (단순 참고/테스트용으로 남겨둔다.)
     */
    fun projectAlongAxisMeters(p: LatLon, nearEnd: LatLon, farEnd: LatLon): Double {
        val ax = toLocalXY(nearEnd, farEnd)
        val px = toLocalXY(nearEnd, p)
        val lenSq = ax[0] * ax[0] + ax[1] * ax[1]
        if (lenSq == 0.0) return 0.0
        return (px[0] * ax[0] + px[1] * ax[1]) / sqrt(lenSq)
    }

    /**
     * 폴리라인(터널의 실제 곡선 경로) 위에서 p 와 가장 가까운 지점까지의, 폴리라인 시작점
     * (points.first()) 기준 누적 거리(m)를 반환한다. 직선(코드) 투영과 달리 곡선 구간에서도
     * "도로를 따라 실제로 얼마나 갔는지"를 정확히 반영한다.
     */
    fun projectAlongPolylineMeters(p: LatLon, points: List<LatLon>): Double {
        if (points.size < 2) return 0.0
        var cumulative = 0.0
        var bestDist = Double.MAX_VALUE
        var bestArcLen = 0.0
        for (i in 0 until points.size - 1) {
            val origin = points[i]
            val ax = doubleArrayOf(0.0, 0.0)
            val bx = toLocalXY(origin, points[i + 1])
            val px = toLocalXY(origin, p)

            val abx = bx[0] - ax[0]
            val aby = bx[1] - ax[1]
            val segLenSq = abx * abx + aby * aby
            val segLen = sqrt(segLenSq)
            val t = if (segLenSq == 0.0) 0.0 else (((px[0] - ax[0]) * abx + (px[1] - ax[1]) * aby) / segLenSq).coerceIn(0.0, 1.0)
            val cx = ax[0] + t * abx
            val cy = ax[1] + t * aby
            val dx = px[0] - cx
            val dy = px[1] - cy
            val dist = sqrt(dx * dx + dy * dy)

            if (dist < bestDist) {
                bestDist = dist
                bestArcLen = cumulative + t * segLen
            }
            cumulative += segLen
        }
        return bestArcLen
    }
}
