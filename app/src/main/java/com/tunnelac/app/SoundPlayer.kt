package com.tunnelac.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.tunnelac.app.data.Prefs

/** 진입/진출 알림음 재생. 음량은 앱 설정(0~100)을 적용한다. 새 소리가 오면 이전 소리를 끊는다. */
object SoundPlayer {

    enum class Kind(val resId: Int) {
        ENTRY(R.raw.entry_callsign),
        EXIT(R.raw.exit_chime)
    }

    private var player: MediaPlayer? = null

    /** 설정(사용 여부)을 확인하고 재생한다. */
    @Synchronized
    fun playIfEnabled(context: Context, kind: Kind) {
        val prefs = Prefs(context)
        if (!prefs.isSoundEnabled()) return
        play(context, kind, prefs.getSoundVolume())
    }

    /** 사용 여부와 무관하게 지정 음량(0~100)으로 재생한다(미리듣기용). */
    @Synchronized
    fun play(context: Context, kind: Kind, volumePercent: Int) {
        stop()
        try {
            val v = (volumePercent.coerceIn(0, 100)) / 100f
            val mp = MediaPlayer()
            val afd = context.applicationContext.resources.openRawResourceFd(kind.resId)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setVolume(v, v)
            mp.setOnCompletionListener { release(it) }
            mp.setOnErrorListener { m, _, _ -> release(m); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            stop()
        }
    }

    /** 재생 중 음량을 바로 반영(슬라이더 조정용). */
    @Synchronized
    fun setLiveVolume(volumePercent: Int) {
        val v = volumePercent.coerceIn(0, 100) / 100f
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        player?.let { release(it) }
        player = null
    }

    private fun release(mp: MediaPlayer) {
        try { mp.release() } catch (_: Exception) {}
        if (player === mp) player = null
    }
}
