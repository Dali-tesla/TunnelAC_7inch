package com.tunnelac.app.hvac

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * CAN 연결 전 개발/테스트용 가상 공조 컨트롤러.
 * 실제 하드웨어 없이 상태만 메모리에 보관하고 로그를 남긴다.
 */
class SimulatedHvacController(
    initialState: HvacState = HvacState.AUTO
) : HvacController {

    private val state = AtomicReference(initialState)

    override suspend fun getCurrentState(): HvacState = state.get()

    override suspend fun setMode(mode: HvacState): Boolean {
        if (mode != HvacState.RECIRCULATION && mode != HvacState.FRESH_AIR && mode != HvacState.AUTO) {
            return false
        }
        val prev = state.getAndSet(mode)
        if (prev != mode) {
            Log.i(TAG, "[시뮬레이션] 공조 상태 변경: $prev -> $mode")
        }
        return true
    }

    companion object {
        private const val TAG = "SimulatedHvac"
    }
}
