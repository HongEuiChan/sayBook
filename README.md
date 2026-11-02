# 지하철 역알림 (SubwayAlert)

지하철에서 영상을 보는 동안 **정차하는 역을 영상 위에 알려주는 앱**입니다.
Kotlin Multiplatform(KMP)으로 감지 로직을 공유하고, UI는 Android(Compose)·iOS(SwiftUI) 네이티브로 구현했습니다.

지하에서는 GPS가 잡히지 않으므로 **가속도 센서만으로** 열차의 출발/정차를 감지해 역을 하나씩 셉니다.

## 동작 방식

```
 휴대폰 센서 (중력 분리된 가속도 + 중력 벡터)
        │  Android: TYPE_LINEAR_ACCELERATION + TYPE_GRAVITY
        │  iOS:     CMDeviceMotion.userAcceleration + gravity
        ▼
 ┌──────────────── shared (KMP, commonMain) ────────────────┐
 │ TrainMotionDetector  진동 RMS + 평활 수평가속도 → 상태머신 │
 │      STOPPED → DEPARTING → RUNNING → (Arrived) STOPPED    │
 │                               └→ HALTED (신호대기)         │
 │ TripTracker          이벤트 → 현재 역/다음 역 진행         │
 │ StationAlertSession  플랫폼이 쓰는 진입점 + 리스너          │
 │ AlertTexts           두 플랫폼 공통 알림 문구              │
 │ SubwayLines          노선/역 데이터 (2·3·4호선)            │
 │ RideSimulator        합성 센서 데이터 (테스트·데모 모드)   │
 └───────────────────────────────────────────────────────────┘
        ▼
 Android: 포그라운드 서비스 + 다른 앱 위 오버레이(드래그 가능) + 알림 + 진동
 iOS:     Live Activity(Dynamic Island·잠금화면) + 로컬 알림
```

### 감지 알고리즘 요약
| 특징값 | 의미 |
|---|---|
| 진동 RMS (2초 창) | 달리는 열차는 레일 진동으로 계속 흔들림. 정차 중엔 매우 작음 |
| 평활 수평 가속도 (τ=1.5초) | 출발/제동 때 한 방향으로 수 초간 이어지는 가·감속(≈0.9 m/s²). 손떨림은 방향이 무작위라 평활화하면 상쇄됨 |

- 움직임이 **8초** 이어지면 출발 확정 → "다음 역 · OO"
- 정지가 **5초** 이어지고 출발 후 **40초** 이상 달렸으면 정차 확정 → "이번 역 · OO" (진동 + 강조)
- 40초 미만에서 멈추면 신호대기로 보고 역을 세지 않음
- 진동 RMS가 매우 크면(걷는 중) 열차 움직임으로 치지 않음
- 정차 중 손으로 폰을 잠깐 움직이는 건 2초 정지로 취소됨

모든 임계값은 `DetectorConfig` 에 모여 있습니다. **실제 탑승 데이터로 꼭 보정하세요.** 추적 화면 하단에 실시간 특징값(진동/가감속/분류)이 표시됩니다.

감지는 완벽할 수 없으므로(역 사이 장시간 정차, 급정거 등) 오버레이/알림/앱 화면에 **◀ 이전 역 / 다음 역 ▶ 보정 버튼**을 두었습니다.

## 프로젝트 구조

```
shared/                       KMP 공유 모듈 (android, ios, jvm 타깃)
  src/commonMain/.../detect   센서 감지 상태머신
  src/commonMain/.../track    역 추적, 세션, 알림 문구
  src/commonMain/.../model    노선/역 데이터
  src/commonMain/.../sim      센서 시뮬레이터
  src/commonTest              감지·추적 테스트
androidApp/                   Android 앱 (Compose, 포그라운드 서비스, 오버레이)
iosApp/                       iOS 앱 (SwiftUI) + StationLiveActivity 위젯 확장
  project.yml                 XcodeGen 프로젝트 정의
```

## 빌드

### 공유 모듈 테스트
```bash
./gradlew :shared:jvmTest          # 기기 없이 감지 알고리즘 테스트
```

### Android
Android Studio에서 루트 폴더를 열고 `androidApp` 실행, 또는
```bash
./gradlew :androidApp:installDebug
```
첫 실행 시 **'다른 앱 위에 표시'** 권한을 허용하면 영상 위에 역 이름이 뜹니다. 허용하지 않으면 헤드업 알림으로 대신 표시합니다.

### iOS (macOS + Xcode 15 이상)
```bash
brew install xcodegen
cd iosApp && xcodegen generate
open SubwayAlert.xcodeproj
```
- `project.yml` 의 `DEVELOPMENT_TEAM` 에 본인 팀 ID를 넣으세요.
- 빌드 시 pre-build 스크립트가 `./gradlew :shared:embedAndSignAppleFrameworkForXcode` 로 Kotlin 프레임워크(`Shared`)를 만듭니다.
- Dynamic Island는 iPhone 14 Pro 이상, Live Activity는 iOS 16.2 이상.

### 데모 모드
두 앱 모두 설정 화면의 **데모 모드** 스위치를 켜면 실제 지하철 없이 합성 센서 데이터(6개 역, 10배속)로 전체 흐름을 볼 수 있습니다.

## 플랫폼별 제약

| | Android | iOS |
|---|---|---|
| 영상 위 표시 | 오버레이 창 (항상 보임, 위치 이동 가능) | Dynamic Island / 알림 배너 (다른 앱 위에 임의의 창은 불가) |
| 백그라운드 센서 | 포그라운드 서비스로 계속 동작 | 앱이 백그라운드로 가면 센서가 멈추므로, 백그라운드 위치 모드로 앱을 유지 (위치 값은 사용 안 함) |
| 스토어 심사 | `specialUse` 포그라운드 서비스 사유 기재 필요 | 백그라운드 위치 사용 목적 설명 필요. 심사에서 반려될 수 있으니 대안(예: 실시간 열차위치 API 결합) 검토 권장 |

## 다음 단계 제안
- 실제 탑승 로그를 저장해 `DetectorConfig` 보정 (특징값 CSV 기록 기능)
- 노선 데이터 확장(공공데이터 기반 전체 노선, 지선, 환승 정보)
- 역간 표준 소요시간을 이용해 신호대기와 정차를 더 정확히 구분
- 목적지 설정 후 N정거장 전 경고, 도착 시 영상 일시정지
