# CamHub for iOS

Build an unsigned device-target app bundle without launching it with
`sh ios/build-ios-device.sh Debug` or `sh ios/build-ios-device.sh Release`. The bundle is written
to `ios/build/<Configuration>-iphoneos/CamHubCamera.app`; code signing and device installation are
separate steps. This script passes the local Swift package product directory required by the
current Xcode build layout.

The iOS app now has separate camera and experimental hub screens. The hub screen discovers
Bonjour cameras, asks for the camera PIN and certificate fingerprint confirmation, and supports
up to four camera slots with a selected PGM and CUT. Each slot has its own TLS control and
encrypted UDP video path. A stalled video connection is stopped and recovered as follows.
After a camera has delivered video at least once, the hub retries network failures with a
bounded exponential backoff (1–30 seconds) while preserving its slot and PGM selection. The
previously approved certificate is accepted automatically only when its fingerprint matches;
a changed fingerprint still requires confirmation. PIN, fingerprint, or initial pairing failures
do not trigger retries; manually disconnecting or backgrounding the app clears the in-memory retry
credentials and fingerprint trust. The app stops hub connections when
backgrounded. Landscape places the multiview on the left and camera controls on the right; portrait
places controls below the video. The iOS app target builds for arm64/iOS 16 in both Debug and
Release configurations. Discovery, pairing, decoding, rotation, CUT, four-camera load and recovery
have not been validated on iOS devices or against Android/desktop cameras.
The UDP receiver also has a macOS loopback smoke test in `ios/IntegrationTests/UDPReceiverSmoke.swift`;
it exercises the same Network.framework receiver with IPv4 and IPv6 loopback, reordered encrypted
fragments and expiration of an incomplete frame, but does not
verify iPhone networking, TLS pairing, or camera interoperability.
Run `sh ios/IntegrationTests/run-udp-receiver-smoke.sh` on macOS to build and run that command-line
test without launching the app. It needs local loopback networking and Xcode command-line tools.
`sh ios/IntegrationTests/run-control-smoke.sh` separately checks a TLS certificate fingerprint,
PIN hello and acknowledgement, certificate refusal and incorrect PIN against a local test server.
The TLS connection times out after 30 seconds if it never becomes ready, including while waiting
for fingerprint confirmation. These checks do not validate Bonjour discovery,
real camera interoperability or behavior on an iPhone.

The camera screen can save its existing VideoToolbox H.264 output as a local MOV file without
opening a second encoder. Files are written under `Documents/CamHub Recordings` and can be shared
from the camera screen. Recording uses a two-sample bounded writer queue; if samples are dropped,
the writer waits for a new keyframe before resuming. This is camera-local recording, not hub PGM
recording.

The hub can record the selected PGM as an H.264 MP4 under `Documents/CamHub PGM Recordings` and
share the finished file. Recording is opt-in. While active, the hub decodes only the current PGM and
AUTO target, applies their rotation and local framing, follows the same 350 ms transition timeline,
and writes a program composite at up to 30 fps. A two-sample in-flight limit bounds decode and encode work;
when input is dropped, that source waits for a fresh keyframe. This adds video decode, Core Image
composition, H.264 encode, PCM mixing and AAC encode work while recording, so sustained performance
and heat still need device validation. `sh ios/IntegrationTests/run-pgm-recorder-smoke.sh` creates
two local VideoToolbox H.264 inputs, mixes generated PCM through a transition, and verifies that the
compositor writes a readable MP4 with both audio and video when the host provides a VideoToolbox
H.264 encoder. This host does not expose one, so the script reports a skip here; it does not run the
CamHub app or establish iPhone performance.

The iOS hub receives the paired camera's separately encrypted TCP audio stream when the camera
advertises an audio port. It parses the existing v1/v2 PCM payloads, plays only the selected PGM
source, and crossfades audio with AUTO. The hub panel includes PGM mute and master level controls.
Audio buffering is bounded and stale queued audio is discarded to limit added delay. Opus audio is
not decoded. PGM recordings convert PCM to 48 kHz mono, pair nearby PGM and AUTO-target packets,
crossfade them, and encode AAC. Unmatched packets older than 40 ms are faded using the current
transition gain to avoid growing latency. The current wire payload has no capture timestamp, so
playback and recording timing follow packet arrival and are not a measured lip-sync guarantee.
Camera interoperability, playback latency and sustained recording performance still need device
validation.

The iOS camera source also captures microphone PCM on a dedicated serial queue and sends it to the
paired hub through an AES-GCM protected TCP stream advertised in `hello_ack`. Native 16-bit or
32-bit integer/float PCM is converted to mono PCM16 while retaining the device sample rate. Audio
processing and network sends are skipped until a hub subscribes to the video stream. Microphone
permission is optional: denying it leaves video streaming available without camera audio. The
camera view reports `AUDIO READY` or `VIDEO ONLY`; end-to-end audio routing still needs device
validation. `sh ios/IntegrationTests/run-camera-audio-server-smoke.sh` checks the source server's
AES-GCM TCP framing against a loopback receiver without launching the app.

The hub reports an estimated capture-to-sample-queue P50/P95/P99 after clock synchronization.
It does not measure actual screen presentation, and clock accuracy and these values remain unverified.
The iOS hub provides a 350 ms AUTO video crossfade after the incoming slot's next keyframe is
available. The PGM layer and transition layer are separate decoders during the transition, so
decoder capacity and transition smoothness still need device validation. Actual end-to-end latency
measurements still need device validation. The hub sends `set_ptz` only to cameras that advertise
compatible remote PTZ and uses the
`ptz_applied` response to retain only the residual hub crop while the camera catches up. The iOS
camera source advertises remote PTZ when its selected camera format has extra pixels to crop beyond
the 720p output. Devices limited to 720p remain hub-crop only. PGM currently uses a second display
layer for the selected camera; its performance and scaling have not been measured.
The selected non-PGM slot supports local pinch, drag and double-tap framing, copied to PGM on CUT.
Direct PGM framing is disabled by default and can be enabled with the LIVE PTZ switch.
This enlarges the received image rather than requesting a camera-side crop, so detail can decrease.
Gesture behavior and picture framing have not been validated on a device.

The Xcode project source implements camera permission handling, a 720p30 AVCaptureSession preview,
a VideoToolbox H.264 encoder, a TLS camera-side control listener, Bonjour advertisement, a displayed
certificate fingerprint and pairing PIN, heartbeat/control handling, and encrypted UDP/RTP sending.
Encoded access units are handed off from the capture pipeline to a dedicated sender queue; stream
orientation is carried as CamHub payload metadata. H.264 encoding starts when a hub subscribes or
local camera recording is active,
and the sender bounds pending work by coalescing stale access units. If that would break the H.264
prediction chain, it drops deltas until requesting and receiving a fresh keyframe.
The authenticated control channel also honors the hub's `request_key_frame` command, allowing the
decoder recovery path to force a new IDR instead of waiting for the normal GOP interval. The sender
includes SPS/PPS in each IDR and resends cached configuration to the requesting hub. Local UDP
send failures request a rate-limited IDR while allowing the other hubs' P-frame queues to continue;
they no longer put every subscription into a global wait-for-keyframe state. The macOS
`ios/IntegrationTests/run-udp-video-sender-smoke.sh` loopback test compiles this sender and checks
one IPv6 and one IPv4-mapped hub endpoint with separate session keys, one hub losing a media
fragment, deadline expiry without a partial frame, recovery when the next keyframe arrives, and a
destination-specific local send failure while the healthy hub continues receiving P-frames.
It verifies the sender and shared ingress path, not feedback-control delivery or behavior on real
Wi-Fi/iPhones.
An authenticated hub can request an encoder bitrate between 1 and 20 Mbps. If multiple hubs
subscribe, the lowest requested value is used; subscriptions without a request do not impose
an additional limit. Encoder support for changing bitrate at runtime still needs device validation.
The iOS source selects the largest supported 16:9 camera format up to 1080p30, then crops and scales
to the 720p30 H.264 output. Authenticated `set_ptz` requests are applied to source pixels with a
reused, bounded Core Image pixel-buffer pool; the advertised remote zoom ceiling reflects the
available source-to-output resolution ratio. This preserves source detail up to that ratio. Beyond
it, the hub keeps the remaining digital crop. The source replies with `ptz_applied` only after a
matching frame has passed through VideoToolbox. Devices limited to 720p do not advertise remote
PTZ. Authenticated `set_zoom` remains a separate centered device-zoom command. The image-processing
cost, camera-format selection, and behavior across iPhone models still require device validation.
The desktop hub supports pan and zoom in PVW: drag to pan, use the mouse wheel to zoom around the
pointer, and double-click to zoom at a point or reset. When the camera advertises remote PTZ, the
hub sends a debounced `set_ptz` request and keeps a residual local crop until `ptz_applied` confirms
the source crop. Otherwise it uses a hub-side crop. CUT/AUTO copies the PVW framing to PGM. Direct
PGM manipulation requires **LIVE PTZ LOCK**.

The UDP sender binds a dual-stack IPv6 socket and supports both IPv4-mapped and native IPv6 peer
addresses, including interface scope for link-local IPv6. The shared payload, RTP and AES-GCM wire
vectors pass in the Swift core package tests. An authenticated, AES-GCM protected TCP video fallback
is included in the app target; its loopback smoke test verifies the session token, framing, codec
configuration and keyframe. The current app target builds and links into unsigned arm64/iOS 16
Debug and Release bundles without launching the app. The simulator remains unavailable because
this Mac's CoreSimulator components do not match the installed Xcode. Android/desktop
interoperability, IPv6-only hub receive behavior, device signing/install, and iPhone runtime behavior
remain unverified. SRT fallback is not implemented by the iOS camera source.

When the app enters the background or camera capture is interrupted, capture and the network
source stop. Returning to the app requires tapping Start Source. A camera runtime error also
stops the source and offers a retry; a network-only error can be retried while capture remains
active. These lifecycle paths have not yet been validated on an iPhone.

The project targets iOS 16 and includes camera/local-network usage strings plus `_camhub._tcp`
Bonjour registration permission.
