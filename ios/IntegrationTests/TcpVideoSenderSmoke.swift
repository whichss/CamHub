import CamHubCore
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
        var received: [CamHubVideoPayload] = []
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

        var receiveBuffer = Data()
        let receiveCipher = try CamHubFrameCipher(key: key)
        func receiveNext() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
                data, _, complete, error in
                errorLock.lock()
                if let error { receiveError = error }
                if let data, !data.isEmpty { receiveBuffer.append(data) }
                var decodedFrames: [CamHubVideoPayload] = []
                do {
                    while let framed = try CamHubTcpFrameCodec.decodeNext(from: &receiveBuffer) {
                        guard let payload = try? receiveCipher.decrypt(framed),
                              let frame = CamHubVideoPayloadCodec.decode(payload) else { continue }
                        received.append(frame)
                        decodedFrames.append(frame)
                    }
                } catch {
                    receiveError = error
                }
                errorLock.unlock()
                decodedFrames.forEach { frame in
                    if frame.isCodecConfig { configReceived.signal() }
                    if frame.isKeyFrame { keyFrameReceived.signal() }
                }
                if error == nil && !complete { receiveNext() }
            }
        }
        receiveNext()

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

        errorLock.lock()
        let finalFrames = received
        let finalError = receiveError
        errorLock.unlock()
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
