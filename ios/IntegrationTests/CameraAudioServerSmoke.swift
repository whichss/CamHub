import CamHubCore
import Foundation
import Network

private final class AudioFrameResult {
    private let lock = NSLock()
    private var storage = Data()
    private var finished = false
    let semaphore = DispatchSemaphore(value: 0)

    func append(_ data: Data) {
        lock.lock()
        storage.append(data)
        lock.unlock()
    }

    func complete() {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        lock.unlock()
        semaphore.signal()
    }

    var bytes: Data {
        lock.lock(); defer { lock.unlock() }
        return storage
    }
}

@main
struct CameraAudioServerSmoke {
    static func main() throws {
        let key = Data((0..<32).map(UInt8.init))
        let payload = Data([2, 1, 0, 0x01, 0xE0, 0x34, 0x12, 0xCC, 0xED])
        let server = CamHubCameraAudioServer { _ in key }
        let started = DispatchSemaphore(value: 0)
        let resultLock = NSLock()
        var port: UInt16 = 0
        var startError: Error?
        server.start { result in
            resultLock.lock()
            switch result {
            case let .success(value): port = value
            case let .failure(error): startError = error
            }
            resultLock.unlock()
            started.signal()
        }
        guard started.wait(timeout: .now() + 5) == .success else {
            throw SmokeError.timedOut("audio listener did not start")
        }
        resultLock.lock()
        let activePort = port
        let error = startError
        resultLock.unlock()
        if let error { throw error }
        guard activePort > 0, let endpointPort = NWEndpoint.Port(rawValue: activePort) else {
            throw SmokeError.invalidPort
        }

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let connection = NWConnection(to: .hostPort(host: .ipv4(.loopback), port: endpointPort),
                                      using: NWParameters(tls: nil, tcp: tcp))
        let queue = DispatchQueue(label: "com.camhub.integration.camera-audio-client")
        let ready = DispatchSemaphore(value: 0)
        let frameResult = AudioFrameResult()
        connection.stateUpdateHandler = { state in
            if case .ready = state { ready.signal() }
            if case .failed = state { frameResult.complete() }
        }
        connection.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success else {
            connection.cancel()
            server.stop()
            throw SmokeError.timedOut("audio client did not connect")
        }
        receiveNext(connection, queue: queue, result: frameResult)
        var receivedFrame = false
        for _ in 0..<10 {
            server.publish(payload)
            if frameResult.semaphore.wait(timeout: .now() + .milliseconds(150)) == .success {
                receivedFrame = true
                break
            }
        }
        guard receivedFrame else {
            connection.cancel()
            server.stop()
            throw SmokeError.timedOut("encrypted audio frame was not received")
        }
        connection.cancel()
        server.stop()

        let received = frameResult.bytes
        guard received.count >= 4 else { throw SmokeError.invalidFrame }
        let size = received.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard size > 0, Int(size) == received.count - 4 else { throw SmokeError.invalidFrame }
        let encrypted = received.subdata(in: 4..<received.count)
        let decrypted = try CamHubFrameCipher(key: key).decrypt(encrypted)
        guard decrypted == payload else { throw SmokeError.payloadMismatch }
        print("Camera audio server smoke test passed: AES-GCM TCP framing round-tripped PCM v2 payload")
    }

    private static func receiveNext(_ connection: NWConnection, queue: DispatchQueue,
                                    result: AudioFrameResult) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, complete, error in
            if let data, !data.isEmpty { result.append(data) }
            if error != nil || complete {
                result.complete()
                return
            }
            let bytes = result.bytes
            guard bytes.count < 64 * 1024 else { result.complete(); return }
            if bytes.count >= 4 {
                let size = bytes.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
                if size > 0 && bytes.count >= 4 + Int(size) {
                    result.complete()
                    return
                }
            }
            receiveNext(connection, queue: queue, result: result)
        }
    }

    private enum SmokeError: Error {
        case invalidPort
        case invalidFrame
        case payloadMismatch
        case timedOut(String)
    }
}
