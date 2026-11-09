package com.tunnelac.app.export

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonWriter
import androidx.room.withTransaction
import com.tunnelac.app.BuildConfig
import com.tunnelac.app.data.Prefs
import com.tunnelac.app.data.TunnelRefresher
import com.tunnelac.app.data.db.AdjacentEntity
import com.tunnelac.app.data.db.AppDatabase
import com.tunnelac.app.data.db.ApproachEntity
import com.tunnelac.app.data.db.TunnelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 저장된 터널·접근도로 데이터를 하나의 파일(.tac)로 내보내고, 받은 .tac 파일을 바로 적용한다.
 * 형식: gzip 으로 압축한 JSON. 헤더(format/version/createdMs/appVersion/개수) 다음에 tunnels, approaches 배열.
 * 데이터는 OpenStreetMap contributors (ODbL) 에서 온 것이므로 공유할 때 출처를 유지해야 한다.
 */
object TunnelDataIO {
    const val FORMAT = "tunnelac-data"
    const val VERSION = 1
    private const val FILE_PREFIX = "tunnelac-data-"
    const val EXTENSION = "tac"

    /** 파일에서 읽어 검증을 마친 데이터 묶음. */
    class Package(
        val createdMs: Long,
        val appVersion: String,
        val tunnels: List<TunnelEntity>,
        val approaches: List<ApproachEntity>,
        /** 접근도로 계산을 마친 터널 id(선택 항목. PC 계산기가 넣는다). 접근도로가 0개여도 '보완' 대상에서 뺀다. */
        val doneIds: List<Long> = emptyList(),
        /** 인접도로(선택 항목. PC 계산기가 넣는다). */
        val adjacent: List<AdjacentEntity> = emptyList()
    )

    /** DB의 터널·접근도로를 .tac 파일로 만든다(앱 전용 exports/ 폴더, 이전 내보내기 파일은 정리). */
    suspend fun export(context: Context): File = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val tunnels = db.tunnelDao().getAll()
        if (tunnels.isEmpty()) throw IllegalStateException("내보낼 터널 데이터가 없습니다. 먼저 '터널 데이터 갱신'을 해 주세요.")
        val approaches = db.approachDao().getAll()

        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".$EXTENSION") }?.forEach { it.delete() }
        val file = File(dir, FILE_PREFIX + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".$EXTENSION")

        JsonWriter(
            OutputStreamWriter(GZIPOutputStream(BufferedOutputStream(FileOutputStream(file))), Charsets.UTF_8)
        ).use { w ->
            w.beginObject()
            w.name("format").value(FORMAT)
            w.name("version").value(VERSION.toLong())
            w.name("createdMs").value(System.currentTimeMillis())
            w.name("appVersion").value(BuildConfig.VERSION_NAME)
            w.name("tunnelCount").value(tunnels.size.toLong())
            w.name("approachCount").value(approaches.size.toLong())
            w.name("source").value("OpenStreetMap contributors (ODbL)")
            w.name("tunnels")
            w.beginArray()
            for (t in tunnels) {
                w.beginObject()
                w.name("id").value(t.osmId)
                if (t.name != null) w.name("name").value(t.name)
                w.name("len").value(t.lengthM)
                w.name("bbox")
                w.beginArray()
                w.value(t.minLat); w.value(t.maxLat); w.value(t.minLon); w.value(t.maxLon)
                w.endArray()
                w.name("pts").value(t.pointsJson)
                w.endObject()
            }
            w.endArray()
            w.name("adjacent")
            w.beginArray()
            for (a in db.adjacentDao().getAll()) {
                w.beginObject()
                w.name("t").value(a.tunnelId)
                w.name("pts").value(a.pointsJson)
                w.endObject()
            }
            w.endArray()
            w.name("approaches")
            w.beginArray()
            for (a in approaches) {
                w.beginObject()
                w.name("t").value(a.tunnelId)
                w.name("e").value(a.endIdx.toLong())
                w.name("pts").value(a.pointsJson)
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        file
    }

    /** 선택한 .tac 파일을 읽어 검증한다(DB는 아직 건드리지 않는다). 형식이 맞지 않으면 사용자에게 보일 메시지로 예외를 던진다. */
    suspend fun read(context: Context, uri: Uri): Package = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없습니다.")
        raw.use {
            val input = BufferedInputStream(raw)
            input.mark(2)
            val b0 = input.read()
            val b1 = input.read()
            input.reset()
            if (b0 != 0x1f || b1 != 0x8b) throw IllegalArgumentException("터널 데이터 파일(.tac)이 아닙니다.")
            try {
                JsonReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8)).use { parse(it) }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("파일이 손상되었거나 읽을 수 없는 형식입니다.")
            }
        }
    }

    /** 검증을 마친 데이터를 DB에 적용한다. 기존 터널·접근도로를 모두 교체하며, 한 트랜잭션이라 실패하면 원래 데이터가 유지된다. */
    suspend fun apply(context: Context, pkg: Package) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            db.tunnelDao().clearAll()
            db.approachDao().clearAll()
            db.approachDoneDao().clearAll()
            db.adjacentDao().clearAll()
            for (c in pkg.tunnels.chunked(500)) db.tunnelDao().insertAll(c)
            for (c in pkg.approaches.chunked(500)) db.approachDao().insertAll(c)
            for (c in pkg.adjacent.chunked(500)) db.adjacentDao().insertAll(c)
            // 접근도로가 들어 있는 터널은 완료로 표시해 '접근도로 보완' 대상에서 뺀다.
            val withApproach = (pkg.approaches.map { it.tunnelId } + pkg.doneIds).distinct()
            for (c in withApproach.chunked(500)) db.approachDoneDao().insertAll(c.map { com.tunnelac.app.data.db.ApproachDoneEntity(it) })
        }
        val prefs = Prefs(context.applicationContext)
        prefs.setLastOverpassUpdateMs(pkg.createdMs)
        prefs.clearDoneBoxes()
        TunnelRefresher.refreshIdleState(context.applicationContext)
    }

    private fun parse(r: JsonReader): Package {
        var format: String? = null
        var created = 0L
        var appVersion = ""
        val tunnels = ArrayList<TunnelEntity>()
        val approaches = ArrayList<ApproachEntity>()
        val doneIds = ArrayList<Long>()
        val adjacent = ArrayList<AdjacentEntity>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> {
                    format = r.nextString()
                    if (format != FORMAT) throw IllegalArgumentException("터널 데이터 파일(.tac)이 아닙니다.")
                }
                "version" -> {
                    val v = r.nextInt()
                    if (v > VERSION) throw IllegalArgumentException("더 새로운 버전의 앱에서 만든 파일입니다. 앱을 업데이트한 뒤 다시 시도해 주세요.")
                }
                "createdMs" -> created = r.nextLong()
                "appVersion" -> appVersion = r.nextString()
                "tunnels" -> {
                    r.beginArray()
                    while (r.hasNext()) tunnels.add(readTunnel(r))
                    r.endArray()
                }
                "approaches" -> {
                    r.beginArray()
                    while (r.hasNext()) approaches.add(readApproach(r))
                    r.endArray()
                }
                "adjacent" -> {
                    r.beginArray()
                    while (r.hasNext()) adjacent.add(readAdjacent(r))
                    r.endArray()
                }
                "doneIds" -> {
                    r.beginArray()
                    while (r.hasNext()) doneIds.add(r.nextLong())
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (format != FORMAT) throw IllegalArgumentException("터널 데이터 파일(.tac)이 아닙니다.")
        if (tunnels.isEmpty()) throw IllegalArgumentException("파일에 터널 데이터가 없습니다.")
        return Package(created, appVersion, tunnels, approaches, doneIds, adjacent)
    }

    private fun readTunnel(r: JsonReader): TunnelEntity {
        var id = 0L; var name: String? = null; var len = 0.0; var pts = ""
        val bbox = DoubleArray(4); var bboxN = 0
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextLong()
                "name" -> name = r.nextString()
                "len" -> len = r.nextDouble()
                "bbox" -> {
                    r.beginArray()
                    while (r.hasNext()) { val v = r.nextDouble(); if (bboxN < 4) bbox[bboxN] = v; bboxN++ }
                    r.endArray()
                }
                "pts" -> pts = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (id == 0L || bboxN != 4 || !validPoints(pts)) throw IllegalArgumentException("파일의 터널 항목이 올바르지 않습니다.")
        return TunnelEntity(id, name, pts, len, bbox[0], bbox[1], bbox[2], bbox[3])
    }

    private fun readAdjacent(r: JsonReader): AdjacentEntity {
        var t = 0L; var pts = ""
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "t" -> t = r.nextLong()
                "pts" -> pts = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (t == 0L || !validPoints(pts)) throw IllegalArgumentException("파일의 인접도로 항목이 올바르지 않습니다.")
        return AdjacentEntity(tunnelId = t, pointsJson = pts)
    }

    private fun readApproach(r: JsonReader): ApproachEntity {
        var t = 0L; var e = -1; var pts = ""
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "t" -> t = r.nextLong()
                "e" -> e = r.nextInt()
                "pts" -> pts = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (t == 0L || (e != 0 && e != 1) || !validPoints(pts)) throw IllegalArgumentException("파일의 접근도로 항목이 올바르지 않습니다.")
        return ApproachEntity(tunnelId = t, endIdx = e, pointsJson = pts)
    }

    /** "lat,lon;lat,lon;..." 형식인지 처음·마지막 점만 가볍게 확인한다. */
    private fun validPoints(s: String): Boolean {
        val parts = s.split(";").filter { it.isNotBlank() }
        if (parts.size < 2) return false
        for (p in listOf(parts.first(), parts.last())) {
            val xy = p.split(",")
            if (xy.size != 2 || xy[0].toDoubleOrNull() == null || xy[1].toDoubleOrNull() == null) return false
        }
        return true
    }
}
