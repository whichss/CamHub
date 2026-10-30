import CamHubCore
import CoreMedia
import Foundation
import Network

/// Coordinates one authenticated control channel and one encrypted UDP video stream.
final class CamHubHubCameraSession {
    private let queue = DispatchQueue(label: "com.camhub.hub.camera-session", qos: .userInitiated)
    private let endpoint: NWEndpoint
    private let pin: String
    private let verify: (String, @escaping (Bool) -> Void) -> Void
    private let onSample: (CMSampleBuffer, Int, Int64?, @escaping () -> Void) -> Void
    private let cameraID: String
    private let onAudioFrame: (String, CamHubPCMFrame, CMTime) -> Void
    private let onAudioStatus: (String, String) -> Void
    private let onError: (String) -> Void
    private let onStatus: (String) -> Void
    private let onCapabilities: (Bool, Float, Float) -> Void
    private let onPtzApplied: (CamHubControlMessage) -> Void
    private var control: CamHubHubControlConnection?
    private var video: CamHubHubVideoStream?
    private var audio: CamHubHubAudioStream?
    private var audioHost: NWEndpoint.Host?
    private var audioPort: UInt16 = 0
    private var audioSessionKey: Data?
    private var audioActive = false
    private var host: NWEndpoint.Host?
    private var generation: UInt64 = 0
    private var videoWatchdog: DispatchSourceTimer?
    private var lastSampleNanos: UInt64 = 0
    private var lastKeyFrameRequestNanos: UInt64 = 0
    private var clockEstimate: CamHubClockEstimate?
    private var clockEstimateNanos: UInt64 = 0
    private let sampleSlots = DispatchSemaphore(value: 2)

    init(endpoint: NWEndpoint, pin: String, cameraID: String,
         verify: @escaping (String, @escaping (Bool) -> Void) -> Void,
         onSample: @escaping (CMSampleBuffer, Int, Int64?, @escaping () -> Void) -> Void,
         onAudioFrame: @escaping (String, CamHubPCMFrame, CMTime) -> Void,
         onAudioStatus: @escaping (String, String) -> Void,
         onStatus: @escaping (String) -> Void,
         onCapabilities: @escaping (Bool, Float, Float) -> Void,
         onPtzApplied: @escaping (CamHubControlMessage) -> Void,
         onError: @escaping (String) -> Void) {
        self.endpoint = endpoint
        self.pin = pin
        self.cameraID = cameraID
        self.verify = verify
        self.onSample = onSample
        self.onAudioFrame = onAudioFrame
        self.onAudioStatus = onAudioStatus
        self.onStatus = onStatus
        self.onCapabilities = onCapabilities
        self.onPtzApplied = onPtzApplied
        self.onError = onError
    }

    func start() {
        queue.async {
            guard self.control == nil else { return }
            self.generation &+= 1
            let generation = self.generation
            let control = CamHubHubControlConnection(
                endpoint: self.endpoint, pin: self.pin, verify: self.verify,
                onRemoteHost: { [weak self] host in self?.queue.async {
                    guard let self, self.generation == generation else { return }; self.host = host
                } },
                onClockEstimate: { [weak self] estimate in self?.queue.async {
                    guard let self, self.generation == generation else { return }
                    let now = DispatchTime.now().uptimeNanoseconds
                    if self.clockEstimate == nil || now &- self.clockEstimateNanos >= 30_000_000_000 ||
                        estimate.roundTripMs < self.clockEstimate!.roundTripMs {
                        self.clockEstimate = estimate
                        self.clockEstimateNanos = now
                    }
                } },
                onMessage: { [weak self] message in self?.queue.async {
                    guard let self, self.generation == generation else { return }; self.handle(message, generation: generation)
                } },
                onError: { [weak self] error in self?.queue.async {
                    guard let self, self.generation == generation else { return }; self.fail(error)
                } }
            )
            self.control = control
            control.start()
        }
    }

    func stop() { queue.async { self.close() } }

    func requestKeyFrame() {
        queue.async { self.sendKeyFrameRequest() }
    }

    func setPtz(zoom: Double, centerX: Double, centerY: Double, requestId: UInt64) {
        guard zoom.isFinite, centerX.isFinite, centerY.isFinite, requestId > 0 else { return }
        var message = CamHubControlMessage(type: "command", command: "set_ptz")
        message.value = Float(zoom)
        message.stringValue = "\(centerX),\(centerY)"
        message.requestId = requestId
        queue.async { self.control?.send(message) }
    }

    func recoverVideo() {
        queue.async {
            self.video?.requireKeyFrame()
            self.sendKeyFrameRequest()
        }
    }

    func setAudioActive(_ active: Bool) {
        queue.async {
            guard self.audioActive != active else { return }
            self.audioActive = active
            if active {
                self.startAudioIfAvailable()
            } else {
                self.audio?.stop()
                self.audio = nil
            }
        }
    }

    private func handle(_ message: CamHubControlMessage, generation: UInt64) {
        if message.type == "ptz_applied" {
            onPtzApplied(message)
            return
        }
        if message.type == "capabilities" {
            onCapabilities(message.supportsRemotePtz, message.minZoomRatio, message.maxZoomRatio)
            return
        }
        if message.type == "error" {
            let detail: String
            switch message.stringValue {
            case "invalid_pairing_pin": detail = "Incorrect PIN. Check the camera and try again."
            case "pairing_rate_limited": detail = "Too many PIN attempts. Wait before trying again."
            default: detail = "The camera rejected the connection."
            }
            fail(detail)
            return
        }
        guard message.type == "hello_ack", video == nil else { return }
        onCapabilities(message.supportsRemotePtz, message.minZoomRatio, message.maxZoomRatio)
        guard let host, (1...65_535).contains(message.udpStreamPort),
              let key = Data(base64Encoded: message.sessionKey), key.count == 32 else {
            fail("Camera did not provide a valid encrypted UDP stream")
            return
        }
        audioHost = host
        audioPort = (1...65_535).contains(message.audioStreamPort) ? UInt16(message.audioStreamPort) : 0
        audioSessionKey = key
        if audioActive { startAudioIfAvailable() }
        onStatus("PREPARING VIDEO")
        do {
            let stream = try CamHubHubVideoStream(
                expectedHost: host, expectedPort: UInt16(message.udpStreamPort), sessionKey: key,
                onSample: { [weak self] sample, rotation, captureAt in
                    guard let self, self.sampleSlots.wait(timeout: .now()) == .success else { return false }
                    self.queue.async {
                        guard self.generation == generation else { self.sampleSlots.signal(); return }
                        self.lastSampleNanos = DispatchTime.now().uptimeNanoseconds
                        let delay: Int64?
                        if let captureAt, captureAt > 0, captureAt <= UInt64(Int64.max / 4),
                           let estimate = self.clockEstimate,
                           DispatchTime.now().uptimeNanoseconds &- self.clockEstimateNanos < 30_000_000_000 {
                            let now = Int64(Date().timeIntervalSince1970 * 1_000)
                            let value = now - (Int64(captureAt) - estimate.remoteOffsetMs)
                            delay = (0...60_000).contains(value) ? value : nil
                        } else { delay = nil }
                        self.onSample(sample, rotation, delay) { self.sampleSlots.signal() }
                    }
                    return true
                },
                onRecoveryRequired: { [weak self] in
                    self?.queue.async {
                        guard let self, self.generation == generation else { return }
                        self.sendKeyFrameRequest()
                    }
                },
                onError: { [weak self] error in self?.queue.async {
                    guard let self, self.generation == generation else { return }; self.fail(error.localizedDescription)
                } }
            )
            video = stream
            stream.start { [weak self, weak stream] port in
                self?.queue.async {
                    guard let self, let stream, self.video === stream else { return }
                    self.control?.send(CamHubControlMessage(type: "command", command: "udp_subscribe", udpReceivePort: Int(port)))
                    self.onStatus("WAITING FOR VIDEO")
                    self.startVideoWatchdog(generation: generation)
                }
            }
        } catch { fail(error.localizedDescription) }
    }

    private func fail(_ message: String) { close(); onError(message) }

    private func startAudioIfAvailable() {
        guard audio == nil else { return }
        guard let audioHost, audioPort > 0, let audioSessionKey else {
            onAudioStatus(cameraID, "NO AUDIO SOURCE")
            return
        }
        do {
            let stream = try CamHubHubAudioStream(
                host: audioHost, port: audioPort, sessionKey: audioSessionKey,
                onFrame: { [weak self] frame, time in
                    guard let self else { return }
                    self.onAudioFrame(self.cameraID, frame, time)
                },
                onStatus: { [weak self] status in
                    guard let self else { return }
                    self.onAudioStatus(self.cameraID, status)
                }
            )
            audio = stream
            stream.start()
        } catch {
            onAudioStatus(cameraID, "AUDIO ERROR · \(error.localizedDescription)")
        }
    }

    private func sendKeyFrameRequest() {
        let now = DispatchTime.now().uptimeNanoseconds
        guard lastKeyFrameRequestNanos == 0 || now &- lastKeyFrameRequestNanos >= 250_000_000 else { return }
        lastKeyFrameRequestNanos = now
        control?.send(CamHubControlMessage(type: "command", command: "request_key_frame"))
    }
    private func startVideoWatchdog(generation: UInt64) {
        lastSampleNanos = DispatchTime.now().uptimeNanoseconds
        videoWatchdog?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self, self.generation == generation else { return }
            if DispatchTime.now().uptimeNanoseconds &- self.lastSampleNanos > 8_000_000_000 {
                self.fail("Video stopped; reconnect the camera")
            }
        }
        videoWatchdog = timer
        timer.resume()
    }

    private func close() {
        generation &+= 1
        videoWatchdog?.cancel(); videoWatchdog = nil
        lastKeyFrameRequestNanos = 0
        video?.stop(); video = nil
        audio?.stop(); audio = nil
        audioHost = nil
        audioPort = 0
        audioSessionKey = nil
        audioActive = false
        control?.stop(); control = nil
        host = nil
        clockEstimate = nil
        clockEstimateNanos = 0
    }
}
