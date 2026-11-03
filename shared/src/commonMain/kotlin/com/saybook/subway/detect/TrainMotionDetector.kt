package com.saybook.subway.detect

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 센서 한 샘플.
 *
 * 두 플랫폼 모두 자이로 센서와 융합해 중력이 분리된 값을 제공하므로 그것을 그대로 넘깁니다.
 * - Android: TYPE_LINEAR_ACCELERATION (user*) + TYPE_GRAVITY (gravity*), 단위 m/s²
 * - iOS: CMDeviceMotion.userAcceleration / gravity (단위 g → 9.81 을 곱해 m/s² 로 변환)
 */
data class MotionSample(
    val timestampMs: Long,
    val userX: Double, val userY: Double, val userZ: Double,
    val gravityX: Double, val gravityY: Double, val gravityZ: Double,
)

/** 감지기 파라미터. 실제 탑승 데이터로 보정(튜닝)해야 하는 값들입니다. */
data class DetectorConfig(
    /** 진동 RMS 를 계산하는 이동 창 길이 */
    val windowMs: Long = 2_000,
    /** 수평 가속도(견인/제동) 평활화 시정수 */
    val horizontalTauMs: Long = 1_500,
    /**
     * 진동 RMS 계산 전 고역통과: 최근 N 샘플 이동평균을 빼서 손 움직임(저주파)을 걸러내고
     * 레일 진동(고주파)만 남김. 50Hz 기준 5샘플 ≈ 5Hz 이상. 0 이면 끔.
     *
     * 실측(신분당선, 손에 들고 시청): 주행 0.2~0.5, 정차 0.03~0.13 m/s².
     * 고역통과 없이 보면 정차 중에도 손 움직임 때문에 0.1~0.7 이 나와 구분이 안 됨.
     */
    val highPassSamples: Int = 5,
    /** 이 값보다 진동 RMS 가 크면 '움직임' (m/s²) */
    val movingRms: Double = 0.20,
    /** 이 값보다 진동 RMS 가 작으면 '정지' (m/s²) */
    val stillRms: Double = 0.10,
    /** 이 값보다 진동 RMS 가 크면 사람이 걷는 중으로 보고 열차 움직임으로 치지 않음 */
    val walkingRms: Double = 1.2,
    /**
     * 주행 중 이 세기([walkingArriveRms]) 이상의 걷기가 [walkingArriveMs] 이어지면 '내렸다' = 정차로 봄.
     * 목적지에서는 열차가 서자마자 일어나 걸어 나가므로 정지 구간이 거의 없음 (실측: 강남).
     * 자리에서 일어나 문 앞으로 가는 정도(1~1.7)는 넘지 않도록 높게 잡음.
     */
    val walkingArriveRms: Double = 2.0,
    val walkingArriveMs: Long = 6_000,
    /** 움직임이 이만큼 이어져야 '출발' 확정 */
    val departConfirmMs: Long = 8_000,
    /** 정지가 이만큼 이어져야 '정차' 확정 */
    val stopConfirmMs: Long = 5_000,
    /** 출발 후보 상태에서 정지가 이만큼 이어지면 오탐(손 흔들림 등)으로 보고 취소 */
    val departCancelStillMs: Long = 2_000,
    /**
     * 출발 후 이 시간 안에 다시 멈추면 출발 자체가 오탐(정차 중 승하차·휴대폰 움직임)이었다고 보고 취소.
     * 실측에서 정차 중 오탐 출발은 10~12초 뒤 다시 멈췄음.
     */
    val falseDepartureMs: Long = 20_000,
    /** 출발부터 정차까지 최소 주행 시간. 이보다 짧은 정차는 신호대기로 간주 */
    val minRunMs: Long = 40_000,
    /**
     * 걷기가 끝난 뒤 이 시간 동안은 출발로 판정하지 않음.
     * 내려서 걸어갈 때 약한 걸음(0.6~0.8)은 걷기 기준 아래라 '움직임' 으로 보이기 때문 (실측: 강남 하차 후).
     */
    val departAfterWalkingMs: Long = 15_000,
    /** 샘플 간격이 이보다 크면(센서 중단) 내부 창을 초기화 */
    val maxSampleGapMs: Long = 1_000,
    /** 역사 신호([TrainMotionDetector.stationHint])가 유효한 시간 */
    val stationHintValidMs: Long = 20_000,
    /** 역사 신호가 있을 때 정차로 확정하는 데 필요한 최소 정지 시간 */
    val stationHintStillMs: Long = 1_000,
)

enum class MotionClass { STILL, MOVING, WALKING, UNCERTAIN }

/** 디버그/튜닝용으로 화면에 노출하는 현재 특징값 */
data class MotionFeatures(
    val vibrationRms: Double = 0.0,
    val horizontalAccel: Double = 0.0,
    val motionClass: MotionClass = MotionClass.UNCERTAIN,
)

sealed class DetectorEvent {
    abstract val timestampMs: Long

    /** 역에서 출발함 (출발 확정 시점에 발생, timestampMs 는 실제 움직임이 시작된 시각) */
    data class Departed(override val timestampMs: Long) : DetectorEvent()

    /** 다음 역에 정차함 */
    data class Arrived(override val timestampMs: Long) : DetectorEvent()

    /** 역 사이에서 멈춤 (신호대기 등) */
    data class HaltedBetweenStations(override val timestampMs: Long) : DetectorEvent()

    /** 역 사이 정지 후 다시 움직임 */
    data class Resumed(override val timestampMs: Long) : DetectorEvent()

    /** 직전 '출발' 이 오탐이었음 (곧바로 다시 멈춤) → 아직 그 역에 정차 중 */
    data class DepartureCancelled(override val timestampMs: Long) : DetectorEvent()
}

/**
 * 가속도 센서만으로 열차의 출발/정차를 판정하는 상태 머신.
 *
 * 특징값
 * 1. 진동 RMS: 달리는 열차는 레일 진동 때문에 정차 중보다 확실히 흔들린다.
 * 2. 평활 수평 가속도: 출발·제동 때는 일정한 방향으로 수 초 이상 가속이 이어진다.
 *    손떨림은 방향이 무작위라 평활화하면 상쇄되므로 열차 가·감속과 구분된다.
 *
 * 상태
 *   STOPPED ──움직임──▶ DEPARTING ──departConfirmMs 지속──▶ RUNNING (Departed)
 *      ▲                    │ 정지 departCancelStillMs                │ 정지 stopConfirmMs
 *      └────────────────────┘                                        ▼
 *      └──────────────── 주행 ≥ minRunMs: Arrived ◀──────────────────┤
 *                                                                    ▼ 주행 < minRunMs
 *                         RUNNING ◀── 움직임 departConfirmMs ── HALTED (HaltedBetweenStations)
 *                                       (Resumed)
 *
 * 스레드 안전하지 않습니다. 한 스레드(센서 콜백)에서만 호출하세요.
 */
class TrainMotionDetector(val config: DetectorConfig = DetectorConfig()) {

    enum class State { STOPPED, DEPARTING, RUNNING, HALTED }

    var state: State = State.STOPPED
        private set

    var features: MotionFeatures = MotionFeatures()
        private set

    // 고역통과용 최근 샘플
    private val recentX = ArrayDeque<Double>()
    private val recentY = ArrayDeque<Double>()
    private val recentZ = ArrayDeque<Double>()

    // 걷기 (내림) 추적: 2초 이내 끊김은 이어진 것으로 봄
    private var strongWalkSince: Long? = null
    private var lastStrongWalkAt: Long = 0
    private var lastWalkingAt: Long? = null

    // 진동 RMS 이동 창
    private val windowTimes = ArrayDeque<Long>()
    private val windowSquares = ArrayDeque<Double>()
    private var windowSum = 0.0

    // 평활 수평 가속도 (기기 좌표계 벡터)
    private var hx = 0.0
    private var hy = 0.0
    private var hz = 0.0

    private var lastTimestamp: Long? = null
    private var stillSince: Long? = null
    private var movingSince: Long? = null
    private var candidateStart: Long = 0
    private var departedAt: Long = 0
    private var haltedAt: Long = 0

    // 역사 신호: 다음 샘플에서 만료 시각을 정함
    private var hintRequested = false
    private var hintExpiresAt: Long? = null

    /**
     * '지금 역사 안에 있다' 는 외부 신호 (예: 지하에서 끊겼던 GPS 가 다시 잡힘).
     * 주행 중 멈춰 있으면 정차 확정을 앞당기고, 신호대기로 본 짧은 구간도 정차로 바로잡습니다.
     */
    fun stationHint() {
        hintRequested = true
    }

    fun reset() {
        hintRequested = false
        hintExpiresAt = null
        state = State.STOPPED
        features = MotionFeatures()
        clearSignal()
    }

    /** 샘플 하나를 처리하고, 상태가 확정적으로 바뀌었으면 이벤트를 돌려준다. */
    fun process(sample: MotionSample): DetectorEvent? {
        val t = sample.timestampMs
        val last = lastTimestamp
        if (last != null && (t - last > config.maxSampleGapMs || t < last)) clearSignal()
        val dt = if (lastTimestamp == null) 0L else t - lastTimestamp!!
        lastTimestamp = t

        updateFeatures(sample, dt)
        val cls = features.motionClass

        if (hintRequested) {
            hintRequested = false
            hintExpiresAt = t + config.stationHintValidMs
        }
        val hintActive = hintExpiresAt?.let { t <= it } ?: false
        if (!hintActive) hintExpiresAt = null

        // 연속 정지/움직임 구간 추적
        if (cls == MotionClass.STILL) {
            if (stillSince == null) stillSince = t
        } else if (cls != MotionClass.UNCERTAIN) {
            stillSince = null
        }
        if (cls == MotionClass.MOVING) {
            if (movingSince == null) movingSince = t
        } else if (cls != MotionClass.UNCERTAIN) {
            movingSince = null
        }
        if (features.vibrationRms > config.walkingArriveRms) {
            if (strongWalkSince == null || t - lastStrongWalkAt > 2_000) strongWalkSince = t
            lastStrongWalkAt = t
        } else if (t - lastStrongWalkAt > 2_000) {
            strongWalkSince = null
        }
        val walkedOff = strongWalkSince?.let { t - it >= config.walkingArriveMs } ?: false
        if (cls == MotionClass.WALKING) lastWalkingAt = t
        val recentlyWalked = lastWalkingAt?.let { t - it < config.departAfterWalkingMs } ?: false

        return when (state) {
            State.STOPPED -> {
                hintExpiresAt = null
                val since = movingSince
                if (since != null && !recentlyWalked) {
                    state = State.DEPARTING
                    candidateStart = since
                }
                null
            }

            State.DEPARTING -> {
                hintExpiresAt = null
                val still = stillSince
                when {
                    cls == MotionClass.WALKING ||
                        (still != null && t - still >= config.departCancelStillMs) -> {
                        state = State.STOPPED
                        null
                    }

                    t - candidateStart >= config.departConfirmMs -> {
                        state = State.RUNNING
                        departedAt = candidateStart
                        DetectorEvent.Departed(candidateStart)
                    }

                    else -> null
                }
            }

            State.RUNNING -> {
                val still = stillSince
                when {
                    // 역사 신호 + 잠깐의 정지 → 바로 정차 확정 (최소 주행 시간도 무시)
                    hintActive && still != null && t - still >= config.stationHintStillMs -> {
                        hintExpiresAt = null
                        state = State.STOPPED
                        DetectorEvent.Arrived(still)
                    }

                    // 주행 중 한참 걸음 → 열차에서 내림
                    walkedOff && strongWalkSince!! - departedAt >= config.minRunMs -> {
                        state = State.STOPPED
                        DetectorEvent.Arrived(strongWalkSince!!)
                    }

                    still != null && t - still >= config.stopConfirmMs -> {
                        if (still - departedAt < config.falseDepartureMs) {
                            state = State.STOPPED
                            DetectorEvent.DepartureCancelled(still)
                        } else if (still - departedAt >= config.minRunMs) {
                            state = State.STOPPED
                            DetectorEvent.Arrived(still)
                        } else {
                            state = State.HALTED
                            haltedAt = still
                            DetectorEvent.HaltedBetweenStations(still)
                        }
                    }

                    else -> null
                }
            }

            State.HALTED -> {
                val since = movingSince
                if (hintActive || walkedOff) {
                    // 신호대기로 봤던 정지가 사실은 역 (역간 거리가 짧은 구간이거나 거기서 내림)
                    hintExpiresAt = null
                    state = State.STOPPED
                    DetectorEvent.Arrived(haltedAt)
                } else if (since != null && t - since >= config.departConfirmMs) {
                    state = State.RUNNING
                    DetectorEvent.Resumed(since)
                } else {
                    null
                }
            }
        }
    }

    private fun updateFeatures(s: MotionSample, dtMs: Long) {
        // 1) 진동 RMS (고역통과 후)
        var vx = s.userX
        var vy = s.userY
        var vz = s.userZ
        val n = config.highPassSamples
        if (n > 0) {
            recentX.addLast(s.userX); recentY.addLast(s.userY); recentZ.addLast(s.userZ)
            if (recentX.size > n + 1) {
                recentX.removeFirst(); recentY.removeFirst(); recentZ.removeFirst()
            }
            vx -= recentX.sum() / recentX.size
            vy -= recentY.sum() / recentY.size
            vz -= recentZ.sum() / recentZ.size
        }
        val sq = vx * vx + vy * vy + vz * vz
        windowTimes.addLast(s.timestampMs)
        windowSquares.addLast(sq)
        windowSum += sq
        while (windowTimes.isNotEmpty() && s.timestampMs - windowTimes.first() > config.windowMs) {
            windowTimes.removeFirst()
            windowSum -= windowSquares.removeFirst()
        }
        val rms = sqrt((windowSum / windowSquares.size).coerceAtLeast(0.0))

        // 2) 수평 성분 = userAcc - (userAcc·ĝ)ĝ, 시간 기반 EMA
        val gNorm = sqrt(s.gravityX * s.gravityX + s.gravityY * s.gravityY + s.gravityZ * s.gravityZ)
        var px = s.userX
        var py = s.userY
        var pz = s.userZ
        if (gNorm > 1e-6) {
            val ux = s.gravityX / gNorm
            val uy = s.gravityY / gNorm
            val uz = s.gravityZ / gNorm
            val dot = px * ux + py * uy + pz * uz
            px -= dot * ux
            py -= dot * uy
            pz -= dot * uz
        }
        if (dtMs <= 0) {
            hx = px; hy = py; hz = pz
        } else {
            val alpha = 1.0 - exp(-dtMs.toDouble() / config.horizontalTauMs)
            hx += alpha * (px - hx)
            hy += alpha * (py - hy)
            hz += alpha * (pz - hz)
        }
        val horizontal = sqrt(hx * hx + hy * hy + hz * hz)

        val cls = when {
            rms > config.walkingRms -> MotionClass.WALKING
            rms > config.movingRms -> MotionClass.MOVING
            // 평활 수평가속도는 손 움직임에도 커지므로 정지 판정에 쓰지 않음 (실측에서 정차를 다수 놓침)
            rms < config.stillRms -> MotionClass.STILL
            else -> MotionClass.UNCERTAIN
        }
        features = MotionFeatures(rms, horizontal, cls)
    }

    private fun clearSignal() {
        windowTimes.clear()
        windowSquares.clear()
        windowSum = 0.0
        recentX.clear(); recentY.clear(); recentZ.clear()
        strongWalkSince = null
        lastWalkingAt = null
        hx = 0.0; hy = 0.0; hz = 0.0
        lastTimestamp = null
        stillSince = null
        movingSince = null
    }
}
