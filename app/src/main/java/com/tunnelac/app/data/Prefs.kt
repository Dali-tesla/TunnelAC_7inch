package com.tunnelac.app.data

import android.content.Context
import com.tunnelac.app.engine.EngineConfig
import com.tunnelac.app.hvac.HvacState

/** EngineConfig 값과 Overpass 갱신 메타데이터를 SharedPreferences 에 저장한다. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("tunnelac_prefs", Context.MODE_PRIVATE)

    /** 기본값 변경(진출 트리거 100m, 방향 허용각 60°)을 이미 저장된 설정에도 한 번만 적용한다. */
    private fun migrateDefaults() {
        if (sp.getBoolean(KEY_DEFAULTS_V2, false)) return
        val d = EngineConfig()
        sp.edit()
            .putFloat(KEY_EXIT, d.exitTriggerM.toFloat())
            .putFloat(KEY_HEADING, d.headingToleranceDeg.toFloat())
            .putBoolean(KEY_DEFAULTS_V2, true)
            .apply()
    }

    fun loadConfig(): EngineConfig {
        migrateDefaults()
        val default = EngineConfig()
        return EngineConfig(
            entryTriggerM = sp.getFloat(KEY_ENTRY, default.entryTriggerM.toFloat()).toDouble(),
            exitTriggerM = sp.getFloat(KEY_EXIT, default.exitTriggerM.toFloat()).toDouble(),
            corridorWidthM = sp.getFloat(KEY_CORRIDOR, default.corridorWidthM.toFloat()).toDouble(),
            headingToleranceDeg = sp.getFloat(KEY_HEADING, default.headingToleranceDeg.toFloat()).toDouble(),
            minSpeedKmh = sp.getFloat(KEY_MIN_SPEED, default.minSpeedKmh.toFloat()).toDouble(),
            mergeGapM = sp.getFloat(KEY_MERGE_GAP, default.mergeGapM.toFloat()).toDouble(),
            maxHoldMinutes = sp.getFloat(KEY_MAX_HOLD, default.maxHoldMinutes.toFloat()).toDouble(),
            minTunnelLengthM = sp.getFloat(KEY_MIN_LEN, default.minTunnelLengthM.toFloat()).toDouble(),
            gpsLostAccuracyThresholdM = sp.getFloat(KEY_GPS_LOST, default.gpsLostAccuracyThresholdM),
            exitMode = loadExitMode()
        )
    }

    /** 진출 모드만 따로 읽는다(기본 FRESH_AIR). FRESH_AIR 또는 AUTO 만 허용. */
    fun loadExitMode(): HvacState {
        val name = sp.getString(KEY_EXIT_MODE, HvacState.FRESH_AIR.name)
        return when (name) {
            HvacState.AUTO.name -> HvacState.AUTO
            else -> HvacState.FRESH_AIR
        }
    }

    fun saveExitMode(mode: HvacState) {
        val stored = if (mode == HvacState.FRESH_AIR) HvacState.FRESH_AIR else HvacState.AUTO
        sp.edit().putString(KEY_EXIT_MODE, stored.name).apply()
    }

    fun saveConfig(config: EngineConfig) {
        sp.edit()
            .putFloat(KEY_ENTRY, config.entryTriggerM.toFloat())
            .putFloat(KEY_EXIT, config.exitTriggerM.toFloat())
            .putFloat(KEY_CORRIDOR, config.corridorWidthM.toFloat())
            .putFloat(KEY_HEADING, config.headingToleranceDeg.toFloat())
            .putFloat(KEY_MIN_SPEED, config.minSpeedKmh.toFloat())
            .putFloat(KEY_MERGE_GAP, config.mergeGapM.toFloat())
            .putFloat(KEY_MAX_HOLD, config.maxHoldMinutes.toFloat())
            .putFloat(KEY_MIN_LEN, config.minTunnelLengthM.toFloat())
            .putFloat(KEY_GPS_LOST, config.gpsLostAccuracyThresholdM)
            .putString(KEY_EXIT_MODE, (if (config.exitMode == HvacState.FRESH_AIR) HvacState.FRESH_AIR else HvacState.AUTO).name)
            .apply()
    }

    // --- 릴레이(BLE) 설정 ---
    fun isRelayEnabled(): Boolean = sp.getBoolean(KEY_RELAY_ENABLED, false)
    fun setRelayEnabled(enabled: Boolean) = sp.edit().putBoolean(KEY_RELAY_ENABLED, enabled).apply()
    /** 릴레이 앱 화면에 표시되는 PIN. */
    fun getRelayPin(): String = sp.getString(KEY_RELAY_PIN, "") ?: ""
    fun setRelayPin(pin: String) = sp.edit().putString(KEY_RELAY_PIN, pin.trim()).apply()

    // --- 알림음 ---
    fun isSoundEnabled(): Boolean = sp.getBoolean(KEY_SOUND_ENABLED, true)
    fun setSoundEnabled(enabled: Boolean) = sp.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    /** 알림음 크기 0~100 (기본 70). */
    fun getSoundVolume(): Int = sp.getInt(KEY_SOUND_VOLUME, 70).coerceIn(0, 100)
    fun setSoundVolume(v: Int) = sp.edit().putInt(KEY_SOUND_VOLUME, v.coerceIn(0, 100)).apply()

    fun setLastOverpassUpdateMs(timeMs: Long) {
        sp.edit().putLong(KEY_LAST_UPDATE, timeMs).apply()
    }

    fun getLastOverpassUpdateMs(): Long = sp.getLong(KEY_LAST_UPDATE, 0L)

    /** 터널 데이터 이어받기: 이번 갱신에서 받기를 마친 구역 키 목록. */
    fun getDoneBoxes(): Set<String> = HashSet(sp.getStringSet(KEY_DONE_BOXES, emptySet()) ?: emptySet())

    fun addDoneBox(key: String) {
        val s = getDoneBoxes().toMutableSet()
        s.add(key)
        sp.edit().putStringSet(KEY_DONE_BOXES, s).commit() // 강제 종료에도 남도록 동기 저장
    }

    fun clearDoneBoxes() {
        sp.edit().remove(KEY_DONE_BOXES).commit()
    }

    companion object {
        private const val KEY_DEFAULTS_V2 = "defaults_v2_exit100_heading60"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_DONE_BOXES = "overpass_done_boxes"
        private const val KEY_ENTRY = "entry_trigger_m"
        private const val KEY_EXIT = "exit_trigger_m"
        private const val KEY_CORRIDOR = "corridor_width_m"
        private const val KEY_HEADING = "heading_tolerance_deg"
        private const val KEY_MIN_SPEED = "min_speed_kmh"
        private const val KEY_MERGE_GAP = "merge_gap_m"
        private const val KEY_MAX_HOLD = "max_hold_minutes"
        private const val KEY_MIN_LEN = "min_tunnel_length_m"
        private const val KEY_GPS_LOST = "gps_lost_accuracy_threshold_m"
        private const val KEY_LAST_UPDATE = "last_overpass_update_ms"
        private const val KEY_EXIT_MODE = "exit_mode"
        private const val KEY_RELAY_ENABLED = "relay_enabled"
        private const val KEY_RELAY_PIN = "relay_pin"
    }
}
