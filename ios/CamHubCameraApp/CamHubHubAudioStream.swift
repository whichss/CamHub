import AVFoundation
import CamHubCore
import Combine
import CoreMedia
import Foundation
import Network

/// Receives the Android-compatible length-prefixed, AES-GCM protected PCM stream.
final class CamHubHubAudioStream {
    private static let maximumFrameSize = 64 * 1024
    private let queue = DispatchQueue(label: "com.camhub.hub.audio-receive", qos: .userInitiated)
    private let endpoint: NWEndpoint
    private let cipher: CamHubFrameCipher
    private let onFrame: (CamHubPCMFrame, CMTime) -> Void
    private let onStatus: (String) -> Void
    private var connection: NWConnection?
    private var reconnectTimer: DispatchSourceTimer?
    private var receiveBuffer = Data()
    private var retryAttempt = 0
    private var stopped = false

    init(host: NWEndpoint.Host, port: UInt16, sessionKey: Data,
         onFrame: @escaping (CamHubPCMFrame, CMTime) -> Void,
         onStatus: @escaping (String) -> Void) throws {
        guard port > 0 else { throw CamHubAudioPayloadError.invalidHeader }
        guard let endpointPort = NWEndpoint.Port(rawValue: port) else { throw CamHubAudioPayloadError.invalidHeader }
        endpoint = .hostPort(host: host, port: endpointPort)
        cipher = try CamHubFrameCipher(key: sessionKey)
        self.onFrame = onFrame
        self.onStatus = onStatus
    }

    func start() { queue.async { self.connectOnQueue() } }
    func stop() { queue.async { self.closeOnQueue() } }

    private func connectOnQueue() {
        guard !stopped, connection == nil else { return }
        receiveBuffer.removeAll(keepingCapacity: true)
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        let current = NWConnection(to: endpoint, using: parameters)
        connection = current
        current.stateUpdateHandler = { [weak self, weak current] state in
            guard let self, let current, self.connection === current, !self.stopped else { return }
            switch state {
            case .ready:
                self.retryAttempt = 0
                self.onStatus("AUDIO CONNECTED")
                self.receive(current)
            case .failed(_):
                self.connection = nil
                current.cancel()
                self.scheduleReconnect()
            case .cancelled:
                if !self.stopped {
                    self.connection = nil
                    self.scheduleReconnect()
                }
            default: break
            }
        }
        current.start(queue: queue)
    }

    private func receive(_ current: NWConnection) {
        current.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self, weak current] data, _, complete, error in
            guard let self, let current, self.connection === current, !self.stopped else { return }
            if let data, !data.isEmpty {
                self.receiveBuffer.append(data)
                self.drainFrames()
            }
            if error != nil || complete {
                self.connection = nil
                current.cancel()
                self.scheduleReconnect()
            } else {
                self.receive(current)
            }
        }
    }

    private func drainFrames() {
        while receiveBuffer.count >= 4 {
            let size = receiveBuffer.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            guard size >= 28, size <= Self.maximumFrameSize else {
                connection?.cancel()
                connection = nil
                receiveBuffer.removeAll(keepingCapacity: true)
                scheduleReconnect()
                return
            }
            let frameSize = 4 + Int(size)
            guard receiveBuffer.count >= frameSize else { return }
            let encrypted = receiveBuffer.subdata(in: 4..<frameSize)
            receiveBuffer.removeSubrange(0..<frameSize)
            guard let plaintext = try? cipher.decrypt(encrypted),
                  let frame = try? CamHubAudioPayloadDecoder.decode(plaintext) else { continue }
            onFrame(frame, CMClockGetTime(CMClockGetHostTimeClock()))
        }
    }

    private func scheduleReconnect() {
        guard !stopped, reconnectTimer == nil else { return }
        onStatus("AUDIO RECONNECTING")
        let delaySeconds = min(30, 1 << min(retryAttempt, 5))
        retryAttempt += 1
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .seconds(delaySeconds))
        timer.setEventHandler { [weak self, weak timer] in
            guard let self else { return }
            timer?.setEventHandler {}
            timer?.cancel()
            self.reconnectTimer = nil
            self.connectOnQueue()
        }
        reconnectTimer = timer
        timer.resume()
    }

    private func closeOnQueue() {
        stopped = true
        reconnectTimer?.setEventHandler {}
        reconnectTimer?.cancel()
        reconnectTimer = nil
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        receiveBuffer.removeAll(keepingCapacity: false)
    }
}

/// Plays only the current PGM audio and crossfades the AUTO target with video.
final class CamHubHubAudioMixer: ObservableObject {
    @Published private(set) var isMuted = true
    @Published private(set) var masterVolume = 0.75
    @Published private(set) var status = "AUDIO OFF"

    private final class Channel {
        let format: AVAudioFormat
        let player: AVAudioPlayerNode
        var queuedBuffers = 0
        var scheduleGeneration: UInt64 = 0

        init(format: AVAudioFormat, player: AVAudioPlayerNode) {
            self.format = format
            self.player = player
        }
    }

    private let queue = DispatchQueue(label: "com.camhub.hub.audio-mixer", qos: .userInitiated)
    private let pendingPackets = DispatchSemaphore(value: 6)
    private let engine = AVAudioEngine()
    private var channels: [String: Channel] = [:]
    private var programID: String?
    private var transitionID: String?
    private var transitionProgress = 0.0
    private var muted = true
    private var volume = 0.75

    func setMuted(_ value: Bool) {
        DispatchQueue.main.async { self.isMuted = value }
        queue.async { self.muted = value; self.updateVolumes() }
    }

    func setMasterVolume(_ value: Double) {
        let normalized = value.isFinite ? min(1, max(0, value)) : 0
        DispatchQueue.main.async { self.masterVolume = normalized }
        queue.async { self.volume = normalized; self.updateVolumes() }
    }

    func setComposition(programID: String?, transitionID: String?, progress: Double) {
        queue.async {
            self.programID = programID
            self.transitionID = transitionID
            self.transitionProgress = progress.isFinite ? min(1, max(0, progress)) : 0
            self.updateVolumes()
        }
    }

    func append(_ frame: CamHubPCMFrame, sourceID: String) {
        guard pendingPackets.wait(timeout: .now()) == .success else { return }
        queue.async {
            defer { self.pendingPackets.signal() }
            guard sourceID == self.programID || sourceID == self.transitionID else { return }
            guard let channel = self.channel(for: sourceID, frame: frame),
                  let buffer = self.makeBuffer(frame, format: channel.format) else { return }
            if channel.queuedBuffers >= 6 {
                channel.scheduleGeneration &+= 1
                channel.player.stop()
                channel.queuedBuffers = 0
            }
            channel.queuedBuffers += 1
            let generation = channel.scheduleGeneration
            channel.player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self, weak channel] _ in
                guard let self, let channel else { return }
                self.queue.async {
                    guard channel.scheduleGeneration == generation else { return }
                    channel.queuedBuffers = max(0, channel.queuedBuffers - 1)
                }
            }
            if !channel.player.isPlaying { channel.player.play() }
            self.updateVolumes()
        }
    }

    func removeSource(_ sourceID: String) {
        queue.async {
            guard let channel = self.channels.removeValue(forKey: sourceID) else { return }
            channel.scheduleGeneration &+= 1
            channel.player.stop()
            self.engine.disconnectNodeOutput(channel.player)
            self.engine.detach(channel.player)
            self.updateVolumes()
            if self.channels.isEmpty {
                self.engine.stop()
                self.publishStatus("AUDIO OFF")
            }
        }
    }

    func stop() {
        queue.async {
            self.channels.values.forEach { $0.scheduleGeneration &+= 1; $0.player.stop() }
            self.channels.values.forEach { self.engine.disconnectNodeOutput($0.player); self.engine.detach($0.player) }
            self.channels.removeAll()
            self.engine.stop()
            self.publishStatus("AUDIO OFF")
        }
    }

    private func channel(for sourceID: String, frame: CamHubPCMFrame) -> Channel? {
        if let existing = channels[sourceID],
           existing.format.sampleRate == frame.sampleRate,
           Int(existing.format.channelCount) == frame.channels { return existing }
        if let existing = channels.removeValue(forKey: sourceID) {
            existing.player.stop()
            engine.disconnectNodeOutput(existing.player)
            engine.detach(existing.player)
        }
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: frame.sampleRate,
                                         channels: AVAudioChannelCount(frame.channels), interleaved: false) else { return nil }
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        do {
            if !engine.isRunning {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                try session.setActive(true)
                try engine.start()
            }
        } catch {
            engine.detach(player)
            publishStatus("AUDIO ERROR · \(error.localizedDescription)")
            return nil
        }
        let channel = Channel(format: format, player: player)
        channels[sourceID] = channel
        publishStatus("AUDIO READY")
        updateVolumes()
        return channel
    }

    private func makeBuffer(_ frame: CamHubPCMFrame, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let bytesPerFrame = frame.channels * MemoryLayout<Int16>.size
        let frameCount = frame.samples.count / bytesPerFrame
        guard frameCount > 0, frameCount <= 4_800,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frameCount)),
              let channels = buffer.floatChannelData else { return nil }
        buffer.frameLength = AVAudioFrameCount(frameCount)
        frame.samples.withUnsafeBytes { raw in
            guard let bytes = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            for sampleIndex in 0..<frameCount {
                for channel in 0..<frame.channels {
                    let offset = sampleIndex * bytesPerFrame + channel * 2
                    let value = Int16(bitPattern: UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8)
                    channels[channel][sampleIndex] = Float(value) / Float(Int16.max)
                }
            }
        }
        return buffer
    }

    private func updateVolumes() {
        let outputVolume: Float = muted ? 0 : Float(volume)
        for (sourceID, channel) in channels {
            let programGain: Double
            if sourceID == programID {
                programGain = transitionID == nil ? 1 : 1 - transitionProgress
            } else if sourceID == transitionID {
                programGain = transitionProgress
            } else {
                programGain = 0
            }
            channel.player.volume = outputVolume * Float(programGain)
        }
    }

    private func publishStatus(_ value: String) {
        DispatchQueue.main.async { self.status = value }
    }
}
