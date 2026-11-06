package com.tunnelac.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tunnelac.app.MainActivity
import com.tunnelac.app.R
import com.tunnelac.app.engine.EngineState

/**
 * 잠금화면에서도 실행 여부를 확인할 수 있는 지속 알림(포그라운드 서비스 알림)을 관리한다.
 * VISIBILITY_PUBLIC 으로 설정해 잠금화면에도 그대로 노출한다.
 */
class NotificationHelper(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            // 앱 아이콘 배지(숫자 1) 표시 안 함. 기존 채널은 설정 변경이 반영되지 않으므로 새 채널로 교체한다.
            channel.setShowBadge(false)
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(state: EngineState): android.app.Notification {
        val contentText = when (state) {
            is EngineState.Idle -> context.getString(R.string.status_engine_idle)
            is EngineState.InTunnel -> context.getString(R.string.status_engine_in_tunnel)
        }

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, StopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title_running))
            .setContentText(contentText)
            .setOngoing(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "tunnelac_running_v2"
        private const val OLD_CHANNEL_ID = "tunnelac_running"
        const val NOTIFICATION_ID = 1001
    }
}
