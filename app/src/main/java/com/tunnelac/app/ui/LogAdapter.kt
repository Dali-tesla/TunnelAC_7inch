package com.tunnelac.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tunnelac.app.R
import com.tunnelac.app.data.db.EventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var items: List<EventEntity> = emptyList()
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())

    fun submitList(newItems: List<EventEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], dateFormat)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle = itemView.findViewById<TextView>(R.id.tv_event_title)
        private val tvDetail = itemView.findViewById<TextView>(R.id.tv_event_detail)
        private val tvHvac = itemView.findViewById<TextView>(R.id.tv_event_hvac)

        fun bind(event: EventEntity, dateFormat: SimpleDateFormat) {
            val typeLabel = when (event.type) {
                "ENTRY" -> "터널 진입"
                "EXIT" -> "터널 진출"
                "FORCE_EXIT" -> "터널 진출 (최대유지시간 초과 강제)"
                "SAFETY_EXIT" -> "터널 진출 (경로 이탈 감지, 안전 처리)"
                "MERGE" -> "연속 터널 병합"
                "RELAY_CHECK" -> "릴레이 연결 확인"
                "SERVICE_STOP" -> "서비스 정지"
                else -> event.type
            }
            if (event.type == "RELAY_CHECK" || event.type == "SERVICE_STOP") {
                tvTitle.text = typeLabel
                tvDetail.text = dateFormat.format(Date(event.timestampMs))
                tvHvac.text = event.hvacNote.orEmpty()
                return
            }
            tvTitle.text = "$typeLabel - ${event.tunnelName ?: "이름없음"}"
            tvDetail.text = "%s | 위치 %.5f, %.5f | 거리 %.0fm | 속도 %.0fkm/h | GPS정확도 %s".format(
                dateFormat.format(Date(event.timestampMs)),
                event.lat,
                event.lon,
                event.distanceM,
                event.speedKmh,
                event.gpsAccuracyM?.let { "%.0fm".format(it) } ?: "N/A"
            )
            val note = event.hvacNote
            tvHvac.text = if (event.type == "ENTRY" && (note?.startsWith("공조:") == true || note?.startsWith("차량 공조") == true)) {
                note.replaceFirst("차량 공조", "공조") // 릴레이에서 읽은 차량 실제 공조(내기/외기)로 대체
            } else {
                "공조: ${hvacLabel(event.hvacBefore)} → ${hvacLabel(event.hvacAfter)}" + (note?.let { "\n$it" } ?: "")
            }
        }
    }
}

/** 공조 상태 표기: 내기순환 = 내기, 외기 = 외기, 오토 = 오토(내기/외기), 값 없음 = 확인 불가. */
private fun hvacLabel(name: String): String = when (name) {
    "RECIRCULATION" -> "내기"
    "FRESH_AIR" -> "외기"
    "AUTO" -> "오토(내기/외기)"
    else -> "확인 불가"
}
