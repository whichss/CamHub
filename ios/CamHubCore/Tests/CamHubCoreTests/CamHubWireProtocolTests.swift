import XCTest
import CoreMedia
@testable import CamHubCore

final class CamHubWireProtocolTests: XCTestCase {
    func testTransitionTimelineHasStableEndpointsAndEaseInOutMidpoint() {
        let timeline = CamHubTransitionTimeline(durationMilliseconds: 350)

        XCTAssertEqual(timeline.progress(elapsedNanoseconds: 0), 0)
        XCTAssertEqual(timeline.progress(elapsedNanoseconds: 175_000_000), 0.5, accuracy: 0.0001)
        XCTAssertEqual(timeline.progress(elapsedNanoseconds: 350_000_000), 1)
        XCTAssertEqual(timeline.progress(elapsedNanoseconds: 500_000_000), 1)
    }

    func testTransitionTimelineClampsZeroDurationToOneMillisecond() {
        let timeline = CamHubTransitionTimeline(durationMilliseconds: 0)

        XCTAssertEqual(timeline.durationNanoseconds, 1_000_000)
        XCTAssertEqual(timeline.progress(elapsedNanoseconds: 0), 0)
        XCTAssertEqual(timeline.progress(elapsedNanoseconds: 1_000_000), 1)
    }

    func testFittedVideoSizeUsesRotationAndViewportAspect() {
        let source = CGSize(width: 16, height: 9)
        XCTAssertEqual(CamHubFraming.fittedSize(source: source, rotation: 0, viewport: CGSize(width: 160, height: 160)), CGSize(width: 160, height: 90))
        XCTAssertEqual(CamHubFraming.fittedSize(source: source, rotation: 90, viewport: CGSize(width: 160, height: 160)), CGSize(width: 90, height: 160))
        XCTAssertEqual(CamHubFraming.fittedSize(source: source, rotation: 270, viewport: CGSize(width: 320, height: 180)), CGSize(width: 101.25, height: 180))
        XCTAssertEqual(CamHubFraming.fittedSize(source: .zero, rotation: 0, viewport: CGSize(width: 1, height: 1)), .zero)
    }

    func testFramingCentersTapAndClampsMovement() {
        XCTAssertEqual(CamHubFraming.zoomedAt(x: 0.75, y: 0.25), CamHubFraming(zoom: 2, x: -0.5, y: 0.5))
        XCTAssertEqual(CamHubFraming(zoom: 1, x: 4, y: -4), CamHubFraming(zoom: 1, x: 0, y: 0))
        XCTAssertEqual(CamHubFraming(zoom: 8, x: 9, y: -9), CamHubFraming(zoom: 4, x: 1.5, y: -1.5))
        XCTAssertEqual(CamHubFraming(zoom: .nan, x: .infinity, y: .nan), CamHubFraming(zoom: 1, x: 0, y: 0))
    }

    func testSourceCropUsesSensorResolutionAndClampsOffCenterFraming() throws {
        let crop = try XCTUnwrap(CamHubSourceCrop.rectangle(
            source: CGSize(width: 1920, height: 1080), output: CGSize(width: 1280, height: 720),
            zoom: 1.5, centerX: 0.5, centerY: 0.5, rotation: 0
        ))
        XCTAssertEqual(crop, CGRect(x: 320, y: 180, width: 1280, height: 720))

        let moved = try XCTUnwrap(CamHubSourceCrop.rectangle(
            source: CGSize(width: 1920, height: 1080), output: CGSize(width: 1280, height: 720),
            zoom: 1.5, centerX: 0.6, centerY: 0.4, rotation: 0
        ))
        XCTAssertEqual(moved, CGRect(x: 512, y: 288, width: 1280, height: 720))
    }

    func testSourceCropMapsDisplayCoordinatesForQuarterTurnRotation() throws {
        let crop = try XCTUnwrap(CamHubSourceCrop.rectangle(
            source: CGSize(width: 1920, height: 1080), output: CGSize(width: 1280, height: 720),
            zoom: 1.5, centerX: 0.6, centerY: 0.5, rotation: 90
        ))
        XCTAssertEqual(crop, CGRect(x: 320, y: 288, width: 1280, height: 720))
        XCTAssertNil(CamHubSourceCrop.rectangle(
            source: .zero, output: CGSize(width: 1280, height: 720), zoom: 2,
            centerX: 0.5, centerY: 0.5, rotation: 0
        ))
    }

    func testHybridPtzUsesResidualHubCropUntilRemoteAcknowledgement() {
        var ptz = CamHubHybridPtzState()
        ptz.request(zoom: 4, centerX: 0.3, centerY: 0.6, supportsRemotePtz: true)
        XCTAssertEqual(ptz.mode, .remotePending)
        XCTAssertEqual(ptz.hubFraming.zoom, 4, accuracy: 0.0001)
        XCTAssertEqual(ptz.hubFraming.x, 0.8, accuracy: 0.0001)
        XCTAssertEqual(ptz.hubFraming.y, -0.4, accuracy: 0.0001)

        XCTAssertTrue(ptz.markRemoteApplied(zoom: 2, centerX: 0.5, centerY: 0.5))
        XCTAssertEqual(ptz.mode, .remotePending)
        XCTAssertEqual(ptz.hubFraming.zoom, 2, accuracy: 0.0001)
        XCTAssertEqual(ptz.hubFraming.x, 0.5, accuracy: 0.0001)
        XCTAssertEqual(ptz.hubFraming.y, -0.4, accuracy: 0.0001)

        XCTAssertTrue(ptz.markRemoteApplied(zoom: 4, centerX: 0.3, centerY: 0.6))
        XCTAssertEqual(ptz.mode, .remote)
        XCTAssertEqual(ptz.hubFraming, CamHubFraming(zoom: 1, x: 0, y: 0))
    }

    func testHybridPtzStaysOnHubWhenRemoteCropIsUnsupported() {
        var ptz = CamHubHybridPtzState()
        ptz.request(zoom: 2, centerX: 0.25, centerY: 0.75, supportsRemotePtz: false)
        XCTAssertEqual(ptz.mode, .hub)
        XCTAssertEqual(ptz.hubFraming, CamHubFraming(zoom: 2, x: 0.5, y: -0.5))
        XCTAssertFalse(ptz.markRemoteApplied(zoom: .nan, centerX: 0.5, centerY: 0.5))
    }

    func testFrameSequenceDistinguishesGapsAndLateFrames() {
        let tracker = CamHubFrameSequenceTracker()
        XCTAssertEqual(tracker.observe(0), .unsequenced)
        XCTAssertEqual(tracker.observe(9), .first)
        XCTAssertEqual(tracker.observe(10), .inOrder)
        XCTAssertEqual(tracker.observe(0), .late)
        XCTAssertEqual(tracker.observe(13), .gap)
        XCTAssertEqual(tracker.observe(12), .late)
        XCTAssertEqual(tracker.observe(13), .late)
        XCTAssertEqual(tracker.observe(14), .inOrder)
        tracker.reset()
        XCTAssertEqual(tracker.observe(1), .first)
    }

    func testLatencyWindowUsesBoundedNearestRank() throws {
        let window = CamHubLatencyWindow(capacity: 4)
        XCTAssertNil(window.percentiles())
        [1, 2, 3, 4, 100].forEach { window.record(Int64($0)) }
        XCTAssertEqual(window.count, 4)
        let values = try XCTUnwrap(window.percentiles())
        XCTAssertEqual(values.p50, 3)
        XCTAssertEqual(values.p95, 100)
        XCTAssertEqual(values.p99, 100)
        window.clear()
        XCTAssertEqual(window.count, 0)
        XCTAssertNil(window.percentiles())
    }

    func testClockEstimateRejectsInvalidAndSlowSamples() throws {
        let estimate = try XCTUnwrap(CamHubClockEstimate.calculate(t0: 1_000, t1: 1_110, t2: 1_112, t3: 1_022))
        XCTAssertEqual(estimate.remoteOffsetMs, 100)
        XCTAssertEqual(estimate.roundTripMs, 20)
        XCTAssertNil(CamHubClockEstimate.calculate(t0: 1_000, t1: 1_110, t2: 1_109, t3: 1_022))
        XCTAssertNil(CamHubClockEstimate.calculate(t0: 1, t1: 2, t2: 3, t3: 3_000))
        XCTAssertNil(CamHubClockEstimate.calculate(t0: 1, t1: 2, t2: 3, t3: .max))
    }

    func testSampleBuilderWaitsForKeyFrameAndCanReset() throws {
        let config = Data([0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x1f, 0xda, 0x01, 0x40, 0x16, 0xec,
                           0x04, 0x40, 0, 0, 3, 0, 0x40, 0, 0, 0x0f, 3, 0xc6, 0x0c, 0xa8,
                           0, 0, 0, 1, 0x68, 0xce, 0x3c, 0x80])
        let delta = Data([0, 0, 0, 1, 0x41, 1])
        let idr = Data([0, 0, 0, 1, 0x65, 1])
        let builder = CamHubH264SampleBuilder()
        XCTAssertNil(builder.makeSample(config, presentationTime: .zero))
        XCTAssertNil(builder.makeSample(delta, presentationTime: .zero))
        let sample = try XCTUnwrap(builder.makeSample(idr, presentationTime: .zero))
        XCTAssertEqual(CMSampleBufferGetNumSamples(sample), 1)
        let deltaSample = try XCTUnwrap(builder.makeSample(delta, presentationTime: .zero))
        let attachments = try XCTUnwrap(CMSampleBufferGetSampleAttachmentsArray(deltaSample, createIfNecessary: false) as? [[CFString: Any]])
        XCTAssertEqual(attachments.first?[kCMSampleAttachmentKey_DisplayImmediately] as? Bool, true)
        XCTAssertEqual(attachments.first?[kCMSampleAttachmentKey_NotSync] as? Bool, true)
        builder.requireKeyFrame()
        XCTAssertNil(builder.makeSample(delta, presentationTime: .zero))
        XCTAssertNotNil(builder.makeSample(idr, presentationTime: .zero))
        XCTAssertNil(builder.makeSample(Data([0x65, 1, 2]), presentationTime: .zero))
        XCTAssertTrue(builder.needsKeyFrame)
        XCTAssertNil(builder.makeSample(delta, presentationTime: .zero))
        XCTAssertNotNil(builder.makeSample(idr, presentationTime: .zero))
        XCTAssertFalse(builder.needsKeyFrame)
        builder.reset()
        XCTAssertNil(builder.makeSample(idr, presentationTime: .zero))
    }

    func testAnnexBExtractsParameterSetsAndCreatesLengthPrefixedSample() throws {
        let input = Data([0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3, 4])
        let unit = try XCTUnwrap(CamHubH264AnnexB.accessUnit(input))
        XCTAssertEqual(unit.sps, Data([0x67, 1]))
        XCTAssertEqual(unit.pps, Data([0x68, 2]))
        XCTAssertTrue(unit.hasIDR)
        XCTAssertEqual(unit.sample, Data([0, 0, 0, 3, 0x65, 3, 4]))
    }

    func testAnnexBParsesMixedStartCodesAndRejectsEmptyUnits() {
        let bytes = Data([0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4])
        XCTAssertEqual(CamHubH264AnnexB.units(bytes), [Data([0x67, 1, 2]), Data([0x68, 3]), Data([0x65, 4])])
        XCTAssertNil(CamHubH264AnnexB.units(Data([0, 0, 1, 0, 0, 1, 0x65])))
        XCTAssertNil(CamHubH264AnnexB.units(Data([0x65, 1, 2, 3])))
    }

    func testFrameReassemblerBoundsAndRejectsConflictingMetadata() throws {
        let bytes = Data(repeating: 7, count: 2_000)
        func packets(_ id: UInt64, _ timestamp: UInt32) throws -> [Data] {
            try CamHubRtpPacketCodec.packetize(bytes, frameID: id, timestamp: timestamp,
                                                ssrc: 1, firstSequence: 0)
        }
        let assembler = CamHubFrameReassembler(deadlineMs: 35, maxFrames: 1, maxFrameBytes: 2_000)
        let first = try packets(1, 10)
        let conflicting = try packets(1, 11)
        XCTAssertEqual(assembler.offer(first[0], nowMs: 0), .accepted)
        XCTAssertEqual(assembler.offer(conflicting[1], nowMs: 1), .dropped)
        XCTAssertEqual(assembler.offer(first[1], nowMs: 2), .dropped)

        let second = try packets(2, 20)
        let third = try packets(3, 30)
        XCTAssertEqual(assembler.offer(second[0], nowMs: 3), .accepted)
        XCTAssertEqual(assembler.offer(third[0], nowMs: 4), .accepted)
        XCTAssertEqual(assembler.offer(second[1], nowMs: 5), .dropped)
        XCTAssertEqual(assembler.offer(third[1], nowMs: 6), .complete(bytes))

        let limited = CamHubFrameReassembler(maxFrameBytes: 1_000)
        XCTAssertEqual(limited.offer(first[0], nowMs: 0), .dropped)
    }

    func testEncryptedIngressReassemblesAndAuthenticatesPayload() throws {
        let key = Data(repeating: 0x21, count: 32)
        let payload = CamHubVideoPayload(width: 1280, height: 720, rotationDegrees: 90,
                                         isKeyFrame: true, isCodecConfig: false, frameSequence: 4,
                                         captureAtWallMs: 10, encodedAtWallMs: 11,
                                         h264Bytes: Data(repeating: 9, count: 2_500))
        let envelope = try CamHubFrameCipher(key: key).encrypt(CamHubVideoPayloadCodec.encode(payload))
        let packets = try CamHubRtpPacketCodec.packetize(envelope, frameID: 4, timestamp: 9,
                                                           ssrc: 2, firstSequence: 4, isKeyFrame: true)
        let receiver = try CamHubVideoIngress(sessionKey: key)
        for packet in packets.dropLast().reversed() {
            XCTAssertEqual(receiver.offer(packet, nowMs: 1), .pending)
        }
        XCTAssertEqual(receiver.offer(try XCTUnwrap(packets.last), nowMs: 2), .frame(payload))
        let replay = try CamHubRtpPacketCodec.packetize(envelope, frameID: 5, timestamp: 10,
                                                          ssrc: 2, firstSequence: 10, isKeyFrame: true)
        for packet in replay.dropLast() { _ = receiver.offer(packet, nowMs: 3) }
        XCTAssertEqual(receiver.offer(try XCTUnwrap(replay.last), nowMs: 4), .dropped)
        let wrongKeyReceiver = try CamHubVideoIngress(sessionKey: Data(repeating: 0x22, count: 32))
        for packet in packets.dropLast() { _ = wrongKeyReceiver.offer(packet, nowMs: 1) }
        XCTAssertEqual(wrongKeyReceiver.offer(try XCTUnwrap(packets.last), nowMs: 2), .dropped)
        XCTAssertEqual(wrongKeyReceiver.droppedFrameCount, 1)
        let correctKey = Data(repeating: 0x22, count: 32)
        let validEnvelope = try CamHubFrameCipher(key: correctKey).encrypt(CamHubVideoPayloadCodec.encode(payload))
        let recovered = try CamHubRtpPacketCodec.packetize(validEnvelope, frameID: 6, timestamp: 11,
                                                            ssrc: 2, firstSequence: 20, isKeyFrame: true)
        for packet in recovered.dropLast() {
            XCTAssertEqual(wrongKeyReceiver.offer(packet, nowMs: 3), .pending)
        }
        XCTAssertEqual(wrongKeyReceiver.offer(try XCTUnwrap(recovered.last), nowMs: 4), .frame(payload))
        XCTAssertEqual(wrongKeyReceiver.droppedFrameCount, 1)
    }

    func testFrameReassemblyHandlesReorderingDuplicatesAndDeadline() throws {
        let bytes = Data(repeating: 42, count: 2_500)
        let packets = try CamHubRtpPacketCodec.packetize(bytes, frameID: 7, timestamp: 1,
                                                           ssrc: 2, firstSequence: 3)
        let assembler = CamHubFrameReassembler(deadlineMs: 35)
        XCTAssertEqual(assembler.offer(packets[1], nowMs: 100), .accepted)
        XCTAssertEqual(assembler.offer(packets[1], nowMs: 101), .duplicate)
        XCTAssertEqual(assembler.offer(packets[0], nowMs: 102), .accepted)
        XCTAssertEqual(assembler.offer(packets[2], nowMs: 103), .complete(bytes))
        XCTAssertEqual(assembler.offer(packets[0], nowMs: 104), .dropped)

        let late = try CamHubRtpPacketCodec.packetize(bytes, frameID: 8, timestamp: 2,
                                                       ssrc: 2, firstSequence: 10)
        XCTAssertEqual(assembler.offer(late[0], nowMs: 200), .accepted)
        XCTAssertEqual(assembler.offer(late[1], nowMs: 235), .dropped)
        XCTAssertEqual(assembler.droppedFrameCount, 1)
        let receiver = try CamHubVideoIngress(sessionKey: Data(repeating: 1, count: 32))
        XCTAssertEqual(receiver.offer(late[0], nowMs: 200), .pending)
        XCTAssertEqual(receiver.expire(nowMs: 235), 1)
        XCTAssertEqual(receiver.expire(nowMs: 250), 0)
    }

    func testAndroidBitrateCommandWithOmittedDefaults() throws {
        let json = #"{"type":"command","command":"set_stream_bitrate","value":4.0}"#
        let message = try JSONDecoder().decode(CamHubControlMessage.self, from: Data(json.utf8))
        XCTAssertEqual(message.type, "command")
        XCTAssertEqual(message.command, "set_stream_bitrate")
        XCTAssertEqual(CamHubBitratePolicy.validatedRequest(message.value), 4)
    }

    func testV4PayloadMatchesCrossPlatformGoldenVector() throws {
        let payload = CamHubVideoPayload(
            width: 2,
            height: 3,
            rotationDegrees: 270,
            isKeyFrame: true,
            isCodecConfig: true,
            frameSequence: 1,
            captureAtWallMs: 2,
            encodedAtWallMs: 3,
            h264Bytes: Data([0xaa, 0xbb])
        )
        XCTAssertEqual(try CamHubVideoPayloadCodec.encode(payload), try goldenVector("payload_v4"))
    }

    func testSingleFragmentRtpMatchesCrossPlatformGoldenVector() throws {
        let packets = try CamHubRtpPacketCodec.packetize(
            Data([0xaa]),
            frameID: 1,
            timestamp: 2,
            ssrc: 3,
            firstSequence: 4,
            isKeyFrame: true,
            isCodecConfig: true
        )
        let packet = try XCTUnwrap(packets.first)
        XCTAssertEqual(packet, try goldenVector("rtp_single_fragment"))
    }

    func testV4PayloadRoundTrip() throws {
        let original = CamHubVideoPayload(
            width: 1280,
            height: 720,
            rotationDegrees: 90,
            isKeyFrame: true,
            isCodecConfig: false,
            frameSequence: 42,
            captureAtWallMs: 1_700_000_000_000,
            encodedAtWallMs: 1_700_000_000_004,
            h264Bytes: Data([0, 0, 0, 1, 0x65, 1, 2, 3])
        )
        let encoded = try CamHubVideoPayloadCodec.encode(original)
        XCTAssertEqual(CamHubVideoPayloadCodec.decode(encoded), original)
    }

    func testRtpFragmentsRespectDatagramLimitAndReassembleInOrder() throws {
        let frame = Data((0..<5_000).map { UInt8($0 & 0xff) })
        let packets = try CamHubRtpPacketCodec.packetize(
            frame,
            frameID: 7,
            timestamp: 90_000,
            ssrc: 0x1234,
            firstSequence: 65_534,
            isKeyFrame: true
        )
        XCTAssertTrue(packets.allSatisfy { $0.count <= CamHubRtpPacketCodec.maxDatagramBytes })
        let decoded = packets.compactMap(CamHubRtpPacketCodec.decode)
            .sorted { $0.fragmentIndex < $1.fragmentIndex }
        XCTAssertEqual(decoded.map(\.payload).reduce(Data(), +), frame)
        XCTAssertTrue(decoded.first?.isKeyFrame == true)
    }

    func testAuthenticatedTcpCapabilityDefaultsAndRoundTrips() throws {
        let legacy = try JSONDecoder().decode(CamHubControlMessage.self, from: Data("{\"type\":\"hello_ack\"}".utf8))
        XCTAssertFalse(legacy.supportsAuthenticatedTcp)
        XCTAssertEqual(legacy.tcpStreamPort, 0)
        let enabled = CamHubControlMessage(
            type: "hello_ack",
            tcpStreamPort: 12_345,
            tcpAuthToken: String(repeating: "A", count: 44),
            supportsAuthenticatedTcp: true
        )
        let decoded = try JSONDecoder().decode(CamHubControlMessage.self, from: JSONEncoder().encode(enabled))
        XCTAssertTrue(decoded.supportsAuthenticatedTcp)
        XCTAssertEqual(decoded.tcpStreamPort, 12_345)
        XCTAssertEqual(decoded.tcpAuthToken, String(repeating: "A", count: 44))
    }

    func testTcpFrameUsesBigEndianLengthAndRejectsOversize() {
        XCTAssertEqual(CamHubTcpFrameCodec.encode(Data([1, 2, 3])), Data([0, 0, 0, 3, 1, 2, 3]))
        XCTAssertNil(CamHubTcpFrameCodec.encode(Data()))
        XCTAssertNil(CamHubTcpFrameCodec.encode(Data([1, 2, 3]), maximumBytes: 2))
    }

    func testTcpFrameDecoderHandlesPartialAndCoalescedFrames() throws {
        var buffer = Data([0, 0, 0, 3, 1])
        XCTAssertNil(try CamHubTcpFrameCodec.decodeNext(from: &buffer))
        buffer.append(contentsOf: [2, 3, 0, 0, 0, 2, 4, 5])
        XCTAssertEqual(try CamHubTcpFrameCodec.decodeNext(from: &buffer), Data([1, 2, 3]))
        XCTAssertEqual(try CamHubTcpFrameCodec.decodeNext(from: &buffer), Data([4, 5]))
        XCTAssertTrue(buffer.isEmpty)
        XCTAssertNil(try CamHubTcpFrameCodec.decodeNext(from: &buffer))
    }

    func testTcpFrameDecoderRejectsInvalidLengthAndClearsBuffer() {
        var zeroLength = Data([0, 0, 0, 0, 9])
        XCTAssertThrowsError(try CamHubTcpFrameCodec.decodeNext(from: &zeroLength)) { error in
            XCTAssertEqual(error as? CamHubTcpFrameCodec.DecodeError, .invalidLength)
        }
        XCTAssertTrue(zeroLength.isEmpty)

        var oversized = Data([0, 0, 0, 3, 9])
        XCTAssertThrowsError(try CamHubTcpFrameCodec.decodeNext(from: &oversized, maximumBytes: 2))
        XCTAssertTrue(oversized.isEmpty)
    }

    func testCipherEnvelopeRoundTrip() throws {
        let cipher = try CamHubFrameCipher(key: Data(repeating: 0x5a, count: 32))
        let plaintext = Data("CamHub frame".utf8)
        XCTAssertEqual(try cipher.decrypt(cipher.encrypt(plaintext)), plaintext)
    }

    func testCipherReadsAES256GCMKnownAnswer() throws {
        let cipher = try CamHubFrameCipher(key: Data(repeating: 0, count: 32))
        XCTAssertEqual(try cipher.decrypt(goldenVector("aes_gcm_empty")), Data())
    }

    func testControlMessageDecodesAndroidHelloWithOmittedDefaults() throws {
        let message = try JSONDecoder().decode(
            CamHubControlMessage.self,
            from: Data(goldenFixture("control_hello").utf8)
        )
        XCTAssertEqual(message.type, "hello")
        XCTAssertEqual(message.deviceName, "Hub")
        XCTAssertEqual(message.pin, "12345678")
        XCTAssertEqual(message.udpReceivePort, 0)
        XCTAssertEqual(message.requestId, 0)
    }

    func testControlMessageDecodesCameraHelloAcknowledgement() throws {
        let message = try JSONDecoder().decode(
            CamHubControlMessage.self,
            from: Data(goldenFixture("control_hello_ack").utf8)
        )
        XCTAssertEqual(message.type, "hello_ack")
        XCTAssertEqual(message.deviceName, "CamHub iOS")
        XCTAssertEqual(message.sessionKey, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        XCTAssertEqual(message.tcpStreamPort, 50_001)
        XCTAssertEqual(message.tcpAuthToken, "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=")
        XCTAssertTrue(message.supportsAuthenticatedTcp)
        XCTAssertEqual(message.udpStreamPort, 50_000)
    }

    func testCameraHelloAcknowledgementEncodingMatchesSharedAndroidWireFixture() throws {
        let message = CamHubControlMessage(
            type: "hello_ack",
            deviceName: "CamHub iOS",
            tcpStreamPort: 50_001,
            sessionKey: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            tcpAuthToken: "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
            udpStreamPort: 50_000,
            supportsAuthenticatedTcp: true
        )
        let expected = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(goldenFixture("control_hello_ack").utf8)) as? NSDictionary
        )
        let actual = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(message)) as? NSDictionary
        )
        let actualRequiredFields = NSMutableDictionary()
        for key in expected.allKeys {
            actualRequiredFields[key] = actual[key]
        }
        XCTAssertEqual(actualRequiredFields, expected)
        XCTAssertEqual(try JSONDecoder().decode(CamHubControlMessage.self, from: JSONSerialization.data(withJSONObject: actual)), message)
    }

    func testControlMessageDecodesAndroidUdpSubscription() throws {
        let message = try JSONDecoder().decode(
            CamHubControlMessage.self,
            from: Data(goldenFixture("control_udp_subscribe").utf8)
        )
        XCTAssertEqual(message.type, "command")
        XCTAssertEqual(message.command, "udp_subscribe")
        XCTAssertEqual(message.udpReceivePort, 40_000)
    }

    func testRemotePtzCommandAndAppliedAcknowledgementRoundTrip() throws {
        var command = CamHubControlMessage(type: "command", command: "set_ptz")
        command.value = 3.5
        command.stringValue = "0.3,0.7"
        command.requestId = 42
        let commandData = try JSONEncoder().encode(command)
        XCTAssertEqual(try JSONDecoder().decode(CamHubControlMessage.self, from: commandData), command)

        var acknowledgement = CamHubControlMessage(type: "ptz_applied")
        acknowledgement.value = 3.25
        acknowledgement.stringValue = "0.31,0.69"
        acknowledgement.requestId = 42
        let ackData = try JSONEncoder().encode(acknowledgement)
        XCTAssertEqual(try JSONDecoder().decode(CamHubControlMessage.self, from: ackData), acknowledgement)
    }

    private func goldenVector(_ name: String) throws -> Data {
        try Data(hex: goldenFixture(name))
    }

    private func goldenFixture(_ name: String) throws -> String {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let fixture = directory.appendingPathComponent(
                "protocol/src/test/resources/camhub-wire-golden.properties"
            )
            if FileManager.default.fileExists(atPath: fixture.path) {
                let contents = try String(contentsOf: fixture, encoding: .utf8)
                let line = contents.split(whereSeparator: \.isNewline).first {
                    $0.hasPrefix("\(name)=")
                }
                let value = try XCTUnwrap(line).split(separator: "=", maxSplits: 1).last
                return String(try XCTUnwrap(value))
            }
            directory.deleteLastPathComponent()
        }
        throw CamHubProtocolError.invalidPayload
    }
}

private extension Data {
    init(hex: String) throws {
        guard hex.count.isMultiple(of: 2) else { throw CamHubProtocolError.invalidPayload }
        var result = Data()
        result.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else {
                throw CamHubProtocolError.invalidPayload
            }
            result.append(byte)
            index = next
        }
        self = result
    }
}
