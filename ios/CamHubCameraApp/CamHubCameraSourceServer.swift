import Combine
import CamHubCore
import Foundation
import Network
import Security

/// iOS camera-side TLS control server. The hub provides its UDP receive port only after it has
/// checked the on-device TLS fingerprint and the pairing PIN has been accepted.
final class CamHubCameraSourceServer: ObservableObject {
    private struct ClientState {
        let connection: NWConnection
        var receiveBuffer = Data()
        var deviceName = ""
        var remoteHost = ""
        var remoteAddress: NWEndpoint.Host?
        var sessionKey: Data?
        var tcpAuthToken: Data?
        let connectedAt = ProcessInfo.processInfo.systemUptime
        var lastMessageAt = ProcessInfo.processInfo.systemUptime
        var lastRecoveryRequestNanos: UInt64 = 0
        var requestedBitrateMbps: Int?
    }

    private typealias ControlMessage = CamHubControlMessage

    @Published private(set) var status = "OFFLINE"
    @Published private(set) var pairingPIN = "--------"
    @Published private(set) var fingerprint = ""
    @Published private(set) var cameraName = ""
    @Published private(set) var connectedHubs: [String] = []
    @Published private(set) var lastError: String?

    private let queue = DispatchQueue(label: "com.camhub.camera.control", qos: .userInitiated)
    private let videoPublishLock = NSLock()
    private let identityStore = CamHubIdentityStore()
    private let videoSender = CamHubUdpVideoSender()
    private let tcpVideoSender = CamHubTcpVideoSender()
    private lazy var audioServer = CamHubCameraAudioServer { [weak self] host in
        guard let self else { return nil }
        return self.sessionKey(for: host)
    }
    private var listener: NWListener?
    private var heartbeatTimer: DispatchSourceTimer?
    private var identity: CamHubLocalIdentity?
    private var udpPort: UInt16 = 0
    private var tcpPort: UInt16 = 0
    private var audioPort: UInt16 = 0
    private var starting = false
    private var sourceGeneration: UInt64 = 0
    private var clients: [UUID: ClientState] = [:]
    private var udpSubscribedClients = Set<UUID>()
    private var tcpSubscribedClients = Set<UUID>()
    private var subscribedClients: Set<UUID> { udpSubscribedClients.union(tcpSubscribedClients) }
    private var pairingFailures: [String: (startedAt: TimeInterval, count: Int)] = [:]
    private var acceptsVideo = false
    private var maxRemotePtzZoom: CGFloat = 1
    var requestKeyFrame: (() -> Void)?
    var onStreamingStateChanged: ((Bool) -> Void)?
    var onBitrateChanged: ((Int) -> Void)?
    var onZoomRequested: ((CGFloat) -> Void)?
    var onPtzRequested: ((UUID, CGFloat, CGFloat, CGFloat, UInt64) -> Void)?

    func updateRemotePtzCapability(maximumZoom: CGFloat) {
        queue.async {
            let supported = maximumZoom.isFinite && maximumZoom > 1.001
            self.maxRemotePtzZoom = supported ? min(4, maximumZoom) : 1
            var capabilities = ControlMessage(type: "capabilities")
            capabilities.supportsRemotePtz = supported
            capabilities.minZoomRatio = 1
            capabilities.maxZoomRatio = Float(self.maxRemotePtzZoom)
            for (id, client) in self.clients where client.sessionKey != nil {
                self.send(capabilities, to: id)
            }
        }
    }

    func acknowledgeRemotePtz(clientId: UUID, requestId: UInt64,
                              zoom: CGFloat, centerX: CGFloat, centerY: CGFloat) {
        guard requestId > 0 else { return }
        queue.async {
            guard self.clients[clientId]?.sessionKey != nil else { return }
            var acknowledgement = ControlMessage(type: "ptz_applied")
            acknowledgement.requestId = requestId
            acknowledgement.value = Float(zoom)
            acknowledgement.stringValue = "\(centerX),\(centerY)"
            self.send(acknowledgement, to: clientId)
        }
    }

    init() {
        videoSender.onKeyFrameRequired = { [weak self] in self?.requestKeyFrame?() }
    }

    func start() {
        queue.async {
            guard self.listener == nil, !self.starting else { return }
            self.starting = true
            self.sourceGeneration &+= 1
            let generation = self.sourceGeneration
            self.updatePublishedState {
                $0.status = "STARTING"
                $0.lastError = nil
            }
            do {
                let localIdentity = try self.identityStore.loadOrCreate()
                guard let serverIdentity = localIdentity.secIdentity else {
                    throw SourceError.identityUnavailable
                }
                let udpPort = try self.videoSender.start()
                self.identity = localIdentity
                self.udpPort = udpPort
                var startupAudioPort: UInt16?
                var startupTcpPort: UInt16?
                var controlListenerStarted = false
                func startControlListenerWhenReady() {
                    guard !controlListenerStarted,
                          let audioPort = startupAudioPort,
                          let tcpPort = startupTcpPort,
                          self.sourceGeneration == generation,
                          self.starting else { return }
                    controlListenerStarted = true
                    self.audioPort = audioPort
                    self.tcpPort = tcpPort
                    self.startControlListener(
                        localIdentity: localIdentity,
                        serverIdentity: serverIdentity,
                        udpPort: udpPort,
                        tcpPort: tcpPort,
                        generation: generation
                    )
                }
                self.tcpVideoSender.onKeyFrameRequired = { [weak self] in self?.requestKeyFrame?() }
                self.tcpVideoSender.start(
                    resolveSession: { [weak self] token, completion in
                        guard let self else { completion(nil); return }
                        self.queue.async {
                            let matches = self.clients.compactMap { id, client -> CamHubAuthorizedVideoSession? in
                                guard client.tcpAuthToken == token, let key = client.sessionKey else { return nil }
                                return CamHubAuthorizedVideoSession(clientID: id, sessionKey: key)
                            }
                            completion(matches.count == 1 ? matches[0] : nil)
                        }
                    },
                    onSubscriberChange: { [weak self] clientID, active in
                        guard let self else { return }
                        self.queue.async {
                            guard self.sourceGeneration == generation,
                                  self.clients[clientID]?.sessionKey != nil else { return }
                            let wasStreaming = !self.subscribedClients.isEmpty
                            if active { self.tcpSubscribedClients.insert(clientID) }
                            else { self.tcpSubscribedClients.remove(clientID) }
                            let isStreaming = !self.subscribedClients.isEmpty
                            if wasStreaming != isStreaming { self.onStreamingStateChanged?(isStreaming) }
                            if wasStreaming != isStreaming { self.updateBitrate() }
                        }
                    },
                    completion: { [weak self] result in
                        guard let self else { return }
                        self.queue.async {
                            guard self.sourceGeneration == generation, self.starting else { return }
                            switch result {
                            case let .success(port): startupTcpPort = port
                            case let .failure(error):
                                startupTcpPort = 0
                                self.updatePublishedState { $0.lastError = "TCP fallback unavailable: \(error.localizedDescription)" }
                            }
                            startControlListenerWhenReady()
                        }
                    }
                )
                self.audioServer.start { [weak self] result in
                    guard let self else { return }
                    self.queue.async {
                        guard self.sourceGeneration == generation, self.starting else { return }
                        startupAudioPort = (try? result.get()) ?? 0
                        startControlListenerWhenReady()
                    }
                }
            } catch {
                self.failStart(error)
            }
        }
    }

    func stop() {
        // Capture callbacks can still arrive while VideoToolbox drains, but no new frame may
        // enter the UDP sender after this synchronous gate closes.
        setAcceptsVideo(false)
        queue.async {
            self.sourceGeneration &+= 1
            self.starting = false
            self.setAcceptsVideo(false)
            self.heartbeatTimer?.cancel()
            self.heartbeatTimer = nil
            self.listener?.cancel()
            self.listener = nil
            self.audioServer.stop()
            self.audioPort = 0
            self.tcpPort = 0
            for (id, client) in self.clients {
                self.videoSender.unsubscribe(clientID: id)
                self.tcpVideoSender.unsubscribe(clientID: id)
                client.connection.cancel()
            }
            self.clients.removeAll()
            self.udpSubscribedClients.removeAll()
            self.tcpSubscribedClients.removeAll()
            self.onStreamingStateChanged?(false)
            self.updateBitrate()
            self.videoSender.stop()
            self.tcpVideoSender.stop()
            self.updatePublishedState {
                $0.status = "OFFLINE"
                $0.connectedHubs = []
            }
        }
    }

    func publish(_ accessUnit: CamHubEncodedAccessUnit) {
        videoPublishLock.lock()
        guard acceptsVideo else {
            videoPublishLock.unlock()
            return
        }
        videoSender.publish(accessUnit)
        tcpVideoSender.publish(accessUnit)
        videoPublishLock.unlock()
    }

    func publishAudio(_ payload: Data) {
        videoPublishLock.lock()
        let canPublish = acceptsVideo
        videoPublishLock.unlock()
        guard canPublish else { return }
        audioServer.publish(payload)
    }

    private func sessionKey(for host: NWEndpoint.Host) -> Data? {
        let remoteHost = String(describing: host)
        return queue.sync {
            let keys = clients.values.compactMap { client -> Data? in
                client.remoteHost == remoteHost ? client.sessionKey : nil
            }
            // A remote IP can map to only one active encrypted audio stream. If multiple
            // hubs share a source address, keep video working and decline ambiguous audio.
            return keys.count == 1 ? keys[0] : nil
        }
    }

    private func startControlListener(localIdentity: CamHubLocalIdentity,
                                      serverIdentity: sec_identity_t,
                                      udpPort: UInt16,
                                      tcpPort: UInt16,
                                      generation: UInt64) {
        guard sourceGeneration == generation, starting else { return }
        do {
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, serverIdentity)
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
            let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
            let listener = try NWListener(using: parameters)
            listener.service = NWListener.Service(
                name: "CamHub_\(Self.cameraName(for: localIdentity))",
                type: "_camhub._tcp"
            )
            listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                guard let self, let listener, self.listener === listener,
                      self.sourceGeneration == generation else { return }
                switch state {
                case .ready:
                    self.starting = false
                    self.updatePublishedState {
                        $0.status = "DISCOVERABLE · UDP \(udpPort) · TCP \(tcpPort)"
                        $0.pairingPIN = localIdentity.pairingPIN
                        $0.fingerprint = localIdentity.certificateFingerprint
                        $0.cameraName = Self.cameraName(for: localIdentity)
                        $0.lastError = nil
                    }
                case let .failed(error):
                    self.setAcceptsVideo(false)
                    listener.cancel()
                    self.listener = nil
                    self.starting = false
                    self.audioServer.stop()
                    self.audioPort = 0
                    self.tcpPort = 0
                    self.heartbeatTimer?.cancel()
                    self.heartbeatTimer = nil
                    for (id, client) in self.clients {
                        self.videoSender.unsubscribe(clientID: id)
                        self.tcpVideoSender.unsubscribe(clientID: id)
                        client.connection.cancel()
                    }
                    self.clients.removeAll()
                    self.udpSubscribedClients.removeAll()
                    self.tcpSubscribedClients.removeAll()
                    self.onStreamingStateChanged?(false)
                    self.updateBitrate()
                    self.videoSender.stop()
                    self.tcpVideoSender.stop()
                    self.updatePublishedState {
                        $0.status = "NETWORK ERROR"
                        $0.connectedHubs = []
                        $0.lastError = error.localizedDescription
                    }
                case .cancelled:
                    self.updatePublishedState { $0.status = "OFFLINE" }
                default: break
                }
            }
            self.listener = listener
            self.setAcceptsVideo(true)
            listener.start(queue: queue)
            let heartbeat = DispatchSource.makeTimerSource(queue: queue)
            heartbeat.schedule(deadline: .now() + 2, repeating: 2)
            heartbeat.setEventHandler { [weak self] in self?.expireSilentClients() }
            heartbeat.resume()
            heartbeatTimer = heartbeat
        } catch {
            failStart(error)
        }
    }

    private func failStart(_ error: Error) {
        starting = false
        setAcceptsVideo(false)
        audioServer.stop()
        audioPort = 0
        videoSender.stop()
        tcpVideoSender.stop()
        updatePublishedState {
            $0.status = "START FAILED"
            $0.lastError = error.localizedDescription
        }
    }

    private func accept(_ connection: NWConnection) {
        guard clients.count < 4 else {
            connection.cancel()
            return
        }
        let id = UUID()
        let remoteAddress = remoteHostAddress(connection)
        clients[id] = ClientState(
            connection: connection,
            remoteHost: remoteAddress.map { String(describing: $0) } ?? "",
            remoteAddress: remoteAddress
        )
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.receiveNext(id)
            case .failed, .cancelled:
                self.removeClient(id)
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveNext(_ id: UUID) {
        guard let client = clients[id] else { return }
        client.connection.receive(minimumIncompleteLength: 1, maximumLength: 8_192) { [weak self] data, _, complete, error in
            guard let self, self.clients[id] != nil else { return }
            if let data, !data.isEmpty {
                self.clients[id]?.receiveBuffer.append(data)
                if self.clients[id]?.receiveBuffer.count ?? 0 > 32_768 {
                    self.removeClient(id)
                    return
                }
                self.consumeCompleteLines(id)
            }
            if error != nil || complete {
                self.removeClient(id)
            } else {
                self.receiveNext(id)
            }
        }
    }

    private func consumeCompleteLines(_ id: UUID) {
        while var client = clients[id],
              let newline = client.receiveBuffer.firstIndex(of: 0x0A) {
            let line = client.receiveBuffer.prefix(upTo: newline)
            let next = client.receiveBuffer.index(after: newline)
            client.receiveBuffer.removeSubrange(..<next)
            client.lastMessageAt = ProcessInfo.processInfo.systemUptime
            clients[id] = client
            guard line.count <= 4_096,
                  let message = try? JSONDecoder().decode(ControlMessage.self, from: Data(line)) else {
                removeClient(id)
                return
            }
            handle(message, from: id)
        }
    }

    private func handle(_ message: ControlMessage, from id: UUID) {
        switch message.type {
        case "hello":
            handleHello(message, from: id)
        case "ping":
            var response = ControlMessage(type: "pong")
            response.syncId = message.syncId
            response.t0WallMs = message.t0WallMs
            response.t1WallMs = wallClockMs()
            response.t2WallMs = wallClockMs()
            send(response, to: id)
        case "command":
            handleCommand(message, from: id)
        default:
            break
        }
    }

    private func handleHello(_ message: ControlMessage, from id: UUID) {
        guard let existing = clients[id] else { return }
        if existing.sessionKey != nil { removeClient(id); return }
        if let client = clients[id], let failures = pairingFailures[client.remoteHost],
           ProcessInfo.processInfo.systemUptime - failures.startedAt < 60, failures.count >= 5 {
            var error = ControlMessage(type: "error")
            error.stringValue = "pairing_rate_limited"
            send(error, to: id)
            return
        }
        guard var client = clients[id], client.deviceName.isEmpty,
              let identity, constantTimePINMatch(message.pin, identity.pairingPIN) else {
            guard let client = clients[id] else { return }
            let host = client.remoteHost
            let current = pairingFailures[host]
            let age = current.map { ProcessInfo.processInfo.systemUptime - $0.startedAt } ?? .infinity
            pairingFailures[host] = age < 60
                ? (current?.startedAt ?? ProcessInfo.processInfo.systemUptime, (current?.count ?? 0) + 1)
                : (ProcessInfo.processInfo.systemUptime, 1)
            var error = ControlMessage(type: "error")
            error.stringValue = "invalid_pairing_pin"
            send(error, to: id)
            return
        }
        var key = Data(repeating: 0, count: 32)
        guard key.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }) == errSecSuccess else {
            removeClient(id)
            return
        }
        var tcpAuthToken = Data(repeating: 0, count: 32)
        guard tcpAuthToken.withUnsafeMutableBytes({
            SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
        }) == errSecSuccess else {
            removeClient(id)
            return
        }
        client.deviceName = message.deviceName.isEmpty ? "CamHub Hub" : message.deviceName
        client.sessionKey = key
        client.tcpAuthToken = tcpAuthToken
        client.lastMessageAt = ProcessInfo.processInfo.systemUptime
        clients[id] = client
        pairingFailures.removeValue(forKey: client.remoteHost)
        var acknowledgement = ControlMessage(type: "hello_ack")
        acknowledgement.deviceName = Self.cameraName(for: identity)
        acknowledgement.udpStreamPort = Int(udpPort)
        acknowledgement.tcpStreamPort = Int(tcpPort)
        acknowledgement.supportsAuthenticatedTcp = tcpPort > 0
        acknowledgement.tcpAuthToken = tcpPort > 0 ? tcpAuthToken.base64EncodedString() : ""
        acknowledgement.audioStreamPort = Int(audioPort)
        acknowledgement.sessionKey = key.base64EncodedString()
        acknowledgement.supportsRemotePtz = self.maxRemotePtzZoom > 1.001
        acknowledgement.minZoomRatio = 1
        acknowledgement.maxZoomRatio = Float(self.maxRemotePtzZoom)
        send(acknowledgement, to: id)
        updatePublishedState { state in
            if !state.connectedHubs.contains(client.deviceName) { state.connectedHubs.append(client.deviceName) }
        }
    }

    private func handleCommand(_ message: ControlMessage, from id: UUID) {
        guard let client = clients[id], let sessionKey = client.sessionKey else { return }
        switch message.command {
        case "udp_subscribe":
            guard message.udpReceivePort > 0, message.udpReceivePort <= 65_535,
                  let remoteAddress = client.remoteAddress else { return }
            videoSender.subscribe(
                clientID: id,
                host: remoteAddress,
                port: UInt16(message.udpReceivePort),
                sessionKey: sessionKey
            )
            let wasStreaming = !subscribedClients.isEmpty
            udpSubscribedClients.insert(id)
            if !wasStreaming { onStreamingStateChanged?(true) }
            updateBitrate()
            requestKeyFrame?()
        case "udp_unsubscribe":
            videoSender.unsubscribe(clientID: id)
            let wasStreaming = !subscribedClients.isEmpty
            let wasSubscribed = udpSubscribedClients.remove(id) != nil
            let isStreaming = !subscribedClients.isEmpty
            if wasStreaming != isStreaming { onStreamingStateChanged?(isStreaming) }
            if wasSubscribed { updateBitrate() }
        case "set_stream_bitrate":
            // A hub may configure the stream before its UDP subscription arrives.
            guard let request = CamHubBitratePolicy.validatedRequest(message.value) else { return }
            clients[id]?.requestedBitrateMbps = request
            if subscribedClients.contains(id) { updateBitrate() }
        case "set_zoom":
            guard message.value.isFinite, message.value > 0 else { return }
            onZoomRequested?(CGFloat(message.value))
        case "set_ptz":
            let coordinates = message.stringValue.split(separator: ",", omittingEmptySubsequences: false)
            guard message.requestId > 0, message.value.isFinite,
                  coordinates.count == 2,
                  let centerX = Double(coordinates[0]), let centerY = Double(coordinates[1]),
                  centerX.isFinite, centerY.isFinite,
                  self.maxRemotePtzZoom > 1.001 else { return }
            onPtzRequested?(id, CGFloat(message.value), CGFloat(centerX), CGFloat(centerY), message.requestId)
        case "request_key_frame":
            guard subscribedClients.contains(id) else { return }
            let now = DispatchTime.now().uptimeNanoseconds
            guard client.lastRecoveryRequestNanos == 0 ||
                    now &- client.lastRecoveryRequestNanos >= 250_000_000 else { return }
            clients[id]?.lastRecoveryRequestNanos = now
            videoSender.resendCodecConfig(clientID: id)
            requestKeyFrame?()
        default:
            break
        }
    }

    private func send(_ message: ControlMessage, to id: UUID) {
        guard let connection = clients[id]?.connection,
              var bytes = try? JSONEncoder().encode(message) else { return }
        bytes.append(0x0A)
        connection.send(content: bytes, completion: .contentProcessed { [weak self] error in
            if error != nil {
                self?.queue.async { [weak self] in self?.removeClient(id) }
            }
        })
    }

    private func removeClient(_ id: UUID) {
        guard let client = clients.removeValue(forKey: id) else { return }
        if !client.remoteHost.isEmpty { audioServer.removePeer(remoteHost: client.remoteHost) }
        videoSender.unsubscribe(clientID: id)
        tcpVideoSender.unsubscribe(clientID: id)
        let wasStreaming = !subscribedClients.isEmpty
        let hadUdpSubscription = udpSubscribedClients.remove(id) != nil
        let hadTcpSubscription = tcpSubscribedClients.remove(id) != nil
        let wasSubscribed = hadUdpSubscription || hadTcpSubscription
        let isStreaming = !subscribedClients.isEmpty
        if wasStreaming != isStreaming { onStreamingStateChanged?(isStreaming) }
        if wasSubscribed { updateBitrate() }
        client.connection.cancel()
        let nameStillConnected = clients.values.contains { $0.deviceName == client.deviceName }
        if !nameStillConnected {
            updatePublishedState { state in
                state.connectedHubs.removeAll { $0 == client.deviceName }
            }
        }
    }

    private func updateBitrate() {
        let requested = subscribedClients.map { clients[$0]?.requestedBitrateMbps }
        onBitrateChanged?(CamHubBitratePolicy.selectedMbps(requested))
    }

    private static func cameraName(for identity: CamHubLocalIdentity) -> String {
        "iOS-" + String(identity.certificateFingerprint.prefix(12))
    }

    private func expireSilentClients() {
        let now = ProcessInfo.processInfo.systemUptime
        let cutoff = now - 20
        let failureCutoff = now - 60
        let unauthenticatedCutoff = failureCutoff
        pairingFailures = pairingFailures.filter { $0.value.startedAt >= failureCutoff }
        let expired = clients.compactMap { id, client in
            (client.lastMessageAt < cutoff ||
             (client.sessionKey == nil && client.connectedAt < unauthenticatedCutoff)) ? id : nil
        }
        expired.forEach(removeClient)
    }

    private func updatePublishedState(_ update: @escaping (CamHubCameraSourceServer) -> Void) {
        DispatchQueue.main.async { update(self) }
    }

    private func setAcceptsVideo(_ enabled: Bool) {
        videoPublishLock.lock()
        acceptsVideo = enabled
        videoPublishLock.unlock()
    }

    private func remoteHostAddress(_ connection: NWConnection) -> NWEndpoint.Host? {
        guard case let .hostPort(host, _) = connection.endpoint else { return nil }
        return host
    }

    private func constantTimePINMatch(_ candidate: String, _ expected: String) -> Bool {
        let lhs = Array(candidate.utf8)
        let rhs = Array(expected.utf8)
        guard lhs.count == 8, rhs.count == 8 else { return false }
        var difference: UInt8 = 0
        for index in 0..<8 { difference |= lhs[index] ^ rhs[index] }
        return difference == 0
    }

    private func wallClockMs() -> UInt64 { UInt64(Date().timeIntervalSince1970 * 1_000) }

    private enum SourceError: LocalizedError {
        case identityUnavailable
        var errorDescription: String? { "CamHub could not create a TLS server identity." }
    }
}
