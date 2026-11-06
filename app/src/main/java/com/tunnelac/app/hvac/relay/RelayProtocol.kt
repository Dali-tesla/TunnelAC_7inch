package com.tunnelac.app.hvac.relay

import com.tunnelac.app.hvac.HvacState
import java.util.Arrays
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 폰 앱 ↔ 리어 디스플레이 릴레이 앱 사이의 BLE(GATT) 프로토콜.
 * 릴레이 앱(relay 모듈)의 RelayProtocol 과 UUID·값이 반드시 일치해야 한다.
 *
 * NONCE(read) → 폰이 HMAC-SHA256(PIN, nonce)[0..16] 를 AUTH(write) 로 전송 → 인증되면
 * CMD(write, 응답 있는 쓰기)로 1바이트 모드 코드를 보낸다. 쓰기 응답 상태가 곧 결과다.
 * 모드 코드: 1=내기순환, 2=외기, 3=오토 (펌웨어 패치의 c 값과 동일)
 */
object RelayProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("7ec1b2a0-5a11-4b22-9c33-7a7e1c0ffee5")
    val CMD_UUID: UUID = UUID.fromString("7ec1b2a1-5a11-4b22-9c33-7a7e1c0ffee5")
    val NONCE_UUID: UUID = UUID.fromString("7ec1b2a2-5a11-4b22-9c33-7a7e1c0ffee5")
    val AUTH_UUID: UUID = UUID.fromString("7ec1b2a3-5a11-4b22-9c33-7a7e1c0ffee5")
    /** STATE (read, 인증 후): 2바이트 [차량 내기순환 0=OFF/1=ON/2=알 수 없음, 내기 유지 모드 0/1] */
    val STATE_UUID: UUID = UUID.fromString("7ec1b2a4-5a11-4b22-9c33-7a7e1c0ffee5")

    /** HvacState -> 모드 코드. RECIRC/FRESH/AUTO 외에는 null. */
    fun modeToCode(mode: HvacState): Int? = when (mode) {
        HvacState.RECIRCULATION -> 1
        HvacState.FRESH_AIR -> 2
        HvacState.AUTO -> 3
        else -> null
    }

    fun hmac16(pin: String, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Arrays.copyOf(mac.doFinal(nonce), 16)
    }
}
