import Foundation

struct CamHubPCMFrame {
    let samples: Data
    let sampleRate: Double
    let channels: Int
}

enum CamHubAudioPayloadError: Error {
    case invalidHeader
    case unsupportedCodec
    case invalidSamples
}

enum CamHubAudioPayloadDecoder {
    private static let pcmCodec: UInt8 = 0

    static func decode(_ payload: Data) throws -> CamHubPCMFrame {
        guard payload.count >= 4 else { throw CamHubAudioPayloadError.invalidHeader }
        let version = payload[0]
        let channels = Int(payload[1])
        guard (1...2).contains(channels) else { throw CamHubAudioPayloadError.invalidHeader }

        let sampleRate: Double
        let samples: Data
        switch version {
        case 1:
            sampleRate = 44_100
            samples = payload.dropFirst(2)
        case 2:
            guard payload.count > 5 else { throw CamHubAudioPayloadError.invalidHeader }
            let rateDiv100 = (UInt16(payload[3]) << 8) | UInt16(payload[4])
            sampleRate = Double(rateDiv100) * 100
            guard sampleRate >= 8_000, sampleRate <= 96_000 else {
                throw CamHubAudioPayloadError.invalidHeader
            }
            guard payload[2] == pcmCodec else { throw CamHubAudioPayloadError.unsupportedCodec }
            samples = payload.dropFirst(5)
        default:
            throw CamHubAudioPayloadError.invalidHeader
        }

        guard !samples.isEmpty, samples.count.isMultiple(of: channels * MemoryLayout<Int16>.size) else {
            throw CamHubAudioPayloadError.invalidSamples
        }
        return CamHubPCMFrame(samples: samples, sampleRate: sampleRate, channels: channels)
    }
}
