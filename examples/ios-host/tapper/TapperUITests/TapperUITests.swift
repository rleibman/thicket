// Answers system UI on the simulator, as a user would (#114, #125). The iOS self-tests ask for
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
//
// A UI-testing bundle with no target application: it reaches SpringBoard, or the app named by
// TEST_RUNNER_APP, by bundle id. The pickers are other processes' (PhotosUI's, the document
// browser's), but their elements are in the app's accessibility tree, so XCUITest taps them.
import XCTest

final class TapperUITests: XCTestCase {
  func testAnswerTheAlert() throws {
    let env = ProcessInfo.processInfo.environment
    let what = env["TAP"] ?? "allow"
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
      there.tap()
      print("[tapper] tapped \(what == "document" ? name : "Save")")
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
    fflush(stdout)
    exit(0)
  }
}
