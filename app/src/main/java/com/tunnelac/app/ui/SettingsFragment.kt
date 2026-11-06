package com.tunnelac.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tunnelac.app.R
import com.tunnelac.app.SoundPlayer
import com.tunnelac.app.data.Prefs
import com.tunnelac.app.data.TunnelRefresher
import com.tunnelac.app.data.TunnelRepository
import com.tunnelac.app.engine.EngineConfig
import com.tunnelac.app.hvac.HvacState
import com.tunnelac.app.hvac.relay.BleRelayHvacController
import kotlinx.coroutines.launch

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var prefs: Prefs
    private lateinit var repository: TunnelRepository

    // 블루투스(근처 기기) 권한 요청 후 실행할 동작
    private var pendingBtAction: (() -> Unit)? = null
    private val requestBtPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pendingBtAction?.invoke()
        } else {
            Toast.makeText(requireContext(), "블루투스(근처 기기) 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
        pendingBtAction = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        repository = TunnelRepository(requireContext().applicationContext)

        val etEntry = view.findViewById<EditText>(R.id.et_entry_distance)
        val etExit = view.findViewById<EditText>(R.id.et_exit_distance)
        val etCorridor = view.findViewById<EditText>(R.id.et_corridor_width)
        val etHeading = view.findViewById<EditText>(R.id.et_heading_tolerance)
        val etMinSpeed = view.findViewById<EditText>(R.id.et_min_speed)
        val etMergeGap = view.findViewById<EditText>(R.id.et_merge_gap)
        val etMaxHold = view.findViewById<EditText>(R.id.et_max_hold)
        val etMinLen = view.findViewById<EditText>(R.id.et_min_tunnel_length)
        val swExitFresh = view.findViewById<SwitchCompat>(R.id.sw_exit_fresh)
        val tvTunnelCount = view.findViewById<TextView>(R.id.tv_tunnel_count)
        val progress = view.findViewById<ProgressBar>(R.id.progress_refresh)
        val tvPercent = view.findViewById<TextView>(R.id.tv_refresh_percent)

        val swRelay = view.findViewById<SwitchCompat>(R.id.sw_relay_enabled)
        val tvRelayDevice = view.findViewById<TextView>(R.id.tv_relay_device)
        val etRelayPin = view.findViewById<EditText>(R.id.et_relay_pin)
        val btnTestRelay = view.findViewById<Button>(R.id.btn_test_relay)
        val btnTestRelayFresh = view.findViewById<Button>(R.id.btn_test_relay_fresh)
        val btnTestRelayAuto = view.findViewById<Button>(R.id.btn_test_relay_auto)

        val config = prefs.loadConfig()
        etEntry.setText(config.entryTriggerM.toInt().toString())
        etExit.setText(config.exitTriggerM.toInt().toString())
        etCorridor.setText(config.corridorWidthM.toInt().toString())
        etHeading.setText(config.headingToleranceDeg.toInt().toString())
        etMinSpeed.setText(config.minSpeedKmh.toInt().toString())
        etMergeGap.setText(config.mergeGapM.toInt().toString())
        etMaxHold.setText(config.maxHoldMinutes.toInt().toString())
        etMinLen.setText(config.minTunnelLengthM.toInt().toString())
        swExitFresh.isChecked = config.exitMode == HvacState.FRESH_AIR

        // 릴레이 설정 초기화
        swRelay.isChecked = prefs.isRelayEnabled()
        tvRelayDevice.text = getString(R.string.relay_pin_help)
        etRelayPin.setText(prefs.getRelayPin())

        refreshTunnelCount(tvTunnelCount)

        // 알림음 설정(변경 즉시 저장)
        val swSound = view.findViewById<SwitchCompat>(R.id.sw_sound_enabled)
        val sbVolume = view.findViewById<SeekBar>(R.id.sb_sound_volume)
        val tvVolume = view.findViewById<TextView>(R.id.tv_sound_volume)
        swSound.isChecked = prefs.isSoundEnabled()
        sbVolume.progress = prefs.getSoundVolume()
        tvVolume.text = "${sbVolume.progress}%"
        swSound.setOnCheckedChangeListener { _, checked -> prefs.setSoundEnabled(checked) }
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolume.text = "$progress%"
                if (fromUser) {
                    prefs.setSoundVolume(progress)
                    SoundPlayer.setLiveVolume(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        view.findViewById<Button>(R.id.btn_preview_entry).setOnClickListener {
            SoundPlayer.play(requireContext(), SoundPlayer.Kind.ENTRY, sbVolume.progress)
        }
        view.findViewById<Button>(R.id.btn_preview_exit).setOnClickListener {
            SoundPlayer.play(requireContext(), SoundPlayer.Kind.EXIT, sbVolume.progress)
        }

        view.findViewById<Button>(R.id.btn_save_settings).setOnClickListener {
            // 외기 전환 ON = 진출 시 외기, OFF = 오토 복귀(내기 강제 해제만, 외기로 강제하지 않음)
            val exitMode = if (swExitFresh.isChecked) HvacState.FRESH_AIR else HvacState.AUTO
            val newConfig = EngineConfig(
                entryTriggerM = etEntry.text.toString().toIntOrNull()?.toDouble() ?: config.entryTriggerM,
                exitTriggerM = etExit.text.toString().toIntOrNull()?.toDouble() ?: config.exitTriggerM,
                corridorWidthM = etCorridor.text.toString().toIntOrNull()?.toDouble() ?: config.corridorWidthM,
                headingToleranceDeg = etHeading.text.toString().toIntOrNull()?.toDouble() ?: config.headingToleranceDeg,
                minSpeedKmh = etMinSpeed.text.toString().toIntOrNull()?.toDouble() ?: config.minSpeedKmh,
                mergeGapM = etMergeGap.text.toString().toIntOrNull()?.toDouble() ?: config.mergeGapM,
                maxHoldMinutes = etMaxHold.text.toString().toIntOrNull()?.toDouble() ?: config.maxHoldMinutes,
                minTunnelLengthM = etMinLen.text.toString().toIntOrNull()?.toDouble() ?: config.minTunnelLengthM,
                gpsLostAccuracyThresholdM = config.gpsLostAccuracyThresholdM,
                exitMode = exitMode
            )
            prefs.saveConfig(newConfig)
            Toast.makeText(requireContext(), "설정이 저장되었습니다. (감지 재시작 시 적용)", Toast.LENGTH_SHORT).show()
        }

        swRelay.setOnCheckedChangeListener { _, checked ->
            prefs.setRelayEnabled(checked)
            if (checked) withBtPermissions { } // 근처 기기 권한을 미리 받아 둔다(백그라운드 연결에 필요)
        }

        etRelayPin.doAfterTextChanged { prefs.setRelayPin(it?.toString().orEmpty()) }

        // 내기 / 외기 / 오토 테스트 버튼: 같은 흐름에 모드만 다르게 전송한다.
        listOf(
            btnTestRelay to HvacState.RECIRCULATION,
            btnTestRelayFresh to HvacState.FRESH_AIR,
            btnTestRelayAuto to HvacState.AUTO
        ).forEach { (btn, mode) ->
            btn.setOnClickListener {
                val pin = prefs.getRelayPin()
                if (pin.isBlank()) {
                    Toast.makeText(requireContext(), "먼저 릴레이 앱 화면의 PIN을 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                withBtPermissions { testRelay(pin, mode) }
            }
        }

        // 터널 데이터 받기: 구역별로 나눠 받고, 끊기면 남은 구역만 이어받는다(진행은 앱 전체에서 유지).
        val btnRefresh = view.findViewById<Button>(R.id.btn_refresh_tunnels)
        TunnelRefresher.refreshIdleState(requireContext())
        btnRefresh.setOnClickListener { TunnelRefresher.toggle(requireContext()) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TunnelRefresher.state.collect { st ->
                        btnRefresh.text = when {
                            st.running -> getString(R.string.btn_refresh_stop)
                            st.resumable -> getString(R.string.btn_refresh_resume, st.done, st.total)
                            else -> getString(R.string.btn_refresh_tunnels)
                        }
                        progress.visibility = if (st.running) View.VISIBLE else View.GONE
                        progress.max = st.total.coerceAtLeast(1)
                        progress.progress = st.done
                        tvPercent.visibility = if (st.message.isNotEmpty()) View.VISIBLE else View.GONE
                        tvPercent.text = st.message
                    }
                }
                launch {
                    repository.observeTunnelCount().collect { n ->
                        tvTunnelCount.text = "저장된 터널 수: ${n}개"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        SoundPlayer.stop()
        super.onDestroyView()
    }

    private fun refreshTunnelCount(tv: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = repository.tunnelCount()
            tv.text = "저장된 터널 수: ${count}개"
        }
    }

    /** 블루투스 권한(Android 12+: 근처 기기 검색·연결)을 확인하고, 있으면 즉시 action 실행, 없으면 요청 후 실행. */
    private fun withBtPermissions(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            action(); return
        }
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            action()
        } else {
            pendingBtAction = action
            requestBtPermissions.launch(needed)
        }
    }

    private fun testRelay(pin: String, mode: HvacState) {
        val label = when (mode) {
            HvacState.RECIRCULATION -> "내기"
            HvacState.FRESH_AIR -> "외기"
            HvacState.AUTO -> "오토"
            else -> mode.name
        }
        Toast.makeText(requireContext(), "릴레이 연결 중... (최대 20초)", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val controller = BleRelayHvacController(requireContext().applicationContext, pin)
            var detail = ""
            val ok = try {
                controller.start()
                if (controller.awaitReady(20_000)) {
                    controller.setMode(mode)
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            } finally {
                detail = controller.statusText
                controller.shutdown()
            }
            val msg = if (ok) {
                "릴레이 전송 성공 ($label 명령 처리됨)"
            } else {
                "릴레이 전송 실패 ($label): $detail"
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        }
    }
}
