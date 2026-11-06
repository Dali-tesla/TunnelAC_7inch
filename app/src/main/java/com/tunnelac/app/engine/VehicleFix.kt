package com.tunnelac.app.engine

/** 한 번의 위치 갱신(GPS fix)을 나타내는 순수 데이터 클래스. */
data class VehicleFix(
    val lat: Double,
    val lon: Double,
    val speedMps: Float,
    val bearingDeg: Float?,
    val accuracyM: Float,
    val hasFix: Boolean,
    val timeMs: Long
) {
    val speedKmh: Double get() = speedMps * 3.6
    fun toLatLon() = LatLon(lat, lon)
}
