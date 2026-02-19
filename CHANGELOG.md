# CamHub Studio - Changelog

## 2026-02-19 (v0.2.0) - 스트리밍 파이프라인 & UI 전면 개선

### Video Streaming Pipeline

#### H.264 Surface 인코딩 파이프라인
- **GL 렌더러 기반 Surface 모드**: CameraX -> SurfaceTexture -> OpenGL ES 2.0 -> H.264 MediaCodec (Surface 입력)
- 듀얼 출력: 동일 GL 프레임을 뷰파인더 + 인코더에 동시 렌더링
- 인코딩 해상도 16바이트 정렬 (H.264 MediaCodec 호환)
- `glViewport()` 명시적 호출로 멀티 EGL 서피스 간 뷰포트 충돌 방지

#### 인코더 최적화
- 비트레이트: 4Mbps -> 2Mbps (대역폭 50% 절감)
- 프로파일: Baseline -> **High** (동일 비트레이트에서 ~30% 화질 향상)
- 레벨: 3.1 -> 4 (고해상도 지원)
- 저지연 힌트: `KEY_LATENCY=1`, `KEY_PRIORITY=0` (Android 11+/12+)
- 디큐 타임아웃: 1ms -> 5ms (안정성 향상)

#### 네트워크 전송 최적화
- **병렬 클라이언트 전송**: `CachedThreadPool` 기반 클라이언트별 독립 스레드
- **프레임 드롭**: 이전 프레임 전송 중인 클라이언트는 새 프레임 스킵 (congestion 대응)
- TCP 전송 버퍼: 기본값 -> 256KB
- SPS/PPS 설정 프레임: 해상도 변경 시 **모든** 클라이언트에 재전송 (기존: 신규 클라이언트만)

#### 가로모드 스트리밍 수정
- 인코딩 해상도: 세로=9:16, 가로=16:9 (디바이스 방향에 따라 자동 전환)
- GL 렌더러 이중 회전 버그 수정: SurfaceTexture transform matrix가 센서->버퍼 매핑 자동 처리하므로 추가 디바이스 회전 보정 제거
- 화면 회전 시 파이프라인 완전 재구성 (`onSurfaceTextureSizeChanged` -> destroy -> recreate)
- CameraController `ResolutionSelector`: 16:9 AspectRatioStrategy 적용 (이전: resolution 파라미터 무시됨)

### Camera Controls

#### 자동 초점 (AF)
- `CONTROL_AF_MODE_CONTINUOUS_VIDEO` 기본 활성화
- 포커스 거리 목록에 "AF" 옵션 추가 (첫 번째, 기본값)
- 수동 거리 선택 시 `AF_MODE_OFF` 전환, "AF" 선택 시 연속 AF 복원
- 탭 투 포커스: `FocusMeteringAction` + 1.5초 시각 피드백

#### 자동 노출 (AE)
- ISO/셔터 목록에 "Auto" 옵션 추가 (첫 번째, 기본값)
- 플리커 수정: ISO/셔터 개별 설정 시 두 값을 항께 `CaptureRequestOptions`에 적용
  - 기존: `setIso()`가 `AE_MODE_OFF` + ISO만 설정 -> 셔터가 극단값으로 점프 -> 깜빡임
  - 수정: `applyManualExposure()`로 ISO + 셔터 동시 설정
- 양쪽 모두 "Auto" 선택 시 `CONTROL_AE_MODE_ON` 복원

### Audio

#### 오디오 캡처 권한 수정
- **원인**: `AudioCaptureService.start()`가 ConnectionSetup에서 호출되지만, `RECORD_AUDIO` 권한은 CameraHudScreen에서 나중에 획득
- `AudioCaptureService.ensureCapture()`: 권한 획득 후 마이크 캡처 재시도 (멱등성 보장)
- CameraHudScreen: 권한 확인 후 `viewModel.ensureAudioCapture()` 호출

#### 오디오 전송 인프라 (기존 구현)
- AudioCaptureService: 44.1kHz PCM 16-bit mono, 20ms 청크, AES 암호화, TCP 브로드캐스트
- AudioStreamClient: 수신 -> 복호화 -> 링 버퍼 -> 채널 믹싱 -> AudioTrack 출력
- 채널별 페이더, AFV, 싱크 오프셋 지원

### UI Changes

#### Camera HUD - Portrait
- 펀치홀/노치 세이프 영역: `WindowInsets.statusBars.union(displayCutout)` 적용
- 녹화 버튼: 56dp -> 40dp
- 도구 아이콘: IconButton(48dp) -> Icon(20dp) + clickable

#### Camera HUD - Landscape
- 레이아웃 전면 재설계: Column -> **Box 오버레이**
  - 프리뷰가 전체 화면 점유
  - 상단: 파라미터 바 + 타임코드 (반투명 오버레이)
  - 하단: 미니멀 상태 라인 (8sp 텍스트, 50% 투명도)
  - 우측: 컴팩트 사이드바 (44dp, 아이콘 20dp)
- 하단 오버레이: StorageBar/StatusChip -> 8sp 텍스트 (LIVE, 용량, 오디오 미터)
- AudioMetersPanel `compact` 모드: 배경 없음, 5dp 바, 7sp 라벨

#### Director Screen
- 펀치홀 세이프 영역 적용
- PVW/PGM 순서 수정: PVW 먼저 (왼쪽/위), PGM 나중에 (오른쪽/아래)
- ControlBar 스크롤: 가로=horizontalScroll, 세로=verticalScroll

#### Audio Meters
- LED Off 색상 수정: `0xFF1A2736` (배경과 동일) -> `0xFF2A3A4D` (식별 가능)

---

## 2026-02-18 (v0.1.0) - 초기 구현

### Core Architecture
- **패키지명**: `com.camhub.studio`
- **구조**: Single Activity + Jetpack Compose Navigation
- **DI**: Hilt (ViewModels, Singletons)
- **패턴**: MVVM (5개 ViewModel + StateFlow)

### Screens
- **RoleSelect**: 카메라/디렉터 역할 선택
- **ConnectionSetup**: mDNS 디스커버리, 수동 IP 연결, 핫스팟
- **CameraHud**: Blackmagic Camera 스타일 촬영 UI
- **Director**: 멀티뷰 그리드, PVW/PGM 스위칭, 카메라 원격 제어
- **Settings**: 프로토콜, 녹화, 디스플레이, 시스템 설정

### Networking
- PeerConnectionManager: TLS 핸드셰이크, 세션 키 교환
- StreamServer/StreamClient: H.264 + JPEG 폴백, V2 프레임 헤더
- SRT 지원 (srtdroid 라이브러리)
- FrameCipher: AES 암호화

### Camera
- CameraX + Camera2 Interop
- 수동 ISO, 셔터, 포커스 제어
- 핀치 줌, 탭 투 포커스
- 전면/후면 전환
- 비디오 녹화 (MediaStore)
