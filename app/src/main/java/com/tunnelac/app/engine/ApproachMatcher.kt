package com.tunnelac.app.engine

/**
 * 최근 이동 경로를 지도의 접근도로·터널선과 맞춰 보는 순수 로직(안드로이드 의존 없음).
 */
object ApproachMatcher {

    /** 주행 이력의 한 점. */
    data class HistPt(val lat: Double, val lon: Double, val accM: Float, val timeMs: Long) {
        fun ll() = LatLon(lat, lon)
    }

    /** 폴리라인에서 가장 가까운 선분까지의 거리(m)와, 그 선분의 (저장 순서 방향) 방위(도). */
    data class Hit(val distM: Double, val bearingDeg: Double)

    fun nearest(p: LatLon, pts: List<LatLon>): Hit? {
        if (pts.size < 2) return null
        var best = Double.MAX_VALUE
        var bestBearing = 0.0
        for (i in 0 until pts.size - 1) {
            val d = GeoMath.distancePointToSegmentMeters(p, pts[i], pts[i + 1])
            if (d < best) {
                best = d
                bestBearing = GeoMath.bearingDeg(pts[i], pts[i + 1])
            }
        }
        return Hit(best, bestBearing)
    }

    /** 점 [p] 에서 폴리라인 목록 중 가장 가까운 선분까지 거리(m). 선이 없으면 null. */
    fun minDist(p: LatLon, lines: List<List<LatLon>>): Double? {
        var best: Double? = null
        for (l in lines) {
            val h = nearest(p, l) ?: continue
            if (best == null || h.distM < best) best = h.distM
        }
        return best
    }

    /** 인접도로가 대상 도로보다 "확실히" 가까운지의 여유 거리: max(minM, accFactor x GPS 정확도). */
    fun adjMargin(accM: Double, minM: Double, accFactor: Double): Double = maxOf(minM, accFactor * accM)

    /**
     * 최근 경로(최근 [windowM] m, 최소 [minPts] 점)에서 접근도로(·터널선)보다 인접도로가 여유 거리 이상 더 가까운 점이
     * [ratio] 를 넘으면 true(= 옆도로를 달리고 있어 진입 불가).
     */
    fun adjacentDominatesRecent(
        history: List<HistPt>,
        targets: List<List<LatLon>>,
        adjacent: List<List<LatLon>>,
        windowM: Double,
        ratio: Double,
        minPts: Int,
        marginMinM: Double,
        marginAccFactor: Double
    ): Boolean {
        if (history.isEmpty() || adjacent.isEmpty() || targets.isEmpty()) return false
        var acc = 0.0
        var n = 0
        var dom = 0
        for (i in history.size - 1 downTo 0) {
            if (i < history.size - 1) acc += GeoMath.distanceMeters(history[i].ll(), history[i + 1].ll())
            if (acc > windowM) break
            val q = history[i]
            val dt = minDist(q.ll(), targets) ?: continue
            val da = minDist(q.ll(), adjacent) ?: continue
            n++
            if (da < dt - adjMargin(q.accM.toDouble(), marginMinM, marginAccFactor)) dom++
        }
        return n >= minPts && dom > ratio * n
    }

    /**
     * 최근 경로가 "터널 입구로 이어지는 도로(접근도로 또는 터널 자체) 위를, 입구 방향으로" 달려온 것인지 판정한다.
     *  - 가장 최근 점: 도로 선에서 허용 거리 이내이고, 현재 진행 방위가 그 선분 방향과 [headingTolDeg] 이내.
     *  - 그 점에서 과거로 거슬러 올라가며 허용 거리 이내가 이어지는 구간이 [minRunM] 이상.
     * 반환: true=접근 중, false=아님(옆길·교차 도로 등), null=이력이 [minRunM] 보다 짧아 판단 불가.
     *
     * @param approaches 입구(endIdx)에 해당하는 접근도로 선들(입구에서 먼 쪽 -> 입구 순서)
     * @param tunnelPts 터널 폴리라인(저장 순서). endIdx==1 이면 진행 방향이 반대라 방위에 180°를 더한다.
     */
    fun matches(
        history: List<HistPt>,
        bearingNow: Double,
        approaches: List<ApproachPath>,
        tunnelPts: List<LatLon>,
        endIdx: Int,
        tolM: Double,
        minRunM: Double,
        headingTolDeg: Double
    ): Boolean? {
        if (history.size < 2) return null
        var total = 0.0
        for (i in 1 until history.size) total += GeoMath.distanceMeters(history[i - 1].ll(), history[i].ll())
        if (total < minRunM) return null

        fun best(p: LatLon): Hit? {
            var res: Hit? = null
            for (a in approaches) {
                val h = nearest(p, a.points) ?: continue
                if (res == null || h.distM < res.distM) res = h
            }
            val t = nearest(p, tunnelPts)
            if (t != null) {
                val adj = Hit(t.distM, if (endIdx == 0) t.bearingDeg else (t.bearingDeg + 180.0) % 360.0)
                if (res == null || adj.distM < res.distM) res = adj
            }
            return res
        }

        fun tol(q: HistPt) = tolM + 0.5 * minOf(q.accM.toDouble(), 20.0)

        val newest = history.last()
        val h = best(newest.ll()) ?: return false
        if (h.distM > tol(newest)) return false
        if (GeoMath.angleDiffDeg(bearingNow, h.bearingDeg) > headingTolDeg) return false

        var run = 0.0
        for (j in history.size - 2 downTo 0) {
            val q = history[j]
            val hq = best(q.ll()) ?: break
            if (hq.distM > tol(q)) break
            run += GeoMath.distanceMeters(q.ll(), history[j + 1].ll())
            if (run >= minRunM) return true
        }
        return run >= minRunM
    }
}
