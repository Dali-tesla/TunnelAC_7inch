package com.tunnelac.app.data

import com.tunnelac.app.data.overpass.OverpassRoadWay
import com.tunnelac.app.data.overpass.OverpassTunnelWay
import com.tunnelac.app.engine.GeoMath
import com.tunnelac.app.engine.LatLon

/** 받아 둔 도로 way 들의 노드 색인. 한 구역의 터널 여러 개가 함께 쓴다(터널 구간 way 는 제외). */
class RoadIndex(all: List<OverpassRoadWay>) {
    val roads: List<OverpassRoadWay> = all.filter { !it.isTunnel }
    val pts: List<List<LatLon>> = roads.map { r -> r.points.map { LatLon(it.first, it.second) } }

    /** nodeId -> [roadIdx, posIdx] 목록 */
    val byNode: HashMap<Long, MutableList<IntArray>> = HashMap()

    init {
        for ((ri, r) in roads.withIndex()) {
            for ((pi, n) in r.nodeIds.withIndex()) {
                byNode.getOrPut(n) { ArrayList(2) }.add(intArrayOf(ri, pi))
            }
        }
    }
}

/**
 * 터널 입구 끝 노드에서 도로망을 거꾸로 따라가, "그 입구로 실제 주행해 들어올 수 있는" 도로만
 * 약 [MAIN_M] m 까지 뽑아 선(polyline)으로 만든다.
 *  - 일방통행(oneway, 고속도로·로터리 기본값 포함)을 반영해 입구 방향으로 진행 가능한 쪽만 따라간다.
 *  - 합류점에서 직진에 가까운 도로가 본선, 나머지 합류 도로는 합류점에서 [BRANCH_M] m 까지만 포함한다
 *    (옆길 안의 또 다른 갈래는 따라가지 않는다).
 *  - 각 선은 "입구에서 먼 쪽 -> 터널 입구" 순서이고, 옆길 선은 합류점 이후 본선 구간까지 이어 붙여
 *    입구까지 하나의 경로가 되게 한다.
 *  - 터널과 노드로 이어지지 않는 도로(평행·교차 도로 등)는 결과에 포함되지 않는다.
 */
object ApproachBuilder {
    const val MAIN_M = 500.0
    const val BRANCH_M = 100.0
    private const val MAX_PATHS_PER_END = 40

    class Result(val endIdx: Int, val points: List<LatLon>)

    fun build(tunnel: OverpassTunnelWay, index: RoadIndex): List<Result> {
        if (tunnel.points.size < 2 || tunnel.nodeIds.size != tunnel.points.size) return emptyList()
        val pts = tunnel.points.map { LatLon(it.first, it.second) }
        val out = ArrayList<Result>()
        for (endIdx in 0..1) {
            // 일방통행 터널은 진행 방향 쪽 입구만 접근도로가 의미 있다.
            if (tunnel.oneway == 1 && endIdx == 1) continue
            if (tunnel.oneway == -1 && endIdx == 0) continue
            val endNode = if (endIdx == 0) tunnel.nodeIds.first() else tunnel.nodeIds.last()
            val endPt = if (endIdx == 0) pts.first() else pts.last()
            val inner = if (endIdx == 0) pts[1] else pts[pts.size - 2]
            val paths = ArrayList<List<LatLon>>()
            Tracer(index, paths).expand(
                n = endNode,
                fromRoad = -1,
                suf = listOf(endPt),
                rem = MAIN_M,
                branch = false,
                vis = hashSetOf(endNode),
                fallbackDepart = GeoMath.bearingDeg(endPt, inner)
            )
            for (p in paths) if (p.size >= 2) out.add(Result(endIdx, p))
        }
        return out
    }

    private class Cand(val ri: Int, val pi: Int, val dir: Int)

    private class Tracer(val ix: RoadIndex, val out: MutableList<List<LatLon>>) {

        /** 노드 [n] 에 "도착할 수 있는" 도로 후보(도로 [exclude] 제외). dir = 뒤로 걷는 인덱스 방향. */
        private fun candidates(n: Long, exclude: Int): List<Cand> {
            val list = ix.byNode[n] ?: return emptyList()
            val res = ArrayList<Cand>(list.size)
            for (e in list) {
                val ri = e[0]
                val pi = e[1]
                if (ri == exclude) continue
                val road = ix.roads[ri]
                val last = road.nodeIds.size - 1
                // 차량이 인덱스 증가 방향으로 n 에 도착 -> 뒤로(-1) 걷는다. 양방향(0) 또는 정방향(1) 도로만 가능.
                if (pi > 0 && road.oneway >= 0) res.add(Cand(ri, pi, -1))
                // 차량이 인덱스 감소 방향으로 n 에 도착 -> 뒤로(+1) 걷는다. 양방향(0) 또는 역방향(-1) 도로만 가능.
                if (pi < last && road.oneway <= 0) res.add(Cand(ri, pi, 1))
            }
            return res
        }

        private fun arrivingBearing(c: Cand): Double {
            val p = ix.pts[c.ri]
            return GeoMath.bearingDeg(p[c.pi + c.dir], p[c.pi])
        }

        private fun emit(suf: List<LatLon>) {
            if (suf.size >= 2 && out.size < MAX_PATHS_PER_END) out.add(suf)
        }

        /** 노드 [n] 에서 도로가 끝났거나 시작점일 때: 직진에 가까운 후보를 본선으로 잇고 나머지는 옆길로 처리. */
        fun expand(
            n: Long, fromRoad: Int, suf: List<LatLon>, rem: Double,
            branch: Boolean, vis: Set<Long>, fallbackDepart: Double
        ) {
            if (out.size >= MAX_PATHS_PER_END) return
            val cands = candidates(n, fromRoad)
            if (cands.isEmpty()) { emit(suf); return }
            val depart = if (suf.size >= 2) GeoMath.bearingDeg(suf[0], suf[1]) else fallbackDepart
            val sorted = cands.sortedBy { GeoMath.angleDiffDeg(arrivingBearing(it), depart) }
            walk(sorted[0], suf, rem, branch, vis)
            if (!branch) {
                for (c in sorted.drop(1)) walk(c, suf, minOf(rem, BRANCH_M), true, vis)
            }
        }

        private fun walk(c: Cand, sufIn: List<LatLon>, remIn: Double, branch: Boolean, visIn: Set<Long>) {
            val road = ix.roads[c.ri]
            val p = ix.pts[c.ri]
            var suf = sufIn
            var rem = remIn
            var k = c.pi
            val vis = HashSet(visIn)
            while (out.size < MAX_PATHS_PER_END) {
                val nk = k + c.dir
                if (nk < 0 || nk >= p.size) { emit(suf); return }
                rem -= GeoMath.distanceMeters(p[k], p[nk])
                suf = listOf(p[nk]) + suf
                k = nk
                val node = road.nodeIds[k]
                if (rem <= 0.0) { emit(suf); return }
                if (!vis.add(node)) { emit(suf); return } // 순환 방지
                val atEnd = (k + c.dir < 0 || k + c.dir >= p.size)
                if (atEnd) { expand(node, c.ri, suf, rem, branch, vis, 0.0); return }
                if (!branch) {
                    // 길 중간 합류점: 계속 본선, 합류 도로는 짧게만 포함
                    for (side in candidates(node, c.ri)) walk(side, suf, minOf(rem, BRANCH_M), true, vis)
                }
            }
        }
    }
}
