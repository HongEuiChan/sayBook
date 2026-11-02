package com.saybook.subway.track

import com.saybook.subway.detect.DetectorConfig
import com.saybook.subway.detect.DetectorEvent
import com.saybook.subway.detect.MotionFeatures
import com.saybook.subway.detect.MotionSample
import com.saybook.subway.detect.TrainMotionDetector
import com.saybook.subway.model.Direction
import com.saybook.subway.model.Station
import com.saybook.subway.model.SubwayLine

enum class TripPhase {
    /** 역에 정차 중 */
    AT_STATION,

    /** 다음 역을 향해 주행 중 */
    RUNNING,

    /** 역 사이에서 멈춤 (신호대기 등) */
    HALTED_BETWEEN,

    /** 종착역 도착 */
    FINISHED,
}

data class TrackerState(
    val line: SubwayLine,
    val direction: Direction,
    /** 마지막으로 정차했던(또는 지금 정차 중인) 역 */
    val currentIndex: Int,
    val phase: TripPhase,
    val stationsPassed: Int = 0,
    val features: MotionFeatures = MotionFeatures(),
) {
    val currentStation: Station get() = line.stations[currentIndex]
    val nextIndex: Int? get() = line.nextIndex(currentIndex, direction)
    val nextStation: Station? get() = nextIndex?.let { line.stations[it] }
    val directionLabel: String get() = line.directionLabel(direction)
}

enum class AlertKind { ARRIVED, DEPARTED, HALTED, TERMINAL, CORRECTED }

/** 오버레이·알림·Live Activity 에 그대로 쓰는 문구 */
data class StationAlert(
    val kind: AlertKind,
    val title: String,
    val body: String,
    /** 진동/소리로 사용자 주의를 끌어야 하는지 */
    val important: Boolean,
)

data class TrackerUpdate(
    val state: TrackerState,
    /** 이번 업데이트에서 새로 생긴 알림. 센서 특징값만 바뀐 경우 null */
    val alert: StationAlert?,
)

/** 탐지 이벤트를 역 진행으로 바꾸는 순수 로직 (센서와 무관하게 테스트 가능) */
class TripTracker(
    line: SubwayLine,
    startIndex: Int,
    direction: Direction,
) {
    init {
        require(startIndex in line.stations.indices) { "startIndex out of range: $startIndex" }
    }

    var state = TrackerState(
        line = line,
        direction = direction,
        currentIndex = startIndex,
        phase = if (line.nextIndex(startIndex, direction) == null) TripPhase.FINISHED else TripPhase.AT_STATION,
    )
        private set

    fun onEvent(event: DetectorEvent): StationAlert? {
        val s = state
        if (s.phase == TripPhase.FINISHED) return null
        return when (event) {
            is DetectorEvent.Departed -> {
                if (s.phase != TripPhase.AT_STATION) return null
                state = s.copy(phase = TripPhase.RUNNING)
                AlertTexts.departed(state)
            }

            is DetectorEvent.Arrived -> {
                if (s.phase == TripPhase.AT_STATION) return null
                arriveAtNext()
            }

            is DetectorEvent.HaltedBetweenStations -> {
                if (s.phase != TripPhase.RUNNING) return null
                state = s.copy(phase = TripPhase.HALTED_BETWEEN)
                AlertTexts.halted(state)
            }

            is DetectorEvent.Resumed -> {
                if (s.phase != TripPhase.HALTED_BETWEEN) return null
                state = s.copy(phase = TripPhase.RUNNING)
                null
            }
        }
    }

    /** 사용자 보정: 감지가 한 역을 놓쳤을 때 한 역 앞으로 */
    fun stepForward(): StationAlert? {
        val next = state.nextIndex ?: return null
        state = state.copy(
            currentIndex = next,
            phase = if (state.line.nextIndex(next, state.direction) == null) TripPhase.FINISHED else TripPhase.AT_STATION,
            stationsPassed = state.stationsPassed + 1,
        )
        return AlertTexts.corrected(state)
    }

    /** 사용자 보정: 정차를 잘못 감지했을 때 한 역 뒤로 */
    fun stepBack(): StationAlert? {
        val prev = state.line.previousIndex(state.currentIndex, state.direction) ?: return null
        state = state.copy(
            currentIndex = prev,
            phase = TripPhase.AT_STATION,
            stationsPassed = (state.stationsPassed - 1).coerceAtLeast(0),
        )
        return AlertTexts.corrected(state)
    }

    internal fun updateFeatures(features: MotionFeatures) {
        state = state.copy(features = features)
    }

    private fun arriveAtNext(): StationAlert? {
        val s = state
        val next = s.nextIndex ?: return null
        val isTerminal = s.line.nextIndex(next, s.direction) == null
        state = s.copy(
            currentIndex = next,
            phase = if (isTerminal) TripPhase.FINISHED else TripPhase.AT_STATION,
            stationsPassed = s.stationsPassed + 1,
        )
        return if (isTerminal) AlertTexts.terminal(state) else AlertTexts.arrived(state)
    }
}

/**
 * 플랫폼(Android 서비스, iOS 앱)이 사용하는 진입점.
 *
 * 센서 콜백에서 [onMotion] 을 호출하면 상태가 바뀔 때마다 리스너가 불립니다.
 * 같은 스레드에서만 호출하세요.
 */
class StationAlertSession(
    line: SubwayLine,
    startIndex: Int,
    direction: Direction,
    config: DetectorConfig,
) {
    /** Swift 에서는 Kotlin 기본 인자를 쓸 수 없어 별도 생성자를 둠 */
    constructor(line: SubwayLine, startIndex: Int, direction: Direction) :
        this(line, startIndex, direction, DetectorConfig())

    private val detector = TrainMotionDetector(config)
    private val tracker = TripTracker(line, startIndex, direction)
    private var listener: ((TrackerUpdate) -> Unit)? = null
    private var lastFeatureEmitMs = Long.MIN_VALUE

    /** 특징값(디버그 표시)만 바뀐 경우 리스너 호출 최소 간격 */
    var featureEmitIntervalMs: Long = 500

    val state: TrackerState get() = tracker.state

    fun setListener(listener: ((TrackerUpdate) -> Unit)?) {
        this.listener = listener
        listener?.invoke(TrackerUpdate(tracker.state, null))
    }

    fun onSample(sample: MotionSample) {
        val event = detector.process(sample)
        tracker.updateFeatures(detector.features)
        val alert = event?.let { tracker.onEvent(it) }
        val now = sample.timestampMs
        if (alert != null || event != null || now - lastFeatureEmitMs >= featureEmitIntervalMs) {
            lastFeatureEmitMs = now
            listener?.invoke(TrackerUpdate(tracker.state, alert))
        }
    }

    /** Swift 에서 쓰기 쉬운 원시값 버전. 단위는 m/s² */
    fun onMotion(
        timestampMs: Long,
        userX: Double, userY: Double, userZ: Double,
        gravityX: Double, gravityY: Double, gravityZ: Double,
    ) = onSample(MotionSample(timestampMs, userX, userY, userZ, gravityX, gravityY, gravityZ))

    fun stepForward() = correct { tracker.stepForward() }

    fun stepBack() = correct { tracker.stepBack() }

    private inline fun correct(action: () -> StationAlert?) {
        val alert = action()
        // 수동 보정 후에는 지금 역에 서 있다고 보고 감지기를 초기화
        detector.reset()
        listener?.invoke(TrackerUpdate(tracker.state, alert))
    }
}
