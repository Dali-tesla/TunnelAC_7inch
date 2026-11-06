package com.tunnelac.app.export

import android.content.Context
import com.tunnelac.app.data.db.EventEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 이벤트 로그를 CSV 파일로 저장한다. FileProvider 의 exports/ 경로와 짝을 이룬다. */
object CsvExporter {

    fun export(context: Context, events: List<EventEntity>): File {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val fileName = "tunnelac_events_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".csv"
        val file = File(dir, fileName)

        file.bufferedWriter().use { writer ->
            writer.write("type,timestamp,tunnel_id,tunnel_name,lat,lon,distance_m,speed_kmh,gps_accuracy_m,hvac_before,hvac_after,hvac_note")
            writer.newLine()
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            for (e in events) {
                writer.write(
                    listOf(
                        e.type,
                        timeFormat.format(Date(e.timestampMs)),
                        e.tunnelId?.toString() ?: "",
                        (e.tunnelName ?: "").replace(",", " "),
                        e.lat.toString(),
                        e.lon.toString(),
                        e.distanceM.toString(),
                        e.speedKmh.toString(),
                        e.gpsAccuracyM?.toString() ?: "",
                        e.hvacBefore,
                        e.hvacAfter,
                        (e.hvacNote ?: "").replace(",", " ")
                    ).joinToString(",")
                )
                writer.newLine()
            }
        }
        return file
    }
}
