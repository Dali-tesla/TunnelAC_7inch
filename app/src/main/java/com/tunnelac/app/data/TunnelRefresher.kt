package com.tunnelac.app.data

import android.content.Context
import com.tunnelac.app.data.overpass.OverpassRegions
import com.tunnelac.app.data.overpass.key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 터널 데이터 받기 진행 상태. */
data class RefreshState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** 진행/결과 안내 문구(없으면 빈 문자열). */
    val message: String = ""
) {
    /** 일부만 받아 둔 상태(이어받기 가능). */
    val resumable: Boolean get() = !running && done in 1 until total
}

/**
 * 전국 터널 데이터를 구역(격자)별로 나눠 받는다.
 *  - 구역 하나를 받을 때마다 바로 DB 에 저장하고 완료 목록을 폰에 기록한다.
 *  - 끊기거나 중지/종료돼도 완료 목록이 남아, 다시 시작하면 남은 구역만 이어서 받는다.
 *  - 앱 전체 범위에서 실행되므로 설정 화면을 벗어나도 계속된다(프로세스가 종료되면 멈추고 이어받기로 복구).
 *  - 구역이 [MAX_CONSECUTIVE_FAILS]번 연속 실패하면 네트워크 문제로 보고 중단한다.
 */
object TunnelRefresher {
    private const val MAX_CONSECUTIVE_FAILS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    /** 실행 중이 아닐 때, 저장된 완료 목록으로 대기 상태(몇 구역 완료)를 계산해 둔다. */
    fun refreshIdleState(context: Context) {
        if (isRunning) return
        val keys = OverpassRegions.koreaGrid().map { it.key() }.toSet()
        val done = Prefs(context.applicationContext).getDoneBoxes().count { it in keys }
        _state.value = _state.value.copy(running = false, done = done, total = keys.size)
    }

    fun toggle(context: Context) {
        if (isRunning) stop() else start(context)
    }

    fun stop() {
        job?.cancel()
    }

    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        val prefs = Prefs(app)
        val repo = TunnelRepository(app)
        job = scope.launch {
            val boxes = OverpassRegions.koreaGrid()
            val total = boxes.size
            val allKeys = boxes.map { it.key() }.toSet()
            val done = prefs.getDoneBoxes().filter { it in allKeys }.toMutableSet()
            if (done.size >= total) { // 지난번에 전부 끝났다면 새로 시작
                prefs.clearDoneBoxes()
                done.clear()
            }
            var consecutiveFails = 0
            var failedNow = 0
            var stoppedByNetwork = false
            _state.value = RefreshState(true, done.size, total, progressText(done.size, total))
            try {
                for (box in boxes) {
                    ensureActive()
                    val key = box.key()
                    if (key in done) continue
                    try {
                        repo.fetchAndStoreBox(box)
                        done.add(key)
                        prefs.addDoneBox(key)
                        consecutiveFails = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedNow++
                        consecutiveFails++
                        if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                            stoppedByNetwork = true
                            break
                        }
                    }
                    _state.value = RefreshState(true, done.size, total, progressText(done.size, total))
                }
                val finalMessage = when {
                    done.size >= total -> {
                        prefs.setLastOverpassUpdateMs(System.currentTimeMillis())
                        prefs.clearDoneBoxes()
                        "터널 데이터 갱신 완료 (${total}개 구역)"
                    }
                    stoppedByNetwork -> "연속 실패로 중단했습니다. 네트워크 확인 후 이어받기를 눌러 주세요. (${done.size}/${total} 구역 저장됨)"
                    else -> "${total - done.size}개 구역을 받지 못했습니다. 이어받기로 남은 구역을 다시 받으세요. (${done.size}/${total} 구역 저장됨)"
                }
                val shownDone = if (done.size >= total) total else done.size
                _state.value = RefreshState(false, shownDone, total, finalMessage)
            } catch (e: CancellationException) {
                _state.value = RefreshState(false, done.size, total, "받기를 중지했습니다. (${done.size}/${total} 구역 저장됨)")
                throw e
            }
        }
    }

    private fun progressText(done: Int, total: Int): String {
        val pct = if (total > 0) done * 100 / total else 0
        return "터널 데이터 받는 중... ${pct}%  (${done}/${total} 구역)"
    }
}
