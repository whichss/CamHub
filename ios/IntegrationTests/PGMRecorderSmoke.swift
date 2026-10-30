import AVFoundation
import CamHubCore
import Foundation
import VideoToolbox

@main
struct PGMRecorderSmoke {
    static func main() throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("camhub-pgm-recorder-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: scratch) }

        let fixtures = try fixtureDirectory()
        let program = try loadSamples(from: fixtures.appendingPathComponent("pgm-red-320x180.h264"), count: 18)
        let preview = try loadSamples(from: fixtures.appendingPathComponent("pgm-green-320x180.h264"), count: 18)
        precondition(program.count >= 12 && preview.count >= 12, "H.264 fixtures did not produce enough samples")
        if let reason = videoToolboxUnavailable(for: program[0]) {
            print("PGM recorder smoke test skipped: \(reason)")
            return
        }
        let audio = makeAudioFrame()

        let recorder = CamHubHubPgmRecorder(recordingsDirectory: scratch)
        recorder.setComposition(programID: "program", transitionID: "preview", progress: 0)
        recorder.start(width: 320, height: 180, requestKeyFrames: [])
        for index in 0..<12 {
            recorder.setComposition(programID: "program", transitionID: "preview",
                                    progress: Double(index) / 11)
            recorder.append(program[index], sourceID: "program", rotation: 0,
                            framing: CamHubFraming(zoom: 1, x: 0, y: 0), requestKeyFrame: {})
            recorder.append(preview[index], sourceID: "preview", rotation: 0,
                            framing: CamHubFraming(zoom: 1, x: 0, y: 0), requestKeyFrame: {})
            for packet in 0..<3 {
                let audioIndex = index * 3 + packet
                let time = CMTime(value: Int64(audioIndex * 960), timescale: 48_000)
                recorder.appendAudio(audio, sourceID: "program", presentationTime: time)
                recorder.appendAudio(audio, sourceID: "preview", presentationTime: time)
            }
            RunLoop.main.run(until: Date().addingTimeInterval(0.10))
        }
        recorder.setComposition(programID: "preview", transitionID: nil, progress: 0)
        for sample in preview.dropFirst(12) {
            recorder.append(sample, sourceID: "preview", rotation: 0,
                            framing: CamHubFraming(zoom: 1, x: 0, y: 0), requestKeyFrame: {})
            RunLoop.main.run(until: Date().addingTimeInterval(0.04))
        }
        for packet in 36..<48 {
            recorder.appendAudio(audio, sourceID: "preview",
                                 presentationTime: CMTime(value: Int64(packet * 960), timescale: 48_000))
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        }
        recorder.stop()

        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            if case .saved = recorder.state { break }
            if case let .failed(message) = recorder.state { throw SmokeError.recordingFailed(message) }
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        guard case .saved = recorder.state, let url = recorder.latestRecordingURL else {
            throw SmokeError.recordingTimedOut
        }
        do { try verifyMovie(url) } catch { fputs("PGM movie verification failed: \(error)\n", stderr); throw error }
        print("PGM recorder smoke test passed: decoded both H.264 sources, mixed PCM through a dissolve, and wrote audio/video MP4")
    }

    private static func makeAudioFrame() -> CamHubPCMFrame {
        var samples = Data(capacity: 960 * 2)
        for index in 0..<960 {
            let value = Int16(sin(Double(index) * 2 * .pi * 440 / 48_000) * 8_000)
            var littleEndian = UInt16(bitPattern: value).littleEndian
            withUnsafeBytes(of: &littleEndian) { samples.append(contentsOf: $0) }
        }
        return CamHubPCMFrame(samples: samples, sampleRate: 48_000, channels: 1)
    }

    private static func fixtureDirectory() throws -> URL {
        guard let path = ProcessInfo.processInfo.environment["CAMHUB_TEST_FIXTURES"] else {
            throw SmokeError.fixtureUnavailable("CAMHUB_TEST_FIXTURES is not set")
        }
        return URL(fileURLWithPath: path, isDirectory: true)
    }

    private static func loadSamples(from url: URL, count: Int) throws -> [CMSampleBuffer] {
        let annexB = try Data(contentsOf: url)
        let builder = CamHubH264SampleBuilder()
        return try (0..<count).map { index in
            guard let sample = builder.makeSample(
                annexB,
                presentationTime: CMTime(value: Int64(index), timescale: 30)
            ) else { throw SmokeError.fixtureUnavailable("Could not parse H.264 fixture at frame \(index)") }
            return sample
        }
    }

    private static func videoToolboxUnavailable(for sample: CMSampleBuffer) -> String? {
        var encoders: CFArray?
        guard VTCopyVideoEncoderList(nil, &encoders) == noErr,
              let encoders = encoders as? [[CFString: Any]] else {
            return "VideoToolbox did not provide its encoder capability list"
        }
        let hasH264Encoder = encoders.contains {
            ($0[kVTVideoEncoderList_CodecType] as? NSNumber)?.uint32Value == kCMVideoCodecType_H264
        }
        guard hasH264Encoder else { return "this host has no H.264 VideoToolbox encoder" }
        guard let format = CMSampleBufferGetFormatDescription(sample) else {
            return "the H.264 fixture has no format description"
        }
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: { _, _, _, _, _, _, _ in },
            decompressionOutputRefCon: nil
        )
        var decoder: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault, formatDescription: format, decoderSpecification: nil,
            imageBufferAttributes: nil, outputCallback: &callback, decompressionSessionOut: &decoder
        )
        if let decoder { VTDecompressionSessionInvalidate(decoder) }
        return status == noErr ? nil : "this host cannot initialize an H.264 VideoToolbox decoder (\(status))"
    }

    private static func verifyMovie(_ url: URL) throws {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else { throw SmokeError.invalidMovie("no video track") }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        guard reader.canAdd(output) else { throw SmokeError.invalidMovie("reader cannot add video track") }
        reader.add(output)
        guard reader.startReading() else { throw SmokeError.invalidMovie("reader start: \(reader.error?.localizedDescription ?? "unknown")") }
        var count = 0
        while output.copyNextSampleBuffer() != nil { count += 1 }
        guard reader.status == .completed, count >= 8 else {
            throw SmokeError.invalidMovie("reader status=\(reader.status.rawValue), frames=\(count), error=\(reader.error?.localizedDescription ?? "none")")
        }
        let dimensions = CMVideoFormatDescriptionGetDimensions(track.formatDescriptions.first as! CMVideoFormatDescription)
        guard dimensions.width == 320, dimensions.height == 180 else {
            throw SmokeError.invalidMovie("unexpected dimensions \(dimensions.width)x\(dimensions.height)")
        }

        guard let audioTrack = asset.tracks(withMediaType: .audio).first else {
            throw SmokeError.invalidMovie("no AAC audio track")
        }
        let audioReader = try AVAssetReader(asset: asset)
        let audioOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: nil)
        guard audioReader.canAdd(audioOutput) else { throw SmokeError.invalidMovie("reader cannot add audio track") }
        audioReader.add(audioOutput)
        guard audioReader.startReading() else {
            throw SmokeError.invalidMovie("audio reader start: \(audioReader.error?.localizedDescription ?? "unknown")")
        }
        var audioCount = 0
        while audioOutput.copyNextSampleBuffer() != nil { audioCount += 1 }
        guard audioReader.status == .completed, audioCount > 0 else {
            throw SmokeError.invalidMovie("audio reader status=\(audioReader.status.rawValue), packets=\(audioCount)")
        }
    }

    private enum SmokeError: Error {
        case fixtureUnavailable(String)
        case recordingFailed(String)
        case recordingTimedOut
        case invalidMovie(String)
    }
}
