import Shared
import SwiftUI

private extension SubwayLine {
    var swiftColor: Color {
        let hex = Int(colorHex)
        return Color(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

struct ContentView: View {
    @EnvironmentObject private var controller: TrackingController

    var body: some View {
        NavigationStack {
            if let state = controller.state {
                TrackingView(state: state)
            } else {
                SetupView()
            }
        }
    }
}

private struct SetupView: View {
    @EnvironmentObject private var controller: TrackingController
    private let lines: [SubwayLine] = SubwayLines.shared.all

    @State private var lineIndex = 0
    @State private var forward = true
    @State private var stationIndex = 0
    @State private var query = ""
    @State private var demo = false

    private var line: SubwayLine { lines[lineIndex] }
    private var direction: Direction { forward ? .forward : .backward }

    private var filtered: [(Int, Station)] {
        line.stations.enumerated()
            .filter { query.isEmpty || $0.element.name.contains(query) }
            .map { ($0.offset, $0.element) }
    }

    var body: some View {
        List {
            Section("노선") {
                Picker("노선", selection: $lineIndex) {
                    ForEach(lines.indices, id: \.self) { i in Text(lines[i].name).tag(i) }
                }
                .pickerStyle(.segmented)
                .onChange(of: lineIndex) { _ in stationIndex = 0; query = "" }

                Picker("방향", selection: $forward) {
                    Text(line.directionLabel(direction: .forward)).tag(true)
                    Text(line.directionLabel(direction: .backward)).tag(false)
                }
                .pickerStyle(.segmented)
            }

            Section("탄 역") {
                ForEach(filtered, id: \.0) { index, station in
                    Button {
                        stationIndex = index
                    } label: {
                        HStack {
                            Circle().fill(line.swiftColor).frame(width: 10, height: 10)
                            Text(station.name)
                                .fontWeight(index == stationIndex ? .bold : .regular)
                                .foregroundStyle(.primary)
                            Spacer()
                            if index == stationIndex { Image(systemName: "checkmark").foregroundStyle(line.swiftColor) }
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "역 이름 검색")
        .navigationTitle("지하철 역알림")
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                Toggle("데모 모드 (가상 센서 데이터, 10배속)", isOn: $demo).font(.footnote)
                Button {
                    controller.start(line: line, startIndex: stationIndex, direction: direction, demo: demo)
                } label: {
                    Text("\(line.stations[stationIndex].name)에서 출발 · \(line.directionLabel(direction: direction))")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .tint(line.swiftColor)
            }
            .padding()
            .background(.bar)
        }
    }
}

private struct TrackingView: View {
    @EnvironmentObject private var controller: TrackingController
    let state: TrackerState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Spacer()
            Text("\(state.line.name) · \(state.directionLabel)")
                .font(.headline)
                .foregroundStyle(state.line.swiftColor)
            Text(AlertTexts.shared.compactTitle(s: state))
                .font(.system(size: 48, weight: .heavy))
            Text(AlertTexts.shared.compactSubtitle(s: state))
                .foregroundStyle(.secondary)
            Text("지나온 역: \(state.stationsPassed)개").font(.subheadline).padding(.top)
            Text(String(
                format: "센서 · 진동 %.3f · 가감속 %.3f m/s² · %@",
                state.features.vibrationRms, state.features.horizontalAccel, state.features.motionClass.name
            ))
            .font(.caption2)
            .foregroundStyle(.secondary)
            Text("이제 영상 앱으로 이동해도 Dynamic Island와 알림으로 계속 알려드려요.")
                .font(.footnote)
            Spacer()
            Text("감지가 틀렸다면 직접 보정하세요").font(.caption).foregroundStyle(.secondary)
            HStack {
                Button("◀ 이전 역") { controller.stepBack() }.frame(maxWidth: .infinity)
                Button("다음 역 ▶") { controller.stepForward() }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            Button("알림 종료", role: .destructive) { controller.stop() }
                .frame(maxWidth: .infinity)
        }
        .padding()
        .navigationTitle("추적 중")
    }
}
