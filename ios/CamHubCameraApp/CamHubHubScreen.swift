import AVFoundation
import CoreMedia
import CamHubCore
import SwiftUI

private func fittedVideoSize(_ source: CGSize, rotation: Int, in viewport: CGSize) -> CGSize {
    CamHubFraming.fittedSize(source: source, rotation: rotation, viewport: viewport)
}

private final class HubCameraDisplay: ObservableObject {
    let layer = AVSampleBufferDisplayLayer()
    @Published var error: String?
    @Published var fingerprint: String?
    @Published var rotationDegrees = 0
    @Published var videoSize = CGSize(width: 16, height: 9)
    @Published var cameraID: String?
    @Published var cameraName: String?
    @Published var status = "WAITING"
    @Published var audioStatus = "AUDIO WAITING"
    @Published var zoom: CGFloat = 1
    @Published var pan = CGSize.zero
    @Published private(set) var desiredZoom = 1.0
    @Published private(set) var desiredCenterX = 0.5
    @Published private(set) var desiredCenterY = 0.5
    @Published private(set) var supportsRemotePtz = false
    @Published private(set) var maxPtzZoom = 4.0
    @Published private(set) var ptzPending = false
    @Published var latencySummary: String?
    private var verification: ((Bool) -> Void)?
    private var trustedFingerprint: String?
    private var session: CamHubHubCameraSession?
    private var generation: UInt64 = 0
    private var reconnectWorkItem: DispatchWorkItem?
    private var reconnectCamera: CamHubDiscoveredCamera?
    private var reconnectPin: String?
    private var reconnectAttempt = 0
    private var receivedVideo = false
    private var audioActive = false
    private var hybridPtz = CamHubHybridPtzState()
    private var remoteMinZoom = 1.0
    private var remoteMaxZoom = 4.0
    private var ptzRequestSequence: UInt64 = 0
    private var lastSentPtzRequest: UInt64 = 0
    private var lastAppliedPtzRequest: UInt64 = 0
    private var ptzWorkItem: DispatchWorkItem?
    private let latencySamples = CamHubLatencyWindow()
    private var lastLatencyUpdate: UInt64 = 0
    var onSample: ((CMSampleBuffer, Int) -> Void)?
    var onAudioFrame: ((String, CamHubPCMFrame, CMTime) -> Void)?
    var onStopped: (() -> Void)?
    var onRecovering: (() -> Void)?
    var onFramingChanged: (() -> Void)?

    func connect(_ camera: CamHubDiscoveredCamera, pin: String) {
        disconnect()
        cameraID = camera.id
        cameraName = camera.name
        reconnectCamera = camera
        reconnectPin = pin
        reconnectAttempt = 0
        receivedVideo = false
        hybridPtz = CamHubHybridPtzState()
        desiredZoom = 1; desiredCenterX = 0.5; desiredCenterY = 0.5
        supportsRemotePtz = false; maxPtzZoom = 4; ptzPending = false
        remoteMinZoom = 1; remoteMaxZoom = 4
        let requestNamespace = UInt64.random(in: 1...0x3fff_ffff) << 32
        ptzRequestSequence = requestNamespace
        lastSentPtzRequest = requestNamespace
        lastAppliedPtzRequest = requestNamespace
        error = nil
        status = "CONNECTING"
        audioStatus = "AUDIO STANDBY"
        startSession()
    }

    private func startSession() {
        guard let camera = reconnectCamera, let pin = reconnectPin else { return }
        let currentGeneration = generation
        layer.videoGravity = .resizeAspect
        let session = CamHubHubCameraSession(endpoint: camera.endpoint, pin: pin, cameraID: camera.id,
            verify: { [weak self] fingerprint, reply in
                DispatchQueue.main.async {
                    guard let self, self.generation == currentGeneration else { reply(false); return }
                    if self.trustedFingerprint == fingerprint {
                        reply(true)
                        return
                    }
                    self.status = "VERIFY CAMERA"
                    self.fingerprint = fingerprint; self.verification = reply
                }
            },
            onSample: { [weak self] sample, rotation, delay, completed in
                DispatchQueue.main.async {
                    defer { completed() }
                    guard let self, self.generation == currentGeneration else { return }
                    self.receivedVideo = true
                    self.reconnectAttempt = 0
                    self.error = nil
                    if self.rotationDegrees != rotation { self.rotationDegrees = rotation }
                    if let format = CMSampleBufferGetFormatDescription(sample) {
                        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
                        let size = CGSize(width: Int(dimensions.width), height: Int(dimensions.height))
                        if size.width > 0 && size.height > 0 && self.videoSize != size { self.videoSize = size }
                    }
                    if let delay {
                        self.latencySamples.record(delay)
                        let now = DispatchTime.now().uptimeNanoseconds
                        if now &- self.lastLatencyUpdate >= 1_000_000_000 {
                            self.lastLatencyUpdate = now
                            if let values = self.latencySamples.percentiles() {
                                self.latencySummary = "Est. to queue: P50 \(values.p50) · P95 \(values.p95) · P99 \(values.p99) ms · n=\(self.latencySamples.count)"
                            }
                        }
                    } else if self.lastLatencyUpdate != 0 &&
                                DispatchTime.now().uptimeNanoseconds &- self.lastLatencyUpdate >= 30_000_000_000 {
                        self.latencySamples.clear()
                        self.latencySummary = nil
                        self.lastLatencyUpdate = 0
                    }
                    if self.layer.status == .failed || !self.layer.isReadyForMoreMediaData {
                        self.layer.flushAndRemoveImage()
                        self.status = "RECOVERING"
                        self.onRecovering?()
                        self.session?.recoverVideo()
                        return
                    }
                    self.layer.enqueue(sample)
                    if self.status != "RECEIVING" { self.status = "RECEIVING" }
                    self.onSample?(sample, rotation)
                }
            },
            onAudioFrame: { [weak self] sourceID, frame, time in
                self?.onAudioFrame?(sourceID, frame, time)
            },
            onAudioStatus: { [weak self] sourceID, status in DispatchQueue.main.async {
                guard let self, self.generation == currentGeneration, self.cameraID == sourceID else { return }
                self.audioStatus = status
            } },
            onStatus: { [weak self] status in DispatchQueue.main.async {
                guard let self, self.generation == currentGeneration else { return }
                self.status = status
            } },
            onCapabilities: { [weak self] supported, minimum, maximum in DispatchQueue.main.async {
                guard let self, self.generation == currentGeneration else { return }
                self.setRemotePtzCapabilities(supported, minimum: Double(minimum), maximum: Double(maximum))
            } },
            onPtzApplied: { [weak self] message in DispatchQueue.main.async {
                guard let self, self.generation == currentGeneration else { return }
                let center = message.stringValue.split(separator: ",", omittingEmptySubsequences: false)
                guard center.count == 2,
                      let x = Double(center[0]), let y = Double(center[1]) else { return }
                self.applyRemotePtz(zoom: Double(message.value), centerX: x, centerY: y,
                                    requestId: message.requestId)
            } },
            onError: { [weak self] error in DispatchQueue.main.async {
                guard let self, self.generation == currentGeneration else { return }
                self.confirm(false)
                self.session = nil
                self.latencySamples.clear()
                self.latencySummary = nil
                self.lastLatencyUpdate = 0
                self.error = error
                self.layer.flushAndRemoveImage()
                if self.receivedVideo {
                    self.scheduleReconnect(after: error)
                } else {
                    self.finishDisconnect(status: "DISCONNECTED")
                }
            } })
        self.session = session
        session.setAudioActive(audioActive)
        session.start()
    }

    private func scheduleReconnect(after message: String) {
        reconnectWorkItem?.cancel()
        ptzWorkItem?.cancel(); ptzWorkItem = nil
        ptzPending = false
        generation &+= 1
        reconnectAttempt += 1
        // Keep retries gentle on the camera and access point during longer outages.
        let delay = min(30.0, pow(2.0, Double(min(reconnectAttempt - 1, 5))))
        status = "RECONNECTING"
        error = "\(message) · retrying in \(Int(delay))s"
        onRecovering?()
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.cameraID != nil, self.status == "RECONNECTING" else { return }
            self.error = nil
            self.status = "CONNECTING"
            self.startSession()
        }
        reconnectWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func finishDisconnect(status: String) {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        ptzWorkItem?.cancel(); ptzWorkItem = nil
        ptzPending = false
        reconnectCamera = nil
        reconnectPin = nil
        trustedFingerprint = nil
        reconnectAttempt = 0
        receivedVideo = false
        generation &+= 1
        cameraID = nil
        self.status = status
        onStopped?()
    }

    func confirm(_ approved: Bool) {
        if approved { trustedFingerprint = fingerprint }
        verification?(approved)
        verification = nil
        fingerprint = nil
    }

    func requestPtz(zoom: Double, centerX: Double, centerY: Double) {
        let upperBound = supportsRemotePtz ? remoteMaxZoom : 4
        let lowerBound = supportsRemotePtz ? remoteMinZoom : 1
        hybridPtz.request(zoom: min(upperBound, max(lowerBound, zoom)), centerX: centerX,
                          centerY: centerY, supportsRemotePtz: supportsRemotePtz)
        syncPtzPresentation()
        guard supportsRemotePtz else { return }
        ptzPending = true
        ptzWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.supportsRemotePtz, self.cameraID != nil else { return }
            self.ptzWorkItem = nil
            self.ptzRequestSequence &+= 1
            self.lastSentPtzRequest = self.ptzRequestSequence
            self.ptzPending = true
            self.session?.setPtz(zoom: self.desiredZoom, centerX: self.desiredCenterX,
                                 centerY: self.desiredCenterY, requestId: self.ptzRequestSequence)
        }
        ptzWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: item)
    }

    func restoreFraming(_ framing: CamHubFraming) {
        requestPtz(zoom: framing.zoom,
                   centerX: 0.5 - framing.x / max(1, framing.zoom),
                   centerY: 0.5 - framing.y / max(1, framing.zoom))
    }

    private func setRemotePtzCapabilities(_ supported: Bool, minimum: Double, maximum: Double) {
        let validRange = minimum.isFinite && maximum.isFinite && maximum > max(1, minimum)
        supportsRemotePtz = supported && validRange
        remoteMinZoom = validRange ? max(1, minimum) : 1
        remoteMaxZoom = validRange ? min(32, maximum) : 4
        maxPtzZoom = supportsRemotePtz ? remoteMaxZoom : 4
        guard supportsRemotePtz else {
            ptzWorkItem?.cancel(); ptzWorkItem = nil
            ptzPending = false
            hybridPtz.request(zoom: desiredZoom, centerX: desiredCenterX, centerY: desiredCenterY,
                              supportsRemotePtz: false)
            syncPtzPresentation()
            return
        }
        requestPtz(zoom: desiredZoom, centerX: desiredCenterX, centerY: desiredCenterY)
    }

    private func applyRemotePtz(zoom: Double, centerX: Double, centerY: Double, requestId: UInt64) {
        guard supportsRemotePtz, requestId > lastAppliedPtzRequest,
              requestId <= lastSentPtzRequest,
              hybridPtz.markRemoteApplied(zoom: zoom, centerX: centerX, centerY: centerY) else { return }
        lastAppliedPtzRequest = requestId
        ptzPending = requestId < lastSentPtzRequest || ptzWorkItem != nil
        syncPtzPresentation()
    }

    private func syncPtzPresentation() {
        desiredZoom = hybridPtz.zoom
        desiredCenterX = hybridPtz.centerX
        desiredCenterY = hybridPtz.centerY
        let framing = hybridPtz.hubFraming
        zoom = CGFloat(framing.zoom)
        pan = CGSize(width: framing.x, height: framing.y)
        onFramingChanged?()
    }

    func requestKeyFrame() { session?.requestKeyFrame() }
    func setAudioActive(_ active: Bool) {
        guard audioActive != active else { return }
        audioActive = active
        session?.setAudioActive(active)
        audioStatus = active ? "AUDIO CONNECTING" : "AUDIO STANDBY"
    }
    func disconnect() {
        generation &+= 1
        ptzWorkItem?.cancel(); ptzWorkItem = nil
        reconnectWorkItem?.cancel(); reconnectWorkItem = nil
        reconnectCamera = nil; reconnectPin = nil
        trustedFingerprint = nil
        reconnectAttempt = 0; receivedVideo = false
        hybridPtz = CamHubHybridPtzState()
        desiredZoom = 1; desiredCenterX = 0.5; desiredCenterY = 0.5
        supportsRemotePtz = false; maxPtzZoom = 4; ptzPending = false
        remoteMinZoom = 1; remoteMaxZoom = 4
        ptzRequestSequence = 0; lastSentPtzRequest = 0; lastAppliedPtzRequest = 0
        verification?(false); verification = nil; fingerprint = nil
        session?.stop(); session = nil
        audioActive = false
        if cameraID != nil { onStopped?() }
        cameraID = nil; cameraName = nil
        error = nil
        status = "WAITING"
        audioStatus = "AUDIO WAITING"
        zoom = 1; pan = .zero
        rotationDegrees = 0
        videoSize = CGSize(width: 16, height: 9)
        latencySamples.clear(); latencySummary = nil; lastLatencyUpdate = 0
        layer.flushAndRemoveImage()
    }
}

private final class HubSlots: ObservableObject {
    let displays = (0..<4).map { _ in HubCameraDisplay() }
    let pgmRecorder = CamHubHubPgmRecorder()
    let audioMixer = CamHubHubAudioMixer()
    @Published var programLayer = AVSampleBufferDisplayLayer()
    @Published var transitionLayer = AVSampleBufferDisplayLayer()
    @Published var programIndex: Int?
    @Published var transitionTarget: Int?
    @Published var transitionProgress = 0.0
    @Published var programRotation = 0
    @Published var programVideoSize = CGSize(width: 16, height: 9)
    @Published var programZoom: CGFloat = 1
    @Published var programPan = CGSize.zero
    private var programWaitingForKeyFrame = true
    private var transitionWaitingForKeyFrame = true
    private var transitionStarted = false
    private var transitionGeneration: UInt64 = 0
    private let transitionTimeline = CamHubTransitionTimeline(durationMilliseconds: 350)
    private var transitionStartedAtNanos: UInt64 = 0
    private var transitionTimer: DispatchSourceTimer?
    private var savedFraming: [String: CamHubFraming] = [:]
    private var framingOrder: [String] = []

    init() {
        programLayer.videoGravity = .resizeAspect
        transitionLayer.videoGravity = .resizeAspect
        for (index, display) in displays.enumerated() {
            display.onAudioFrame = { [weak self] sourceID, frame, time in
                guard let self else { return }
                self.audioMixer.append(frame, sourceID: sourceID)
                self.pgmRecorder.appendAudio(frame, sourceID: sourceID, presentationTime: time)
            }
            display.onRecovering = { [weak self] in
                guard let self else { return }
                if self.programIndex == index {
                    self.programLayer.flushAndRemoveImage()
                    self.programWaitingForKeyFrame = true
                }
                if self.transitionTarget == index {
                    self.transitionLayer.flushAndRemoveImage()
                    self.transitionWaitingForKeyFrame = true
                }
            }
            display.onStopped = { [weak self] in
                guard let self else { return }
                if let sourceID = self.displays[index].cameraID {
                    self.audioMixer.removeSource(sourceID)
                }
                if self.transitionTarget == index { self.cancelTransition() }
                guard self.programIndex == index else { return }
                if self.transitionStarted, let target = self.transitionTarget,
                   self.displays[target].status == "RECEIVING" {
                    self.finishTransition()
                } else {
                    self.programLayer.flushAndRemoveImage()
                    self.programIndex = nil
                    self.syncRecorderComposition()
                }
            }
            display.onFramingChanged = { [weak self] in
                guard let self, self.programIndex == index else { return }
                self.updateLiveFraming(index)
            }
            display.onSample = { [weak self] sample, rotation in
                guard let self else { return }
                if self.transitionTarget == index {
                    self.enqueueTransitionSample(sample, from: index)
                    return
                }
                guard self.programIndex == index else { return }
                self.appendPgmSample(sample, rotation: rotation, from: index)
                if self.programRotation != rotation { self.programRotation = rotation }
                if self.programVideoSize != self.displays[index].videoSize { self.programVideoSize = self.displays[index].videoSize }
                if self.programLayer.status == .failed || !self.programLayer.isReadyForMoreMediaData {
                    self.programLayer.flushAndRemoveImage()
                    self.programWaitingForKeyFrame = true
                    self.displays[index].requestKeyFrame()
                    return
                }
                if self.programWaitingForKeyFrame {
                    let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
                    guard (attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) != true else { return }
                    self.programWaitingForKeyFrame = false
                }
                self.programLayer.enqueue(sample)
            }
        }
    }

    func cut(to index: Int) {
        guard displays.indices.contains(index), displays[index].status == "RECEIVING" else { return }
        cancelTransition()
        programLayer.flushAndRemoveImage()
        programWaitingForKeyFrame = true
        programIndex = index
        programRotation = displays[index].rotationDegrees
        programVideoSize = displays[index].videoSize
        programZoom = displays[index].zoom
        programPan = displays[index].pan
        syncRecorderComposition()
        displays[index].requestKeyFrame()
    }

    func auto(to index: Int) {
        guard displays.indices.contains(index), displays[index].status == "RECEIVING",
              index != programIndex, transitionTarget == nil else { return }
        transitionLayer.flushAndRemoveImage()
        transitionTarget = index
        transitionWaitingForKeyFrame = true
        transitionStarted = false
        transitionProgress = 0
        transitionLayer.opacity = 1
        syncRecorderComposition()
        displays[index].requestKeyFrame()
    }

    private func enqueueTransitionSample(_ sample: CMSampleBuffer, from index: Int) {
        guard transitionTarget == index else { return }
        if transitionLayer.status == .failed || !transitionLayer.isReadyForMoreMediaData {
            transitionLayer.flushAndRemoveImage()
            transitionWaitingForKeyFrame = true
            displays[index].requestKeyFrame()
            return
        }
        if transitionWaitingForKeyFrame {
            let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false) as? [[CFString: Any]]
            guard (attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) != true else { return }
            transitionWaitingForKeyFrame = false
        }
        appendPgmSample(sample, rotation: displays[index].rotationDegrees, from: index)
        transitionLayer.enqueue(sample)
        guard !transitionStarted else { return }
        transitionStarted = true
        transitionGeneration &+= 1
        let current = transitionGeneration
        transitionStartedAtNanos = DispatchTime.now().uptimeNanoseconds
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now(), repeating: .milliseconds(16), leeway: .milliseconds(2))
        timer.setEventHandler { [weak self] in
            guard let self, self.transitionGeneration == current else { return }
            let now = DispatchTime.now().uptimeNanoseconds
            let elapsed = now >= self.transitionStartedAtNanos ? now - self.transitionStartedAtNanos : 0
            let progress = self.transitionTimeline.progress(elapsedNanoseconds: elapsed)
            self.transitionProgress = progress
            self.syncRecorderComposition()
            if progress >= 1 { self.finishTransition() }
        }
        transitionTimer = timer
        timer.resume()
    }

    private func finishTransition() {
        guard let target = transitionTarget, displays[target].status == "RECEIVING" else {
            cancelTransition()
            return
        }
        transitionTimer?.setEventHandler {}
        transitionTimer?.cancel()
        transitionTimer = nil
        transitionStartedAtNanos = 0
        let outgoing = programLayer
        programLayer = transitionLayer
        transitionLayer = outgoing
        transitionLayer.flushAndRemoveImage()
        transitionLayer.opacity = 1
        programWaitingForKeyFrame = false
        programIndex = target
        programRotation = displays[target].rotationDegrees
        programVideoSize = displays[target].videoSize
        programZoom = displays[target].zoom
        programPan = displays[target].pan
        transitionTarget = nil
        transitionWaitingForKeyFrame = true
        transitionStarted = false
        transitionProgress = 0
        transitionGeneration &+= 1
        syncRecorderComposition()
    }

    private func cancelTransition() {
        transitionTimer?.setEventHandler {}
        transitionTimer?.cancel()
        transitionTimer = nil
        transitionStartedAtNanos = 0
        transitionGeneration &+= 1
        transitionLayer.flushAndRemoveImage()
        transitionLayer.opacity = 1
        transitionTarget = nil
        transitionWaitingForKeyFrame = true
        transitionStarted = false
        transitionProgress = 0
        syncRecorderComposition()
    }

    func disconnect(_ index: Int) {
        if transitionTarget == index { cancelTransition() }
        if programIndex == index { programLayer.flushAndRemoveImage(); programIndex = nil }
        displays[index].disconnect()
        syncRecorderComposition()
    }
    func connect(_ camera: CamHubDiscoveredCamera, pin: String, to index: Int) {
        disconnect(index)
        let display = displays[index]
        display.connect(camera, pin: pin)
        syncRecorderComposition()
        if let saved = savedFraming[camera.id] {
            display.restoreFraming(saved)
        }
    }
    func rememberFraming(_ index: Int) {
        let display = displays[index]
        guard let id = display.cameraID else { return }
        let zoom = display.desiredZoom
        savedFraming[id] = CamHubFraming(zoom: zoom,
            x: (0.5 - display.desiredCenterX) * zoom,
            y: (0.5 - display.desiredCenterY) * zoom)
        framingOrder.removeAll { $0 == id }
        framingOrder.append(id)
        if framingOrder.count > 64 {
            savedFraming.removeValue(forKey: framingOrder.removeFirst())
        }
    }
    func updateLiveFraming(_ index: Int) {
        guard programIndex == index else { return }
        programZoom = displays[index].zoom
        programPan = displays[index].pan
        syncRecorderComposition()
    }

    func startPgmRecording() {
        guard let programIndex, displays[programIndex].cameraID != nil,
              displays[programIndex].status == "RECEIVING" else { return }
        let display = displays[programIndex]
        let rotated = display.rotationDegrees == 90 || display.rotationDegrees == 270
        let width = Int(rotated ? display.videoSize.height : display.videoSize.width)
        let height = Int(rotated ? display.videoSize.width : display.videoSize.height)
        var requests: [() -> Void] = [{ [weak display] in display?.requestKeyFrame() }]
        if let transitionTarget, transitionTarget != programIndex {
            let target = displays[transitionTarget]
            requests.append { [weak target] in target?.requestKeyFrame() }
        }
        pgmRecorder.start(width: width, height: height, requestKeyFrames: requests)
    }

    func stopPgmRecording() { pgmRecorder.stop() }

    private func appendPgmSample(_ sample: CMSampleBuffer, rotation: Int, from index: Int) {
        guard let sourceID = displays[index].cameraID else { return }
        let display = displays[index]
        let framing = CamHubFraming(zoom: Double(display.zoom), x: Double(display.pan.width),
                                    y: Double(display.pan.height))
        pgmRecorder.append(sample, sourceID: sourceID, rotation: rotation, framing: framing) { [weak display] in
            display?.requestKeyFrame()
        }
    }

    private func syncRecorderComposition() {
        let programID = programIndex.flatMap { displays[$0].cameraID }
        let targetID = transitionTarget.flatMap { displays[$0].cameraID }
        let audioSources = Set([programID, targetID].compactMap { $0 })
        for display in displays {
            display.setAudioActive(display.cameraID.map(audioSources.contains) ?? false)
        }
        pgmRecorder.setComposition(programID: programID, transitionID: targetID,
                                  progress: transitionProgress)
        audioMixer.setComposition(programID: programID, transitionID: targetID,
                                  progress: transitionProgress)
    }
    func disconnectAll() {
        pgmRecorder.stop()
        audioMixer.stop()
        cancelTransition()
        programLayer.flushAndRemoveImage()
        programIndex = nil
        displays.forEach { $0.disconnect() }
        syncRecorderComposition()
    }
}

private struct HubProgramView: UIViewRepresentable {
    let layer: AVSampleBufferDisplayLayer
    let rotation: Int
    func makeUIView(context: Context) -> HubVideoHost {
        let view = HubVideoHost(); view.videoLayer = layer; return view
    }
    func updateUIView(_ view: HubVideoHost, context: Context) {
        view.videoLayer = layer
        view.rotationDegrees = rotation
    }
}

private struct HubSelectedStatus: View {
    @ObservedObject var display: HubCameraDisplay
    let index: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("PVW \(index + 1) · \(display.cameraName ?? "No camera") · \(display.status) · PTZ \(display.ptzPending ? "SYNCING" : (display.supportsRemotePtz ? "REMOTE" : "HUB"))")
                .font(.caption)
            if let error = display.error {
                Text(error).font(.caption).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
            }
            Text(display.audioStatus).font(.caption2).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct HubSlotView: View {
    @ObservedObject var display: HubCameraDisplay
    let index: Int
    let selected: Bool
    let isProgram: Bool
    let editable: Bool
    let select: () -> Void
    let framingChanged: () -> Void
    @State private var gestureZoom = 1.0
    @State private var gestureCenterX = 0.5
    @State private var gestureCenterY = 0.5
    @State private var pinchActive = false
    @State private var dragActive = false

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            GeometryReader { geometry in
                let contentSize = fittedVideoSize(display.videoSize, rotation: display.rotationDegrees, in: geometry.size)
                HubVideoView(display: display)
                    .scaleEffect(display.zoom)
                    .offset(x: display.pan.width * contentSize.width,
                            y: display.pan.height * contentSize.height)
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                    .contentShape(Rectangle())
                    .simultaneousGesture(MagnificationGesture()
                        .onChanged { factor in
                            guard selected && editable else { return }
                            if !pinchActive { gestureZoom = display.desiredZoom; pinchActive = true }
                            display.requestPtz(zoom: min(display.maxPtzZoom, max(1, gestureZoom * Double(factor))),
                                               centerX: display.desiredCenterX,
                                               centerY: display.desiredCenterY)
                            framingChanged()
                        }
                        .onEnded { _ in pinchActive = false; gestureZoom = display.desiredZoom })
                    .simultaneousGesture(DragGesture()
                        .onChanged { value in
                            guard selected && editable && display.desiredZoom > 1 else { return }
                            if !dragActive {
                                gestureZoom = display.desiredZoom
                                gestureCenterX = display.desiredCenterX
                                gestureCenterY = display.desiredCenterY
                                dragActive = true
                            }
                            display.requestPtz(zoom: gestureZoom,
                                centerX: gestureCenterX - Double(value.translation.width / max(1, contentSize.width)) / gestureZoom,
                                centerY: gestureCenterY - Double(value.translation.height / max(1, contentSize.height)) / gestureZoom)
                            framingChanged()
                        }
                        .onEnded { _ in dragActive = false })
                    .simultaneousGesture(SpatialTapGesture(count: 2).onEnded { value in
                        guard selected && editable else { return }
                        let targetZoom = display.desiredZoom > 1 ? 1.0 : min(2, display.maxPtzZoom)
                        let tapX = Double((value.location.x - (geometry.size.width - contentSize.width) / 2) / max(1, contentSize.width))
                        let tapY = Double((value.location.y - (geometry.size.height - contentSize.height) / 2) / max(1, contentSize.height))
                        let centerX = targetZoom == 1 ? 0.5 : display.desiredCenterX + (tapX - 0.5) / display.desiredZoom
                        let centerY = targetZoom == 1 ? 0.5 : display.desiredCenterY + (tapY - 0.5) / display.desiredZoom
                        display.requestPtz(zoom: targetZoom, centerX: centerX, centerY: centerY)
                        gestureZoom = targetZoom
                        gestureCenterX = centerX; gestureCenterY = centerY
                        framingChanged()
                    })
            }.background(.black)
            Text(display.error ?? "\(display.cameraName ?? "Camera \(index + 1)") · \(display.status)")
                .font(.caption).foregroundStyle(.white).lineLimit(1)
                .padding(6).background(.black.opacity(0.65))
            if let latency = display.latencySummary {
                Text(latency).font(.system(size: 9, design: .monospaced)).foregroundStyle(.white)
                    .padding(4).background(.black.opacity(0.65)).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            if isProgram || selected {
                Text(isProgram ? (selected ? "PGM · PVW" : "PGM") : "PVW")
                    .font(.caption2.bold()).foregroundStyle(.white)
                    .padding(4).background(isProgram ? Color.red : Color.blue)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 4).stroke(selected ? Color.cyan : Color.gray, lineWidth: selected ? 2 : 1))
        .contentShape(Rectangle()).onTapGesture(perform: select)
        .onChange(of: display.cameraID) { _ in
            pinchActive = false; dragActive = false
            gestureZoom = display.desiredZoom
            gestureCenterX = display.desiredCenterX; gestureCenterY = display.desiredCenterY
        }
        .alert("Verify camera", isPresented: Binding(get: { display.fingerprint != nil }, set: { if !$0 { display.confirm(false) } })) {
            Button("Matches") { display.confirm(true) }
            Button("Cancel", role: .cancel) { display.confirm(false) }
        } message: { Text("Compare this SHA-256 fingerprint with the camera: \(display.fingerprint ?? "")") }
    }

}

private struct HubVideoView: UIViewRepresentable {
    let display: HubCameraDisplay
    func makeUIView(context: Context) -> UIView {
        let view = HubVideoHost()
        view.videoLayer = display.layer
        return view
    }
    func updateUIView(_ view: UIView, context: Context) {
        guard let host = view as? HubVideoHost else { return }
        host.rotationDegrees = display.rotationDegrees
    }
}

private final class HubVideoHost: UIView {
    var rotationDegrees = 0 { didSet { if oldValue != rotationDegrees { setNeedsLayout() } } }
    var videoLayer: AVSampleBufferDisplayLayer? {
        didSet {
            guard oldValue !== videoLayer else { return }
            oldValue?.removeFromSuperlayer()
            if let videoLayer { layer.addSublayer(videoLayer); setNeedsLayout() }
        }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard let videoLayer else { return }
        let degrees = ((rotationDegrees % 360) + 360) % 360
        let swapped = degrees == 90 || degrees == 270
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        videoLayer.setAffineTransform(.identity)
        videoLayer.bounds = CGRect(x: 0, y: 0, width: swapped ? bounds.height : bounds.width,
                                   height: swapped ? bounds.width : bounds.height)
        videoLayer.position = CGPoint(x: bounds.midX, y: bounds.midY)
        videoLayer.setAffineTransform(CGAffineTransform(rotationAngle: CGFloat(degrees) * .pi / 180))
        CATransaction.commit()
    }
}

struct CamHubHubScreen: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var discovery = CamHubHubDiscovery()
    @StateObject private var slots = HubSlots()
    @State private var selectedSlot = 0
    @State private var livePtzEnabled = false
    @State private var selected: CamHubDiscoveredCamera?
    @State private var pin = ""
    @State private var connectionNotice: String?

    var body: some View {
        GeometryReader { geometry in
            Group {
                if geometry.size.width > geometry.size.height {
                    HStack(spacing: 12) {
                        videoWorkspace
                        controlPanel
                            .frame(width: min(360, max(260, geometry.size.width * 0.32)))
                    }
                } else {
                    VStack(spacing: 10) {
                        videoWorkspace
                            .frame(maxHeight: .infinity)
                        controlPanel
                            .frame(height: min(320, max(210, geometry.size.height * 0.38)))
                    }
                }
            }
            .padding()
        }
        .navigationTitle("CamHub Hub")
        .onChange(of: slots.programIndex) { _ in
            // A live-control opt-in applies only to the current program source.
            livePtzEnabled = false
        }
        .onAppear { discovery.start() }
        .onDisappear {
            selected = nil
            pin = ""
            connectionNotice = nil
            livePtzEnabled = false
            discovery.stop()
            slots.disconnectAll()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                selected = nil
                pin = ""
                connectionNotice = nil
                livePtzEnabled = false
                discovery.stop()
                slots.disconnectAll()
            } else if phase == .active {
                discovery.start()
            }
        }
        .alert("Camera PIN", isPresented: Binding(get: { selected != nil }, set: {
            if !$0 { selected = nil; pin = "" }
        })) {
            TextField("8-digit PIN", text: $pin).keyboardType(.numberPad)
            Button("Connect") {
                defer { pin = "" }
                guard let camera = selected else { return }
                guard pin.count == 8, pin.allSatisfy({ $0.isASCII && $0.isNumber }) else {
                    connectionNotice = "Enter the 8-digit PIN shown on the camera."
                    selected = nil
                    return
                }
                guard !slots.displays.enumerated().contains(where: { $0.offset != selectedSlot && $0.element.cameraID == camera.id }) else {
                    connectionNotice = "This camera is already assigned to another slot."
                    selected = nil
                    return
                }
                slots.connect(camera, pin: pin, to: selectedSlot)
                selected = nil
            }
            Button("Cancel", role: .cancel) { selected = nil; pin = "" }
        } message: { Text("Enter the PIN shown on the camera.") }
    }

    private var videoWorkspace: some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topLeading) {
                programVideo(layer: slots.programLayer, rotation: slots.programRotation,
                             size: slots.programVideoSize, zoom: slots.programZoom,
                             pan: slots.programPan)
                    .opacity(1 - slots.transitionProgress)
                if let target = slots.transitionTarget {
                    let display = slots.displays[target]
                    programVideo(layer: slots.transitionLayer, rotation: display.rotationDegrees,
                                 size: display.videoSize, zoom: display.zoom, pan: display.pan)
                        .opacity(slots.transitionProgress)
                }
                Text("PGM \(slots.programIndex.map { String($0 + 1) } ?? "—")")
                    .font(.caption.bold()).foregroundStyle(.white).padding(6).background(.red)
            }.frame(maxHeight: .infinity)
            VStack(spacing: 6) {
                ForEach(0..<2) { row in
                    HStack(spacing: 6) {
                        ForEach(0..<2) { column in
                            let index = row * 2 + column
                            HubSlotView(display: slots.displays[index], index: index,
                                        selected: selectedSlot == index,
                                        isProgram: slots.programIndex == index,
                                        editable: slots.programIndex != index || livePtzEnabled,
                                        select: { selectedSlot = index },
                                        framingChanged: {
                                            slots.rememberFraming(index)
                                            if livePtzEnabled { slots.updateLiveFraming(index) }
                                        })
                        }
                    }
                }
            }.frame(maxHeight: .infinity)
        }
    }

    private func programVideo(layer: AVSampleBufferDisplayLayer, rotation: Int,
                              size: CGSize, zoom: CGFloat, pan: CGSize) -> some View {
        GeometryReader { geometry in
            let contentSize = fittedVideoSize(size, rotation: rotation, in: geometry.size)
            HubProgramView(layer: layer, rotation: rotation)
                .scaleEffect(zoom)
                .offset(x: pan.width * contentSize.width, y: pan.height * contentSize.height)
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
        }
        .background(.black)
    }

    private var controlPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                HubSelectedStatus(display: slots.displays[selectedSlot], index: selectedSlot)
                if let error = connectionNotice ?? discovery.error {
                    Text(error).font(.caption).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                }
                Text("Select a video slot, then choose a camera").font(.caption)
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(discovery.cameras) { camera in
                            Button {
                                selected = camera
                                pin = ""
                                connectionNotice = nil
                            } label: {
                                HStack {
                                    Text(camera.name).lineLimit(1)
                                    Spacer(minLength: 4)
                                    if slots.displays[selectedSlot].cameraID == camera.id {
                                        Text("ASSIGNED").font(.caption2).foregroundStyle(.secondary)
                                    }
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }
                .frame(maxHeight: 150)
                HStack(spacing: 8) {
                    Button("Refresh") { discovery.stop(); discovery.start() }
                    Button("CUT") { slots.cut(to: selectedSlot) }.buttonStyle(.borderedProminent)
                    Button("AUTO") { slots.auto(to: selectedSlot) }
                        .disabled(slots.transitionTarget != nil || slots.programIndex == selectedSlot ||
                                  slots.displays[selectedSlot].status != "RECEIVING")
                    Button("Disconnect") { slots.disconnect(selectedSlot) }
                }
                .buttonStyle(.bordered)
                HubPgmRecorderControl(
                    recorder: slots.pgmRecorder,
                    canRecord: slots.programIndex.map { slots.displays[$0].status == "RECEIVING" } ?? false,
                    start: slots.startPgmRecording,
                    stop: slots.stopPgmRecording
                )
                HubAudioControl(mixer: slots.audioMixer)
                Toggle("Allow LIVE PTZ", isOn: $livePtzEnabled).font(.caption)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct HubAudioControl: View {
    @ObservedObject var mixer: CamHubHubAudioMixer

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Button(mixer.isMuted ? "UNMUTE PGM AUDIO" : "MUTE PGM AUDIO") {
                    mixer.setMuted(!mixer.isMuted)
                }
                .disabled(mixer.status.hasPrefix("AUDIO ERROR"))
                Text(mixer.status).font(.caption2).foregroundStyle(.secondary)
            }
            HStack(spacing: 8) {
                Text("PGM LEVEL").font(.caption2)
                Slider(value: Binding(get: { mixer.masterVolume },
                                       set: { mixer.setMasterVolume($0) }), in: 0...1)
                Text("\(Int(mixer.masterVolume * 100))%").font(.caption2).monospacedDigit()
            }
        }
    }
}

private struct HubPgmRecorderControl: View {
    @ObservedObject var recorder: CamHubHubPgmRecorder
    let canRecord: Bool
    let start: () -> Void
    let stop: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Button(recorder.isRecording ? "STOP PGM RECORDING" : "RECORD PGM") {
                    recorder.isRecording ? stop() : start()
                }
                .disabled(!recorder.isRecording && !canRecord || isSaving)
                if let url = recorder.latestRecordingURL { ShareLink(item: url) { Text("Share") } }
            }
            Text(statusText).font(.caption).foregroundStyle(statusColor)
        }
    }

    private var isSaving: Bool {
        if case .saving = recorder.state { return true }
        return false
    }

    private var statusText: String {
        switch recorder.state {
        case .idle: return "PGM recording is off"
        case .recording: return "Recording the selected program output"
        case .saving: return "Finalizing PGM recording…"
        case .saved: return "PGM recording saved"
        case let .failed(message): return message
        }
    }

    private var statusColor: Color {
        if case .failed = recorder.state { return .red }
        return .secondary
    }
}
