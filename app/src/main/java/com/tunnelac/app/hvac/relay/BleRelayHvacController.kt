package com.tunnelac.app.hvac.relay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.tunnelac.app.hvac.HvacController
import com.tunnelac.app.hvac.HvacState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE(GATT)로 리어 디스플레이의 릴레이 앱에 공조 명령을 전달하는 컨트롤러.
 *
 * 폰 = GATT 클라이언트. 릴레이가 광고하는 서비스 UUID 로 스캔해 연결하고(기기 선택·페어링 불필요),
 * PIN 인증(nonce + HMAC) 후 연결한다. 연결은 필요할 때만 맺는다: 터널 접근([prepare]), 명령 전송, 상태 확인.
 * 연결이 필요한 동안(wanted)은 끊겨도 자동으로 다시 검색해 연결하고, 마지막 명령 후 30초가 지나면 끊는다.
 * 명령 전송 = CMD 특성에 모드 코드 1바이트를 쓰고, 쓰기 응답 성공 여부가 결과다.
 *
 * 모든 GATT 상태 변경은 메인 스레드에서만 수행한다. [start] 로 연결 루프를 시작하고 [shutdown] 으로 끝낸다.
 */
class BleRelayHvacController(
    private val appContext: Context,
    private val pin: String
) : HvacController {

    private val h = Handler(Looper.getMainLooper())
    private val lastState = AtomicReference(HvacState.UNKNOWN)
    private val mutex = Mutex()

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var nonceChar: BluetoothGattCharacteristic? = null
    private var authChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    @Volatile private var started = false
    @Volatile private var authFailed = false
    @Volatile private var ready = false
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingState: CompletableDeferred<ByteArray?>? = null
    private var stateChar: BluetoothGattCharacteristic? = null

    /** 연결이 필요한 상태인지. false 면 끊겨도 재연결하지 않는다. */
    @Volatile private var wanted = false
    @Volatile private var lastErr = ""

    override val lastError: String get() = lastErr

    /** 릴레이가 보고한 차량 공조 상태. innerLoop: true=내기, false=내기 아님, null=알 수 없음. */
    data class RelayCarState(val innerLoop: Boolean?, val holdMode: Boolean)

    /** 마지막 연결 상태 설명(실패 원인 표시용). */
    @Volatile var statusText: String = "대기"
        private set

    override suspend fun getCurrentState(): HvacState = lastState.get()

    // ================= 공개 API =================

    /** 연결 루프 시작(이미 시작됐으면 무시). */
    fun start() {
        if (started) return
        started = true
        authFailed = false
    }

    /** 터널 접근 시 호출: 연결이 없으면 지금부터 연결을 시도한다(이미 연결/시도 중이면 무시). */
    override fun prepare() {
        if (!started || authFailed) return
        h.post { wantConnection() }
    }

    private fun wantConnection() {
        wanted = true
        // 관심이 계속되는 동안(터널 접근/명령 재시도)만 연결을 시도하고, 마지막 관심 후 일정 시간이 지나면 멈춘다.
        h.removeCallbacks(idleClose)
        h.postDelayed(idleClose, WANT_TIMEOUT_MS)
        tryConnect()
    }

    /** 확인/전송에 실패한 뒤 더 이상 연결이 필요 없으면 곧 시도를 멈춘다(터널 접근 중이면 prepare 가 다시 살린다). */
    fun relaxInterest() {
        h.post {
            if (!ready) {
                h.removeCallbacks(idleClose)
                h.postDelayed(idleClose, 3_000)
            }
        }
    }

    fun shutdown() {
        started = false
        wanted = false
        h.post {
            h.removeCallbacks(idleClose)
            disconnectAll("종료", retry = false)
        }
    }

    private val idleClose = object : Runnable {
        override fun run() {
            if (!started) return
            if (pendingWrite != null || pendingState != null) {
                h.postDelayed(this, 5_000)
                return
            }
            wanted = false
            disconnectAll("유휴 종료", retry = false)
        }
    }

    private fun scheduleIdleClose() {
        h.removeCallbacks(idleClose)
        h.postDelayed(idleClose, IDLE_CLOSE_MS)
    }

    /** 연결 후 인증까지 [CONNECT_WATCHDOG_MS] 안에 끝나지 않으면 끊고 다시 시도(멈춘 연결 방지). */
    private val connectWatchdog = Runnable {
        if (!ready && gatt != null) disconnectAll("${statusText} 중 시간 초과")
    }

    /** 인증까지 끝난 연결이 될 때까지 최대 [timeoutMs] 대기. */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (!started) { lastErr = "릴레이 사용 안 함"; return false }
        if (!authFailed) h.post { wantConnection() }
        if (ready) return true
        return withTimeoutOrNull(timeoutMs) {
            while (!ready) {
                if (authFailed) return@withTimeoutOrNull false
                delay(200)
            }
            true
        } == true
    }

    override suspend fun setMode(mode: HvacState): Boolean = setMode(mode, DEFAULT_BUDGET_MS)

    /** 연결 대기 + 전송을 합쳐 대략 [budgetMs] 안에 끝낸다(첫 시도 3/5, 재시도 2/5). */
    suspend fun setMode(mode: HvacState, budgetMs: Long): Boolean = mutex.withLock {
        val code = RelayProtocol.modeToCode(mode)
        if (code == null) {
            lastErr = "지원하지 않는 모드"
            return@withLock false
        }
        var ok = false
        val first = budgetMs * 3 / 5
        for (attempt in 0..1) {
            if (!awaitReady(if (attempt == 0) first else budgetMs - first)) break
            ok = writeCmd(code)
            if (ok) break
            Log.w(TAG, "명령 쓰기 실패 → 연결 재설정")
            h.post { disconnectAll("명령 응답 없음") } // 죽은 연결일 수 있으니 새로 맺는다
        }
        if (ok) {
            lastState.set(mode)
            lastErr = ""
            h.post { if (mode == HvacState.RECIRCULATION) h.removeCallbacks(idleClose) else scheduleIdleClose() }
        } else {
            lastErr = if (authFailed) "인증 실패(PIN 확인)" else "연결 안 됨(${statusText})"
        }
        ok
    }

    /**
     * 릴레이가 읽고 있는 차량 공조 상태를 가져온다. 연결이 안 되거나 릴레이가 상태 읽기를 지원하지 않으면 null.
     * 실패 이유는 [lastError] 에 남는다. 내기(true)일 때만 [getCurrentState] 를 RECIRCULATION 으로 맞춘다.
     */
    suspend fun readCarState(budgetMs: Long): RelayCarState? = mutex.withLock {
        if (!awaitReady(budgetMs)) {
            lastErr = if (authFailed) "인증 실패(PIN 확인)" else "연결 안 됨(${statusText})"
            return@withLock null
        }
        readStateNow(scheduleClose = true)
    }

    /** 이미 인증된 연결에서 STATE 를 읽는다(연결 유지 타이머는 건드리지 않는 옵션 포함). mutex 보유 상태에서 호출. */
    private suspend fun readStateNow(scheduleClose: Boolean): RelayCarState? {
        val d = CompletableDeferred<ByteArray?>()
        val began = withContext(Dispatchers.Main) { startStateRead(d) }
        if (!began) {
            lastErr = "릴레이 앱이 상태 확인을 지원하지 않음(릴레이 앱 업데이트 필요)"
            return null
        }
        val v = try {
            withTimeoutOrNull(3_000) { d.await() }
        } finally {
            if (pendingState === d) pendingState = null // 바깥에서 취소돼도 대기 상태가 남지 않게
        }
        if (v == null || v.size < 2) {
            lastErr = "상태 응답 없음"
            return null
        }
        lastErr = ""
        val inner = when (v[0].toInt()) { 1 -> true; 0 -> false; else -> null }
        if (inner == true) lastState.set(HvacState.RECIRCULATION)
        if (scheduleClose) h.post { scheduleIdleClose() }
        return RelayCarState(inner, v[1].toInt() == 1)
    }

    /** 이미 연결돼 있으면 차량 내기 여부를 빠르게 읽는다(연결이 없으면 기다리지 않고 null). */
    override suspend fun peekCarRecirc(): Boolean? {
        if (!ready) return null
        return withTimeoutOrNull(2_500) { mutex.withLock { if (ready) readStateNow(scheduleClose = false) else null } }?.innerLoop
    }

    @SuppressLint("MissingPermission")
    private fun startStateRead(d: CompletableDeferred<ByteArray?>): Boolean {
        val g = gatt
        val c = stateChar
        if (g == null || c == null || !ready) return false
        pendingState = d
        val ok = g.readCharacteristic(c)
        if (!ok) pendingState = null
        return ok
    }

    private suspend fun writeCmd(code: Int): Boolean {
        val d = CompletableDeferred<Boolean>()
        val began = withContext(Dispatchers.Main) { startWrite(code, d) }
        if (!began) return false
        return withTimeoutOrNull(3_000) { d.await() } ?: run {
            pendingWrite = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startWrite(code: Int, d: CompletableDeferred<Boolean>): Boolean {
        val g = gatt
        val c = cmdChar
        if (g == null || c == null || !ready) return false
        pendingWrite = d
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = byteArrayOf(code.toByte())
        val ok = g.writeCharacteristic(c)
        if (!ok) pendingWrite = null
        return ok
    }

    // ================= 연결 (메인 스레드) =================

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect() {
        if (!started || authFailed || gatt != null || scanning) return
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            statusText = "이 폰은 블루투스를 지원하지 않음"
            return
        }
        if (!hasPermissions()) {
            statusText = "블루투스(근처 기기) 권한 필요"
            scheduleRetry(RETRY_MS)
            return
        }
        if (!adapter.isEnabled) {
            statusText = "폰 블루투스가 꺼져 있음"
            scheduleRetry(RETRY_MS)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            statusText = "BLE 스캐너 사용 불가(블루투스/위치 서비스 확인)"
            scheduleRetry(RETRY_MS)
            return
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RelayProtocol.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        statusText = "릴레이 검색 중"
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            scanning = false
            statusText = "스캔 시작 실패: ${e.message}"
            scheduleRetry(RETRY_MS)
            return
        }
        h.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            statusText = "릴레이를 찾지 못함(릴레이 앱 실행 여부 확인)"
            scheduleRetry(RETRY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        h.removeCallbacks(scanTimeout)
        if (scanning) {
            try {
                (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                    ?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan 오류: ${e.message}")
            }
        }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            h.post {
                if (!scanning) return@post
                stopScan()
                statusText = "릴레이 발견, 연결 중"
                gatt = result.device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                h.removeCallbacks(connectWatchdog)
                h.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            h.post {
                scanning = false
                statusText = "스캔 실패 code=$errorCode"
                scheduleRetry(RETRY_MS)
            }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        h.removeCallbacks(retryRun)
        if (!started || authFailed || !wanted) return
        h.postDelayed(retryRun, delayMs)
    }

    private val retryRun = Runnable { tryConnect() }

    @SuppressLint("MissingPermission")
    private fun disconnectAll(why: String, retry: Boolean = true) {
        Log.i(TAG, "연결 정리: $why")
        stopScan()
        h.removeCallbacks(retryRun)
        h.removeCallbacks(connectWatchdog)
        ready = false
        pendingWrite?.complete(false)
        pendingWrite = null
        pendingState?.complete(null)
        pendingState = null
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
        cmdChar = null; nonceChar = null; authChar = null; stateChar = null
        statusText = why
        if (retry) scheduleRetry(RETRY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    statusText = "서비스 탐색 중"
                    g.discoverServices()
                } else if (g == gatt || gatt == null) {
                    disconnectAll("연결 끊김 (status=$status)")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            h.post {
                val s = g.getService(RelayProtocol.SERVICE_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || s == null) {
                    disconnectAll("릴레이 서비스를 찾지 못함 status=$status")
                    return@post
                }
                cmdChar = s.getCharacteristic(RelayProtocol.CMD_UUID)
                stateChar = s.getCharacteristic(RelayProtocol.STATE_UUID)
                nonceChar = s.getCharacteristic(RelayProtocol.NONCE_UUID)
                authChar = s.getCharacteristic(RelayProtocol.AUTH_UUID)
                val nc = nonceChar
                if (cmdChar == null || nc == null || authChar == null) {
                    disconnectAll("릴레이 특성이 없음(앱 버전 불일치?)")
                    return@post
                }
                statusText = "인증 중"
                g.readCharacteristic(nc)
            }
        }

        // API 33+ 에서는 value 를 받는 새 콜백만 호출되고, 이전 버전에서는 옛 콜백만 호출된다.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handleRead(g, c, c.value, status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            handleRead(g, c, value, status)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post {
                when (c.uuid) {
                    RelayProtocol.AUTH_UUID -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            ready = true
                            h.removeCallbacks(connectWatchdog)
                            statusText = "연결됨"
                            Log.i(TAG, "릴레이 인증 성공")
                        } else {
                            authFailed = true
                            disconnectAll("인증 실패 — 릴레이 앱 화면의 PIN과 같은지 확인", retry = false)
                        }
                    }
                    RelayProtocol.CMD_UUID -> {
                        pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                        pendingWrite = null
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        h.post {
            if (RelayProtocol.STATE_UUID == c.uuid) {
                pendingState?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
                pendingState = null
                return@post
            }
            if (RelayProtocol.NONCE_UUID != c.uuid) return@post
            val ac = authChar
            if (status != BluetoothGatt.GATT_SUCCESS || value == null || ac == null) {
                disconnectAll("nonce 읽기 실패 status=$status")
                return@post
            }
            try {
                ac.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ac.value = RelayProtocol.hmac16(pin, value)
                if (!g.writeCharacteristic(ac)) disconnectAll("인증 쓰기 시작 실패")
            } catch (e: Exception) {
                disconnectAll("인증 계산 오류: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "BleRelayHvac"
        private const val RETRY_MS = 5_000L
        private const val SCAN_TIMEOUT_MS = 15_000L
        /** 연결 후 인증 완료까지 허용 시간(넘으면 끊고 재시도). */
        private const val CONNECT_WATCHDOG_MS = 12_000L
        /** 마지막 명령/확인 후 연결을 유지하는 시간. */
        private const val IDLE_CLOSE_MS = 30_000L
        /** 연결 시도에 대한 관심이 이 시간 동안 갱신되지 않으면 재검색을 멈춘다(릴레이가 꺼져 있을 때 배터리 보호). */
        private const val WANT_TIMEOUT_MS = 60_000L
        /** 명령 전송 1회에 허용하는 총 대기(연결 3초 + 재시도 2초). */
        private const val DEFAULT_BUDGET_MS = 5_000L
    }
}
