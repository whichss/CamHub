# CamHub Pro - 멀티캠 스마트폰 스튜디오 시스템 종합 기획서

> **문서 버전**: v1.0
> **작성일**: 2026-02-11
> **목적**: 멀티 스마트폰 카메라 스위칭/스트리밍 시스템 설계 및 구현 계획

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [시장 분석 및 경쟁 제품](#2-시장-분석-및-경쟁-제품)
3. [시스템 아키텍처](#3-시스템-아키텍처)
4. [프로토콜 선정](#4-프로토콜-선정)
5. [이미지 처리 및 스케일링 기술](#5-이미지-처리-및-스케일링-기술)
6. [카메라 파이프라인 아키텍처](#6-카메라-파이프라인-아키텍처)
7. [ISO 녹화 시스템](#7-iso-녹화-시스템)
8. [HDMI 외부 출력](#8-hdmi-외부-출력)
9. [키오스크 모드 (전용 카메라 디바이스)](#9-키오스크-모드-전용-카메라-디바이스)
10. [PC/Mac 확장](#10-pcmac-확장)
11. [개발 로드맵](#11-개발-로드맵)
12. [차별점 및 시장 포지셔닝](#12-차별점-및-시장-포지셔닝)
13. [기술 리스크 및 대응](#13-기술-리스크-및-대응)

---

## 1. 프로젝트 개요

### 1.1 비전

여러 대의 스마트폰을 카메라로 활용하여, 하나의 디렉터 폰에서 실시간 멀티뷰 프리뷰 + 라이브 스위칭을 수행하고, HDMI를 통해 프로그램 출력을 내보내는 올인원 모바일 프로덕션 시스템.

### 1.2 핵심 컨셉

```
[카메라 폰 1] ─┐
[카메라 폰 2] ─┤── WiFi ──> [디렉터 폰] ──> HDMI 출력 ──> 모니터/캡처카드
[카메라 폰 3] ─┤            (멀티뷰 + 스위칭)
[카메라 폰 4] ─┘
```

### 1.3 핵심 요구사항

| 항목 | 요구사항 |
|------|----------|
| **카메라 수** | 최대 4~9대 동시 |
| **지연시간** | LAN 50~150ms (glass-to-glass) |
| **스트리밍 해상도** | 720p 전송 → GPU 업스케일링 → 1080p 출력 |
| **녹화** | 각 카메라 로컬 ISO 녹화 + 디렉터 프로그램 녹화 |
| **출력** | HDMI 클린 출력 (디렉터 폰) |
| **전용 디바이스** | 공기계 전원 ON → 자동 카메라 모드 (키오스크) |
| **PC 확장** | NDI/WHIP으로 OBS/vMix 연동 |
| **플랫폼** | iOS + Android (크로스플랫폼) |

---

## 2. 시장 분석 및 경쟁 제품

### 2.1 주요 경쟁 제품 비교표

| 제품 | 가격 | 최대 카메라 | 지연시간 | 최대 화질 | 플랫폼 | HDMI 출력 | 추가 HW | 라이브 스위칭 |
|------|------|------------|---------|----------|--------|----------|---------|-------------|
| **Switcher Studio** | $49-149/월 | 9 | 낮음 | 1080p | iOS 전용 | 어댑터 필요 | 없음 | O |
| **Canon Live Switcher** | 무료/$17.99/월 | 3(무료)/무제한 | 낮음 | 720p/1080p | iOS 전용 | X | 없음 | O |
| **Blackmagic Camera** | 무료 | 9 | 낮음 | 4K ProRes | iOS+Android | O | 없음 | X (녹화만) |
| **Mevo Multicam** | 무료/$19/월 | 3+ | 낮음 | 1080p | iOS+Android | Mevo HW | Mevo 카메라 권장 | O |
| **ViuLive** | 무료 | 6 | 중간 | HD | iOS+Android | HDMI 입력 지원 | 없음 | O |
| **TVU Anywhere** | 18EUR/시간 | 다수 | 매우 낮음 | HEVC | iOS+Android | TVU HW | TVU Producer | O |
| **SlingStudio** | $1,000+ 허브 | 10 | 낮음 | 1080p60 | iOS+Android | O (Hub) | O (허브 필수) | O |
| **VDO.Ninja** | 무료 (오픈소스) | 무제한 | 초저지연 | 4K | 브라우저 | X | OBS 필요 | OBS 통해 |
| **NDI Camera** | 무료 | N/A (소스만) | 80-200ms | 1080p+ | iOS+Android | X | NDI 스위처 필요 | X (소스만) |
| **ATEM Mini** | $295-995 | 4-8 | 거의 없음 | 1080p60 | 하드웨어 | O | O (스위처+케이블) | O |
| **YoloBox Ultra** | $1,299 | 7+ | 낮음 | 4K | 독립형 | O | O | O |

### 2.2 시장 갭 분석 (10가지 핵심 기회)

1. **크로스플랫폼 무료/저가 멀티캠 라이브 스위처 부재** — iOS+Android + 라이브 스위칭 + 무료/저가 조합이 없음
2. **Android 생태계 방치** — Switcher Studio, Canon Live Switcher, Mavis NDI 모두 iOS 전용
3. **WiFi 안정성 문제** — 대부분 WiFi 의존, 혼잡 환경에서 불안정 (TVU만 셀룰러 본딩 지원)
4. **녹화 vs 스트리밍 분리** — Blackmagic은 녹화만, Switcher는 스트리밍만 강점
5. **전용 디렉터+카메라 앱 부재** — 카메라 전용 모드 최적화 제품 없음
6. **HDMI 클린 출력 제한** — 스위칭된 프로그램 출력을 HDMI로 보낼 수 있는 폰 앱이 거의 없음
7. **라이브 이벤트용 저지연** — WiFi 기반 100-500ms 지연, 음악/스포츠에 부적합
8. **오디오 믹싱 부족** — 대부분 기초적 수준
9. **오픈소스 갭** — 폰-투-폰 멀티캠 프로덕션 오픈소스 없음
10. **가격/기능 불균형** — 무료+제한적 vs 비싼 구독 사이 중간 영역 부재

### 2.3 CamHub Pro 차별화 포인트

**기존 어떤 제품도 아래 조합을 모두 충족하지 못함:**

- 크로스플랫폼 (iOS + Android)
- 라이브 스위칭
- ISO 녹화
- 추가 하드웨어 불필요
- 저가/무료
- HDMI 클린 출력
- 전용 카메라 키오스크 모드

---

## 3. 시스템 아키텍처

### 3.1 전체 시스템 구성도

```
┌─────────────────────────────────────────────────────────────┐
│                    네트워크 레이어                              │
│  전용 WiFi 6 라우터 (5GHz, 카메라 전용 트래픽)                    │
│  mDNS/Bonjour 디바이스 디스커버리                                │
│  BLE 제어 채널 (탈리, 설정, 스위칭 커맨드)                        │
└─────────────────────────────────────────────────────────────┘

┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  카메라 1     │  │  카메라 2     │  │  카메라 3     │  │  카메라 4     │
│  (스마트폰)   │  │  (스마트폰)   │  │  (스마트폰)   │  │  (스마트폰)   │
│              │  │              │  │              │  │              │
│  H.265 HW   │  │  H.265 HW   │  │  H.265 HW   │  │  H.265 HW   │
│  인코더      │  │  인코더      │  │  인코더      │  │  인코더      │
│  720p 3-5Mbps│  │  720p 3-5Mbps│  │  720p 3-5Mbps│  │  720p 3-5Mbps│
│              │  │              │  │              │  │              │
│  +로컬 ISO   │  │  +로컬 ISO   │  │  +로컬 ISO   │  │  +로컬 ISO   │
│  녹화(고화질) │  │  녹화(고화질) │  │  녹화(고화질) │  │  녹화(고화질) │
│              │  │              │  │              │  │              │
│  WebRTC P2P  │  │  WebRTC P2P  │  │  WebRTC P2P  │  │  WebRTC P2P  │
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
       │                 │                 │                 │
       └────────────┬────┴────────┬────────┘                 │
                    │             │                           │
              ┌─────▼─────────────▼───────────────────────────▼──┐
              │              디렉터 폰                            │
              │                                                  │
              │  4x H.265 HW 디코드 (동시)                       │
              │  GPU 업스케일링 (MetalFX / Arm ASR)               │
              │  멀티뷰 컴포지터 → 폰 스크린                      │
              │  프로그램 출력 (선택된 피드) → HDMI                 │
              │  스위칭 UI + 터치 컨트롤                           │
              │  프로그램 녹화 (H.265)                             │
              │                                                  │
              │  USB-C ──> HDMI 어댑터 ──> 모니터/캡처카드         │
              └──────────────────────────────────────────────────┘
```

### 3.2 네트워크 토폴로지

**P2P 스타 토폴로지 (권장)**

각 카메라 폰이 디렉터 폰에 직접 스트림 전송. 최소 네트워크 홉, 최저 지연시간.

**네트워크 요구사항:**

| 카메라 수 | 총 대역폭 (디렉터 수신) | 권장 네트워크 |
|----------|----------------------|-------------|
| 2대 | 6-10 Mbps | WiFi 5 (5GHz) |
| 4대 | 12-20 Mbps | WiFi 5/6 (5GHz) |
| 8대 | 24-40 Mbps | WiFi 6 전용 |
| 9대+ | 27-45 Mbps+ | WiFi 6 + SFU 고려 |

**권장 사항:**
- 전용 5GHz WiFi 6 라우터 사용 (다른 트래픽 격리)
- 포터블 트래블 라우터 (GL.iNet Beryl AX 등)
- 4대 이하: P2P 직접 연결
- 5대 이상: SFU (LiveKit) 고려

### 3.3 디바이스 디스커버리

**mDNS/Bonjour 기반 자동 디스커버리:**

1. 카메라 폰이 서비스 등록: `_camhub._tcp.local`
2. TXT 레코드: 디바이스명, 해상도, 스트림 엔드포인트
3. 디렉터 폰이 서비스 브라우징
4. 발견 시 WebRTC 시그널링 또는 NDI 연결 시작
5. 폴백: QR 코드 스캔 또는 수동 IP 입력

**플랫폼 API:**
- iOS: `NWBrowser` / `NetService` (Bonjour)
- Android: `NsdManager` (Network Service Discovery)

### 3.4 역할 구분

| 역할 | 설명 | 가능한 디바이스 |
|------|------|---------------|
| **카메라** | 영상+오디오 캡처, 디렉터에 전송 | 스마트폰, 웹캠, NDI 카메라 |
| **디렉터/스위처** | 모든 피드 수신, 라이브 전환, 트랜지션 적용 | 스마트폰(메인), PC(확장) |
| **프로그램 출력** | 최종 스위칭된 출력 | 스트림, 녹화, NDI, HDMI |
| **모니터** | 프로그램 또는 멀티뷰 패시브 뷰어 | 브라우저, 별도 디바이스 |

---

## 4. 프로토콜 선정

### 4.1 프로토콜 비교

| 프로토콜 | 지연시간 | 전송 방식 | 오픈소스 | 최적 용도 |
|---------|---------|----------|---------|----------|
| **WebRTC** | 30-150ms (LAN) | UDP (SRTP/SCTP) | O | 최저지연 P2P |
| **NDI HX3** | 80-200ms | TCP/UDP | X (SDK 무료) | 프로 LAN 프로덕션 |
| **SRT** | 50-500ms (조절가능) | UDP | O | 불안정 네트워크 |
| **RTSP** | 200-500ms | TCP/UDP (RTP) | O | IP 카메라 연동 |
| **RTMP** | 1-3초 | TCP | O | 레거시, 플랫폼 인제스트 |

### 4.2 선정: WebRTC (1차) + NDI (2차)

**WebRTC를 1차 프로토콜로 선정한 이유:**

- **최저 지연**: LAN에서 50-150ms glass-to-glass (WiFi), 유선 30-40ms
- **P2P**: 중계 서버 불필요, 각 카메라 → 디렉터 직접 전송
- **하드웨어 가속**: iOS/Android 모두 H.264/H.265 HW 인코더/디코더 지원
- **적응형 비트레이트**: 네트워크 상태에 따라 자동 품질 조정
- **오픈 표준**: 풍부한 라이브러리 생태계

**지연시간 상세 분석 (WebRTC on WiFi LAN):**

| 단계 | 일반 지연 | 최적화 지연 |
|------|----------|-----------|
| 센서 노출 | 16-33ms (30fps) | 8-16ms (60fps) |
| ISP 처리 | 5-10ms | 5-10ms |
| GPU 처리 (스케일/컬러) | 2-5ms | 1-3ms |
| HW 인코딩 | 15-30ms | 5-10ms (저지연 모드) |
| 네트워크 전송 | 5-20ms (LAN) | 1-5ms (유선) |
| 지터 버퍼 | 0-50ms | 0ms (버퍼 없음) |
| HW 디코딩 | 10-20ms | 5-10ms |
| GPU 스케일/컴포지트 | 2-5ms | 1-3ms |
| 디스플레이 리프레시 | 0-16ms (60Hz) | 0-8ms (120Hz) |
| **총합** | **55-189ms** | **26-65ms** |

**NDI를 2차(선택) 프로토콜로:**
- PC/Mac에서 OBS/vMix와 연동 시
- 프로페셔널 LAN 환경
- NDI SDK 무료 사용 가능

### 4.3 시그널링 및 세션 관리

```json
// 세션 참가 메시지
{
  "type": "join_studio",
  "studio_id": "abc123",
  "device_id": "phone-uuid",
  "role": "camera",
  "capabilities": {
    "video_codecs": ["h264", "h265"],
    "max_resolution": "3840x2160",
    "max_fps": 60,
    "audio_codecs": ["opus", "aac"],
    "supports_ndi": true
  }
}

// 스위칭 커맨드
{
  "type": "switch_camera",
  "from": "director-uuid",
  "active_camera": "phone-b-uuid",
  "transition": "cut"  // cut | dissolve | wipe
}

// 탈리 업데이트
{
  "type": "tally_update",
  "camera_id": "phone-b-uuid",
  "status": "program"  // program(빨강) | preview(초록) | off
}
```

---

## 5. 이미지 처리 및 스케일링 기술

### 5.1 스케일링 알고리즘 비교

| 알고리즘 | 샘플 수 | 품질 | GPU 비용 | 용도 |
|---------|--------|------|---------|------|
| Bilinear | 4 | 낮음 | 최소 | 프리뷰 다운스케일, 썸네일 |
| Bicubic | 16 | 양호 | 낮음 | 범용 스케일링 |
| Lanczos-3 | ~36 | 우수 | 낮음-중간 | 프로그램 출력, 최종 화질 |
| FSR 1.0 (EASU) | ~16+엣지 | 매우 양호 | 낮음 | 엣지 복원 업스케일링 |
| FSR 2.x / Arm ASR | 시간 누적 | 우수 | 중간 | 모션벡터 활용 최고 품질 |

### 5.2 플랫폼별 업스케일링 기술

#### iOS: MetalFX Spatial Scaler

- **A15 이상** 디바이스에서 사용 가능 (iPhone 13 Pro+)
- `MTLFXSpatialScaler`: 단일 프레임 공간 업스케일링, AMD FSR 1.0 수준
- 720p → 1080p 업스케일링에 성능 손실 거의 없음
- 모션벡터 불필요 → 비디오 업스케일링에 바로 적용 가능
- **60 FPS에서 1080p → 4K도 가능**

#### Android: Arm ASR (Accuracy Super Resolution)

- AMD FSR 2.2 기반, **모바일 Arm GPU에 최적화**
- 오픈소스 (GitHub에서 사용 가능)
- 기존 FSR 2 대비 **GPU 부하 50% 감소**
- Vulkan 컴퓨트 셰이더로 구현
- Lanczos-3 커스텀 셰이더도 대안

#### AI 슈퍼 레졸루션 (선택적 고급 기능)

| 작업 | 모델 | 디바이스 | 달성 FPS |
|------|------|---------|---------|
| 720p → 1080p | FSRCNN-small (int8) | iPhone 15 Pro ANE | ~25-30 |
| 720p → 1080p | ESPCN (int8) | iPhone 15 Pro ANE | ~50-60 |
| 1080p → 4K | MetalFX Spatial | iPhone 15 Pro GPU | 60 |
| 720p → 1080p | Real-ESRGAN (full) | iPhone 15 Pro ANE | ~2-5 |

**권장 전략**: 실시간은 **MetalFX/Arm ASR** 사용, AI 슈퍼 레졸루션은 후처리용으로 제공

### 5.3 대역폭 vs 화질 전략

**핵심 전략: 저해상도 스트리밍 + GPU 업스케일링**

```
카메라 폰: 720p 캡처 → H.265 인코딩 (3-5 Mbps) → 네트워크 전송
                                                        │
디렉터 폰: H.265 디코딩 → GPU 업스케일 (MetalFX/Arm ASR) → 1080p 출력
```

이 전략의 장점:
- 네트워크 대역폭 절약 (4대 × 5Mbps = 20Mbps)
- GPU 업스케일링은 거의 무료 (Metal/Vulkan GPU에서)
- 화질은 네이티브 1080p 스트리밍과 유사
- HW 디코더 부하 감소 (720p < 1080p)

---

## 6. 카메라 파이프라인 아키텍처

### 6.1 iOS: Zero-Copy GPU 파이프라인

```
카메라 센서
    │
AVCaptureVideoDataOutput (CMSampleBuffer)
    │
CMSampleBufferGetImageBuffer() → CVPixelBuffer (IOSurface 기반)
    │
CVMetalTextureCacheCreateTextureFromImage() → MTLTexture [ZERO COPY]
    │
Metal Compute/Render Pass (스케일링, 컬러 그레이딩, LUT, 샤프닝)
    │
    ├──→ 프리뷰: CAMetalLayer에 렌더링 [ZERO COPY]
    │
    ├──→ 인코딩: VTCompressionSession.encodeFrame() [ZERO COPY]
    │         │
    │         ├──→ 로컬 ISO 파일 (AVAssetWriter)
    │         └──→ 네트워크 전송 (WebRTC)
    │
    └──→ (선택) AI 슈퍼 레졸루션: Core ML on ANE [비동기]
```

**핵심 구현 포인트:**

1. `CVMetalTextureCache` 생성 (앱 시작 시 1회)
2. 카메라 프레임 → Metal 텍스처 변환 (zero copy, IOSurface 공유)
3. 처리 결과 → VTCompressionSession에 직접 전달 (zero copy)
4. **Encode-Once 듀얼 출력**: 한 번 인코딩 → 로컬 파일 + 네트워크 동시 출력

**인코더 설정 (저지연 모드):**
- `kVTCompressionPropertyKey_RealTime = true`
- `kVTCompressionPropertyKey_AllowFrameReordering = false` (B-프레임 비활성화)
- `kVTVideoEncoderSpecification_EnableLowLatencyRateControl = true`
- 키프레임 간격: 1초

### 6.2 Android: Zero-Copy GPU 파이프라인

```
Camera2 API (ImageReader + AHardwareBuffer)
    │
AHardwareBuffer (GPU 접근 가능)
    │
VK_ANDROID_external_memory_android_hardware_buffer → VkImage [ZERO COPY]
    │
Vulkan Compute Pipeline (스케일링, 컬러, LUT)
    │
    ├──→ 프리뷰: SurfaceView/TextureView에 렌더링
    │
    └──→ 인코딩: MediaCodec InputSurface에 렌더링 [ZERO COPY]
              │
              ├──→ 로컬 ISO 파일 (MediaMuxer)
              └──→ 네트워크 전송 (WebRTC)
```

**핵심 기술:**
- `MediaCodec.createInputSurface()`: GPU에서 인코더로 직접 연결 (CPU 복사 없음)
- `VK_ANDROID_external_memory_android_hardware_buffer`: Vulkan에서 카메라 버퍼 직접 접근
- `VkSamplerYcbcrConversion`: YCbCr → RGB 색공간 변환

### 6.3 컬러 사이언스

**Apple Log 캡처 (iPhone 15 Pro+):**
- 약 14 스톱 다이내믹 레인지
- 플랫/로그 커브로 캡처 → 실시간 LUT 프리뷰 적용
- 최대 품질의 후처리 여지 보존

**실시간 3D LUT 적용 (GPU):**
- Metal/Vulkan 3D 텍스처 룩업으로 구현
- 4K 프레임 기준 **0.5ms 미만** 소요
- Blackmagic DaVinci 컬러 사이언스와 동등한 접근

**멀티카메라 컬러 매칭:**
1. 컬러 레퍼런스 카드 (X-Rite ColorChecker)로 사전 캘리브레이션
2. 카메라별 3×3 색보정 매트릭스 계산
3. GPU에서 실시간 보정 적용
4. 모든 카메라 수동 WB 동일 설정 (자동 WB 금지)

### 6.4 프레임워크 권장사항

| 프레임워크 | 플랫폼 | 용도 | 비고 |
|-----------|--------|------|------|
| **MetalPetal** | iOS | GPU 이미지 처리 | GPUImage3 대체, 활발히 유지보수 |
| **Core Image (CIFilter)** | iOS | 필터 체인 | JIT 커널 퓨전 → 5개 필터 = 1 패스 성능 |
| **Vulkan Compute** | Android | GPU 이미지 처리 | RenderScript 대체 |
| **NCNN** | Android | AI 슈퍼 레졸루션 | Vulkan 백엔드, NNAPI보다 빠른 경우 많음 |

### 6.5 열 관리 전략

```
ProcessInfo.thermalState 기반 적응형 파이프라인:

.nominal  → 풀 파이프라인 (모든 이펙트, 슈퍼 레졸루션)
.fair     → 슈퍼 레졸루션 비활성화, Bilinear 스케일링
.serious  → 해상도 감소, 비필수 이펙트 비활성화
.critical → 720p 출력, 최소 처리
```

---

## 7. ISO 녹화 시스템

### 7.1 아키텍처: Encode-Once 듀얼 출력

```
각 카메라 폰:
┌─────────────────────────────────────────────────┐
│  카메라 센서                                      │
│      │                                          │
│  AVCaptureVideoDataOutput / Camera2 API         │
│      │                                          │
│  Raw CVPixelBuffer / YUV 프레임                   │
│      │                                          │
│  ┌───┴───────────────────────┐                  │
│  │  VTCompressionSession /   │                  │
│  │  MediaCodec (HW 인코더)    │                  │
│  │  H.265, 1080p, 20-30 Mbps │                  │
│  └───┬───────────────────────┘                  │
│      │                                          │
│  인코딩된 프레임 (한 번만 인코딩)                    │
│      │                                          │
│  ┌───┴────┐    ┌─────────────┐                  │
│  │AVAsset │    │ 네트워크     │                  │
│  │Writer  │    │ 패키타이저   │                  │
│  │(로컬   │    │(SRT/WebRTC) │                  │
│  │ .mov)  │    │→ 디렉터     │                  │
│  └────────┘    └─────────────┘                  │
│                                                 │
│  NTP 동기화 타임코드 양쪽 출력에 임베드              │
└─────────────────────────────────────────────────┘
```

### 7.2 HW 인코더 동시 세션 수

| 플랫폼 | 동시 인코드 세션 | 비고 |
|--------|----------------|------|
| **iOS (A16/A17/A18)** | 2 세션 (안정적) | Apple Media Engine 전용 실리콘 |
| **Android (Snapdragon 8 Gen 2/3)** | 2-3 세션 | `getMaxSupportedInstances()` API로 확인 |

**1 세션 전략 (권장):**
- H.265, 1080p, 20-30 Mbps로 1회 인코딩
- 동일 비트스트림을 로컬 파일 + 네트워크 양쪽에 출력
- 열 부하 최소화, 배터리 절약

**2 세션 전략 (고품질 분리 시):**
- 세션 1: H.265, 4K/1080p, 50+ Mbps (로컬 ISO)
- 세션 2: H.264, 720p, 4-8 Mbps (네트워크 전송)
- 열 부하 증가, 플래그십 폰에서만 권장

### 7.3 녹화 포맷별 용량

| 포맷 | 해상도 | 분당 용량 | 시간당 용량 |
|------|--------|----------|-----------|
| ProRes 422 HQ | 4K/30 | ~6 GB | ~360 GB |
| ProRes 422 HQ | 1080p/30 | ~1.7 GB | ~102 GB |
| H.265 (고화질) | 4K/30 | ~350-400 MB | ~21-24 GB |
| H.265 (고화질) | 1080p/30 | ~170-200 MB | ~10-12 GB |
| H.265 (중간) | 1080p/30 | ~60-100 MB | ~4-6 GB |
| H.264 (스트리밍) | 1080p/30 | ~30-60 MB | ~2-4 GB |

**권장**: H.265, 1080p, 20-40 Mbps → 분당 ~150-225 MB, 시간당 ~9-13.5 GB

### 7.4 타임코드 동기화

**NTP 기반 소프트웨어 동기화 (권장):**

1. 세션 시작 시 모든 폰이 동일 NTP 서버 쿼리 (또는 디렉터 폰이 NTP 서버 역할)
2. 각 폰의 클럭 오프셋 계산
3. 보정된 NTP 시간 기반 타임코드 (HH:MM:SS:FF) 임베드:
   - MOV/MP4 파일 메타데이터 (타임코드 트랙)
   - H.264/H.265 비트스트림 내 SEI NAL 유닛
   - 사이드카 파일 (.csv 또는 .xml)

**정밀도**: ±10-50ms (LAN NTP), 멀티캠 편집에 충분

**폴백**: 오디오 파형 동기화 (DaVinci Resolve, Premiere Pro에서 자동 동기화)

### 7.5 디렉터 폰 녹화

- 디렉터 폰: **프로그램 출력만** 로컬 녹화
- 모든 ISO 스트림 재인코딩은 HW 인코더 한계 초과 → 시도하지 않음
- 각 카메라 폰의 로컬 ISO에 의존
- 스위칭 메타데이터 (EDL/XML) 생성 → NLE에서 바로 멀티캠 타임라인 생성

### 7.6 촬영 후 파일 수집

| 방법 | 속도 | 13.5GB 전송 시간 | 비고 |
|------|------|-----------------|------|
| USB-C 유선 | ~300 MB/s | ~45초 | **최빠르고 안정적** |
| AirDrop (iOS) | ~33 MB/s | ~7분 | iOS 전용 |
| WiFi Direct | ~20 MB/s | ~11분 | 1:1만 가능 |
| WiFi 네트워크 | 가변 | 가변 | 공유 대역폭 |

### 7.7 파일 명명 규칙

```
[프로젝트]_[카메라ID]_[날짜]_[테이크]_[타임코드].[확장자]

예시:
LiveShow_Cam1_20260211_Take1_14-30-00-00.mov
LiveShow_Cam2_20260211_Take1_14-30-00-00.mov
LiveShow_PGM_20260211_Take1_14-30-00-00.mov  (프로그램 출력)
```

**메타데이터 임베드:**
- 카메라 식별자 (릴 이름)
- 씬/테이크 번호
- 시작 타임코드
- 프레임 레이트
- 코덱 및 비트레이트 정보

---

## 8. HDMI 외부 출력

### 8.1 iOS 구현

**API**: `UIWindowScene` (Scene 기반 외부 디스플레이)

- iPhone 15+ USB-C → DisplayPort, 최대 4K HDR
- `UIWindowSceneSessionRoleExternalDisplayNonInteractive` Scene Role
- 외부 디스플레이: 비대화형 (터치 없음) → 프로그램 피드 전용
- 폰 내장 스크린: 스위칭 컨트롤 + 멀티뷰 프리뷰

**구현 단계:**
1. `UIScene.willConnectNotification` 감지 → 외부 디스플레이 연결 확인
2. 외부 디스플레이의 `UIWindowScene`에 전용 `UIWindow` 생성
3. 선택된 카메라의 디코딩된 비디오를 해당 윈도우에 렌더링
4. 내장 스크린에는 멀티뷰 + 스위칭 UI 표시

### 8.2 Android 구현

**API**: `Presentation` 클래스 + `DisplayManager`

- Android 4.2+부터 보조 디스플레이 지원
- `Presentation`: 보조 디스플레이에 렌더링하는 특수 `Dialog`
- DisplayPort Alt Mode 지원: Samsung Galaxy S 시리즈, Google Pixel (최근), Huawei Mate/P 시리즈

**구현 단계:**
1. `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)` → 외부 디스플레이 검색
2. `Presentation` 객체 생성 (타겟 디스플레이 지정)
3. Presentation Surface에 프로그램 출력 비디오 렌더링
4. 메인 폰 스크린: 프리뷰 그리드 + 스위칭 UI

### 8.3 실용적 고려사항

- USB-C to HDMI 어댑터: $15-30
- 충전 패스스루 지원 어댑터 필수 (장시간 프로덕션)
- Blackmagic Camera ProDock: iOS 전용, HDMI/SDI + 전원 공급

---

## 9. 키오스크 모드 (전용 카메라 디바이스)

### 9.1 Android 키오스크 (권장 플랫폼)

Android가 키오스크 모드에 훨씬 유연하여 **전용 카메라 디바이스로 권장**.

#### 방법 A: 커스텀 런처 (가장 간단, 루트 불필요)

```xml
<!-- AndroidManifest.xml -->
<activity android:name=".CameraKioskActivity"
          android:launchMode="singleTask"
          android:screenOrientation="landscape"
          android:keepScreenOn="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

**부팅 시 자동 시작:**

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver android:name=".BootReceiver" android:enabled="true" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

#### 방법 B: Lock Task Mode (Device Owner 필요)

```bash
# ADB로 Device Owner 설정 (팩토리 리셋 상태에서)
adb shell dpm set-device-owner com.camhub/.MyDeviceAdminReceiver
```

- 상태바 숨김, 알림 차단
- 홈/최근 버튼 비활성화
- 재부팅 후에도 유지

#### 방법 C: Android Management API (대규모 배포)

- Google Cloud 기반 무료 MDM
- 키오스크 정책 JSON으로 원격 배포
- OTA 앱 업데이트 지원

#### 방법 D: 서드파티 (Fully Kiosk, ~$7/디바이스)

- 코딩 없이 어떤 앱이든 키오스크화
- REST API 제공, 웹 관리 패널
- 자동 재시작, 부팅 시 자동 실행

### 9.2 iOS 키오스크

**Guided Access (MDM 없이):**
- 설정 → 손쉬운 사용 → Guided Access
- 앱 실행 후 사이드 버튼 3번 클릭으로 활성화
- **재부팅 시 해제됨** → 비신뢰성

**Single App Mode (MDM 필요):**
1. Apple Configurator 2로 감독 모드 (디바이스 초기화 필요)
2. MDM 등록 (Jamf Now 무료: 3대, Mosyle 무료: 30대)
3. Single App Mode 프로파일 푸시
4. 재부팅 후에도 자동 실행

### 9.3 전원 및 배터리 관리

**상시 전원 ON:**
- Android: `FLAG_KEEP_SCREEN_ON` + Developer Options → Stay Awake
- iOS: Auto-Lock → Never + `isIdleTimerDisabled = true`

**배터리 보호:**

| 전략 | Android | iOS |
|------|---------|-----|
| 충전 제한 | Samsung "배터리 보호" (85%) | iPhone 15+ (80%) |
| 스마트 플러그 | WiFi 플러그로 80%↑ OFF, 30%↓ ON 사이클 | 동일 |
| 바이패스 충전 | 일부 산업용 기기 지원 | 미지원 |

**열 관리:**
- 케이스 제거
- 등면 공기 노출 마운트
- USB 팬 사용 (장시간)
- 720p/15fps 스트리밍 (발열 최소화)
- 화면 밝기 최소화

### 9.4 네트워크 자동 재연결

```kotlin
// Android: 네트워크 모니터링
val connectivityManager = getSystemService(ConnectivityManager::class.java)
val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        restartCameraStream()  // 재연결 시 스트리밍 재시작
    }
    override fun onLost(network: Network) {
        pauseStream()  // 연결 끊김 시 일시정지
    }
}
```

- 지수 백오프 재연결: 1s → 2s → 4s → 8s → 16s → 32s → 60s 캡
- 라우터에서 DHCP 예약 (MAC 기반 고정 IP)
- mDNS로 이름 기반 디스커버리 (`kitchen-cam.local`)

### 9.5 원격 관리

**디바이스 헬스 리포팅:**

```json
{
  "battery_level": 85,
  "battery_temperature": 38.5,
  "is_charging": true,
  "wifi_signal_dbm": -45,
  "free_memory_mb": 1024,
  "uptime_seconds": 86400,
  "stream_status": "streaming",
  "current_fps": 29.97,
  "timestamp": 1707638400
}
```

- 60초마다 디렉터 폰/서버에 POST
- 또는 로컬 HTTP 엔드포인트로 폴링

### 9.6 권장 전용 카메라 하드웨어

- **Android**: Google Pixel 3a/4a/5a (좋은 카메라, 스톡 Android, 저렴한 중고)
- **iOS**: iPhone SE 2/3세대, iPhone 8 (저렴, 컴팩트, 충분한 카메라)
- 플래그십 폰은 상시 구동 열 부하에 부적합

---

## 10. PC/Mac 확장

### 10.1 확장 시나리오

```
스마트폰 카메라들 ──WebRTC──> 디렉터 폰 ──NDI/WHIP──> PC/Mac
                                                      │
                                               ┌──────┴──────┐
                                               │ OBS Studio  │
                                               │ vMix        │
                                               │ Wirecast    │
                                               └─────────────┘
```

### 10.2 연동 프로토콜

**NDI 출력 (LAN):**
- 디렉터 폰/앱에서 NDI SDK로 프로그램 출력 송출
- OBS (NDI 플러그인), vMix, TriCaster에서 바로 수신
- mDNS 자동 디스커버리

**WHIP/WHEP (WebRTC):**
- OBS v30+에서 네이티브 WebRTC 지원 (WHIP 프로토콜)
- 폰에서 직접 OBS로 WebRTC 스트림 전송 가능
- 초저지연 (<100ms)

**SRT:**
- OBS, vMix 모두 SRT 입력 지원
- 네트워크 불안정 시 WebRTC보다 안정적
- 조절 가능한 지연 (50ms~수초)

### 10.3 데스크톱 디렉터 앱

**기술 스택:**

| 컴포넌트 | 선택 | 이유 |
|---------|------|------|
| 프레임워크 | Electron + React | 크로스플랫폼, WebRTC 내장 (Chromium) |
| WebRTC SFU | LiveKit (오픈소스, Go) | 프로덕션 레디, 네이티브 SDK 전 플랫폼 |
| NDI | NDI SDK (C/C++) + 네이티브 래퍼 | 프로 LAN 연동 |
| 비디오 인코딩 | H.264 (호환) / H.265 (효율) | HW 인코딩 |
| 오디오 코덱 | Opus | 최고 품질/비트레이트, WebRTC 기본 |
| LAN 디스커버리 | mDNS/Bonjour (DNS-SD) | 제로 설정 |

**핵심 기능:**
- 멀티뷰 프리뷰 (2×2, 3×3, 커스텀 레이아웃)
- Program/Preview 패러다임 (방송 표준)
- 키보드 숏컷 스위칭
- 트랜지션 (컷, 디졸브, 와이프)
- 오디오 믹서
- 그래픽 오버레이 (하단 자막, 로고)
- Stream Deck 연동
- OBS 가상 카메라 출력
- NDI 출력
- RTMP 스트리밍 (YouTube, Twitch, Facebook)

### 10.4 LiveKit SFU 아키텍처

```
┌──────────────────────────────────────────────────────┐
│                LiveKit 서버 (SFU)                      │
│  • 미디어 라우팅 (선택적 전달)                           │
│  • Room 관리                                          │
│  • Simulcast 지원                                     │
│  • 서버사이드 녹화 (Egress)                             │
│  • RTMP 출력 (Egress)                                 │
│                                                      │
│  배포 옵션:                                            │
│  • 디렉터 PC에 임베디드 (LAN 전용, 인터넷 불필요)         │
│  • 클라우드 호스팅 (원격 카메라 지원)                     │
│  • LiveKit Cloud (관리형)                               │
└──────────────────────────────────────────────────────┘
```

---

## 11. 개발 로드맵

### Phase 1: MVP (3-4개월)

**목표**: 2대 카메라 → 1대 디렉터, 기본 스위칭

| 항목 | 세부 내용 |
|------|----------|
| iOS 카메라 앱 | AVFoundation + VideoToolbox, H.265 인코딩, WebRTC 전송 |
| 디렉터 앱 (폰) | 멀티뷰 2분할, 컷 스위칭, 프로그램 출력 |
| 네트워크 | WebRTC P2P, mDNS 디스커버리 |
| 기본 UI | 카메라 전환, 탈리 라이트 (소프트웨어) |
| 출력 | HDMI 클린 출력 (프로그램 피드) |

### Phase 2: 핵심 기능 (2-3개월)

**목표**: 4대 카메라, 트랜지션, 녹화

| 항목 | 세부 내용 |
|------|----------|
| Android 카메라 앱 | Camera2 + MediaCodec, Vulkan 파이프라인 |
| 카메라 확장 | 4대 동시 수신/디코딩 |
| 트랜지션 | 디졸브, 와이프, 딥 투 블랙 |
| GPU 업스케일링 | MetalFX (iOS) / Arm ASR (Android) |
| 로컬 녹화 | 프로그램 출력 녹화 (디렉터) |
| 오디오 | 소스 선택, 레벨 조절 |
| 리턴 비디오 | 카메라 오퍼레이터에 프로그램 PiP |

### Phase 3: ISO 녹화 + 프로 기능 (3-4개월)

**목표**: 각 카메라 독립 녹화, NLE 연동

| 항목 | 세부 내용 |
|------|----------|
| ISO 녹화 | 각 카메라 로컬 고화질 녹화 (Encode-Once) |
| 타임코드 | NTP 동기화 + SEI 임베딩 |
| NLE 연동 | DaVinci Resolve/Premiere 프로젝트 파일 생성 |
| 컬러 매칭 | 카메라별 WB 락 + 3D LUT |
| Apple Log | iPhone 15 Pro+ 로그 캡처 + 실시간 LUT 프리뷰 |
| 파일 관리 | 자동 명명, 메타데이터 임베딩, USB 전송 |

### Phase 4: PC 확장 + 키오스크 (3-4개월)

**목표**: 데스크톱 디렉터, 전용 카메라 모드

| 항목 | 세부 내용 |
|------|----------|
| 데스크톱 디렉터 | Electron + LiveKit, NDI 입출력 |
| PC 연동 | WHIP/WHEP, NDI, OBS 가상 카메라 |
| 키오스크 모드 | Android 커스텀 런처 + BOOT_COMPLETED |
| 원격 관리 | 디바이스 헬스 모니터링, OTA 업데이트 |
| 8+ 카메라 | SFU (LiveKit) + Simulcast |
| 그래픽 | 하단 자막, 로고 오버레이 |
| 고급 기능 | Stream Deck, 인스턴트 리플레이 |

### Phase 5: 확장 및 최적화 (지속)

| 항목 | 세부 내용 |
|------|----------|
| AI 기능 | 오토 스위칭 (오디오/모션 기반), 오토 프레이밍 |
| 멀티 플랫폼 스트리밍 | YouTube + Twitch + Facebook 동시 |
| 웹 디렉터 | 브라우저 기반 클라우드 디렉터 |
| 16+ 카메라 | 다중 SFU, 10GbE 지원 |
| 가상 세트 | 크로마 키, 3D 환경 |

---

## 12. 차별점 및 시장 포지셔닝

### 12.1 유일한 가치 제안

**"추가 하드웨어 없이, 스마트폰만으로 프로페셔널 멀티캠 프로덕션"**

| 차별점 | CamHub Pro | Switcher | Blackmagic | Canon | VDO.Ninja |
|--------|-----------|----------|-----------|-------|-----------|
| 크로스플랫폼 | **iOS+Android** | iOS만 | iOS+Android | iOS만 | 브라우저 |
| 라이브 스위칭 | **O** | O | X | O | OBS 필요 |
| ISO 녹화 | **O** | 부분 | O | X | X |
| HDMI 출력 | **O** | 어댑터 | O | X | X |
| 추가 HW 불필요 | **O** | O | O | O | OBS PC |
| 키오스크 모드 | **O** | X | X | X | X |
| GPU 업스케일링 | **O** | X | X | X | X |
| 무료/저가 | **O** | $49+/월 | 무료 | 무료 | 무료 |
| PC 확장 | **O** | X | X | X | O |

### 12.2 타겟 시장

| 세그먼트 | 규모 | 지불 의향 | 핵심 니즈 |
|---------|------|---------|----------|
| 교회/종교시설 | 미국만 ~38만 | $50-200/월 | 예배 멀티캠 라이브스트림 |
| 학교/대학 | 대규모 | 예산 제한 | 이벤트, 강의, 스포츠 |
| 소규모 프로덕션 | 성장 중 | $200-500 일회 | 클라이언트 이벤트 |
| 콘텐츠 크리에이터 | 대규모 | $10-50/월 | 멀티 앵글 콘텐츠 |
| 기업 이벤트 | 대규모 | 높음 | 타운홀, 제품 런칭 |
| 아마추어 스포츠 | 대규모 | $20-50/월 | 경기 스트리밍 |
| 팟캐스터 (영상) | 급성장 | $10-30/월 | 멀티캠 팟캐스트 녹화 |

### 12.3 가격 전략 (안)

| 티어 | 가격 | 내용 |
|------|------|------|
| **Free** | 무료 | 2대 카메라, 720p, 워터마크, 기본 스위칭 |
| **Pro** | $9.99/월 또는 $79.99/년 | 4대 카메라, 1080p, 워터마크 없음, 오버레이, 녹화 |
| **Studio** | $24.99/월 또는 $199.99/년 | 8대 카메라, 4K, ISO 녹화, NDI 출력, 커스텀 브랜딩 |
| **Enterprise** | 문의 | 16+대 카메라, 우선 지원, 커스텀 연동 |

---

## 13. 기술 리스크 및 대응

### 13.1 주요 리스크

| 리스크 | 심각도 | 확률 | 대응 전략 |
|--------|-------|------|----------|
| **WiFi 불안정** (혼잡 환경) | 높음 | 중간 | 전용 5GHz 라우터, 적응형 비트레이트, SRT 폴백 |
| **열 쓰로틀링** (장시간 촬영) | 높음 | 높음 | 적응형 파이프라인, 1080p 타겟, 외부 쿨링 |
| **HW 디코더 한계** (4대 동시) | 중간 | 낮음 | 720p 스트리밍, 선택 카메라만 풀 해상도 |
| **배터리 열화** (상시 충전) | 중간 | 높음 | 충전 제한, 스마트 플러그 사이클링 |
| **Android 파편화** | 중간 | 높음 | CameraX 추상화, 디바이스별 프로파일 |
| **HDMI 어댑터 호환성** | 낮음 | 중간 | 인증 어댑터 목록 제공, USB-C 직접 |
| **오디오 동기화** | 중간 | 중간 | NTP 타임스탬프, WebRTC 내장 동기화 |
| **앱 백그라운드 킬** | 높음 | 중간 | 포그라운드 서비스 (Android), 오디오 세션 (iOS) |

### 13.2 기술적 제약 요약

| 항목 | 제약 | 대응 |
|------|------|------|
| 동시 HW 인코드 | iOS 2, Android 2-3 세션 | Encode-Once 전략 |
| 동시 HW 디코드 | 4-6 세션 (1080p) | 720p 스트리밍 + GPU 업스케일 |
| WiFi 대역폭 | 실효 200-400 Mbps | 720p × 4 = 20 Mbps (여유) |
| 배터리 드레인 | 30-40%/시간 (녹화+스트리밍) | 외부 배터리/전원 필수 |
| 스토리지 | H.265 1080p ~10 GB/시간 | 128GB+, 외장 SSD |
| 열 쓰로틀링 | 4K 15-30분, 1080p 30-60분 | 1080p 타겟, 쿨링 |

---

## 부록 A: 기술 참고 자료

### 프로토콜 및 네트워크
- WebRTC: [webrtc.org](https://webrtc.org/)
- NDI SDK: [ndi.video](https://ndi.video/)
- SRT: [github.com/Haivision/srt](https://github.com/Haivision/srt)
- LiveKit: [livekit.io](https://livekit.io/)
- VDO.Ninja: [github.com/steveseguin/vdo.ninja](https://github.com/steveseguin/vdo.ninja)

### iOS 개발
- AVFoundation: [developer.apple.com/avfoundation](https://developer.apple.com/documentation/avfoundation)
- VideoToolbox: [developer.apple.com/videotoolbox](https://developer.apple.com/documentation/videotoolbox)
- MetalFX: [developer.apple.com/metalfx](https://developer.apple.com/documentation/metalfx)
- Metal Performance Shaders: [developer.apple.com/mps](https://developer.apple.com/documentation/metalperformanceshaders)
- MetalPetal: [github.com/MetalPetal/MetalPetal](https://github.com/MetalPetal/MetalPetal)

### Android 개발
- Camera2 API: [developer.android.com/camera2](https://developer.android.com/reference/android/hardware/camera2/package-summary)
- MediaCodec: [developer.android.com/mediacodec](https://developer.android.com/reference/android/media/MediaCodec)
- Vulkan: [vulkan.org](https://www.vulkan.org/)
- Arm ASR: AMD FSR 2.2 기반 모바일 최적화
- NCNN: [github.com/Tencent/ncnn](https://github.com/Tencent/ncnn)

### 경쟁 제품
- Switcher Studio: [switcherstudio.com](https://www.switcherstudio.com/)
- Blackmagic Camera: [blackmagicdesign.com](https://www.blackmagicdesign.com/products/blackmagiccamera)
- Canon Live Switcher: [usa.canon.com](https://www.usa.canon.com/mobile-apps/live-switcher-mobile)
- Mevo Multicam: [mevo.com](https://mevo.com/pages/multi-camera-app)

---

## 부록 B: 용어 사전

| 용어 | 설명 |
|------|------|
| **ISO 녹화** | 각 카메라의 개별 독립 녹화 (Isolated Recording) |
| **프로그램 출력** | 스위칭된 최종 영상 출력 (Program Out) |
| **탈리 라이트** | 카메라 상태 표시등 (빨강=라이브, 초록=프리뷰) |
| **SFU** | Selective Forwarding Unit - 선택적 미디어 전달 서버 |
| **Glass-to-Glass** | 카메라 렌즈부터 모니터 화면까지의 총 지연시간 |
| **Zero-Copy** | CPU 메모리 복사 없이 GPU 버퍼를 직접 공유하는 기법 |
| **Encode-Once** | 한 번의 인코딩으로 로컬 녹화 + 네트워크 전송 동시 수행 |
| **mDNS** | Multicast DNS - 로컬 네트워크 제로 설정 디스커버리 |
| **NTP** | Network Time Protocol - 네트워크 시간 동기화 |
| **SEI NAL** | Supplemental Enhancement Information - 비디오 비트스트림 내 메타데이터 |
| **LUT** | Look-Up Table - 색상 변환 테이블 |
| **MetalFX** | Apple의 GPU 기반 업스케일링 프레임워크 |
| **Arm ASR** | Arm Accuracy Super Resolution - 모바일 GPU 업스케일링 |
| **EDL** | Edit Decision List - 편집 결정 목록 |

---

*본 기획서는 6개의 독립 리서치 에이전트 분석 결과를 종합하여 작성되었습니다.*
*최종 기술 선정 및 구현 시 프로토타입 검증이 필요합니다.*
