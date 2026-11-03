import ActivityKit
import CoreLocation
import CoreMotion
import Foundation
import Shared
import UserNotifications

/// 센서 → 공유 Kotlin 로직(StationAlertSession) → Live Activity / 알림 연결.
///
/// Kotlin 세션은 단일 스레드 전제이므로 모든 세션 호출은 `sensorQueue` 에서만 합니다.
@MainActor
final class TrackingController: NSObject, ObservableObject {
    @Published private(set) var state: TrackerState?

    private let motion = CMMotionManager()
    private let sensorQueue = DispatchQueue(label: "station.sensor")
    private lazy var motionQueue: OperationQueue = {
        let q = OperationQueue()
        q.maxConcurrentOperationCount = 1
        q.underlyingQueue = sensorQueue
        return q
    }()

    /// 위치 업데이트는 두 가지 역할:
    /// 1. iOS 는 백그라운드 앱의 센서 수집을 멈추므로, 영상 앱을 보는 동안 앱을 살려 둠 (백그라운드 위치 모드)
    /// 2. 한국 지하철 역사에서 잠깐 잡히는 GPS/와이파이 위치를 정차 보정 신호로 사용
    private let keepAlive = CLLocationManager()

    private var session: StationAlertSession?
    private var activity: Activity<StationActivityAttributes>?
    private var demoTimer: DispatchSourceTimer?

    override init() {
        super.init()
        keepAlive.delegate = self
        keepAlive.desiredAccuracy = kCLLocationAccuracyBest
        keepAlive.distanceFilter = kCLDistanceFilterNone
        keepAlive.pausesLocationUpdatesAutomatically = false
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    var isTracking: Bool { state != nil }

    func start(line: SubwayLine, startIndex: Int, direction: Direction, demo: Bool) {
        stop()
        let newSession = StationAlertSession(line: line, startIndex: Int32(startIndex), direction: direction)
        session = newSession
        state = newSession.state
        startLiveActivity(newSession.state)

        sensorQueue.async { [weak self] in
            newSession.setListener { update in
                Task { @MainActor in self?.handle(update, from: newSession) }
            }
        }

        if demo {
            startDemo(newSession)
        } else {
            startMotion(newSession)
            keepAlive.requestWhenInUseAuthorization()
            keepAlive.allowsBackgroundLocationUpdates = true
            keepAlive.showsBackgroundLocationIndicator = true
            keepAlive.startUpdatingLocation()
        }
    }

    func stop() {
        motion.stopDeviceMotionUpdates()
        keepAlive.stopUpdatingLocation()
        demoTimer?.cancel()
        demoTimer = nil
        if let s = session { sensorQueue.async { s.setListener(listener: nil) } }
        session = nil
        state = nil
        endLiveActivity()
    }

    func stepForward() {
        guard let s = session else { return }
        sensorQueue.async { s.stepForward() }
    }

    func stepBack() {
        guard let s = session else { return }
        sensorQueue.async { s.stepBack() }
    }

    // MARK: - 위치

    fileprivate func feed(_ locations: [CLLocation]) {
        guard let s = session else { return }
        for loc in locations where loc.horizontalAccuracy >= 0 && -loc.timestamp.timeIntervalSinceNow < 10 {
            let t = Int64(loc.timestamp.timeIntervalSince1970 * 1000)
            let lat = loc.coordinate.latitude
            let lon = loc.coordinate.longitude
            let acc = loc.horizontalAccuracy
            sensorQueue.async { s.onLocation(timestampMs: t, latitude: lat, longitude: lon, accuracyM: acc) }
        }
    }

    // MARK: - 센서

    private func startMotion(_ session: StationAlertSession) {
        guard motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 50.0
        // userAcceleration / gravity 는 g 단위 → m/s² 로 변환
        let g = 9.81
        motion.startDeviceMotionUpdates(to: motionQueue) { data, _ in
            guard let m = data else { return }
            session.onMotion(
                timestampMs: Int64(m.timestamp * 1000),
                userX: m.userAcceleration.x * g, userY: m.userAcceleration.y * g, userZ: m.userAcceleration.z * g,
                gravityX: m.gravity.x * g, gravityY: m.gravity.y * g, gravityZ: m.gravity.z * g
            )
        }
    }

    /// 합성 센서 데이터를 10배속으로 재생 (실제 탑승 없이 흐름 확인용)
    private func startDemo(_ session: StationAlertSession) {
        let simulator = RideSimulator(seed: Int32.random(in: 0..<10_000), sampleRateHz: 50)
        let segments = RideSimulator.companion.typicalRide(stations: 6, dwellMs: 25_000)
        let samples = simulator.generate(segments: segments, startMs: 0)
        var cursor = 0
        let perTick = 50  // 100ms 마다 50샘플(=1초 분량) → 10배속

        let timer = DispatchSource.makeTimerSource(queue: sensorQueue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(100))
        timer.setEventHandler { [weak timer] in
            let end = min(cursor + perTick, samples.count)
            for i in cursor..<end { session.onSample(sample: samples[i]) }
            cursor = end
            if cursor >= samples.count { timer?.cancel() }
        }
        demoTimer = timer
        timer.resume()
    }

    // MARK: - 표시

    private func handle(_ update: TrackerUpdate, from source: StationAlertSession) {
        guard source === session else { return }
        state = update.state
        updateLiveActivity(update)

        if let alert = update.alert, alert.important {
            postNotification(alert)
        }
        if update.state.phase == .finished, update.alert != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
                guard let self, self.session === source else { return }
                self.stop()
            }
        }
    }

    private func contentState(_ s: TrackerState) -> StationActivityAttributes.ContentState {
        .init(
            title: AlertTexts.shared.compactTitle(s: s),
            subtitle: AlertTexts.shared.compactSubtitle(s: s),
            atStation: s.phase == .atStation || s.phase == .finished,
            stationsPassed: Int(s.stationsPassed)
        )
    }

    private func startLiveActivity(_ s: TrackerState) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = StationActivityAttributes(
            lineName: s.line.name,
            directionLabel: s.directionLabel,
            lineColorHex: Int(s.line.colorHex)
        )
        activity = try? Activity.request(
            attributes: attributes,
            content: ActivityContent(state: contentState(s), staleDate: nil),
            pushType: nil
        )
    }

    private func updateLiveActivity(_ update: TrackerUpdate) {
        guard let activity else { return }
        let content = ActivityContent(state: contentState(update.state), staleDate: nil)
        // 중요한 알림(정차)이면 Dynamic Island 가 펼쳐지며 알려줌
        let alertConfig: AlertConfiguration? = update.alert.flatMap { alert in
            alert.important
                ? AlertConfiguration(title: "\(alert.title)", body: "\(alert.body)", sound: .default)
                : nil
        }
        Task { await activity.update(content, alertConfiguration: alertConfig) }
    }

    private func endLiveActivity() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    private func postNotification(_ alert: StationAlert) {
        let content = UNMutableNotificationContent()
        content.title = alert.title
        content.body = alert.body
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let request = UNNotificationRequest(identifier: "station-alert", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

extension TrackingController: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in self.feed(locations) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 지하에서는 위치 실패가 정상. 무시하고 계속 대기
    }
}
