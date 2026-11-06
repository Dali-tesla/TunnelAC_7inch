package com.tunnelac.relay

import android.content.Context

/**
 * 차량 공조의 내기순환 상태를 수신 전용으로 감시한다. WWC2 헤드유닛의 canbussdk 에 바인드해
 * 변경 통지(콜백)를 받고, [poll] 때 getAirconditionData() 로 현재 값을 직접 조회한다.
 * 차량으로 어떤 명령도 보내지 않는다. 실제 연결·통신은 [WwcCan] 이 담당한다.
 *
 * [onInnerLoop] 는 상태를 받을 때마다 호출된다(바인더 스레드일 수 있으므로 받는 쪽에서 스레드를 넘길 것).
 */
class CarAirMonitor(
    private val ctx: Context,
    private val onInnerLoop: (Boolean) -> Unit,
    private val log: (String) -> Unit
) {
    private val listener: (Boolean) -> Unit = { v -> onInnerLoop(v) }
    private var listening = false

    val connected: Boolean get() = WwcCan.isReady

    /** 연결돼 있지 않으면 연결을 시도한다(비동기). 지금 사용 가능하면 true. */
    @Synchronized
    fun ensureConnected(): Boolean {
        WwcCan.log = log
        if (!listening) {
            WwcCan.addListener(listener)
            listening = true
        }
        return WwcCan.ensureBound(ctx)
    }

    /** 현재 값을 한 번 조회한다(콜백이 끊겨도 값을 놓치지 않기 위한 보조 수단). */
    fun poll() {
        if (!ensureConnected()) return
        WwcCan.refresh() // 값이 있으면 WwcCan 이 리스너(onInnerLoop)에 전달한다
    }

    @Synchronized
    fun stop() {
        if (listening) {
            WwcCan.removeListener(listener)
            listening = false
        }
        WwcCan.release(ctx)
    }
}
