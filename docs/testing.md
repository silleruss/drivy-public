# 테스트 화면과 핵심 기능 확인

## 테스트 환경과 실행 범위

2026-11-15, Pleos Connect 2.1.0 / API 34 ARM64, 2560×1440px·160dpi 분할 창에서 확인했습니다. 정상 앱과 분리된 `io.github.silleruss.drivy.uitest`를 사용합니다. 테스트 메뉴는 debug·uitest 전용입니다. release에는 표시되지 않습니다.

테스트 APK: [`DRIVY_demo.apk`](https://github.com/silleruss/drivy-public/releases/download/submission-2026-11-15/DRIVY_demo.apk). 설치는 루트 README를 따릅니다. 실제 저장값 변경·초기화가 포함된 검사는 기존 사용자 앱에서 실행하지 않습니다.

## 시연 영상

[핵심 기능 시연 · 2분 13초 · MP4](videos/drivy-demo.mp4)

네루로 촬영한 기능별 앱 화면과 하단 설명만 담았습니다. 프로젝트 소개, 개발 환경, 검사 결과 카드는 넣지 않았습니다. 각 구간에서는 조작 방법과 화면에서 확인하실 결과를 설명합니다. 무음 영상이며 장면 전환과 마지막 화면 정지가 포함되어 있습니다. 러너 구간은 테스트 시계로 시간을 제어했습니다.

| 시작 | 기능 |
| --- | --- |
| 00:00 | 펫 선택 |
| 00:07 | 홈과 인사 |
| 00:14 | 테스트 메뉴 |
| 00:21 | 거리 입력과 성장 계산 |
| 00:28 | 네루의 2단계 성장 |
| 00:35 | 네루의 3단계 성장 |
| 00:42 | 화면 재생성 후 복원 |
| 00:49 | 기록 동의와 목표 설정 |
| 00:56 | 측정 기록 확인 |
| 01:03 | 상황 입력과 목표 회고 |
| 01:10 | 동행 정보와 표시 설정 |
| 01:17 | 주행 제한 화면 |
| 01:24 | 드라이비 런 시작 |
| 01:31 | 점프와 장애물 피하기 |
| 01:38 | 게임 결과와 랭킹 |
| 01:45 | 앱 재실행 후 복원 |
| 01:52 | 실시간 차량 데이터 |
| 01:59 | 작은 펫 창 표시 |
| 02:06 | 작은 펫 창에서 앱으로 복귀 |

플레이어에서 위 시각으로 이동해 확인합니다. GitHub에서 바로 재생되지 않으면 파일을 내려받아 재생합니다.

## 테스트 메뉴로 직접 확인

### T01 합성 거리 계산

- 사전 조건: 주차 상태, 테스트 앱에서 펫 선택 완료.
- 조작: `테스트 → 차량 데이터 값 설정`에서 **이동 거리 0.05km / 이동 시간 10초**를 입력하고 `입력값으로 성장 확인`을 누릅니다.
- 기대 결과: 기본 경험치 **+5.0XP**가 계산되고 실제 저장은 바뀌지 않았다는 안내가 표시됩니다.
- 확인 결과: 합성 계산 안내를 확인했으며, 저장된 XP가 0으로 유지되는 검사가 통과했습니다.
- 저장 영향: **이 계산은 저장하지 않습니다.** 같은 화면 위쪽의 `차량 데이터 값 적용`은 실제 테스트 앱 저장값을 바꾸는 별도 기능입니다.

### T02 성장 단계

- 조작: `테스트 → 성장과 캐릭터`에서 `50000.0 XP 적용`, 이후 `300000.0 XP 적용`을 선택하고 상단 `드라이비`로 돌아갑니다.
- 기대 결과: 각각 2단계와 3단계 외형으로 바뀌며 선택하신 펫은 유지됩니다.
- 확인 결과: 네루의 성장 모습과 저장 파일의 50,000XP·300,000XP가 일치했습니다.
- 저장 영향: 테스트 앱의 실제 성장값을 변경합니다. 실주행 획득 XP가 아닙니다.

### T03 저장과 복원

- 화면 재생성: `테스트 → 드라이비 성장 기록 저장과 복원 → 성장 기록 저장 후 화면 재생성`을 실행합니다.
- 새 프로세스: 테스트 앱을 강제 종료한 뒤 다시 엽니다.

```sh
adb -s emulator-5554 shell am force-stop --user current io.github.silleruss.drivy.uitest
adb -s emulator-5554 shell am start --user current \
  -n io.github.silleruss.drivy.uitest/io.github.silleruss.drivy.MainActivity
```

- 기대 결과: 펫·거리·XP가 유지됩니다. 화면 재생성과 프로세스 재시작은 다른 검사입니다.
- 확인 결과: 화면 재생성 시 300,000XP, 이후 합성 수신으로 5XP를 추가한 새 프로세스에서 네루와 300,005XP가 복원되는 것을 확인했습니다.
- 저장 영향: 기존 테스트 앱 저장을 사용합니다. 손상·저장 실패를 숨기지 않으며 미저장 데이터는 강제 종료 시 유실될 수 있습니다.

### T04 기록 동의·목표·상황·회고

- 조작: 홈 `기록·목표·설정 → 기록·분석에 동의하고 시작`에서 `부드러운 출발`, `시내 위주`를 선택합니다. 유효 표본 수신 후 `최근 기록 상세 보기`에서 `내 기록`, `정체가 있었음`, `도움 됨`을 선택합니다.
- 기대 결과: 관측 사실과 직접 입력한 환경·상황·회고가 구분되어 보존됩니다.
- 확인 결과: 시연에서는 전용 instrumentation으로 거리·속도 표본 3개를 합성 주입해 관측 거리 0.05km·유효 시간 2초를 만들고 실제 화면에서 입력했습니다.
- 저장 영향: 리포트 저장에 동의·기록·사용자 입력을 저장합니다. T01의 저장하지 않는 합성 거리 계산으로 리포트가 만들어지지는 않습니다.
- 삭제 확인: `기록·입력·목표 전체 삭제 → 전체 삭제 확인` 후 기록·입력·동의가 사라지고 펫 진행이 유지되는지 봅니다. 이 경로는 이전 Android 회귀 검사에서 통과했으며 영상에는 삭제 조작을 담지 않았습니다.

### T05 차량 SDK 수신

- 조작: `테스트 → 실시간 차량 데이터`를 열고 Pleos Virtual Console에서 도어·누적계 값을 변경합니다. 수신 시각과 값이 갱신되는지 확인합니다.
- 기대 결과: 도어·누적계는 Pleos SDK, 속도·UX는 AAOS 수신으로 구분됩니다. 실패·미지원을 정상값으로 표시하지 않습니다.
- 이번 확인: 실제 에뮬레이터 콜백으로 속도 0km/h·누적계 0km·도어 0을 확인했고 운행시간은 `unsupported`였습니다. 이 수치는 마지막 콜백이며 최신 상태를 보장하지 않습니다.
- **보류:** 현재 Virtual Console API는 `emulator_inactive`를 반환해 값 변경 전후 비교를 완료하지 못했습니다. 0 수신만으로 값 변경 검사를 통과 처리하지 않습니다.
- 저장 영향: 실제 유효 거리 증가가 들어오면 테스트 앱 성장과 동의한 리포트에 반영될 수 있습니다.

### T06 주행 제한

- 조작: 지원되는 Console의 기어·속도 제어로 실제 Car UX 제한을 발생시키고 주차 상태로 복귀합니다.
- 기대 결과: 제한 상태에서 게임·펫·리포트·테스트 조작을 사용할 수 없고, 허용 상태로 돌아온 뒤 다시 이용할 수 있습니다.
- 이번 영상: 전용 instrumentation에서 **합성 UX 입력**으로 차단 경로를 검사했습니다. 실제 차량·Console의 제한 전환을 검증한 영상은 아닙니다.
- 저장 영향: 제한 전환에서 기록 구간이 종료될 수 있으며 미관측 구간을 복원하지 않습니다.

### T07 작은 펫 창

- 사전 조건: 주차 상태, `자세히`의 표시 설정 및 다른 앱 위에 표시 권한 허용.
- 조작: `테스트 → 화면 위 드라이비 → 드라이비 띄우기`를 선택하고, 표시된 펫을 눌러 앱으로 돌아옵니다.
- 기대·확인 결과: 실제 시스템 오버레이 창 표시와 터치 복귀를 별도 프로세스 검사에서 확인했습니다. 작은 네루 창은 다른 화면 위에 표시됩니다.
- 저장 영향: 창 표시·위치 설정을 저장합니다. 자동 시연은 테스트 앱 권한을 허용한 뒤 검사 종료 시 기본값으로 되돌립니다. OEM별 허용 범위는 별도입니다.

### T08 드라이비 런

- 조작: 홈 `드라이비 런 → 시작`, 화면의 `점프` 또는 스페이스바를 사용합니다.
- 기대 결과: 장애물과 코인, 점수를 확인하실 수 있으며 게임 종료 후 기기 내 랭킹에 기록이 저장됩니다.
- 확인 결과: 자동 시연에서 시작·점프·종료를 검사했고, 테스트 앱의 랭킹 파일에 네루의 기록이 저장되는 것을 확인했습니다.
- 저장 영향: 러너 랭킹 파일을 변경하며 성장 XP에 게임 결과를 추가하지 않습니다.

## 자동 검사

단위 검사와 빌드·lint:

```sh
./Android/gradlew -p Android :app:testUitestUnitTest :app:assembleDebug \
  :app:assembleRelease :app:assembleUitest :app:assembleUitestAndroidTest \
  :app:lintDebug :app:lintRelease --max-workers=2
```

펫 선택·홈·리포트·테스트 메뉴 회귀 검사:

```sh
./Android/gradlew -p Android :app:connectedUitestAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.silleruss.drivy.CompanionSetupTest,io.github.silleruss.drivy.CompanionScreenTest,io.github.silleruss.drivy.TestControlsScreenTest,io.github.silleruss.drivy.DriveReportsPanelTest
```

화면 자료를 재현하는 전용 시연 검사입니다. **별도 테스트 앱의 펫·리포트를 초기화합니다.** 아래 사용자 번호 `10`은 `adb shell am get-current-user` 결과로 바꿉니다. 두 메서드는 순서대로 각각 실행하여 두 번째 실행의 새 프로세스 복원을 확인합니다.

```sh
adb install -r Android/app/build/outputs/apk/uitest/app-uitest.apk
adb install -r Android/app/build/outputs/apk/androidTest/uitest/app-uitest-androidTest.apk
adb shell pm grant --user 10 io.github.silleruss.drivy.uitest android.car.permission.CAR_SPEED
adb shell am instrument --user 10 -w -r -e submission_demo true \
  -e class 'io.github.silleruss.drivy.SubmissionDemoTest#captureCoreFeatures' \
  io.github.silleruss.drivy.uitest.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument --user 10 -w -r -e submission_demo true \
  -e class 'io.github.silleruss.drivy.SubmissionDemoTest#captureNewProcessAndLiveStatus' \
  io.github.silleruss.drivy.uitest.test/androidx.test.runner.AndroidJUnitRunner
```

전용 검사의 캡처는 테스트 앱 내부 `files/submission-demo/`에 생성됩니다. 일반 전체 검사에서는 `submission_demo=true`가 없으면 시연 검사를 건너뜁니다. 기존 테스트 중에는 특정 격리 패키지·fixture가 필요한 검사도 있어, 선택한 검사 결과를 전체 기기 검사의 통과로 확대하지 않습니다.

## 이번 검증 결과

| 항목 | 결과 |
| --- | --- |
| JVM 단위 검사 | 72개, 실패 0 |
| 펫 선택·홈·리포트·테스트 메뉴 검사 | 19개 통과 |
| 핵심 시연 검사 | 1개 통과 |
| 새 프로세스·실제 작은 창 검사 | 1개 통과 |
| debug / uitest / unsigned release 빌드 | 성공 |
| debug / release lint | 오류 0, 각각 경고 19 |
| 정상 앱 펫 저장 | 검사 전후 SHA-256 동일 |
| 제출 release 서명 | v3 서명과 인증서 검증 완료. 독립 설치·복원 검증은 미완료 |
| 실차·OEM·최신 버전 장시간 운용 | 미검증 |

최초 핵심 시연에서는 자동 테스트 시계가 게임을 진행시켜 점프 전에 게임이 끝났습니다. 시연의 러너 구간만 시간을 명시적으로 제어하도록 고친 뒤 재검사했습니다. 작은 창 복귀 검사에서는 Activity 복귀 중 Compose 루트가 아직 없는 순간을 기다리지 못했으며, 루트가 나타날 때까지 기다리도록 검사 코드를 고친 뒤 통과했습니다. 제품의 제한·검증 로직은 변경하지 않았습니다.
