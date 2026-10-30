import CamHubCore
import Foundation
import Network

/// Publishes the camera's PCM audio to paired hubs over a bounded encrypted TCP stream.
final class CamHubCameraAudioServer {
    private final class Client {
        let connection: NWConnection
        let remoteHost: String
        let cipher: CamHubFrameCipher
        var ready = false
        var sending = false
        var pendingPayload: Data?

        init(connection: NWConnection, remoteHost: String, cipher: CamHubFrameCipher) {
            self.connection = connection
            self.remoteHost = remoteHost
            self.cipher = cipher
        }
    }

    private let queue = DispatchQueue(label: "com.camhub.camera.audio-server", qos: .userInitiated)
    private let sessionKeyForHost: (NWEndpoint.Host) -> Data?
    private var listener: NWListener?
    private var clients: [UUID: Client] = [:]

    init(sessionKeyForHost: @escaping (NWEndpoint.Host) -> Data?) {
        self.sessionKeyForHost = sessionKeyForHost
    }

    func start(completion: @escaping (Result<UInt16, Error>) -> Void) {
        queue.async {
            guard self.listener == nil else {
                if let port = self.listener?.port?.rawValue { completion(.success(port)) }
                return
            }
            do {
                let tcp = NWProtocolTCP.Options()
                tcp.noDelay = true
                let listener = try NWListener(using: NWParameters(tls: nil, tcp: tcp))
                self.listener = listener
                var reportedReady = false
                listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
                listener.stateUpdateHandler = { [weak self, weak listener] state in
                    guard let self, let listener, self.listener === listener else { return }
                    switch state {
                    case .ready:
                        guard !reportedReady, let port = listener.port?.rawValue else { return }
                        reportedReady = true
                        completion(.success(port))
                    case let .failed(error):
                        self.listener = nil
                        listener.cancel()
                        if !reportedReady { completion(.failure(error)) }
                    default: break
                    }
                }
                listener.start(queue: self.queue)
            } catch { completion(.failure(error)) }
        }
    }

    func publish(_ payload: Data) {
        guard !payload.isEmpty, payload.count <= 64 * 1024 else { return }
        queue.async {
            for client in self.clients.values {
                client.pendingPayload = payload
                self.sendNextIfReady(client)
            }
        }
    }

    func removePeer(remoteHost: String) {
        queue.async {
            let ids = self.clients.compactMap { $0.value.remoteHost == remoteHost ? $0.key : nil }
            ids.forEach(self.removeClient)
        }
    }

    func stop() {
        queue.async {
            self.listener?.stateUpdateHandler = nil
            self.listener?.cancel()
            self.listener = nil
            Array(self.clients.keys).forEach(self.removeClient)
        }
    }

    private func accept(_ connection: NWConnection) {
        guard case let .hostPort(host, _) = connection.endpoint,
              let key = sessionKeyForHost(host), let cipher = try? CamHubFrameCipher(key: key) else {
            connection.cancel()
            return
        }
        let id = UUID()
        let client = Client(connection: connection, remoteHost: String(describing: host), cipher: cipher)
        clients[id] = client
        connection.stateUpdateHandler = { [weak self, weak client] state in
            guard let self, let client, self.clients[id] === client else { return }
            switch state {
            case .ready:
                client.ready = true
                self.sendNextIfReady(client)
            case .failed, .cancelled:
                self.removeClient(id)
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func sendNextIfReady(_ client: Client) {
        guard client.ready, !client.sending, let payload = client.pendingPayload,
              let id = clients.first(where: { $0.value === client })?.key else { return }
        client.pendingPayload = nil
        client.sending = true
        do {
            let encrypted = try client.cipher.encrypt(payload)
            var packet = Data(capacity: 4 + encrypted.count)
            let size = UInt32(encrypted.count)
            packet.append(UInt8((size >> 24) & 0xff))
            packet.append(UInt8((size >> 16) & 0xff))
            packet.append(UInt8((size >> 8) & 0xff))
            packet.append(UInt8(size & 0xff))
            packet.append(encrypted)
            client.connection.send(content: packet, completion: .contentProcessed { [weak self, weak client] error in
                guard let self, let client else { return }
                self.queue.async {
                    guard self.clients[id] === client else { return }
                    if error != nil {
                        self.removeClient(id)
                        return
                    }
                    client.sending = false
                    self.sendNextIfReady(client)
                }
            })
        } catch { removeClient(id) }
    }

    private func removeClient(_ id: UUID) {
        guard let client = clients.removeValue(forKey: id) else { return }
        client.connection.stateUpdateHandler = nil
        client.connection.cancel()
    }
}
