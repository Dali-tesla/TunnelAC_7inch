package com.tunnelac.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 알림의 "정지" 액션 버튼에서 오는 브로드캐스트를 받아 서비스를 중지시킨다. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 서비스가 오토 복귀 명령을 보낸 뒤 스스로 종료하도록 정지 액션으로 전달한다.
        context.startService(
            Intent(context, TunnelDetectionService::class.java).setAction(TunnelDetectionService.ACTION_STOP)
        )
    }
}
