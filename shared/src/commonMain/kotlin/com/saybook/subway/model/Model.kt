package com.saybook.subway.model

data class Station(
    val name: String,
    /** 역 중심 좌표 (WGS84). 있으면 역사에서 잡힌 GPS 위치로 현재 역을 자동 보정합니다. */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
}

data class SubwayLine(
    val id: String,
    val name: String,
    /** 노선 대표색 (0xRRGGBB) */
    val colorHex: Long,
    val stations: List<Station>,
    /** 2호선 본선처럼 순환하는 노선이면 true */
    val isCircular: Boolean = false,
    /** FORWARD(목록 순서) 방향의 이름. 예: "내선순환". null 이면 종착역 이름으로 표시 */
    val forwardLabel: String? = null,
    val backwardLabel: String? = null,
) {
    val size: Int get() = stations.size

    /** [from]에서 [direction] 방향으로 한 정거장 이동한 인덱스. 종착역이면 null. */
    fun nextIndex(from: Int, direction: Direction): Int? {
        val n = from + direction.step
        return when {
            n in stations.indices -> n
            isCircular -> (n + size) % size
            else -> null
        }
    }

    fun previousIndex(from: Int, direction: Direction): Int? =
        nextIndex(from, direction.reversed())

    /** "을지로입구 방면 (내선순환)" 처럼 사용자에게 보여줄 방향 이름 */
    fun directionLabel(direction: Direction): String {
        val custom = if (direction == Direction.FORWARD) forwardLabel else backwardLabel
        if (custom != null) return custom
        val terminal = if (direction == Direction.FORWARD) stations.last() else stations.first()
        return "${terminal.name} 방면"
    }
}

enum class Direction(val step: Int) {
    /** 노선 역 목록의 순서대로 진행 */
    FORWARD(1),

    /** 노선 역 목록의 역순으로 진행 */
    BACKWARD(-1);

    fun reversed(): Direction = if (this == FORWARD) BACKWARD else FORWARD
}
