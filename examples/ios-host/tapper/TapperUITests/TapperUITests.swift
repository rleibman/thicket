// Answers a system alert on the simulator, as a user would (#114). The iOS self-tests ask for
// it with a `[gallery] DRIVE deny` or `DRIVE allow` line; run-ios-app.sh runs this UI test with
// TEST_RUNNER_TAP=deny|allow. Nothing else can: simctl has no tap, `simctl privacy` has no
// notifications service, and a logic test may not use XCUIApplication (measured).
//
// A UI-testing bundle with no target application: it reaches SpringBoard, which owns the alert,
// by bundle id.
//
// It also taps an app's own buttons (#129): TEST_RUNNER_TAP="app <bundle id> <label>|<label>…"
// taps each in turn, waiting up to 20 s for each, in that app (the camera screen's shutter and
// "Use Photo", in the gallery); a label written "?label" is tapped only if it comes up in 10 s,
// and skipped otherwise. A button that never appears fails the test, and the labels of
// the buttons that were there are printed, to say what to ask for instead.
import XCTest

final class TapperUITests: XCTestCase {
  func testAnswerTheAlert() throws {
    let what = ProcessInfo.processInfo.environment["TAP"] ?? "allow"
    if what.hasPrefix("app ") {
      let parts = what.split(separator: " ", maxSplits: 2).map(String.init)
      XCTAssertEqual(parts.count, 3, "want \"app <bundle id> <label>|<label>…\", got \"\(what)\"")
      let app = XCUIApplication(bundleIdentifier: parts[1])
      for wanted in parts[2].split(separator: "|").map(String.init) {
        // "?label": tapped if it comes up within 10 s, skipped otherwise.
        let optional = wanted.hasPrefix("?")
        let label = optional ? String(wanted.dropFirst()) : wanted
        let button = app.buttons[label]
        if optional && !button.waitForExistence(timeout: 10) {
          print("[tapper] no \(label), which was optional")
          continue
        }
        guard button.waitForExistence(timeout: 20) else {
          let there = app.buttons.allElementsBoundByIndex.map { $0.label }
          print("[tapper] no \(label); buttons: \(there)")
          print("[tapper] failed")
          XCTFail("no \"\(label)\" button appeared in 20 s; there were \(there)")
          return
        }
        button.tap()
        print("[tapper] tapped \(label)")
      }
      print("[tapper] done")
      return
    }
    // The apostrophe is U+2019, as SpringBoard writes it.
    let label = what == "deny" ? "Don\u{2019}t Allow" : "Allow"
    let button = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons[label]
    guard button.waitForExistence(timeout: 20) else {
      print("[tapper] failed")
      XCTFail("no \"\(label)\" button appeared in 20 s")
      return
    }
    button.tap()
    print("[tapper] tapped \(label)")
    print("[tapper] done")
  }
}
