package com.tunnelac.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tunnelac.app.data.db.AppDatabase
import com.tunnelac.app.data.db.ApproachDoneEntity
import com.tunnelac.app.data.db.ApproachEntity
import com.tunnelac.app.data.db.TunnelEntity
import com.tunnelac.app.data.overpass.BoundingBox
import com.tunnelac.app.data.overpass.OverpassClient
import com.tunnelac.app.data.overpass.OverpassRegions
import com.tunnelac.app.data.overpass.OverpassRoadWay
import com.tunnelac.app.data.overpass.OverpassTunnelWay
import com.tunnelac.app.engine.ApproachPath
import com.tunnelac.app.engine.GeoMath
import com.tunnelac.app.engine.LatLon
import com.tunnelac.app.engine.TunnelSegment
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 전국 갱신 결과. failedBoxCount > 0 이면 일부 구역은 이번에 갱신되지 못했다는 뜻(재시도 가능). */
data class RefreshResult(
    val tunnelCount: Int,
    val failedBoxCount: Int,
    val totalBoxCount: Int
)

/**
 * Overpass 원격 조회와 Room 로컬 저장소를 연결하는 저장소 계층.
 * 엔진/지도 화면은 이 클래스를 통해서만 터널 데이터를 얻는다.
 */
/** 네트워크가 끊긴 채로 대기 시간을 넘겼을 때. 구역을 실패 횟수에 넣지 않고 받기를 멈추는 신호로 쓴다. */
class NetworkDownException(message: String) : Exception(message)

class TunnelRepository(context: Context) {

    private val appContext = context.applicationContext

    private val db = AppDatabase.getInstance(context)
    private val tunnelDao = db.tunnelDao()
    private val approachDao = db.approachDao()
    private val adjacentDao = db.adjacentDao()
    private val doneDao = db.approachDoneDao()
    private val overpass = OverpassClient()

    /** 구역 안 진행 상황(문구)을 화면에 알리는 콜백. */
    @Volatile var statusListener: ((String) -> Unit)? = null

    /** 인터넷에 실제로 연결돼 있는지(연결 + 검증 완료). */
    fun isOnline(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 주소 조회 실패·연결 불가 같은 네트워크 끊김인지(서버 오류·타임아웃과 구분). */
    fun isNetworkError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is UnknownHostException || t is ConnectException || t is NoRouteToHostException) return true
            t = t.cause
        }
        return !isOnline()
    }

    /** 연결이 돌아올 때까지 최대 [maxWaitMs] 기다린다. 돌아왔으면 true, 기다린 시간은 [waitedMs]로 알린다. */
    suspend fun waitForNetwork(maxWaitMs: Long = NETWORK_WAIT_MAX_MS, waitedMs: (Long) -> Unit = {}): Boolean {
        val start = System.currentTimeMillis()
        var ok = isOnline()
        while (!ok && System.currentTimeMillis() - start < maxWaitMs) {
            val sec = (System.currentTimeMillis() - start) / 1000
            statusListener?.invoke("네트워크 연결 대기 중... (${sec}초, 최대 ${maxWaitMs / 60_000}분)")
            delay(5_000)
            ok = isOnline()
        }
        waitedMs(System.currentTimeMillis() - start)
        return ok
    }

    fun observeTunnelCount(): Flow<Int> = tunnelDao.observeCount()

    suspend fun tunnelCount(): Int = tunnelDao.count()

    /**
     * Overpass 에서 전국 터널 데이터를 새로 받아 로컬 DB를 갱신한다.
     * 일부 구역이 504/429 등으로 실패해도 나머지 구역 데이터는 반영한다(REPLACE 저장이라
     * 실패한 구역의 기존 데이터는 그대로 유지되고, 성공한 구역만 최신화된다. 전체를
     * clearAll 하지 않으므로 한 번의 부분 실패로 기존에 받아둔 데이터가 사라지지 않는다).
     */
    suspend fun refreshFromOverpass(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RefreshResult {
        val boxes = OverpassRegions.koreaGrid()
        val result = overpass.fetchKoreaTunnels(boxes, onProgress)
        val entities = result.ways.mapNotNull { it.toEntity() }
        if (entities.isNotEmpty()) tunnelDao.insertAll(entities)
        return RefreshResult(
            tunnelCount = entities.size,
            failedBoxCount = result.failedBoxCount,
            totalBoxCount = result.totalBoxCount
        )
    }

    /**
     * 구역 하나를 받아 바로 DB에 저장한다(이어받기용). 터널을 저장한 뒤 그 터널 입구들의 접근도로도 받아 저장한다.
     * 저장한 터널 수를 돌려주고, 실패하면 예외(이 경우 구역 전체가 미완료로 남아 이어받기 때 다시 받는다).
     */
    suspend fun fetchAndStoreBox(box: BoundingBox): Int {
        statusListener?.invoke("터널 조회 중...")
        val ways = overpass.fetchBoundingBox(box)
        val entities = ways.mapNotNull { it.toEntity() }
        if (entities.isNotEmpty()) tunnelDao.insertAll(entities)
        storeApproaches(ways)
        return entities.size
    }

    /** 한 구역만 다시 받을 때, 그 구역과 겹치는 터널의 접근도로 완료 표시만 지워 처음부터 다시 계산하게 한다. */
    suspend fun resetApproachProgressInBox(box: BoundingBox) {
        val ids = tunnelDao.getInBoundingBox(box.minLat, box.maxLat, box.minLon, box.maxLon).map { it.osmId }
        for (c in ids.chunked(500)) doneDao.deleteDone(c)
    }

    /** 터널 하나의 접근도로가 있는 입구 수(0, 1, 2). */
    suspend fun approachEntranceCount(tunnelId: Long): Int =
        approachDao.getForTunnels(listOf(tunnelId)).map { it.endIdx }.distinct().size

    /**
     * 터널 하나만 다시 받는다: way id 로 조회해 좌표·이름을 덮어쓰고, 그 터널의 접근도로를 새로 계산해 저장한다.
     * 결과 문구를 돌려준다(네트워크 오류 등은 예외).
     */
    suspend fun refreshOneTunnel(tunnelId: Long): String {
        statusListener?.invoke("터널 조회 중...")
        val way = overpass.fetchWaysByIds(listOf(tunnelId)).firstOrNull()
            ?: return "OSM에서 이 터널을 찾지 못했습니다(삭제됐을 수 있음). 기존 데이터는 그대로 둡니다."
        way.toEntity()?.let { tunnelDao.insertAll(listOf(it)) }
        db.withTransaction {
            approachDao.deleteForTunnels(listOf(tunnelId))
            doneDao.deleteDone(listOf(tunnelId))
        }
        if (polylineLength(way.points) < APPROACH_MIN_TUNNEL_M || way.nodeIds.size != way.points.size) {
            return "터널 정보를 갱신했습니다. (100m 미만이라 접근도로 대상 아님)"
        }
        resetApproachStats()
        storeApproaches(listOf(way))
        val n = approachEntranceCount(tunnelId)
        return when {
            skippedTotal.get() > 0 -> "터널 정보를 갱신했습니다. 접근도로 일부를 받지 못했습니다(입구 ${n}곳 저장). 다시 눌러 주세요."
            else -> "터널 정보와 접근도로를 갱신했습니다. (접근도로가 있는 입구 ${n}곳)"
        }
    }

    /** 새 갱신을 시작할 때 접근도로 완료 표시를 비운다(이어받기 때는 비우지 않는다). */
    suspend fun clearApproachProgress() = doneDao.clearAll()

    /** 접근도로에서 끝까지 실패해 건너뛴 입구 수(이번 실행 누적)와 마지막 오류 내용. */
    private val skippedTotal = AtomicInteger(0)
    val skippedApproachCenters: Int get() = skippedTotal.get()
    @Volatile var lastApproachError: String? = null
        private set

    /** 이번 구역의 진행·건너뜀·연속 실패·시간 한도(서버 일꾼들이 함께 쓰므로 원자적으로). */
    private val boxSkipped = AtomicInteger(0)
    private val failStreak = AtomicInteger(0)
    private val deadlineMs = AtomicLong(0L)

    /** 연속 실패로 쉰 횟수와 쉬는 시간 종료 시각(일꾼 모두가 함께 쉰다). */
    private val cooldownCount = AtomicInteger(0)
    private val cooldownUntil = AtomicLong(0L)

    private fun aborted(): Boolean =
        System.currentTimeMillis() > deadlineMs.get() || cooldownCount.get() > APPROACH_MAX_COOLDOWNS

    /** 연속 실패가 한도에 닿으면 중단하는 대신 모두 잠시 쉰다(쉰 시간만큼 시간 한도도 늘린다). */
    private fun maybeStartCooldown() {
        synchronized(cooldownCount) {
            if (failStreak.get() < APPROACH_MAX_FAIL_STREAK) return
            failStreak.set(0)
            cooldownCount.incrementAndGet()
            cooldownUntil.set(System.currentTimeMillis() + APPROACH_COOLDOWN_MS)
            deadlineMs.addAndGet(APPROACH_COOLDOWN_MS)
        }
    }

    private suspend fun awaitCooldown() {
        val w = cooldownUntil.get() - System.currentTimeMillis()
        if (w > 0) {
            statusListener?.invoke("서버 과부하로 ${w / 1000}초 쉬는 중 (${cooldownCount.get()}/${APPROACH_MAX_COOLDOWNS}회)")
            delay(w)
        }
    }

    private fun skip(n: Int, local: AtomicInteger? = null) {
        boxSkipped.addAndGet(n)
        skippedTotal.addAndGet(n)
        local?.addAndGet(n)
    }

    /** 보완 실행 전에 건너뜀 집계·마지막 오류를 비운다. */
    fun resetApproachStats() {
        skippedTotal.set(0)
        lastApproachError = null
    }

    /** 접근도로 완료 표시가 없는 터널(100m 이상) id. 이 터널들이 보완 대상이다. */
    suspend fun findMissingApproachIds(box: BoundingBox? = null): List<Long> {
        val all = if (box == null) tunnelDao.getAll()
        else tunnelDao.getInBoundingBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
        val candidates = all.filter { it.lengthM >= APPROACH_MIN_TUNNEL_M }.map { it.osmId }
        if (candidates.isEmpty()) return emptyList()
        val done = HashSet<Long>()
        for (ids in candidates.chunked(500)) done.addAll(doneDao.getDone(ids))
        return candidates.filter { it !in done }
    }

    /**
     * 접근도로가 비어 있는 터널만 골라 다시 받는다. 터널 노드 정보는 저장하지 않으므로 way id 로 다시 조회한다.
     * OSM에서 사라진 way 는 완료로 표시해 반복 조회를 막는다. 남은(아직 비어 있는) 터널 수를 돌려준다.
     */
    suspend fun supplementApproaches(box: BoundingBox? = null): Int {
        val missing = findMissingApproachIds(box)
        if (missing.isEmpty()) return 0
        resetApproachStats()
        val ways = ArrayList<OverpassTunnelWay>()
        var fetched = 0
        for (chunk in missing.chunked(100)) {
            statusListener?.invoke("빈 터널 ${fetched}/${missing.size} 조회 중...")
            ways.addAll(overpass.fetchWaysByIds(chunk))
            fetched += chunk.size
            delay(APPROACH_REQUEST_GAP_MS)
        }
        val found = ways.map { it.osmId }.toHashSet()
        val gone = missing.filter { it !in found }
        if (gone.isNotEmpty()) doneDao.insertAll(gone.map { ApproachDoneEntity(it) })
        storeApproaches(ways)
        return findMissingApproachIds(box).size
    }

    private fun entrances(t: OverpassTunnelWay): List<Pair<Double, Double>> {
        val list = ArrayList<Pair<Double, Double>>(2)
        if (t.oneway != -1) list.add(t.points.first())
        if (t.oneway != 1) list.add(t.points.last())
        return list
    }

    /** 약 50m 격자로 같은 입구를 합치기 위한 키. */
    private fun centerKey(pt: Pair<Double, Double>): Long =
        Math.round(pt.first / 0.0005) * 1_000_000L + Math.round(pt.second / 0.0006)

    /**
     * 터널 입구 주변 도로를 받아 입구별 접근도로 선을 만들어 저장한다(기존 것은 교체).
     * 터널을 입구 6곳 이하 묶음으로 나누고, 묶음마다 "받기 -> 계산 -> 저장 -> 완료 표시"를 끝낸다.
     * 서버 수만큼(최대 2) 일꾼이 같은 묶음 목록에서 가져가 동시에 받고, 이미 끝난 터널은 건너뛰므로
     * 구역 도중에 멈춰도 이어받기 때 끝난 묶음은 다시 받지 않는다.
     */
    private suspend fun storeApproaches(ways: List<OverpassTunnelWay>) {
        val targets = ways.filter {
            it.points.size >= 2 && it.nodeIds.size == it.points.size && polylineLength(it.points) >= APPROACH_MIN_TUNNEL_M
        }
        if (targets.isEmpty()) return

        // 이미 끝난 터널 제외
        val doneIds = HashSet<Long>()
        for (ids in targets.map { it.osmId }.chunked(500)) doneIds.addAll(doneDao.getDone(ids))
        val todo = targets.filter { it.osmId !in doneIds }
        if (todo.isEmpty()) return

        // 가까운 터널끼리 묶어 겹치는 도로를 덜 받는다.
        val sorted = todo.sortedWith(
            compareBy({ Math.round(it.points.first().first / 0.01) }, { Math.round(it.points.first().second / 0.01) }, { it.osmId })
        )
        val groups = ArrayList<List<OverpassTunnelWay>>()
        var cur = ArrayList<OverpassTunnelWay>()
        var curKeys = HashSet<Long>()
        for (t in sorted) {
            val keys = entrances(t).map { centerKey(it) }
            if (cur.isNotEmpty() && (curKeys + keys).size > APPROACH_BATCH) {
                groups.add(cur); cur = ArrayList(); curKeys = HashSet()
            }
            cur.add(t); curKeys.addAll(keys)
        }
        if (cur.isNotEmpty()) groups.add(cur)

        boxSkipped.set(0)
        failStreak.set(0)
        cooldownCount.set(0)
        cooldownUntil.set(0L)
        deadlineMs.set(System.currentTimeMillis() + APPROACH_BOX_BUDGET_MS)
        val queue = Channel<List<OverpassTunnelWay>>(Channel.UNLIMITED)
        for (g in groups) queue.trySend(g)
        queue.close()
        val finished = AtomicInteger(0)
        val workers = minOf(APPROACH_WORKERS, overpass.endpointCount).coerceAtLeast(1)

        coroutineScope {
            for (w in 0 until workers) {
                launch {
                    for (group in queue) {
                        statusListener?.invoke(
                            "접근도로 ${finished.get()}/${groups.size} 묶음 · 건너뜀 ${boxSkipped.get()} (서버 ${workers}곳 동시)"
                        )
                        processGroup(group, w)
                        finished.incrementAndGet()
                        delay(APPROACH_REQUEST_GAP_MS) // 서버마다 요청 사이 간격을 둔다
                    }
                }
            }
        }
    }

    /** 묶음 하나: 입구 주변 도로를 받아 접근도로를 만들고, 저장과 완료 표시를 한 트랜잭션으로 처리한다. */
    private suspend fun processGroup(group: List<OverpassTunnelWay>, worker: Int) {
        val centers = LinkedHashMap<Long, Pair<Double, Double>>()
        for (t in group) for (pt in entrances(t)) centers.putIfAbsent(centerKey(pt), pt)
        if (aborted()) { skip(centers.size); return } // 시간 한도·연속 실패: 이 묶음은 표시 없이 건너뜀(기존 방식으로 판정)

        val roads = LinkedHashMap<Long, OverpassRoadWay>()
        val groupSkipped = AtomicInteger(0)
        fetchRoadsAdaptive(centers.values.toList(), roads, APPROACH_FIRST_ROUNDS, 0, worker, groupSkipped)

        val index = RoadIndex(roads.values.toList())
        val items = ArrayList<ApproachEntity>()
        for (t in group) {
            for (r in ApproachBuilder.build(t, index)) {
                items.add(
                    ApproachEntity(
                        tunnelId = t.osmId,
                        endIdx = r.endIdx,
                        pointsJson = r.points.joinToString(";") { "${it.lat},${it.lon}" }
                    )
                )
            }
        }
        val ids = group.map { it.osmId }
        db.withTransaction {
            approachDao.deleteForTunnels(ids)
            if (items.isNotEmpty()) approachDao.insertAll(items)
            // 건너뛴 입구가 있으면 완료로 표시하지 않아 '접근도로 보완' 때 다시 받는다.
            if (groupSkipped.get() == 0) doneDao.insertAll(ids.map { ApproachDoneEntity(it) })
        }
    }

    /**
     * 입구 묶음을 조회한다([worker]번 서버를 먼저 쓰고 실패하면 다른 서버).
     *  - 네트워크가 끊겼으면 연결을 기다렸다가 같은 묶음을 다시 시도한다(실패로 세지 않음, 기다린 시간은 시간 한도에서 제외).
     *  - 그 외 실패는 절반씩 나눠 다시 시도하고, 1곳까지 나눠도 실패하면 그 입구만 건너뛴다.
     *  - 시간 한도를 넘었거나 연속 실패가 많으면 남은 입구는 건너뛴다. 건너뛴 입구는 기존 방식(거리·방향)으로 판정한다.
     */
    private suspend fun fetchRoadsAdaptive(
        centers: List<Pair<Double, Double>>,
        roads: MutableMap<Long, OverpassRoadWay>,
        rounds: Int,
        netRetry: Int,
        worker: Int,
        groupSkipped: AtomicInteger
    ) {
        if (aborted()) { skip(centers.size, groupSkipped); return }
        awaitCooldown()
        if (aborted()) { skip(centers.size, groupSkipped); return }
        try {
            val got = overpass.fetchRoadsAround(centers, rounds, worker)
            synchronized(roads) { for (w in got) roads[w.osmId] = w }
            failStreak.set(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastApproachError = e.message?.take(160) ?: e.javaClass.simpleName
            if (isNetworkError(e) && netRetry < APPROACH_NET_RETRIES) {
                var waited = 0L
                val back = waitForNetwork { waited = it }
                deadlineMs.addAndGet(waited)
                if (!back) throw NetworkDownException("네트워크 연결이 돌아오지 않았습니다.")
                delay(2_000)
                fetchRoadsAdaptive(centers, roads, rounds, netRetry + 1, worker, groupSkipped)
                return
            }
            failStreak.incrementAndGet()
            maybeStartCooldown()
            if (centers.size > 1) {
                val mid = centers.size / 2
                delay(APPROACH_REQUEST_GAP_MS)
                fetchRoadsAdaptive(centers.subList(0, mid), roads, 1, 0, worker, groupSkipped)
                delay(APPROACH_REQUEST_GAP_MS)
                fetchRoadsAdaptive(centers.subList(mid, centers.size), roads, 1, 0, worker, groupSkipped)
            } else {
                skip(1, groupSkipped)
            }
        }
    }

    private fun polylineLength(points: List<Pair<Double, Double>>): Double {
        var len = 0.0
        for (i in 0 until points.size - 1) {
            len += GeoMath.distanceMeters(
                LatLon(points[i].first, points[i].second),
                LatLon(points[i + 1].first, points[i + 1].second)
            )
        }
        return len
    }

    suspend fun getAllSegments(): List<TunnelSegment> =
        tunnelDao.getAll().map { it.toSegment() }

    /** 좌표 기준 반경(margin, m)을 감안한 bbox 내 터널만 조회한다. 엔진/지도에서 사용. */
    suspend fun getSegmentsNear(lat: Double, lon: Double, marginM: Double): List<TunnelSegment> {
        val latMargin = marginM / 111_000.0
        val lonMargin = marginM / (111_000.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        val entities = tunnelDao.getInBoundingBox(
            minLat = lat - latMargin,
            maxLat = lat + latMargin,
            minLon = lon - lonMargin,
            maxLon = lon + lonMargin
        )
        return withApproaches(entities)
    }

    /** 터널 목록에 저장된 접근도로를 붙여 세그먼트로 만든다(엔진용). */
    private suspend fun withApproaches(entities: List<TunnelEntity>): List<TunnelSegment> {
        if (entities.isEmpty()) return emptyList()
        val byTunnel = HashMap<Long, MutableList<ApproachPath>>()
        for (ids in entities.map { it.osmId }.chunked(500)) {
            for (a in approachDao.getForTunnels(ids)) {
                byTunnel.getOrPut(a.tunnelId) { ArrayList() }.add(ApproachPath(a.endIdx, parsePoints(a.pointsJson)))
            }
        }
        val adjByTunnel = HashMap<Long, MutableList<List<LatLon>>>()
        for (ids in entities.map { it.osmId }.chunked(500)) {
            for (a in adjacentDao.getForTunnels(ids)) {
                adjByTunnel.getOrPut(a.tunnelId) { ArrayList() }.add(parsePoints(a.pointsJson))
            }
        }
        return entities.map { it.toSegment(byTunnel[it.osmId].orEmpty(), adjByTunnel[it.osmId].orEmpty()) }
    }

    private fun parsePoints(json: String): List<LatLon> =
        json.split(";").filter { it.isNotBlank() }.map { pair ->
            val (latStr, lonStr) = pair.split(",")
            LatLon(latStr.toDouble(), lonStr.toDouble())
        }

    /** 지도 화면용: bbox 와 겹치는 터널들의 접근도로 선(입구 방향 순서)을 모두 돌려준다. */
    /** 지도 화면용: bbox 와 겹치는 터널들의 인접도로 선을 모두 돌려준다. */
    suspend fun getAdjacentLinesInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<List<LatLon>> {
        val ids = tunnelDao.getInBoundingBox(minLat, maxLat, minLon, maxLon).map { it.osmId }
        if (ids.isEmpty()) return emptyList()
        val result = ArrayList<List<LatLon>>()
        for (chunk in ids.chunked(500)) {
            for (a in adjacentDao.getForTunnels(chunk)) result.add(parsePoints(a.pointsJson))
        }
        return result
    }

    suspend fun getApproachLinesInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<List<LatLon>> {
        val ids = tunnelDao.getInBoundingBox(minLat, maxLat, minLon, maxLon).map { it.osmId }
        if (ids.isEmpty()) return emptyList()
        val result = ArrayList<List<LatLon>>()
        for (chunk in ids.chunked(500)) {
            for (a in approachDao.getForTunnels(chunk)) result.add(parsePoints(a.pointsJson))
        }
        return result
    }

    suspend fun getSegmentsInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<TunnelSegment> =
        tunnelDao.getInBoundingBox(minLat, maxLat, minLon, maxLon).map { it.toSegment() }

    private fun OverpassTunnelWay.toEntity(): TunnelEntity? {
        if (points.size < 2) return null
        val latLons = points.map { LatLon(it.first, it.second) }
        var length = 0.0
        for (i in 0 until latLons.size - 1) {
            length += GeoMath.distanceMeters(latLons[i], latLons[i + 1])
        }
        val pointsJson = points.joinToString(";") { "${it.first},${it.second}" }
        return TunnelEntity(
            osmId = osmId,
            name = name,
            pointsJson = pointsJson,
            lengthM = length,
            minLat = points.minOf { it.first },
            maxLat = points.maxOf { it.first },
            minLon = points.minOf { it.second },
            maxLon = points.maxOf { it.second }
        )
    }

    private fun TunnelEntity.toSegment(
        approaches: List<ApproachPath> = emptyList(),
        adjacent: List<List<LatLon>> = emptyList()
    ): TunnelSegment {
        return TunnelSegment(
            id = osmId,
            name = name,
            points = parsePoints(pointsJson),
            lengthM = lengthM,
            approaches = approaches,
            adjacent = adjacent
        )
    }

    companion object {
        /** 이 길이 미만 터널은 접근도로를 받지 않는다(엔진 기본 최소 터널 길이 200m 보다 여유 있게 낮춤). */
        private const val APPROACH_MIN_TUNNEL_M = 100.0
        /** 한 요청에 묶는 입구 중심점 수. */
        private const val APPROACH_BATCH = 6
        private const val APPROACH_REQUEST_GAP_MS = 1_000L
        /** 첫 시도의 재시도 라운드 수(나눈 뒤의 재시도는 1라운드). */
        private const val APPROACH_FIRST_ROUNDS = 1
        /** 한 구역의 접근도로 수집 시간 한도(ms). 넘으면 남은 입구는 건너뛴다. */
        private const val APPROACH_BOX_BUDGET_MS = 15 * 60_000L
        /** 연속으로 이만큼 실패하면 서버 사정이 나쁜 것으로 보고 이 구역의 남은 입구를 건너뛴다. */
        private const val APPROACH_MAX_FAIL_STREAK = 8
        /** 연속 실패 시 쉬는 시간과 구역당 최대 쉬는 횟수(넘으면 남은 묶음은 건너뜀). */
        private const val APPROACH_COOLDOWN_MS = 60_000L
        private const val APPROACH_MAX_COOLDOWNS = 3
        private const val APPROACH_NET_RETRIES = 3
        /** 동시에 받는 서버 일꾼 수의 상한(서버마다 1개씩). */
        private const val APPROACH_WORKERS = 2
        private const val NETWORK_WAIT_MAX_MS = 10 * 60_000L
    }
}
