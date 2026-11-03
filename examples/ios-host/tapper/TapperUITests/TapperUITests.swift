// Answers system UI on the simulator, as a user would (#114, #125, #129, #134). The iOS self-tests ask for
// it with `[gallery] DRIVE <action>` lines, and run-ios-app.sh's `drive` runs this UI test
// through tap.sh with TEST_RUNNER_TAP=<what>. Nothing else can: simctl has no tap, `simctl
// privacy` has no notifications service, and a logic test may not use XCUIApplication
// (measured).
//
//   deny | allow   SpringBoard's permission alert (#114)
//   photo          the newest photo in the app's PHPickerViewController (#125)
//   cancel         the Cancel button of the picker the app shows (#125)
//   document       the file named TEST_RUNNER_FILE in Files' "On My iPhone", in the app's
//                  UIDocumentPickerViewController (#125)
//   export         Save in "On My iPhone", in the app's exporting document picker (#125)
//   <bundle id>|<label>  the cell so labelled in that app, brought to the front: the share
//                  sheet's "Copy" (#134)
//   app <bundle id> <label>|<label>…  each of that app's buttons in turn: the camera screen's
//                  shutter, then "Use Photo" (#129). "?label" is tapped only if it comes up in 10 s.
//
// Every form prints "[tapper] done" once its taps are made, or "[tapper] failed"; tap.sh reads
// those, not xcodebuild's verdict (XCTest's tear-down takes 300 s, see tap.sh).
//
// A UI-testing bundle with no target application: it reaches SpringBoard, or the app named by
// TEST_RUNNER_APP, by bundle id. The pickers are other processes' (PhotosUI's, the document
// browser's), but their elements are in the app's accessibility tree, so XCUITest taps them.
import XCTest

final class TapperUITests: XCTestCase {
  func testAnswerTheAlert() throws {
    // A failed step ends the test, so no "[tapper] done" line follows it.
    continueAfterFailure = false
    let env = ProcessInfo.processInfo.environment
    let what = env["TAP"] ?? "allow"
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
      XCTAssertTrue(element.waitForExistence(timeout: 20), "no \"\(label)\" appeared in 20 s")
      // At its centre, not element.tap(): that looks the cell up again after the tap, and the sheet
      // the tap closed has taken it away, which fails the test (measured, the first run).
      element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
      print("[tapper] tapped \(label) in \(what[..<bar])")
      done()
    }
    if what == "deny" || what == "allow" {
      // The apostrophe is U+2019, as SpringBoard writes it.
      let label = what == "deny" ? "Don\u{2019}t Allow" : "Allow"
      let button = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons[label]
      XCTAssertTrue(button.waitForExistence(timeout: 20), "no \"\(label)\" button appeared in 20 s")
      button.tap()
      print("[tapper] tapped \(label)")
      done()
    }
    let app = XCUIApplication(bundleIdentifier: env["APP"] ?? "")
    switch what {
    case "photo":
      // Newest first: the photo build-app.sh added before the launch.
      let photo = app.images.matching(identifier: "PXGGridLayout-Info").firstMatch
      XCTAssertTrue(photo.waitForExistence(timeout: 20), "no photo in the picker in 20 s")
      let label = photo.label
      photo.tap()
      print("[tapper] tapped photo \"\(label)\"")
    case "cancel":
      let cancel = app.buttons["Cancel"].firstMatch
      XCTAssertTrue(cancel.waitForExistence(timeout: 20), "no Cancel button in 20 s")
      cancel.tap()
      print("[tapper] tapped Cancel")
    case "document", "export":
      // The browser opens where it was last; from Browse, "On My iPhone" is one tap.
      let name = env["FILE"] ?? ""
      let file = app.cells.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
      let save = app.buttons["Save"].firstMatch
      let there = what == "document" ? file : save
      if !there.waitForExistence(timeout: 5) || what == "export" {
        let browse = app.buttons["Browse"]
        XCTAssertTrue(browse.waitForExistence(timeout: 20), "no Browse tab in 20 s")
        browse.tap()
        let local = app.staticTexts["On My iPhone"].firstMatch
        XCTAssertTrue(local.waitForExistence(timeout: 10), "no \"On My iPhone\" in Browse in 10 s")
        local.tap()
      }
      XCTAssertTrue(there.waitForExistence(timeout: 10), "no \(what == "document" ? name : "Save") in 10 s")
      if what == "export" {
        // Tapped while "On My iPhone" is still sliding in, Save is ignored (measured, #125): wait
        // for its contents, and tap again while the browser is still up.
        _ = app.cells.firstMatch.waitForExistence(timeout: 10)
        sleep(1)
        var taps = 0
        while save.exists && taps < 4 {
          save.tap()
          taps += 1
          sleep(2)
        }
        XCTAssertFalse(save.exists, "Save is still there after \(taps) taps")
        print("[tapper] tapped Save (\(taps) taps)")
      } else {
        there.tap()
        print("[tapper] tapped \(name)")
      }
    default:
      XCTFail("unknown TAP: \(what)")
      return
    }
    done()
  }

  /// Ends the run here: a UI test that touched an app, SpringBoard included, took 300 s to tear
  /// down on Xcode 27 (measured, #125), which held up every DRIVE line after it. tap.sh reads the
  /// "[tapper] tapped" line, not xcodebuild's verdict.
  private func done() -> Never {
    print("[tapper] done")
    fflush(stdout)
    exit(0)
  }
}
