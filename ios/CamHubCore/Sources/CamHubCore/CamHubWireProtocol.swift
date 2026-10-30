import Foundation
import CryptoKit
import CoreMedia

public struct CamHubFraming: Equatable {
    public let zoom: Double
    public let x: Double
    public let y: Double

    public init(zoom: Double, x: Double, y: Double) {
        let safeZoom = zoom.isFinite ? min(4, max(1, zoom)) : 1
        let limit = (safeZoom - 1) / 2
        self.zoom = safeZoom
        self.x = x.isFinite ? min(limit, max(-limit, x)) : 0
        self.y = y.isFinite ? min(limit, max(-limit, y)) : 0
    }

    public static func zoomedAt(x: Double, y: Double, zoom: Double = 2) -> CamHubFraming {
        let targetZoom = zoom.isFinite ? min(4, max(1, zoom)) : 1
        return CamHubFraming(zoom: targetZoom, x: (0.5 - x) * targetZoom, y: (0.5 - y) * targetZoom)
    }

    public static func fittedSize(source: CGSize, rotation: Int, viewport: CGSize) -> CGSize {
        let swapped = ((rotation % 180) + 180) % 180 == 90
        let width = swapped ? source.height : source.width
        let height = swapped ? source.width : source.height
        guard width.isFinite, height.isFinite, viewport.width.isFinite, viewport.height.isFinite,
              width > 0, height > 0, viewport.width > 0, viewport.height > 0 else { return .zero }
        let scale = min(viewport.width / width, viewport.height / height)
        return CGSize(width: width * scale, height: height * scale)
    }
}

/// A deterministic eased progress value for program transitions. The same elapsed-time input can
/// drive both the preview compositor and an offline or real-time recording compositor.
public struct CamHubTransitionTimeline {
    public let durationNanoseconds: UInt64

    public init(durationMilliseconds: UInt64 = 350) {
        durationNanoseconds = max(1, durationMilliseconds) * 1_000_000
    }

    public func progress(elapsedNanoseconds: UInt64) -> Double {
        let linear = min(1, Double(elapsedNanoseconds) / Double(durationNanoseconds))
        return linear * linear * (3 - 2 * linear)
    }
}

/// Computes a source-pixel crop for a normalized framing request. The returned rectangle uses
/// Core Image's bottom-left origin and preserves the requested output aspect ratio.
public enum CamHubSourceCrop {
    public static func rectangle(source: CGSize, output: CGSize, zoom: Double,
                                 centerX: Double, centerY: Double, rotation: Int) -> CGRect? {
        guard source.width.isFinite, source.height.isFinite, output.width.isFinite, output.height.isFinite,
              source.width > 0, source.height > 0, output.width > 0, output.height > 0,
              zoom.isFinite, zoom >= 1, centerX.isFinite, centerY.isFinite else { return nil }
        let normalizedZoom = min(32, max(1, zoom))
        let halfVisible = 0.5 / normalizedZoom
        let x = min(1 - halfVisible, max(halfVisible, centerX))
        let y = min(1 - halfVisible, max(halfVisible, centerY))
        let rawCenter: (Double, Double)
        switch ((rotation % 360) + 360) % 360 {
        case 90: rawCenter = (y, 1 - x)
        case 180: rawCenter = (1 - x, 1 - y)
        case 270: rawCenter = (1 - y, x)
        default: rawCenter = (x, y)
        }
        let aspect = output.width / output.height
        let cropWidth = min(source.width, source.height * aspect) / normalizedZoom
        let cropHeight = cropWidth / aspect
        let originX = min(source.width - cropWidth, max(0, rawCenter.0 * source.width - cropWidth / 2))
        let originY = min(source.height - cropHeight, max(0, (1 - rawCenter.1) * source.height - cropHeight / 2))
        guard cropWidth.isFinite, cropHeight.isFinite, cropWidth > 0, cropHeight > 0 else { return nil }
        return CGRect(x: originX, y: originY, width: cropWidth, height: cropHeight)
    }
}

public enum CamHubPtzMode: Equatable {
    case hub
    case remotePending
    case remote
}

/// Tracks the requested source crop and the crop the remote camera has confirmed.
/// The hub renders only the residual crop while a remote PTZ request is pending.
public struct CamHubHybridPtzState: Equatable {
    public private(set) var zoom: Double
    public private(set) var centerX: Double
    public private(set) var centerY: Double
    public private(set) var mode: CamHubPtzMode
    public private(set) var remoteAppliedZoom: Double
    public private(set) var remoteAppliedCenterX: Double
    public private(set) var remoteAppliedCenterY: Double

    public init(zoom: Double = 1, centerX: Double = 0.5, centerY: Double = 0.5,
                mode: CamHubPtzMode = .hub, remoteAppliedZoom: Double = 1,
                remoteAppliedCenterX: Double = 0.5, remoteAppliedCenterY: Double = 0.5) {
        let safeZoom = Self.clampZoom(zoom)
        self.zoom = safeZoom
        self.centerX = Self.clampCenter(centerX, zoom: safeZoom)
        self.centerY = Self.clampCenter(centerY, zoom: safeZoom)
        self.mode = mode
        let appliedZoom = Self.clampZoom(remoteAppliedZoom)
        self.remoteAppliedZoom = appliedZoom
        self.remoteAppliedCenterX = Self.clampCenter(remoteAppliedCenterX, zoom: appliedZoom)
        self.remoteAppliedCenterY = Self.clampCenter(remoteAppliedCenterY, zoom: appliedZoom)
    }

    public mutating func request(zoom: Double, centerX: Double, centerY: Double,
                                 supportsRemotePtz: Bool) {
        self.zoom = Self.clampZoom(zoom)
        self.centerX = Self.clampCenter(centerX, zoom: self.zoom)
        self.centerY = Self.clampCenter(centerY, zoom: self.zoom)
        mode = supportsRemotePtz ? .remotePending : .hub
    }

    @discardableResult
    public mutating func markRemoteApplied(zoom: Double, centerX: Double, centerY: Double) -> Bool {
        guard zoom.isFinite, centerX.isFinite, centerY.isFinite else { return false }
        remoteAppliedZoom = Self.clampZoom(zoom)
        remoteAppliedCenterX = Self.clampCenter(centerX, zoom: remoteAppliedZoom)
        remoteAppliedCenterY = Self.clampCenter(centerY, zoom: remoteAppliedZoom)
        mode = .remote
        // A camera may report a supported but rounded crop. Keep any residual crop on the hub.
        if abs(remoteAppliedZoom - self.zoom) < 0.01 &&
            abs(remoteAppliedCenterX - self.centerX) < 0.01 &&
            abs(remoteAppliedCenterY - self.centerY) < 0.01 {
            self.zoom = remoteAppliedZoom
            self.centerX = remoteAppliedCenterX
            self.centerY = remoteAppliedCenterY
        } else {
            mode = .remotePending
        }
        return true
    }

    public var hubFraming: CamHubFraming {
        let scale: Double
        let centerX: Double
        let centerY: Double
        switch mode {
        case .hub:
            scale = zoom
            centerX = self.centerX
            centerY = self.centerY
        case .remotePending:
            scale = max(1, zoom / max(1, remoteAppliedZoom))
            centerX = Self.clampCenter(
                0.5 + (self.centerX - remoteAppliedCenterX) * remoteAppliedZoom, zoom: scale
            )
            centerY = Self.clampCenter(
                0.5 + (self.centerY - remoteAppliedCenterY) * remoteAppliedZoom, zoom: scale
            )
        case .remote:
            scale = 1
            centerX = 0.5
            centerY = 0.5
        }
        return CamHubFraming(zoom: scale, x: (0.5 - centerX) * scale,
                             y: (0.5 - centerY) * scale)
    }

    private static func clampZoom(_ value: Double) -> Double {
        value.isFinite ? min(32, max(1, value)) : 1
    }

    private static func clampCenter(_ value: Double, zoom: Double) -> Double {
        let halfVisible = 0.5 / max(1, zoom)
        return value.isFinite ? min(1 - halfVisible, max(halfVisible, value)) : 0.5
    }
}

public final class CamHubFrameSequenceTracker {
    public enum Result: Equatable { case unsequenced, first, inOrder, gap, late }
    private var previous: UInt64?
    public init() {}
    public func observe(_ sequence: UInt64) -> Result {
        guard sequence > 0 else { return previous == nil ? .unsequenced : .late }
        guard let last = previous else { previous = sequence; return .first }
        guard sequence > last else { return .late }
        previous = sequence
        return sequence - last == 1 ? .inOrder : .gap
    }
    public func reset() { previous = nil }
}

public final class CamHubLatencyWindow {
    private let capacity: Int
    private var values: [Int64] = []
    private var next = 0
    public var count: Int { values.count }
    public init(capacity: Int = 300) { precondition(capacity > 0); self.capacity = capacity }
    public func record(_ milliseconds: Int64) {
        guard milliseconds >= 0 else { return }
        if values.count < capacity { values.append(milliseconds) }
        else { values[next] = milliseconds }
        next = (next + 1) % capacity
    }
    public func clear() { values.removeAll(); next = 0 }
    public func percentiles() -> (p50: Int64, p95: Int64, p99: Int64)? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        func rank(_ p: Double) -> Int64 { sorted[max(1, Int(ceil(p * Double(sorted.count)))) - 1] }
        return (rank(0.50), rank(0.95), rank(0.99))
    }
}

public struct CamHubClockEstimate: Equatable {
    public let remoteOffsetMs: Int64
    public let roundTripMs: Int64

    public static func calculate(t0: UInt64, t1: UInt64, t2: UInt64, t3: UInt64,
                                 maxRoundTripMs: Int64 = 1_000) -> CamHubClockEstimate? {
        let limit = UInt64(Int64.max / 4)
        guard t0 > 0, t1 > 0, t2 >= t1, t3 >= t0,
              [t0, t1, t2, t3].allSatisfy({ $0 <= limit }), maxRoundTripMs > 0 else { return nil }
        let a = Int64(t0), b = Int64(t1), c = Int64(t2), d = Int64(t3)
        let roundTrip = (d - a) - (c - b)
        guard roundTrip >= 0, roundTrip <= maxRoundTripMs else { return nil }
        return CamHubClockEstimate(remoteOffsetMs: ((b - a) + (c - d)) / 2, roundTripMs: roundTrip)
    }
}

/// A single encoder is shared by subscribers. Honor the most restrictive explicit request.
public enum CamHubBitratePolicy {
    public static let defaultMbps = 4

    public static func validatedRequest(_ value: Float) -> Int? {
        guard value.isFinite else { return nil }
        return Int(min(max(value, 1), 20))
    }

    public static func selectedMbps(_ requests: [Int?]) -> Int {
        requests.compactMap { $0 }.map { min(max($0, 1), 20) }.min() ?? defaultMbps
    }
}

/// Keeps an H.264 recording decodable after its bounded writer queue drops a frame.
public struct CamHubKeyframeRecoveryGate {
    private var waitingForKeyFrame = true

    public init() {}

    public mutating func requireKeyFrame() {
        waitingForKeyFrame = true
    }

    public mutating func shouldAppend(isKeyFrame: Bool) -> Bool {
        guard !waitingForKeyFrame || isKeyFrame else { return false }
        if isKeyFrame { waitingForKeyFrame = false }
        return true
    }
}

/// H.264 Annex B units without their start codes. Used by VideoToolbox setup and decode.
public enum CamHubH264AnnexB {
    public struct AccessUnit: Equatable {
        public let sps: Data?
        public let pps: Data?
        public let sample: Data
        public let hasIDR: Bool
    }

    /// Extract parameter sets and convert remaining NAL units to four-byte AVCC lengths.
    public static func accessUnit(_ data: Data) -> AccessUnit? {
        guard let nalUnits = units(data) else { return nil }
        var sps: Data?
        var pps: Data?
        var sample = Data()
        var hasIDR = false
        for nal in nalUnits {
            guard let first = nal.first, nal.count <= Int(UInt32.max) else { return nil }
            switch first & 0x1f {
            case 7: sps = nal
            case 8: pps = nal
            default:
                if first & 0x1f == 5 { hasIDR = true }
                sample.appendUInt32(UInt32(nal.count))
                sample.append(nal)
            }
        }
        return AccessUnit(sps: sps, pps: pps, sample: sample, hasIDR: hasIDR)
    }

    public static func units(_ data: Data) -> [Data]? {
        let bytes = [UInt8](data)
        guard bytes.count >= 4 else { return nil }
        func startCode(at index: Int) -> Int {
            guard index + 2 < bytes.count, bytes[index] == 0, bytes[index + 1] == 0 else { return 0 }
            if bytes[index + 2] == 1 { return 3 }
            if index + 3 < bytes.count, bytes[index + 2] == 0, bytes[index + 3] == 1 { return 4 }
            return 0
        }
        guard startCode(at: 0) > 0 else { return nil }
        var units: [Data] = []
        var start = 0
        while start < bytes.count {
            let prefix = startCode(at: start)
            guard prefix > 0 else { return nil }
            let payloadStart = start + prefix
            var next = payloadStart
            while next < bytes.count && startCode(at: next) == 0 { next += 1 }
            guard next > payloadStart else { return nil }
            units.append(Data(bytes[payloadStart..<next]))
            start = next
        }
        return units.isEmpty ? nil : units
    }
}

/// Converts authenticated H.264 access units into Core Media samples on one serial queue.
public final class CamHubH264SampleBuilder {
    private var sps: Data?
    private var pps: Data?
    private var format: CMVideoFormatDescription?
    private var waitingForIDR = true
    public var needsKeyFrame: Bool { waitingForIDR }

    public init() {}

    public func reset() {
        sps = nil
        pps = nil
        format = nil
        waitingForIDR = true
    }

    public func requireKeyFrame() { waitingForIDR = true }

    public func makeSample(_ annexB: Data, presentationTime: CMTime) -> CMSampleBuffer? {
        guard let access = CamHubH264AnnexB.accessUnit(annexB) else {
            waitingForIDR = true
            return nil
        }
        let newSps = access.sps ?? sps
        let newPps = access.pps ?? pps
        if newSps != sps || newPps != pps {
            sps = newSps
            pps = newPps
            format = nil
            waitingForIDR = true
        }
        if format == nil, let sps, let pps {
            format = sps.withUnsafeBytes { spsBytes in
                pps.withUnsafeBytes { ppsBytes in
                    guard let spsBase = spsBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                          let ppsBase = ppsBytes.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return nil }
                    let pointers = [spsBase, ppsBase]
                    let sizes = [sps.count, pps.count]
                    var description: CMFormatDescription?
                    let status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                        allocator: kCFAllocatorDefault, parameterSetCount: 2,
                        parameterSetPointers: pointers, parameterSetSizes: sizes,
                        nalUnitHeaderLength: 4, formatDescriptionOut: &description
                    )
                    return status == noErr ? description : nil
                }
            }
        }
        guard let format, !access.sample.isEmpty, !waitingForIDR || access.hasIDR else { return nil }
        var block: CMBlockBuffer?
        let count = access.sample.count
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil,
                                                blockLength: count, blockAllocator: kCFAllocatorDefault,
                                                customBlockSource: nil, offsetToData: 0, dataLength: count,
                                                flags: 0, blockBufferOut: &block) == noErr,
              let block else { waitingForIDR = true; return nil }
        let copied = access.sample.withUnsafeBytes { bytes in
            CMBlockBufferReplaceDataBytes(with: bytes.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: count)
        }
        guard copied == noErr else { waitingForIDR = true; return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: presentationTime, decodeTimeStamp: .invalid)
        var sampleSize = count
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block,
                                        formatDescription: format, sampleCount: 1,
                                        sampleTimingEntryCount: 1, sampleTimingArray: &timing,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &sampleSize,
                                        sampleBufferOut: &sample) == noErr else { waitingForIDR = true; return nil }
        guard let sample,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
              CFArrayGetCount(attachments) > 0 else { waitingForIDR = true; return nil }
        let entry = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
        CFDictionarySetValue(entry, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                             Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        if !access.hasIDR {
            CFDictionarySetValue(entry, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        if access.hasIDR { waitingForIDR = false }
        return sample
    }
}

public struct CamHubVideoPayload: Equatable {
    public let width: Int
    public let height: Int
    public let rotationDegrees: Int
    public let isKeyFrame: Bool
    public let isCodecConfig: Bool
    public let frameSequence: UInt64
    public let captureAtWallMs: UInt64?
    public let encodedAtWallMs: UInt64?
    public let h264Bytes: Data

    public init(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        isKeyFrame: Bool,
        isCodecConfig: Bool,
        frameSequence: UInt64,
        captureAtWallMs: UInt64?,
        encodedAtWallMs: UInt64?,
        h264Bytes: Data
    ) {
        self.width = width
        self.height = height
        self.rotationDegrees = rotationDegrees
        self.isKeyFrame = isKeyFrame
        self.isCodecConfig = isCodecConfig
        self.frameSequence = frameSequence
        self.captureAtWallMs = captureAtWallMs
        self.encodedAtWallMs = encodedAtWallMs
        self.h264Bytes = h264Bytes
    }
}

/// Byte-compatible with protocol/CamHubVideoPayload.kt (v2-v4, encoding v4).
public enum CamHubVideoPayloadCodec {
    public static let version4HeaderBytes = 31
    private static let keyFrameFlag: UInt8 = 0x01
    private static let codecConfigFlag: UInt8 = 0x02

    public static func encode(_ payload: CamHubVideoPayload) throws -> Data {
        guard (1...16_384).contains(payload.width), (1...16_384).contains(payload.height),
              [0, 90, 180, 270].contains(payload.rotationDegrees),
              !payload.h264Bytes.isEmpty else {
            throw CamHubProtocolError.invalidPayload
        }
        var result = Data([4])
        result.appendUInt16(UInt16(payload.width))
        result.appendUInt16(UInt16(payload.height))
        result.append(UInt8(payload.rotationDegrees / 90))
        var flags: UInt8 = 0
        if payload.isKeyFrame { flags |= keyFrameFlag }
        if payload.isCodecConfig { flags |= codecConfigFlag }
        result.append(flags)
        result.appendUInt64(payload.frameSequence)
        result.appendUInt64(payload.captureAtWallMs ?? 0)
        result.appendUInt64(payload.encodedAtWallMs ?? 0)
        result.append(payload.h264Bytes)
        return result
    }

    public static func decode(_ data: Data) -> CamHubVideoPayload? {
        guard let version = data.first else { return nil }
        let headerSize: Int
        switch version {
        case 2: headerSize = 7
        case 3: headerSize = 15
        case 4: headerSize = version4HeaderBytes
        default: return nil
        }
        guard data.count > headerSize,
              let width = data.readUInt16(at: 1),
              let height = data.readUInt16(at: 3),
              let rotationCode = data.byte(at: 5),
              let flags = data.byte(at: 6),
              (1...16_384).contains(Int(width)), (1...16_384).contains(Int(height)),
              rotationCode <= 3 else { return nil }

        let sequence = version >= 4 ? data.readUInt64(at: 7) ?? 0 : 0
        let captureAt = version >= 4 ? data.readUInt64(at: 15) : nil
        let encodedAt: UInt64?
        if version == 3 { encodedAt = data.readUInt64(at: 7) }
        else if version == 4 { encodedAt = data.readUInt64(at: 23) }
        else { encodedAt = nil }
        return CamHubVideoPayload(
            width: Int(width),
            height: Int(height),
            rotationDegrees: Int(rotationCode) * 90,
            isKeyFrame: flags & 0x01 != 0,
            isCodecConfig: flags & 0x02 != 0,
            frameSequence: sequence,
            captureAtWallMs: captureAt,
            encodedAtWallMs: encodedAt,
            h264Bytes: data.subdata(in: headerSize..<data.count)
        )
    }
}

public struct CamHubRtpFragment: Equatable {
    public let sequence: UInt16
    public let timestamp: UInt32
    public let ssrc: UInt32
    public let frameID: UInt64
    public let fragmentIndex: UInt16
    public let fragmentCount: UInt16
    public let totalFrameBytes: UInt32
    public let isKeyFrame: Bool
    public let isCodecConfig: Bool
    public let payload: Data
}

/// Matches the CamHub RTP-style 32-byte header and 1,200-byte datagram cap.
public enum CamHubRtpPacketCodec {
    public static let maxDatagramBytes = 1_200
    public static let headerBytes = 32
    public static let maxFragmentPayloadBytes = maxDatagramBytes - headerBytes
    private static let payloadType: UInt8 = 96
    private static let magic: [UInt8] = [0x43, 0x48, 0x01]

    public static func packetize(
        _ frame: Data,
        frameID: UInt64,
        timestamp: UInt32,
        ssrc: UInt32,
        firstSequence: UInt16,
        isKeyFrame: Bool = false,
        isCodecConfig: Bool = false
    ) throws -> [Data] {
        guard !frame.isEmpty, frame.count <= Int(UInt32.max) else {
            throw CamHubProtocolError.invalidPayload
        }
        let count = (frame.count + maxFragmentPayloadBytes - 1) / maxFragmentPayloadBytes
        guard count <= Int(UInt16.max) else { throw CamHubProtocolError.frameTooLarge }
        var flags: UInt8 = 0
        if isKeyFrame { flags |= 0x01 }
        if isCodecConfig { flags |= 0x02 }

        return (0..<count).map { index in
            let start = index * maxFragmentPayloadBytes
            let end = min(frame.count, start + maxFragmentPayloadBytes)
            let marker: UInt8 = index == count - 1 ? 0x80 : 0
            var packet = Data()
            packet.append(0x80)
            packet.append(payloadType | marker)
            packet.appendUInt16(firstSequence &+ UInt16(index))
            packet.appendUInt32(timestamp)
            packet.appendUInt32(ssrc)
            packet.append(contentsOf: magic)
            packet.append(flags)
            packet.appendUInt64(frameID)
            packet.appendUInt16(UInt16(index))
            packet.appendUInt16(UInt16(count))
            packet.appendUInt32(UInt32(frame.count))
            packet.append(frame.subdata(in: start..<end))
            return packet
        }
    }

    public static func decode(_ datagram: Data) -> CamHubRtpFragment? {
        guard (headerBytes + 1...maxDatagramBytes).contains(datagram.count),
              datagram.byte(at: 0) == 0x80,
              let second = datagram.byte(at: 1), second & 0x7f == payloadType,
              datagram.byte(at: 12) == magic[0], datagram.byte(at: 13) == magic[1],
              datagram.byte(at: 14) == magic[2],
              let sequence = datagram.readUInt16(at: 2),
              let timestamp = datagram.readUInt32(at: 4),
              let ssrc = datagram.readUInt32(at: 8),
              let flags = datagram.byte(at: 15),
              let frameID = datagram.readUInt64(at: 16),
              let index = datagram.readUInt16(at: 24),
              let count = datagram.readUInt16(at: 26),
              let total = datagram.readUInt32(at: 28),
              count > 0, index < count, total > 0 else { return nil }
        let payloadSize = datagram.count - headerBytes
        let expectedCount = (Int(total) + maxFragmentPayloadBytes - 1) / maxFragmentPayloadBytes
        guard Int(count) == expectedCount,
              payloadSize == (index == count - 1
                ? Int(total) - Int(index) * maxFragmentPayloadBytes
                : maxFragmentPayloadBytes),
              (second & 0x80 != 0) == (index == count - 1) else { return nil }
        return CamHubRtpFragment(
            sequence: sequence,
            timestamp: timestamp,
            ssrc: ssrc,
            frameID: frameID,
            fragmentIndex: index,
            fragmentCount: count,
            totalFrameBytes: total,
            isKeyFrame: flags & 0x01 != 0,
            isCodecConfig: flags & 0x02 != 0,
            payload: datagram.subdata(in: headerBytes..<datagram.count)
        )
    }
}

/// Bounded, deadline-based assembly for one camera. Call from a single receive queue.
public final class CamHubFrameReassembler {
    public static let defaultMaxPendingFrames = 4
    public enum Result: Equatable {
        case accepted, duplicate, dropped
        case complete(Data)
    }
    private struct Pending {
        let first: CamHubRtpFragment
        let startedAtMs: UInt64
        var parts: [UInt16: Data] = [:]
    }
    private let deadlineMs: UInt64
    private let maxFrames: Int
    private let maxFrameBytes: Int
    private var pending: [UInt64: Pending] = [:]
    private var order: [UInt64] = []
    private var terminal: [UInt64] = []
    public private(set) var droppedFrameCount: UInt64 = 0
    private var capacityDroppedFrameCount: UInt64 = 0
    public var pendingFrameCount: Int { pending.count }

    public init(deadlineMs: UInt64 = 35, maxFrames: Int = CamHubFrameReassembler.defaultMaxPendingFrames, maxFrameBytes: Int = 2 * 1024 * 1024) {
        precondition(deadlineMs > 0 && maxFrames > 0 && maxFrameBytes > 0)
        self.deadlineMs = deadlineMs
        self.maxFrames = maxFrames
        self.maxFrameBytes = maxFrameBytes
    }

    public func expire(nowMs: UInt64) {
        let expired = order.filter { id in
            guard let frame = pending[id], nowMs >= frame.startedAtMs else { return false }
            return nowMs - frame.startedAtMs >= deadlineMs
        }
        expired.forEach(discard)
    }

    public func offer(_ datagram: Data, nowMs: UInt64) -> Result {
        expire(nowMs: nowMs)
        guard let part = CamHubRtpPacketCodec.decode(datagram),
              Int(part.totalFrameBytes) <= maxFrameBytes,
              !terminal.contains(part.frameID) else { return .dropped }
        if pending[part.frameID] == nil {
            if order.count >= maxFrames, let oldest = order.first {
                if pending[oldest] != nil { capacityDroppedFrameCount &+= 1 }
                discard(oldest)
            }
            pending[part.frameID] = Pending(first: part, startedAtMs: nowMs)
            order.append(part.frameID)
        }
        guard var frame = pending[part.frameID] else { return .dropped }
        let first = frame.first
        guard first.ssrc == part.ssrc, first.timestamp == part.timestamp,
              first.fragmentCount == part.fragmentCount, first.totalFrameBytes == part.totalFrameBytes,
              first.isKeyFrame == part.isKeyFrame, first.isCodecConfig == part.isCodecConfig else {
            discard(part.frameID)
            return .dropped
        }
        if frame.parts[part.fragmentIndex] != nil { return .duplicate }
        frame.parts[part.fragmentIndex] = part.payload
        if frame.parts.count == Int(part.fragmentCount) {
            var bytes = Data()
            bytes.reserveCapacity(Int(part.totalFrameBytes))
            for index in 0..<Int(part.fragmentCount) {
                guard let bytesPart = frame.parts[UInt16(index)] else { return .dropped }
                bytes.append(bytesPart)
            }
            finish(part.frameID)
            return .complete(bytes)
        }
        pending[part.frameID] = frame
        return .accepted
    }

    private func finish(_ id: UInt64) {
        pending.removeValue(forKey: id)
        order.removeAll { $0 == id }
        if !terminal.contains(id) { terminal.append(id) }
        if terminal.count > 64 { terminal.removeFirst() }
    }

    private func discard(_ id: UInt64) {
        if pending[id] != nil { droppedFrameCount &+= 1 }
        finish(id)
    }

    /// Returns and clears incomplete frames evicted to enforce `maxFrames`.
    public func takeCapacityDropCount() -> UInt64 {
        let result = capacityDroppedFrameCount
        capacityDroppedFrameCount = 0
        return result
    }
}

/// Compatible with the existing [12-byte IV][AES-GCM ciphertext + 16-byte tag] envelope.
public final class CamHubFrameCipher {
    private let key: SymmetricKey
    private let prefix: Data
    private let lock = NSLock()
    private var counter: UInt64 = 0

    public init(key: Data) throws {
        guard key.count == 32 else { throw CamHubProtocolError.invalidKey }
        self.key = SymmetricKey(data: key)
        self.prefix = Data((0..<4).map { _ in UInt8.random(in: .min ... .max) })
    }

    public func encrypt(_ plaintext: Data) throws -> Data {
        lock.lock()
        let current = counter
        counter &+= 1
        lock.unlock()

        var nonceBytes = prefix
        nonceBytes.appendUInt64(current)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        return Data(nonceBytes) + sealed.ciphertext + sealed.tag
    }

    public func decrypt(_ envelope: Data) throws -> Data {
        guard envelope.count >= 28 else { throw CamHubProtocolError.invalidCiphertext }
        let nonceBytes = envelope.prefix(12)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let ciphertext = envelope.subdata(in: 12..<(envelope.count - 16))
        let tag = envelope.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(box, using: key)
    }
}

/// One instance per authenticated camera; calls must be serialized on its receive queue.
public final class CamHubVideoIngress {
    public enum Result: Equatable {
        case pending, duplicate, dropped
        case frame(CamHubVideoPayload)
    }
    private let assembler = CamHubFrameReassembler()
    private let cipher: CamHubFrameCipher
    private var corruptFrames: UInt64 = 0
    private var recentNonces: [Data] = []
    private var recentNonceSet: Set<Data> = []
    public var droppedFrameCount: UInt64 { assembler.droppedFrameCount &+ corruptFrames }
    public var pendingFrameCount: Int { assembler.pendingFrameCount }

    /// Returns and clears incomplete frames evicted by the assembler capacity limit.
    public func takeCapacityDropCount() -> UInt64 {
        assembler.takeCapacityDropCount()
    }

    public init(sessionKey: Data) throws {
        cipher = try CamHubFrameCipher(key: sessionKey)
    }

    @discardableResult
    public func expire(nowMs: UInt64) -> UInt64 {
        let previous = droppedFrameCount
        assembler.expire(nowMs: nowMs)
        return droppedFrameCount &- previous
    }

    public func offer(_ datagram: Data, nowMs: UInt64) -> Result {
        switch assembler.offer(datagram, nowMs: nowMs) {
        case .accepted: return .pending
        case .duplicate: return .duplicate
        case .dropped: return .dropped
        case let .complete(envelope):
            let nonce = Data(envelope.prefix(12))
            guard !recentNonceSet.contains(nonce) else { return .dropped }
            guard let plaintext = try? cipher.decrypt(envelope),
                  let payload = CamHubVideoPayloadCodec.decode(plaintext) else {
                corruptFrames &+= 1
                return .dropped
            }
            recentNonces.append(nonce)
            recentNonceSet.insert(nonce)
            if recentNonces.count > 256 { recentNonceSet.remove(recentNonces.removeFirst()) }
            return .frame(payload)
        }
    }
}

public enum CamHubProtocolError: Error {
    case invalidPayload
    case frameTooLarge
    case invalidKey
    case invalidCiphertext
}

/// JSON control-channel contract shared with the Android and desktop Kotlin peers.
public struct CamHubControlMessage: Codable, Equatable {
    public var type: String
    public var deviceName: String
    public var streamPort: Int
    public var tcpStreamPort: Int
    public var pin: String
    public var sessionKey: String
    public var tcpAuthToken: String
    public var command: String
    public var value: Float
    public var stringValue: String
    public var audioStreamPort: Int
    public var udpStreamPort: Int
    public var udpReceivePort: Int
    public var syncId: UInt64
    public var t0WallMs: UInt64
    public var t1WallMs: UInt64
    public var t2WallMs: UInt64
    public var supportsRemotePtz: Bool
    public var minZoomRatio: Float
    public var maxZoomRatio: Float
    public var supportsAuthenticatedTcp: Bool
    public var requestId: UInt64

    public init(
        type: String,
        deviceName: String = "",
        streamPort: Int = 0,
        tcpStreamPort: Int = 0,
        pin: String = "",
        sessionKey: String = "",
        tcpAuthToken: String = "",
        command: String = "",
        value: Float = 0,
        stringValue: String = "",
        audioStreamPort: Int = 0,
        udpStreamPort: Int = 0,
        udpReceivePort: Int = 0,
        syncId: UInt64 = 0,
        t0WallMs: UInt64 = 0,
        t1WallMs: UInt64 = 0,
        t2WallMs: UInt64 = 0,
        supportsRemotePtz: Bool = false,
        minZoomRatio: Float = 1,
        maxZoomRatio: Float = 1,
        supportsAuthenticatedTcp: Bool = false,
        requestId: UInt64 = 0
    ) {
        self.type = type
        self.deviceName = deviceName
        self.streamPort = streamPort
        self.tcpStreamPort = tcpStreamPort
        self.pin = pin
        self.sessionKey = sessionKey
        self.tcpAuthToken = tcpAuthToken
        self.command = command
        self.value = value
        self.stringValue = stringValue
        self.audioStreamPort = audioStreamPort
        self.udpStreamPort = udpStreamPort
        self.udpReceivePort = udpReceivePort
        self.syncId = syncId
        self.t0WallMs = t0WallMs
        self.t1WallMs = t1WallMs
        self.t2WallMs = t2WallMs
        self.supportsRemotePtz = supportsRemotePtz
        self.minZoomRatio = minZoomRatio
        self.maxZoomRatio = maxZoomRatio
        self.supportsAuthenticatedTcp = supportsAuthenticatedTcp
        self.requestId = requestId
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        type = try values.decode(String.self, forKey: .type)
        deviceName = try values.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        streamPort = try values.decodeIfPresent(Int.self, forKey: .streamPort) ?? 0
        tcpStreamPort = try values.decodeIfPresent(Int.self, forKey: .tcpStreamPort) ?? 0
        pin = try values.decodeIfPresent(String.self, forKey: .pin) ?? ""
        sessionKey = try values.decodeIfPresent(String.self, forKey: .sessionKey) ?? ""
        tcpAuthToken = try values.decodeIfPresent(String.self, forKey: .tcpAuthToken) ?? ""
        command = try values.decodeIfPresent(String.self, forKey: .command) ?? ""
        value = try values.decodeIfPresent(Float.self, forKey: .value) ?? 0
        stringValue = try values.decodeIfPresent(String.self, forKey: .stringValue) ?? ""
        audioStreamPort = try values.decodeIfPresent(Int.self, forKey: .audioStreamPort) ?? 0
        udpStreamPort = try values.decodeIfPresent(Int.self, forKey: .udpStreamPort) ?? 0
        udpReceivePort = try values.decodeIfPresent(Int.self, forKey: .udpReceivePort) ?? 0
        syncId = try values.decodeIfPresent(UInt64.self, forKey: .syncId) ?? 0
        t0WallMs = try values.decodeIfPresent(UInt64.self, forKey: .t0WallMs) ?? 0
        t1WallMs = try values.decodeIfPresent(UInt64.self, forKey: .t1WallMs) ?? 0
        t2WallMs = try values.decodeIfPresent(UInt64.self, forKey: .t2WallMs) ?? 0
        supportsRemotePtz = try values.decodeIfPresent(Bool.self, forKey: .supportsRemotePtz) ?? false
        minZoomRatio = try values.decodeIfPresent(Float.self, forKey: .minZoomRatio) ?? 1
        maxZoomRatio = try values.decodeIfPresent(Float.self, forKey: .maxZoomRatio) ?? 1
        supportsAuthenticatedTcp = try values.decodeIfPresent(Bool.self, forKey: .supportsAuthenticatedTcp) ?? false
        requestId = try values.decodeIfPresent(UInt64.self, forKey: .requestId) ?? 0
    }
}

private extension Data {
    mutating func appendUInt16(_ value: UInt16) {
        append(UInt8((value >> 8) & 0xff)); append(UInt8(value & 0xff))
    }
    mutating func appendUInt32(_ value: UInt32) {
        for shift in stride(from: 24, through: 0, by: -8) { append(UInt8((value >> shift) & 0xff)) }
    }
    mutating func appendUInt64(_ value: UInt64) {
        for shift in stride(from: 56, through: 0, by: -8) { append(UInt8((value >> shift) & 0xff)) }
    }
    func byte(at index: Int) -> UInt8? { indices.contains(index) ? self[index] : nil }
    func readUInt16(at offset: Int) -> UInt16? {
        guard offset >= 0, count >= offset + 2 else { return nil }
        return (UInt16(self[offset]) << 8) | UInt16(self[offset + 1])
    }
    func readUInt32(at offset: Int) -> UInt32? {
        guard offset >= 0, count >= offset + 4 else { return nil }
        return (UInt32(self[offset]) << 24) | (UInt32(self[offset + 1]) << 16) |
            (UInt32(self[offset + 2]) << 8) | UInt32(self[offset + 3])
    }
    func readUInt64(at offset: Int) -> UInt64? {
        guard offset >= 0, count >= offset + 8 else { return nil }
        return (0..<8).reduce(UInt64(0)) { ($0 << 8) | UInt64(self[offset + $1]) }
    }
}

/// TCP stream frames use a four-byte big-endian length followed by encrypted payload bytes.
public enum CamHubTcpFrameCodec {
    public enum DecodeError: Error, Equatable {
        case invalidLength
    }

    public static func encode(_ payload: Data, maximumBytes: Int = 2 * 1024 * 1024) -> Data? {
        guard maximumBytes > 0, !payload.isEmpty, payload.count <= maximumBytes,
              payload.count <= Int(UInt32.max) else { return nil }
        var frame = Data()
        frame.reserveCapacity(4 + payload.count)
        frame.appendUInt32(UInt32(payload.count))
        frame.append(payload)
        return frame
    }

    /// Extracts one complete frame from a TCP receive buffer. Returns nil when the buffer only
    /// contains a partial header or payload. Multiple concatenated frames remain available for
    /// subsequent calls; malformed lengths clear the buffer before throwing.
    public static func decodeNext(from buffer: inout Data,
                                  maximumBytes: Int = 2 * 1024 * 1024) throws -> Data? {
        guard maximumBytes > 0 else { throw DecodeError.invalidLength }
        guard buffer.count >= 4 else { return nil }
        let length = (UInt32(buffer[buffer.startIndex]) << 24) |
            (UInt32(buffer[buffer.startIndex + 1]) << 16) |
            (UInt32(buffer[buffer.startIndex + 2]) << 8) |
            UInt32(buffer[buffer.startIndex + 3])
        guard length > 0, UInt64(length) <= UInt64(maximumBytes) else {
            buffer.removeAll(keepingCapacity: false)
            throw DecodeError.invalidLength
        }
        let frameEnd = 4 + Int(length)
        guard buffer.count >= frameEnd else { return nil }
        let payload = buffer.subdata(in: 4..<frameEnd)
        buffer.removeSubrange(..<frameEnd)
        return payload
    }
}
