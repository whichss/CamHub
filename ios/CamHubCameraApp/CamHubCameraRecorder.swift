import AVFoundation
import CamHubCore
import Combine
import CoreMedia
import Foundation

/// Writes the camera's existing H.264 samples to disk without opening a second encoder.
/// All AVAssetWriter state is confined to `queue`; incoming samples are bounded to two items.
final class CamHubCameraRecorder: ObservableObject {
    enum State: Equatable {
        case idle
        case starting
        case recording
        case saving
        case saved
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var latestRecordingURL: URL?

    var onEncodingDemandChanged: ((Bool) -> Void)?

    private let queue = DispatchQueue(label: "com.camhub.camera.recording", qos: .utility)
    private let pendingSamples = DispatchSemaphore(value: 2)
    private let requestLock = NSLock()
    private let gapLock = NSLock()
    private var recordingRequested = false
    private var gapNotificationQueued = false
    // The remaining fields are confined to `queue`.
    private var writer: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var currentURL: URL?
    private var frameGate = CamHubKeyframeRecoveryGate()

    var isRecording: Bool {
        switch state {
        case .starting, .recording: return true
        default: return false
        }
    }

    func start(requestKeyFrame: @escaping () -> Void) {
        requestLock.lock()
        guard !recordingRequested else {
            requestLock.unlock()
            return
        }
        recordingRequested = true
        requestLock.unlock()

        onEncodingDemandChanged?(true)
        publish(.starting)
        queue.async {
            self.frameGate.requireKeyFrame()
            DispatchQueue.main.async { self.latestRecordingURL = nil }
            requestKeyFrame()
        }
    }

    /// Called from the encoder output queue. It never waits for disk I/O.
    func append(_ sampleBuffer: CMSampleBuffer, rotationDegrees: Int,
                requestKeyFrame: @escaping () -> Void) {
        requestLock.lock()
        let shouldRecord = recordingRequested
        requestLock.unlock()
        guard shouldRecord else { return }

        guard pendingSamples.wait(timeout: .now()) == .success else {
            markInputGap(requestKeyFrame: requestKeyFrame)
            return
        }
        queue.async {
            defer { self.pendingSamples.signal() }
            guard self.isRecordingRequested else { return }
            self.appendOnQueue(sampleBuffer, rotationDegrees: rotationDegrees,
                               requestKeyFrame: requestKeyFrame)
        }
    }

    func stop() {
        requestLock.lock()
        let wasRecording = recordingRequested
        recordingRequested = false
        requestLock.unlock()
        guard wasRecording else { return }

        onEncodingDemandChanged?(false)
        queue.async {
            guard let writer = self.writer, let input = self.writerInput else {
                self.clearWriterState()
                self.publish(.idle)
                return
            }
            self.publish(.saving)
            input.markAsFinished()
            writer.finishWriting {
                self.queue.async {
                    let url = self.currentURL
                    let result: State
                    if writer.status == .completed, url != nil {
                        result = .saved
                    } else {
                        result = .failed(writer.error?.localizedDescription ?? "The recording could not be finalized.")
                        if let url { try? FileManager.default.removeItem(at: url) }
                    }
                    self.clearWriterState()
                    DispatchQueue.main.async {
                        if result == .saved { self.latestRecordingURL = url }
                        self.state = result
                    }
                }
            }
        }
    }

    private var isRecordingRequested: Bool {
        requestLock.lock()
        defer { requestLock.unlock() }
        return recordingRequested
    }

    private func markInputGap(requestKeyFrame: @escaping () -> Void) {
        gapLock.lock()
        let shouldQueueNotification = !gapNotificationQueued
        gapNotificationQueued = true
        gapLock.unlock()
        guard shouldQueueNotification else { return }

        queue.async {
            self.frameGate.requireKeyFrame()
            self.gapLock.lock()
            self.gapNotificationQueued = false
            self.gapLock.unlock()
            requestKeyFrame()
        }
    }

    private func appendOnQueue(_ sampleBuffer: CMSampleBuffer, rotationDegrees: Int,
                               requestKeyFrame: @escaping () -> Void) {
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
            as? [[CFString: Any]]
        let isKeyFrame = (attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool) != true
        guard frameGate.shouldAppend(isKeyFrame: isKeyFrame) else { return }

        if writer == nil {
            guard isKeyFrame, createWriter(sampleBuffer, rotationDegrees: rotationDegrees) else {
                requestKeyFrame()
                return
            }
        }

        guard let writer, let writerInput else { return }
        guard writer.status == .writing else {
            failOnQueue(writer.error?.localizedDescription ?? "The recording writer stopped unexpectedly.")
            return
        }
        guard writerInput.isReadyForMoreMediaData else {
            frameGate.requireKeyFrame()
            requestKeyFrame()
            return
        }
        guard writerInput.append(sampleBuffer) else {
            let reason = writer.error?.localizedDescription ?? "The H.264 sample could not be written."
            failOnQueue(reason)
            return
        }
        publish(.recording)
    }

    private func createWriter(_ sampleBuffer: CMSampleBuffer, rotationDegrees: Int) -> Bool {
        guard let format = CMSampleBufferGetFormatDescription(sampleBuffer) else {
            failOnQueue("The camera encoder did not provide an H.264 format description.")
            return false
        }
        do {
            let directory = try FileManager.default.url(
                for: .documentDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("CamHub Recordings", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withYear, .withMonth, .withDay, .withTime]
            let timestamp = formatter.string(from: Date()).replacingOccurrences(of: ":", with: "-")
            let fileURL = directory.appendingPathComponent("CamHub-\(timestamp)-\(UUID().uuidString.prefix(8)).mov")
            let assetWriter = try AVAssetWriter(outputURL: fileURL, fileType: .mov)
            currentURL = fileURL
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: format)
            input.expectsMediaDataInRealTime = true
            input.transform = Self.trackTransform(rotationDegrees: rotationDegrees)
            guard assetWriter.canAdd(input) else {
                throw NSError(domain: "CamHubCameraRecorder", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "The H.264 recording input is not supported."])
            }
            assetWriter.add(input)
            guard assetWriter.startWriting() else {
                throw assetWriter.error ?? NSError(domain: "CamHubCameraRecorder", code: 2,
                                                   userInfo: [NSLocalizedDescriptionKey: "The recording could not start."])
            }
            assetWriter.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
            writer = assetWriter
            writerInput = input
            currentURL = fileURL
            return true
        } catch {
            failOnQueue(error.localizedDescription)
            return false
        }
    }

    private static func trackTransform(rotationDegrees: Int) -> CGAffineTransform {
        switch ((rotationDegrees % 360) + 360) % 360 {
        case 90: return CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 720, ty: 0)
        case 180: return CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: 1_280, ty: 720)
        case 270: return CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: 1_280)
        default: return .identity
        }
    }

    private func failOnQueue(_ message: String) {
        requestLock.lock()
        recordingRequested = false
        requestLock.unlock()
        onEncodingDemandChanged?(false)
        writer?.cancelWriting()
        if let currentURL { try? FileManager.default.removeItem(at: currentURL) }
        clearWriterState()
        publish(.failed(message))
    }

    private func clearWriterState() {
        writer = nil
        writerInput = nil
        currentURL = nil
        frameGate.requireKeyFrame()
    }

    private func publish(_ newState: State) {
        DispatchQueue.main.async { self.state = newState }
    }
}
