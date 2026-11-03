package com.saybook.subway

import com.saybook.subway.detect.DetectorEvent
import com.saybook.subway.detect.TrainMotionDetector
import com.saybook.subway.model.Direction
import com.saybook.subway.model.SubwayLines
import com.saybook.subway.sim.RideSegment
import com.saybook.subway.sim.RideSimulator
import com.saybook.subway.track.AlertKind
import com.saybook.subway.track.StationAlertSession
import com.saybook.subway.track.TrackerUpdate
import com.saybook.subway.track.TripPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationTrackingTest {

    private fun events(segments: List<RideSegment>, seed: Int = 1): List<DetectorEvent> {
        val detector = TrainMotionDetector()
        return RideSimulator(seed).generate(segments).mapNotNull { detector.process(it) }
    }

    @Test
    fun countsEachStationOnTypicalRide() {
        for (seed in 1..5) {
            val ev = events(RideSimulator.typicalRide(stations = 4), seed)
            assertEquals(4, ev.count { it is DetectorEvent.Departed }, "seed=$seed $ev")
            assertEquals(4, ev.count { it is DetectorEvent.Arrived }, "seed=$seed $ev")
        }
    }

    @Test
    fun handMovementWhileStoppedIsNotADeparture() {
        val ev = events(
            listOf(
                RideSegment.Dwell(10_000),
                RideSegment.HandMovement(3_000),
                RideSegment.Dwell(10_000),
                RideSegment.HandMovement(4_000, magnitude = 0.3),
                RideSegment.Dwell(10_000),
            ),
        )
        assertTrue(ev.isEmpty(), "unexpected events: $ev")
    }

    @Test
    fun signalStopBetweenStationsIsNotCountedAsStation() {
        val ev = events(
            listOf(
                RideSegment.Dwell(20_000),
                // 출발 후 26초 만에 역 사이에서 신호대기로 멈춤
                RideSegment.Run(accelMs = 8_000, cruiseMs = 10_000, brakeMs = 8_000),
                RideSegment.Dwell(15_000),
                RideSegment.Run(),
                RideSegment.Dwell(20_000),
            ),
        )
        assertEquals(
            listOf(
                DetectorEvent.Departed::class,
                DetectorEvent.HaltedBetweenStations::class,
                DetectorEvent.Resumed::class,
                DetectorEvent.Arrived::class,
            ),
            ev.map { it::class },
        )
    }

    @Test
    fun departureThatStopsRightAwayIsCancelled() {
        // 정차 중 휴대폰을 움직여 '출발' 로 오인 → 곧 다시 멈춤 → 출발 취소 (역은 그대로)
        val ev = events(
            listOf(
                RideSegment.Dwell(20_000),
                RideSegment.Run(accelMs = 4_000, cruiseMs = 4_000, brakeMs = 4_000, accel = 0.0, vibration = 0.25),
                RideSegment.Dwell(15_000),
                RideSegment.Run(),
                RideSegment.Dwell(20_000),
            ),
        )
        assertEquals(
            listOf(
                DetectorEvent.Departed::class,
                DetectorEvent.DepartureCancelled::class,
                DetectorEvent.Departed::class,
                DetectorEvent.Arrived::class,
            ),
            ev.map { it::class },
        )
    }

    @Test
    fun walkingOffTheTrainCountsAsArrival() {
        val line = SubwayLines.line3
        val session = StationAlertSession(line, 0, Direction.FORWARD)
        val alerts = mutableListOf<String>()
        session.setListener { u -> u.alert?.let { alerts += it.title } }
        // 목적지: 열차가 서자마자 일어나 걸어 나감 (정지 구간 없음), 이후 약하게 걷기
        RideSimulator(4).generate(
            listOf(
                RideSegment.Dwell(20_000),
                RideSegment.Run(),
                RideSegment.Walking(10_000),
                RideSegment.Walking(10_000, intensity = 0.4),
            ),
        ).forEach(session::onSample)
        assertEquals("주엽", session.state.currentStation.name, "$alerts")
        assertEquals(TripPhase.AT_STATION, session.state.phase, "내린 뒤 걸어도 출발로 보면 안 됨: $alerts")
    }

    @Test
    fun sessionAdvancesStationsAndWrapsOnCircularLine() {
        val line = SubwayLines.line2
        val start = line.stations.indexOfFirst { it.name == "을지로입구" }
        // 외선순환(역순): 을지로입구 → 시청 → 충정로
        val session = StationAlertSession(line, start, Direction.BACKWARD)
        val updates = mutableListOf<TrackerUpdate>()
        session.setListener { updates += it }

        RideSimulator(7).generate(RideSimulator.typicalRide(stations = 2)).forEach(session::onSample)

        val arrivals = updates.mapNotNull { it.alert }.filter { it.kind == AlertKind.ARRIVED }
        assertEquals(listOf("이번 역 · 시청", "이번 역 · 충정로"), arrivals.map { it.title })
        assertEquals("충정로", session.state.currentStation.name)
        assertEquals("아현", session.state.nextStation?.name)
        assertEquals(TripPhase.AT_STATION, session.state.phase)
    }

    @Test
    fun terminalStationFinishesTrip() {
        val line = SubwayLines.line3
        val session = StationAlertSession(line, line.size - 2, Direction.FORWARD)
        var last: TrackerUpdate? = null
        session.setListener { if (it.alert != null) last = it }
        RideSimulator(3).generate(RideSimulator.typicalRide(stations = 2)).forEach(session::onSample)

        assertEquals(TripPhase.FINISHED, session.state.phase)
        assertEquals("오금", session.state.currentStation.name)
        assertEquals(AlertKind.TERMINAL, last?.alert?.kind)
    }

    @Test
    fun manualCorrection() {
        val line = SubwayLines.line4
        val session = StationAlertSession(line, 0, Direction.FORWARD)
        session.stepForward()
        session.stepForward()
        assertEquals("별내별가람", session.state.currentStation.name)
        session.stepBack()
        assertEquals("오남", session.state.currentStation.name)
        session.stepBack()
        session.stepBack() // 시점에서 더 뒤로는 못 감
        assertEquals("진접", session.state.currentStation.name)
    }
}
