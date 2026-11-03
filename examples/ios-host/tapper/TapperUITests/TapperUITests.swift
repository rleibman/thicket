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
    // "<bundle id>|<label>": brings that app to the front and taps the element with that label, in
    // whatever the app shows — the share sheet's "Copy", for one (#134). The runner itself comes to
    // the front when it starts, so the app is activated first; activating does not relaunch it.
    if let bar = what.firstIndex(of: "|") {
      let app = XCUIApplication(bundleIdentifier: String(what[..<bar]))
      let label = String(what[what.index(after: bar)...])
      app.activate()
      // A cell: the share sheet's actions are cells ('actionGroupCell'), and an app's own button of the
      // same name — the gallery has a "Copy" — would otherwise match first.
      let element = app.cells.matching(NSPredicate(format: "label == %@", label)).firstMatch
      XCTAssertTrue(element.waitForExistence(timeout: 20), "no \"\(label)\" appeared in 20 s")
      // At its centre, not element.tap(): that looks the cell up again after the tap, and the sheet
      // the tap closed has taken it away, which fails the test (measured, the first run).
      element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      print("[tapper] tapped \(label) in \(what[..<bar])")
      return
    }
    // The apostrophe is U+2019, as SpringBoard writes it.
    let label = what == "deny" ? "Don\u{2019}t Allow" : "Allow"
    let button = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons[label]
    XCTAssertTrue(button.waitForExistence(timeout: 20), "no \"\(label)\" button appeared in 20 s")
    button.tap()
    print("[tapper] tapped \(label)")
  }
}
