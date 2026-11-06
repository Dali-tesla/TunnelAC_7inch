package com.tunnelac.app.hvac

/**
 * 실제 차량 공조 제어 경로(CAN/BLE 등)를 감추는 인터페이스.
 * 구현체:
 *  - [SimulatedHvacController] : CAN 연결 전 개발/테스트용(메모리 상태만 변경)
 *  - [com.tunnelac.app.hvac.relay.BleRelayHvacController] : BLE(GATT)로 리어 디스플레이 릴레이에 명령 전달
 *
 * 엔진/서비스는 이 인터페이스만 사용하므로, 전송 경로가 바뀌어도 엔진 코드는 수정할 필요가 없다.
 */
interface HvacController {

    suspend fun getCurrentState(): HvacState

    /** 마지막 [setMode] 가 실패했을 때의 사유(성공이면 빈 문자열). 이벤트 로그에 남기는 용도. */
    val lastError: String get() = ""

    /**
     * 차량의 현재 내기순환 여부를 즉시 확인한다(true=내기, false=내기 아님=외기로 표시, null=확인 불가).
     * 이미 연결돼 있을 때만 시도하며 오래 기다리지 않는다. 기본은 null.
     */
    suspend fun peekCarRecirc(): Boolean? = null

    /** 터널이 가까워졌을 때 호출: 전송 경로(연결)를 미리 준비한다. 기본은 아무것도 안 함. */
    fun prepare() {}

    /**
     * 공조 모드를 지정 값으로 전환한다.
     * [HvacState.RECIRCULATION] / [HvacState.FRESH_AIR] / [HvacState.AUTO] 만 유효.
     * @return 명령 전달 성공 여부
     */
    suspend fun setMode(mode: HvacState): Boolean

    /** 하위호환 편의 함수. true=내기순환, false=외기. 내부적으로 [setMode] 를 호출한다. */
    suspend fun setRecirculation(on: Boolean): Boolean =
        setMode(if (on) HvacState.RECIRCULATION else HvacState.FRESH_AIR)
}
