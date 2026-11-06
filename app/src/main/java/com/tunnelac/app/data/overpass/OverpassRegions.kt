package com.tunnelac.app.data.overpass

/** 위경도 사각 영역. */
data class BoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

/**
 * 대한민국 전역을 한 번에 조회하면 Overpass 서버 timeout/메모리 한도에 걸리기 쉬우므로
 * 격자 단위로 잘라서 순차 조회한다.
 * 대한민국 대략 범위: 위도 33.0~38.7, 경도 124.5~131.0 (독도 포함 여유치 포함).
 */
object OverpassRegions {
    private const val MIN_LAT = 33.0
    private const val MAX_LAT = 38.7
    private const val MIN_LON = 124.5
    private const val MAX_LON = 131.0

    /**
     * cellSizeDeg 단위 격자로 분할한 bbox 목록을 반환한다.
     * 값이 작을수록 요청 수는 늘지만 구역당 데이터량이 줄어 공용 서버의 504(타임아웃)를
     * 덜 겪는다. 서울 등 도로 밀도가 높은 지역까지 고려해 기본값을 1.0으로 낮췄다.
     */
    fun koreaGrid(cellSizeDeg: Double = 1.0): List<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        var lat = MIN_LAT
        while (lat < MAX_LAT) {
            val latEnd = minOf(lat + cellSizeDeg, MAX_LAT)
            var lon = MIN_LON
            while (lon < MAX_LON) {
                val lonEnd = minOf(lon + cellSizeDeg, MAX_LON)
                boxes.add(BoundingBox(lat, latEnd, lon, lonEnd))
                lon += cellSizeDeg
            }
            lat += cellSizeDeg
        }
        return boxes
    }
}
