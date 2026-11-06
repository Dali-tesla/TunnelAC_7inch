package com.tunnelac.relay

import java.util.Arrays
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 폰 앱 ↔ 릴레이 앱 BLE(GATT) 프로토콜. 폰 앱(com.tunnelac.app.hvac.relay.RelayProtocol)과
 * UUID·값이 반드시 동일해야 한다. (인증 방식: PIN + HMAC)
 *
 * 서비스 SERVICE_UUID 아래 특성 3개:
 *  - NONCE (read)  : 읽을 때마다 새 16바이트 난수
 *  - AUTH  (write) : HMAC-SHA256(key=PIN(UTF-8), msg=nonce)[0..16]
 *  - CMD   (write, 응답 있는 쓰기) : 1바이트 모드 코드. 인증된 기기만 허용.
 *                    쓰기 응답 상태가 곧 결과다(성공=CAN 전송 성공, 실패=GATT_FAILURE).
 *
 * 모드 코드: 1=내기순환(RECIRC), 2=외기(FRESH), 3=오토(AUTO)  (펌웨어 패치의 c 값과 동일)
 */
object RelayProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("7ec1b2a0-5a11-4b22-9c33-7a7e1c0ffee5")
    val CMD_UUID: UUID = UUID.fromString("7ec1b2a1-5a11-4b22-9c33-7a7e1c0ffee5")
    val NONCE_UUID: UUID = UUID.fromString("7ec1b2a2-5a11-4b22-9c33-7a7e1c0ffee5")
    val AUTH_UUID: UUID = UUID.fromString("7ec1b2a3-5a11-4b22-9c33-7a7e1c0ffee5")
    /** STATE (read, 인증 후): 2바이트 [차량 내기순환 0=OFF/1=ON/2=알 수 없음, 내기 유지 모드 0/1] */
    val STATE_UUID: UUID = UUID.fromString("7ec1b2a4-5a11-4b22-9c33-7a7e1c0ffee5")

    fun codeName(code: Int): String = when (code) {
        1 -> "RECIRC"
        2 -> "FRESH"
        3 -> "AUTO"
        else -> "?"
    }

    fun hmac16(pin: String, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Arrays.copyOf(mac.doFinal(nonce), 16)
    }
}
