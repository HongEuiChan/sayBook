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
    /** 이 값보다 진동 RMS 가 크면 '움직임' (m/s²) */
    val movingRms: Double = 0.10,
    /** 이 값보다 진동 RMS 가 작으면 '정지' (m/s²) */
    val stillRms: Double = 0.05,
    /** 열차 가·감속으로 보는 평활 수평 가속도 (m/s²). 지하철은 보통 0.7~1.0 m/s² */
    val tractionAccel: Double = 0.30,
    /** 이 값보다 진동 RMS 가 크면 사람이 걷는 중으로 보고 열차 움직임으로 치지 않음 */
    val walkingRms: Double = 1.2,
    /** 움직임이 이만큼 이어져야 '출발' 확정 */
    val departConfirmMs: Long = 8_000,
    /** 정지가 이만큼 이어져야 '정차' 확정 */
    val stopConfirmMs: Long = 5_000,
    /** 출발 후보 상태에서 정지가 이만큼 이어지면 오탐(손 흔들림 등)으로 보고 취소 */
    val departCancelStillMs: Long = 2_000,
    /** 출발부터 정차까지 최소 주행 시간. 이보다 짧은 정차는 신호대기로 간주 */
    val minRunMs: Long = 40_000,
    /** 샘플 간격이 이보다 크면(센서 중단) 내부 창을 초기화 */
    val maxSampleGapMs: Long = 1_000,
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

    fun reset() {
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

        return when (state) {
            State.STOPPED -> {
                val since = movingSince
                if (since != null) {
                    state = State.DEPARTING
                    candidateStart = since
                }
                null
            }

            State.DEPARTING -> {
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
                if (still != null && t - still >= config.stopConfirmMs) {
                    if (still - departedAt >= config.minRunMs) {
                        state = State.STOPPED
                        DetectorEvent.Arrived(still)
                    } else {
                        state = State.HALTED
                        DetectorEvent.HaltedBetweenStations(still)
                    }
                } else {
                    null
                }
            }

            State.HALTED -> {
                val since = movingSince
                if (since != null && t - since >= config.departConfirmMs) {
                    state = State.RUNNING
                    DetectorEvent.Resumed(since)
                } else {
                    null
                }
            }
        }
    }

    private fun updateFeatures(s: MotionSample, dtMs: Long) {
        // 1) 진동 RMS
        val sq = s.userX * s.userX + s.userY * s.userY + s.userZ * s.userZ
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
            rms > config.movingRms || horizontal > config.tractionAccel -> MotionClass.MOVING
            rms < config.stillRms && horizontal < config.tractionAccel / 2 -> MotionClass.STILL
            else -> MotionClass.UNCERTAIN
        }
        features = MotionFeatures(rms, horizontal, cls)
    }

    private fun clearSignal() {
        windowTimes.clear()
        windowSquares.clear()
        windowSum = 0.0
        hx = 0.0; hy = 0.0; hz = 0.0
        lastTimestamp = null
        stillSince = null
        movingSince = null
    }
}
