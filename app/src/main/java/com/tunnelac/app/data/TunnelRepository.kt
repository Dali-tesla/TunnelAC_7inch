package com.tunnelac.app.data

import android.content.Context
import com.tunnelac.app.data.db.AppDatabase
import com.tunnelac.app.data.db.TunnelEntity
import com.tunnelac.app.data.overpass.BoundingBox
import com.tunnelac.app.data.overpass.OverpassClient
import com.tunnelac.app.data.overpass.OverpassRegions
import com.tunnelac.app.data.overpass.OverpassTunnelWay
import com.tunnelac.app.engine.GeoMath
import com.tunnelac.app.engine.LatLon
import com.tunnelac.app.engine.TunnelSegment
import kotlinx.coroutines.flow.Flow

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
class TunnelRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val tunnelDao = db.tunnelDao()
    private val overpass = OverpassClient()

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

    /** 구역 하나를 받아 바로 DB에 저장한다(이어받기용). 저장한 터널 수를 돌려주고, 실패하면 예외. */
    suspend fun fetchAndStoreBox(box: BoundingBox): Int {
        val entities = overpass.fetchBoundingBox(box).mapNotNull { it.toEntity() }
        if (entities.isNotEmpty()) tunnelDao.insertAll(entities)
        return entities.size
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
        return entities.map { it.toSegment() }
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

    private fun TunnelEntity.toSegment(): TunnelSegment {
        val points = pointsJson.split(";").filter { it.isNotBlank() }.map { pair ->
            val (latStr, lonStr) = pair.split(",")
            LatLon(latStr.toDouble(), lonStr.toDouble())
        }
        return TunnelSegment(
            id = osmId,
            name = name,
            points = points,
            lengthM = lengthM
        )
    }
}
