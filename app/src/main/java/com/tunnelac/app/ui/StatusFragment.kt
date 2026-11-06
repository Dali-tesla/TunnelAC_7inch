package com.tunnelac.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tunnelac.app.R
import com.tunnelac.app.engine.EngineState
import com.tunnelac.app.hvac.HvacState
import com.tunnelac.app.service.TunnelDetectionService
import kotlinx.coroutines.launch

class StatusFragment : Fragment(R.layout.fragment_status) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvServiceState = view.findViewById<TextView>(R.id.tv_service_state)
        val tvEngineState = view.findViewById<TextView>(R.id.tv_engine_state)
        val tvHvacState = view.findViewById<TextView>(R.id.tv_hvac_state)
        val tvRelayState = view.findViewById<TextView>(R.id.tv_relay_state)
        val tvGpsState = view.findViewById<TextView>(R.id.tv_gps_state)
        val tvNearestTunnel = view.findViewById<TextView>(R.id.tv_nearest_tunnel)

        view.findViewById<Button>(R.id.btn_start).setOnClickListener {
            val intent = Intent(requireContext(), TunnelDetectionService::class.java).apply {
                action = TunnelDetectionService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        }

        view.findViewById<Button>(R.id.btn_stop).setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), TunnelDetectionService::class.java).apply {
                    action = TunnelDetectionService.ACTION_STOP
                }
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TunnelDetectionService.isRunning.collect { running ->
                        tvServiceState.text = getString(
                            if (running) R.string.status_service_running else R.string.status_service_stopped
                        )
                        tvGpsState.text = ""
                        tvNearestTunnel.text = ""
                    }
                }
                launch {
                    TunnelDetectionService.relayText.collect { tvRelayState.text = it }
                }
                launch {
                    TunnelDetectionService.engineState.collect { state ->
                        tvEngineState.text = when (state) {
                            is EngineState.Idle -> getString(R.string.status_engine_idle)
                            is EngineState.InTunnel -> getString(R.string.status_engine_in_tunnel) +
                                (state.tunnelName?.let { " ($it)" } ?: "")
                        }
                    }
                }
                launch {
                    TunnelDetectionService.hvacState.collect { hvac ->
                        tvHvacState.text = "공조 상태: " + when (hvac) {
                            HvacState.RECIRCULATION -> "내기순환"
                            HvacState.FRESH_AIR -> "외기순환"
                            HvacState.AUTO -> "오토"
                            HvacState.UNKNOWN -> "알 수 없음 (서비스 미실행)"
                        }
                    }
                }
            }
        }
    }
}
