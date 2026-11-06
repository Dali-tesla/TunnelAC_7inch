package com.tunnelac.relay

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class RelayActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etPin: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay)
        // 서비스가 꺼져 있어도 로컬 테스트가 바로 되도록 차량 CAN 서비스(canbussdk)에 미리 연결해 둔다.
        try { WwcCan.ensureBound(applicationContext) } catch (e: Throwable) { /* 실패해도 화면은 떠야 한다 */ }
        showLastCrash()
        requestBlePermissions()

        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)
        etPin = findViewById(R.id.et_pin)
        etPin.setText(RelayService.getOrCreatePin(this))
        findViewById<Button>(R.id.btn_save_pin).setOnClickListener {
            val pin = etPin.text.toString().trim()
            if (RelayService.setPin(this, pin)) {
                Toast.makeText(this, "PIN이 변경되었습니다. 폰 앱에도 같은 PIN을 입력하세요.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "PIN은 숫자 6자리여야 합니다.", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, RelayService::class.java))
            Toast.makeText(this, "릴레이 서비스 시작", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
            Toast.makeText(this, "릴레이 서비스 정지", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_ble_retry).setOnClickListener {
            BleGuard.reset(this)
            Toast.makeText(this, "BLE 차단을 풀었습니다. '릴레이 서비스 시작'을 누르세요.", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btn_force_toggle).setOnClickListener {
            // 상태를 보지 않고 내기/외기 토글 키(0x13)를 무조건 한 번 보낸다(전송 경로 확인용).
            val err = if (WwcCan.ensureBound(applicationContext)) WwcCan.pressAirKey(0x13) else "canbussdk 연결 중"
            Toast.makeText(this, if (err == null) "강제 토글 전송" else "강제 토글 실패: $err", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_local_recirc).setOnClickListener { localTest(1, "RECIRC") }
        findViewById<Button>(R.id.btn_local_fresh).setOnClickListener { localTest(2, "FRESH") }
        findViewById<Button>(R.id.btn_local_auto).setOnClickListener { localTest(3, "AUTO") }

        observeState()
    }

    /** 지난번 실행이 예외로 종료됐다면 원인을 보여준다(캡처해서 보내주세요). */
    private fun showLastCrash() {
        val text = CrashLog.readLast(this) ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("지난 실행 크래시 기록")
            .setMessage(text.take(3000))
            .setPositiveButton("확인") { _, _ -> CrashLog.clear(this) }
            .show()
    }

    /** Android 12+ 는 BLE 광고/GATT 에 런타임 권한(근처 기기)이 필요하다. 없으면 팝업으로 요청한다. */
    private fun requestBlePermissions() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val need = arrayOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE")
            .filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) {
            try {
                androidx.core.app.ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
            } catch (e: Throwable) {
                Toast.makeText(this, "권한 요청 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this, if (ok) "BLE 권한 허용됨. 서비스를 다시 시작하세요." else "BLE 권한이 거부되었습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun localTest(code: Int, name: String) {
        // 서비스가 실행 중이면 BLE 경로와 같은 로직(내기 유지 모드 포함)으로, 아니면 CAN 직접 전송
        val svc = RelayService.instance
        val r = svc?.runCommand(code, "로컬") ?: CanSender.send(this, code)
        val msg = "로컬 테스트 $name: ${if (r.ok) "성공" else "실패"} (${r.message})"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RelayService.runningState.collect { render() }
                }
                launch {
                    RelayService.clientConnectedState.collect { render() }
                }
                launch {
                    RelayService.statusState.collect { render() }
                }
                launch { RelayService.holdState.collect { render() } }
                launch { RelayService.carInnerLoopState.collect { render() } }
                launch { RelayService.monitorState.collect { render() } }
                launch { RelayService.resendCountState.collect { render() } }
                launch {
                    RelayService.lastLogState.collect { log ->
                        tvLog.text = log
                        render() // 오토/외기 전환은 로그로만 알려지므로 상태 표시도 함께 갱신
                    }
                }
            }
        }
    }

    private fun render() {
        val running = RelayService.runningState.value
        val connected = RelayService.clientConnectedState.value
        tvStatus.text = buildString {
            append(if (running) "서비스: 실행 중" else "서비스: 정지됨")
            append("\n")
            append(if (connected) "폰 연결: 연결됨 ✓" else "폰 연결: 대기 중")
            append("\n")
            append("BLE: ").append(RelayService.statusState.value)
            append("\n")
            append("내기 유지: ").append(if (RelayService.holdState.value) "ON" else "OFF")
            append("  ·  재전송 ").append(RelayService.resendCountState.value).append("회")
            append("\n")
            append("차량 내기순환: ")
            append(
                when {
                    !RelayService.monitorState.value -> "감시 연결 안 됨"
                    RelayService.carInnerLoopState.value == null -> "수신 대기"
                    else -> WwcCan.modeName(WwcCan.recircMode)
                }
            )
        }
    }
}
