import CamHubCore
import Foundation
import Network

struct CamHubAuthorizedVideoSession {
    let clientID: UUID
    let sessionKey: Data
}

/// Authenticated, bounded TCP fallback. Each client has one send in flight and at most one
/// pending access unit; queue pressure drops dependent P-frames until a fresh keyframe arrives.
final class CamHubTcpVideoSender {
    private struct PendingHandshake {
        let connection: NWConnection
        var token = Data()
    }

    private struct Client {
        let connection: NWConnection
        let sessionID: UUID
        let cipher: CamHubFrameCipher
        var pendingConfig: CamHubEncodedAccessUnit?
        var pendingKeyFrame: CamHubEncodedAccessUnit?
        var pendingAccessUnit: CamHubEncodedAccessUnit?
        var waitingForKeyFrame = false
        var isSending = false
        var sendStartedAtNanos: UInt64 = 0
    }

    private let queue = DispatchQueue(label: "com.camhub.camera.tcp-video", qos: .userInteractive)
    private let pendingLock = NSLock()
    private var listener: NWListener?
    private var listenerGeneration: UInt64 = 0
    private var pendingHandshakes: [UUID: PendingHandshake] = [:]
    private var clients: [UUID: Client] = [:]
    private var clientBySession: [UUID: UUID] = [:]
    private var cachedCodecConfig: CamHubEncodedAccessUnit?
    private var pendingAccessUnit: CamHubEncodedAccessUnit?
    private var pendingKeyFrame: CamHubEncodedAccessUnit?
    private var sendWorkerScheduled = false
    private var waitingForKeyFrame = false
    private var videoSequence: UInt64 = 0
    private var lastKeyFrameRequestNanos: UInt64 = 0
    private var resolveSession: ((Data, @escaping (CamHubAuthorizedVideoSession?) -> Void) -> Void)?
    private var onSubscriberChange: ((UUID, Bool) -> Void)?
    var onKeyFrameRequired: (() -> Void)?

    func start(
        resolveSession: @escaping (Data, @escaping (CamHubAuthorizedVideoSession?) -> Void) -> Void,
        onSubscriberChange: @escaping (UUID, Bool) -> Void,
        completion: @escaping (Result<UInt16, Error>) -> Void
    ) {
        queue.async {
            guard self.listener == nil else {
                completion(.failure(SenderError.alreadyStarted))
                return
            }
            self.resolveSession = resolveSession
            self.onSubscriberChange = onSubscriberChange
            self.listenerGeneration &+= 1
            let generation = self.listenerGeneration
            do {
                let listener = try NWListener(using: .tcp)
                self.listener = listener
                var didComplete = false
                listener.newConnectionHandler = { [weak self] connection in
                    guard let self else { connection.cancel(); return }
                    self.accept(connection)
                }
                listener.stateUpdateHandler = { [weak self, weak listener] state in
                    guard let self, let listener, self.listener === listener,
                          self.listenerGeneration == generation else { return }
                    switch state {
                    case .ready:
                        guard !didComplete, let port = listener.port?.rawValue else { return }
                        didComplete = true
                        completion(.success(port))
                    case let .failed(error):
                        if !didComplete {
                            didComplete = true
                            completion(.failure(error))
                        }
                        listener.cancel()
                        self.listener = nil
                    case .cancelled:
                        self.listener = nil
                    default:
                        break
                    }
                }
                listener.start(queue: self.queue)
            } catch {
                completion(.failure(error))
            }
        }
    }

    func publish(_ accessUnit: CamHubEncodedAccessUnit) {
        if accessUnit.isCodecConfig {
            pendingLock.lock()
            cachedCodecConfig = accessUnit
            pendingLock.unlock()
            queue.async { self.broadcastConfig(accessUnit) }
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
        } else if pendingAccessUnit != nil {
            pendingAccessUnit = nil
            waitingForKeyFrame = true
            requestKeyFrame = true
        } else {
            pendingAccessUnit = accessUnit
        }
        let shouldSchedule = !sendWorkerScheduled
        if shouldSchedule { sendWorkerScheduled = true }
        pendingLock.unlock()

        if requestKeyFrame { requestRecoveryKeyFrame() }
        if shouldSchedule { queue.async { self.drainPendingAccessUnits() } }
    }

    func unsubscribe(clientID: UUID) {
        queue.async {
            guard let connectionID = self.clientBySession[clientID] else { return }
            self.removeClient(connectionID)
        }
    }

    func stop() {
        queue.sync {
            listenerGeneration &+= 1
            listener?.cancel()
            listener = nil
            for id in Array(pendingHandshakes.keys) {
                pendingHandshakes[id]?.connection.cancel()
                pendingHandshakes.removeValue(forKey: id)
            }
            for id in Array(clients.keys) { removeClient(id) }
            resolveSession = nil
            onSubscriberChange = nil
            videoSequence = 0
        }
        pendingLock.lock()
        cachedCodecConfig = nil
        pendingAccessUnit = nil
        pendingKeyFrame = nil
        sendWorkerScheduled = false
        waitingForKeyFrame = false
        pendingLock.unlock()
    }

    private func accept(_ connection: NWConnection) {
        guard pendingHandshakes.count + clients.count < 4 else {
            connection.cancel()
            return
        }
        let id = UUID()
        pendingHandshakes[id] = PendingHandshake(connection: connection)
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.receiveToken(id)
            case .failed, .cancelled:
                if let pending = self.pendingHandshakes.removeValue(forKey: id) {
                    pending.connection.cancel()
                }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveToken(_ id: UUID) {
        guard let pending = pendingHandshakes[id] else { return }
        let remaining = 32 - pending.token.count
        guard remaining > 0 else { authenticate(id) ; return }
        pending.connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) {
            [weak self] data, _, complete, error in
            guard let self else { return }
            self.queue.async {
                guard var current = self.pendingHandshakes[id] else { return }
                if let data, !data.isEmpty { current.token.append(data) }
                self.pendingHandshakes[id] = current
                if current.token.count == 32 {
                    self.authenticate(id)
                } else if error != nil || complete {
                    self.pendingHandshakes.removeValue(forKey: id)?.connection.cancel()
                } else {
                    self.receiveToken(id)
                }
            }
        }
    }

    private func authenticate(_ connectionID: UUID) {
        guard let pending = pendingHandshakes[connectionID],
              pending.token.count == 32,
              let resolveSession else {
            pendingHandshakes.removeValue(forKey: connectionID)?.connection.cancel()
            return
        }
        let token = pending.token
        resolveSession(token) { [weak self] authorized in
            guard let self else { return }
            self.queue.async {
                guard let pending = self.pendingHandshakes.removeValue(forKey: connectionID) else { return }
                guard let authorized, self.clients.count < 4,
                      self.clientBySession[authorized.clientID] == nil,
                      let cipher = try? CamHubFrameCipher(key: authorized.sessionKey) else {
                    pending.connection.cancel()
                    return
                }
                var client = Client(
                    connection: pending.connection,
                    sessionID: authorized.clientID,
                    cipher: cipher
                )
                self.pendingLock.lock()
                client.pendingConfig = self.cachedCodecConfig
                self.pendingLock.unlock()
                self.clients[connectionID] = client
                self.clientBySession[authorized.clientID] = connectionID
                pending.connection.stateUpdateHandler = { [weak self] state in
                    guard let self else { return }
                    if case .failed = state { self.queue.async { self.removeClient(connectionID) } }
                    if case .cancelled = state { self.queue.async { self.removeClient(connectionID) } }
                }
                self.onSubscriberChange?(authorized.clientID, true)
                if client.pendingConfig != nil { self.sendNext(connectionID) }
                self.requestRecoveryKeyFrame()
            }
        }
    }

    private func broadcastConfig(_ accessUnit: CamHubEncodedAccessUnit) {
        for id in Array(clients.keys) {
            guard var client = clients[id] else { continue }
            client.pendingConfig = accessUnit
            clients[id] = client
            sendNext(id)
        }
    }

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
            videoSequence &+= 1
            for id in Array(clients.keys) { enqueue(next, for: id) }
        }
    }

    private func enqueue(_ accessUnit: CamHubEncodedAccessUnit, for id: UUID) {
        guard var client = clients[id] else { return }
        if client.isSending,
           DispatchTime.now().uptimeNanoseconds &- client.sendStartedAtNanos > 750_000_000 {
            removeClient(id)
            return
        }
        if accessUnit.isKeyFrame {
            client.pendingKeyFrame = accessUnit
            client.waitingForKeyFrame = false
            client.pendingAccessUnit = nil
        } else if client.waitingForKeyFrame {
            clients[id] = client
            return
        } else if client.pendingAccessUnit != nil {
            client.pendingAccessUnit = nil
            client.waitingForKeyFrame = true
            clients[id] = client
            requestRecoveryKeyFrame()
            return
        } else {
            client.pendingAccessUnit = accessUnit
        }
        clients[id] = client
        sendNext(id)
    }

    private func sendNext(_ id: UUID) {
        guard var client = clients[id], !client.isSending else { return }
        let accessUnit: CamHubEncodedAccessUnit
        let frameSequence: UInt64
        if let config = client.pendingConfig {
            accessUnit = config
            client.pendingConfig = nil
            frameSequence = 0
        } else if let keyFrame = client.pendingKeyFrame {
            accessUnit = keyFrame
            client.pendingKeyFrame = nil
            frameSequence = videoSequence
        } else if let next = client.pendingAccessUnit {
            accessUnit = next
            client.pendingAccessUnit = nil
            frameSequence = videoSequence
        } else {
            clients[id] = client
            return
        }
        do {
            let payload = CamHubVideoPayload(
                width: 1_280,
                height: 720,
                rotationDegrees: accessUnit.rotationDegrees,
                isKeyFrame: accessUnit.isKeyFrame,
                isCodecConfig: accessUnit.isCodecConfig,
                frameSequence: frameSequence,
                captureAtWallMs: accessUnit.captureAtWallMs,
                encodedAtWallMs: accessUnit.encodedAtWallMs,
                h264Bytes: accessUnit.bytes
            )
            let encoded = try CamHubVideoPayloadCodec.encode(payload)
            let encrypted = try client.cipher.encrypt(encoded)
            guard let framed = CamHubTcpFrameCodec.encode(encrypted) else {
                throw CamHubProtocolError.frameTooLarge
            }
            client.isSending = true
            client.sendStartedAtNanos = DispatchTime.now().uptimeNanoseconds
            clients[id] = client
            client.connection.send(content: framed, completion: .contentProcessed { [weak self] error in
                guard let self else { return }
                self.queue.async {
                    guard var current = self.clients[id] else { return }
                    if error != nil {
                        self.removeClient(id)
                        return
                    }
                    current.isSending = false
                    current.sendStartedAtNanos = 0
                    self.clients[id] = current
                    self.sendNext(id)
                }
            })
        } catch {
            removeClient(id)
            requestRecoveryKeyFrame()
        }
    }

    private func removeClient(_ connectionID: UUID) {
        guard let client = clients.removeValue(forKey: connectionID) else { return }
        clientBySession.removeValue(forKey: client.sessionID)
        onSubscriberChange?(client.sessionID, false)
        client.connection.cancel()
    }

    private func requestRecoveryKeyFrame() {
        let now = DispatchTime.now().uptimeNanoseconds
        guard lastKeyFrameRequestNanos == 0 || now &- lastKeyFrameRequestNanos >= 250_000_000 else { return }
        lastKeyFrameRequestNanos = now
        onKeyFrameRequired?()
    }

    private enum SenderError: LocalizedError {
        case alreadyStarted
        var errorDescription: String? {
            switch self {
            case .alreadyStarted: return "TCP video sender is already running."
            }
        }
    }
}
