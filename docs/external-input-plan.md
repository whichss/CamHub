# CamHub 외부 입력 지원 계획

## 개요
폰에서 내장 카메라 외에 외부 영상 소스를 입력받아 CamHub 카메라 소스로 활용하는 기능.

---

## 1단계: 폰 외부 입력 지원

### Camera2 외부 카메라 경로 (구현)
- OS가 USB/UVC 장치를 `LENS_FACING_EXTERNAL` Camera2 기기로 노출하면 기존 CameraX 촬영·인코딩 경로에서 선택 가능
- 내부 카메라가 없는 Android 기기에도 앱을 설치할 수 있도록 카메라 및 USB host 기능은 선택 사항으로 선언
- 이 경로는 제조사 외부 카메라 provider 지원에 의존하며, USB 권한·대역폭·인코더 조합은 실기기 검증 필요

### USB 웹캠 (UVC)
- 직접 UVC/libusb 접근은 미구현. Camera2 외부 provider가 장치를 제공하지 않는 기기의 지원에 필요
- USB-C OTG를 통해 웹캠 연결
- Android UVC 라이브러리 통합 필요 (ex: UVCCamera, libusb)
- 기존 Camera API가 아닌 UVC 전용 캡처 파이프라인 추가
- 연결 시 카메라 소스 목록에 자동 추가

### HDMI 캡처카드
- DSLR, 캠코더 등의 HDMI 출력 → USB 캡처카드 → 폰
- UVC 장치로 인식되므로 웹캠과 동일한 파이프라인 사용
- 전문 카메라 화질을 폰을 통해 CamHub에 연결 가능

### 구현 포인트
- CameraController에 입력 소스 타입 추가 (INTERNAL / UVC)
- GL 렌더링 파이프라인은 동일하게 재사용
- 해상도/프레임레이트 협상 로직 필요

---

## 2단계: 노트북 웹 클라이언트 (선택)

### 웹 관리자 모드
- 브라우저에서 접속하는 관리자 UI
- WebSocket으로 폰들과 통신
- PVW/PGM 뷰, 스위칭, 카메라 제어

### 웹 카메라 송출
- 브라우저 `getUserMedia` API로 웹캠/화면 캡처
- WebRTC 또는 WebSocket으로 스트림 전송
- CamHub 네트워크에서 하나의 카메라 소스로 인식

---

## 우선순위
1. **폰 UVC 입력** — 핵심 기능, 먼저 구현
2. **노트북 웹 클라이언트** — 부가 기능, 이후 확장

## 비고
- 네트워크 구성: 같은 공유기(LAN) 내에서 동작, 공유기는 데이터 전달만 담당
- 노트북은 유선(LAN 포트) 연결 시 안정성 확보 가능
