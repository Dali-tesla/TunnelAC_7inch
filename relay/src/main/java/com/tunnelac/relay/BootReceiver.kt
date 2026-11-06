package com.tunnelac.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 기기 부팅 / 앱 업데이트 / 화면 잠금 해제 / ACC 깨어남 때 릴레이 서비스를 자동 시작한다.
 * 어떤 이벤트로 시작됐는지는 서비스 로그에 남긴다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            RelayService.ACTION_ACC_WAKE -> {
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, RelayService::class.java).putExtra(RelayService.EXTRA_TRIGGER, action)
                    )
                } catch (e: Exception) {
                    Log.w("BootReceiver", "릴레이 자동 시작 실패: ${e.message}")
                }
            }
        }
    }
}
