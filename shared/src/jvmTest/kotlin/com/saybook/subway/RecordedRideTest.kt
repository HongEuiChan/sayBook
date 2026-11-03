package com.saybook.subway

import com.saybook.subway.model.Direction
import com.saybook.subway.model.SubwayLines
import com.saybook.subway.track.AlertKind
import com.saybook.subway.track.StationAlertSession
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 실제 탑승 기록 재생 테스트.
 *
 * 프로젝트 루트의 `recordings/` (git 제외) 에 기록 `X.csv` 와 정답 `X.truth` 를 두면 실행됩니다.
 * 없으면 아무것도 하지 않습니다.
 *
 * 기록 형식 (앱의 탑승 기록 기능이 남기는 CSV)
 * - `H,버전,노선ID,출발역인덱스,방향,...`
 * - `S,ms,userX,userY,userZ,gravX,gravY,gravZ`  (가속도: mm/s², 즉 m/s² × 1000)
 * - `L,epochMs,위도,경도,정확도m`
 *
 * 정답 형식: 한 줄에 `역이름,정차시작초,정차끝초` (기록 첫 샘플 기준)
 */
class RecordedRideTest {

    private val dir = listOf(File("recordings"), File("../recordings")).firstOrNull { it.isDirectory }

    @Test
    fun recordedRidesDetectEveryStation() {
        val rides = dir?.listFiles { f -> f.extension == "csv" && File(f.path.removeSuffix(".csv") + ".truth").exists() }
            ?: return
        for (csv in rides) {
            val r = replay(csv, useLocation = true)
            println("${csv.name}: ${r.correct}/${r.total} 정답, 오탐 ${r.wrong}")
            assertTrue(r.correct == r.total && r.wrong == 0, "${csv.name}: $r")
        }
    }

    data class Result(val total: Int, val correct: Int, val wrong: Int, val arrivals: List<Pair<Int, String>>)

    companion object {
        fun replay(csv: File, useLocation: Boolean): Result {
            val truth = File(csv.path.removeSuffix(".csv") + ".truth").readLines().filter { it.isNotBlank() }.map {
                val (name, a, b) = it.split(',')
                Triple(name.trim(), a.trim().toInt(), b.trim().toInt())
            }
            var session: StationAlertSession? = null
            var t0 = -1L
            var lastS = 0L
            val arrivals = mutableListOf<Pair<Int, String>>()
            csv.forEachLine { l ->
                val p = l.split(',')
                when (p[0]) {
                    "H" -> {
                        val line = SubwayLines.byId(p[2]) ?: error("unknown line ${p[2]}")
                        session = StationAlertSession(line, p[3].toInt(), Direction.valueOf(p[4])).also { s ->
                            s.setListener { u ->
                                val kind = u.alert?.kind
                                if (kind == AlertKind.ARRIVED || kind == AlertKind.TERMINAL) {
                                    arrivals += ((lastS - t0) / 1000).toInt() to u.state.currentStation.name
                                }
                            }
                        }
                    }
                    "S" -> {
                        val t = p[1].toLong()
                        if (t0 < 0) t0 = t
                        lastS = t
                        val v = p.subList(2, 8).map { it.toDouble() / 1000 }
                        session?.onMotion(t, v[0], v[1], v[2], v[3], v[4], v[5])
                    }
                    "L" -> if (useLocation) {
                        session?.onLocation(p[1].toLong(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble())
                    }
                }
            }
            val correct = truth.count { (name, a, b) -> arrivals.any { it.second == name && it.first in a..b } }
            val wrong = arrivals.count { (ts, name) -> truth.none { it.first == name && ts in it.second..it.third } }
            return Result(truth.size, correct, wrong, arrivals)
        }
    }
}
