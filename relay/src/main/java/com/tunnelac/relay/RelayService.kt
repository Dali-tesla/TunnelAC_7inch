package com.tunnelac.relay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BLE GATT 서버. 폰 앱이 보낸 모드 명령(1=내기순환, 2=외기, 3=오토)을 받아 [CanSender] 로 차량 CAN 에 중계한다.
 *
 * 릴레이 = GATT 서버, 폰 = 클라이언트 구조를 따른다.
 *  - PIN(첫 실행 시 랜덤 6자리) + nonce/HMAC 로 폰을 인증, 인증된 기기만 명령 허용
 *  - 포그라운드 서비스 + 30초마다 자가 점검(서버/광고가 죽었으면 재시작)
 *  - 블루투스 OFF/ON, ACC 깨어남(action_acc_wakeup) 시 서버를 다시 연다(BT 칩 리셋 대응)
 * 모든 BLE 상태 변경은 메인 스레드에서만 수행한다.
 */
class RelayService : Service() {

    private val h = Handler(Looper.getMainLooper())

    @Volatile private var alive = false
    private var server: BluetoothGattServer? = null
    private var advRunning = false
    private val connected = HashSet<String>()
    private val authorized = HashSet<String>()
    private val nonces = HashMap<String, ByteArray>()
    /** 연결 후 인증 제한시간(10초) 감시용 */
    private val authTimeouts = HashMap<String, Runnable>()
    private val rnd = SecureRandom()
    private var receiverRegistered = false
    private var lastAccWakeAt = 0L

    // ---- 내기 유지 모드 (차량 isInnerLoop 감시) ----
    private var holdRecirc = false
    private var holdSince = 0L
    private var lastSendAt = 0L
    private var consecutiveResend = 0
    private var innerLoop: Boolean? = null
    private var innerLoopSince = 0L
    private var lastClientAt = 0L
    private var tickCount = 0
    private val pollExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var polling = false
    private val monitor = CarAirMonitor(
        ctx = this,
        onInnerLoop = { v -> h.post { onInnerLoopSample(v) } },
        log = { appendLog(it) }
    )

    private val guard = object : Runnable {
        override fun run() {
            try { guardTick() } catch (t: Throwable) { appendLog("감시 틱 오류: ${t.javaClass.simpleName}: ${t.message}") }
            h.postDelayed(this, GUARD_MS)
        }
    }

    private val health = object : Runnable {
        override fun run() {
            ensureRunning()
            h.postDelayed(this, HEALTH_MS)
        }
    }

    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    onBtState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                ACTION_ACC_WAKE -> onAccWake()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        getOrCreatePin(this) // 첫 실행이면 PIN 생성
        registerReceiver(sysReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_ACC_WAKE)
        })
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("릴레이 시작 중"))
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
        if (trigger != null) appendLog("자동 시작 트리거: $trigger")
        if (!alive) {
            alive = true
            runningFlow.value = true
        }
        if (trigger == ACTION_ACC_WAKE) onAccWake() else h.post { doStartBle(0) }
        h.removeCallbacks(health)
        h.postDelayed(health, HEALTH_MS)
        instance = this
        h.removeCallbacks(guard)
        h.postDelayed(guard, GUARD_MS)
        return START_STICKY
    }

    // ================= BLE 서버 =================

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** BLE 시작 중 어떤 예외가 나도 앱이 죽지 않게 감싼다(헤드유닛 BT 스택 차이 대비). */
    private fun doStartBle(attempt: Int) {
        try {
            doStartBleUnsafe(attempt)
        } catch (t: Throwable) {
            BleGuard.ok(this)
            setStatus("불가: BLE 시작 예외 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun doStartBleUnsafe(attempt: Int) {
        if (!alive || server != null) return
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val ad = bm?.adapter
        if (ad == null) {
            setStatus("불가: BluetoothAdapter 없음")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            setStatus("불가: bluetooth_le 기능 없음")
            return
        }
        if (!ad.isEnabled) {
            if (attempt == 0) {
                try { ad.enable() } catch (t: Throwable) { appendLog("BT enable 실패: $t") }
            }
            if (attempt < 10) {
                statusFlow.value = "블루투스 켜는 중..."
                h.postDelayed({ doStartBle(attempt + 1) }, 1500)
            } else {
                setStatus("불가: 블루투스를 켤 수 없음")
            }
            return
        }
        if (!ad.isMultipleAdvertisementSupported || ad.bluetoothLeAdvertiser == null) {
            setStatus("불가: BLE 광고(Peripheral) 미지원")
            return
        }
        if (!BleGuard.begin(this)) {
            setStatus("BLE 꺼짐: 지난번 BLE 시작 중 앱이 종료됨 (앱 화면의 'BLE 다시 시도'로 재개)")
            return
        }
        val srv = bm?.openGattServer(this, gattCallback)
        if (srv == null) {
            BleGuard.ok(this)
            setStatus("불가: GATT 서버 열기 실패")
            return
        }
        server = srv
        val svc = BluetoothGattService(RelayProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                RelayProtocol.CMD_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                RelayProtocol.NONCE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                RelayProtocol.AUTH_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                RelayProtocol.STATE_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
        )
        srv.addService(svc)
        setStatus("GATT 서비스 등록 중...")
    }

    private fun startAdv() {
        try {
            startAdvUnsafe()
        } catch (t: Throwable) {
            BleGuard.ok(this)
            setStatus("광고 불가: 예외 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvUnsafe() {
        val adv = bluetoothAdapter()?.bluetoothLeAdvertiser
        if (adv == null) {
            setStatus("광고 불가: advertiser 없음")
            return
        }
        if (advRunning) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(RelayProtocol.SERVICE_UUID))
            .build()
        adv.startAdvertising(settings, data, advCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopAdv() {
        try {
            bluetoothAdapter()?.bluetoothLeAdvertiser?.stopAdvertising(advCallback)
        } catch (t: Throwable) {
            appendLog("광고 중지 오류: $t")
        }
        advRunning = false
    }

    @SuppressLint("MissingPermission")
    private fun stopBleInternal() {
        BleGuard.ok(this)
        stopAdv()
        server?.let {
            try { it.close() } catch (t: Throwable) { appendLog("GATT close 오류: $t") }
        }
        server = null
        authorized.clear()
        nonces.clear()
        authTimeouts.values.forEach { h.removeCallbacks(it) }
        authTimeouts.clear()
        connected.clear()
        clientFlow.value = false
        statusFlow.value = "중지됨"
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            BleGuard.ok(this@RelayService)
            h.post {
                advRunning = true
                setStatus("광고 중 — 폰 앱이 연결합니다")
                updateNotification("대기 중 (폰 연결 안 됨)")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            BleGuard.ok(this@RelayService)
            h.post {
                advRunning = false
                setStatus("광고 실패 code=$errorCode (1=데이터큼 2=광고슬롯부족 3=이미광고중 4=내부오류 5=미지원)")
            }
        }
    }

    // ---- 자동 복구 ----

    private fun onAccWake() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAccWakeAt < 5000) return // 수신 경로가 둘이라 중복 방지
        lastAccWakeAt = now
        h.postDelayed({
            if (!alive) return@postDelayed
            appendLog("ACC 깨어남 → BLE 서버 재시작")
            stopBleInternal()
            doStartBle(0)
        }, 2000)
    }

    private fun onBtState(state: Int) {
        h.post {
            if (!alive) return@post
            if (state == BluetoothAdapter.STATE_OFF) {
                appendLog("블루투스 꺼짐 → 서버 정리")
                stopBleInternal()
            } else if (state == BluetoothAdapter.STATE_ON) {
                h.postDelayed({ doStartBle(0) }, 800)
            }
        }
    }

    /** 30초마다 호출. 블루투스를 강제로 켜지는 않는다. */
    private fun ensureRunning() {
        if (!alive) return
        val ad = bluetoothAdapter()
        if (ad == null || !ad.isEnabled) return
        if (server == null) {
            doStartBle(0)
        } else if (!advRunning && connected.isEmpty()) {
            appendLog("자가 점검: 광고가 꺼져 있어 다시 시작")
            stopAdv()
            startAdv()
        }
    }

    // ---- GATT 콜백 (바인더 스레드 → 메인 스레드로 넘겨서 처리) ----

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            h.post {
                if (status == BluetoothGatt.GATT_SUCCESS) startAdv()
                else setStatus("GATT 서비스 등록 실패 status=$status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            h.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.add(addr)
                    appendLog("폰 연결(미인증): $addr")
                    armAuthTimeout(device)
                } else {
                    cancelAuthTimeout(addr)
                    connected.remove(addr)
                    authorized.remove(addr)
                    nonces.remove(addr)
                    appendLog("폰 연결 끊김: $addr")
                    stopAdv()
                    startAdv() // 다시 광고
                }
                refreshClientState()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            h.post {
                val srv = server ?: return@post
                if (RelayProtocol.NONCE_UUID == characteristic.uuid) {
                    val n = ByteArray(16)
                    rnd.nextBytes(n)
                    nonces[device.address] = n
                    val out = if (offset < n.size) n.copyOfRange(offset, n.size) else ByteArray(0)
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, out)
                } else if (RelayProtocol.STATE_UUID == characteristic.uuid) {
                    if (!authorized.contains(device.address)) {
                        srv.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    } else {
                        val inner = when (innerLoop) { true -> 1; false -> 0; null -> 2 }
                        val st = byteArrayOf(inner.toByte(), if (holdFlow.value) 1 else 0)
                        val out = if (offset < st.size) st.copyOfRange(offset, st.size) else ByteArray(0)
                        srv.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, out)
                    }
                } else {
                    srv.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            h.post {
                var result = BluetoothGatt.GATT_SUCCESS
                try {
                    val addr = device.address
                    when (characteristic.uuid) {
                        RelayProtocol.AUTH_UUID -> {
                            val n = nonces.remove(addr)
                            val ok = n != null && value != null &&
                                MessageDigest.isEqual(RelayProtocol.hmac16(getOrCreatePin(this@RelayService), n), value)
                            if (ok) {
                                authorized.add(addr)
                                cancelAuthTimeout(addr)
                                appendLog("인증 성공: $addr")
                            } else {
                                authorized.remove(addr)
                                appendLog("인증 실패(PIN 불일치?): $addr")
                                result = BluetoothGatt.GATT_FAILURE
                            }
                            refreshClientState()
                        }
                        RelayProtocol.CMD_UUID -> {
                            if (!authorized.contains(addr)) {
                                result = BluetoothGatt.GATT_FAILURE // 미인증 명령 무시
                            } else if (value == null || value.isEmpty()) {
                                result = BluetoothGatt.GATT_FAILURE
                            } else {
                                val code = value[0].toInt()
                                val r = runCommand(code, "폰")
                                if (r.ok) updateNotification("연결됨 · 마지막 명령 ${RelayProtocol.codeName(code)}")
                                else result = BluetoothGatt.GATT_FAILURE
                            }
                        }
                        else -> result = BluetoothGatt.GATT_FAILURE
                    }
                } catch (t: Throwable) {
                    appendLog("쓰기 처리 오류: $t")
                    result = BluetoothGatt.GATT_FAILURE
                }
                val srv = server
                if (responseNeeded && srv != null) {
                    srv.sendResponse(device, requestId, result, offset, null)
                }
            }
        }
    }

    // ---- 인증 제한시간: 연결만 하고 10초 안에 인증하지 않은 폰은 끊는다 ----

    @SuppressLint("MissingPermission")
    private fun armAuthTimeout(device: BluetoothDevice) {
        val addr = device.address
        cancelAuthTimeout(addr)
        val r = Runnable {
            authTimeouts.remove(addr)
            if (connected.contains(addr) && !authorized.contains(addr)) {
                appendLog("인증 시간 초과(${AUTH_TIMEOUT_MS / 1000}초) → 연결 해제: $addr")
                try { server?.cancelConnection(device) } catch (_: Exception) {}
            }
        }
        authTimeouts[addr] = r
        h.postDelayed(r, AUTH_TIMEOUT_MS)
    }

    private fun cancelAuthTimeout(addr: String) {
        authTimeouts.remove(addr)?.let { h.removeCallbacks(it) }
    }

    // ================= 명령 / 내기 유지 모드 =================

    /**
     * 모드 코드(1=내기순환, 2=외기, 3=오토)를 CAN 으로 보내고 유지 모드를 갱신한다. 메인 스레드에서 호출.
     *  - 1: 내기 유지 모드 시작(차량 내기가 풀리면 자동으로 다시 내기). CAN 전송이 실패해도 유지 모드는 켠다.
     *  - 2/3: 유지 모드 종료. 3(오토)은 우리가 걸어 둔 강제를 풀고 차량 오토에 제어권을 돌려주는 용도다.
     */
    fun runCommand(code: Int, from: String): CanSender.Result {
        val r = CanSender.send(this, code)
        val now = SystemClock.elapsedRealtime()
        lastSendAt = now
        appendLog("[$from] MODE:${RelayProtocol.codeName(code)} -> ${if (r.ok) "OK" else "FAIL"} (${r.message})")
        when (code) {
            1 -> {
                holdSince = now
                consecutiveResend = 0
                setHold(true, "내기순환 명령 수신")
            }
            2, 3 -> setHold(false, "${RelayProtocol.codeName(code)} 명령 수신")
        }
        return r
    }

    private fun setHold(on: Boolean, why: String) {
        if (holdFlow.value == on) return
        holdFlow.value = on
        appendLog("내기 유지 모드 ${if (on) "시작" else "종료"} ($why)")
        if (!on) consecutiveResend = 0
        updateNotification(if (on) "내기 유지 중" else "대기 중")
    }

    private fun onInnerLoopSample(v: Boolean) {
        if (innerLoop != v) {
            innerLoopSince = SystemClock.elapsedRealtime()
            appendLog("차량 내기순환 → ${if (v) "ON" else "OFF"}")
        }
        innerLoop = v
        carFlow.value = v
        if (v) consecutiveResend = 0
    }

    /** 1초마다: (2초마다 직접 조회) + 유지 모드 감시 */
    private fun guardTick() {
        if (!alive) return
        // 감시 연결·조회는 유지 모드일 때와 화면 표시용으로 2초마다
        tickCount++
        if ((tickCount % 2 == 0 || holdFlow.value) && !polling) {
            polling = true
            pollExec.execute {
                try { monitor.poll() }
                catch (t: Throwable) { appendLog("감시 오류: ${t.javaClass.simpleName}: ${t.message}") }
                finally { polling = false }
            }
        }
        monitorFlow.value = monitor.connected
        if (!holdFlow.value) return

        val now = SystemClock.elapsedRealtime()
        if (now - holdSince > HOLD_MAX_MS) {
            setHold(false, "최대 유지시간(${HOLD_MAX_MS / 60000}분) 초과")
            return
        }
        if (authorized.isNotEmpty()) lastClientAt = now
        else if (now - lastClientAt > CLIENT_LOST_MS) {
            setHold(false, "폰 연결 ${CLIENT_LOST_MS / 1000}초 이상 끊김")
            return
        }
        // 차량이 내기가 아니라고 보고하는 상태가 1.5초 이상 지속되면 재전송(최소 3초 간격, 연속 5회 한도)
        if (innerLoop == false &&
            now - innerLoopSince >= FALL_CONFIRM_MS &&
            now - lastSendAt >= RESEND_GAP_MS
        ) {
            if (consecutiveResend < RESEND_MAX) {
                consecutiveResend++
                resendFlow.value = resendFlow.value + 1
                lastSendAt = now
                val r = CanSender.send(this, 1)
                appendLog("내기 풀림 감지 → 내기순환 재전송 #$consecutiveResend (${if (r.ok) "OK" else "FAIL"})")
            } else if (now - lastSendAt >= RESEND_COOLDOWN_MS) {
                consecutiveResend = 0 // 한도 후 60초 지나면 다시 시도
                appendLog("재전송 한도 후 대기 종료 → 감시 재개")
            }
        }
    }

    private fun refreshClientState() {
        clientFlow.value = authorized.isNotEmpty()
        if (authorized.isNotEmpty()) lastClientAt = SystemClock.elapsedRealtime()
        if (authorized.isNotEmpty()) updateNotification("연결됨 (폰 인증 완료)")
        else if (connected.isEmpty()) updateNotification("대기 중 (폰 연결 안 됨)")
    }

    private fun stopEverything() {
        alive = false
        runningFlow.value = false
        h.removeCallbacks(health)
        h.removeCallbacks(guard)
        setHold(false, "서비스 종료")
        pollExec.execute { monitor.stop() }
        instance = null
        stopBleInternal()
    }

    override fun onDestroy() {
        stopEverything()
        pollExec.shutdown()
        if (receiverRegistered) {
            try { unregisterReceiver(sysReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= 알림 / 로그 =================

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "릴레이 실행 중", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, RelayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tunnel AC 릴레이")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        if (!alive) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun setStatus(s: String) {
        statusFlow.value = s
        appendLog("BLE: $s")
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $msg"
        Log.i(TAG, line)
        logFlow.value = (logFlow.value + "\n" + line).takeLast(12000)
    }

    companion object {
        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "relay_running"
        private const val NOTIF_ID = 42
        private const val HEALTH_MS = 30_000L
        private const val GUARD_MS = 1_000L
        private const val HOLD_MAX_MS = 30 * 60_000L       // 내기 유지 모드 최대 30분
        private const val AUTH_TIMEOUT_MS = 10_000L          // 연결 후 이 시간 안에 인증하지 않으면 끊음
        private const val CLIENT_LOST_MS = 120_000L        // 폰 연결이 이 시간 이상 끊기면 해제
        private const val FALL_CONFIRM_MS = 1_500L         // 내기 풀림 확인 시간
        private const val RESEND_GAP_MS = 3_000L           // 재전송 최소 간격
        private const val RESEND_MAX = 5                   // 연속 재전송 한도
        private const val RESEND_COOLDOWN_MS = 60_000L     // 한도 도달 후 대기

        const val ACTION_STOP = "com.tunnelac.relay.STOP"
        const val ACTION_ACC_WAKE = "action_acc_wakeup"
        const val EXTRA_TRIGGER = "trigger"

        private val runningFlow = MutableStateFlow(false)
        val runningState: StateFlow<Boolean> = runningFlow.asStateFlow()

        /** 인증까지 끝난 폰이 연결돼 있는지. */
        private val clientFlow = MutableStateFlow(false)
        val clientConnectedState: StateFlow<Boolean> = clientFlow.asStateFlow()

        /** 내기 유지 모드 켜짐 여부 / 차량이 보고하는 내기순환(null=미수신) / 감시 연결 / 재전송 누적 횟수. */
        private val holdFlow = MutableStateFlow(false)
        val holdState: StateFlow<Boolean> = holdFlow.asStateFlow()
        private val carFlow = MutableStateFlow<Boolean?>(null)
        val carInnerLoopState: StateFlow<Boolean?> = carFlow.asStateFlow()
        private val monitorFlow = MutableStateFlow(false)
        val monitorState: StateFlow<Boolean> = monitorFlow.asStateFlow()
        private val resendFlow = MutableStateFlow(0)
        val resendCountState: StateFlow<Int> = resendFlow.asStateFlow()

        /** 로컬 테스트 버튼이 BLE 경로와 같은 로직(유지 모드 포함)을 타도록 실행 중인 서비스를 가리킨다. */
        @Volatile var instance: RelayService? = null

        private val statusFlow = MutableStateFlow("중지됨")
        val statusState: StateFlow<String> = statusFlow.asStateFlow()

        private val logFlow = MutableStateFlow("")
        val lastLogState: StateFlow<String> = logFlow.asStateFlow()

        /** 첫 실행이면 6자리 랜덤 PIN 을 만들어 저장하고, 이후엔 저장된 값을 돌려준다. */
        @Synchronized
        fun getOrCreatePin(ctx: Context): String {
            val sp = ctx.applicationContext.getSharedPreferences("relay", Context.MODE_PRIVATE)
            var pin = sp.getString("pin", null)
            if (pin == null) {
                pin = String.format(Locale.US, "%06d", SecureRandom().nextInt(1_000_000))
                sp.edit().putString("pin", pin).apply()
            }
            return pin
        }

        /** 숫자 6자리일 때만 저장한다. 연결 인증 때마다 저장값을 읽으므로 즉시 적용된다. */
        @Synchronized
        fun setPin(ctx: Context, pin: String): Boolean {
            if (!Regex("\\d{6}").matches(pin)) return false
            ctx.applicationContext.getSharedPreferences("relay", Context.MODE_PRIVATE)
                .edit().putString("pin", pin).apply()
            return true
        }
    }
}
