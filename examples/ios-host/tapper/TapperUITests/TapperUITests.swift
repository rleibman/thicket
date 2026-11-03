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
//
// And TEST_RUNNER_TAP="<bundle id>|<label>" (#134) brings that app to the front and taps the
// cell so labelled: the share sheet's "Copy".
//
// Every form prints "[tapper] done" once its taps are made, or "[tapper] failed"; tap.sh reads
// those, not xcodebuild's verdict (XCTest's tear-down takes 300 s, see tap.sh).
import XCTest

final class TapperUITests: XCTestCase {
  func testAnswerTheAlert() throws {
    let what = ProcessInfo.processInfo.environment["TAP"] ?? "allow"
    // Before the '|' form: "app …" has '|' between its labels.
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
      guard element.waitForExistence(timeout: 20) else {
        print("[tapper] failed")
        XCTFail("no \"\(label)\" appeared in 20 s")
        return
      }
      // At its centre, not element.tap(): that looks the cell up again after the tap, and the sheet
      // the tap closed has taken it away, which fails the test (measured, the first run).
      element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      print("[tapper] tapped \(label) in \(what[..<bar])")
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
