import CamHubCore
import CryptoKit
import Foundation
import Network
import Security

/// TLS control channel for one camera. The UI must explicitly confirm its certificate fingerprint.
final class CamHubHubControlConnection {
    private static let hubName: String = {
        let key = "camhub.hub.instance-id"
        let stored = UserDefaults.standard.string(forKey: key)
        let id = stored ?? UUID().uuidString.replacingOccurrences(of: "-", with: "")
        if stored == nil { UserDefaults.standard.set(id, forKey: key) }
        return "iOS-Hub-" + String(id.prefix(12))
    }()
    private let queue = DispatchQueue(label: "com.camhub.hub.control", qos: .userInitiated)
    private let endpoint: NWEndpoint
    private let pin: String
    private let verify: (String, @escaping (Bool) -> Void) -> Void
    private let onMessage: (CamHubControlMessage) -> Void
    private let onRemoteHost: (NWEndpoint.Host) -> Void
    private let onClockEstimate: (CamHubClockEstimate) -> Void
    private let onError: (String) -> Void
    private var connection: NWConnection?
    private var buffer = Data()
    private var heartbeat: DispatchSourceTimer?
    private var connectionDeadline: DispatchSourceTimer?
    private var lastMessageAt: UInt64 = 0
    private var handshakeStartedAt: UInt64 = 0
    private var authenticated = false
    private var stopping = false
    private var syncSequence: UInt64 = 0
    private var pendingSync: (id: UInt64, sentAt: UInt64)?

    init(endpoint: NWEndpoint, pin: String, verify: @escaping (String, @escaping (Bool) -> Void) -> Void,
         onRemoteHost: @escaping (NWEndpoint.Host) -> Void,
         onClockEstimate: @escaping (CamHubClockEstimate) -> Void,
         onMessage: @escaping (CamHubControlMessage) -> Void, onError: @escaping (String) -> Void) {
        self.endpoint = endpoint
        self.pin = pin
        self.verify = verify
        self.onRemoteHost = onRemoteHost
        self.onClockEstimate = onClockEstimate
        self.onMessage = onMessage
        self.onError = onError
    }

    func start() {
        queue.async {
            guard self.connection == nil else { return }
            guard self.pin.count == 8, self.pin.allSatisfy({ $0.isASCII && $0.isNumber }) else {
                self.onError("Enter the 8-digit camera PIN")
                return
            }
            self.stopping = false
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
            sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { [weak self] _, trust, complete in
                guard let self else { complete(false); return }
                let reference = sec_trust_copy_ref(trust).takeRetainedValue()
                guard let certificates = SecTrustCopyCertificateChain(reference) as? [SecCertificate],
                      let certificate = certificates.first else { complete(false); return }
                let fingerprint = SHA256.hash(data: SecCertificateCopyData(certificate) as Data)
                    .map { String(format: "%02X", $0) }.joined()
                DispatchQueue.main.async { self.verify(fingerprint, complete) }
            }, self.queue)
            let connection = NWConnection(to: self.endpoint, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
            self.connection = connection
            let deadline = DispatchSource.makeTimerSource(queue: self.queue)
            deadline.schedule(deadline: .now() + 30)
            deadline.setEventHandler { [weak self, weak connection] in
                guard let self, let connection, self.connection === connection else { return }
                self.onError("Camera TLS connection timed out")
                self.closeOnQueue()
            }
            deadline.resume()
            self.connectionDeadline = deadline
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                guard let self, let connection, self.connection === connection else { return }
                switch state {
                case .ready:
                    self.connectionDeadline?.cancel()
                    self.connectionDeadline = nil
                    guard case let .hostPort(host, _) = connection.currentPath?.remoteEndpoint else {
                        self.onError("Could not resolve the camera network address")
                        self.closeOnQueue()
                        return
                    }
                    self.onRemoteHost(host)
                    self.lastMessageAt = DispatchTime.now().uptimeNanoseconds
                    self.handshakeStartedAt = self.lastMessageAt
                    self.authenticated = false
                    var hello = CamHubControlMessage(type: "hello")
                    hello.deviceName = Self.hubName
                    hello.pin = self.pin
                    self.sendOnQueue(hello)
                    self.receive(connection)
                    let timer = DispatchSource.makeTimerSource(queue: self.queue)
                    timer.schedule(deadline: .now() + 3, repeating: 3)
                    timer.setEventHandler { [weak self] in
                        guard let self else { return }
                        let now = DispatchTime.now().uptimeNanoseconds
                        if (!self.authenticated && now &- self.handshakeStartedAt > 10_000_000_000) ||
                            now &- self.lastMessageAt > 20_000_000_000 {
                            self.onError("Camera control connection timed out")
                            self.closeOnQueue()
                            return
                        }
                        self.syncSequence &+= 1
                        let sentAt = UInt64(Date().timeIntervalSince1970 * 1_000)
                        self.pendingSync = (self.syncSequence, sentAt)
                        var ping = CamHubControlMessage(type: "ping")
                        ping.syncId = self.syncSequence
                        ping.t0WallMs = sentAt
                        self.sendOnQueue(ping)
                    }
                    timer.resume()
                    self.heartbeat = timer
                case let .failed(error):
                    if !self.stopping { self.onError(error.localizedDescription) }
                    self.closeOnQueue()
                case .cancelled: self.closeOnQueue()
                default: break
                }
            }
            connection.start(queue: self.queue)
        }
    }

    func send(_ message: CamHubControlMessage) { queue.async { self.sendOnQueue(message) } }
    func stop() {
        queue.async {
            guard !self.stopping else { return }
            self.stopping = true
            guard self.authenticated, let current = self.connection,
                  var bytes = try? JSONEncoder().encode(CamHubControlMessage(type: "command", command: "udp_unsubscribe")) else {
                self.closeOnQueue()
                return
            }
            self.heartbeat?.cancel()
            self.heartbeat = nil
            bytes.append(0x0a)
            current.send(content: bytes, completion: .contentProcessed { [weak self, weak current] _ in
                self?.queue.async {
                    guard let self, let current, self.connection === current else { return }
                    self.closeOnQueue()
                }
            })
            self.queue.asyncAfter(deadline: .now() + .milliseconds(500)) { [weak self, weak current] in
                guard let self, let current, self.connection === current else { return }
                self.closeOnQueue()
            }
        }
    }

    private func sendOnQueue(_ message: CamHubControlMessage) {
        guard !stopping, let connection, var data = try? JSONEncoder().encode(message) else { return }
        data.append(0x0a)
        connection.send(content: data, completion: .contentProcessed { [weak self, weak connection] error in
            if let error {
                self?.queue.async {
                    guard let self, let connection, self.connection === connection else { return }
                    if !self.stopping { self.onError(error.localizedDescription) }
                    self.closeOnQueue()
                }
            }
        })
    }

    private func receive(_ current: NWConnection) {
        current.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self, weak current] data, _, complete, error in
            guard let self, let current, self.connection === current else { return }
            if let data { self.buffer.append(data) }
            guard self.buffer.count <= 32_768 else { self.onError("Control message too large"); self.closeOnQueue(); return }
            while let newline = self.buffer.firstIndex(of: 10) {
                let line = self.buffer.prefix(upTo: newline)
                self.buffer.removeSubrange(...newline)
                guard line.count <= 4096, let message = try? JSONDecoder().decode(CamHubControlMessage.self, from: Data(line)) else {
                    self.onError("Invalid control message"); self.closeOnQueue(); return
                }
                self.lastMessageAt = DispatchTime.now().uptimeNanoseconds
                if message.type == "hello_ack" { self.authenticated = true }
                if message.type == "pong", let pending = self.pendingSync,
                   message.syncId == pending.id, message.t0WallMs == pending.sentAt {
                    self.pendingSync = nil
                    let receivedAt = UInt64(Date().timeIntervalSince1970 * 1_000)
                    if let estimate = CamHubClockEstimate.calculate(t0: pending.sentAt, t1: message.t1WallMs,
                                                                     t2: message.t2WallMs, t3: receivedAt) {
                        self.onClockEstimate(estimate)
                    }
                }
                if message.type == "ping" {
                    let receivedAt = UInt64(Date().timeIntervalSince1970 * 1_000)
                    var pong = CamHubControlMessage(type: "pong")
                    pong.syncId = message.syncId
                    pong.t0WallMs = message.t0WallMs
                    pong.t1WallMs = receivedAt
                    pong.t2WallMs = UInt64(Date().timeIntervalSince1970 * 1_000)
                    self.sendOnQueue(pong)
                }
                self.onMessage(message)
            }
            if error != nil || complete {
                if !self.stopping { self.onError(error?.localizedDescription ?? "Camera disconnected") }
                self.closeOnQueue()
            }
            else { self.receive(current) }
        }
    }

    private func closeOnQueue() {
        connectionDeadline?.cancel(); connectionDeadline = nil
        heartbeat?.cancel(); heartbeat = nil
        connection?.cancel(); connection = nil
        buffer.removeAll()
        pendingSync = nil
    }
}
