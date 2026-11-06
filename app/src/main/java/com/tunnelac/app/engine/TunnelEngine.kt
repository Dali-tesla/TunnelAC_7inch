package com.tunnelac.app.engine

import com.tunnelac.app.hvac.HvacController
import com.tunnelac.app.hvac.HvacState

/**
 * 터널 진입/진출을 판정하고 공조 컨트롤러를 호출하는 핵심 상태 머신.
 * Android 프레임워크에 의존하지 않으므로 순수 로직만으로 테스트할 수 있다.
 *
 * 보조 신호는 GPS 신호(정확도 저하/유실)만 사용한다: 정상 fix 가 들어오는 동안은 실측 위치로
 * 축 위 진행거리를 갱신하고, fix 가 저하/유실되면(터널 안에 있을 가능성) 마지막으로 알려진
 * 속도로 추정(dead reckoning)하여 진행거리를 이어간다.
 */
class TunnelEngine(
    private var config: EngineConfig,
    private val hvacController: HvacController,
    private val listener: EngineListener
) {
    private sealed class InternalState {
        object Idle : InternalState()
        data class InTunnel(
            val tunnel: TunnelSegment,
            val nearEnd: LatLon,
            val farEnd: LatLon,
            val enteredAtMs: Long,
            val preHvacState: HvacState,
            var axisDistM: Double,
            var lastGoodFixTimeMs: Long,
            var lastKnownSpeedMps: Float,
            var lastKnownBearingDeg: Float?,
            val visitedTunnelIds: MutableSet<Long>,
            var hasEnteredCorridor: Boolean,
            /** 상태 시작(진입/병합) 시점의 다음 터널 입구까지 거리. 접근 중 이보다 크게 멀어지면 엉뚱한 길로 빠진 것으로 본다. */
            val approachRefDistM: Double
        ) : InternalState()
    }

    private var state: InternalState = InternalState.Idle

    /** 서비스가 주기적으로 갱신해주는, 현재 위치 근방의 터널 후보 목록. */
    @Volatile
    var candidateTunnels: List<TunnelSegment> = emptyList()

    fun updateConfig(newConfig: EngineConfig) {
        config = newConfig
    }

    fun currentStateSnapshot(): EngineState = when (val s = state) {
        is InternalState.Idle -> EngineState.Idle
        is InternalState.InTunnel -> EngineState.InTunnel(s.tunnel.name, s.enteredAtMs)
    }

    /** 전송 실패한 공조 명령. 성공할 때까지 [RETRY_INTERVAL_MS] 간격으로 재시도한다(새 명령이 생기면 교체). */
    private var pendingMode: HvacState? = null
    private var lastAttemptMs: Long = 0L

    private suspend fun sendMode(mode: HvacState, nowMs: Long) {
        lastAttemptMs = nowMs
        pendingMode = if (hvacController.setMode(mode)) null else mode
    }

    /** 전송이 아직 실패 상태면 그 사유를 이벤트 로그용 문구로 만든다. */
    private fun failNote(): String? =
        if (pendingMode != null) "전송 실패: ${hvacController.lastError.ifBlank { "원인 불명" }} (재시도 중)" else null

    private var lastPrepareMs = 0L

    /** 터널 입구가 [PREPARE_DISTANCE_M] 이내로 다가오면 공조 전송 경로(릴레이 연결)를 미리 준비시킨다. */
    private fun maybePrepare(fix: VehicleFix) {
        if (!fix.hasFix || fix.speedKmh < config.minSpeedKmh) return
        if (fix.timeMs - lastPrepareMs < PREPARE_INTERVAL_MS) return
        val bearing = fix.bearingDeg?.toDouble() ?: return
        val pos = fix.toLatLon()
        val range = maxOf(PREPARE_DISTANCE_M, config.entryTriggerM + 200.0)
        for (tunnel in candidateTunnels) {
            if (tunnel.lengthM < config.minTunnelLengthM) continue
            for (end in tunnel.nearEndCandidates) {
                if (GeoMath.distanceMeters(pos, end) > range) continue
                if (GeoMath.angleDiffDeg(bearing, GeoMath.bearingDeg(pos, end)) > config.headingToleranceDeg) continue
                if (GeoMath.angleDiffDeg(bearing, tunnel.directionBearingFrom(end)) > config.headingToleranceDeg) continue
                lastPrepareMs = fix.timeMs
                hvacController.prepare()
                return
            }
        }
    }

    suspend fun onFix(fix: VehicleFix) {
        if (state is InternalState.Idle) maybePrepare(fix)
        val p = pendingMode
        if (p != null && fix.timeMs - lastAttemptMs >= RETRY_INTERVAL_MS) sendMode(p, fix.timeMs)
        when (val s = state) {
            is InternalState.Idle -> checkForEntry(fix)
            is InternalState.InTunnel -> handleInTunnel(fix, s)
        }
    }

    private suspend fun checkForEntry(fix: VehicleFix) {
        if (!fix.hasFix) return
        if (fix.speedKmh < config.minSpeedKmh) return
        val pos = fix.toLatLon()

        for (tunnel in candidateTunnels) {
            if (tunnel.lengthM < config.minTunnelLengthM) continue
            for (end in tunnel.nearEndCandidates) {
                val dist = GeoMath.distanceMeters(pos, end)
                if (dist > config.entryTriggerM) continue

                val bearingToEnd = GeoMath.bearingDeg(pos, end)
                val vehicleBearing = fix.bearingDeg?.toDouble()
                if (vehicleBearing == null) continue
                val headingDiff = GeoMath.angleDiffDeg(vehicleBearing, bearingToEnd)
                if (headingDiff > config.headingToleranceDeg) continue

                // 터널 진행 방향 검사: 입구 근처를 직각/크게 비스듬히 지나는 다른 도로는 진입으로 보지 않는다.
                if (GeoMath.angleDiffDeg(vehicleBearing, tunnel.directionBearingFrom(end)) > config.headingToleranceDeg) continue

                // 참고: 접근도로는 터널 폴리라인에 포함되지 않으므로(터널 구간만 매핑됨),
                // 여기서 도로 근접 허용치(corridor)를 검사하면 입구 바로 앞에서만 조건을
                // 만족하게 되어 entryTriggerM(예: 300m) 설정이 무의미해진다. 그래서 진입
                // 판정은 거리+방향만으로 하고, corridor 검사는 실제 터널 진입 후(아래
                // handleInTunnel)의 코스 이탈 감지에만 사용한다.

                // 조건 충족 -> 진입
                val farEnd = tunnel.otherEnd(end)
                val preHvac = hvacController.getCurrentState()
                // 전환 직전 차량 공조(내기/외기)를 릴레이에서 읽어 둔다(연결돼 있을 때만, 오래 기다리지 않음).
                val carBefore = hvacController.peekCarRecirc()
                sendMode(HvacState.RECIRCULATION, fix.timeMs) // 진입 시 항상 내기순환 (실패 시 재시도)
                val postHvac = hvacController.getCurrentState()

                state = InternalState.InTunnel(
                    tunnel = tunnel,
                    nearEnd = end,
                    farEnd = farEnd,
                    enteredAtMs = fix.timeMs,
                    preHvacState = preHvac,
                    axisDistM = 0.0,
                    lastGoodFixTimeMs = fix.timeMs,
                    lastKnownSpeedMps = fix.speedMps,
                    lastKnownBearingDeg = fix.bearingDeg,
                    visitedTunnelIds = mutableSetOf(tunnel.id),
                    hasEnteredCorridor = false,
                    approachRefDistM = dist
                )

                listener.onEvent(
                    EngineEvent(
                        type = EngineEventType.ENTRY,
                        timestampMs = fix.timeMs,
                        tunnelId = tunnel.id,
                        tunnelName = tunnel.name,
                        lat = fix.lat,
                        lon = fix.lon,
                        distanceM = dist,
                        speedKmh = fix.speedKmh,
                        gpsAccuracyM = fix.accuracyM,
                        hvacBefore = preHvac,
                        hvacAfter = postHvac,
                        hvacNote = failNote(),
                        carRecircBefore = when (carBefore) { true -> 1; false -> 0; null -> -1 }
                    )
                )
                listener.onStateChanged(currentStateSnapshot())
                return
            }
        }
    }

    private suspend fun handleInTunnel(fix: VehicleFix, s: InternalState.InTunnel) {
        val gpsGood = fix.hasFix && fix.accuracyM <= config.gpsLostAccuracyThresholdM

        if (gpsGood) {
            // 진입(ENTRY)은 이제 입구 300m 전 접근도로 위에서부터 발동될 수 있는데, 접근도로는
            // 터널 폴리라인에 포함되지 않아 이 시점엔 corridor 편차가 당연히 크다. 그래서 실제로
            // 한 번이라도 터널 도로(폴리라인) 근접 범위에 들어온 뒤부터만 코스 이탈 감지를
            // 적용한다 — 그 전에는 아직 접근 중인 것뿐이므로 감시하지 않는다.
            val corridorDeviationM = GeoMath.distancePointToPolylineMeters(fix.toLatLon(), s.tunnel.points)
            if (!s.hasEnteredCorridor && corridorDeviationM <= config.corridorWidthM) {
                s.hasEnteredCorridor = true
            }

            // 접근 중(아직 터널 도로에 못 들어섬)인데 입구에서 시작 거리보다 크게 멀어졌다면
            // 터널이 아닌 다른 길로 빠진 것이므로 안전하게 진출 처리한다(내기가 계속 유지되는 것 방지).
            if (!s.hasEnteredCorridor) {
                val awayM = GeoMath.distanceMeters(fix.toLatLon(), s.nearEnd)
                if (awayM > s.approachRefDistM + APPROACH_AWAY_MARGIN_M) {
                    finishTunnel(fix, s, EngineEventType.SAFETY_EXIT, awayM)
                    return
                }
            }

            // 입구를 충분히 지났는데도(진행 [ENTRY_CONFIRM_PROGRESS_M] 이상) 터널선에서 [ENTRY_CANCEL_DEVIATION_M]
            // 넘게 떨어져 있으면 터널 옆 도로를 잘못 따라온 것이므로 바로 취소(외기 복귀)한다.
            // 터널을 놓치지 않는 것이 우선이라 지도 오차를 넉넉히 감안해 크게 벗어난 경우에만 취소한다.
            if (!s.hasEnteredCorridor &&
                corridorDeviationM > ENTRY_CANCEL_DEVIATION_M &&
                progressAlongTunnelMeters(fix.toLatLon(), s) >= ENTRY_CONFIRM_PROGRESS_M
            ) {
                finishTunnel(fix, s, EngineEventType.SAFETY_EXIT, corridorDeviationM)
                return
            }

            // 축 투영(projectAlongAxisMeters)은 직선을 무한히 연장해서 계산하므로, 유턴 등으로
            // 차량이 그 도로에서 완전히 벗어나도 투영값만 보면 여전히 "터널 구간 안"으로 착시될 수
            // 있다. 실제 GPS가 정상일 때는 도로(터널 폴리라인)에서 실제로 얼마나 벗어났는지도 같이
            // 검사해서, 코스를 벗어난 게 명백하면 즉시 안전하게 진출 처리한다. 단, 접근 단계(아직
            // hasEnteredCorridor=false)에서는 이 검사를 적용하지 않는다.
            // 안전 처리는 항상 정상 진출(터널 끝 + 진출 트리거)보다 뒤에 오도록 기준을 정한다.
            val safetyDeviationM = maxOf(
                config.corridorWidthM + SAFETY_CORRIDOR_MARGIN_M,
                config.exitTriggerM + SAFETY_AFTER_EXIT_M
            )
            if (s.hasEnteredCorridor && corridorDeviationM > safetyDeviationM) {
                finishTunnel(fix, s, EngineEventType.SAFETY_EXIT, corridorDeviationM)
                return
            }

            s.axisDistM = progressAlongTunnelMeters(fix.toLatLon(), s)
            s.lastGoodFixTimeMs = fix.timeMs
            s.lastKnownSpeedMps = fix.speedMps
            if (fix.bearingDeg != null) s.lastKnownBearingDeg = fix.bearingDeg
        } else {
            val dtSec = ((fix.timeMs - s.lastGoodFixTimeMs).coerceAtLeast(0)) / 1000.0
            s.axisDistM = s.axisDistM + s.lastKnownSpeedMps * dtSec
            s.lastGoodFixTimeMs = fix.timeMs
        }

        val totalLenM = s.tunnel.lengthM // 곡선 실측 길이(폴리라인 누적거리). 직선거리가 아님.
        val exitThresholdM = totalLenM + config.exitTriggerM
        val elapsedMin = (fix.timeMs - s.enteredAtMs) / 60000.0
        val forcedByTimeout = elapsedMin >= config.maxHoldMinutes

        // 최대 유지시간 초과는 GPS 상태와 무관하게 항상 적용되는 최종 안전장치다
        // (GPS가 영영 안 돌아올 경우를 대비).
        if (forcedByTimeout) {
            finishTunnel(fix, s, EngineEventType.FORCE_EXIT, s.axisDistM - totalLenM)
            return
        }

        // GPS 정확도가 나빠 데드레커닝(추정치)만 있는 상태에서는 진출/병합을 확정하지 않는다.
        // 정체 등으로 진입 직전 속도보다 실제로 느려지면 추정 진행거리가 과대평가되어,
        // 아직 터널 안인데도 진출로 오판해 공조를 미리 외기로 되돌릴 수 있기 때문이다.
        // axisDistM 자체는 계속 갱신해 두되(내부 추적용), 실제 판정은 GPS가 다시 정상일 때만 한다.
        if (!gpsGood) return
        if (s.axisDistM < exitThresholdM) return // 아직 터널(+진출 여유거리) 안

        // 진출 지점 도달 -> 인접 터널로 이어지는지 확인 (연속 터널 병합).
        // 단, 병합이 비정상적으로 길게 이어지면(맞은편 차선 터널과의 핑퐁 등) 안전하게 진출 처리한다.
        val mergeCandidate = if (s.visitedTunnelIds.size <= MAX_MERGE_CHAIN) findMergeCandidate(s) else null
        if (mergeCandidate != null) {
            val (nextTunnel, nextNearEnd, nextFarEnd) = mergeCandidate
            state = InternalState.InTunnel(
                tunnel = nextTunnel,
                nearEnd = nextNearEnd,
                farEnd = nextFarEnd,
                enteredAtMs = s.enteredAtMs, // 최초 진입 시각 유지 (최대 유지시간 계산 기준)
                preHvacState = s.preHvacState, // 최초 진입 전 상태를 그대로 유지 (최종 복귀 대상)
                axisDistM = 0.0,
                lastGoodFixTimeMs = fix.timeMs,
                lastKnownSpeedMps = s.lastKnownSpeedMps,
                lastKnownBearingDeg = s.lastKnownBearingDeg,
                visitedTunnelIds = (s.visitedTunnelIds + nextTunnel.id).toMutableSet(),
                // 병합 직후 차량은 아직 터널 사이의 열린 도로에 있다(다음 터널선까지 최대 mergeGap 만큼 떨어짐).
                // 따라서 다음 터널 도로에 실제로 들어선 뒤부터 이탈 감시를 적용한다(진입 때와 같은 규칙).
                hasEnteredCorridor = false,
                approachRefDistM = GeoMath.distanceMeters(fix.toLatLon(), nextNearEnd)
            )
            listener.onEvent(
                EngineEvent(
                    type = EngineEventType.MERGE,
                    timestampMs = fix.timeMs,
                    tunnelId = nextTunnel.id,
                    tunnelName = nextTunnel.name,
                    lat = fix.lat,
                    lon = fix.lon,
                    distanceM = 0.0,
                    speedKmh = fix.speedKmh,
                    gpsAccuracyM = fix.accuracyM,
                    hvacBefore = HvacState.RECIRCULATION,
                    hvacAfter = HvacState.RECIRCULATION
                )
            )
            listener.onStateChanged(currentStateSnapshot())
            return
        }

        finishTunnel(fix, s, EngineEventType.EXIT, s.axisDistM - totalLenM)
    }

    private suspend fun finishTunnel(
        fix: VehicleFix,
        s: InternalState.InTunnel,
        type: EngineEventType,
        distancePastExitM: Double
    ) {
        val before = hvacController.getCurrentState()
        // 진출 시에는 설정된 모드(기본 AUTO, 사용자가 외기로 변경 가능)로 전환한다.
        // (이전에는 "진입 전 상태로 복귀"였으나, 요구사항에 따라 진출=오토/외기 고정으로 변경)
        sendMode(config.exitMode, fix.timeMs) // 실패 시 재시도
        // 로그에는 명령한 모드(외기/오토)를 기록한다. 전송에 실패하면 hvacNote 에 실패 사유가 함께 남는다.
        val after = config.exitMode

        listener.onEvent(
            EngineEvent(
                type = type,
                timestampMs = fix.timeMs,
                tunnelId = s.tunnel.id,
                tunnelName = s.tunnel.name,
                lat = fix.lat,
                lon = fix.lon,
                distanceM = distancePastExitM,
                speedKmh = fix.speedKmh,
                gpsAccuracyM = fix.accuracyM,
                hvacBefore = before,
                hvacAfter = after,
                hvacNote = failNote()
            )
        )
        state = InternalState.Idle
        listener.onStateChanged(currentStateSnapshot())
    }

    /**
     * s.nearEnd -> s.farEnd 방향으로 실제 도로(곡선 폴리라인)를 따라 p 까지 얼마나
     * 진행했는지(m)를 계산한다. s.tunnel.points 는 항상 OSM way 원래 순서(시작->끝)로
     * 저장되어 있으므로, 차량이 points.last() 쪽에서 진입했다면(= nearEnd 가 points.last())
     * 폴리라인 기준 누적거리를 뒤집어서(총길이 - 누적거리) 반환해야 한다.
     */
    private fun progressAlongTunnelMeters(p: LatLon, s: InternalState.InTunnel): Double {
        val pts = s.tunnel.points
        val totalLenM = s.tunnel.lengthM
        val forwardArcLen = GeoMath.projectAlongPolylineMeters(p, pts)
        val nearIsFirst = s.nearEnd == pts.first()
        val progress = if (nearIsFirst) forwardArcLen else totalLenM - forwardArcLen

        // 폴리라인 투영은 양 끝점에서 멈추므로(끝점 이후에도 값이 끝점 거리로 고정), 차량이 진출 끝점을
        // 넘어선 경우 "터널 길이 + 끝점까지의 직선거리"로 계산해야 진출 트리거 거리가 의미를 가진다.
        val farPoint = if (nearIsFirst) pts.last() else pts.first()
        val atFarEnd = if (nearIsFirst) forwardArcLen >= totalLenM - END_EPS_M else forwardArcLen <= END_EPS_M
        return if (atFarEnd) totalLenM + GeoMath.distanceMeters(p, farPoint) else progress
    }

    private data class MergeTarget(val tunnel: TunnelSegment, val nearEnd: LatLon, val farEnd: LatLon)

    /**
     * 실제로 이어지는 다음 터널만 병합 대상으로 인정한다.
     * 국내 OSM 도로 데이터는 왕복 차로가 별도 way로 매핑된 경우가 많아, 반대쪽 차선의
     * 터널(입구가 몇 m 옆에 나란히 붙어있는 경우가 흔함)이 후보로 잡히면서 끝없이
     * 병합->병합이 반복되어 진출 판정이 영영 안 나는 문제가 있었다. 이를 막기 위해
     * (1) 이미 지나온 터널은 다시 후보로 잡지 않고, (2) 거의 같은 지점(중복 포털/반대
     * 차선)은 최소 거리 미만이면 제외하며, (3) 실제 주행 방향과 이어지는 방향인지
     * 진행방향 허용 각도로 검증한다.
     */
    private fun findMergeCandidate(s: InternalState.InTunnel): MergeTarget? {
        val travelBearing = s.lastKnownBearingDeg?.toDouble() ?: return null
        for (tunnel in candidateTunnels) {
            if (tunnel.id in s.visitedTunnelIds) continue
            for (end in tunnel.nearEndCandidates) {
                val gapM = GeoMath.distanceMeters(s.farEnd, end)
                if (gapM > config.mergeGapM) continue
                if (gapM < MIN_MERGE_GAP_M) continue

                val bearingToCandidate = GeoMath.bearingDeg(s.farEnd, end)
                if (GeoMath.angleDiffDeg(travelBearing, bearingToCandidate) > config.headingToleranceDeg) continue

                return MergeTarget(tunnel, end, tunnel.otherEnd(end))
            }
        }
        return null
    }

    companion object {
        /** 반대 차선 등 사실상 같은 지점(중복 포털)을 별개 터널로 오인하지 않기 위한 최소 간격. */
        private const val MIN_MERGE_GAP_M = 5.0
        /** 투영 결과가 끝점에 닿았다고 보는 허용 오차(m). */
        private const val END_EPS_M = 3.0
        /** 병합이 이 횟수를 넘어가면(비정상 데이터로 인한 핑퐁 등) 더 이상 병합하지 않고 진출 처리한다. */
        private const val MAX_MERGE_CHAIN = 8
        /** 명령 전송 실패 시 재시도 간격. */
        private const val RETRY_INTERVAL_MS = 4000L
        /** 터널 입구까지 이 거리 이내로 접근하면 릴레이 연결을 미리 확인한다. */
        private const val PREPARE_DISTANCE_M = 500.0
        private const val PREPARE_INTERVAL_MS = 3000L
        /** 도로 근접 허용치에 더해, 이 거리 이상 벗어나면 명백히 코스 이탈로 보고 안전 진출 처리한다. */
        private const val SAFETY_CORRIDOR_MARGIN_M = 150.0
        /** 안전 처리 기준은 진출 트리거보다 최소 이만큼 더 멀리. */
        private const val SAFETY_AFTER_EXIT_M = 100.0
        /** 접근 중 입구에서 시작 거리보다 이만큼 더 멀어지면 다른 길로 빠진 것으로 본다. */
        private const val APPROACH_AWAY_MARGIN_M = 100.0
        /** 입구를 이만큼(m) 지났는데도 터널선에서 [ENTRY_CANCEL_DEVIATION_M] 넘게 떨어져 있으면 진입을 취소한다. */
        private const val ENTRY_CONFIRM_PROGRESS_M = 50.0
        private const val ENTRY_CANCEL_DEVIATION_M = 90.0
    }
}
