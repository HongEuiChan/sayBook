package com.saybook.subway.track

import com.saybook.subway.model.SubwayLine
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 플랫폼이 받은 위치 한 건.
 * [timestampMs] 는 위치 값끼리만 비교하므로 센서 시계와 달라도 됩니다 (단조 증가하면 됨).
 */
data class LocationFix(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    /** 수평 정확도 반경 (m) */
    val accuracyM: Double,
)

data class LocationConfig(
    /** 이보다 오래 위치가 끊겼다가 다시 잡히면 '역사에 들어왔다' 로 봄 (지하 터널에서는 위치가 안 잡힘) */
    val reappearGapMs: Long = 45_000,
    /**
     * 이보다 부정확한 위치는 아예 없는 것으로 봄.
     * 지하에서도 기지국 기반 위치(정확도 수백 m~수 km)는 계속 들어올 수 있으므로,
     * 역사 와이파이/GPS 중계기 수준의 위치만 '잡혔다' 로 칩니다.
     */
    val maxAccuracyM: Double = 200.0,
    /** 좌표 매칭: 역 중심에서 이 거리 + min(정확도, [matchAccuracyAllowanceM]) 안이어야 함 */
    val matchRadiusM: Double = 250.0,
    val matchAccuracyAllowanceM: Double = 250.0,
    /** 좌표 매칭: 두 번째로 가까운 후보 역보다 이만큼은 더 가까워야 함 (모호하면 무시) */
    val matchMarginM: Double = 150.0,
)

internal object StationMatcher {

    /**
     * 현재 위치 주변 후보 역 (이전 역 ~ 다다음 역) 중에서 [fix] 와 확실히 맞는 역의 인덱스.
     * 엉뚱한 위치로 크게 점프하지 않도록 후보 범위를 좁게 잡습니다.
     */
    fun match(state: TrackerState, fix: LocationFix, config: LocationConfig): Int? {
        val line = state.line
        val candidates = candidateIndices(line, state)
            .filter { line.stations[it].hasCoordinates }
        if (candidates.isEmpty()) return null

        val ranked = candidates
            .map { it to distanceM(fix.latitude, fix.longitude, line.stations[it].latitude!!, line.stations[it].longitude!!) }
            .sortedBy { it.second }
        val (best, d1) = ranked.first()
        val limit = config.matchRadiusM + fix.accuracyM.coerceAtMost(config.matchAccuracyAllowanceM)
        if (d1 > limit) return null
        val d2 = ranked.getOrNull(1)?.second
        if (d2 != null && d2 - d1 < config.matchMarginM) return null
        return best
    }

    private fun candidateIndices(line: SubwayLine, s: TrackerState): List<Int> {
        val prev = line.previousIndex(s.currentIndex, s.direction)
        val next = line.nextIndex(s.currentIndex, s.direction)
        val nextNext = next?.let { line.nextIndex(it, s.direction) }
        return listOfNotNull(prev, s.currentIndex, next, nextNext).distinct()
    }

    /** 하버사인 거리 (m) */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
