import ActivityKit
import Foundation

/// 앱과 Live Activity 위젯 확장이 함께 쓰는 Live Activity 데이터 정의
struct StationActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// 큰 글씨: 정차 중이면 이번 역, 주행 중이면 다음 역
        var title: String
        /// 작은 글씨: "이번 역 · 다음 교대" 등
        var subtitle: String
        /// 정차 중이면 true (강조 표시)
        var atStation: Bool
        var stationsPassed: Int
    }

    var lineName: String
    var directionLabel: String
    /// 0xRRGGBB
    var lineColorHex: Int
}

extension StationActivityAttributes {
    var lineColorComponents: (red: Double, green: Double, blue: Double) {
        (Double((lineColorHex >> 16) & 0xFF) / 255,
         Double((lineColorHex >> 8) & 0xFF) / 255,
         Double(lineColorHex & 0xFF) / 255)
    }
}
