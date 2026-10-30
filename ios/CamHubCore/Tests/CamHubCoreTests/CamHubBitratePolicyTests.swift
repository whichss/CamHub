import XCTest
@testable import CamHubCore

final class CamHubBitratePolicyTests: XCTestCase {
    func testKeyframeRecoveryGateWaitsForKeyframeAfterStartAndGap() {
        var gate = CamHubKeyframeRecoveryGate()

        XCTAssertFalse(gate.shouldAppend(isKeyFrame: false))
        XCTAssertTrue(gate.shouldAppend(isKeyFrame: true))
        XCTAssertTrue(gate.shouldAppend(isKeyFrame: false))

        gate.requireKeyFrame()
        XCTAssertFalse(gate.shouldAppend(isKeyFrame: false))
        XCTAssertTrue(gate.shouldAppend(isKeyFrame: true))
        XCTAssertTrue(gate.shouldAppend(isKeyFrame: false))
    }

    func testKeyframeRecoveryGateCanBeResetMoreThanOnce() {
        var gate = CamHubKeyframeRecoveryGate()

        XCTAssertTrue(gate.shouldAppend(isKeyFrame: true))
        gate.requireKeyFrame()
        gate.requireKeyFrame()
        XCTAssertFalse(gate.shouldAppend(isKeyFrame: false))
        XCTAssertTrue(gate.shouldAppend(isKeyFrame: true))
    }

    func testSelectionUsesLowestExplicitSubscriberRequest() {
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps([]), 4)
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps([nil, nil]), 4)
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps([nil, 8, 2]), 2)
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps([nil, 8]), 8)
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps([0, 30]), 1)
    }

    func testRequestsAreBoundedAndNonfiniteValuesRejected() {
        XCTAssertEqual(CamHubBitratePolicy.validatedRequest(0), 1)
        XCTAssertEqual(CamHubBitratePolicy.validatedRequest(25), 20)
        XCTAssertEqual(CamHubBitratePolicy.validatedRequest(3.9), 3)
        XCTAssertNil(CamHubBitratePolicy.validatedRequest(.nan))
        XCTAssertNil(CamHubBitratePolicy.validatedRequest(.infinity))
    }

    func testSelectionTracksSubscribersLeaving() {
        var subscribers: [Int?] = [nil, 8, 2]
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps(subscribers), 2)
        subscribers.removeLast()
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps(subscribers), 8)
        subscribers.removeLast()
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps(subscribers), CamHubBitratePolicy.defaultMbps)
        subscribers.removeAll()
        XCTAssertEqual(CamHubBitratePolicy.selectedMbps(subscribers), CamHubBitratePolicy.defaultMbps)
    }
}
