package com.saybook.subway.track

/** 두 플랫폼이 같은 문구를 쓰도록 공유하는 메시지 포맷 */
object AlertTexts {

    fun arrived(s: TrackerState) = StationAlert(
        kind = AlertKind.ARRIVED,
        title = "이번 역 · ${s.currentStation.name}",
        body = s.nextStation?.let { "다음 역은 ${it.name}입니다" } ?: "종착역입니다",
        important = true,
    )

    fun departed(s: TrackerState) = StationAlert(
        kind = AlertKind.DEPARTED,
        title = "다음 역 · ${s.nextStation?.name ?: "-"}",
        body = "${s.currentStation.name} 출발 · ${s.line.name} ${s.directionLabel}",
        important = false,
    )

    fun halted(s: TrackerState) = StationAlert(
        kind = AlertKind.HALTED,
        title = "잠시 정차 중",
        body = "${s.currentStation.name} → ${s.nextStation?.name ?: "-"} 사이",
        important = false,
    )

    fun terminal(s: TrackerState) = StationAlert(
        kind = AlertKind.TERMINAL,
        title = "종착역 · ${s.currentStation.name}",
        body = "${s.line.name} 종착역입니다. 내리실 준비하세요",
        important = true,
    )

    fun corrected(s: TrackerState) = StationAlert(
        kind = AlertKind.CORRECTED,
        title = "현재 역 · ${s.currentStation.name}",
        body = "위치를 직접 보정했습니다",
        important = false,
    )

    /** 영상 위 작은 오버레이에 쓰는 한 줄 요약: (큰 글씨, 작은 글씨) */
    fun compact(s: TrackerState): Pair<String, String> = when (s.phase) {
        TripPhase.AT_STATION -> s.currentStation.name to "이번 역 · 다음 ${s.nextStation?.name ?: "-"}"
        TripPhase.RUNNING -> (s.nextStation?.name ?: "-") to "다음 역 · ${s.currentStation.name} 출발"
        TripPhase.HALTED_BETWEEN -> (s.nextStation?.name ?: "-") to "다음 역 · 잠시 정차 중"
        TripPhase.FINISHED -> s.currentStation.name to "종착역"
    }

    fun compactTitle(s: TrackerState): String = compact(s).first
    fun compactSubtitle(s: TrackerState): String = compact(s).second
}
