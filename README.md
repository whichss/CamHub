# CamHub Studio

여러 대의 Android 스마트폰을 카메라로 사용하고, 한 대의 Android 기기에서 멀티뷰·PVW/PGM 스위칭·오디오·녹화를 처리하는 저지연 모바일 프로덕션 앱입니다.

CamHub는 특정 Galaxy 모델에 맞춘 앱이 아닙니다. 실행 기기의 코덱, 메모리, CPU, OpenGL ES 성능과 실시간 발열·지연을 측정해 처리량을 자동 조절하도록 설계합니다.

> 현재 개발 단계의 실험용 프로젝트입니다. 실제 촬영 전에는 사용할 기기와 네트워크 조합으로 안정성·발열·저장 공간을 반드시 확인하세요.

```text
Camera 1 ─┐
Camera 2 ─┼─ Wi-Fi 5/6 AP ────────────┐
Camera 3 ─┤                            ├─ Hub ── PGM recording / external display
Camera 4 ─┘                 USB-C LAN ─┘
```

## 설계 목표

- 최대 4대의 카메라 동시 연결
- 기본 720p30 입력과 선택된 PGM의 1080p30 공간 업스케일링
- Wi-Fi 5 5GHz와 Wi-Fi 6 지원, Wi-Fi 6 필수 아님
- 전체 무선 또는 허브의 USB-C Ethernet 연결 지원
- UDP/RTP 우선 저지연 전송과 SRT/TCP 자동 폴백
- 늦은 패킷 재전송보다 프레임 마감시간 기반 폐기 우선
- 평균값이 아닌 P50/P95/P99 지연과 파이프라인 단계별 측정
- 제조사나 특정 기종이 아닌 실행 기기 능력 기반 자동 최적화

50ms는 방향성 있는 목표이며 모든 조합에서 보장되는 수치가 아닙니다. 실제 지연은 카메라 노출·센서 읽기, 인코딩, 네트워크, 디코딩, 합성, 디스플레이 주사까지 단계별로 달라집니다.

## 현재 구현

### 데스크톱 허브 기반

- Windows와 macOS에서 같은 코드로 실행되는 별도 JVM 데스크톱 모듈
- 연결된 모니터의 이름·해상도·주사율 탐지
- 선택한 모니터에 테두리 없는 PGM 전체화면 출력
- 모니터 위에서 드래그해 선택한 화면 영역을 PVW에서 확인한 뒤 CUT/AUTO로 PGM에 송출
- 화면 입력은 카메라 4개 슬롯과 독립된 PGM 소스로 관리하고, 같은 모니터 캡처·출력 피드백을 차단
- 선택 화면을 독립 CamHub 카메라 소스로 광고하고, Android 허브의 TLS 지문/PIN 페어링과 UDP/RTP 구독 수락
- 화면 캡처·최신 프레임 인코딩·암호화 UDP 송신을 각각 분리하고, 대기열 적체 시 키프레임부터 복구
- 현재 PGM을 OBS Browser Source에 전달하는 loopback MJPEG 출력(최대 1080p, 15fps)과 로컬 `/healthz` 상태 조회
- `ESC` 전체화면 종료와 출력 중 모니터 분리 감지
- Android와 데스크톱이 함께 사용하는 RTP 재조립·AES-256-GCM 공통 모듈
- 최대 4대 멀티뷰와 분리된 PVW/PGM 버스, 카메라·화면 입력 선택, CUT 전환
- 300ms 페이드-블랙 AUTO 전환을 데스크톱 전체화면 PGM과 OBS 출력에 동일 적용
- 데스크톱 PGM을 H.264 MP4로 저장하고 AUTO 페이드 결과까지 녹화
- 녹화는 전용 인코딩·파일 I/O 스레드와 최신 프레임 1개 대기열을 사용해 UI·수신 경로의 적체를 제한
- Android 카메라의 mDNS·UDP 이중 검색 목록과 `REFRESH`, `CONNECT`, `CONNECT ALL`, `DISCONNECT`
- 자동 검색이 제한된 네트워크를 위한 숨김형 `IP:포트` 수동 연결
- 카메라 화면으로 이동한 뒤에도 검색 광고와 제어 서버 유지
- 자체 서명 TLS 제어 핸드셰이크, 세션 키 수신, 암호화 UDP/RTP 구독
- 카메라별 수신·프레임 마감·FFmpeg H.264 디코딩 전용 스레드
- 첫 키프레임이 도착한 뒤에만 FFmpeg 디코더를 초기화해, 영상이 없는 카메라의 대기가 다른 인코더·녹화를 막지 않도록 처리
- 데스크톱 디코더 입력을 2프레임으로 제한하고 적체 시 다음 키프레임부터 복구
- 컴퓨터 도착 이후 180ms를 넘긴 디코더 출력 폐기와 저지연 FFmpeg 옵션
- 멀티뷰 선택으로 PVW 구도 확인 후 CUT/AUTO로 PGM 지정, 모니터 전체화면 공간 확대
- 프레임 상태 UI를 초당 4회로 제한하고 영상 표시는 최신 이미지로 교체
- 핑/퐁 시계 보정 이후에만 카메라 촬영부터 데스크톱 표시까지의 지연 계산
- 카메라 연결 단절 감지와 지수 백오프 자동 재연결

현재 데스크톱 단계는 검색 목록에서 선택한 휴대폰 한 대 또는 최대 네 대를 연결하고 실영상을 멀티뷰, PVW/PGM, PGM 전체화면에 표시하고 PGM을 H.264 MP4로 녹화할 수 있습니다. 녹화 입력은 PGM 버스이므로 AUTO 전환을 포함하며, 녹화 처리는 제한된 최신 프레임 대기열과 독립 작업 스레드를 사용합니다. AUTO는 300ms 페이드-블랙 전환입니다. 현재 PGM 확대는 데스크톱 그래픽 파이프라인의 bicubic 공간 필터를 사용합니다. VideoToolbox/Media Foundation 하드웨어 디코딩과 GPU FSR 샤프닝은 다음 단계입니다.

화면 영역은 로컬 PGM 입력과 CamHub 카메라 소스로 선택할 수 있습니다. 데스크톱 카메라 소스와 기존 Android 허브 간 인증·송신 경로는 코드로 연결했습니다. macOS arm64 앱 ZIP과 Windows x64 설치 파일은 GitHub Actions에서 생성 및 패키지 검증을 통과했습니다. 앱 실행은 하지 않았으므로 화면 캡처 권한, 설치 후 동작과 실기기 상호운용은 별도 확인이 남아 있습니다. OBS 연결은 네이티브 플러그인이 아니라 기본 Browser Source용 로컬 MJPEG URL입니다. 현재 iOS TCP 송신 변경을 포함한 arm64 기기용 unsigned Debug·Release `.app` 빌드가 성공했으며, 서명·설치·실기기 송출과 상호운용 검증은 남아 있습니다. 시뮬레이터 검증은 이 Mac의 CoreSimulator 버전이 Xcode와 맞지 않아 진행하지 못했습니다.

### iOS 카메라 소스 (arm64 기기용 unsigned Debug/Release 빌드 확인)

- SwiftUI 카메라 화면과 720p30 H.264 VideoToolbox 인코딩
- 로컬 Bonjour 검색 광고와 TLS 지문 표시·8자리 PIN 페어링
- CamHub 제어 메시지와 AES-256-GCM으로 보호된 UDP/RTP 영상 송신
- 실제 영상 방향 메타데이터 반영 및 허브 구독 중에만 인코딩 수행
- 지원 기기에서는 1080p30 소스 프레임을 720p30으로 크롭·축소해 전송하며, 원격 PTZ 크롭은 인코딩 전에 적용
- 같은 VideoToolbox H.264 인코딩 출력을 MOV 파일로 저장하는 카메라 로컬 녹화와 공유 내보내기
- 촬영 해상도에 추가 픽셀이 없는 기기는 원격 PTZ를 광고하지 않고 허브 크롭으로 처리
- 네트워크가 인코더보다 느릴 때 오래된 영상이 쌓이지 않도록 송신 대기열 제한
- IPv4-mapped/native IPv6 UDP 목적지와 링크 로컬 주소 scope 처리를 구현했고, 공통 wire fixture로 프로토콜 형식을 검증
- 인증 토큰으로 허브를 확인하는 AES-GCM TCP 영상 폴백 구현 및 loopback 스모크 검증 완료
- SRT 폴백, 현재 변경을 포함한 iOS 앱 전체 재빌드, 서명·설치 및 실기기 상호운용 검증은 미완료

### 카메라 모드

- CameraX + Camera2 Interop 기반 촬영
- 연속 AF, 탭 포커스, 자동/수동 ISO·셔터·포커스
- 전면/후면 카메라 전환
- 가로 16:9 및 세로 9:16 스트림 방향 처리
- 핀치 줌, 드래그 이동, 더블 탭 확대
- 중앙 복귀형 속도 줌 컨트롤
- MediaStore 기반 카메라 로컬 녹화
- 세로·가로 전용 HUD 레이아웃

### 허브 모드

- 최대 4개 카메라 멀티뷰
- PVW에서 구도를 정한 뒤 CUT/AUTO로 PGM 전환
- PGM 직접 조작을 막는 LIVE PTZ 잠금
- 카메라별 연결·재연결·오디오 상태 표시
- PGM 단독 허브 녹화와 일시정지/재개
- 선택적으로 카메라별 로컬 녹화를 원격 시작/정지하는 ISO ARM
- 외부 디스플레이 PGM 출력

### 혼합형 PTZ

카메라마다 `zoom`, `centerX`, `centerY`, `ptzMode`를 독립적으로 유지합니다.

1. 허브 제스처 직후 OpenGL에서 임시 확대해 즉각 반응합니다.
2. 원격 PTZ가 가능한 카메라는 Camera2 줌/크롭 명령을 적용합니다.
3. 원격 적용 확인과 새 프레임 도착 후 허브 임시 크롭을 해제합니다.
4. 원격 PTZ를 지원하지 않으면 허브 크롭을 그대로 유지합니다.

### 영상 전송

- CameraX → SurfaceTexture → OpenGL ES → MediaCodec H.264 Surface 인코딩
- 뷰파인더와 인코더 Surface로 동일 프레임을 렌더링하는 듀얼 출력
- 카메라별 전용 UDP 송신 스레드와 허브의 카메라별 UDP 수신 스레드
- RTP 스타일 조각화, 순서가 뒤섞인 패킷 재조립
- 기본 35ms 프레임 마감시간 이후 불완전 프레임 폐기
- 재전송 없는 최신 프레임 우선 큐와 키프레임/SPS·PPS 보호
- UDP 첫 프레임 및 정지 감시 후 SRT/TCP 폴백
- AES-256-GCM 프레임 암호화

### 네트워크 자동 선택

- mDNS 및 UDP 알림으로 같은 로컬 네트워크의 카메라 자동 검색
- TLS 제어 채널에서 세션 키 교환
- 카메라 인증서 지문을 기기 화면끼리 비교한 뒤 고정하고, 카메라 PIN 검증 전에는 세션 키를 전달하지 않음
- 이 페어링 핸드셰이크를 지원하는 CamHub Android/desktop 버전끼리 연결해야 함
- `AUTO`, `WI-FI FIXED`, `LAN FIXED` 선택 모드
- AUTO에서는 목적지 경로가 유효한 Ethernet을 우선하고 Wi-Fi를 대안으로 사용
- 선택한 Android `Network`에 제어·영상·오디오 소켓을 함께 고정
- 연결 단절 시 마지막 프레임을 표시하고 지수 백오프로 재검색·재연결

셀룰러 통신망은 현재 로컬 영상 경로에서 의도적으로 제외합니다. 인터넷을 경유하지 않는 같은 LAN 환경을 기본 전제로 합니다.

### 자동 품질 및 기기 부하 관리

- 카메라별 자동 비트레이트(AIMD)
- UDP 손실률, 마감시간 폐기, P95/P99 지연, 실제 수신 FPS 기반 판단
- 혼잡 시 빠른 감소, 안정 구간에서 느린 단계적 회복
- 사용자가 정한 비트레이트는 상한값으로 유지
- 코덱 동시 디코딩 수, 메모리, CPU 코어, OpenGL ES 능력 기반 허브 등급 분류
- 발열·드롭·실제 수신 FPS·지연에 따른 런타임 프로필 조절
- 카메라별 전용 디코딩 스레드를 사용하고 PGM은 30fps를 유지
- PVW는 10~20fps, 나머지 멀티뷰는 5~15fps 범위에서 연결 대수·지연·발열에 따라 자동 조절

자동 조절값은 카메라의 저장 설정을 덮어쓰지 않으며 현재 세션에만 적용됩니다. 신규 설치에서는 자동 허브 프로필과 자동 비트레이트가 기본 활성화되고, 사용자가 직접 끈 설정은 유지됩니다.

### 지연 진단

프레임 메타데이터와 카메라-허브 시계 오프셋을 이용해 다음 값을 추적합니다.

- CAP → ENC: 촬영부터 인코딩 완료
- ENC → RX: 네트워크 전송
- RX → DEC: 디코딩
- DEC → READY: 렌더 준비
- READY → DRAW: 허브 화면 표시
- 외부 디스플레이까지의 sink draw 지연
- 카메라별 P50/P95/P99, 수신 비트레이트, FPS, 드롭 수
- UDP 수신 패킷, 완성 프레임, 마감 폐기, 추정 누락 패킷과 손실률

시계 동기화가 안정되기 전에는 종단 간 지연값을 확정값으로 표시하지 않습니다.

### 공간 업스케일링과 녹화

- 선택된 PGM만 OpenGL ES 공간 업스케일링
- 에지 적응형 샤프닝을 포함한 비-AI 셰이더 방식
- AMD FSR 라이브러리를 직접 포함한 구현이 아니라 FSR과 같은 공간 처리 방향의 자체 셰이더
- 허브 PGM 녹화는 MediaCodec + MediaMuxer 사용
- 허브 녹화 프레임 쓰기는 전용 단일 스레드와 최신 프레임 우선 정책 사용
- OpenGL 녹화 경로를 사용할 수 없으면 Canvas 경로로 폴백

### 오디오

- 48kHz, 16-bit mono, 20ms 청크
- 현재 저지연 모니터링은 PCM 전송 우선
- AES 암호화 TCP 전송과 카메라별 독립 송신 작업
- 채널 페이더, AFV(Audio Follow Video), 싱크 오프셋, 마스터 출력
- 캡처/수신 오류 감시와 자동 재시작
- Opus MediaCodec 래퍼는 포함되어 있지만 코덱 협상 완료 전까지 기본 비활성화

## 스레드 구조

| 영역 | 실행 경로 |
|---|---|
| UI | Compose 메인 스레드 + ViewModel StateFlow |
| 카메라 촬영 | CameraX/Camera2 내부 카메라 실행기 |
| 영상 인코딩 | MediaCodec + GL 렌더 스레드 |
| UDP 송신 | 카메라 전용 `CameraUdpVideoSend` 스레드 |
| UDP 수신 | 카메라별 `HubUdpVideoReceive-*` 스레드 |
| 디코딩 | 카메라별 큐와 MediaCodec/GL 처리 경로 |
| 오디오 | IO coroutine + 카메라별 송신 풀 + AudioTrack 출력 |
| 허브 녹화 | 전용 `DirectorRecorderFrameWriter` + MediaCodec drain |

영상과 녹화 경로에는 크기가 제한된 큐를 사용합니다. 처리가 밀리면 오래된 프레임을 버려 메인 UI와 실시간 프리뷰가 함께 막히는 것을 방지합니다.

## 기술 스택

| 항목 | 기술 |
|---|---|
| 언어 | Kotlin |
| UI | Jetpack Compose, Material 3 |
| 아키텍처 | Single Activity, MVVM, StateFlow |
| DI | Hilt + KSP |
| 카메라 | CameraX + Camera2 Interop |
| 코덱 | Android MediaCodec H.264/AVC, 선택적 HEVC 녹화 |
| 렌더링 | OpenGL ES, EGL, SurfaceTexture |
| 전송 | UDP/RTP 우선, SRT/TCP 폴백, TLS 제어 채널 |
| 오디오 | AudioRecord, AudioTrack, PCM, 실험적 Opus |
| 최소 Android | API 26 |
| 빌드 SDK | compileSdk 36, targetSdk 35 |

## 프로젝트 구조

```text
├── protocol/         # Android/Windows/macOS 공통 RTP·암호화 규격
├── desktop/          # Windows/macOS 허브와 모니터 PGM 출력
└── app/src/main/java/com/camhub/studio/
    ├── data/
    │   ├── audio/       # 캡처, 전송, 믹싱, Opus 래퍼
    │   ├── camera/      # CameraX/Camera2 제어
    │   ├── capability/  # 기기 분류와 런타임 성능 정책
    │   ├── gl/          # 카메라/디코더 렌더링과 공간 업스케일링
    │   ├── metrics/     # 시계 보정과 단계별 지연 백분위
    │   ├── network/     # 검색, TLS, UDP/RTP, SRT/TCP, 자동 경로 선택
    │   └── ptz/         # 카메라별 혼합형 PTZ 상태와 변환
    ├── navigation/  # 역할 선택과 Compose 경로
    └── ui/
        ├── camera/      # 카메라 HUD
        ├── connection/  # 검색, 연결, 네트워크 선택
        ├── director/    # 멀티뷰, PVW/PGM, 녹화, 기기 관리
        ├── settings/    # 스트림·디스플레이·녹화·시스템 설정
        └── audio/       # 허브 오디오 믹서
```

## 빌드 및 테스트

요구 사항:

- Android Studio 최신 안정 버전
- JDK 17 이상
- Android SDK 36

```bash
# Debug APK
./gradlew assembleDebug

# JVM 단위 테스트
./gradlew testDebugUnitTest

# 클린 검증
./gradlew clean testDebugUnitTest assembleDebug

# Android/데스크톱 공통 wire 프로토콜
./gradlew :protocol:test

# iOS/Swift 공통 wire 프로토콜 (앱을 실행하지 않음)
swift test --package-path ios/CamHubCore

# 데스크톱 허브 실행
./gradlew :desktop:run

# Windows/macOS 실행 스크립트가 포함된 배포 폴더 생성
./gradlew :desktop:installDist

# Java 런타임이 포함된 macOS 앱 생성 (macOS에서 실행)
./gradlew :desktop:packageMacApp
# 결과: desktop/build/package/macos/CamHub Desktop.app

# Java 런타임이 포함된 Windows 설치 파일 생성 (Windows에서 실행)
gradlew.bat :desktop:packageWindowsExe
# 결과: desktop/build/package/windows/*.exe

# Release AAB
./gradlew bundleRelease
```

Kotlin과 Swift 프로토콜 테스트는 [`protocol/src/test/resources/camhub-wire-golden.properties`](protocol/src/test/resources/camhub-wire-golden.properties)의 동일한 payload, RTP, AES-GCM 벡터를 읽습니다. 이는 wire-format 일치를 검증하며 카메라 실기기와 허브 간 네트워크 상호운용까지 증명하지는 않습니다.

`.github/workflows/desktop-packages.yml`을 수동 실행하거나 데스크톱 관련 변경을 `main`에 푸시하면 macOS arm64 앱과 Windows x64 설치 파일을 각각 해당 운영체제에서 빌드합니다.

Release 서명에는 다음 환경 변수가 필요합니다.

```text
CAMHUB_KEYSTORE_PATH
CAMHUB_KEYSTORE_PASSWORD
CAMHUB_KEY_ALIAS
CAMHUB_KEY_PASSWORD
```

## 현재 제한사항과 다음 검증

- 다양한 제조사 조합에서 4대 동시 스트림 장시간 실기기 검증 필요
- Wi-Fi AP별 혼잡, 절전 정책, 멀티캐스트 제한 차이 검증 필요
- 50ms 목표는 촬영부터 실제 패널 표시까지 고속 카메라/LED 기준 검증 필요
- Opus는 송수신 코덱 협상과 기기 호환성 검증 후 기본 활성화 예정
- 외부 입력(USB/UVC, 캡처 카드)은 [docs/external-input-plan.md](docs/external-input-plan.md)의 계획 단계
- 현재 영상 코덱은 Android 하드웨어 H.264/HEVC이며 자체 영상 코덱은 구현하지 않음
- 데스크톱 VideoToolbox/Media Foundation 하드웨어 디코더 선택은 후속 구현 단계
- 카메라 TLS 인증서는 앱 내부에 보존되고, 최초 연결 시 카메라와 허브 화면의 SHA-256 지문을 직접 비교해 신뢰를 고정함
- 카메라의 8자리 페어링 PIN은 TLS 인증서 지문 확인 뒤 전송되며, PIN이 맞기 전에는 카메라가 영상 세션 키를 보내지 않음

## 문서

- [CHANGELOG.md](CHANGELOG.md) — 기존 버전 변경 이력
- [CamHub_Pro_기획서.md](CamHub_Pro_기획서.md) — 제품 기획과 초기 아키텍처
- [docs/external-input-plan.md](docs/external-input-plan.md) — 외부 영상 입력 확장 계획

## 라이선스

All rights reserved.
