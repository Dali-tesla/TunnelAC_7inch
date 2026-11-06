package com.tunnelac.relay

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** 앱이 예외로 죽으면 원인(스택트레이스)을 파일로 남기고, 다음 실행 때 화면에 보여준다. */
class RelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * BLE 시작 도중 앱이 (네이티브 크래시 등으로) 죽었는지 감지한다.
 * 시작 직전에 pending 을 켜고, 광고 성공/실패 콜백이나 정상 중지에서 끈다.
 * 다음 실행 때 pending 이 남아 있으면 BLE 시작이 앱을 죽인 것으로 보고 BLE 를 자동으로 끈다.
 */
object BleGuard {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("relay_guard", Context.MODE_PRIVATE)

    /** BLE 를 시작해도 되면 true. 지난번 시작 중 죽었으면 비활성화하고 false. */
    fun begin(ctx: Context): Boolean {
        val p = sp(ctx)
        if (p.getBoolean("pending", false)) {
            p.edit().putBoolean("disabled", true).putBoolean("pending", false).commit()
        }
        if (p.getBoolean("disabled", false)) return false
        return p.edit().putBoolean("pending", true).commit()
    }

    fun ok(ctx: Context) { sp(ctx).edit().putBoolean("pending", false).apply() }

    fun isDisabled(ctx: Context) = sp(ctx).getBoolean("disabled", false)

    fun reset(ctx: Context) { sp(ctx).edit().clear().commit() }
}

object CrashLog {
    private const val FILE = "crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(app, t, e) } catch (ignored: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun write(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("시각: ${Date()}")
            pw.println("스레드: ${t.name}")
            pw.println("기기: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            e.printStackTrace(pw)
        }
        val text = sw.toString()
        File(ctx.filesDir, FILE).writeText(text)
        // 파일 관리자로 열어볼 수 있게 외부 앱 전용 폴더에도 한 부 남긴다.
        try { ctx.getExternalFilesDir(null)?.let { File(it, FILE).writeText(text) } } catch (ignored: Throwable) {}
    }

    /** 직전 크래시 기록을 돌려준다. 없으면 null. */
    fun readLast(ctx: Context): String? = try {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    } catch (ignored: Throwable) { null }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (ignored: Throwable) {}
        try { ctx.getExternalFilesDir(null)?.let { File(it, FILE).delete() } } catch (ignored: Throwable) {}
    }
}
