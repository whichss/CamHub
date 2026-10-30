# Optimization notes

## Cipher object reuse: preliminary host-only comparison

An exploratory AES-256-GCM microbenchmark on arm64 macOS with Android Studio's
JetBrains Runtime 21.0.8 encrypted 12,000 separate 8 KiB inputs per run. Each
input had a unique 12-byte nonce; both alternatives performed `init` and
`doFinal` on every input. After 2,000 warmup operations per alternative, three
paired measurements were:

| New `Cipher` each input | Reused `Cipher` on one thread |
| ---: | ---: |
| 84 ms | 51 ms |
| 65 ms | 41 ms |
| 47 ms | 32 ms |

This is a simple host JVM comparison, not a controlled Android benchmark, an
end-to-end latency result, or a guarantee of a performance improvement. JIT,
provider, device, encryption size and other workloads can change the outcome.
The observed difference is about 1–3 microseconds per input in these runs.
The production implementation retains a fresh `Cipher` per operation: a
thread-local reuse strategy would need Android provider measurements and a
careful key/lifecycle and concurrency audit. The production path writes
ciphertext directly into the final output array and gives GCM the IV slice from
the input without creating a separate IV array. Never reuse a nonce with a key.

The sender's logged wall-time metrics cover queue residence, packetization plus
socket sends, socket sends, and keyframes; they are not Wi-Fi airtime or
camera-to-screen latency. UDP sender durations retain microsecond resolution so
sub-millisecond packetization and socket-call costs are visible. Camera GL
preview and encoder submission durations now also retain microsecond resolution;
they remain CPU wall time including buffer swaps, not GPU execution time.
Camera GL viewport calculation reuses a GL-thread-owned four-element array
instead of allocating a new array for preview and encoder output on each frame.
Device/Wi-Fi measurements are needed before selecting pacing or throttling
policies.

## Recording sample copies

Once the recorder's `MediaMuxer` has started, encoded video and audio samples
are written synchronously from the active `MediaCodec` output buffer before it
is released, avoiding an intermediate per-sample `ByteArray`. Samples received
while waiting for track formats are still copied into the bounded pre-mux queue,
because those codec buffers must be released before the muxer is ready. Unit
tests compile this path but cannot validate encoded-file playback or device
codec buffer behavior; verify recordings on representative Android devices.

## RTP packet buffer reuse

The UDP sender now packetizes into one fixed 1,200-byte scratch buffer and sends
each fragment synchronously to its destinations before overwriting that buffer.
This removes the per-fragment `ByteBuffer`, packet array, and packet-list
allocations from this sender path. The owned-array `packetize` API remains
available for callers that need to retain packets. A protocol test checks that
the reusable path emits byte-identical RTP datagrams. This proves wire-format
equivalence, not a measured throughput or latency gain on Android hardware.

The receive reassembler parses RTP header fields directly from the datagram and
reuses one mutable packet view per camera. It keeps an immutable metadata
snapshot only when a new frame enters the pending set, and copies fragment bytes
once into that frame's owned buffer. Tests compare the reusable parser fields
with the public decoder and cover reordered, duplicate, malformed and expired
frames.

## Surface encoder and live audio copies

Camera-screen setup now starts/configures the surface encoder on an IO dispatcher;
CameraX binding remains on the main dispatcher. Cancellation before the GL
surface is ready releases locally owned encoder/renderer resources. This removes
codec initialization from the UI call stack, but device startup latency still
needs to be measured on representative phones.

For surface-encoded video, the MediaCodec output buffer is copied directly into
the final CamHub v4 payload while the codec callback still owns it. This avoids
an intermediate access-unit `ByteArray`; the final payload remains independently
owned by the bounded UDP sender queue. A protocol test checks that the borrowed
buffer encoder produces the same envelope as the owned-array encoder. This is a
structural copy reduction, not a measured throughput result.

The live audio mixer now passes its reusable output array synchronously to its
single recorder tap. The recorder copies accepted chunks into its own bounded
queue before returning, so one redundant intermediate copy is removed without
letting the queue retain a buffer that the mixer will reuse.

GPU completion P95 sorting is refreshed once per four completed samples rather
than on every sampled fence; the sample count still updates for each completion.
This reduces diagnostic sorting/allocation frequency, with up to four samples
of reporting lag. Bitmap readback targets are limited to the active PGM/PVW
bootstrap consumers and any explicitly required external bitmap output; inactive
camera-grid feeds use the shared GPU surface without continuous CPU readback.
Frame dimensions and rotation are now taken from shared GPU frames so off-screen
camera cards do not need Bitmap readback just to determine their aspect ratio.
Target selection has unit coverage. Device-side readback cost still needs runtime
measurement.

## Desktop image ownership and hub ingress bursts

The desktop decoder now publishes JavaCV's converted image directly instead of
copying every decoded frame into another `BufferedImage`. Painting and subsequent
conversion of a reused image share a per-image guard, so independent cameras do
not serialize on one global pixel lock. Unit tests cover direct image publication
and mutation-vs-paint synchronization; multi-window visual behavior and the
converter's reuse behavior still need a desktop runtime check. This removes one
full-frame copy in the application path, but is not yet a measured throughput
result.

The Android hub aggregates packet arrivals from camera receiver threads into
aligned 5 ms buckets and reports one-second throughput, P95/P99 and maximum
5 ms bursts, plus the maximum 20 ms burst when at least two cameras are active.
Simulation tests distinguish synchronized from spread arrivals, and a loopback
integration test sends four simultaneous camera streams through independent UDP
sender sockets into one receiver while validating the aggregated counters. These
host-side checks validate instrumentation and socket integration, not Wi-Fi airtime
or radio contention. This is diagnostic only: no UDP pacing policy was enabled,
and actual Wi-Fi behavior must be measured before making that tradeoff.
