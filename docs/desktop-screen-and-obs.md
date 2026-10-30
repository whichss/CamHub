# Desktop screen source and OBS output

The desktop hub can capture a rectangular area from a selected monitor. Choose a display, drag over
the desired area, and press Enter to accept the outlined region; Escape cancels. The selected area
appears as a separate PVW source. Use CUT or AUTO to send it to PGM. Camera slots and desktop capture are represented as
separate source types, so screen capture does not consume one of the four camera slots. Capture is isolated on its own worker
and the UI consumes only the newest frame. The same monitor cannot be used for both screen capture
and PGM fullscreen output, preventing recursive feedback. The screen-area picker also rejects a
region overlapping the visible CamHub control window; minimize or move the control window outside the
selected region to avoid capturing CamHub's own controls. The desktop capture can also be started as
a separate network camera source without consuming one of the four camera slots.

The OBS action exposes the current PGM as a loopback-only multipart MJPEG stream capped at
1920×1080 and 30 fps. CamHub connects to OBS's local WebSocket server (default port 4455) and adds
or updates a `CamHub PGM` built-in Media Source in the current program scene. The source reads the
`mpjpeg` stream through OBS's FFmpeg media input with buffering set to 0 MB and reconnect delay set
to 1 second; this avoids the Browser Source rendering path and shortens reconnect waits, but actual
end-to-end latency still needs measurement in OBS.
The WebSocket password is used for that connection only and is not saved. Existing `CamHub PGM`
Browser Sources continue to be updated for compatibility. If OBS is unavailable or the connection
fails, CamHub copies the direct stream URL for manual setup in OBS's Media Source (`Input Format:
mpjpeg`, `Buffering MB: 0`, `Reconnect Delay: 1 second`). The server prefers
`http://127.0.0.1:48765/` so the stream URL can remain stable across restarts. If that port is
occupied, it uses another local port and exposes the actual URL.
Up to two simultaneous video connections are supported; further connections receive HTTP 503 until
a slot is available. JPEG encoding is shared across clients for an unchanged PGM frame. The OBS
control path configures OBS's built-in Media Source through WebSocket; a separately installed native
OBS plugin is not included. This path does not change the Android or shared video wire protocol.

Local integrations can poll `/healthz` for `status`, `pgm` (`active` or `no_signal`),
`transitionAlpha`, `activeClients`, `framesSent`, and `lastFrameAgeMs` (`null` before the first
frame). The desktop UI distinguishes waiting for a stream, an active stream, no PGM signal, and a
stream stalled for more than two seconds. These counters show local HTTP delivery; they do not
measure OBS rendering or end-to-end presentation latency. The endpoint is served by the same
loopback-only HTTP server and does not expose camera or network details.

PVW selection does not change the live program source. CUT switches immediately. AUTO performs a
300 ms fade to black, switches at black, and fades the new source in. The same transition is applied
to the local PGM preview, fullscreen output, and OBS Media Source.

When a camera is in PVW, drag the PVW output to pan, use the mouse wheel to zoom around the pointer,
and double-click to zoom toward that point or reset the framing. CUT/AUTO copies the preview framing
to PGM. When a camera advertises remote PTZ, the hub sends a debounced `set_ptz` request and uses the
`ptz_applied` response to reduce its temporary local crop. Cameras without remote PTZ keep the full
framing in the hub. The **LIVE PTZ LOCK** toggle enables direct edits on the PGM output; with it off,
PGM remains unchanged while the operator sets up a shot.

The hub footer reports per-camera capture-to-decode P50/P95/P99 over a rolling 300-frame
window and shows the highest value for each percentile across connected cameras. It is available
only when capture timestamps and clock synchronization are valid. It ends when H.264 decoding
completes; Swing painting and monitor presentation are not included, so it is not an end-to-end
camera-to-screen measurement.

Use **STOP OBS OUTPUT** to close the local HTTP server when OBS no longer needs the source.

Use **RECORD PGM** to save the live program bus as an H.264 MP4 file. Choose a destination in the
save dialog; the recorder does not start automatically. The recording includes the same 300 ms
AUTO fade-to-black seen in the PGM output. Encoding and disk writes use a dedicated worker with a
single latest-frame slot, so a slow disk or encoder drops stale recording frames instead of
building an unbounded queue or blocking camera reception and the UI. The recording runs at 1080p30
with a low-latency H.264 encoder when available and falls back to the bundled OpenH264 encoder.
Finalization completes after **SAVING…** returns to the saved state. If encoding or file writing
fails, the status area reports the error; check that the destination has enough free space.

When the PGM source is explicitly cleared, the OBS stream sends black instead of retaining the
last frame. If no decoded camera frame arrives for eight seconds, the desktop hub reconnects that
camera and clears its last PGM frame. Live behavior and recovery still require device verification.

The desktop can now be started as a discoverable CamHub camera source. It advertises through mDNS
on multicast-capable IPv4 and IPv6 interfaces, plus the Android-compatible IPv4 UDP broadcast
fallback. Discovery prefers IPv4 when available and uses IPv6 endpoints on IPv6-only networks. It
accepts the existing TLS + fingerprint/PIN pairing flow,
and subscribes the hub's announced UDP receive endpoint only after authentication. Screen capture
feeds a latest-frame-only encoder worker; the H.264 encoder defaults to 720p30, no B-frames, a short
GOP, and repeated SPS/PPS headers. A bounded UDP/RTP publisher uses the existing CamHub payload and
AES-GCM format and requests an IDR after queue loss or a new subscription. It also honors
authenticated `request_key_frame` commands from the hub so decoding can recover promptly after
packet loss. Encoding and network I/O do not run on the Swing UI or screen-capture worker. The
sender tries the platform H.264 hardware encoder (VideoToolbox on macOS, Media Foundation on
Windows), verifies that it emits consecutive Annex-B access units with SPS/PPS/IDR for the live
RTP path, and falls back to libx264 or OpenH264 if that runtime check fails. The result is cached
per process and stream format so reconnects do not repeat the probe.

The macOS arm64 app ZIP and Windows x64 installer have both been generated and verified by the
`Desktop packages` GitHub Actions workflow. The Windows installer is produced on a Windows host with
the JDK and WiX toolchain; the macOS bundle is produced on an arm64 macOS host. Neither packaged app
was launched. Screen-capture permissions, installation behavior, and camera-to-hub interoperability
still need runtime verification on each platform. The camera decoder now requests VideoToolbox on
macOS or D3D11VA on Windows when the bundled FFmpeg H.264 decoder advertises that hardware device.
Decoded GPU frames transfer to host memory before Java2D rendering. Missing hardware support or a
failed device setup selects FFmpeg's software format; a failed hardware-frame transfer reopens the
software decoder and waits for a new keyframe. The camera connection detail shows which path
produced the latest frame. Integration tests cover native H.264 decoding, a forced software decoding
path, and multi-camera UDP recovery. They do not simulate a GPU transfer failure or prove hardware
acceleration or a throughput gain on every user's GPU. GPU upscaling remains future work. The OBS path uses local
WebSocket control plus a loopback FFmpeg Media Source, not a separately installed native OBS plugin.

The Windows package derives its modular Java runtime from the installed distribution with `jdeps`
instead of relying on a hand-maintained module list. The `verifyDesktopRuntimeModules` Gradle task
checks core runtime modules such as `java.prefs` and `jdk.httpserver` before packaging.

The bundled FFmpeg 7.1 OpenH264 wrapper may log a profile warning: it reports profile value 578 as
unsupported and falls back to an unspecified profile. The encoder is left to choose its default;
the local smoke test produces Constrained Baseline output and decodes it, but this does not establish
compatibility on every receiver. Track this upstream wrapper behavior when updating the bundled
encoder.

On macOS, the user may need to grant Screen Recording permission to CamHub Desktop in System
Settings → Privacy & Security. Capture output remains black until permission is granted.
