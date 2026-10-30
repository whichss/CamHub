import CamHubCore
import Darwin
import Foundation
import Network

/// Executed as a command-line test, never as an application or simulator.
@main
struct UDPReceiverSmoke {
    static func main() throws {
        precondition(camHubHostsMatch(NWEndpoint.Host("127.0.0.1"), NWEndpoint.Host("::ffff:127.0.0.1")))
        precondition(!camHubHostsMatch(NWEndpoint.Host("127.0.0.1"), NWEndpoint.Host("::ffff:127.0.0.2")))
        precondition(!camHubHostsMatch(NWEndpoint.Host("::1"), NWEndpoint.Host("::2")))
        let socketFD = socket(AF_INET, SOCK_DGRAM, 0)
        precondition(socketFD >= 0)
        defer { close(socketFD) }
        var local = sockaddr_in()
        local.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        local.sin_family = sa_family_t(AF_INET)
        local.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bindResult = withUnsafePointer(to: &local) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socketFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        precondition(bindResult == 0)
        var addressLength = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameResult = withUnsafeMutablePointer(to: &local) { p in
            p.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socketFD, $0, &addressLength) }
        }
        precondition(nameResult == 0)
        let key = Data(repeating: 3, count: 32)
        let payload = CamHubVideoPayload(width: 1280, height: 720, rotationDegrees: 0,
                                         isKeyFrame: true, isCodecConfig: false, frameSequence: 1,
                                         captureAtWallMs: 10, encodedAtWallMs: 11,
                                         h264Bytes: Data(repeating: 7, count: 2_000))
        let encrypted = try CamHubFrameCipher(key: key).encrypt(CamHubVideoPayloadCodec.encode(payload))
        let packets = try CamHubRtpPacketCodec.packetize(encrypted, frameID: 1, timestamp: 1,
                                                           ssrc: 1, firstSequence: 1, isKeyFrame: true)
        let ready = DispatchSemaphore(value: 0)
        let received = DispatchSemaphore(value: 0)
        let dropped = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var port: UInt16 = 0
        var result: CamHubVideoPayload?
        var error: String?
        let receiver = try CamHubUdpVideoReceiver(expectedHost: NWEndpoint.Host("127.0.0.1"),
                                                   expectedPort: UInt16(bigEndian: local.sin_port), sessionKey: key,
            onFrame: { frame in lock.lock(); result = frame; lock.unlock(); received.signal() },
            onDroppedFrame: { dropped.signal() },
            onError: { failure in lock.lock(); error = failure.localizedDescription; lock.unlock(); ready.signal(); received.signal() })
        receiver.start { value in lock.lock(); port = value; lock.unlock(); ready.signal() }
        guard ready.wait(timeout: .now() + 5) == .success else { fatalError("UDP listener did not become ready") }
        lock.lock(); let targetPort = port; let startError = error; lock.unlock()
        guard targetPort > 0 else { fatalError(startError ?? "UDP listener has no port") }
        var destination = local
        destination.sin_port = targetPort.bigEndian
        let unexpectedSocket = socket(AF_INET, SOCK_DGRAM, 0)
        precondition(unexpectedSocket >= 0)
        for packet in packets {
            let sent = packet.withUnsafeBytes { bytes in
                withUnsafePointer(to: &destination) { p in
                    p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(unexpectedSocket, bytes.baseAddress!, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            precondition(sent == packet.count)
        }
        close(unexpectedSocket)
        precondition(received.wait(timeout: .now() + .milliseconds(250)) == .timedOut,
                     "Received a frame from an unexpected source port")
        // Deliver fragments out of order to exercise the receiver's real reassembly path.
        for packet in packets.reversed() {
            let sent = packet.withUnsafeBytes { bytes in
                withUnsafePointer(to: &destination) { p in
                    p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        sendto(socketFD, bytes.baseAddress!, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            precondition(sent == packet.count)
        }
        guard received.wait(timeout: .now() + 5) == .success else { fatalError("UDP frame was not received") }
        lock.lock(); let finalResult = result; let finalError = error; lock.unlock()
        guard finalResult == payload else { fatalError(finalError ?? "Received frame mismatch") }
        let incomplete = try CamHubRtpPacketCodec.packetize(encrypted, frameID: 2, timestamp: 2,
                                                              ssrc: 1, firstSequence: 20, isKeyFrame: true)
        let first = incomplete[0]
        let partialSent = first.withUnsafeBytes { bytes in
            withUnsafePointer(to: &destination) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(socketFD, bytes.baseAddress!, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        precondition(partialSent == first.count)
        guard dropped.wait(timeout: .now() + 5) == .success else { fatalError("Incomplete frame was not expired") }
        receiver.stop()
        try checkIPv6()
        print("UDP receiver smoke test passed, including reordered fragments and incomplete-frame expiration")
    }

    private static func checkIPv6() throws {
        let fd = socket(AF_INET6, SOCK_DGRAM, 0)
        precondition(fd >= 0)
        defer { close(fd) }
        var local = sockaddr_in6()
        local.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        local.sin6_family = sa_family_t(AF_INET6)
        local.sin6_addr = in6addr_loopback
        let size = socklen_t(MemoryLayout<sockaddr_in6>.size)
        let bound = withUnsafePointer(to: &local) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, size) }
        }
        precondition(bound == 0)
        var addressLength = size
        let named = withUnsafeMutablePointer(to: &local) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &addressLength) }
        }
        precondition(named == 0)
        let key = Data(repeating: 9, count: 32)
        let payload = CamHubVideoPayload(width: 1280, height: 720, rotationDegrees: 0,
                                         isKeyFrame: true, isCodecConfig: false, frameSequence: 1,
                                         captureAtWallMs: 10, encodedAtWallMs: 11,
                                         h264Bytes: Data([0, 0, 0, 1, 0x65]))
        let encrypted = try CamHubFrameCipher(key: key).encrypt(CamHubVideoPayloadCodec.encode(payload))
        let packet = try CamHubRtpPacketCodec.packetize(encrypted, frameID: 1, timestamp: 1,
                                                          ssrc: 1, firstSequence: 1, isKeyFrame: true)[0]
        let ready = DispatchSemaphore(value: 0)
        let received = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var port: UInt16 = 0
        var result: CamHubVideoPayload?
        let receiver = try CamHubUdpVideoReceiver(expectedHost: NWEndpoint.Host("::1"),
                                                   expectedPort: UInt16(bigEndian: local.sin6_port), sessionKey: key,
            onFrame: { frame in lock.lock(); result = frame; lock.unlock(); received.signal() },
            onDroppedFrame: {},
            onError: { _ in ready.signal(); received.signal() })
        receiver.start { value in lock.lock(); port = value; lock.unlock(); ready.signal() }
        precondition(ready.wait(timeout: .now() + 5) == .success)
        lock.lock(); let target = port; lock.unlock()
        precondition(target > 0)
        var destination = local
        destination.sin6_port = target.bigEndian
        let sent = packet.withUnsafeBytes { bytes in
            withUnsafePointer(to: &destination) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, bytes.baseAddress!, bytes.count, 0, $0, size)
                }
            }
        }
        precondition(sent == packet.count)
        precondition(received.wait(timeout: .now() + 5) == .success)
        lock.lock(); let actual = result; lock.unlock()
        precondition(actual == payload, "IPv6 frame mismatch")
        receiver.stop()
        print("IPv6 UDP receiver smoke test passed")
    }
}
