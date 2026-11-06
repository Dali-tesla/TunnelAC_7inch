package com.tunnelac.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import com.tunnelac.app.data.Prefs
import com.tunnelac.app.data.TunnelRepository
import com.tunnelac.app.data.db.AppDatabase
import com.tunnelac.app.data.db.EventEntity
import com.tunnelac.app.engine.EngineConfig
import com.tunnelac.app.engine.EngineEvent
import com.tunnelac.app.engine.EngineListener
import com.tunnelac.app.engine.EngineState
import com.tunnelac.app.engine.TunnelEngine
import com.tunnelac.app.engine.VehicleFix
import com.tunnelac.app.hvac.HvacController
import com.tunnelac.app.hvac.HvacState
import com.tunnelac.app.hvac.SimulatedHvacController
import com.tunnelac.app.hvac.relay.BleRelayHvacController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GPS 전용(LocationManager.GPS_PROVIDER) 위치 갱신을 받아 TunnelEngine 에 전달하고,
 * 발생한 이벤트를 Room 에 기록하며, 잠금화면에도 노출되는 포그라운드 알림으로 실행 상태를 표시하는 서비스.
 * 보조신호로 조도센서 등은 전혀 사용하지 않는다 (GPS 신호의 정확도 저하/유실만 사용).
 */
class TunnelDetectionService : Service(), LocationListener, EngineListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var locationManager: LocationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var repository: TunnelRepository
    private lateinit var prefs: Prefs
    private lateinit var hvacController: HvacController
    private lateinit var engine: TunnelEngine

    private var stateCheckJob: Job? = null
    @Volatile private var stopping = false

    private var lastCandidateQueryMs = 0L
    private var lastCandidateLat: Double? = null
    private var lastCandidateLon: Double? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        notificationHelper = NotificationHelper(this)
        repository = TunnelRepository(applicationContext)
        prefs = Prefs(this)
        hvacController = buildHvacController()
        engine = TunnelEngine(prefs.loadConfig(), hvacController, this)

        notificationHelper.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            else -> startDetection()
        }
        return START_STICKY
    }

    /**
     * 설정에 따라 공조 컨트롤러를 생성한다.
     * 릴레이가 켜져 있고 PIN 이 입력돼 있으면 BLE 릴레이(필요할 때 연결), 아니면 시뮬레이션.
     */
    private fun buildHvacController(): HvacController {
        val pin = prefs.getRelayPin()
        return if (prefs.isRelayEnabled() && pin.isNotBlank()) {
            BleRelayHvacController(applicationContext, pin).also { it.start() }
        } else {
            SimulatedHvacController()
        }
    }

    private fun startDetection() {
        engine.updateConfig(prefs.loadConfig())
        startForeground(NotificationHelper.NOTIFICATION_ID, notificationHelper.buildNotification(engine.currentStateSnapshot()))
        stopping = false
        setRunning(true)
        setEngineState(engine.currentStateSnapshot())
        startRelayStateCheck()

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_UPDATE_INTERVAL_MS,
                MIN_UPDATE_DISTANCE_M,
                this
            )
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (stopping) return
        serviceScope.launch {
            refreshCandidatesIfNeeded(location)
            val fix = VehicleFix(
                lat = location.latitude,
                lon = location.longitude,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                bearingDeg = if (location.hasBearing()) location.bearing else null,
                accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
                hasFix = true,
                timeMs = location.time
            )
            engine.onFix(fix)
        }
    }

    /** GPS_PROVIDER 자체가 비활성화되면(터널 진입 등) 시스템이 이 콜백을 호출한다. */
    @Suppress("DEPRECATION")
    override fun onProviderDisabled(provider: String) {
        if (provider != LocationManager.GPS_PROVIDER) return
        serviceScope.launch {
            val fix = VehicleFix(
                lat = lastCandidateLat ?: return@launch,
                lon = lastCandidateLon ?: return@launch,
                speedMps = 0f,
                bearingDeg = null,
                accuracyM = Float.MAX_VALUE,
                hasFix = false,
                timeMs = System.currentTimeMillis()
            )
            engine.onFix(fix)
        }
    }

    override fun onProviderEnabled(provider: String) = Unit

    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private suspend fun refreshCandidatesIfNeeded(location: Location) {
        val now = System.currentTimeMillis()
        val movedFar = lastCandidateLat == null || lastCandidateLon == null ||
            distanceMeters(lastCandidateLat!!, lastCandidateLon!!, location.latitude, location.longitude) > CANDIDATE_REFRESH_DISTANCE_M
        val timedOut = now - lastCandidateQueryMs > CANDIDATE_REFRESH_INTERVAL_MS

        if (!movedFar && !timedOut) return

        val config = prefs.loadConfig()
        val marginM = config.entryTriggerM + CANDIDATE_QUERY_MARGIN_M
        engine.candidateTunnels = repository.getSegmentsNear(location.latitude, location.longitude, marginM)

        lastCandidateQueryMs = now
        lastCandidateLat = location.latitude
        lastCandidateLon = location.longitude
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0].toDouble()
    }

    private fun carLabel(v: Int) = when (v) { 1 -> "내기"; 0 -> "외기"; else -> "확인 불가" }

    override fun onEvent(event: EngineEvent) {
        // 알림음: 진입 = 안전벨트음, 내기 해제(정상/안전/강제 진출) = 콜사인. 병합은 소리 없음.
        when (event.type) {
            com.tunnelac.app.engine.EngineEventType.ENTRY ->
                com.tunnelac.app.SoundPlayer.playIfEnabled(applicationContext, com.tunnelac.app.SoundPlayer.Kind.ENTRY)
            com.tunnelac.app.engine.EngineEventType.EXIT,
            com.tunnelac.app.engine.EngineEventType.SAFETY_EXIT,
            com.tunnelac.app.engine.EngineEventType.FORCE_EXIT ->
                com.tunnelac.app.SoundPlayer.playIfEnabled(applicationContext, com.tunnelac.app.SoundPlayer.Kind.EXIT)
            else -> {}
        }
        serviceScope.launch {
            val relay = hvacController as? BleRelayHvacController
            val trackCar = event.type == com.tunnelac.app.engine.EngineEventType.ENTRY && relay != null
            // 진입: 전환 전 차량 공조를 먼저 기록하고, 전환 2초 뒤에 전환 후 상태를 확인해 이어서 기록한다.
            val firstNote = if (trackCar) {
                listOfNotNull("공조: ${carLabel(event.carRecircBefore)} → 확인 중…", event.hvacNote).joinToString("\n")
            } else event.hvacNote
            val rowId = AppDatabase.getInstance(applicationContext).eventDao().insert(
                EventEntity(
                    type = event.type.name,
                    timestampMs = event.timestampMs,
                    tunnelId = event.tunnelId,
                    tunnelName = event.tunnelName,
                    lat = event.lat,
                    lon = event.lon,
                    distanceM = event.distanceM,
                    speedKmh = event.speedKmh,
                    gpsAccuracyM = event.gpsAccuracyM,
                    hvacBefore = event.hvacBefore.name,
                    hvacAfter = event.hvacAfter.name,
                    hvacNote = firstNote
                )
            )
            if (trackCar && relay != null) {
                delay(CAR_RECHECK_DELAY_MS)
                val after = relay.peekCarRecirc()
                val afterLabel = when (after) { true -> "내기"; false -> "외기"; null -> "확인 불가" }
                val line = "공조: ${carLabel(event.carRecircBefore)} → $afterLabel" +
                    if (after == false) " (내기 전환 확인 안 됨)" else ""
                AppDatabase.getInstance(applicationContext).eventDao()
                    .updateNote(rowId, listOfNotNull(line, event.hvacNote).joinToString("\n"))
            }
        }
        setHvacState(event.hvacAfter)
    }

    override fun onStateChanged(state: EngineState) {
        setEngineState(state)
        val notification = notificationHelper.buildNotification(state)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    /** 이벤트 로그에 위치 없는 알림성 기록(릴레이 확인 결과, 서비스 정지 등)을 남긴다. */
    private suspend fun logNote(type: String, note: String) {
        AppDatabase.getInstance(applicationContext).eventDao().insert(
            EventEntity(
                type = type,
                timestampMs = System.currentTimeMillis(),
                tunnelId = null,
                tunnelName = null,
                lat = 0.0,
                lon = 0.0,
                distanceM = 0.0,
                speedKmh = 0.0,
                gpsAccuracyM = null,
                hvacBefore = HvacState.UNKNOWN.name,
                hvacAfter = HvacState.UNKNOWN.name,
                hvacNote = note
            )
        )
    }

    /**
     * 서비스 시작 즉시 릴레이에 연결해 차량 공조(내기 여부)를 한 번 확인한다.
     * 실패하면(릴레이 앱 부팅이 늦은 경우 등) 1분 간격으로 최대 [STATE_CHECK_MAX_ATTEMPTS]회까지 다시 시도한다.
     */
    private fun startRelayStateCheck() {
        val c = hvacController as? BleRelayHvacController ?: run {
            setRelayText("릴레이 미사용 (시뮬레이션)")
            return
        }
        stateCheckJob?.cancel()
        setRelayText("릴레이 확인 중…")
        stateCheckJob = serviceScope.launch {
            var attempt = 0
            while (isActive && !stopping && attempt < STATE_CHECK_MAX_ATTEMPTS) {
                attempt++
                val r = c.readCarState(STATE_CHECK_BUDGET_MS)
                if (r != null) {
                    val car = when (r.innerLoop) {
                        true -> "내기"
                        false -> "외기"
                        null -> "알 수 없음(릴레이가 차량 상태를 아직 못 받음)"
                    }
                    val hold = if (r.holdMode) " · 내기 유지 모드 ON" else ""
                    setRelayText("릴레이 연결 OK · 차량 공조: $car$hold")
                    if (r.innerLoop == true) setHvacState(HvacState.RECIRCULATION)
                    logNote("RELAY_CHECK", "연결 성공(${attempt}회째) · 차량 공조: $car$hold")
                    c.relaxInterest()
                    return@launch
                }
                val why = c.lastError.ifBlank { "원인 불명" }
                setRelayText("릴레이 확인 실패(${attempt}회): $why — 1분 뒤 재시도")
                if (attempt == 1) logNote("RELAY_CHECK", "연결 실패: $why (1분 간격으로 재시도)")
                c.relaxInterest()
                delay(STATE_CHECK_INTERVAL_MS)
            }
            if (isActive && !stopping) {
                setRelayText("릴레이 확인 불가: ${c.lastError.ifBlank { "원인 불명" }}")
                logNote("RELAY_CHECK", "확인 포기: ${STATE_CHECK_MAX_ATTEMPTS}회 모두 실패 (${c.lastError})")
            }
        }
    }

    /**
     * 정지 요청(앱 버튼/알림/바로가기): 감지를 멈추고, 릴레이 사용 중이면 오토 복귀 명령을 보낸 뒤 종료한다.
     * 릴레이에 연결되지 않아도 [STOP_AUTO_BUDGET_MS] 후에는 반드시 종료한다.
     */
    private fun requestStop() {
        if (stopping) return
        stopping = true
        stateCheckJob?.cancel()
        locationManager.removeUpdates(this)
        val c = hvacController as? BleRelayHvacController
        if (c == null) {
            stopSelf()
            return
        }
        serviceScope.launch {
            val ok = withTimeoutOrNull(STOP_AUTO_BUDGET_MS + 2_000) {
                c.setMode(HvacState.AUTO, STOP_AUTO_BUDGET_MS)
            } ?: false
            logNote(
                "SERVICE_STOP",
                if (ok) "서비스 정지: 차량 공조 오토 복귀 명령 전송 완료"
                else "서비스 정지: 오토 복귀 명령 전송 실패 (${c.lastError.ifBlank { "응답 없음" }})"
            )
            stopSelf()
        }
    }

    override fun onDestroy() {
        com.tunnelac.app.SoundPlayer.stop()
        locationManager.removeUpdates(this)
        (hvacController as? BleRelayHvacController)?.shutdown()
        setRunning(false)
        setEngineState(EngineState.Idle)
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.tunnelac.app.action.START"
        const val ACTION_STOP = "com.tunnelac.app.action.STOP"

        private const val MIN_UPDATE_INTERVAL_MS = 1000L
        private const val MIN_UPDATE_DISTANCE_M = 0f
        private const val CANDIDATE_REFRESH_DISTANCE_M = 1000.0
        private const val CANDIDATE_REFRESH_INTERVAL_MS = 15_000L
        private const val CANDIDATE_QUERY_MARGIN_M = 1000.0
        /** 시작 직후 릴레이 확인: 시도당 대기, 재시도 간격, 최대 시도 횟수. */
        private const val STATE_CHECK_BUDGET_MS = 20_000L
        private const val STATE_CHECK_INTERVAL_MS = 60_000L
        private const val STATE_CHECK_MAX_ATTEMPTS = 10
        /** 정지 시 오토 복귀 명령에 쓰는 최대 대기. */
        private const val STOP_AUTO_BUDGET_MS = 8_000L
        /** 진입 명령 후 차량이 실제로 내기로 바뀌었는지 다시 읽기까지의 대기. */
        private const val CAR_RECHECK_DELAY_MS = 2_000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
        val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

        private val _hvacState = MutableStateFlow(HvacState.UNKNOWN)
        val hvacState: StateFlow<HvacState> = _hvacState.asStateFlow()

        private val _relayText = MutableStateFlow("")
        val relayText: StateFlow<String> = _relayText.asStateFlow()

        private fun setRelayText(text: String) {
            _relayText.value = text
        }

        private fun setRunning(running: Boolean) {
            _isRunning.value = running
        }

        private fun setEngineState(state: EngineState) {
            _engineState.value = state
        }

        private fun setHvacState(state: HvacState) {
            _hvacState.value = state
        }
    }
}
