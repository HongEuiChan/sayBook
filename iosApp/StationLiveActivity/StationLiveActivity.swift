import ActivityKit
import SwiftUI
import WidgetKit

@main
struct StationLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        StationLiveActivity()
    }
}

/// 잠금화면 배너 + Dynamic Island 에 지금 역 / 다음 역 표시
struct StationLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: StationActivityAttributes.self) { context in
            LockScreenView(attributes: context.attributes, state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.8))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let color = lineColor(context.attributes)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.lineName, systemImage: "tram.fill")
                        .font(.caption)
                        .foregroundStyle(color)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.attributes.directionLabel).font(.caption).foregroundStyle(.secondary)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.title).font(.title2.bold())
                        Text(context.state.subtitle).font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } compactLeading: {
                Image(systemName: context.state.atStation ? "tram.fill" : "arrow.right")
                    .foregroundStyle(color)
            } compactTrailing: {
                Text(context.state.title)
                    .font(.caption.bold())
                    .lineLimit(1)
                    .frame(maxWidth: 80)
            } minimal: {
                Image(systemName: "tram.fill").foregroundStyle(color)
            }
            .keylineTint(color)
        }
    }
}

private func lineColor(_ attributes: StationActivityAttributes) -> Color {
    let c = attributes.lineColorComponents
    return Color(red: c.red, green: c.green, blue: c.blue)
}

private struct LockScreenView: View {
    let attributes: StationActivityAttributes
    let state: StationActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(lineColor(attributes))
                .frame(width: 14, height: 14)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(attributes.lineName) · \(attributes.directionLabel)")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.7))
                Text(state.title).font(.title2.bold()).foregroundStyle(.white)
                Text(state.subtitle).font(.caption).foregroundStyle(.white.opacity(0.8))
            }
            Spacer()
        }
        .padding()
    }
}
