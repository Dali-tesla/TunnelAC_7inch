package com.tunnelac.app

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class TunnelAcApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid 설정: OSM 타일 정책상 앱 고유 User-Agent 필수, 저장소 권한 없이 앱 캐시 폴더 사용.
        val cfg = Configuration.getInstance()
        cfg.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        cfg.userAgentValue = "TunnelAC/${BuildConfig.VERSION_NAME} (${packageName})"
        val base = File(cacheDir, "osmdroid")
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles")
    }
}
