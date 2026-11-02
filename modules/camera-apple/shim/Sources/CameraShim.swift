// thicket-camera-apple: taking a photo on iOS with UIImagePickerController (#129,
// docs/16-device-services.md §16.3, §16.10 item 5).
//
// ON THE MAIN THREAD ONLY, like thicket-device-apple: Scala calls in on the main thread, and the
// picker's delegate answers there, the one non-Scala thread allowed to enter Scala (S1); the Scala
// side wraps the answer in GcState.guarded (S3).
//
// Swift rather than a C helper or Scala alone: the picker answers through a delegate, and
// UIImagePickerControllerDelegate is an Objective-C protocol. Conforming to it needs an
// Objective-C class: from Scala Native that would be objc_allocateClassPair, class_addMethod with
// hand-written IMPs, class_addProtocol and objc_msgSend casts for every UIKit call, unchecked by
// any compiler. The answer also reads a UIImage out of the info dictionary and calls
// jpegData(compressionQuality:). Swift, beside the other Apple libraries, keeps one language and
// one build shape.
//
// The photo is handed over as a file, never as bytes: the library writes the JPEG to a file of
// its own in NSTemporaryDirectory(), and Scala copies it into the app's cache through
// PickedFiles and deletes it. The camera permission (AVCaptureDevice) is thicket-device-apple's.
//
// The declarations are checked against tools/shim-gen's CameraAbi by CameraConsistencySpec.

import Foundation
import UIKit

public typealias thk_cam_text_cb = @convention(c) (Int64, UnsafePointer<CChar>) -> Void

/// The delegate of the one picker that may be up. The picker holds its delegate weakly, so this
/// library holds it until the answer.
private final class Answer: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
  let done: thk_cam_text_cb
  let ctx: Int64

  init(_ done: @escaping thk_cam_text_cb, _ ctx: Int64) {
    self.done = done
    self.ctx = ctx
  }

  func imagePickerController(
    _ picker: UIImagePickerController,
    didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
  ) {
    let path = (info[.originalImage] as? UIImage).flatMap(write) ?? ""
    finish(picker, path)
  }

  func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
    finish(picker, "")
  }

  /// The JPEG at quality 0.9 in a file of its own, or nil if it could not be made or written.
  private func write(_ image: UIImage) -> String? {
    guard let data = image.jpegData(compressionQuality: 0.9) else { return nil }
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
      .appendingPathComponent("thicket-camera-\(UUID().uuidString).jpg")
    do {
      try data.write(to: url, options: .atomic)
      return url.path
    } catch {
      return nil
    }
  }

  /// Answers once the picker is gone, so a photo taken next presents over the app, not over it.
  private func finish(_ picker: UIImagePickerController, _ path: String) {
    picker.dismiss(animated: true) {
      current = nil
      path.withCString { self.done(self.ctx, $0) }
    }
  }
}

/// The request that is up, if any: one at a time.
private var current: Answer?

/// The controller to present from: the key window's root, or whatever it is already presenting.
private func presenter() -> UIViewController? {
  let windows = UIApplication.shared.connectedScenes
    .compactMap { $0 as? UIWindowScene }
    .flatMap { $0.windows }
  var top = (windows.first { $0.isKeyWindow } ?? windows.first)?.rootViewController
  while let next = top?.presentedViewController { top = next }
  return top
}

@_cdecl("thk_cam_available")
public func thk_cam_available() -> Int32 {
  UIImagePickerController.isSourceTypeAvailable(.camera) ? 1 : 0
}

@_cdecl("thk_cam_capture")
public func thk_cam_capture(_ done: @escaping thk_cam_text_cb, _ ctx: Int64) -> Int32 {
  guard current == nil,
    UIImagePickerController.isSourceTypeAvailable(.camera),
    let from = presenter()
  else { return 0 }
  let answer = Answer(done, ctx)
  let picker = UIImagePickerController()
  picker.sourceType = .camera
  picker.delegate = answer
  current = answer
  from.present(picker, animated: true)
  return 1
}

// MARK: - testing

@_cdecl("thk_cam_test_presented")
public func thk_cam_test_presented() -> Int32 {
  current == nil ? 0 : 1
}
