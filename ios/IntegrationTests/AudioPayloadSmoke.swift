import Foundation

@main
private struct AudioPayloadSmoke {
    static func main() throws {
        let pcmV1 = Data([1, 1, 0x00, 0x80, 0xFF, 0x7F])
        let legacy = try CamHubAudioPayloadDecoder.decode(pcmV1)
        guard legacy.sampleRate == 44_100, legacy.channels == 1,
              legacy.samples == pcmV1.dropFirst(2) else {
            throw SmokeError.invalidFrame("v1 PCM was not decoded as 44.1 kHz mono")
        }

        let pcmV2 = Data([2, 1, 0, 0x01, 0xE0, 0x00, 0x80, 0xFF, 0x7F])
        let current = try CamHubAudioPayloadDecoder.decode(pcmV2)
        guard current.sampleRate == 48_000, current.channels == 1,
              current.samples == pcmV2.dropFirst(5) else {
            throw SmokeError.invalidFrame("v2 PCM sample rate or payload was parsed incorrectly")
        }

        let stereo = Data([2, 2, 0, 0x01, 0xB9, 0x00, 0x00, 0xFF, 0x7F])
        let stereoFrame = try CamHubAudioPayloadDecoder.decode(stereo)
        guard stereoFrame.sampleRate == 44_100, stereoFrame.channels == 2 else {
            throw SmokeError.invalidFrame("stereo PCM metadata was not preserved")
        }

        try expectRejected(Data([2, 1, 1, 0x01, 0xE0, 0x00, 0x00]), "Opus")
        try expectRejected(Data([2, 3, 0, 0x01, 0xE0, 0, 0, 0, 0]), "invalid channel count")
        try expectRejected(Data([2, 1, 0, 0, 0, 0, 0]), "invalid sample rate")
        try expectRejected(Data([1, 1, 0]), "unaligned PCM samples")
        print("Audio payload smoke test passed: v1/v2 mono/stereo PCM metadata and invalid frames")
    }

    private static func expectRejected(_ payload: Data, _ label: String) throws {
        do {
            _ = try CamHubAudioPayloadDecoder.decode(payload)
            throw SmokeError.invalidFrame("accepted \(label)")
        } catch is CamHubAudioPayloadError {
            return
        }
    }

    private enum SmokeError: Error {
        case invalidFrame(String)
    }
}
