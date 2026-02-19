# CamHub Studio

**멀티 스마트폰 카메라 스위칭 & 라이브 프로덕션 시스템**

여러 대의 스마트폰을 무선 카메라로 활용하여 실시간 멀티뷰 프리뷰, 라이브 스위칭, 오디오 믹싱을 수행하는 올인원 모바일 프로덕션 앱입니다.

```
[카메라 폰 1] ─┐
[카메라 폰 2] ─┼── WiFi ──> [디렉터 폰] ──> HDMI 출력
[카메라 폰 3] ─┤            (멀티뷰 + 스위칭)
[카메라 폰 4] ─┘
```

---

## 주요 기능

### 카메라 모드
- **Blackmagic Camera 스타일 HUD** — 타임코드, 파라미터 바, 히스토그램, 오디오 미터
- **수동 제어** — ISO, 셔터 스피드, 포커스 거리 (드럼 다이얼 UI)
- **자동 제어** — 연속 AF (`CONTINUOUS_VIDEO`), 자동 노출 (AE), 탭 투 포커스
- **핀치 줌** — 실시간 줌 비율 조절
- **전면/후면 카메라 전환**
- **비디오 녹화** — MediaStore 기반 로컬 녹화

### 스트리밍 파이프라인
- **H.264 Surface 인코딩** — CameraX → SurfaceTexture → OpenGL ES 2.0 → MediaCodec (Surface 입력)
- **듀얼 출력** — 동일 GL 프레임을 뷰파인더 + 인코더에 동시 렌더링
- **저지연 최적화** — High 프로파일, 2Mbps, KEY_LATENCY=1
- **병렬 클라이언트 전송** — 클라이언트별 독립 스레드, 프레임 드롭 (congestion 대응)
- **가로/세로 자동 전환** — 디바이스 방향에 따라 인코딩 해상도 전환 (9:16 / 16:9)

### 디렉터 모드
- **멀티뷰 그리드** — 최대 4대 카메라 동시 프리뷰
- **PVW/PGM 스위칭** — 프리뷰 → 프로그램 라이브 전환
- **카메라 원격 제어** — 줌, ISO, 셔터, 포커스 원격 조정
- **탈리 라이트** — PGM/PVW 상태에 따른 시각적 피드백

### 오디오
- **44.1kHz PCM 캡처** — 16-bit mono, 20ms 청크
- **AES 암호화 전송** — TCP 기반 오디오 브로드캐스트
- **오디오 믹서** — 채널별 페이더, AFV (Audio Follow Video)
- **싱크 오프셋** — 비디오-오디오 동기화 조정
- **LED VU 미터** — Green → Yellow → Red 세그먼트 미터

### 네트워크
- **mDNS 디스커버리** — 로컬 네트워크 자동 디바이스 탐색
- **TLS 핸드셰이크** — 세션 키 교환, 보안 연결
- **AES 프레임 암호화** — 비디오/오디오 데이터 암호화
- **SRT 지원** — srtdroid 라이브러리 (옵션)

---

## 기술 스택

| 항목 | 기술 |
|------|------|
| **언어** | Kotlin 100% |
| **UI** | Jetpack Compose |
| **아키텍처** | Single Activity + MVVM + StateFlow |
| **DI** | Hilt |
| **카메라** | CameraX + Camera2 Interop |
| **인코딩** | MediaCodec (H.264 Surface 모드) |
| **렌더링** | OpenGL ES 2.0 (EGL + SurfaceTexture) |
| **네트워크** | Raw TCP + TLS + mDNS (NsdManager) |
| **오디오** | AudioRecord + AudioTrack |
| **빌드** | Gradle KTS, compileSdk 36, targetSdk 35, minSdk 26 |

---

## 프로젝트 구조

```
com.camhub.studio/
├── CamHubApplication.kt          # Hilt Application
├── MainActivity.kt                # Single Activity (Compose Navigation)
│
├── data/                          # 데이터 레이어
│   ├── DeviceMonitor.kt           # 배터리, WiFi, 저장소 모니터링
│   ├── audio/
│   │   ├── AudioCaptureService.kt # 마이크 캡처 + TCP 전송
│   │   └── AudioStreamClient.kt   # 오디오 수신 + 재생
│   ├── camera/
│   │   ├── CameraController.kt    # CameraX 제어 (AF, AE, MF, 줌)
│   │   └── CameraValueMapper.kt   # ISO/셔터/포커스 값 변환
│   ├── gl/
│   │   └── CameraGlRenderer.kt    # OpenGL ES 2.0 렌더러
│   └── network/
│       ├── FrameCipher.kt         # AES 암호화
│       ├── H264Encoder.kt         # MediaCodec 인코더
│       ├── PeerConnectionManager.kt # TLS 핸드셰이크
│       ├── StreamServer.kt        # 비디오 스트림 서버
│       └── StreamClient.kt        # 비디오 스트림 클라이언트
│
├── navigation/
│   └── CamHubNavHost.kt           # Compose Navigation 라우트
│
└── ui/                            # UI 레이어
    ├── theme/                     # 테마, 색상, 타이포그래피
    ├── components/                # 공통 UI 컴포넌트
    ├── camera/                    # 카메라 HUD 화면
    │   ├── CameraHudScreen.kt
    │   ├── CameraHudViewModel.kt
    │   ├── components/            # 파라미터 바, 오디오 미터 등
    │   └── model/                 # CameraUiState
    ├── director/                  # 디렉터 화면
    │   ├── DirectorScreen.kt
    │   ├── DirectorViewModel.kt
    │   ├── components/            # 멀티뷰, 컨트롤 바 등
    │   └── model/                 # DirectorUiState
    ├── audio/                     # 오디오 믹서
    │   ├── AudioMixerViewModel.kt
    │   ├── components/            # 페이더, LED 미터
    │   └── model/                 # AudioMixerUiState
    ├── settings/                  # 설정 화면
    │   ├── SettingsScreen.kt
    │   ├── SettingsViewModel.kt
    │   └── model/                 # SettingsUiState
    └── connection/                # 연결 설정
        ├── ConnectionSetupScreen.kt
        └── ConnectionViewModel.kt
```

---

## 화면 구성

| 화면 | 설명 |
|------|------|
| **RoleSelect** | 카메라 / 디렉터 역할 선택 |
| **ConnectionSetup** | mDNS 자동 탐색, 수동 IP 입력, 핫스팟 설정 |
| **CameraHud** | 카메라 촬영 UI (세로/가로 레이아웃) |
| **Director** | 멀티뷰 그리드 + PVW/PGM 스위칭 |
| **Settings** | 프로토콜, 녹화, 디스플레이, 시스템 설정 |

---

## 빌드

```bash
# Debug APK
./gradlew assembleDebug

# Release AAB
./gradlew bundleRelease
```

**요구 사항:**
- Android Studio Ladybug 이상
- JDK 17+
- Android SDK 36

---

## 문서

- [CHANGELOG.md](./CHANGELOG.md) — 버전별 변경 이력
- [CamHub_Pro_기획서.md](./CamHub_Pro_기획서.md) — 종합 기획서 (시장 분석, 시스템 아키텍처, 프로토콜, 로드맵)

---

## 라이선스

All rights reserved.
