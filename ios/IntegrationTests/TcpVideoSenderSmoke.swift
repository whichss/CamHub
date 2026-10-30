import CamHubCore
import Foundation
import Network

private final class TcpVideoSmokeReceiver: @unchecked Sendable {
    private let connection: NWConnection
    private let cipher: CamHubFrameCipher
    private let stateLock = NSLock()
    private let configReceived: DispatchSemaphore
    private let keyFrameReceived: DispatchSemaphore
    private var receiveBuffer = Data()
    private var received: [CamHubVideoPayload] = []
    private var receiveError: Error?

    init(connection: NWConnection, cipher: CamHubFrameCipher,
         configReceived: DispatchSemaphore, keyFrameReceived: DispatchSemaphore) {
        self.connection = connection
        self.cipher = cipher
        self.configReceived = configReceived
        self.keyFrameReceived = keyFrameReceived
    }

    var frames: [CamHubVideoPayload] {
        stateLock.lock()
        defer { stateLock.unlock() }
        return received
    }

    var failure: Error? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return receiveError
    }

    func start() { receiveNext() }

    private func receiveNext() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self] data, _, complete, error in
            guard let self else { return }
            self.stateLock.lock()
            if let error { self.receiveError = error }
            if let data, !data.isEmpty { self.receiveBuffer.append(data) }
            var decodedFrames: [CamHubVideoPayload] = []
            do {
                while let framed = try CamHubTcpFrameCodec.decodeNext(from: &self.receiveBuffer) {
                    guard let payload = try? self.cipher.decrypt(framed),
                          let frame = CamHubVideoPayloadCodec.decode(payload) else { continue }
                    self.received.append(frame)
                    decodedFrames.append(frame)
                }
            } catch {
                self.receiveError = error
            }
            self.stateLock.unlock()
            decodedFrames.forEach { frame in
                if frame.isCodecConfig { self.configReceived.signal() }
                if frame.isKeyFrame { self.keyFrameReceived.signal() }
            }
            if error == nil && !complete { self.receiveNext() }
        }
    }
}

struct CamHubEncodedAccessUnit {
    let bytes: Data
    let isKeyFrame: Bool
    let isCodecConfig: Bool
    let captureAtWallMs: UInt64
    let encodedAtWallMs: UInt64
    let rotationDegrees: Int
}

@main
struct TcpVideoSenderSmoke {
    static func main() throws {
        let sender = CamHubTcpVideoSender()
        let token = Data((0..<32).map(UInt8.init))
        let key = Data(repeating: 0x56, count: 32)
        let listenerReady = DispatchSemaphore(value: 0)
        let configReceived = DispatchSemaphore(value: 0)
        let keyFrameReceived = DispatchSemaphore(value: 0)
        let errorLock = NSLock()
        var listenerResult: Result<UInt16, Error>?
        var receiveError: Error?

        sender.onKeyFrameRequired = { }
        sender.start(
            resolveSession: { candidate, completion in
                completion(candidate == token ? CamHubAuthorizedVideoSession(
                    clientID: UUID(), sessionKey: key
                ) : nil)
            },
            onSubscriberChange: { _, _ in },
            completion: { result in listenerResult = result; listenerReady.signal() }
        )
        guard listenerReady.wait(timeout: .now() + 3) == .success,
              let port = try listenerResult?.get() else {
            throw SmokeError.listenerDidNotStart
        }

        let config = CamHubEncodedAccessUnit(
            bytes: Data([0, 0, 0, 1, 0x67, 0x42, 0, 0x1f, 0, 0, 0, 1, 0x68, 0xce]),
            isKeyFrame: false,
            isCodecConfig: true,
            captureAtWallMs: 10,
            encodedAtWallMs: 11,
            rotationDegrees: 0
        )
        sender.publish(config)

        let connection = NWConnection(
            host: NWEndpoint.Host("127.0.0.1"),
            port: NWEndpoint.Port(rawValue: port)!,
            using: .tcp
        )
        let hubReady = DispatchSemaphore(value: 0)
        connection.stateUpdateHandler = { state in
            if case .ready = state { hubReady.signal() }
            if case let .failed(error) = state {
                errorLock.lock(); receiveError = error; errorLock.unlock()
                configReceived.signal(); keyFrameReceived.signal()
            }
        }
        let receiveQueue = DispatchQueue(label: "camhub.tcp-smoke.receive")
        connection.start(queue: receiveQueue)
        guard hubReady.wait(timeout: .now() + 3) == .success else {
            throw SmokeError.hubDidNotConnect
        }
        let tokenSent = DispatchSemaphore(value: 0)
        connection.send(content: token, completion: .contentProcessed { error in
            if let error {
                errorLock.lock(); receiveError = error; errorLock.unlock()
            }
            tokenSent.signal()
        })
        guard tokenSent.wait(timeout: .now() + 3) == .success else {
            throw SmokeError.authorizationTokenNotSent
        }

        let receiveCipher = try CamHubFrameCipher(key: key)
        let receiver = TcpVideoSmokeReceiver(
            connection: connection,
            cipher: receiveCipher,
            configReceived: configReceived,
            keyFrameReceived: keyFrameReceived
        )
        receiver.start()

        guard configReceived.wait(timeout: .now() + 3) == .success else {
            throw SmokeError.codecConfigNotReceived
        }
        let keyFrame = CamHubEncodedAccessUnit(
            bytes: Data([0, 0, 0, 1, 0x65, 0x11, 0x22]),
            isKeyFrame: true,
            isCodecConfig: false,
            captureAtWallMs: 20,
            encodedAtWallMs: 22,
            rotationDegrees: 90
        )
        sender.publish(keyFrame)
        guard keyFrameReceived.wait(timeout: .now() + 3) == .success else {
            throw SmokeError.keyFrameNotReceived
        }

        let finalFrames = receiver.frames
        errorLock.lock()
        let connectionError = receiveError
        errorLock.unlock()
        let finalError = receiver.failure ?? connectionError
        guard finalError == nil,
              finalFrames.contains(where: { $0.isCodecConfig && $0.h264Bytes == config.bytes }),
              finalFrames.contains(where: { $0.isKeyFrame && $0.frameSequence == 1 &&
                  $0.rotationDegrees == 90 && $0.h264Bytes == keyFrame.bytes }) else {
            throw SmokeError.invalidReceivedFrames
        }
        connection.cancel()
        sender.stop()
        print("Authenticated TCP sender smoke passed: session token, AES-GCM, framed codec config and keyframe")
    }

    private enum SmokeError: Error {
        case listenerDidNotStart
        case hubDidNotConnect
        case authorizationTokenNotSent
        case codecConfigNotReceived
        case keyFrameNotReceived
        case invalidReceivedFrames
    }
}
