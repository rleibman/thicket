// Answers a system alert on the simulator, as a user would (#114). The iOS self-tests ask for
// it with a `[gallery] DRIVE deny` or `DRIVE allow` line; run-ios-app.sh runs this UI test with
// TEST_RUNNER_TAP=deny|allow. Nothing else can: simctl has no tap, `simctl privacy` has no
// notifications service, and a logic test may not use XCUIApplication (measured).
//
// A UI-testing bundle with no target application: it reaches SpringBoard, which owns the alert,
// by bundle id.
import XCTest

final class TapperUITests: XCTestCase {
  func testAnswerTheAlert() throws {
    let what = ProcessInfo.processInfo.environment["TAP"] ?? "allow"
    // The apostrophe is U+2019, as SpringBoard writes it.
    let label = what == "deny" ? "Don\u{2019}t Allow" : "Allow"
    let button = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons[label]
    XCTAssertTrue(button.waitForExistence(timeout: 20), "no \"\(label)\" button appeared in 20 s")
    button.tap()
    print("[tapper] tapped \(label)")
  }
}
