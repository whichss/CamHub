import AVFoundation
import SwiftUI
import UIKit

struct CamHubCameraScreen: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var pipeline = CameraCapturePipeline()
    @StateObject private var recorder = CamHubCameraRecorder()
    @StateObject private var sourceServer = CamHubCameraSourceServer()
    @State private var previewRotationDegrees = 90
    @State private var stoppedReason: String?
    @State private var zoomDrive: CGFloat = 0
    private let zoomTimer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color(red: 0.035, green: 0.055, blue: 0.085).ignoresSafeArea()
            GeometryReader { geometry in
                let landscape = geometry.size.width > geometry.size.height
            VStack(spacing: 18) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("CAMHUB CAMERA")
                            .font(.system(size: 21, weight: .bold, design: .rounded))
                        Text("iOS source · 720p30 H.264 · UDP/RTP")
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.58))
                    }
                    Spacer()
                    Circle()
                        .fill(sourceServer.status.hasPrefix("DISCOVERABLE") ? Color.green : Color.orange)
                        .frame(width: 9, height: 9)
                    Text(sourceServer.status)
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                }
                .foregroundStyle(.white)

                let layout = landscape ? AnyLayout(HStackLayout(spacing: 14)) : AnyLayout(VStackLayout(spacing: 14))
                layout {
                CameraPreview(session: pipeline.captureSession, rotationDegrees: previewRotationDegrees)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .overlay(alignment: .bottomLeading) {
                        Label("VIDEO · \(pipeline.encodedFrames) · \(pipeline.audioAvailable ? "AUDIO READY" : "VIDEO ONLY")",
                              systemImage: "dot.radiowaves.left.and.right")
                            .font(.system(size: 11, weight: .semibold, design: .monospaced))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(12)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black, in: RoundedRectangle(cornerRadius: 18))

                    ScrollView {
                        VStack(spacing: 14) {
                HStack(spacing: 8) {
                    Button {
                        if recorder.isRecording {
                            recorder.stop()
                        } else {
                            recorder.start { pipeline.requestKeyFrame() }
                        }
                    } label: {
                        Label(recorder.isRecording ? "STOP RECORDING" : "RECORD CAMERA",
                              systemImage: recorder.isRecording ? "stop.fill" : "record.circle")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(recorder.isRecording ? Color.red : Color.orange,
                                        in: RoundedRectangle(cornerRadius: 11))
                            .foregroundStyle(.black)
                    }
                    .buttonStyle(.plain)
                    .disabled(pipeline.state != .capturing || recorder.state == .saving)

                    if let url = recorder.latestRecordingURL {
                        ShareLink(item: url) {
                            Label("SHARE", systemImage: "square.and.arrow.up")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .padding(12)
                                .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 11))
                                .foregroundStyle(.white)
                        }
                    }
                }
                Text(recordingStatusText)
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(recorder.state.isFailure ? .red : .white.opacity(0.58))
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 12) {
                    Text("−")
                    Text(String(format: "%.1f×", pipeline.zoomRatio))
                        .monospacedDigit()
                        .frame(minWidth: 48)
                    GeometryReader { geometry in
                        ZStack {
                            Capsule().fill(.white.opacity(0.15))
                            Circle().fill(Color.cyan)
                                .frame(width: 28, height: 28)
                                .offset(x: zoomDrive * max(0, (geometry.size.width - 28) / 2))
                        }
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                let half = max(1, geometry.size.width / 2)
                                let next = min(1, max(-1, (value.location.x - half) / half))
                                if abs(next) <= 0.05 || (zoomDrive * next < 0) {
                                    pipeline.cancelPendingZoom()
                                }
                                zoomDrive = next
                            }
                            .onEnded { _ in
                                zoomDrive = 0
                                pipeline.cancelPendingZoom()
                            })
                    }
                    .frame(height: 36)
                    .accessibilityElement()
                    .accessibilityLabel("Camera zoom")
                    .accessibilityValue(String(format: "%.1f times", pipeline.zoomRatio))
                    .accessibilityHint("Swipe up to zoom in or down to zoom out.")
                    .accessibilityAdjustableAction { direction in
                        switch direction {
                        case .increment: pipeline.adjustZoom(by: 1.2)
                        case .decrement: pipeline.adjustZoom(by: 1 / 1.2)
                        @unknown default: break
                        }
                    }
                    Text("+")
                }
                .foregroundStyle(.white)
                .disabled(pipeline.state != .capturing || pipeline.maximumZoomRatio <= pipeline.minimumZoomRatio)

                VStack(alignment: .leading, spacing: 7) {
                    Label("PAIRING", systemImage: "qrcode")
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(.cyan)
                    if sourceServer.status.hasPrefix("DISCOVERABLE") {
                        Text("Camera name: \(sourceServer.cameraName)")
                            .font(.system(size: 12, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.white)
                    }
                    HStack(alignment: .firstTextBaseline) {
                        Text("PIN")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.55))
                        Text(sourceServer.status.hasPrefix("DISCOVERABLE") ? sourceServer.pairingPIN : "--------")
                            .font(.system(size: 22, weight: .bold, design: .monospaced))
                            .foregroundStyle(.white)
                    }
                    Text("Verify fingerprint on the hub before entering this PIN.")
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.68))
                    Text(sourceServer.status.hasPrefix("DISCOVERABLE") ? "SHA-256  \(sourceServer.fingerprint)" : "Start the source to show pairing information.")
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.55))
                        .textSelection(.enabled)
                    Text(sourceServer.connectedHubs.isEmpty
                         ? (sourceServer.status.hasPrefix("DISCOVERABLE") ? "Waiting for a hub to connect" : "Source is offline")
                         : "Connected: \(sourceServer.connectedHubs.joined(separator: ", "))")
                        .font(.system(size: 12))
                        .foregroundStyle(.white.opacity(0.68))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(pipeline.maxRemotePtzZoomRatio > 1.001
                         ? "Camera-side source crop available up to \(pipeline.maxRemotePtzZoomRatio, specifier: "%.2f")×; extra zoom stays on the hub."
                         : "This camera format has no extra crop resolution; hub PTZ remains available.")
                        .font(.system(size: 11))
                        .foregroundStyle(.white.opacity(0.58))
                    if let reason = stoppedReason {
                        Text(reason)
                            .font(.system(size: 11))
                            .foregroundStyle(.orange)
                    }
                    if let error = pipeline.lastError ?? sourceServer.lastError {
                        Text(error)
                            .font(.system(size: 11))
                            .foregroundStyle(.red.opacity(0.9))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))

                if pipeline.state == .capturing &&
                    (sourceServer.status == "NETWORK ERROR" || sourceServer.status == "START FAILED") {
                    Button("Retry connection") { sourceServer.start() }
                        .buttonStyle(.borderedProminent)
                }

                Button {
                    if pipeline.state == .capturing || pipeline.state == .starting || pipeline.state == .requestingPermission {
                        zoomDrive = 0
                        recorder.stop()
                        pipeline.stop()
                        sourceServer.stop()
                    } else {
                        startSource()
                    }
                } label: {
                    Label(
                        pipeline.state == .capturing ? "STOP SOURCE" : "START SOURCE",
                        systemImage: pipeline.state == .capturing ? "stop.fill" : "video.fill"
                    )
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(pipeline.state == .capturing ? Color.red : Color.cyan, in: RoundedRectangle(cornerRadius: 13))
                    .foregroundStyle(.black)
                }
                .buttonStyle(.plain)
                        }
                    }
                    .frame(maxWidth: landscape ? min(340, geometry.size.width * 0.40) : .infinity,
                           maxHeight: landscape ? .infinity : geometry.size.height * 0.52)
                }
            }
            .padding(18)
            }
        }
        .preferredColorScheme(.dark)
        .onReceive(zoomTimer) { _ in
            guard scenePhase == .active, pipeline.state == .capturing, abs(zoomDrive) > 0.05 else { return }
            pipeline.adjustZoom(by: CGFloat(pow(2, Double(zoomDrive) * 0.08)))
        }
        .onAppear {
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            previewRotationDegrees = currentVideoRotation(fallback: previewRotationDegrees)
            startSource()
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active {
                zoomDrive = 0
                pipeline.cancelPendingZoom()
            }
            if phase == .background {
                stoppedReason = "Backgrounded. Tap Start Source to resume."
                recorder.stop()
                pipeline.stop()
                sourceServer.stop()
            }
        }
        .onChange(of: pipeline.state) { state in
            if state != .capturing {
                zoomDrive = 0
                pipeline.cancelPendingZoom()
                if recorder.isRecording { recorder.stop() }
            }
            if state == .capturing {
                sourceServer.start()
            } else if state == .permissionDenied || state == .failed {
                sourceServer.stop()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: AVCaptureSession.wasInterruptedNotification, object: pipeline.captureSession)) { _ in
            zoomDrive = 0
            stoppedReason = "Camera interrupted. Tap Start Source to resume."
            recorder.stop()
            pipeline.stop()
            sourceServer.stop()
        }
        .onReceive(NotificationCenter.default.publisher(for: AVCaptureSession.runtimeErrorNotification, object: pipeline.captureSession)) { notification in
            zoomDrive = 0
            let error = notification.userInfo?[AVCaptureSessionErrorKey] as? Error
            stoppedReason = error.map { "Camera error: \($0.localizedDescription). Tap Start Source to retry." }
                ?? "Camera error. Tap Start Source to retry."
            recorder.stop()
            pipeline.stop()
            sourceServer.stop()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            // Face-up, face-down, and unknown are not usable video orientations. Keep the last
            // valid orientation instead of unexpectedly rotating the outgoing picture to portrait.
            let rotation = currentVideoRotation(fallback: previewRotationDegrees)
            previewRotationDegrees = rotation
            pipeline.setRotationDegrees(rotation)
        }
        .onDisappear {
            zoomDrive = 0
            pipeline.cancelPendingZoom()
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
            recorder.stop()
            pipeline.setEncodedSampleHandler(nil)
            pipeline.setAudioPayloadHandler(nil)
            pipeline.stop()
            sourceServer.stop()
        }
    }

    private func startSource() {
        stoppedReason = nil
        pipeline.setRotationDegrees(currentVideoRotation(fallback: previewRotationDegrees))
        let capturePipeline = pipeline
        let cameraRecorder = recorder
        let cameraServer = sourceServer
        cameraRecorder.onEncodingDemandChanged = { [weak capturePipeline] enabled in
            capturePipeline?.setRecordingEnabled(enabled)
        }
        capturePipeline.setEncodedSampleHandler { [weak cameraRecorder, weak capturePipeline] sample, rotation in
            cameraRecorder?.append(sample, rotationDegrees: rotation) {
                capturePipeline?.requestKeyFrame()
            }
        }
        capturePipeline.setAccessUnitHandler { [weak cameraServer] unit in cameraServer?.publish(unit) }
        capturePipeline.setAudioPayloadHandler { [weak cameraServer] payload in
            cameraServer?.publishAudio(payload)
        }
        cameraServer.requestKeyFrame = { [weak capturePipeline] in capturePipeline?.requestKeyFrame() }
        cameraServer.onZoomRequested = { [weak capturePipeline] factor in capturePipeline?.setZoom(factor) }
        cameraServer.onPtzRequested = { [weak capturePipeline] clientId, zoom, centerX, centerY, requestId in
            capturePipeline?.setRemotePtz(zoom: zoom, centerX: centerX, centerY: centerY,
                                           requestId: requestId, clientId: clientId)
        }
        capturePipeline.onRemotePtzApplied = { [weak cameraServer] clientId, requestId, zoom, centerX, centerY in
            cameraServer?.acknowledgeRemotePtz(clientId: clientId, requestId: requestId,
                                               zoom: zoom, centerX: centerX, centerY: centerY)
        }
        capturePipeline.onRemotePtzCapabilitiesChanged = { [weak cameraServer] maximumZoom in
            cameraServer?.updateRemotePtzCapability(maximumZoom: maximumZoom)
        }
        cameraServer.onStreamingStateChanged = { [weak capturePipeline] enabled in
            capturePipeline?.setStreamingEnabled(enabled)
        }
        cameraServer.onBitrateChanged = { [weak capturePipeline] value in
            capturePipeline?.setBitrateMbps(value)
        }
        capturePipeline.start()
    }

    private func currentVideoRotation(fallback: Int) -> Int {
        switch UIDevice.current.orientation {
        case .portrait: return 90
        case .portraitUpsideDown: return 270
        case .landscapeLeft: return 180
        case .landscapeRight: return 0
        default: return fallback
        }
    }

    private var recordingStatusText: String {
        switch recorder.state {
        case .idle: return "LOCAL H.264 RECORDING · FILES ARE SAVED ON THIS DEVICE"
        case .starting: return "STARTING · WAITING FOR A KEYFRAME"
        case .recording: return "RECORDING CAMERA · EXISTING H.264 ENCODE"
        case .saving: return "FINALIZING RECORDING…"
        case .saved: return "SAVED · DOCUMENTS/CAMHUB RECORDINGS"
        case let .failed(message): return message
        }
    }
}

private extension CamHubCameraRecorder.State {
    var isFailure: Bool {
        if case .failed = self { return true }
        return false
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let rotationDegrees: Int

    func makeUIView(context: Context) -> CameraPreviewHost {
        let view = CameraPreviewHost()
        view.previewLayer.session = session
        // Show the whole transmitted camera frame instead of cropping it to the UI panel.
        view.previewLayer.videoGravity = .resizeAspect
        view.updateOrientation(rotationDegrees)
        return view
    }

    func updateUIView(_ view: CameraPreviewHost, context: Context) {
        if view.previewLayer.session !== session { view.previewLayer.session = session }
        view.updateOrientation(rotationDegrees)
    }
}

private final class CameraPreviewHost: UIView {
    private var lastRotationDegrees: Int?
    private var desiredRotationDegrees = 0
    private weak var lastConnection: AVCaptureConnection?
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    func updateOrientation(_ degrees: Int) {
        desiredRotationDegrees = degrees
        guard let connection = previewLayer.connection,
              lastRotationDegrees != degrees || lastConnection !== connection,
              connection.isVideoOrientationSupported else { return }
        let orientation: AVCaptureVideoOrientation
        switch degrees {
        case 90: orientation = .portrait
        case 180: orientation = .landscapeLeft
        case 270: orientation = .portraitUpsideDown
        default: orientation = .landscapeRight
        }
        connection.videoOrientation = orientation
        lastRotationDegrees = degrees
        lastConnection = connection
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateOrientation(desiredRotationDegrees)
    }
}
