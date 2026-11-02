package com.saybook.subway.android

import com.saybook.subway.track.TrackerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 서비스 ↔ 화면 사이에서 현재 추적 상태를 공유하는 단일 저장소 */
object TrackingRepository {
    private val _state = MutableStateFlow<TrackerState?>(null)

    /** 추적 중이 아니면 null */
    val state: StateFlow<TrackerState?> = _state.asStateFlow()

    internal fun publish(state: TrackerState?) {
        _state.value = state
    }
}
