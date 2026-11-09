package com.tunnelac.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tunnelac.app.data.Prefs
import com.tunnelac.app.data.TunnelRefresher
import com.tunnelac.app.data.overpass.BoundingBox
import com.tunnelac.app.data.overpass.OverpassRegions
import com.tunnelac.app.data.overpass.key
import org.osmdroid.views.overlay.Overlay
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tunnelac.app.R
import com.tunnelac.app.data.TunnelRepository
import com.tunnelac.app.engine.TunnelSegment
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Overpass 로 받아온 터널 위치를 OpenStreetMap(osmdroid) 지도 위에 경로선(Polyline)으로 그려서 눈으로 확인하는 화면.
 */
class MapFragment : Fragment(R.layout.fragment_map) {

    private lateinit var repository: TunnelRepository
    private var mapView: MapView? = null
    private lateinit var tvInfo: TextView
    private var myLocation: MyLocationNewOverlay? = null
    private var hasLocationPermission = false
    private val approachLines = ArrayList<Polyline>()
    private val adjacentLines = ArrayList<Polyline>()
    private var approachJob: Job? = null
    private val tunnelLines = ArrayList<Polyline>()
    private val zoneBoxes: List<BoundingBox> = OverpassRegions.koreaGrid()
    private var zoneOverlay: ZoneOverlay? = null
    private var selectedZone = -1
    private var zoneCounts = IntArray(zoneBoxes.size)
    private lateinit var zonePanel: LinearLayout
    private lateinit var tvZoneInfo: TextView
    private lateinit var btnZoneUpdate: Button
    private lateinit var btnZoneSupplement: Button
    private var zoneMessage: String? = null
    private var selectedTunnelId = -1L
    /** 0=없음, 1=구역 패널, 2=터널 패널 */
    private var activePanel = 0
    private lateinit var tunnelPanel: LinearLayout
    private lateinit var tvTunnelInfo: TextView
    private lateinit var btnTunnelUpdate: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvInfo = view.findViewById(R.id.tv_map_info)
        repository = TunnelRepository(requireContext().applicationContext)

        val map = view.findViewById<MapView>(R.id.map_view)
        mapView = map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // OSM 출처 표시(정책상 필수).
        map.overlays.add(CopyrightOverlay(requireContext()))
        map.controller.setZoom(DEFAULT_ZOOM)
        map.controller.setCenter(KOREA_CENTER)

        setupMyLocation(map, view)
        setupZones(map, view)
        loadAndDrawTunnels()
        // 지도를 움직이거나 확대/축소하면 보이는 영역의 접근도로(주황색)를 다시 그린다.
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { scheduleApproachRedraw(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { scheduleApproachRedraw(); return false }
        })
    }

    /** 폰 GPS 현위치를 파란 마커로 표시하고, 지도를 현위치 중심으로 맞춘다(손으로 움직이면 따라가기가 멈추고 '내 위치' 버튼으로 복귀). */
    private fun setupMyLocation(map: MapView, view: View) {
        hasLocationPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val btn = view.findViewById<Button>(R.id.btn_my_location)
        if (!hasLocationPermission) {
            btn.isEnabled = false
            tvInfo.text = "위치 권한이 없어 현위치를 표시할 수 없습니다."
            return
        }
        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), map)
        overlay.isDrawAccuracyEnabled = true
        // 내 위치 표시를 빨간색으로: 이동 중에는 진행 방향 화살표, 정지 시에는 빨간 점
        overlay.setDirectionArrow(buildRedDot(), buildRedArrow())
        overlay.setPersonAnchor(0.5f, 0.5f)
        overlay.setDirectionAnchor(0.5f, 0.5f)
        overlay.enableFollowLocation()
        // 첫 위치를 받으면 현위치 중심으로 확대
        overlay.runOnFirstFix {
            map.post {
                overlay.myLocation?.let {
                    map.controller.setZoom(MY_LOCATION_ZOOM)
                    map.controller.setCenter(it)
                }
            }
        }
        map.overlays.add(overlay)
        myLocation = overlay
        btn.setOnClickListener {
            overlay.enableFollowLocation()
            overlay.myLocation?.let { map.controller.animateTo(it) }
        }
    }

    private fun loadAndDrawTunnels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val segments = repository.getAllSegments()
            drawTunnels(segments)
            updateZoneCounts(segments)
            tvInfo.text = if (segments.isEmpty()) {
                "저장된 터널 데이터가 없습니다. 설정 탭에서 '터널 데이터 갱신'을 눌러주세요."
            } else {
                "지도에 표시된 터널: ${segments.size}개  (파랑=터널, 주황=접근도로, 보라=인접도로·확대 시 표시)"
            }
            scheduleApproachRedraw()

        }
    }

    private fun drawTunnels(segments: List<TunnelSegment>) {
        val map = mapView ?: return
        map.overlays.removeAll(tunnelLines.toSet())
        tunnelLines.clear()
        val lines = ArrayList<Polyline>(segments.size)
        for (segment in segments) {
            if (segment.points.size < 2) continue
            val line = Polyline(map)
            line.setPoints(segment.points.map { GeoPoint(it.lat, it.lon) })
            line.outlinePaint.color = TUNNEL_LINE_COLOR
            line.outlinePaint.strokeWidth = TUNNEL_LINE_WIDTH_DP * resources.displayMetrics.density
            // 터널 선을 탭하면 터널 패널(이 터널만 갱신)을 연다.
            line.setOnClickListener { _, _, _ -> onTunnelTapped(segment); true }
            lines.add(line)
        }
        map.overlays.addAll(lines)
        tunnelLines.addAll(lines)
        map.invalidate()
    }

    /** 구역별 터널 수를 센다(터널 첫 점이 속한 구역 기준). */
    private fun updateZoneCounts(segments: List<TunnelSegment>) {
        val counts = IntArray(zoneBoxes.size)
        for (s in segments) {
            val pt = s.points.firstOrNull() ?: continue
            val i = zoneIndexOf(pt.lat, pt.lon)
            if (i >= 0) counts[i]++
        }
        zoneCounts = counts
        refreshZoneOverlay()
        showSelectedZoneInfo()
    }

    private fun zoneIndexOf(lat: Double, lon: Double): Int =
        zoneBoxes.indexOfFirst { lat >= it.minLat && lat < it.maxLat && lon >= it.minLon && lon < it.maxLon }

    private fun onTunnelTapped(segment: TunnelSegment) {
        selectedTunnelId = segment.id
        activePanel = 2
        zonePanel.visibility = View.GONE
        tunnelPanel.visibility = View.VISIBLE
        val title = segment.name ?: "OSM ${segment.id}"
        tvTunnelInfo.text = "$title · ${segment.lengthM.toInt()}m"
        viewLifecycleOwner.lifecycleScope.launch {
            val n = repository.approachEntranceCount(segment.id)
            if (selectedTunnelId == segment.id && activePanel == 2 && !TunnelRefresher.isRunning) {
                tvTunnelInfo.text = "$title · ${segment.lengthM.toInt()}m · 접근도로 있는 입구 ${n}곳"
            }
        }
    }

    private fun refreshZoneOverlay() {
        val doneKeys = try { Prefs(requireContext()).getDoneBoxes() } catch (e: Exception) { emptySet() }
        zoneOverlay?.let { ov ->
            ov.green = zoneBoxes.indices.filter { zoneCounts[it] > 0 || zoneBoxes[it].key() in doneKeys }.toSet()
            ov.selected = selectedZone
        }
        mapView?.invalidate()
    }

    private fun showSelectedZoneInfo() {
        if (selectedZone < 0) return
        val msg = zoneMessage
        tvZoneInfo.text = msg ?: "구역 ${selectedZone + 1} · 터널 ${zoneCounts[selectedZone]}개"
    }

    /** 42개 구역 격자 표시, 구역 선택, 선택한 구역만 갱신. */
    private fun setupZones(map: MapView, view: View) {
        tunnelPanel = view.findViewById(R.id.tunnel_panel)
        tvTunnelInfo = view.findViewById(R.id.tv_tunnel_info)
        btnTunnelUpdate = view.findViewById(R.id.btn_tunnel_update)
        btnTunnelUpdate.setOnClickListener {
            if (selectedTunnelId < 0) return@setOnClickListener
            if (TunnelRefresher.isRunning) {
                Toast.makeText(requireContext(), "다른 받기가 진행 중입니다. 끝난 뒤 다시 눌러 주세요.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            TunnelRefresher.startTunnel(requireContext(), selectedTunnelId)
        }
        zonePanel = view.findViewById(R.id.zone_panel)
        tvZoneInfo = view.findViewById(R.id.tv_zone_info)
        btnZoneUpdate = view.findViewById(R.id.btn_zone_update)
        btnZoneSupplement = view.findViewById(R.id.btn_zone_supplement)
        val btnToggle = view.findViewById<Button>(R.id.btn_zone_toggle)
        val overlay = ZoneOverlay()
        overlay.isEnabled = false
        map.overlays.add(0, overlay)
        zoneOverlay = overlay
        btnToggle.setOnClickListener {
            overlay.isEnabled = !overlay.isEnabled
            btnToggle.text = if (overlay.isEnabled) "구역 숨기기" else "구역 보기"
            if (!overlay.isEnabled) {
                zonePanel.visibility = View.GONE
                if (activePanel == 1) activePanel = 0
            } else {
                refreshZoneOverlay()
                if (selectedZone >= 0 && activePanel != 2) { zonePanel.visibility = View.VISIBLE; activePanel = 1 }
            }
            map.invalidate()
        }
        btnZoneUpdate.setOnClickListener {
            if (selectedZone < 0) return@setOnClickListener
            if (TunnelRefresher.isRunning) {
                Toast.makeText(requireContext(), "다른 받기가 진행 중입니다. 끝난 뒤 다시 눌러 주세요.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            zoneMessage = null
            TunnelRefresher.startZone(requireContext(), zoneBoxes[selectedZone], selectedZone + 1)
        }
        btnZoneSupplement.setOnClickListener {
            if (selectedZone < 0) return@setOnClickListener
            if (TunnelRefresher.isRunning) {
                Toast.makeText(requireContext(), "다른 받기가 진행 중입니다. 끝난 뒤 다시 눌러 주세요.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            zoneMessage = null
            TunnelRefresher.startZoneSupplement(requireContext(), zoneBoxes[selectedZone], selectedZone + 1)
        }
        // 구역 갱신 진행/결과를 패널에 보여 주고, 끝나면 지도를 다시 그린다.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasRunning = TunnelRefresher.isRunning
                TunnelRefresher.state.collect { st ->
                    btnZoneUpdate.isEnabled = !st.running
                    btnZoneSupplement.isEnabled = !st.running
                    btnTunnelUpdate.isEnabled = !st.running
                    if (st.running || wasRunning) {
                        if (st.message.isNotEmpty()) {
                            if (activePanel == 2) {
                                tvTunnelInfo.text = st.message
                            } else if (activePanel == 1 && selectedZone >= 0) {
                                zoneMessage = st.message
                                tvZoneInfo.text = st.message
                            }
                        }
                    }
                    if (wasRunning && !st.running) loadAndDrawTunnels()
                    wasRunning = st.running
                }
            }
        }
    }

    private fun onZoneTapped(idx: Int) {
        selectedZone = idx
        zoneMessage = null
        activePanel = 1
        tunnelPanel.visibility = View.GONE
        zonePanel.visibility = View.VISIBLE
        showSelectedZoneInfo()
        refreshZoneOverlay()
    }

    /** 구역 격자(점선)·번호·받은 구역 색칠을 그리고, 칸을 탭하면 선택한다. */
    private inner class ZoneOverlay : Overlay() {
        var green: Set<Int> = emptySet()
        var selected = -1
        private val d = resources.displayMetrics.density
        private val fillGreen = Paint().apply { color = Color.parseColor("#3300C853"); style = Paint.Style.FILL }
        private val fillGray = Paint().apply { color = Color.parseColor("#22888888"); style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CCB0BEC5"); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
        }
        private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFC107"); style = Paint.Style.STROKE; strokeWidth = 3.5f * d
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 18f * d; textAlign = Paint.Align.CENTER
            setShadowLayer(4f * d, 0f, 0f, Color.BLACK)
        }
        private val p1 = Point()
        private val p2 = Point()

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val pj = mapView.projection
            for ((i, b) in zoneBoxes.withIndex()) {
                pj.toPixels(GeoPoint(b.maxLat, b.minLon), p1)
                pj.toPixels(GeoPoint(b.minLat, b.maxLon), p2)
                val l = p1.x.toFloat(); val t = p1.y.toFloat(); val r = p2.x.toFloat(); val bt = p2.y.toFloat()
                c.drawRect(l, t, r, bt, if (i in green) fillGreen else fillGray)
                c.drawRect(l, t, r, bt, line)
                if (i == selected) c.drawRect(l, t, r, bt, sel)
                if (r - l > 40f * d) c.drawText("${i + 1}", (l + r) / 2f, (t + bt) / 2f + text.textSize / 3f, text)
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
            val g = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
            val i = zoneIndexOf(g.latitude, g.longitude)
            if (i < 0) return false
            onZoneTapped(i)
            return true
        }
    }

    /** 짧은 지연(연속 이동 중 반복 조회 방지) 뒤 현재 화면의 접근도로를 다시 그린다. */
    private fun scheduleApproachRedraw() {
        approachJob?.cancel()
        approachJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(APPROACH_REDRAW_DELAY_MS)
            val map = mapView ?: return@launch
            if (map.zoomLevelDouble < APPROACH_MIN_ZOOM) {
                clearApproachLines(map)
                return@launch
            }
            val bb = try { map.boundingBox } catch (e: Exception) { return@launch }
            val lines = repository.getApproachLinesInBoundingBox(bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast)
            val adjLines = repository.getAdjacentLinesInBoundingBox(bb.latSouth, bb.latNorth, bb.lonWest, bb.lonEast)
            if (mapView == null) return@launch
            clearApproachLines(map)
            val drawn = ArrayList<Polyline>(minOf(lines.size, APPROACH_MAX_LINES))
            for (pts in lines) {
                if (drawn.size >= APPROACH_MAX_LINES) break
                if (pts.size < 2) continue
                val line = Polyline(map)
                line.setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                line.outlinePaint.color = APPROACH_LINE_COLOR
                line.outlinePaint.strokeWidth = APPROACH_LINE_WIDTH_DP * resources.displayMetrics.density
                drawn.add(line)
            }
            val adjDrawn = ArrayList<Polyline>(minOf(adjLines.size, ADJACENT_MAX_LINES))
            for (pts in adjLines) {
                if (adjDrawn.size >= ADJACENT_MAX_LINES) break
                if (pts.size < 2) continue
                val line = Polyline(map)
                line.setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                line.outlinePaint.color = ADJACENT_LINE_COLOR
                line.outlinePaint.strokeWidth = ADJACENT_LINE_WIDTH_DP * resources.displayMetrics.density
                adjDrawn.add(line)
            }
            // 터널(파랑)과 내 위치 표시 아래에 깔리도록 맨 아래에 넣는다(접근도로 아래에 인접도로).
            map.overlays.addAll(0, drawn)
            approachLines.addAll(drawn)
            map.overlays.addAll(0, adjDrawn)
            adjacentLines.addAll(adjDrawn)
            map.invalidate()
        }
    }

    private fun clearApproachLines(map: MapView) {
        if (approachLines.isEmpty() && adjacentLines.isEmpty()) return
        map.overlays.removeAll(approachLines.toSet())
        map.overlays.removeAll(adjacentLines.toSet())
        approachLines.clear()
        adjacentLines.clear()
        map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        if (hasLocationPermission) myLocation?.enableMyLocation()
    }

    override fun onPause() {
        myLocation?.disableMyLocation()
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        approachJob?.cancel()
        approachJob = null
        approachLines.clear()
        adjacentLines.clear()
        tunnelLines.clear()
        zoneOverlay = null
        myLocation?.disableMyLocation()
        myLocation = null
        mapView?.onDetach()
        mapView = null
        super.onDestroyView()
    }

    companion object {
        private const val TUNNEL_LINE_WIDTH_DP = 8f
        private const val APPROACH_LINE_WIDTH_DP = 6f
        private const val ADJACENT_LINE_WIDTH_DP = 4f
        private val ADJACENT_LINE_COLOR = Color.parseColor("#BA68C8") // 인접도로: 보라색
        private const val ADJACENT_MAX_LINES = 1500
        private val APPROACH_LINE_COLOR = Color.parseColor("#FF9800") // 접근도로: 주황색
        /** 이 확대 수준 이상에서만 접근도로를 그린다(전국 보기에서는 너무 많아 생략). */
        private const val APPROACH_MIN_ZOOM = 14.0
        private const val APPROACH_MAX_LINES = 1500
        private const val APPROACH_REDRAW_DELAY_MS = 300L
        private val TUNNEL_LINE_COLOR = Color.parseColor("#4FC3F7") // colors.xml accent 와 동일
        private val KOREA_CENTER = GeoPoint(36.5, 127.8)
        private const val DEFAULT_ZOOM = 7.0
        private const val MY_LOCATION_ZOOM = 15.0
    }

    /** 위쪽(북)을 향한 빨간 화살표(흰 테두리). 이동 방향에 맞춰 회전되어 그려진다. */
    private fun buildRedArrow(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply {
            moveTo(size * 0.5f, size * 0.08f)
            lineTo(size * 0.88f, size * 0.90f)
            lineTo(size * 0.5f, size * 0.70f)
            lineTo(size * 0.12f, size * 0.90f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 68, 68); style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
        return bmp
    }

    /** 정지 상태용 빨간 점(흰 테두리). */
    private fun buildRedDot(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size * 0.32f
        c.drawCircle(size / 2f, size / 2f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 68, 68) })
        c.drawCircle(size / 2f, size / 2f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f
        })
        return bmp
    }
}
