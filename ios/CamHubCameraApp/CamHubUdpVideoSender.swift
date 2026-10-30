import Foundation
import CamHubCore
import Network
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// Deadline-friendly camera-side UDP sender. Frame encryption, packetization and all socket writes
/// happen on one serial worker; pending video is coalesced to the newest frames instead of building
/// an unbounded queue when the network is slower than the encoder.
final class CamHubUdpVideoSender {
    private struct Destination {
        let address: sockaddr_in6
        let cipher: CamHubFrameCipher
    }

    private let queue = DispatchQueue(label: "com.camhub.camera.udp-send", qos: .userInteractive)
    private let pendingLock = NSLock()
    private var socketFD: Int32 = -1
    private var port: UInt16 = 0
    private var destinations: [UUID: Destination] = [:]
    private var cachedCodecConfig: CamHubEncodedAccessUnit?
    private var pendingAccessUnit: CamHubEncodedAccessUnit?
    private var pendingKeyFrame: CamHubEncodedAccessUnit?
    private var sendWorkerScheduled = false
    private var waitingForKeyFrame = false
    private var frameID: UInt64 = 0
    private var videoSequence: UInt64 = 0
    private var lastLocalFailureKeyFrameRequestNanos: UInt64 = 0
    private var rtpSequence = UInt16.random(in: .min ... .max)
    private let ssrc = UInt32.random(in: .min ... .max)
    private let datagramWriter: (Int32, Data, sockaddr_in6) -> Bool
    var onKeyFrameRequired: (() -> Void)?

    init(datagramWriter: @escaping (Int32, Data, sockaddr_in6) -> Bool = CamHubUdpVideoSender.writeDatagram) {
        self.datagramWriter = datagramWriter
    }

    func start() throws -> UInt16 {
        return try queue.sync { () throws -> UInt16 in
            if socketFD >= 0 { return port }
            let fd = socket(AF_INET6, SOCK_DGRAM, 0)
            guard fd >= 0 else { throw SenderError.socket(errno) }
            // Never let a full kernel send buffer stall the real-time sender queue.
            // A failed datagram triggers the existing keyframe recovery path.
            let flags = fcntl(fd, F_GETFL, 0)
            guard flags >= 0, fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0 else {
                let error = errno
                close(fd)
                throw SenderError.socket(error)
            }
            var ipv6Only: Int32 = 0
            guard setsockopt(
                fd,
                IPPROTO_IPV6,
                IPV6_V6ONLY,
                &ipv6Only,
                socklen_t(MemoryLayout<Int32>.size)
            ) == 0 else {
                let error = errno
                close(fd)
                throw SenderError.socket(error)
            }
            var localAddress = sockaddr_in6()
            localAddress.sin6_family = sa_family_t(AF_INET6)
            localAddress.sin6_port = 0
            localAddress.sin6_addr = in6_addr()
            #if canImport(Darwin)
            localAddress.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
            #endif
            let bindStatus = withUnsafePointer(to: &localAddress) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
                }
            }
            guard bindStatus == 0 else {
                let error = errno
                close(fd)
                throw SenderError.socket(error)
            }
            var boundAddress = sockaddr_in6()
            var addressLength = socklen_t(MemoryLayout<sockaddr_in6>.size)
            let nameStatus = withUnsafeMutablePointer(to: &boundAddress) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    getsockname(fd, $0, &addressLength)
                }
            }
            guard nameStatus == 0 else {
                let error = errno
                close(fd)
                throw SenderError.socket(error)
            }
            socketFD = fd
            port = UInt16(bigEndian: boundAddress.sin6_port)
            return port
        }
    }

    func subscribe(clientID: UUID, host: NWEndpoint.Host, port: UInt16, sessionKey: Data) {
        queue.async {
            guard self.socketFD >= 0, port > 0 else { return }
            guard let address = Self.socketAddress(host: host, port: port),
                  let cipher = try? CamHubFrameCipher(key: sessionKey) else { return }
            let destination = Destination(address: address, cipher: cipher)
            self.destinations[clientID] = destination
            self.pendingLock.lock()
            let config = self.cachedCodecConfig
            self.pendingLock.unlock()
            if let config { self.transmit(config, to: [destination]) }
        }
    }

    func resendCodecConfig(clientID: UUID) {
        queue.async {
            guard let destination = self.destinations[clientID] else { return }
            self.pendingLock.lock()
            let config = self.cachedCodecConfig
            self.pendingLock.unlock()
            if let config { self.transmit(config, to: [destination]) }
        }
    }

    func unsubscribe(clientID: UUID) {
        queue.async { self.destinations.removeValue(forKey: clientID) }
    }

    func publish(_ accessUnit: CamHubEncodedAccessUnit) {
        if accessUnit.isCodecConfig {
            pendingLock.lock()
            cachedCodecConfig = accessUnit
            pendingLock.unlock()
            queue.async { self.transmit(accessUnit, to: Array(self.destinations.values)) }
            return
        }

        var requestKeyFrame = false
        pendingLock.lock()
        if accessUnit.isKeyFrame {
            pendingKeyFrame = accessUnit
            waitingForKeyFrame = false
            if let pendingAccessUnit,
               pendingAccessUnit.captureAtWallMs <= accessUnit.captureAtWallMs {
                self.pendingAccessUnit = nil
            }
        } else if waitingForKeyFrame {
            pendingLock.unlock()
            return
        } else {
            if pendingAccessUnit != nil {
                // Later P-frames may depend on the replaced frame. Wait for a fresh IDR so the
                // receiver never decodes a broken prediction chain after queue pressure.
                pendingAccessUnit = nil
                waitingForKeyFrame = true
                requestKeyFrame = true
            } else {
                pendingAccessUnit = accessUnit
            }
        }
        let shouldSchedule = !sendWorkerScheduled
        if shouldSchedule { sendWorkerScheduled = true }
        pendingLock.unlock()

        if requestKeyFrame { onKeyFrameRequired?() }
        if shouldSchedule { queue.async { self.drainPendingAccessUnits() } }
    }

    func stop() {
        queue.sync {
            destinations.removeAll()
            if socketFD >= 0 { close(socketFD) }
            socketFD = -1
            port = 0
            frameID = 0
            videoSequence = 0
            lastLocalFailureKeyFrameRequestNanos = 0
        }
        pendingLock.lock()
        cachedCodecConfig = nil
        pendingAccessUnit = nil
        pendingKeyFrame = nil
        sendWorkerScheduled = false
        waitingForKeyFrame = false
        pendingLock.unlock()
    }

    @discardableResult
    private func transmit(_ accessUnit: CamHubEncodedAccessUnit, to destinations: [Destination]) -> Bool {
        guard socketFD >= 0, !destinations.isEmpty else { return false }
        let currentFrameID = frameID
        frameID &+= 1
        if !accessUnit.isCodecConfig { videoSequence &+= 1 }
        let currentVideoSequence = accessUnit.isCodecConfig ? 0 : videoSequence
        var packetCount = 0
        defer { rtpSequence &+= UInt16(packetCount) }
        do {
            let payload = CamHubVideoPayload(
                width: 1_280,
                height: 720,
                rotationDegrees: accessUnit.rotationDegrees,
                isKeyFrame: accessUnit.isKeyFrame,
                isCodecConfig: accessUnit.isCodecConfig,
                frameSequence: currentVideoSequence,
                captureAtWallMs: accessUnit.captureAtWallMs,
                encodedAtWallMs: accessUnit.encodedAtWallMs,
                h264Bytes: accessUnit.bytes
            )
            let encoded = try CamHubVideoPayloadCodec.encode(payload)
            let timestamp = UInt32(truncatingIfNeeded: DispatchTime.now().uptimeNanoseconds / 1_000_000 * 90)
            var sendFailed = false
            for destination in destinations {
                let encrypted = try destination.cipher.encrypt(encoded)
                let packets = try CamHubRtpPacketCodec.packetize(
                    encrypted,
                    frameID: currentFrameID,
                    timestamp: timestamp,
                    ssrc: ssrc,
                    firstSequence: rtpSequence,
                    isKeyFrame: accessUnit.isKeyFrame,
                    isCodecConfig: accessUnit.isCodecConfig
                )
                packetCount = packets.count
                for packet in packets {
                    if !send(packet, to: destination.address) {
                        sendFailed = true
                        break
                    }
                }
            }
            if sendFailed {
                // A local write failure affects only the destination whose datagram was
                // rejected. Keep other hubs' prediction chains flowing and ask the shared
                // encoder for an IDR so the affected hub can recover independently.
                requestKeyFrameAfterLocalFailure()
                return false
            }
            return true
        } catch {
            requestKeyFrameAfterLocalFailure()
            return false
        }
    }

    /// Keep at most the newest ordinary frame and newest key frame while the network worker is busy.
    /// This bounds memory and prevents a slow Wi-Fi path from turning encoder output into stale video.
    private func drainPendingAccessUnits() {
        while true {
            pendingLock.lock()
            let next: CamHubEncodedAccessUnit
            if let keyFrame = pendingKeyFrame {
                next = keyFrame
                pendingKeyFrame = nil
            } else if let accessUnit = pendingAccessUnit {
                next = accessUnit
                pendingAccessUnit = nil
            } else {
                sendWorkerScheduled = false
                pendingLock.unlock()
                return
            }
            pendingLock.unlock()
            let targets = Array(destinations.values)
            transmit(next, to: targets)
        }
    }

    private static func socketAddress(host: NWEndpoint.Host, port: UInt16) -> sockaddr_in6? {
        var address = sockaddr_in6()
        address.sin6_family = sa_family_t(AF_INET6)
        address.sin6_port = port.bigEndian
        address.sin6_flowinfo = 0
        #if canImport(Darwin)
        address.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        #endif

        let addressBytes: Data
        switch host {
        case let .ipv4(ipv4):
            guard ipv4.rawValue.count == 4 else { return nil }
            addressBytes = Data(repeating: 0, count: 10) + Data([0xff, 0xff]) + ipv4.rawValue
        case let .ipv6(ipv6):
            guard ipv6.rawValue.count == 16 else { return nil }
            addressBytes = ipv6.rawValue
            address.sin6_scope_id = UInt32(max(0, ipv6.interface?.index ?? 0))
        case .name:
            return nil
        @unknown default:
            return nil
        }

        addressBytes.withUnsafeBytes { source in
            withUnsafeMutableBytes(of: &address.sin6_addr) { destination in
                guard let sourceAddress = source.baseAddress,
                      let destinationAddress = destination.baseAddress else { return }
                destinationAddress.copyMemory(from: sourceAddress, byteCount: 16)
            }
        }
        return address
    }

    private func requestKeyFrameAfterLocalFailure() {
        let now = DispatchTime.now().uptimeNanoseconds
        let minimumInterval: UInt64 = 250_000_000
        guard now &- lastLocalFailureKeyFrameRequestNanos >= minimumInterval else { return }
        lastLocalFailureKeyFrameRequestNanos = now
        onKeyFrameRequired?()
    }

    private func send(_ packet: Data, to address: sockaddr_in6) -> Bool {
        guard socketFD >= 0 else { return false }
        return datagramWriter(socketFD, packet, address)
    }

    static func writeDatagram(_ fd: Int32, _ packet: Data, _ address: sockaddr_in6) -> Bool {
        var remoteAddress = address
        return packet.withUnsafeBytes { bytes in
            guard let base = bytes.baseAddress else { return false }
            return withUnsafePointer(to: &remoteAddress) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(
                        fd,
                        base,
                        packet.count,
                        0,
                        $0,
                        socklen_t(MemoryLayout<sockaddr_in6>.size)
                    ) == packet.count
                }
            }
        }
    }

    private enum SenderError: LocalizedError {
        case socket(Int32)
        var errorDescription: String? {
            switch self {
            case let .socket(code): return "UDP socket setup failed (\(code))."
            }
        }
    }
}
