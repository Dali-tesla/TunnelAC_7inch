package com.tunnelac.app.data.overpass

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Overpass 로 받아온 터널 way 하나. */
data class OverpassTunnelWay(
    val osmId: Long,
    val name: String?,
    val points: List<Pair<Double, Double>>, // lat, lon
    /** points 와 같은 순서의 OSM 노드 id (접근도로와 끝 노드를 맞대어 보는 데 쓴다). 없으면 빈 목록. */
    val nodeIds: List<Long> = emptyList(),
    /** 1 = way 순서 방향 일방통행, -1 = 역방향 일방통행, 0 = 양방향. */
    val oneway: Int = 0
)

/** 접근도로 후보로 받아온 일반 도로 way 하나. */
data class OverpassRoadWay(
    val osmId: Long,
    val points: List<Pair<Double, Double>>,
    val nodeIds: List<Long>,
    val oneway: Int,
    /** tunnel=yes 등 터널 구간이면 true (접근도로에서는 제외한다). */
    val isTunnel: Boolean
)

/** OSM 태그로 일방통행 값을 정한다. */
internal fun onewayOf(tags: org.json.JSONObject?): Int {
    if (tags == null) return 0
    when (tags.optString("oneway").lowercase()) {
        "yes", "true", "1" -> return 1
        "-1", "reverse" -> return -1
        "no", "false", "0" -> return 0
    }
    val hw = tags.optString("highway")
    val junction = tags.optString("junction")
    if (hw == "motorway" || hw == "motorway_link" || junction == "roundabout" || junction == "circular") return 1
    return 0
}

/** 구역 식별 키(이어받기 완료 목록에 저장). 격자 정의가 바뀌면 키도 달라져 자동으로 새로 받게 된다. */
fun BoundingBox.key(): String = "$minLat,$maxLat,$minLon,$maxLon,a1"

/** 전국 조회 결과. 서버 과부하(504)/속도제한(429) 등으로 실패한 구역은 건너뛰고 나머지는 반영한다. */
data class OverpassFetchResult(
    val ways: List<OverpassTunnelWay>,
    val failedBoxCount: Int,
    val totalBoxCount: Int
)

/**
 * OpenStreetMap Overpass API 클라이언트.
 * 대한민국 전역의 tunnel=yes 도로 way 를 bbox 단위로 나누어 조회한다.
 * 별도 HTTP 라이브러리 의존성 없이 java.net.HttpURLConnection 만 사용한다.
 *
 * 공용 무료 서버 특성상 특정 구역에서 504(게이트웨이 타임아웃)/429(속도 제한)가 종종 발생하므로,
 * 1) 여러 미러 엔드포인트를 순서대로 시도하고, 2) 라운드마다 백오프를 늘려 재시도하며,
 * 3) 그래도 실패한 구역은 전체를 중단시키지 않고 건너뛴 뒤 결과에 실패 개수로 알려준다.
 */
class OverpassClient(
    private val endpoints: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    ),
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxRounds: Int = 3
) {
    /** 여러 bbox 를 순차 조회하여 합친 뒤 osmId 기준으로 중복 제거한다. 일부 구역 실패는 건너뛴다. */
    suspend fun fetchKoreaTunnels(
        boxes: List<BoundingBox>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): OverpassFetchResult {
        val merged = LinkedHashMap<Long, OverpassTunnelWay>()
        var failedCount = 0
        boxes.forEachIndexed { index, box ->
            try {
                val ways = fetchBoundingBox(box)
                for (w in ways) merged[w.osmId] = w
            } catch (e: Exception) {
                failedCount++
                Log.w(TAG, "구역 조회 실패 (건너뜀): $box", e)
            }
            onProgress(index + 1, boxes.size)
        }
        return OverpassFetchResult(merged.values.toList(), failedCount, boxes.size)
    }

    suspend fun fetchBoundingBox(box: BoundingBox): List<OverpassTunnelWay> = withContext(Dispatchers.IO) {
        val query = buildQuery(box)
        var lastError: Exception? = null
        for (round in 0 until maxRounds) {
            for (endpoint in endpoints) {
                try {
                    val json = postQuery(endpoint, query)
                    return@withContext parseResponse(json)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (round < maxRounds - 1) {
                delay(BACKOFF_MS[round.coerceAtMost(BACKOFF_MS.lastIndex)])
            }
        }
        throw lastError ?: IllegalStateException("Overpass 조회 실패")
    }

    /**
     * 터널 입구(끝점) 주변 [ROAD_RADIUS_M] 반경(정사각 bbox)의 일반 도로를 받는다.
     * 여러 중심점을 한 요청의 합집합으로 묶어 요청 수를 줄인다. 보행·자전거·주차장 진입로(service)는 제외한다.
     */
    /** 사용할 수 있는 서버 수(병렬 받기 일꾼 수 결정에 쓴다). */
    val endpointCount: Int get() = endpoints.size

    /** 서버별 연속 실패 횟수와 '쉬는 중' 종료 시각(여러 일꾼이 함께 쓰므로 원자적으로). */
    private val epFails = java.util.concurrent.atomic.AtomicIntegerArray(endpoints.size)
    private val epDownUntil = java.util.concurrent.atomic.AtomicLongArray(endpoints.size)

    suspend fun fetchRoadsAround(
        centers: List<Pair<Double, Double>>,
        rounds: Int = maxRounds,
        startEndpoint: Int = 0
    ): List<OverpassRoadWay> = withContext(Dispatchers.IO) {
        if (centers.isEmpty()) return@withContext emptyList()
        val sb = StringBuilder("[out:json][timeout:25];\n(\n")
        for ((lat, lon) in centers) {
            val dLat = ROAD_RADIUS_M / 111_320.0
            val dLon = ROAD_RADIUS_M / (111_320.0 * Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
            sb.append("  way[\"highway\"~\"").append(ROAD_CLASSES).append("\"](")
                .append(lat - dLat).append(',').append(lon - dLon).append(',')
                .append(lat + dLat).append(',').append(lon + dLon).append(");\n")
        }
        sb.append(");\nout geom;")
        val query = sb.toString()
        var lastError: Exception? = null
        for (round in 0 until rounds) {
            // 연속으로 실패해 쉬게 한 서버는 건너뛴다(전부 쉬는 중이면 어쩔 수 없이 모두 시도).
            val order = endpoints.indices.map { (startEndpoint + it) % endpoints.size }
            val live = order.filter { System.currentTimeMillis() >= epDownUntil.get(it) }.ifEmpty { order }
            for (i in live) {
                try {
                    val roads = parseRoads(postQuery(endpoints[i], query, ROAD_READ_TIMEOUT_MS))
                    epFails.set(i, 0)
                    return@withContext roads
                } catch (e: Exception) {
                    lastError = e
                    if (epFails.incrementAndGet(i) >= ENDPOINT_MAX_FAILS) {
                        epDownUntil.set(i, System.currentTimeMillis() + ENDPOINT_REST_MS)
                        epFails.set(i, 0)
                    }
                }
            }
            if (round < rounds - 1) {
                var wait = BACKOFF_MS[round.coerceAtMost(BACKOFF_MS.lastIndex)]
                // 429(요청 과다)·504(서버 타임아웃)는 서버가 쉴 시간을 더 준다.
                val msg = lastError?.message.orEmpty()
                if (msg.contains("HTTP 429") || msg.contains("HTTP 504")) wait *= 2
                delay(wait)
            }
        }
        throw lastError ?: IllegalStateException("Overpass 도로 조회 실패")
    }

    private fun parseRoads(json: String): List<OverpassRoadWay> {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val result = ArrayList<OverpassRoadWay>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "way") continue
            val geometry = el.optJSONArray("geometry") ?: continue
            val nodesArr = el.optJSONArray("nodes") ?: continue
            if (geometry.length() < 2 || nodesArr.length() != geometry.length()) continue
            val pts = ArrayList<Pair<Double, Double>>(geometry.length())
            for (j in 0 until geometry.length()) {
                val pt = geometry.getJSONObject(j)
                pts.add(pt.getDouble("lat") to pt.getDouble("lon"))
            }
            val tags = el.optJSONObject("tags")
            val tunnelTag = tags?.optString("tunnel").orEmpty()
            result.add(
                OverpassRoadWay(
                    osmId = el.getLong("id"),
                    points = pts,
                    nodeIds = List(nodesArr.length()) { nodesArr.getLong(it) },
                    oneway = onewayOf(tags),
                    isTunnel = tunnelTag.isNotEmpty() && tunnelTag != "no"
                )
            )
        }
        return result
    }

    /** OSM way id 목록으로 터널 way 를 다시 조회한다(노드 id 포함). 접근도로 보완용. */
    suspend fun fetchWaysByIds(ids: List<Long>): List<OverpassTunnelWay> = withContext(Dispatchers.IO) {
        val query = "[out:json][timeout:50];\nway(id:" + ids.joinToString(",") + ");\nout geom;"
        var lastError: Exception? = null
        for (round in 0 until maxRounds) {
            for (endpoint in endpoints) {
                try {
                    return@withContext parseResponse(postQuery(endpoint, query))
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (round < maxRounds - 1) delay(BACKOFF_MS[round.coerceAtMost(BACKOFF_MS.lastIndex)])
        }
        throw lastError ?: IllegalStateException("Overpass 조회 실패")
    }

    private fun buildQuery(box: BoundingBox): String {
        val bbox = "${box.minLat},${box.minLon},${box.maxLat},${box.maxLon}"
        return """
            [out:json][timeout:50];
            way["highway"~"^(motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link)$"]["tunnel"~"^(yes|building_passage|covered)$"]($bbox);
            out geom;
        """.trimIndent()
    }

    private fun postQuery(endpoint: String, query: String, readTimeout: Int = readTimeoutMs): String {
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeout
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            // overpass-api.de 는 기본 Java User-Agent 로 오는 요청을 남용 방지 차원에서 406으로 거부한다.
            // 식별 가능한 User-Agent를 명시해야 정상 응답한다 (공식 포럼에서 확인된 해결 방법).
            conn.setRequestProperty("User-Agent", "TunnelAC-Android/1.0 (open-source personal-use client)")

            val body = "data=" + java.net.URLEncoder.encode(query, "UTF-8")
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("Overpass HTTP $code ($endpoint): ${text.take(200)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(json: String): List<OverpassTunnelWay> {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val result = mutableListOf<OverpassTunnelWay>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "way") continue
            val geometry = el.optJSONArray("geometry") ?: continue
            if (geometry.length() < 2) continue

            val points = ArrayList<Pair<Double, Double>>(geometry.length())
            for (j in 0 until geometry.length()) {
                val pt = geometry.getJSONObject(j)
                points.add(pt.getDouble("lat") to pt.getDouble("lon"))
            }

            val tags = el.optJSONObject("tags")
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }

            val nodesArr = el.optJSONArray("nodes")
            val nodeIds = if (nodesArr != null && nodesArr.length() == points.size) {
                List(nodesArr.length()) { nodesArr.getLong(it) }
            } else emptyList()

            result.add(
                OverpassTunnelWay(
                    osmId = el.getLong("id"),
                    name = name,
                    points = points,
                    nodeIds = nodeIds,
                    oneway = onewayOf(tags)
                )
            )
        }
        return result
    }

    companion object {
        private const val TAG = "OverpassClient"
        private val BACKOFF_MS = listOf(3_000L, 10_000L, 25_000L)
        /** 터널 입구 주변에서 접근도로로 받을 반경(m). 접근 500m + 여유. */
        const val ROAD_RADIUS_M = 550.0
        /** 한 서버가 이만큼 연속 실패하면 [ENDPOINT_REST_MS] 동안 쓰지 않는다. */
        private const val ENDPOINT_MAX_FAILS = 3
        private const val ENDPOINT_REST_MS = 3 * 60_000L
        /** 접근도로 조회의 응답 대기 제한(ms). 길게 기다려 봐야 소용없으므로 짧게 잡고 나눠서 다시 시도한다. */
        const val ROAD_READ_TIMEOUT_MS = 30_000
        /** 접근도로 후보 도로 등급(보행·자전거·service 제외). */
        const val ROAD_CLASSES =
            "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"
    }
}
