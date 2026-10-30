import CamHubCore
import CoreMedia
import Foundation
import Network

/// Match the same IPv4 peer whether Network.framework reports it as IPv4 or mapped IPv6.
func camHubHostsMatch(_ expected: NWEndpoint.Host, _ actual: NWEndpoint.Host) -> Bool {
    func ipv4Bytes(_ host: NWEndpoint.Host) -> Data? {
        switch host {
        case let .ipv4(address): return address.rawValue
        case let .ipv6(address) where address.isIPv4Mapped: return address.asIPv4?.rawValue
        default: return nil
        }
    }
    let expectedV4 = ipv4Bytes(expected)
    let actualV4 = ipv4Bytes(actual)
    if expectedV4 != nil || actualV4 != nil {
        return expectedV4 != nil && expectedV4 == actualV4
    }
    if case let .ipv6(left) = expected, case let .ipv6(right) = actual {
        guard left.rawValue == right.rawValue else { return false }
        if left.isLinkLocal, let leftInterface = left.interface, let rightInterface = right.interface {
            return leftInterface == rightInterface
        }
        return true
    }
    return expected == actual
}

/// One receiver per authenticated camera. All network and assembly work stays on its serial queue.
final class CamHubUdpVideoReceiver {
    private let queue = DispatchQueue(label: "com.camhub.hub.udp-receive", qos: .userInteractive)
    private let expectedHost: NWEndpoint.Host
    private let expectedPort: UInt16
    private let ingress: CamHubVideoIngress
    private let onFrame: (CamHubVideoPayload) -> Void
    private let onError: (Error) -> Void
    private let onDroppedFrame: () -> Void
    private var listener: NWListener?
    private var connection: NWConnection?
    private var expiryTimer: DispatchSourceTimer?
    private var startupTimer: DispatchSourceTimer?

    init(expectedHost: NWEndpoint.Host, expectedPort: UInt16, sessionKey: Data,
         onFrame: @escaping (CamHubVideoPayload) -> Void, onDroppedFrame: @escaping () -> Void,
         onError: @escaping (Error) -> Void) throws {
        self.expectedHost = expectedHost
        self.expectedPort = expectedPort
        self.ingress = try CamHubVideoIngress(sessionKey: sessionKey)
        self.onFrame = onFrame
        self.onDroppedFrame = onDroppedFrame
        self.onError = onError
    }

    func start(onReady: @escaping (UInt16) -> Void) {
        queue.async {
            guard self.listener == nil else { return }
            do {
                let listener = try NWListener(using: .udp, on: .any)
                self.listener = listener
                let timer = DispatchSource.makeTimerSource(queue: self.queue)
                timer.schedule(deadline: .now() + 10)
                timer.setEventHandler { [weak self, weak listener] in
                    guard let self, let listener, self.listener === listener else { return }
                    self.onError(NSError(domain: "CamHubUdpVideoReceiver", code: 1,
                                         userInfo: [NSLocalizedDescriptionKey: "Video receiver startup timed out"]))
                    self.closeOnQueue()
                }
                self.startupTimer = timer
                timer.resume()
                listener.stateUpdateHandler = { [weak self, weak listener] state in
                    guard let self, let listener, self.listener === listener else { return }
                    switch state {
                    case .ready:
                        self.startupTimer?.cancel()
                        self.startupTimer = nil
                        guard let port = listener.port else {
                            self.onError(NSError(domain: "CamHubUdpVideoReceiver", code: 2,
                                                 userInfo: [NSLocalizedDescriptionKey: "Video receiver has no port"]))
                            self.closeOnQueue()
                            return
                        }
                        onReady(port.rawValue)
                    case let .failed(error):
                        self.onError(error)
                        self.closeOnQueue()
                    default: break
                    }
                }
                listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
                listener.start(queue: self.queue)
            } catch { self.onError(error) }
        }
    }

    func stop() { queue.async { self.closeOnQueue() } }

    func perform(_ action: @escaping () -> Void) { queue.async(execute: action) }

    private func accept(_ incoming: NWConnection) {
        guard case let .hostPort(host, port) = incoming.endpoint,
              camHubHostsMatch(expectedHost, host), expectedPort == 0 || port.rawValue == expectedPort,
              connection == nil else { incoming.cancel(); return }
        connection = incoming
        incoming.stateUpdateHandler = { [weak self, weak incoming] state in
            guard let self, let incoming, self.connection === incoming else { return }
            switch state {
            case .ready: self.receive(incoming)
            case let .failed(error): self.onError(error); self.connection = nil; incoming.cancel()
            case .cancelled: self.connection = nil
            default: break
            }
        }
        incoming.start(queue: queue)
    }

    private func receive(_ current: NWConnection) {
        current.receiveMessage { [weak self, weak current] data, _, _, error in
            guard let self, let current, self.connection === current else { return }
            if let error { self.onError(error); self.connection = nil; current.cancel(); return }
            if let data, !data.isEmpty {
                let now = DispatchTime.now().uptimeNanoseconds / 1_000_000
                let droppedBefore = self.ingress.droppedFrameCount
                let result = self.ingress.offer(data, nowMs: now)
                if self.ingress.pendingFrameCount > 0 { self.startExpiryTimer() }
                if self.ingress.droppedFrameCount != droppedBefore { self.onDroppedFrame() }
                if case let .frame(frame) = result { self.onFrame(frame) }
            }
            self.receive(current)
        }
    }

    private func closeOnQueue() {
        startupTimer?.cancel()
        startupTimer = nil
        expiryTimer?.cancel()
        expiryTimer = nil
        connection?.cancel()
        connection = nil
        listener?.cancel()
        listener = nil
    }

    private func startExpiryTimer() {
        guard expiryTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(20), repeating: .milliseconds(20))
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let now = DispatchTime.now().uptimeNanoseconds / 1_000_000
            if self.ingress.expire(nowMs: now) > 0 { self.onDroppedFrame() }
            if self.ingress.pendingFrameCount == 0 {
                self.expiryTimer?.cancel()
                self.expiryTimer = nil
            }
        }
        expiryTimer = timer
        timer.resume()
    }
}

/// Connects a single camera's encrypted UDP input to display-ready H.264 samples.
final class CamHubHubVideoStream {
    private let receiver: CamHubUdpVideoReceiver
    private let builder: CamHubH264SampleBuilder

    init(expectedHost: NWEndpoint.Host, expectedPort: UInt16, sessionKey: Data,
         onSample: @escaping (CMSampleBuffer, Int, UInt64?) -> Bool,
         onRecoveryRequired: @escaping () -> Void,
         onError: @escaping (Error) -> Void) throws {
        let builder = CamHubH264SampleBuilder()
        self.builder = builder
        let sequence = CamHubFrameSequenceTracker()
        var lastRecoveryAt: UInt64 = 0
        func requestRecovery() {
            let now = DispatchTime.now().uptimeNanoseconds
            if lastRecoveryAt == 0 || now &- lastRecoveryAt >= 250_000_000 {
                lastRecoveryAt = now
                onRecoveryRequired()
            }
        }
        receiver = try CamHubUdpVideoReceiver(
            expectedHost: expectedHost, expectedPort: expectedPort, sessionKey: sessionKey,
            onFrame: { frame in
                if !frame.isCodecConfig {
                    switch sequence.observe(frame.frameSequence) {
                    case .late: return
                    case .gap:
                        builder.requireKeyFrame()
                        requestRecovery()
                    default: break
                    }
                }
                let timestamp = CMClockGetTime(CMClockGetHostTimeClock())
                if let sample = builder.makeSample(frame.h264Bytes, presentationTime: timestamp) {
                    if !onSample(sample, frame.rotationDegrees, frame.captureAtWallMs) {
                        builder.requireKeyFrame()
                        requestRecovery()
                    }
                } else if !frame.isCodecConfig && builder.needsKeyFrame {
                    requestRecovery()
                }
            },
            onDroppedFrame: {
                builder.requireKeyFrame()
                requestRecovery()
            },
            onError: onError
        )
    }

    func start(onReady: @escaping (UInt16) -> Void) { receiver.start(onReady: onReady) }
    func stop() { receiver.stop() }
    func requireKeyFrame() { receiver.perform { self.builder.requireKeyFrame() } }
}
