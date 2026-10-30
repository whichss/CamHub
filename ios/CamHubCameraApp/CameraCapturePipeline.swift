import AVFoundation
import AudioToolbox
import Combine
import CoreImage
import CoreMedia
import CoreVideo
import CamHubCore
import Foundation
import VideoToolbox

struct CamHubEncodedAccessUnit {
    let bytes: Data
    let isKeyFrame: Bool
    let isCodecConfig: Bool
    let captureAtWallMs: UInt64
    let encodedAtWallMs: UInt64
    let rotationDegrees: Int
}

/// VideoToolbox output callbacks may arrive away from the capture queue. Mutable encoder state is
/// confined to `sampleQueue` or `encodedOutputQueue`; lifecycle and rotation state use locks, and
/// published UI state is changed on the main queue.
final class CameraCapturePipeline: NSObject, ObservableObject, @unchecked Sendable {
    private final class FrameContext {
        let captureAtWallMs: UInt64
        let rotationDegrees: Int
        let generation: UInt64
        let ptzGeneration: UInt64
        let ptzRequestId: UInt64
        let ptzClientId: UUID?
        let ptzZoom: CGFloat
        let ptzCenterX: CGFloat
        let ptzCenterY: CGFloat
        init(captureAtWallMs: UInt64, rotationDegrees: Int, generation: UInt64,
             ptzGeneration: UInt64, ptzRequestId: UInt64, ptzClientId: UUID?,
             ptzZoom: CGFloat, ptzCenterX: CGFloat, ptzCenterY: CGFloat) {
            self.captureAtWallMs = captureAtWallMs
            self.rotationDegrees = rotationDegrees
            self.generation = generation
            self.ptzGeneration = ptzGeneration
            self.ptzRequestId = ptzRequestId
            self.ptzClientId = ptzClientId
            self.ptzZoom = ptzZoom
            self.ptzCenterX = ptzCenterX
            self.ptzCenterY = ptzCenterY
        }
    }
    private struct PendingEncodedSample: @unchecked Sendable {
        let sampleBuffer: CMSampleBuffer
        let captureAtWallMs: UInt64
        let rotationDegrees: Int
        let generation: UInt64
    }

    enum State: String {
        case idle = "READY"
        case requestingPermission = "CAMERA PERMISSION"
        case starting = "STARTING CAMERA"
        case capturing = "CAMERA READY · 720p30"
        case permissionDenied = "CAMERA PERMISSION REQUIRED"
        case failed = "CAMERA START FAILED"
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var encodedFrames: UInt64 = 0
    @Published private(set) var lastError: String?
    @Published private(set) var zoomRatio: CGFloat = 1
    @Published private(set) var minimumZoomRatio: CGFloat = 1
    @Published private(set) var maximumZoomRatio: CGFloat = 1
    @Published private(set) var maxRemotePtzZoomRatio: CGFloat = 1
    @Published private(set) var audioAvailable = false

    let captureSession = AVCaptureSession()

    private let sessionQueue = DispatchQueue(label: "com.camhub.camera.session", qos: .userInitiated)
    private let sampleQueue = DispatchQueue(label: "com.camhub.camera.samples", qos: .userInteractive)
    private let encodedOutputQueue = DispatchQueue(label: "com.camhub.camera.encoded-output", qos: .userInteractive)
    private let audioQueue = DispatchQueue(label: "com.camhub.camera.audio-capture", qos: .userInitiated)
    private let lifecycleLock = NSLock()
    private let rotationLock = NSLock()
    private let zoomRequestLock = NSLock()
    private var zoomRequestGeneration: UInt64 = 0
    private let accessUnitHandlerLock = NSLock()
    private let audioDeliveryLock = NSLock()
    private var audioDeliveryEnabled = false
    private let width = 1_280
    private let height = 720
    private let frameRate: Int32 = 30
    // Accessed on sampleQueue, including while a compression session is being created.
    private var requestedBitrate = 4_000_000
    private var videoOutput: AVCaptureVideoDataOutput?
    private var audioOutput: AVCaptureAudioDataOutput?
    private var compressionSession: VTCompressionSession?
    private var outputPixelBufferPool: CVPixelBufferPool?
    private var imageContext: CIContext?
    private var lastCodecConfig = Data()
    // Confined to encodedOutputQueue; UI updates do not need to run for every frame.
    private var encodedFrameCount: UInt64 = 0
    private var lastFrameCountUpdateNanos: UInt64 = 0
    private var waitingForEncodedKeyFrame = false
    private var forceNextKeyFrame = false
    private var streamingEnabled = false
    private var recordingEnabled = false
    private var running = false
    private var lifecycleGeneration: UInt64 = 0
    private var rotationDegrees = 0
    private var startRequested = false
    private var accessUnitHandler: ((CamHubEncodedAccessUnit) -> Void)?
    private var audioPayloadHandler: ((Data) -> Void)?
    private var encodedSampleHandler: ((CMSampleBuffer, Int) -> Void)?
    private struct RemotePtzRequest {
        let zoom: CGFloat
        let centerX: CGFloat
        let centerY: CGFloat
        let requestId: UInt64
        let clientId: UUID
    }
    // Confined to sampleQueue.
    private var remotePtz = RemotePtzRequest(zoom: 1, centerX: 0.5, centerY: 0.5, requestId: 0, clientId: UUID())
    private var remotePtzMaxZoomForFrames: CGFloat = 1
    private var ptzGeneration: UInt64 = 0
    // Confined to encodedOutputQueue.
    private var lastAcknowledgedPtzGeneration: UInt64 = 0
    var onRemotePtzApplied: ((UUID, UInt64, CGFloat, CGFloat, CGFloat) -> Void)?
    var onRemotePtzCapabilitiesChanged: ((CGFloat) -> Void)?

    func setAccessUnitHandler(_ handler: ((CamHubEncodedAccessUnit) -> Void)?) {
        accessUnitHandlerLock.lock()
        accessUnitHandler = handler
        accessUnitHandlerLock.unlock()
    }

    func setAudioPayloadHandler(_ handler: ((Data) -> Void)?) {
        accessUnitHandlerLock.lock()
        audioPayloadHandler = handler
        accessUnitHandlerLock.unlock()
    }

    private func setAudioDeliveryEnabled(_ enabled: Bool) {
        audioDeliveryLock.lock()
        audioDeliveryEnabled = enabled
        audioDeliveryLock.unlock()
    }

    private var shouldDeliverAudio: Bool {
        audioDeliveryLock.lock(); defer { audioDeliveryLock.unlock() }
        return audioDeliveryEnabled
    }

    func setEncodedSampleHandler(_ handler: ((CMSampleBuffer, Int) -> Void)?) {
        accessUnitHandlerLock.lock()
        encodedSampleHandler = handler
        accessUnitHandlerLock.unlock()
    }

    func start() {
        lifecycleLock.lock()
        guard !startRequested else {
            lifecycleLock.unlock()
            return
        }
        startRequested = true
        lifecycleGeneration &+= 1
        let generation = lifecycleGeneration
        lifecycleLock.unlock()
        DispatchQueue.main.async {
            guard self.isCurrentGeneration(generation) else { return }
            self.lastError = nil
            self.state = .requestingPermission
        }
        Task {
            let allowed: Bool
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                allowed = true
            case .notDetermined:
                allowed = await AVCaptureDevice.requestAccess(for: .video)
            default:
                allowed = false
            }
            guard allowed else {
                self.clearStartRequest(generation)
                DispatchQueue.main.async {
                    guard self.isCurrentGeneration(generation) else { return }
                    self.state = .permissionDenied
                }
                return
            }
            let audioAllowed: Bool
            switch AVCaptureDevice.authorizationStatus(for: .audio) {
            case .authorized:
                audioAllowed = true
            case .notDetermined:
                audioAllowed = await AVCaptureDevice.requestAccess(for: .audio)
            default:
                audioAllowed = false
            }
            DispatchQueue.main.async {
                guard self.isCurrentGeneration(generation) else { return }
                self.state = .starting
            }
            sessionQueue.async { [weak self] in
                self?.configureAndStart(generation: generation, audioAllowed: audioAllowed)
            }
        }
    }

    func stop() {
        cancelPendingZoom()
        setAudioDeliveryEnabled(false)
        let generation = advanceLifecycleGeneration()
        lifecycleLock.lock()
        startRequested = false
        lifecycleLock.unlock()
        sessionQueue.async { [weak self] in
            guard let self else { return }
            // A stop operation is queued before any later restart configuration on this
            // serial queue. Always perform the physical teardown even if a newer start
            // has already advanced the generation; only its published UI state is stale.
            self.captureSession.stopRunning()
            self.running = false
            self.sampleQueue.sync {
                self.streamingEnabled = false
                self.recordingEnabled = false
                self.forceNextKeyFrame = false
                if let compressionSession = self.compressionSession {
                    VTCompressionSessionCompleteFrames(compressionSession, untilPresentationTimeStamp: .invalid)
                    VTCompressionSessionInvalidate(compressionSession)
                    self.compressionSession = nil
                }
            }
            self.encodedOutputQueue.sync {
                self.waitingForEncodedKeyFrame = false
                self.lastCodecConfig = Data()
                self.nalUnitLengthSize = 4
                self.lastAcknowledgedPtzGeneration = 0
            }
            self.outputPixelBufferPool = nil
            self.imageContext = nil
            self.videoOutput = nil
            self.audioOutput = nil
            DispatchQueue.main.async {
                guard self.isCurrentGeneration(generation) else { return }
                self.audioAvailable = false
                if self.state != .permissionDenied { self.state = .idle }
            }
        }
    }

    func requestKeyFrame() {
        sampleQueue.async { self.forceNextKeyFrame = true }
    }

    func adjustZoom(by factor: CGFloat) {
        guard factor.isFinite, factor > 0 else { return }
        updateZoom { current in current * factor }
    }

    func setZoom(_ factor: CGFloat) {
        guard factor.isFinite, factor > 0 else { return }
        updateZoom { _ in factor }
    }

    func setRemotePtz(zoom: CGFloat, centerX: CGFloat, centerY: CGFloat, requestId: UInt64, clientId: UUID) {
        guard zoom.isFinite, centerX.isFinite, centerY.isFinite, requestId > 0 else { return }
        cancelPendingZoom()
        sessionQueue.async {
            guard self.running,
                  let camera = (self.captureSession.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            do {
                try camera.lockForConfiguration()
                camera.videoZoomFactor = 1
                camera.unlockForConfiguration()
            } catch {
                DispatchQueue.main.async { self.lastError = error.localizedDescription }
                return
            }
            DispatchQueue.main.async { self.zoomRatio = 1 }
            self.sampleQueue.async {
                let maximum = max(1, self.remotePtzMaxZoomForFrames)
                let appliedZoom = min(max(1, zoom), maximum)
                let halfVisible = 0.5 / appliedZoom
                self.remotePtz = RemotePtzRequest(
                    zoom: appliedZoom,
                    centerX: min(1 - halfVisible, max(halfVisible, centerX)),
                    centerY: min(1 - halfVisible, max(halfVisible, centerY)),
                    requestId: requestId,
                    clientId: clientId
                )
                self.ptzGeneration &+= 1
            }
        }
    }

    private func updateZoom(_ target: @escaping (CGFloat) -> CGFloat) {
        zoomRequestLock.lock()
        let generation = zoomRequestGeneration
        zoomRequestLock.unlock()
        sessionQueue.async {
            self.zoomRequestLock.lock()
            let valid = generation == self.zoomRequestGeneration
            self.zoomRequestLock.unlock()
            guard valid else { return }
            guard self.running,
                  let camera = (self.captureSession.inputs.first as? AVCaptureDeviceInput)?.device else { return }
            do {
                try camera.lockForConfiguration()
                let minimum = camera.minAvailableVideoZoomFactor
                let maximum = max(minimum, min(camera.maxAvailableVideoZoomFactor, 8))
                let requested = min(max(target(camera.videoZoomFactor), minimum), maximum)
                camera.videoZoomFactor = requested
                camera.unlockForConfiguration()
                DispatchQueue.main.async { self.zoomRatio = requested }
            } catch {
                DispatchQueue.main.async { self.lastError = error.localizedDescription }
            }
        }
    }

    func cancelPendingZoom() {
        zoomRequestLock.lock()
        zoomRequestGeneration &+= 1
        zoomRequestLock.unlock()
    }

    func setBitrateMbps(_ value: Int) {
        let bitsPerSecond = min(max(value, 1), 20) * 1_000_000
        sampleQueue.async {
            self.requestedBitrate = bitsPerSecond
            guard let session = self.compressionSession else { return }
            let status = VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate,
                                              value: NSNumber(value: bitsPerSecond))
            if status != noErr {
                DispatchQueue.main.async {
                    self.lastError = "Could not change video bitrate (\(status))."
                }
            } else {
                DispatchQueue.main.async {
                    if self.lastError?.hasPrefix("Could not change video bitrate") == true {
                        self.lastError = nil
                    }
                }
            }
        }
    }

    func setStreamingEnabled(_ enabled: Bool) {
        setAudioDeliveryEnabled(enabled)
        sampleQueue.async {
            self.streamingEnabled = enabled
            if enabled { self.forceNextKeyFrame = true }
        }
    }

    func setRecordingEnabled(_ enabled: Bool) {
        sampleQueue.async {
            self.recordingEnabled = enabled
            if enabled { self.forceNextKeyFrame = true }
        }
    }

    func setRotationDegrees(_ degrees: Int) {
        guard [0, 90, 180, 270].contains(degrees) else { return }
        rotationLock.lock()
        rotationDegrees = degrees
        rotationLock.unlock()
    }

    private func configureAndStart(generation: UInt64, audioAllowed: Bool) {
        guard isCurrentGeneration(generation), !running else { return }
        var configurationOpen = false
        do {
            captureSession.beginConfiguration()
            configurationOpen = true
            captureSession.inputs.forEach { captureSession.removeInput($0) }
            captureSession.outputs.forEach { captureSession.removeOutput($0) }
            videoOutput = nil
            guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else {
                throw CaptureError.cameraUnavailable
            }
            let input = try AVCaptureDeviceInput(device: camera)
            guard captureSession.canAddInput(input) else { throw CaptureError.inputUnavailable }
            captureSession.addInput(input)
            guard captureSession.canSetSessionPreset(.inputPriority) else { throw CaptureError.resolutionUnavailable }
            do {
                try camera.lockForConfiguration()
                defer { camera.unlockForConfiguration() }
                let supportedFormats = camera.formats.filter { format in
                    let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                    let aspect = Double(dimensions.width) / Double(max(1, dimensions.height))
                    return dimensions.width >= Int32(width) && dimensions.height >= Int32(height) &&
                        dimensions.width <= 1_920 && dimensions.height <= 1_080 &&
                        abs(aspect - 16.0 / 9.0) < 0.03 &&
                        format.videoSupportedFrameRateRanges.contains {
                            $0.minFrameRate <= Double(frameRate) && $0.maxFrameRate >= Double(frameRate)
                        }
                }.sorted { lhs, rhs in
                    let left = CMVideoFormatDescriptionGetDimensions(lhs.formatDescription)
                    let right = CMVideoFormatDescriptionGetDimensions(rhs.formatDescription)
                    return left.width * left.height > right.width * right.height
                }
                if let format = supportedFormats.first {
                    camera.activeFormat = format
                } else if let format = camera.formats.first(where: { format in
                    let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                    return dimensions.width == Int32(width) && dimensions.height == Int32(height) &&
                        format.videoSupportedFrameRateRanges.contains {
                            $0.minFrameRate <= Double(frameRate) && $0.maxFrameRate >= Double(frameRate)
                        }
                }) {
                    camera.activeFormat = format
                } else {
                    throw CaptureError.resolutionUnavailable
                }
                guard camera.activeFormat.videoSupportedFrameRateRanges.contains(where: {
                    $0.minFrameRate <= Double(frameRate) && $0.maxFrameRate >= Double(frameRate)
                }) else { throw CaptureError.frameRateUnavailable }
                camera.activeVideoMinFrameDuration = CMTime(value: 1, timescale: frameRate)
                camera.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: frameRate)
            }
            captureSession.sessionPreset = .inputPriority

            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ]
            output.setSampleBufferDelegate(self, queue: sampleQueue)
            guard captureSession.canAddOutput(output) else { throw CaptureError.outputUnavailable }
            captureSession.addOutput(output)
            if let connection = output.connection(with: .video), connection.isVideoOrientationSupported {
                connection.videoOrientation = .landscapeRight
            }
            videoOutput = output

            var microphoneReady = false
            if audioAllowed, let microphone = AVCaptureDevice.default(for: .audio) {
                do {
                    let audioInput = try AVCaptureDeviceInput(device: microphone)
                    let audioOutput = AVCaptureAudioDataOutput()
                    guard captureSession.canAddInput(audioInput) else { throw CaptureError.audioUnavailable }
                    captureSession.addInput(audioInput)
                    guard captureSession.canAddOutput(audioOutput) else {
                        captureSession.removeInput(audioInput)
                        throw CaptureError.audioUnavailable
                    }
                    audioOutput.setSampleBufferDelegate(self, queue: audioQueue)
                    captureSession.addOutput(audioOutput)
                    self.audioOutput = audioOutput
                    microphoneReady = true
                } catch {
                    // Keep video-only capture usable when microphone capture is unavailable.
                    self.audioOutput = nil
                }
            } else {
                self.audioOutput = nil
            }
            captureSession.commitConfiguration()
            configurationOpen = false

            try createCompressionSession()
            guard isCurrentGeneration(generation) else {
                if let compressionSession {
                    VTCompressionSessionInvalidate(compressionSession)
                    self.compressionSession = nil
                }
                return
            }
            let minZoom = camera.minAvailableVideoZoomFactor
            let maxZoom = max(minZoom, min(camera.maxAvailableVideoZoomFactor, 8))
            let currentZoom = camera.videoZoomFactor
            let capturedDimensions = CMVideoFormatDescriptionGetDimensions(camera.activeFormat.formatDescription)
            let sourceCropZoom = min(CGFloat(capturedDimensions.width) / CGFloat(width),
                                     CGFloat(capturedDimensions.height) / CGFloat(height))
            let supportedPtzZoom = max(1, min(4, sourceCropZoom))
            sampleQueue.sync { self.remotePtzMaxZoomForFrames = supportedPtzZoom }
            captureSession.startRunning()
            running = true
            DispatchQueue.main.async {
                guard self.isCurrentGeneration(generation) else { return }
                self.maximumZoomRatio = maxZoom
                self.minimumZoomRatio = minZoom
                self.zoomRatio = currentZoom
                self.maxRemotePtzZoomRatio = supportedPtzZoom
                self.audioAvailable = microphoneReady
                self.onRemotePtzCapabilitiesChanged?(self.maxRemotePtzZoomRatio)
                self.state = .capturing
            }
        } catch {
            if configurationOpen { captureSession.commitConfiguration() }
            captureSession.stopRunning()
            if let compressionSession {
                VTCompressionSessionInvalidate(compressionSession)
                self.compressionSession = nil
            }
            clearStartRequest(generation)
            DispatchQueue.main.async {
                guard self.isCurrentGeneration(generation) else { return }
                self.audioAvailable = false
                self.lastError = error.localizedDescription
                self.state = .failed
            }
        }
    }

    private func createCompressionSession() throws {
        var createdSession: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(width),
            height: Int32(height),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: Self.compressionOutputCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &createdSession
        )
        guard status == noErr, let createdSession else { throw CaptureError.encoderUnavailable(status) }
        sampleQueue.sync {
            self.compressionSession = createdSession
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
                kCVPixelBufferMetalCompatibilityKey as String: true
            ]
            var pool: CVPixelBufferPool?
            let poolStatus = CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &pool)
            if poolStatus == kCVReturnSuccess { self.outputPixelBufferPool = pool }
            self.imageContext = CIContext(options: [.cacheIntermediates: false])
            VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_AverageBitRate,
                                 value: NSNumber(value: self.requestedBitrate))
        }
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: NSNumber(value: frameRate))
        VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: NSNumber(value: 1))
        let mainProfileStatus = VTSessionSetProperty(
            createdSession, key: kVTCompressionPropertyKey_ProfileLevel,
            value: kVTProfileLevel_H264_Main_AutoLevel
        )
        if mainProfileStatus != noErr {
            // Some encoders do not support Main; use the more widely supported Baseline profile.
            // If neither setting is available, leave profile selection to VideoToolbox.
            VTSessionSetProperty(createdSession, key: kVTCompressionPropertyKey_ProfileLevel,
                                 value: kVTProfileLevel_H264_Baseline_AutoLevel)
        }
        let prepareStatus = VTCompressionSessionPrepareToEncodeFrames(createdSession)
        guard prepareStatus == noErr else { throw CaptureError.encoderUnavailable(prepareStatus) }
    }

    private static let compressionOutputCallback: VTCompressionOutputCallback = {
        callbackRefCon, sourceFrameRefCon, status, infoFlags, sampleBuffer in
        let context = sourceFrameRefCon.map {
            Unmanaged<FrameContext>.fromOpaque($0).takeRetainedValue()
        }
        guard let callbackRefCon else { return }
        let pipeline = Unmanaged<CameraCapturePipeline>.fromOpaque(callbackRefCon).takeUnretainedValue()
        guard let context, pipeline.isCurrentGeneration(context.generation) else { return }
        guard status == noErr, !infoFlags.contains(.frameDropped), let sampleBuffer else {
            pipeline.encodedOutputQueue.async { [weak pipeline] in
                guard let pipeline, pipeline.isCurrentGeneration(context.generation) else { return }
                pipeline.recoverEncodedOutput()
            }
            return
        }
        let output = PendingEncodedSample(sampleBuffer: sampleBuffer, captureAtWallMs: context.captureAtWallMs,
                                          rotationDegrees: context.rotationDegrees, generation: context.generation)
        pipeline.encodedOutputQueue.async { [weak pipeline, output] in
            guard let pipeline, pipeline.isCurrentGeneration(output.generation) else { return }
            pipeline.consumeEncodedSample(output.sampleBuffer, captureAtWallMs: output.captureAtWallMs,
                                          rotationDegrees: output.rotationDegrees, ptzContext: context)
        }
    }

    private func recoverEncodedOutput() {
        waitingForEncodedKeyFrame = true
        requestKeyFrame()
    }

    private func consumeEncodedSample(_ sampleBuffer: CMSampleBuffer, captureAtWallMs: UInt64,
                                      rotationDegrees: Int, ptzContext: FrameContext) {
        guard let format = CMSampleBufferGetFormatDescription(sampleBuffer),
              let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
            recoverEncodedOutput()
            return
        }
        let isKeyFrame: Bool = {
            let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                as? [[CFString: Any]]
            return (attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) != true
        }()

        if waitingForEncodedKeyFrame && !isKeyFrame { return }

        guard let config = codecConfiguration(from: format) else {
            recoverEncodedOutput()
            return
        }
        if config != lastCodecConfig {
            lastCodecConfig = config
            publish(config, keyFrame: false, codecConfig: true, captureAtWallMs: captureAtWallMs, rotationDegrees: rotationDegrees)
        }
        guard let accessUnit = annexBAccessUnit(from: blockBuffer, nalLengthSize: nalUnitLengthSize) else {
            recoverEncodedOutput()
            return
        }
        // Keep SPS/PPS in the same transport frame as the IDR, so packet reordering
        // cannot make the IDR arrive before its required configuration.
        if isKeyFrame { waitingForEncodedKeyFrame = false }
        accessUnitHandlerLock.lock()
        let sampleHandler = encodedSampleHandler
        accessUnitHandlerLock.unlock()
        sampleHandler?(sampleBuffer, rotationDegrees)
        let video = isKeyFrame ? lastCodecConfig + accessUnit : accessUnit
        publish(video, keyFrame: isKeyFrame, codecConfig: false, captureAtWallMs: captureAtWallMs, rotationDegrees: rotationDegrees)
        if ptzContext.ptzGeneration > lastAcknowledgedPtzGeneration,
           let clientId = ptzContext.ptzClientId, ptzContext.ptzRequestId > 0 {
            lastAcknowledgedPtzGeneration = ptzContext.ptzGeneration
            onRemotePtzApplied?(clientId, ptzContext.ptzRequestId, ptzContext.ptzZoom,
                                ptzContext.ptzCenterX, ptzContext.ptzCenterY)
        }
        encodedFrameCount &+= 1
        let now = DispatchTime.now().uptimeNanoseconds
        if lastFrameCountUpdateNanos == 0 || now &- lastFrameCountUpdateNanos >= 1_000_000_000 {
            lastFrameCountUpdateNanos = now
            let count = encodedFrameCount
            DispatchQueue.main.async { self.encodedFrames = count }
        }
    }

    private func codecConfiguration(from format: CMFormatDescription) -> Data? {
        var parameterSetCount = 0
        var nalLength: Int32 = 0
        let firstStatus = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            format,
            parameterSetIndex: 0,
            parameterSetPointerOut: nil,
            parameterSetSizeOut: nil,
            parameterSetCountOut: &parameterSetCount,
            nalUnitHeaderLengthOut: &nalLength
        )
        guard firstStatus == noErr, parameterSetCount >= 2 else { return nil }
        nalUnitLengthSize = Int(nalLength)
        guard (1...4).contains(nalUnitLengthSize) else { return nil }
        var config = Data()
        for index in 0..<parameterSetCount {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            let status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format,
                parameterSetIndex: index,
                parameterSetPointerOut: &pointer,
                parameterSetSizeOut: &size,
                parameterSetCountOut: nil,
                nalUnitHeaderLengthOut: nil
            )
            guard status == noErr, let pointer, size > 0 else { return nil }
            config.append(contentsOf: [0, 0, 0, 1])
            config.append(contentsOf: UnsafeBufferPointer(start: pointer, count: size))
        }
        return config
    }

    private var nalUnitLengthSize = 4

    private func annexBAccessUnit(from block: CMBlockBuffer, nalLengthSize: Int) -> Data? {
        var totalLength = 0
        guard CMBlockBufferGetDataLength(block) > 0 else { return nil }
        totalLength = CMBlockBufferGetDataLength(block)
        var bytes = [UInt8](repeating: 0, count: totalLength)
        let copyStatus = bytes.withUnsafeMutableBytes { destination in
            CMBlockBufferCopyDataBytes(
                block,
                atOffset: 0,
                dataLength: totalLength,
                destination: destination.baseAddress!
            )
        }
        guard copyStatus == kCMBlockBufferNoErr else { return nil }
        var result = Data()
        var offset = 0
        while offset + nalLengthSize <= totalLength {
            var nalLength = 0
            for index in 0..<nalLengthSize { nalLength = (nalLength << 8) | Int(bytes[offset + index]) }
            offset += nalLengthSize
            guard nalLength > 0, offset + nalLength <= totalLength else { return nil }
            result.append(contentsOf: [0, 0, 0, 1])
            result.append(contentsOf: bytes[offset..<(offset + nalLength)])
            offset += nalLength
        }
        return offset == totalLength && !result.isEmpty ? result : nil
    }

    private func publish(_ bytes: Data, keyFrame: Bool, codecConfig: Bool, captureAtWallMs: UInt64, rotationDegrees: Int) {
        let now = UInt64(Date().timeIntervalSince1970 * 1_000)
        let unit = CamHubEncodedAccessUnit(
            bytes: bytes,
            isKeyFrame: keyFrame,
            isCodecConfig: codecConfig,
            captureAtWallMs: captureAtWallMs,
            encodedAtWallMs: now,
            rotationDegrees: rotationDegrees
        )
        accessUnitHandlerLock.lock()
        let handler = accessUnitHandler
        accessUnitHandlerLock.unlock()
        handler?(unit)
    }

    private func advanceLifecycleGeneration() -> UInt64 {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        lifecycleGeneration &+= 1
        return lifecycleGeneration
    }

    private func isCurrentGeneration(_ generation: UInt64) -> Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return lifecycleGeneration == generation
    }

    private func clearStartRequest(_ generation: UInt64) {
        lifecycleLock.lock()
        if lifecycleGeneration == generation { startRequested = false }
        lifecycleLock.unlock()
    }

    private func encode(_ sampleBuffer: CMSampleBuffer) {
        guard streamingEnabled || recordingEnabled,
              let session = compressionSession,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let captureAt = UInt64(Date().timeIntervalSince1970 * 1_000)
        rotationLock.lock()
        let rotation = rotationDegrees
        rotationLock.unlock()
        lifecycleLock.lock()
        let generation = lifecycleGeneration
        lifecycleLock.unlock()
        let ptz = remotePtz
        guard let encoderBuffer = renderForEncoding(pixelBuffer, rotationDegrees: rotation, ptz: ptz) else { return }
        let timingRef = Unmanaged.passRetained(FrameContext(
            captureAtWallMs: captureAt, rotationDegrees: rotation, generation: generation,
            ptzGeneration: ptzGeneration, ptzRequestId: ptz.requestId, ptzClientId: ptz.clientId,
            ptzZoom: ptz.zoom, ptzCenterX: ptz.centerX, ptzCenterY: ptz.centerY
        )).toOpaque()
        let requestedKeyFrame = forceNextKeyFrame
        let frameProperties: CFDictionary? = requestedKeyFrame
            ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
            : nil
        forceNextKeyFrame = false
        var flags = VTEncodeInfoFlags()
        let status = VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: encoderBuffer,
            presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(sampleBuffer),
            duration: CMSampleBufferGetDuration(sampleBuffer),
            frameProperties: frameProperties,
            sourceFrameRefcon: timingRef,
            infoFlagsOut: &flags
        )
        if status != noErr {
            Unmanaged<FrameContext>.fromOpaque(timingRef).release()
            forceNextKeyFrame = true
            encodedOutputQueue.async { [weak self] in
                guard let self, self.isCurrentGeneration(generation) else { return }
                self.recoverEncodedOutput()
            }
        }
    }

    private func publishAudioSample(_ sampleBuffer: CMSampleBuffer) {
        guard shouldDeliverAudio,
              let format = CMSampleBufferGetFormatDescription(sampleBuffer),
              let description = CMAudioFormatDescriptionGetStreamBasicDescription(format),
              description.pointee.mFormatID == kAudioFormatLinearPCM,
              (1...2).contains(Int(description.pointee.mChannelsPerFrame)),
              description.pointee.mSampleRate >= 8_000,
              description.pointee.mSampleRate <= 96_000,
              description.pointee.mSampleRate.rounded() == description.pointee.mSampleRate,
              Int(description.pointee.mSampleRate).isMultiple(of: 100),
              let pcm = monoPCM16(sampleBuffer, description: description.pointee) else { return }

        var payload = Data(count: 5 + pcm.count)
        payload[0] = 2 // protocol version
        payload[1] = 1 // mono
        payload[2] = 0 // PCM16
        let rateDiv100 = UInt16(description.pointee.mSampleRate / 100)
        payload[3] = UInt8((rateDiv100 >> 8) & 0xff)
        payload[4] = UInt8(rateDiv100 & 0xff)
        payload.replaceSubrange(5..<payload.count, with: pcm)
        accessUnitHandlerLock.lock()
        let handler = audioPayloadHandler
        accessUnitHandlerLock.unlock()
        handler?(payload)
    }

    private func monoPCM16(_ sampleBuffer: CMSampleBuffer,
                           description: AudioStreamBasicDescription) -> Data? {
        let isFloat = description.mFormatFlags & kAudioFormatFlagIsFloat != 0
        let isSigned = description.mFormatFlags & kAudioFormatFlagIsSignedInteger != 0
        let bits = Int(description.mBitsPerChannel)
        guard (isFloat && (bits == 32 || bits == 64)) || (isSigned && (bits == 16 || bits == 32)) else {
            return nil
        }
        let bytesPerSample = bits / 8
        let channels = Int(description.mChannelsPerFrame)
        let isNonInterleaved = description.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        let isBigEndian = description.mFormatFlags & kAudioFormatFlagIsBigEndian != 0
        let frameCount = CMSampleBufferGetNumSamples(sampleBuffer)
        guard frameCount > 0, frameCount <= 4_096 else { return nil }

        var requiredSize = 0
        var retainedBlockBuffer: CMBlockBuffer?
        _ = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: &requiredSize,
            bufferListOut: nil,
            bufferListSize: 0,
            blockBufferAllocator: kCFAllocatorDefault,
            blockBufferMemoryAllocator: kCFAllocatorDefault,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: nil
        )
        guard requiredSize >= MemoryLayout<AudioBufferList>.size, requiredSize <= 4_096 else { return nil }
        let storage = UnsafeMutableRawPointer.allocate(byteCount: requiredSize, alignment: 16)
        defer { storage.deallocate() }
        let list = storage.assumingMemoryBound(to: AudioBufferList.self)
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: list,
            bufferListSize: requiredSize,
            blockBufferAllocator: kCFAllocatorDefault,
            blockBufferMemoryAllocator: kCFAllocatorDefault,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &retainedBlockBuffer
        )
        guard status == noErr else { return nil }
        let buffers = UnsafeMutableAudioBufferListPointer(list)
        guard (isNonInterleaved ? buffers.count >= channels : buffers.count == 1),
              buffers.allSatisfy({ $0.mData != nil }) else { return nil }
        let requiredBytesPerBuffer = frameCount * bytesPerSample * (isNonInterleaved ? 1 : channels)
        guard buffers.allSatisfy({ Int($0.mDataByteSize) >= requiredBytesPerBuffer }) else { return nil }

        var result = Data(count: frameCount * 2)
        let littleEndian = !isBigEndian
        for frame in 0..<frameCount {
            var mixed = 0.0
            for channel in 0..<channels {
                let bufferIndex = isNonInterleaved ? channel : 0
                let sampleIndex = isNonInterleaved ? frame : frame * channels + channel
                let pointer = buffers[bufferIndex].mData!.assumingMemoryBound(to: UInt8.self)
                    .advanced(by: sampleIndex * bytesPerSample)
                let sample: Double
                if isFloat {
                    if bits == 32 {
                        let word = readWord(pointer, byteCount: 4, littleEndian: littleEndian)
                        sample = Double(Float(bitPattern: UInt32(truncatingIfNeeded: word)))
                    } else {
                        let word = readWord(pointer, byteCount: 8, littleEndian: littleEndian)
                        sample = Double(bitPattern: word)
                    }
                } else if bits == 16 {
                    let word = readWord(pointer, byteCount: 2, littleEndian: littleEndian)
                    sample = Double(Int16(bitPattern: UInt16(truncatingIfNeeded: word))) / 32_768
                } else {
                    let word = readWord(pointer, byteCount: 4, littleEndian: littleEndian)
                    sample = Double(Int32(bitPattern: UInt32(truncatingIfNeeded: word))) / 2_147_483_648
                }
                guard sample.isFinite else { return nil }
                mixed += min(1, max(-1, sample))
            }
            let normalized = mixed / Double(channels)
            let pcm = Int16(min(32_767, max(-32_768, Int((normalized * 32_767).rounded()))))
            let bits = UInt16(bitPattern: pcm)
            result[frame * 2] = UInt8(bits & 0xff)
            result[frame * 2 + 1] = UInt8((bits >> 8) & 0xff)
        }
        return result
    }

    private func readWord(_ bytes: UnsafePointer<UInt8>, byteCount: Int, littleEndian: Bool) -> UInt64 {
        var word: UInt64 = 0
        for index in 0..<byteCount {
            let shift = littleEndian ? index * 8 : (byteCount - index - 1) * 8
            word |= UInt64(bytes[index]) << shift
        }
        return word
    }

    private func renderForEncoding(_ source: CVPixelBuffer, rotationDegrees: Int,
                                   ptz: RemotePtzRequest) -> CVPixelBuffer? {
        let sourceWidth = CVPixelBufferGetWidth(source)
        let sourceHeight = CVPixelBufferGetHeight(source)
        if sourceWidth == width, sourceHeight == height,
           abs(ptz.zoom - 1) < 0.0001, abs(ptz.centerX - 0.5) < 0.0001,
           abs(ptz.centerY - 0.5) < 0.0001 {
            return source
        }
        guard let pool = outputPixelBufferPool, let context = imageContext,
              sourceWidth > 0, sourceHeight > 0 else { return nil }
        var output: CVPixelBuffer?
        let allocationThreshold = [kCVPixelBufferPoolAllocationThresholdKey as String: 4] as CFDictionary
        guard CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(kCFAllocatorDefault, pool,
                                                                  allocationThreshold, &output) == kCVReturnSuccess,
              let output else { return nil }
        guard let crop = CamHubSourceCrop.rectangle(
            source: CGSize(width: sourceWidth, height: sourceHeight),
            output: CGSize(width: width, height: height), zoom: Double(ptz.zoom),
            centerX: Double(ptz.centerX), centerY: Double(ptz.centerY), rotation: rotationDegrees
        ) else { return nil }
        let scaleX = CGFloat(width) / crop.width
        let scaleY = CGFloat(height) / crop.height
        let rendered = CIImage(cvPixelBuffer: source)
            .cropped(to: crop)
            .transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
            .transformed(by: CGAffineTransform(translationX: -crop.minX * scaleX, y: -crop.minY * scaleY))
            .cropped(to: CGRect(x: 0, y: 0, width: width, height: height))
        context.render(rendered, to: output,
                       bounds: CGRect(x: 0, y: 0, width: width, height: height),
                       colorSpace: CGColorSpaceCreateDeviceRGB())
        return output
    }

    private enum CaptureError: LocalizedError {
        case cameraUnavailable
        case inputUnavailable
        case outputUnavailable
        case audioUnavailable
        case resolutionUnavailable
        case frameRateUnavailable
        case encoderUnavailable(OSStatus)
        var errorDescription: String? {
            switch self {
            case .cameraUnavailable: return "No back camera is available."
            case .inputUnavailable: return "The camera input could not be attached."
            case .outputUnavailable: return "The camera capture output could not be attached."
            case .audioUnavailable: return "The microphone input or PCM audio output is unavailable."
            case .resolutionUnavailable: return "This camera cannot capture at 720p."
            case .frameRateUnavailable: return "The selected camera format does not support 30 frames per second."
            case let .encoderUnavailable(status): return "Hardware H.264 encoder setup failed (\(status))."
            }
        }
    }
}

extension CameraCapturePipeline: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        if output is AVCaptureAudioDataOutput {
            publishAudioSample(sampleBuffer)
        } else if output is AVCaptureVideoDataOutput {
            encode(sampleBuffer)
        }
    }
}
