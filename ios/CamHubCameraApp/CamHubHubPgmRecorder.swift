import AVFoundation
import AudioToolbox
import CamHubCore
import Combine
import CoreImage
import CoreMedia
import Foundation
import VideoToolbox

/// Records the hub's selected program after decoding and applying the same framing and dissolve
/// used by the PGM view. It is opt-in and keeps at most two compressed samples in flight.
final class CamHubHubPgmRecorder: ObservableObject {
    enum State: Equatable {
        case idle
        case recording
        case saving
        case saved
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var latestRecordingURL: URL?

    private struct Frame {
        let pixels: CVPixelBuffer
        let rotation: Int
        let framing: CamHubFraming
        let presentationTime: CMTime
    }

    private final class DecodeTicket {
        let owner: CamHubHubPgmRecorder
        let sourceID: String
        let rotation: Int
        let framing: CamHubFraming
        let requestKeyFrame: () -> Void

        init(owner: CamHubHubPgmRecorder, sourceID: String, rotation: Int,
             framing: CamHubFraming, requestKeyFrame: @escaping () -> Void) {
            self.owner = owner
            self.sourceID = sourceID
            self.rotation = rotation
            self.framing = framing
            self.requestKeyFrame = requestKeyFrame
        }
    }

    private struct Decoder {
        let format: CMVideoFormatDescription
        let session: VTDecompressionSession
    }

    private struct PendingAudioFrame {
        let samples: [Int16]
        let presentationTime: CMTime
    }

    private let queue = DispatchQueue(label: "com.camhub.hub.pgm-recording", qos: .userInitiated)
    private let pendingSamples = DispatchSemaphore(value: 2)
    private let pendingAudioSamples = DispatchSemaphore(value: 8)
    private let requestLock = NSLock()
    private let gapLock = NSLock()
    private var gapNotificationsQueued = Set<String>()
    private let recordingsDirectory: URL?
    private var recordingRequested = false
    // The remaining mutable recording state is confined to `queue`.
    private var programSourceID: String?
    private var transitionSourceID: String?
    private var transitionProgress = 0.0
    private var decoders: [String: Decoder] = [:]
    private var waitingForKeyFrame = Set<String>()
    private var latestFrames: [String: Frame] = [:]
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var audioWriterInput: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var audioFormatDescription: CMAudioFormatDescription?
    private var outputURL: URL?
    private var outputWidth = 1280
    private var outputHeight = 720
    private var lastPresentationTime: CMTime?
    private var recordingStartTime: CMTime?
    private var audioPresentationCursor: CMTime?
    private var pendingAudio: [String: [PendingAudioFrame]] = [:]
    private let imageContext = CIContext(options: [.cacheIntermediates: false])

    init(recordingsDirectory: URL? = nil) {
        self.recordingsDirectory = recordingsDirectory
    }

    var isRecording: Bool {
        switch state {
        case .recording: return true
        default: return false
        }
    }

    func start(width: Int, height: Int, requestKeyFrames: [() -> Void]) {
        requestLock.lock()
        guard !recordingRequested else { requestLock.unlock(); return }
        recordingRequested = true
        requestLock.unlock()

        publish(.recording)
        queue.async {
            self.outputWidth = max(16, min(1920, width)) & ~1
            self.outputHeight = max(16, min(1920, height)) & ~1
            self.pendingAudio.removeAll(keepingCapacity: true)
            self.audioPresentationCursor = nil
            DispatchQueue.main.async { self.latestRecordingURL = nil }
            requestKeyFrames.forEach { $0() }
        }
    }

    func stop() {
        requestLock.lock()
        let wasRecording = recordingRequested
        recordingRequested = false
        requestLock.unlock()
        guard wasRecording else { return }

        queue.async {
            self.publish(.saving)
            self.drainAudio(force: true)
            self.decoders.values.forEach { VTDecompressionSessionWaitForAsynchronousFrames($0.session) }
            self.decoders.values.forEach { VTDecompressionSessionInvalidate($0.session) }
            self.decoders.removeAll()
            self.latestFrames.removeAll()
            // VideoToolbox invokes its output callback before Wait returns, but that callback
            // enqueues the actual writer append on this serial queue. Finalize behind those
            // queued appends so the last decoded frames are not lost at stop time.
            self.queue.async { self.finishWriterOnQueue() }
        }
    }

    private func finishWriterOnQueue() {
        guard let writer, let input = writerInput, let audioInput = audioWriterInput else {
            clearWriterState()
            publish(.idle)
            return
        }
        input.markAsFinished()
        audioInput.markAsFinished()
        writer.finishWriting {
            self.queue.async {
                let url = self.outputURL
                let result: State
                if writer.status == .completed, url != nil {
                    result = .saved
                } else {
                    result = .failed(writer.error?.localizedDescription ?? "The PGM recording could not be finalized.")
                    if let url { try? FileManager.default.removeItem(at: url) }
                }
                self.clearWriterState()
                self.publish(result, recordingURL: result == .saved ? url : nil)
            }
        }
    }

    func setComposition(programID: String?, transitionID: String?, progress: Double) {
        queue.async {
            self.programSourceID = programID
            self.transitionSourceID = transitionID
            self.transitionProgress = progress.isFinite ? min(1, max(0, progress)) : 0
            let active = Set([programID, transitionID].compactMap { $0 })
            let stale = self.latestFrames.keys.filter { !active.contains($0) }
            stale.forEach { self.latestFrames.removeValue(forKey: $0) }
            let staleAudio = self.pendingAudio.keys.filter { !active.contains($0) }
            staleAudio.forEach { self.pendingAudio.removeValue(forKey: $0) }
            self.drainAudio()
        }
    }

    /// Audio is optional and lossy under load; never let PCM buffering stall video recording.
    func appendAudio(_ frame: CamHubPCMFrame, sourceID: String, presentationTime: CMTime) {
        requestLock.lock()
        let active = recordingRequested
        requestLock.unlock()
        guard active, presentationTime.isValid, presentationTime.isNumeric,
              pendingAudioSamples.wait(timeout: .now()) == .success else { return }
        queue.async {
            defer { self.pendingAudioSamples.signal() }
            guard self.isRecordingRequested,
                  sourceID == self.programSourceID || sourceID == self.transitionSourceID else { return }
            guard let normalized = Self.normalizeAudio(frame), !normalized.isEmpty else { return }
            var frames = self.pendingAudio[sourceID, default: []]
            if frames.count >= 4 { frames.removeFirst() }
            frames.append(PendingAudioFrame(samples: normalized, presentationTime: presentationTime))
            self.pendingAudio[sourceID] = frames
            self.drainAudio()
        }
    }

    /// Called on the UI delivery path. It only queues work and never waits for decoding or disk I/O.
    func append(_ sample: CMSampleBuffer, sourceID: String, rotation: Int, framing: CamHubFraming,
                requestKeyFrame: @escaping () -> Void) {
        requestLock.lock()
        let active = recordingRequested
        requestLock.unlock()
        guard active else { return }
        guard pendingSamples.wait(timeout: .now()) == .success else {
            markInputGap(sourceID: sourceID, requestKeyFrame: requestKeyFrame)
            return
        }
        queue.async {
            guard self.isRecordingRequested,
                  sourceID == self.programSourceID || sourceID == self.transitionSourceID,
                  let format = CMSampleBufferGetFormatDescription(sample) else {
                self.pendingSamples.signal()
                return
            }
            let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: false)
                as? [[CFString: Any]]
            let keyFrame = (attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) != true
            if self.waitingForKeyFrame.contains(sourceID) && !keyFrame {
                self.pendingSamples.signal()
                return
            }
            let formatDimensions = CMVideoFormatDescriptionGetDimensions(format)
            let needsDecoder = self.decoders[sourceID].map {
                let currentDimensions = CMVideoFormatDescriptionGetDimensions($0.format)
                return currentDimensions.width != formatDimensions.width ||
                    currentDimensions.height != formatDimensions.height ||
                    CMFormatDescriptionGetMediaSubType($0.format) != CMFormatDescriptionGetMediaSubType(format)
            } ?? true
            if needsDecoder {
                self.replaceDecoder(for: sourceID, format: format)
            }
            guard let decoder = self.decoders[sourceID] else {
                self.pendingSamples.signal()
                return
            }
            if keyFrame { self.waitingForKeyFrame.remove(sourceID) }
            let ticket = DecodeTicket(owner: self, sourceID: sourceID, rotation: rotation,
                                      framing: framing, requestKeyFrame: requestKeyFrame)
            let refcon = Unmanaged.passRetained(ticket).toOpaque()
            var flags = VTDecodeInfoFlags()
            let status = VTDecompressionSessionDecodeFrame(
                decoder.session, sampleBuffer: sample,
                flags: [._EnableAsynchronousDecompression], frameRefcon: refcon, infoFlagsOut: &flags
            )
            if status != noErr {
                Unmanaged<DecodeTicket>.fromOpaque(refcon).release()
                self.pendingSamples.signal()
                self.waitingForKeyFrame.insert(sourceID)
                self.latestFrames.removeValue(forKey: sourceID)
                requestKeyFrame()
            }
        }
    }

    private var isRecordingRequested: Bool {
        requestLock.lock(); defer { requestLock.unlock() }
        return recordingRequested
    }

    private func markInputGap(sourceID: String, requestKeyFrame: @escaping () -> Void) {
        gapLock.lock()
        let shouldQueue = gapNotificationsQueued.insert(sourceID).inserted
        gapLock.unlock()
        guard shouldQueue else { return }
        queue.async {
            self.waitingForKeyFrame.insert(sourceID)
            self.latestFrames.removeValue(forKey: sourceID)
            self.gapLock.lock()
            self.gapNotificationsQueued.remove(sourceID)
            self.gapLock.unlock()
            requestKeyFrame()
        }
    }

    private func replaceDecoder(for sourceID: String, format: CMVideoFormatDescription) {
        if let old = decoders.removeValue(forKey: sourceID) {
            VTDecompressionSessionWaitForAsynchronousFrames(old.session)
            VTDecompressionSessionInvalidate(old.session)
        }
        latestFrames.removeValue(forKey: sourceID)
        waitingForKeyFrame.insert(sourceID)
        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferWidthKey as String: Int(dimensions.width),
            kCVPixelBufferHeightKey as String: Int(dimensions.height),
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            kCVPixelBufferMetalCompatibilityKey as String: true
        ]
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: Self.outputCallback,
            decompressionOutputRefCon: Unmanaged.passUnretained(self).toOpaque()
        )
        var session: VTDecompressionSession?
        let preferredStatus = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault, formatDescription: format, decoderSpecification: nil,
            imageBufferAttributes: attributes as CFDictionary, outputCallback: &callback,
            decompressionSessionOut: &session
        )
        var fallbackStatus = noErr
        if preferredStatus != noErr {
            if let session { VTDecompressionSessionInvalidate(session) }
            session = nil
            // Some decoders reject the preferred NV12/IOSurface/Metal output tuple.
            // Let VideoToolbox select a compatible pixel buffer before failing recording.
            fallbackStatus = VTDecompressionSessionCreate(
                allocator: kCFAllocatorDefault, formatDescription: format, decoderSpecification: nil,
                imageBufferAttributes: nil, outputCallback: &callback, decompressionSessionOut: &session
            )
        }
        guard fallbackStatus == noErr, let session else {
            let message = "The hub could not create a PGM video decoder (preferred: \(preferredStatus), fallback: \(fallbackStatus))."
            publish(.failed(message))
            failOnQueue(message)
            return
        }
        decoders[sourceID] = Decoder(format: format, session: session)
    }

    private static let outputCallback: VTDecompressionOutputCallback = {
        outputRefcon, sourceRefcon, status, flags, imageBuffer, presentationTime, _ in
        guard let sourceRefcon else { return }
        let ticket = Unmanaged<DecodeTicket>.fromOpaque(sourceRefcon).takeRetainedValue()
        guard let outputRefcon, let imageBuffer, status == noErr,
              !flags.contains(.frameDropped) else {
            ticket.owner.queue.async {
                ticket.owner.pendingSamples.signal()
                ticket.owner.waitingForKeyFrame.insert(ticket.sourceID)
                ticket.owner.latestFrames.removeValue(forKey: ticket.sourceID)
                ticket.requestKeyFrame()
            }
            return
        }
        let owner = Unmanaged<CamHubHubPgmRecorder>.fromOpaque(outputRefcon).takeUnretainedValue()
        owner.queue.async {
            defer { owner.pendingSamples.signal() }
            guard owner.isRecordingRequested,
                  ticket.sourceID == owner.programSourceID || ticket.sourceID == owner.transitionSourceID else { return }
            owner.latestFrames[ticket.sourceID] = Frame(
                pixels: imageBuffer, rotation: ticket.rotation, framing: ticket.framing,
                presentationTime: presentationTime
            )
            owner.renderAndWrite()
        }
    }

    private func renderAndWrite() {
        guard let programSourceID, let program = latestFrames[programSourceID] else { return }
        let canvas = CGRect(x: 0, y: 0, width: outputWidth, height: outputHeight)
        let programImage = framedImage(program, in: canvas)
        let composed: CIImage
        if transitionProgress > 0, let transitionSourceID, let transition = latestFrames[transitionSourceID] {
            let targetImage = framedImage(transition, in: canvas)
            let filter = CIFilter(name: "CIDissolveTransition")!
            filter.setValue(programImage, forKey: kCIInputImageKey)
            filter.setValue(targetImage, forKey: kCIInputTargetImageKey)
            filter.setValue(transitionProgress, forKey: kCIInputTimeKey)
            composed = (filter.outputImage ?? programImage).cropped(to: canvas)
        } else {
            composed = programImage
        }

        let timestamp = program.presentationTime
        guard timestamp.isValid, timestamp.isNumeric else { return }
        if let lastPresentationTime {
            let elapsed = CMTimeSubtract(timestamp, lastPresentationTime)
            guard CMTimeCompare(elapsed, .zero) > 0,
                  CMTimeCompare(elapsed, CMTime(value: 1, timescale: 30)) >= 0 else { return }
        }
        guard ensureWriter(at: timestamp), let writer, let input = writerInput, let adaptor,
              writer.status == .writing, input.isReadyForMoreMediaData,
              let pool = adaptor.pixelBufferPool else { return }
        var output: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &output) == kCVReturnSuccess,
              let output else { return }
        imageContext.render(composed, to: output, bounds: canvas, colorSpace: CGColorSpaceCreateDeviceRGB())
        guard adaptor.append(output, withPresentationTime: timestamp) else {
            failOnQueue(writer.error?.localizedDescription ?? "A PGM video frame could not be written.")
            return
        }
        lastPresentationTime = timestamp
        drainAudio()
    }

    private func drainAudio(force: Bool = false) {
        guard writer != nil, audioWriterInput != nil, let programSourceID else { return }
        if transitionSourceID == nil {
            let frames = pendingAudio.removeValue(forKey: programSourceID) ?? []
            frames.forEach { writeAudio($0.samples, at: $0.presentationTime, gain: 1) }
            return
        }

        guard let transitionSourceID else { return }
        let currentTime = CMClockGetTime(CMClockGetHostTimeClock())
        while true {
            var programFrames = pendingAudio[programSourceID] ?? []
            var transitionFrames = pendingAudio[transitionSourceID] ?? []
            if let firstProgram = programFrames.first, !transitionFrames.isEmpty {
                let nearestIndex = transitionFrames.indices.min { left, right in
                    abs(CMTimeGetSeconds(CMTimeSubtract(transitionFrames[left].presentationTime,
                                                        firstProgram.presentationTime))) <
                    abs(CMTimeGetSeconds(CMTimeSubtract(transitionFrames[right].presentationTime,
                                                        firstProgram.presentationTime)))
                }!
                let targetFrame = transitionFrames[nearestIndex]
                let delta = abs(CMTimeGetSeconds(CMTimeSubtract(targetFrame.presentationTime,
                                                                 firstProgram.presentationTime)))
                if delta <= 0.040 {
                    programFrames.removeFirst()
                    transitionFrames.remove(at: nearestIndex)
                    pendingAudio[programSourceID] = programFrames
                    pendingAudio[transitionSourceID] = transitionFrames
                    let samples = Self.mixAudio(firstProgram.samples, targetFrame.samples,
                                                firstGain: 1 - transitionProgress,
                                                secondGain: transitionProgress)
                    let timestamp = CMTimeCompare(firstProgram.presentationTime, targetFrame.presentationTime) >= 0
                        ? firstProgram.presentationTime : targetFrame.presentationTime
                    writeAudio(samples, at: timestamp, gain: 1)
                    continue
                }
            }

            let programTime = programFrames.first?.presentationTime
            let transitionTime = transitionFrames.first?.presentationTime
            guard programTime != nil || transitionTime != nil else { return }
            let emitProgram: Bool
            if let programTime, let transitionTime {
                emitProgram = CMTimeCompare(programTime, transitionTime) <= 0
            } else {
                emitProgram = programTime != nil
            }
            let oldest = emitProgram ? programFrames[0] : transitionFrames[0]
            let age = CMTimeGetSeconds(CMTimeSubtract(currentTime, oldest.presentationTime))
            guard force || age >= 0.040 else { return }

            if emitProgram {
                programFrames.removeFirst()
                pendingAudio[programSourceID] = programFrames
                writeAudio(oldest.samples, at: oldest.presentationTime, gain: 1 - transitionProgress)
            } else {
                transitionFrames.removeFirst()
                pendingAudio[transitionSourceID] = transitionFrames
                writeAudio(oldest.samples, at: oldest.presentationTime, gain: transitionProgress)
            }
        }
    }

    private func writeAudio(_ samples: [Int16], at sourceTime: CMTime, gain: Double) {
        guard !samples.isEmpty, let writer, let input = audioWriterInput,
              let format = audioFormatDescription, writer.status == .writing,
              input.isReadyForMoreMediaData else { return }
        let pcm = Self.pcmData(samples, gain: gain)
        guard !pcm.isEmpty else { return }
        var blockBuffer: CMBlockBuffer?
        let createBlockStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: pcm.count,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
            dataLength: pcm.count, flags: 0, blockBufferOut: &blockBuffer
        )
        guard createBlockStatus == kCMBlockBufferNoErr, let blockBuffer else { return }
        let copyStatus = pcm.withUnsafeBytes { bytes in
            guard let baseAddress = bytes.baseAddress else { return kCMBlockBufferBadLengthParameterErr }
            return CMBlockBufferReplaceDataBytes(with: baseAddress, blockBuffer: blockBuffer,
                                                  offsetIntoDestination: 0, dataLength: pcm.count)
        }
        guard copyStatus == kCMBlockBufferNoErr else { return }

        let duration = CMTime(value: Int64(samples.count), timescale: Self.recordingAudioSampleRate)
        var timestamp = sourceTime
        if let recordingStartTime, CMTimeCompare(timestamp, recordingStartTime) < 0 {
            timestamp = recordingStartTime
        }
        if let audioPresentationCursor, CMTimeCompare(timestamp, audioPresentationCursor) < 0 {
            timestamp = audioPresentationCursor
        }
        var timing = CMSampleTimingInfo(duration: duration, presentationTimeStamp: timestamp,
                                        decodeTimeStamp: .invalid)
        var sampleBuffer: CMSampleBuffer?
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: blockBuffer, formatDescription: format,
            sampleCount: samples.count, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
            sampleSizeEntryCount: 0, sampleSizeArray: nil, sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else { return }
        guard input.append(sampleBuffer) else {
            failOnQueue(writer.error?.localizedDescription ?? "A PGM audio frame could not be written.")
            return
        }
        audioPresentationCursor = CMTimeAdd(timestamp, duration)
    }

    private static let recordingAudioSampleRate: Int32 = 48_000

    private static func normalizeAudio(_ frame: CamHubPCMFrame) -> [Int16]? {
        guard frame.sampleRate.isFinite, frame.sampleRate >= 8_000, frame.sampleRate <= 96_000,
              (1...2).contains(frame.channels), frame.samples.count.isMultiple(of: frame.channels * 2) else { return nil }
        let inputFrameCount = frame.samples.count / (frame.channels * 2)
        guard inputFrameCount > 0 else { return nil }
        let outputFrameCount = max(1, Int((Double(inputFrameCount) * Double(recordingAudioSampleRate) / frame.sampleRate).rounded()))
        var mono = [Double](repeating: 0, count: inputFrameCount)
        frame.samples.withUnsafeBytes { raw in
            guard let bytes = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            for index in 0..<inputFrameCount {
                var sum = 0.0
                for channel in 0..<frame.channels {
                    let offset = (index * frame.channels + channel) * 2
                    let value = Int16(bitPattern: UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8)
                    sum += Double(value) / Double(Int16.max)
                }
                mono[index] = sum / Double(frame.channels)
            }
        }
        if frame.sampleRate == Double(recordingAudioSampleRate) { return mono.map(Self.toInt16) }
        var output = [Int16](repeating: 0, count: outputFrameCount)
        let step = frame.sampleRate / Double(recordingAudioSampleRate)
        for index in 0..<outputFrameCount {
            let position = Double(index) * step
            let left = min(inputFrameCount - 1, Int(position))
            let right = min(inputFrameCount - 1, left + 1)
            let fraction = position - Double(left)
            output[index] = toInt16(mono[left] * (1 - fraction) + mono[right] * fraction)
        }
        return output
    }

    private static func mixAudio(_ first: [Int16], _ second: [Int16], firstGain: Double,
                                 secondGain: Double) -> [Int16] {
        let count = max(first.count, second.count)
        return (0..<count).map { index in
            let left = index < first.count ? Double(first[index]) * firstGain : 0
            let right = index < second.count ? Double(second[index]) * secondGain : 0
            let value = min(Double(Int16.max), max(Double(Int16.min), left + right))
            return Int16(value.rounded())
        }
    }

    private static func pcmData(_ samples: [Int16], gain: Double) -> Data {
        let normalizedGain = gain.isFinite ? min(1, max(0, gain)) : 0
        var data = Data(capacity: samples.count * MemoryLayout<Int16>.size)
        for sample in samples {
            let scaled = min(Double(Int16.max), max(Double(Int16.min), Double(sample) * normalizedGain))
            var value = UInt16(bitPattern: Int16(scaled.rounded())).littleEndian
            withUnsafeBytes(of: &value) { data.append(contentsOf: $0) }
        }
        return data
    }

    private static func toInt16(_ value: Double) -> Int16 {
        Int16(min(Double(Int16.max), max(Double(Int16.min), value * Double(Int16.max))).rounded())
    }

    private func framedImage(_ frame: Frame, in canvas: CGRect) -> CIImage {
        var image = CIImage(cvPixelBuffer: frame.pixels)
        switch ((frame.rotation % 360) + 360) % 360 {
        case 90: image = image.oriented(.right)
        case 180: image = image.oriented(.down)
        case 270: image = image.oriented(.left)
        default: break
        }
        let extent = image.extent
        let fit = min(canvas.width / extent.width, canvas.height / extent.height)
        let fittedWidth = extent.width * fit
        let fittedHeight = extent.height * fit
        let zoom = frame.framing.zoom
        let scale = fit * zoom
        let tx = (canvas.width - fittedWidth * zoom) / 2 + frame.framing.x * fittedWidth - extent.minX * scale
        let ty = (canvas.height - fittedHeight * zoom) / 2 - frame.framing.y * fittedHeight - extent.minY * scale
        let transform = CGAffineTransform(a: scale, b: 0, c: 0, d: scale, tx: tx, ty: ty)
        let transformed = image.transformed(by: transform).cropped(to: canvas)
        return transformed.composited(over: CIImage(color: .black).cropped(to: canvas)).cropped(to: canvas)
    }

    private func ensureWriter(at presentationTime: CMTime) -> Bool {
        guard writer == nil else { return true }
        do {
            let directory: URL
            if let recordingsDirectory {
                directory = recordingsDirectory
            } else {
                directory = try FileManager.default.url(
                    for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true
                ).appendingPathComponent("CamHub PGM Recordings", isDirectory: true)
            }
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withYear, .withMonth, .withDay, .withTime]
            let name = "CamHub-PGM-\(formatter.string(from: Date()).replacingOccurrences(of: ":", with: "-"))-\(UUID().uuidString.prefix(8)).mp4"
            let url = directory.appendingPathComponent(name)
            let assetWriter = try AVAssetWriter(outputURL: url, fileType: .mp4)
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: outputWidth,
                AVVideoHeightKey: outputHeight,
                AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: 6_000_000,
                    AVVideoExpectedSourceFrameRateKey: 30,
                    AVVideoMaxKeyFrameIntervalKey: 30,
                    AVVideoAllowFrameReorderingKey: false
                ]
            ])
            input.expectsMediaDataInRealTime = true
            guard assetWriter.canAdd(input) else { throw assetWriter.error ?? Self.writerError("The PGM encoder is unavailable.") }
            assetWriter.add(input)
            let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: Self.recordingAudioSampleRate,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 96_000
            ])
            audioInput.expectsMediaDataInRealTime = true
            guard assetWriter.canAdd(audioInput) else {
                throw assetWriter.error ?? Self.writerError("The PGM AAC audio encoder is unavailable.")
            }
            assetWriter.add(audioInput)
            var audioFormat: CMAudioFormatDescription?
            let audioFormatStatus = Self.makeAudioFormatDescription(&audioFormat)
            guard audioFormatStatus == noErr, let audioFormat else {
                throw Self.writerError("The PGM PCM audio format could not be created (\(audioFormatStatus)).")
            }
            let adapter = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: outputWidth,
                kCVPixelBufferHeightKey as String: outputHeight,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
                kCVPixelBufferMetalCompatibilityKey as String: true
            ])
            guard assetWriter.startWriting() else { throw assetWriter.error ?? Self.writerError("The PGM recording could not start.") }
            assetWriter.startSession(atSourceTime: presentationTime)
            writer = assetWriter
            writerInput = input
            audioWriterInput = audioInput
            adaptor = adapter
            audioFormatDescription = audioFormat
            outputURL = url
            recordingStartTime = presentationTime
            return true
        } catch {
            failOnQueue(error.localizedDescription)
            return false
        }
    }

    private static func makeAudioFormatDescription(_ output: inout CMAudioFormatDescription?) -> OSStatus {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: Double(recordingAudioSampleRate),
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked | kAudioFormatFlagsNativeEndian,
            mBytesPerPacket: 2,
            mFramesPerPacket: 1,
            mBytesPerFrame: 2,
            mChannelsPerFrame: 1,
            mBitsPerChannel: 16,
            mReserved: 0
        )
        return CMAudioFormatDescriptionCreate(
            allocator: kCFAllocatorDefault, asbd: &asbd, layoutSize: 0, layout: nil,
            magicCookieSize: 0, magicCookie: nil, extensions: nil,
            formatDescriptionOut: &output
        )
    }

    private static func writerError(_ message: String) -> NSError {
        NSError(domain: "CamHubHubPgmRecorder", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }

    private func failOnQueue(_ message: String) {
        requestLock.lock(); recordingRequested = false; requestLock.unlock()
        writer?.cancelWriting()
        if let outputURL { try? FileManager.default.removeItem(at: outputURL) }
        decoders.values.forEach { VTDecompressionSessionInvalidate($0.session) }
        decoders.removeAll()
        latestFrames.removeAll()
        clearWriterState()
        publish(.failed(message))
    }

    private func clearWriterState() {
        writer = nil
        writerInput = nil
        audioWriterInput = nil
        adaptor = nil
        audioFormatDescription = nil
        outputURL = nil
        lastPresentationTime = nil
        recordingStartTime = nil
        audioPresentationCursor = nil
        pendingAudio.removeAll(keepingCapacity: true)
    }

    private func publish(_ newState: State, recordingURL: URL? = nil) {
        DispatchQueue.main.async {
            self.state = newState
            if case .saved = newState { self.latestRecordingURL = recordingURL }
        }
    }
}
