package com.tunnelac.app.data

import android.content.Context
import com.tunnelac.app.data.overpass.OverpassRegions
import com.tunnelac.app.data.overpass.key
import com.tunnelac.app.data.overpass.BoundingBox
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
    private const val MAX_NET_RETRIES = 5

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
            if (done.isEmpty()) repo.clearApproachProgress() // 새 갱신이면 접근도로 완료 표시도 처음부터
            var consecutiveFails = 0
            var failedNow = 0
            var stoppedByNetwork = false
            var lastError: String? = null
            _state.value = RefreshState(true, done.size, total, progressText(done.size, total))
            // 구역 안 진행 상황(터널 조회 / 접근도로 n/m 묶음 / 네트워크 대기)을 진행 문구 아래에 보여 준다.
            repo.statusListener = { status ->
                _state.value = RefreshState(true, done.size, total, progressText(done.size, total) + "\n구역 ${done.size + 1}: " + status)
            }
            try {
                for (box in boxes) {
                    ensureActive()
                    val key = box.key()
                    if (key in done) continue
                    var netRetries = 0
                    while (true) {
                        try {
                            repo.fetchAndStoreBox(box)
                            done.add(key)
                            prefs.addDoneBox(key)
                            consecutiveFails = 0
                            break
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e.message?.take(160) ?: e.javaClass.simpleName
                            if (e is NetworkDownException) { stoppedByNetwork = true; break }
                            // 네트워크가 끊긴 경우: 실패로 세지 않고 연결을 기다렸다가 같은 구역을 다시 시도한다.
                            if (repo.isNetworkError(e) && netRetries < MAX_NET_RETRIES) {
                                netRetries++
                                if (repo.waitForNetwork()) continue
                                stoppedByNetwork = true
                                break
                            }
                            failedNow++
                            consecutiveFails++
                            if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) stoppedByNetwork = true
                            break
                        }
                    }
                    if (stoppedByNetwork) break
                    _state.value = RefreshState(true, done.size, total, progressText(done.size, total))
                }
                // 전부 받았으면 접근도로가 빈 터널을 점검해 그 터널만 다시 받는다.
                var suppNote = ""
                if (done.size >= total && !stoppedByNetwork) {
                    repo.statusListener = { s -> _state.value = RefreshState(true, done.size, total, "접근도로 보완: $s") }
                    suppNote = supplementNote(repo)
                }
                val skipped = repo.skippedApproachCenters
                val skipNote = if (skipped > 0) " 접근도로를 받지 못한 입구 ${skipped}곳은 기존 방식으로 판정합니다." else ""
                val errNote = (lastError ?: repo.lastApproachError)?.let { "\n마지막 오류: $it" }.orEmpty()
                val finalMessage = when {
                    done.size >= total -> {
                        prefs.setLastOverpassUpdateMs(System.currentTimeMillis())
                        prefs.clearDoneBoxes()
                        "터널·접근도로 데이터 갱신 완료 (${total}개 구역)." + suppNote + skipNote
                    }
                    stoppedByNetwork -> "연속 실패로 중단했습니다. 네트워크 확인 후 이어받기를 눌러 주세요. (${done.size}/${total} 구역 저장됨)" + errNote
                    else -> "${total - done.size}개 구역을 받지 못했습니다. 이어받기로 남은 구역을 다시 받으세요. (${done.size}/${total} 구역 저장됨)" + errNote
                }
                val shownDone = if (done.size >= total) total else done.size
                _state.value = RefreshState(false, shownDone, total, finalMessage)
            } catch (e: CancellationException) {
                _state.value = RefreshState(false, done.size, total, "받기를 중지했습니다. (${done.size}/${total} 구역 저장됨)")
                throw e
            }
        }
    }

    /** 접근도로가 빈 터널을 점검·재조회하고 결과 문구를 돌려준다(없으면 빈 문자열). */
    private suspend fun supplementNote(repo: TunnelRepository, box: BoundingBox? = null): String {
        return try {
            val before = repo.findMissingApproachIds(box).size
            if (before == 0) return ""
            val remain = repo.supplementApproaches(box)
            "\n접근도로 보완: 빈 터널 ${before}개 중 ${before - remain}개 완료" +
                if (remain > 0) ", 남은 ${remain}개는 '접근도로 보완' 버튼으로 다시 시도하세요." else "."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "\n접근도로 보완 실패: ${e.message?.take(120) ?: e.javaClass.simpleName}"
        }
    }

    /** 지도에서 고른 구역 하나만 다시 받는다(터널 조회 -> 접근도로 -> 빈 터널 보완). 다른 구역과 이어받기 상태는 건드리지 않는다. */
    fun startZone(context: Context, box: BoundingBox, no: Int) {
        if (isRunning) return
        val repo = TunnelRepository(context.applicationContext)
        val before = _state.value
        job = scope.launch {
            _state.value = RefreshState(true, 0, 1, "구역 $no 갱신 중...")
            repo.statusListener = { s -> _state.value = RefreshState(true, 0, 1, "구역 $no: $s") }
            repo.resetApproachStats()
            try {
                repo.resetApproachProgressInBox(box)
                var tries = 0
                var count = 0
                while (true) {
                    try {
                        count = repo.fetchAndStoreBox(box)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e !is NetworkDownException && repo.isNetworkError(e) && tries < MAX_NET_RETRIES) {
                            tries++
                            if (repo.waitForNetwork()) continue
                        }
                        throw e
                    }
                }
                val note = supplementNote(repo)
                val skipped = repo.skippedApproachCenters
                val skipNote = if (skipped > 0) " 접근도로를 받지 못한 입구 ${skipped}곳은 기존 방식으로 판정합니다." else ""
                _state.value = RefreshState(false, before.done, before.total, "구역 $no 갱신 완료 (터널 ${count}개)." + note + skipNote)
            } catch (e: CancellationException) {
                _state.value = RefreshState(false, before.done, before.total, "구역 $no 갱신을 중지했습니다.")
                throw e
            } catch (e: Exception) {
                _state.value = RefreshState(false, before.done, before.total,
                    "구역 $no 갱신 실패: ${e.message?.take(120) ?: e.javaClass.simpleName}\n다시 눌러 주세요.")
            }
        }
    }

    /** 지도에서 고른 구역 하나에서 접근도로가 비어 건너뛴 터널만 다시 받는다(받아 둔 것은 건드리지 않는다). */
    fun startZoneSupplement(context: Context, box: BoundingBox, no: Int) {
        if (isRunning) return
        val repo = TunnelRepository(context.applicationContext)
        val before = _state.value
        job = scope.launch {
            _state.value = RefreshState(true, 0, 1, "구역 $no 점검 중...")
            repo.statusListener = { s -> _state.value = RefreshState(true, 0, 1, "구역 $no 보완: $s") }
            try {
                val note = supplementNote(repo, box).trim()
                val skipped = repo.skippedApproachCenters
                val skipNote = if (skipped > 0) " 접근도로를 받지 못한 입구 ${skipped}곳은 기존 방식으로 판정합니다." else ""
                val msg = if (note.isEmpty()) "구역 $no: 빈 접근도로가 없습니다." else "구역 $no " + note.removePrefix("접근도로 ") + skipNote
                _state.value = RefreshState(false, before.done, before.total, msg)
            } catch (e: CancellationException) {
                _state.value = RefreshState(false, before.done, before.total, "구역 $no 보완을 중지했습니다.")
                throw e
            }
        }
    }

    /** 지도에서 고른 터널 하나만 다시 받는다(좌표·이름 + 접근도로). */
    fun startTunnel(context: Context, tunnelId: Long) {
        if (isRunning) return
        val repo = TunnelRepository(context.applicationContext)
        val before = _state.value
        job = scope.launch {
            _state.value = RefreshState(true, 0, 1, "터널 갱신 중...")
            repo.statusListener = { s -> _state.value = RefreshState(true, 0, 1, "터널: $s") }
            try {
                var tries = 0
                var msg: String
                while (true) {
                    try {
                        msg = repo.refreshOneTunnel(tunnelId)
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e !is NetworkDownException && repo.isNetworkError(e) && tries < MAX_NET_RETRIES) {
                            tries++
                            if (repo.waitForNetwork()) continue
                        }
                        msg = "터널 갱신 실패: ${e.message?.take(120) ?: e.javaClass.simpleName}\n다시 눌러 주세요."
                        break
                    }
                }
                _state.value = RefreshState(false, before.done, before.total, msg)
            } catch (e: CancellationException) {
                _state.value = RefreshState(false, before.done, before.total, "터널 갱신을 중지했습니다.")
                throw e
            }
        }
    }

    /** 설정 화면의 '접근도로 보완' 버튼: 접근도로가 빈 터널만 점검해서 다시 받는다. */
    fun startSupplement(context: Context) {
        if (isRunning) return
        val repo = TunnelRepository(context.applicationContext)
        val before = _state.value
        job = scope.launch {
            _state.value = RefreshState(true, 0, 1, "접근도로 점검 중...")
            repo.statusListener = { s -> _state.value = RefreshState(true, 0, 1, "접근도로 보완: $s") }
            try {
                val note = supplementNote(repo).trim()
                val msg = if (note.isEmpty()) "빈 접근도로가 없습니다. 모든 터널이 점검을 마쳤습니다." else note
                _state.value = RefreshState(false, before.done, before.total, msg)
            } catch (e: CancellationException) {
                _state.value = RefreshState(false, before.done, before.total, "접근도로 보완을 중지했습니다.")
                throw e
            }
        }
    }

    private fun progressText(done: Int, total: Int): String {
        val pct = if (total > 0) done * 100 / total else 0
        return "터널·접근도로 데이터 받는 중... ${pct}%  (${done}/${total} 구역)"
    }
}
