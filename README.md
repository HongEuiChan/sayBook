# 지하철 역알림 (SubwayAlert)

지하철에서 영상을 보는 동안 **정차하는 역을 영상 위에 알려주는 앱**입니다.
Kotlin Multiplatform(KMP)으로 감지 로직을 공유하고, UI는 Android(Compose)·iOS(SwiftUI) 네이티브로 구현했습니다.

**핵심 목표: OTT 영상에 몰입해 있어도 지금 어느 역에 도착했는지 영상 위에서 바로 알 수 있게 하기.**

- 역에 서면 화면 위쪽 가운데에 **큰 역 이름 배너**가 노선색으로 6초간 뜨고 진동이 울립니다 (Android 오버레이 / iOS 알림 배너·Dynamic Island).
- 평소에는 작은 표시(드래그로 이동 가능)에 "이번 역 / 다음 역"이 계속 보입니다.

지하 터널에서는 GPS가 잡히지 않으므로 **가속도 센서**로 열차의 출발/정차를 감지해 역을 하나씩 셉니다.
그리고 한국 지하철은 **역사 안에서 GPS·와이파이 위치가 잠깐 잡히는 경우가 많아** 이를 보정 신호로 함께 씁니다.

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
 │ SubwayLines          노선/역 데이터 (2·3·4호선, 신분당선)  │
 │ RideSimulator        합성 센서 데이터 (테스트·데모 모드)   │
 └───────────────────────────────────────────────────────────┘
        ▼
 Android: 포그라운드 서비스 + 다른 앱 위 오버레이(드래그 가능) + 알림 + 진동
 iOS:     Live Activity(Dynamic Island·잠금화면) + 로컬 알림
```

### 감지 알고리즘 요약
실제 탑승 기록(신분당선 광교중앙→강남, 손에 들고 영상 시청)으로 보정했습니다.

| 특징값 | 의미 |
|---|---|
| 고역통과 진동 RMS (2초 창, 약 5Hz 이상) | 레일 진동만 남기고 손 움직임(저주파)은 걸러냄. 실측: 주행 0.2~0.5, 정차 0.03~0.13 m/s² |
| 걷기 세기 | 진동 RMS 1.2 이상은 걷는 중. 2.0 이상이 6초 이어지면 '내렸다' |

- 진동이 0.20 이상으로 **8초** 이어지면 출발 → "다음 역 · OO"
- 0.10 미만으로 **5초** 이어지면 정차 → "이번 역 · OO" (큰 배너 + 진동)
- 출발 후 **20초 안에** 다시 멈추면 출발 오탐(정차 중 승하차·휴대폰 움직임)으로 보고 취소 → 계속 "이번 역"
- 출발 후 20~40초 사이에 멈추면 신호대기 (역으로 세지 않음)
- 주행 중 한참 걸으면 내린 것 = 정차 (목적지에서 서자마자 일어나 나가는 경우)
- 걸은 뒤 15초 동안은 출발로 판정하지 않음 (내려서 걸어가는 중 오탐 방지)

**실측 결과 (신분당선 11개 역):** 기존 로직 0/11 → 개선 후 **11/11 정답, 오탐 0** (센서만으로도 11/11).
한 번의 탑승으로 맞춘 값이므로 다른 노선·자세(주머니, 서서 탑승)로 기록을 더 모아 검증해야 합니다.

### 실제 탑승 기록으로 검증
`recordings/` 폴더(git 제외)에 기록 `X.csv` 와 정답 `X.truth`(`역이름,정차시작초,정차끝초`)를 두고
`./gradlew :shared:jvmTest` 를 실행하면 `RecordedRideTest` 가 재생·채점합니다.
**기록에는 위치와 시간이 들어 있으니 공개 저장소에 올리지 마세요.**

### GPS 보정 (역사에서 잠깐 잡히는 위치)
| 상황 | 동작 |
|---|---|
| 45초 이상 끊겼던 위치가 다시 잡힘 (= 역사에 들어옴) | 열차가 1초만 멈춰도 바로 정차 확정 (센서만이면 5초). 역간 거리가 짧아 신호대기로 오판한 정차도 도착으로 바로잡음 |
| 지상 구간처럼 위치가 계속 잡힘 | 역 신호로 쓰지 않음 (센서 감지만 사용) |
| 정확도 200m 보다 나쁜 위치 (기지국 기반) | 무시 |
| 역 좌표가 있고 위치가 특정 역과 맞음 | 이전 역~다다음 역 범위에서 현재 역을 자동 보정 (센서가 놓친 정차 복구) |

> ⚠️ 역 좌표는 신분당선 6개 역(실측)에만 있습니다 (`Station.latitude/longitude`). 좌표 없이도 '위치 재등장' 신호는 동작하며,
> 공공데이터(서울교통공사 역 좌표 등)로 좌표를 채우면 역 자동 보정까지 켜집니다.

모든 임계값은 `DetectorConfig` / `LocationConfig` 에 모여 있습니다. **실제 탑승 데이터로 꼭 보정하세요.** 추적 화면 하단에 실시간 특징값(진동/가감속/분류)이 표시됩니다.

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
| 영상 위 표시 | 작은 표시 + 도착 시 큰 배너 오버레이 (터치는 영상 앱으로 통과) | 알림 배너(Time Sensitive) + Dynamic Island. 다른 앱 위에 임의의 창은 불가하며, 전체화면 영상에서는 Dynamic Island가 숨겨질 수 있어 배너가 주 수단 |
| 백그라운드 센서 | 포그라운드 서비스로 계속 동작 | 앱이 백그라운드로 가면 센서가 멈추므로, 백그라운드 위치 모드로 앱을 유지 (위치 값은 사용 안 함) |
| 스토어 심사 | `specialUse` 포그라운드 서비스 사유 기재 필요 | 백그라운드 위치 사용 목적 설명 필요. 심사에서 반려될 수 있으니 대안(예: 실시간 열차위치 API 결합) 검토 권장 |

## 다음 단계 제안
- 실제 탑승 로그를 저장해 `DetectorConfig` 보정 (특징값 CSV 기록 기능)
- 노선 데이터 확장(공공데이터 기반 전체 노선, 지선, 환승 정보)
- 역간 표준 소요시간을 이용해 신호대기와 정차를 더 정확히 구분
- 목적지 설정 후 N정거장 전 경고, 도착 시 영상 일시정지
