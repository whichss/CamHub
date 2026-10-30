import CamHubCore
import Darwin
import Foundation
import Network

struct CamHubEncodedAccessUnit {
    let bytes: Data
    let isKeyFrame: Bool
    let isCodecConfig: Bool
    let captureAtWallMs: UInt64
    let encodedAtWallMs: UInt64
    let rotationDegrees: Int
}

@main
struct UdpVideoSenderSmoke {
    private final class DatagramFailurePlan {
        private let lock = NSLock()
        private var targetPort: UInt16?
        private var remainingFailures = 0
        private var failureSignal: DispatchSemaphore?

        func failNextDatagram(to port: UInt16) -> DispatchSemaphore {
            lock.lock()
            defer { lock.unlock() }
            let signal = DispatchSemaphore(value: 0)
            targetPort = port
            remainingFailures = 1
            failureSignal = signal
            return signal
        }

        func shouldFail(_ address: sockaddr_in6) -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard remainingFailures > 0,
                  targetPort == UInt16(bigEndian: address.sin6_port) else { return false }
            remainingFailures -= 1
            failureSignal?.signal()
            failureSignal = nil
            return true
        }
    }

    private struct Hub {
        let socket: Int32
        let port: UInt16
        let host: NWEndpoint.Host
        let key: Data
        let ingress: CamHubVideoIngress
    }

    static func main() throws {
        let failurePlan = DatagramFailurePlan()
        let sender = CamHubUdpVideoSender { fd, packet, address in
            if failurePlan.shouldFail(address) { return false }
            return CamHubUdpVideoSender.writeDatagram(fd, packet, address)
        }
        let senderPort = try sender.start()
        precondition(senderPort > 0)
        defer { sender.stop() }

        let keys = [Data(repeating: 0x31, count: 32), Data(repeating: 0x72, count: 32)]
        let hubs = try keys.enumerated().map { index, key -> Hub in
            let endpoint = try (index == 0 ? makeIPv6Receiver() : makeIPv4Receiver())
            return Hub(socket: endpoint.socket, port: endpoint.port, host: endpoint.host,
                       key: key, ingress: try CamHubVideoIngress(sessionKey: key))
        }
        defer { hubs.forEach { close($0.socket) } }

        let config = accessUnit(bytes: Data([0, 0, 0, 1, 0x67, 0x42, 0, 0x1f,
                                             0, 0, 0, 1, 0x68, 0xce, 0x3c, 0x80]),
                                keyFrame: false, config: true, time: 0)
        sender.publish(config)
        for (index, hub) in hubs.enumerated() {
            sender.subscribe(clientID: UUID(), host: hub.host,
                             port: hub.port, sessionKey: hub.key)
            // Wait for each asynchronous subscription and its cached codec-config send.
            guard case let .frame(frame) = hub.ingress.offer(try receive(hub.socket), nowMs: 10),
                  frame.isCodecConfig, frame.h264Bytes == config.bytes else {
                fatalError("Hub \(index) did not receive its session-encrypted codec config")
            }
        }

        let first = accessUnit(bytes: Data(repeating: 0x65, count: 3_000),
                               keyFrame: true, config: false, time: 1_000)
        sender.publish(first)
        let count = try expectedPacketCount(first, sequence: 1)
        for index in hubs.indices {
            var complete: CamHubVideoPayload?
            for _ in 0..<count {
                let packet = try receive(hubs[index].socket)
                let fragment = CamHubRtpPacketCodec.decode(packet)
                if index == 1 && fragment?.fragmentIndex == 1 { continue }
                if case let .frame(frame) = hubs[index].ingress.offer(packet, nowMs: 100) {
                    complete = frame
                }
            }
            if index == 0 {
                precondition(complete?.h264Bytes == first.bytes,
                             "The healthy hub did not receive the complete frame")
            } else {
                precondition(complete == nil, "The damaged hub displayed an incomplete frame")
                _ = hubs[index].ingress.expire(nowMs: 140)
                precondition(hubs[index].ingress.droppedFrameCount == 1,
                             "The damaged hub did not expire its partial frame")
            }
        }

        // Represents the authenticated recovery request from the hub that lost a fragment.
        let recovery = accessUnit(bytes: Data(repeating: 0x66, count: 3_000),
                                 keyFrame: true, config: false, time: 2_000)
        sender.publish(recovery)
        let recoveryCount = try expectedPacketCount(recovery, sequence: 2)
        for hub in hubs {
            var complete: CamHubVideoPayload?
            for _ in 0..<recoveryCount {
                if case let .frame(frame) = hub.ingress.offer(try receive(hub.socket), nowMs: 200) {
                    complete = frame
                }
            }
            precondition(complete?.frameSequence == 2, "A hub did not recover on the next keyframe")
            precondition(complete?.h264Bytes == recovery.bytes)
        }

        // A local send failure for one hub must not stall P-frames to the healthy hub.
        let failedHubSignal = failurePlan.failNextDatagram(to: hubs[1].port)
        let afterLocalFailure = accessUnit(bytes: Data(repeating: 0x67, count: 3_000),
                                           keyFrame: true, config: false, time: 3_000)
        sender.publish(afterLocalFailure)
        guard failedHubSignal.wait(timeout: .now() + 3) == .success else {
            fatalError("The injected hub-specific send failure did not occur")
        }
        let afterLocalFailureCount = try expectedPacketCount(afterLocalFailure, sequence: 3)
        var healthyFrame: CamHubVideoPayload?
        for _ in 0..<afterLocalFailureCount {
            if case let .frame(frame) = hubs[0].ingress.offer(try receive(hubs[0].socket), nowMs: 300) {
                healthyFrame = frame
            }
        }
        precondition(healthyFrame?.frameSequence == 3,
                     "A failed write to one hub interrupted the healthy hub's keyframe")

        let delta = accessUnit(bytes: Data(repeating: 0x41, count: 3_000),
                               keyFrame: false, config: false, time: 3_033)
        sender.publish(delta)
        let deltaCount = try expectedPacketCount(delta, sequence: 4)
        for _ in 0..<deltaCount {
            if case let .frame(frame) = hubs[0].ingress.offer(try receive(hubs[0].socket), nowMs: 333) {
                healthyFrame = frame
            }
        }
        precondition(healthyFrame?.frameSequence == 4,
                     "A local write failure stalled subsequent frames for the healthy hub")
        var otherHubDelta: CamHubVideoPayload?
        for _ in 0..<deltaCount {
            if case let .frame(frame) = hubs[1].ingress.offer(try receive(hubs[1].socket), nowMs: 333) {
                otherHubDelta = frame
            }
        }
        precondition(otherHubDelta?.frameSequence == 4,
                     "The other hub did not continue receiving after its local write failure")

        let finalKeyFrame = accessUnit(bytes: Data(repeating: 0x68, count: 3_000),
                                       keyFrame: true, config: false, time: 3_066)
        sender.publish(finalKeyFrame)
        let finalKeyFrameCount = try expectedPacketCount(finalKeyFrame, sequence: 5)
        for hub in hubs {
            var complete: CamHubVideoPayload?
            for _ in 0..<finalKeyFrameCount {
                if case let .frame(frame) = hub.ingress.offer(try receive(hub.socket), nowMs: 366) {
                    complete = frame
                }
            }
            precondition(complete?.frameSequence == 5,
                         "A hub did not receive the next keyframe after an isolated local failure")
        }
        print("Dual-hub UDP sender smoke passed: per-hub encryption, isolated loss, keyframe recovery and local send failure isolation")
    }

    private static func accessUnit(bytes: Data, keyFrame: Bool, config: Bool,
                                   time: UInt64) -> CamHubEncodedAccessUnit {
        CamHubEncodedAccessUnit(bytes: bytes, isKeyFrame: keyFrame, isCodecConfig: config,
                                captureAtWallMs: time, encodedAtWallMs: time + 5,
                                rotationDegrees: 0)
    }

    private static func expectedPacketCount(_ unit: CamHubEncodedAccessUnit,
                                            sequence: UInt64) throws -> Int {
        let payload = CamHubVideoPayload(width: 1_280, height: 720,
                                         rotationDegrees: unit.rotationDegrees,
                                         isKeyFrame: unit.isKeyFrame,
                                         isCodecConfig: unit.isCodecConfig,
                                         frameSequence: sequence,
                                         captureAtWallMs: unit.captureAtWallMs,
                                         encodedAtWallMs: unit.encodedAtWallMs,
                                         h264Bytes: unit.bytes)
        let encryptedSize = try CamHubVideoPayloadCodec.encode(payload).count + 28
        return (encryptedSize + CamHubRtpPacketCodec.maxFragmentPayloadBytes - 1)
            / CamHubRtpPacketCodec.maxFragmentPayloadBytes
    }

    private static func makeIPv6Receiver() throws -> (socket: Int32, port: UInt16, host: NWEndpoint.Host) {
        let fd = socket(AF_INET6, SOCK_DGRAM, 0)
        guard fd >= 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
        var local = sockaddr_in6()
        local.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        local.sin6_family = sa_family_t(AF_INET6)
        local.sin6_addr = in6addr_loopback
        let bound = withUnsafePointer(to: &local) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in6>.size))
            }
        }
        guard bound == 0 else {
            let code = errno
            close(fd)
            throw POSIXError(.init(rawValue: code) ?? .EIO)
        }
        var actual = sockaddr_in6()
        var length = socklen_t(MemoryLayout<sockaddr_in6>.size)
        let named = withUnsafeMutablePointer(to: &actual) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard named == 0 else {
            let code = errno
            close(fd)
            throw POSIXError(.init(rawValue: code) ?? .EIO)
        }
        var timeout = timeval(tv_sec: 3, tv_usec: 0)
        guard setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout,
                         socklen_t(MemoryLayout<timeval>.size)) == 0 else {
            let code = errno
            close(fd)
            throw POSIXError(.init(rawValue: code) ?? .EIO)
        }
        return (fd, UInt16(bigEndian: actual.sin6_port), .ipv6(IPv6Address("::1")!))
    }

    private static func makeIPv4Receiver() throws -> (socket: Int32, port: UInt16, host: NWEndpoint.Host) {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
        var local = sockaddr_in()
        local.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        local.sin_family = sa_family_t(AF_INET)
        local.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &local) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            let code = errno
            close(fd)
            throw POSIXError(.init(rawValue: code) ?? .EIO)
        }
        var actual = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &actual) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(fd, $0, &length)
            }
        }
        guard named == 0 else {
            let code = errno
            close(fd)
            throw POSIXError(.init(rawValue: code) ?? .EIO)
        }
        var timeout = timeval(tv_sec: 3, tv_usec: 0)
        guard setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout,
                         socklen_t(MemoryLayout<timeval>.size)) == 0 else {
            let code = errno
            close(fd)
            throw POSIXError(.init(rawValue: code) ?? .EIO)
        }
        return (fd, UInt16(bigEndian: actual.sin_port), .ipv4(IPv4Address("127.0.0.1")!))
    }

    private static func receive(_ fd: Int32) throws -> Data {
        var buffer = [UInt8](repeating: 0, count: CamHubRtpPacketCodec.maxDatagramBytes)
        let received = buffer.withUnsafeMutableBytes { bytes in
            recv(fd, bytes.baseAddress, bytes.count, 0)
        }
        guard received > 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
        return Data(buffer.prefix(received))
    }
}
