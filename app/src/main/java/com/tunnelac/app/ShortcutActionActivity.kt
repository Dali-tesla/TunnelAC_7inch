package com.tunnelac.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.tunnelac.app.service.TunnelDetectionService

/**
 * 정적 앱 바로가기(삼성 모드 및 루틴의 "앱 세부 동작")와 START/STOP 액션을 받아
 * 화면 없이 서비스를 시작/정지시키고 즉시 종료되는 투명 액티비티.
 */
class ShortcutActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, TunnelDetectionService::class.java)
        when (intent?.action) {
            TunnelDetectionService.ACTION_STOP -> {
                serviceIntent.action = TunnelDetectionService.ACTION_STOP
                startService(serviceIntent)
            }
            else -> {
                serviceIntent.action = TunnelDetectionService.ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }
        finish()
    }
}
