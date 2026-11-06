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
    val points: List<Pair<Double, Double>> // lat, lon
)

/** 구역 식별 키(이어받기 완료 목록에 저장). 격자 정의가 바뀌면 키도 달라져 자동으로 새로 받게 된다. */
fun BoundingBox.key(): String = "$minLat,$maxLat,$minLon,$maxLon"

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

    private fun buildQuery(box: BoundingBox): String {
        val bbox = "${box.minLat},${box.minLon},${box.maxLat},${box.maxLon}"
        return """
            [out:json][timeout:50];
            way["highway"~"^(motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link)$"]["tunnel"~"^(yes|building_passage|covered)$"]($bbox);
            out geom;
        """.trimIndent()
    }

    private fun postQuery(endpoint: String, query: String): String {
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
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

            result.add(
                OverpassTunnelWay(
                    osmId = el.getLong("id"),
                    name = name,
                    points = points
                )
            )
        }
        return result
    }

    companion object {
        private const val TAG = "OverpassClient"
        private val BACKOFF_MS = listOf(3_000L, 10_000L, 25_000L)
    }
}
