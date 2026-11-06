package com.tunnelac.relay

import android.content.Context
import android.util.Log

/**
 * 폰 앱의 모드 코드(1=내기, 2=외기, 3=오토)를 WWC2 헤드유닛의 순환 모드로 맞춘다.
 *
 * 순정 런처는 순환을 "설정"하는 명령이 없고 **순환 키(0x13) 하나**만 보낸다. 실측 결과 이 키는 내기·오토·외기를
 * 돌아가며 바꾸고, 현재 모드는 IN_OUT_LOOP / AQS_INNERLOOP 두 값으로 구분된다(WwcCan.recircMode).
 *   내기 = loop 1 · aqs 0,  오토 = loop 1 · aqs 1,  외기 = loop 0
 *
 * 그래서 현재 모드를 먼저 읽고, 목표와 다르면 키를 누른 뒤 0.7초마다 다시 읽어 목표가 될 때까지 최대 3번 누른다
 * ([WwcCan.cycleTo]). 이미 목표 모드면 아무것도 보내지 않는다(성공 처리).
 * 현재 모드를 알 수 없으면 엉뚱한 방향으로 넘길 수 있으므로 **전송하지 않고 실패**로 돌려준다.
 */
object CanSender {

    data class Result(val ok: Boolean, val message: String)

    @Synchronized
    fun send(ctx: Context, canCode: Int): Result {
        if (canCode !in 1..3) return Result(false, "유효하지 않은 코드: $canCode")
        if (!WwcCan.ensureBound(ctx)) {
            return Result(false, "차량 CAN 서비스(canbussdk) 연결 중이거나 연결 불가 — 잠시 후 재시도")
        }
        return try {
            setMode(canCode)
        } catch (e: Throwable) {
            Log.w(TAG, "CAN 전송 실패", e)
            Result(false, "오류: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun setMode(target: Int): Result {
        val label = WwcCan.modeName(target)
        // 같은 목표로 전환이 이미 진행 중이면 겹쳐 누르지 않는다.
        if (WwcCan.isCyclingTo(target)) return Result(true, "$label 전환 진행 중")

        WwcCan.refresh()
        val current = WwcCan.recircMode
            ?: return Result(false, "차량의 현재 순환 모드를 읽지 못해 전환하지 않음")
        if (current == target) return Result(true, "이미 $label 상태 (전송 생략)")

        val err = WwcCan.cycleTo(target)
        if (err != null) return Result(false, err)
        return Result(true, "순환 키(0x13) 전송 → ${WwcCan.modeName(current)}에서 $label 로 전환 요청 (결과는 로그 확인)")
    }

    private const val TAG = "CanSender"
}
