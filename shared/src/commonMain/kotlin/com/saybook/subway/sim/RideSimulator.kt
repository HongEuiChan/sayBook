package com.saybook.subway.sim

import com.saybook.subway.detect.MotionSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** 시뮬레이션 구간 */
sealed class RideSegment {
    abstract val durationMs: Long

    /** 정차 중. [handShake] 는 손에 든 휴대폰의 미세 흔들림 표준편차 (m/s²) */
    data class Dwell(override val durationMs: Long, val handShake: Double = 0.015) : RideSegment()

    /** 가속 → 정속 → 제동 한 번의 역간 주행 */
    data class Run(
        val accelMs: Long = 15_000,
        val cruiseMs: Long = 60_000,
        val brakeMs: Long = 15_000,
        val accel: Double = 0.9,
        val vibration: Double = 0.18,
    ) : RideSegment() {
        override val durationMs: Long get() = accelMs + cruiseMs + brakeMs
    }

    /** 정차 중 사용자가 휴대폰을 크게 움직임 (오탐 테스트용) */
    data class HandMovement(override val durationMs: Long, val magnitude: Double = 0.4) : RideSegment()

    /** 사람이 걷는 중 (약 2Hz 발걸음 충격 + 잔진동) */
    data class Walking(override val durationMs: Long, val intensity: Double = 2.5) : RideSegment()
}

/**
 * 지하철 탑승 중 가속도 센서 신호를 합성합니다.
 * 테스트와 앱의 '데모 모드' 에서 실제 탑승 없이 흐름을 확인하는 용도입니다.
 */
class RideSimulator(
    seed: Int = 42,
    private val sampleRateHz: Int = 50,
) {
    private val random = Random(seed)

    // 휴대폰을 45° 정도 기울여 들고 영상을 보는 자세: 중력이 y,z 에 나뉨
    private val gravity = doubleArrayOf(0.0, 6.94, 6.94)

    // 열차 진행 방향 (중력에 수직인 기기 x 축)
    private val travelAxis = doubleArrayOf(1.0, 0.0, 0.0)

    fun generate(segments: List<RideSegment>, startMs: Long = 0): List<MotionSample> {
        val out = ArrayList<MotionSample>()
        val stepMs = 1000L / sampleRateHz
        var t = startMs
        for (seg in segments) {
            val end = t + seg.durationMs
            val segStart = t
            while (t < end) {
                val local = t - segStart
                out += sampleFor(seg, local, t)
                t += stepMs
            }
        }
        return out
    }

    private fun sampleFor(seg: RideSegment, localMs: Long, t: Long): MotionSample {
        var ax = 0.0
        var ay = 0.0
        var az = 0.0
        when (seg) {
            is RideSegment.Dwell -> {
                ax += gauss(seg.handShake); ay += gauss(seg.handShake); az += gauss(seg.handShake)
            }

            is RideSegment.HandMovement -> {
                val phase = 2 * PI * localMs / 900.0
                ax += seg.magnitude * sin(phase) + gauss(0.1)
                ay += seg.magnitude * cos(phase * 1.3) + gauss(0.1)
                az += gauss(0.1)
            }

            is RideSegment.Walking -> {
                val stepPhase = (localMs % 500) / 500.0
                val impact = if (stepPhase < 0.1) seg.intensity * 2 else 0.0
                ax += gauss(seg.intensity * 0.6)
                ay += gauss(seg.intensity * 0.6) + impact
                az += gauss(seg.intensity * 0.6)
            }

            is RideSegment.Run -> {
                val longitudinal = when {
                    localMs < seg.accelMs -> seg.accel
                    localMs < seg.accelMs + seg.cruiseMs -> 0.0
                    else -> -seg.accel
                }
                ax += longitudinal * travelAxis[0]
                // 레일 진동: 백색잡음 + 저주파 흔들림
                val sway = 0.08 * sin(2 * PI * localMs / 700.0)
                ax += gauss(seg.vibration)
                ay += gauss(seg.vibration) + sway
                az += gauss(seg.vibration)
            }
        }
        return MotionSample(t, ax, ay, az, gravity[0], gravity[1], gravity[2])
    }

    private fun gauss(sigma: Double): Double {
        // Box–Muller
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sigma * sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }

    companion object {
        /** [stations] 개 역을 지나는 전형적인 탑승 시나리오 */
        fun typicalRide(stations: Int, dwellMs: Long = 25_000): List<RideSegment> = buildList {
            add(RideSegment.Dwell(dwellMs))
            repeat(stations) {
                add(RideSegment.Run(cruiseMs = 40_000L + (it % 3) * 20_000L))
                add(RideSegment.Dwell(dwellMs))
            }
        }
    }
}
