package com.tunnelac.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
        loadAndDrawTunnels()
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
            tvInfo.text = if (segments.isEmpty()) {
                "저장된 터널 데이터가 없습니다. 설정 탭에서 '터널 데이터 갱신'을 눌러주세요."
            } else {
                "지도에 표시된 터널: ${segments.size}개"
            }

        }
    }

    private fun drawTunnels(segments: List<TunnelSegment>) {
        val map = mapView ?: return
        val lines = ArrayList<Polyline>(segments.size)
        for (segment in segments) {
            if (segment.points.size < 2) continue
            val line = Polyline(map)
            line.setPoints(segment.points.map { GeoPoint(it.lat, it.lon) })
            line.outlinePaint.color = TUNNEL_LINE_COLOR
            line.outlinePaint.strokeWidth = TUNNEL_LINE_WIDTH_PX
            lines.add(line)
        }
        map.overlays.addAll(lines)
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
        myLocation?.disableMyLocation()
        myLocation = null
        mapView?.onDetach()
        mapView = null
        super.onDestroyView()
    }

    companion object {
        private const val TUNNEL_LINE_WIDTH_PX = 8f
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
