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
    /**
     * 주변 후보(이전 역~다다음 역) 밖으로 크게 건너뛰는 보정은 이 정확도 이하의 위치로만.
     * 출발역을 잘못 골랐을 때 바로잡는 용도. 실측에서 1,700~2,200m 짜리 위치로 건너뛰다 3정거장 틀림.
     */
    val catchUpMaxAccuracyM: Double = 60.0,
    /** 직전에 믿은 위치에서 이보다 빠르게 이동한 것으로 나오는 위치는 버림 (신분당선 최고 약 25m/s) */
    val maxTrainSpeedMps: Double = 35.0,
    /**
     * 똑같은 좌표가 [stickyMinCount] 번 이상, [stickyMinSpanMs] 넘게 걸쳐 반복되면 고정된 엉터리 위치
     * (다른 곳에 등록된 와이파이, 캐시)로 보고 그 좌표를 계속 무시.
     * 실측: 열차가 정자·동천에 있을 때도 같은 좌표가 20번 넘게 들어왔고, 한 번은 정확도 193m 라고 주장.
     */
    val stickyMinCount: Int = 3,
    val stickyMinSpanMs: Long = 120_000,
)

/** 엉터리 위치를 걸러내는 필터 (반복 좌표, 낮은 정확도, 불가능한 이동 속도) */
internal class LocationFilter {
    private val seen = HashMap<Pair<Long, Long>, MutableList<Long>>()
    private val blocked = HashSet<Pair<Long, Long>>()
    private var lastTrusted: LocationFix? = null

    fun accept(fix: LocationFix, config: LocationConfig): Boolean {
        // 1. 반복 좌표 (정확도와 무관하게 모든 위치로 집계)
        val key = (fix.latitude * 10_000).toLong() to (fix.longitude * 10_000).toLong()
        if (key in blocked) return false
        val times = seen.getOrPut(key) { mutableListOf() }
        times += fix.timestampMs
        if (times.size >= config.stickyMinCount && times.last() - times.first() >= config.stickyMinSpanMs) {
            blocked += key
            return false
        }
        // 2. 정확도
        if (fix.accuracyM > config.maxAccuracyM) return false
        // 3. 이동 속도: 더 정확한 위치가 덜 정확한 위치를 이김
        val last = lastTrusted
        if (last != null) {
            val dt = (fix.timestampMs - last.timestampMs) / 1000.0
            val d = StationMatcher.distanceM(last.latitude, last.longitude, fix.latitude, fix.longitude) -
                fix.accuracyM - last.accuracyM
            if (dt in 0.0..600.0 && d > config.maxTrainSpeedMps * dt && fix.accuracyM >= last.accuracyM) return false
        }
        lastTrusted = fix
        return true
    }
}

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

    /** 노선 전체에서 [fix] 와 확실히 맞는 역 (크게 건너뛰는 보정용, 정확한 위치에만 사용) */
    fun matchAnywhere(line: SubwayLine, fix: LocationFix, config: LocationConfig): Int? {
        val ranked = line.stations.indices
            .filter { line.stations[it].hasCoordinates }
            .map { it to distanceM(fix.latitude, fix.longitude, line.stations[it].latitude!!, line.stations[it].longitude!!) }
            .sortedBy { it.second }
        val (best, d1) = ranked.firstOrNull() ?: return null
        if (d1 > config.matchRadiusM + fix.accuracyM) return null
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
