import SwiftUI
import XCTest
@testable import Pairline

/// Two-pane layouts on large displays and iPhone Duo's fold (D-063). Sizes are iPhone Duo's safe content areas from
/// Apple's design resource: 867 × 669 pt on the inner display held wide (an 84 pt side bar), 669 × 772 pt held upright.
final class FoldLayoutTests: XCTestCase {
    private let all: Axis.Set = [.horizontal, .vertical]

    func testSplitsOnTheFoldWhenThereIsOne() throws {
        // Book pose: the fold runs top to bottom at 475.5 pt, 27 pt wide with the system's clearance.
        let fold = CGRect(x: 462, y: 0, width: 27, height: 669)
        let split = try XCTUnwrap(PaneSplit(size: CGSize(width: 867, height: 669), fold: fold, axes: all))
        XCTAssertEqual(split.axis, .horizontal)
        XCTAssertEqual(split.firstLength, 462)
        XCTAssertEqual(split.gap, 27)
    }

    func testAFlatFoldStillGetsTheMinimumGap() throws {
        // Lying flat the region may have no width; the panes still leave room for it, so nothing moves when it bends.
        let fold = CGRect(x: 475.5, y: 0, width: 0, height: 669)
        let split = try XCTUnwrap(PaneSplit(size: CGSize(width: 867, height: 669), fold: fold, axes: all))
        XCTAssertEqual(split.axis, .horizontal)
        XCTAssertEqual(split.firstLength, 475.5 - PaneSplit.minimumGap / 2)
        XCTAssertEqual(split.gap, PaneSplit.minimumGap)
    }

    func testTabletopStacksThePanes() throws {
        // Held upright and folded like a laptop: the fold runs across, content goes above it and controls below.
        let fold = CGRect(x: 0, y: 380, width: 669, height: 27)
        let split = try XCTUnwrap(PaneSplit(size: CGSize(width: 669, height: 772), fold: fold, axes: all))
        XCTAssertEqual(split.axis, .vertical)
        XCTAssertEqual(split.firstLength, 380)
        XCTAssertNil(PaneSplit(size: CGSize(width: 669, height: 772), fold: fold, axes: .horizontal))
    }

    func testWithoutAFoldThePanesShareTheSpace() throws {
        // iPad mini in landscape, and iPhone Duo before iOS 27.1.
        let split = try XCTUnwrap(PaneSplit(size: CGSize(width: 1133, height: 700), fold: nil, axes: all))
        XCTAssertEqual(split.axis, .horizontal)
        XCTAssertEqual(split.firstLength, (1133 - PaneSplit.minimumGap) / 2)
        let upright = try XCTUnwrap(PaneSplit(size: CGSize(width: 744, height: 1000), fold: nil, axes: all))
        XCTAssertEqual(upright.axis, .vertical)
    }

    func testTooSmallForTwoPanesStaysInOneColumn() {
        // iPhone Duo's outer display held sideways, and a short, wide window.
        XCTAssertNil(PaneSplit(size: CGSize(width: 594, height: 466), fold: nil, axes: all))
        XCTAssertNil(PaneSplit(size: CGSize(width: 560, height: 400), fold: nil, axes: all))
        // A fold outside this space (the app beside it in Split View) doesn't count.
        let fold = CGRect(x: 900, y: 0, width: 27, height: 669)
        let split = PaneSplit(size: CGSize(width: 867, height: 669), fold: fold, axes: all)
        XCTAssertEqual(split?.firstLength, (867 - PaneSplit.minimumGap) / 2)
    }

    func testKeysShrinkToFitAShortPane() {
        XCTAssertEqual(DialerView.keySize(fitting: CGSize(width: 400, height: 700)), DialerView.fullKey)
        // The lower half of the inner display held upright.
        let key = DialerView.keySize(fitting: CGSize(width: 669, height: 370))
        XCTAssertLessThan(key, DialerView.fullKey)
        XCTAssertGreaterThanOrEqual(key, 44)
        XCTAssertLessThanOrEqual(5 * key + 60 * key / DialerView.fullKey + 30, 370.5)
    }
}
