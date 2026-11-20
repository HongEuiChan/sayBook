package com.saybook.subway

import com.saybook.subway.detect.MotionSample
import com.saybook.subway.model.Direction
import com.saybook.subway.model.Station
import com.saybook.subway.model.SubwayLine
import com.saybook.subway.sim.RideSegment
import com.saybook.subway.sim.RideSimulator
import com.saybook.subway.track.AlertKind
import com.saybook.subway.track.LocationFix
import com.saybook.subway.track.StationAlert
import com.saybook.subway.track.StationAlertSession
import com.saybook.subway.track.TripPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationFusionTest {

    /** 정북 방향으로 약 1.1km(0.01°) 간격인 가상 노선 */
    private val geoLine = SubwayLine(
        id = "t",
        name = "테스트선",
        colorHex = 0x123456,
        stations = (0 until 6).map { Station("역$it", latitude = 37.50 + it * 0.01, longitude = 127.0) },
    )
    private val plainLine = geoLine.copy(stations = geoLine.stations.map { Station(it.name) })

    private fun fixAt(t: Long, station: Int, accuracy: Double = 50.0) =
        LocationFix(t, 37.50 + station * 0.01 + 0.0005, 127.0003, accuracy)

    /** 샘플을 재생하면서 [fixes] (샘플 시각 기준) 를 끼워 넣음. 발생한 알림 목록을 돌려줌 */
    private fun play(
        session: StationAlertSession,
        samples: List<MotionSample>,
        fixes: Map<Long, LocationFix> = emptyMap(),
    ): List<StationAlert> {
        val alerts = mutableListOf<StationAlert>()
        session.setListener { u -> u.alert?.let { alerts += it } }
        val pending = fixes.toSortedMap().toMutableMap()
        for (s in samples) {
            val due = pending.keys.filter { it <= s.timestampMs }
            due.forEach { session.onLocation(pending.remove(it)!!) }
            session.onSample(s)
        }
        return alerts
    }

    @Test
    fun reappearingFixTurnsShortRunIntoArrival() {
        // 역간 주행 25초짜리 짧은 구간 → 센서만으로는 신호대기로 판단됨
        val segments = listOf(
            RideSegment.Dwell(20_000),
            RideSegment.Run(accelMs = 8_000, cruiseMs = 9_000, brakeMs = 8_000),
            RideSegment.Dwell(25_000),
        )
        val samples = RideSimulator(5).generate(segments)

        val sensorOnly = StationAlertSession(plainLine, 0, Direction.FORWARD)
        val a1 = play(sensorOnly, samples)
        assertTrue(a1.none { it.kind == AlertKind.ARRIVED }, "센서만: $a1")
        assertEquals(TripPhase.HALTED_BETWEEN, sensorOnly.state.phase)

        // 역사에서 위치가 다시 잡힘 (탑승 시 마지막 위치 이후 50초 공백)
        val withGps = StationAlertSession(plainLine, 0, Direction.FORWARD)
        val a2 = play(
            withGps, samples,
            mapOf(0L to fixAt(0, 0), 52_000L to LocationFix(52_000, 0.0, 0.0, 30.0)),
        )
        assertEquals(listOf("이번 역 · 역1"), a2.filter { it.kind == AlertKind.ARRIVED }.map { it.title })
        assertEquals(1, withGps.state.currentIndex)
    }

    @Test
    fun continuousFixesAboveGroundAreNotStationHints() {
        val segments = listOf(
            RideSegment.Dwell(20_000),
            RideSegment.Run(accelMs = 8_000, cruiseMs = 9_000, brakeMs = 8_000),
            RideSegment.Dwell(25_000),
        )
        val samples = RideSimulator(5).generate(segments)
        val session = StationAlertSession(plainLine, 0, Direction.FORWARD)
        // 지상 구간: 2초마다 계속 위치가 잡힘 → 역사 신호로 쓰지 않음
        val fixes = (0L..70_000L step 2_000).associateWith { LocationFix(it, 0.0, 0.0, 10.0) }
        val alerts = play(session, samples, fixes)
        assertTrue(alerts.none { it.kind == AlertKind.ARRIVED }, "$alerts")
    }

    @Test
    fun stationHintMakesArrivalFasterThanSensorOnly() {
        val segments = listOf(RideSegment.Dwell(20_000), RideSegment.Run(), RideSegment.Dwell(20_000))
        val samples = RideSimulator(9).generate(segments)
        val runEnd = 20_000L + RideSegment.Run().durationMs

        fun arrivalNotifiedAt(fixes: Map<Long, LocationFix>): Long? {
            val session = StationAlertSession(plainLine, 0, Direction.FORWARD)
            var notified: Long? = null
            session.setListener { }
            val pending = fixes.toMutableMap()
            for (s in samples) {
                pending.keys.filter { it <= s.timestampMs }.forEach { session.onLocation(pending.remove(it)!!) }
                val before = session.state.currentIndex
                session.onSample(s)
                if (notified == null && session.state.currentIndex != before) notified = s.timestampMs
            }
            return notified
        }

        val sensorOnly = arrivalNotifiedAt(emptyMap())!!
        val withGps = arrivalNotifiedAt(mapOf(0L to fixAt(0, 0), runEnd + 1_000 to LocationFix(runEnd + 1_000, 0.0, 0.0, 30.0)))!!
        assertTrue(withGps < sensorOnly, "gps=$withGps sensor=$sensorOnly")
        assertTrue(withGps - runEnd < 6_000, "역사 신호 후 빠르게 알려야 함: ${withGps - runEnd}ms")
    }

    @Test
    fun coordinatesSnapToStationWhenSensorMissedIt() {
        val session = StationAlertSession(geoLine, 0, Direction.FORWARD)
        val alerts = mutableListOf<StationAlert>()
        session.setListener { u -> u.alert?.let { alerts += it } }
        // 센서가 움직임을 못 잡았지만(정차 상태) 위치가 역2 에서 잡힘 → 두 정거장 앞으로 보정 + 도착 알림
        session.onLocation(fixAt(1_000, 2))
        assertEquals("역2", session.state.currentStation.name)
        assertEquals(2, session.state.stationsPassed)
        assertEquals(AlertKind.ARRIVED, alerts.last().kind)
    }

    @Test
    fun coordinatesIgnoreFarOrAmbiguousOrOutOfWindowFixes() {
        val session = StationAlertSession(geoLine, 0, Direction.FORWARD)
        // 두 역 사이 한가운데 (모호)
        session.onLocation(LocationFix(1_000, 37.505, 127.0, 30.0))
        // 너무 부정확
        session.onLocation(LocationFix(2_000, 37.51, 127.0, 3_000.0))
        // 후보 범위 밖 (역5 는 다섯 정거장 앞) + 건너뛰기엔 부정확 (100m)
        session.onLocation(fixAt(3_000, 5, accuracy = 100.0))
        assertEquals("역0", session.state.currentStation.name)
    }

    @Test
    fun accurateFixFarAwayCatchesUp() {
        // 출발역을 잘못 골랐어도, 아주 정확한 위치(30m)면 멀리 있는 역으로 바로잡음
        val session = StationAlertSession(geoLine, 0, Direction.FORWARD)
        session.onLocation(fixAt(1_000, 5, accuracy = 30.0))
        assertEquals("역5", session.state.currentStation.name)
    }

    @Test
    fun unknownStartStationIsFoundFromLocation() {
        // 출발역을 모르는 채로 시작 → 정차는 이름 없이 → 역3 에서 정확한 위치 → 역3 확정
        val session = StationAlertSession(geoLine, Direction.FORWARD)
        val alerts = mutableListOf<StationAlert>()
        session.setListener { u -> u.alert?.let { alerts += it } }
        RideSimulator(6).generate(RideSimulator.typicalRide(stations = 1)).forEach(session::onSample)
        assertTrue(!session.state.located)
        assertTrue(alerts.none { it.important }, "역을 모를 땐 크게 알리지 않음: $alerts")

        session.onLocation(fixAt(500_000, 3, accuracy = 80.0))
        assertTrue(session.state.located)
        assertEquals("역3", session.state.currentStation.name)
        assertEquals("이번 역 · 역3", alerts.last().title)

        // 이후는 평소처럼 센서로 진행
        RideSimulator(7).generate(RideSimulator.typicalRide(stations = 1), startMs = 600_000).forEach(session::onSample)
        assertEquals("역4", session.state.currentStation.name)
    }

    @Test
    fun unknownStartIgnoresInaccurateFix() {
        val session = StationAlertSession(geoLine, Direction.FORWARD)
        session.onLocation(fixAt(1_000, 3, accuracy = 150.0))
        assertTrue(!session.state.located)
    }

    @Test
    fun stickyRepeatedCoordinatesAreIgnored() {
        // 같은 좌표가 2분 넘게 반복 → 고정된 엉터리 위치로 보고, 이후 정확해 보여도 무시
        val session = StationAlertSession(geoLine, 0, Direction.FORWARD)
        listOf(0L, 60_000L, 130_000L).forEach { session.onLocation(fixAt(it, 1, accuracy = 500.0)) }
        session.onLocation(fixAt(140_000, 1, accuracy = 40.0))
        assertEquals("역0", session.state.currentStation.name)
    }

    @Test
    fun impossibleJumpIsIgnored() {
        val session = StationAlertSession(geoLine, 0, Direction.FORWARD)
        session.onLocation(fixAt(0, 0, accuracy = 20.0))
        // 10초 만에 2.2km (220m/s) → 버림
        session.onLocation(fixAt(10_000, 2, accuracy = 40.0))
        assertEquals("역0", session.state.currentStation.name)
    }

    @Test
    fun coordinatesWhileMovingSetNextStation() {
        val session = StationAlertSession(geoLine, 0, Direction.FORWARD)
        // 출발해서 달리는 중
        val samples = RideSimulator(2).generate(listOf(RideSegment.Dwell(5_000), RideSegment.Run(cruiseMs = 200_000)))
        val departIdx = samples.indexOfFirst { it.timestampMs >= 40_000 }
        samples.take(departIdx).forEach(session::onSample)
        assertEquals(TripPhase.RUNNING, session.state.phase)

        // 아직 움직이는 중에 역2 근처 위치 → 역2 를 다음 역으로 맞춤 (도착 알림은 실제 정차 때)
        session.onLocation(fixAt(40_000, 2))
        assertEquals("역1", session.state.currentStation.name)
        assertEquals("역2", session.state.nextStation?.name)
        assertEquals(TripPhase.RUNNING, session.state.phase)
    }
}
