// thicket-pickers-apple: the system's photo picker, document picker and save dialog on macOS
// and iOS (#125, docs/16-device-services.md §16.3, §16.10 item 5).
//
// PUSH, ON THE MAIN THREAD ONLY, like thicket-device-apple: every callback into Scala is made on
// the main thread, the one non-Scala thread allowed to enter Scala (S1), and the Scala side wraps
// each in GcState.guarded (S3).
//
// Swift rather than a C helper (clipboard-apple's shape): the pickers answer through delegate
// objects (PHPickerViewControllerDelegate, UIDocumentPickerDelegate) and completion blocks
// (beginSheetModal, loadFileRepresentation), which C cannot write without an Objective-C file,
// and Scala Native compiles .c but not .m. Swift, beside the other Apple libraries, keeps one
// language and one build shape.
//
// A picked file is a HOLD. Scala copies it into the app's cache (thicket.pickers.PickedFiles) on
// a worker thread and releases the hold from there. PHPickerViewController's temporary file is
// deleted when loadFileRepresentation's callback returns, so that callback - on a queue of
// its own - waits for the release. `done` follows the last release.
//
// One picker at a time: while one is up, every request returns 0 and shows nothing.
//
// The declarations are checked against tools/shim-gen's PickersAbi by PickersConsistencySpec.

import Foundation
import UniformTypeIdentifiers

#if os(iOS)
  import PhotosUI
  import UIKit
#else
  import AppKit
#endif

public typealias thk_pick_int_cb = @convention(c) (Int64, Int32) -> Void

// MARK: - holds

private final class Hold {
  let path: UnsafeMutablePointer<CChar>
  let index: Int32
  let released: () -> Void
  init(path: String, index: Int32, released: @escaping () -> Void) {
    self.path = strdup(path)
    self.index = index
    self.released = released
  }
}

private let holdsLock = NSLock()
private var holds: [Int32: Hold] = [:]
private var nextHold: Int32 = 1

private func hold(_ h: Hold) -> Int32 {
  holdsLock.lock()
  defer { holdsLock.unlock() }
  let id = nextHold
  nextHold += 1
  holds[id] = h
  return id
}

private func lookup(_ id: Int32) -> Hold? {
  holdsLock.lock()
  defer { holdsLock.unlock() }
  return holds[id]
}

// MARK: - a pick in progress

/// One request, from its showing to its `done`. Files are counted on the main thread; the group is
/// left once per file, at its release, and once per pending load (iOS photos).
private final class Session {
  let file: thk_pick_int_cb
  let done: thk_pick_int_cb
  let ctx: Int64
  let group = DispatchGroup()
  var count: Int32 = 0
  var description: String
  init(file: @escaping thk_pick_int_cb, done: @escaping thk_pick_int_cb, ctx: Int64, description: String) {
    self.file = file
    self.done = done
    self.ctx = ctx
    self.description = description
  }

  /// On the main thread: hands Scala one file. `released` runs at its release, on Scala's thread.
  func deliver(path: String, index: Int, released: @escaping () -> Void = {}) {
    group.enter()
    count += 1
    let id = hold(Hold(path: path, index: Int32(index)) { [group] in
      released()
      group.leave()
    })
    file(ctx, id)
  }

  /// After every file has been handed over (or none): `done` once they are all released.
  func finish() {
    group.notify(queue: .main) { [self] in
      if current === self { current = nil }
      done(ctx, count)
    }
  }
}

/// A save in progress: answered once.
private final class SaveRequest {
  let answered: thk_pick_int_cb
  let ctx: Int64
  let description: String
  init(answered: @escaping thk_pick_int_cb, ctx: Int64, description: String) {
    self.answered = answered
    self.ctx = ctx
    self.description = description
  }
}

/// The picker that is up, if any: a Session or a SaveRequest. Main thread only.
private var current: AnyObject?
private var shown: Int32 = 0
private var savePath: UnsafeMutablePointer<CChar>?

private func answerSave(_ request: SaveRequest, _ code: Int32, path: String? = nil) {
  if current === request { current = nil }
  savePath = path.map { strdup($0) }
  request.answered(request.ctx, code)
  if let p = savePath { free(p) }
  savePath = nil
}

private func contentTypes(_ mimes: String) -> [UTType] {
  mimes.split(separator: "\n").compactMap { UTType(mimeType: String($0)) }
}

private func typeNames(_ types: [UTType]) -> String {
  types.isEmpty ? "any" : types.map(\.identifier).joined(separator: ",")
}

// MARK: - iOS

#if os(iOS)

  private func presenter() -> UIViewController? {
    let windows = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap(\.windows)
    var vc = (windows.first { $0.isKeyWindow } ?? windows.first)?.rootViewController
    // A picker still being dismissed is no place to present the next one from.
    while let presented = vc?.presentedViewController, !presented.isBeingDismissed { vc = presented }
    return vc
  }

  /// Presents `picker` once no transition is running over `over`. A request made while the last
  /// picker is still animating away is otherwise dropped by UIKit, silently, with no picker shown
  /// and no callback (measured, #125: "Pick a photo" pressed again right after a pick).
  private func show(_ picker: UIViewController, over: UIViewController, attempts: Int = 100) {
    let busy = over.transitionCoordinator != nil || over.presentedViewController?.isBeingDismissed == true
    if busy && attempts > 0 {
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { show(picker, over: over, attempts: attempts - 1) }
    } else {
      over.present(picker, animated: true)
    }
  }

  /// The delegate of every picker: one object, since only one picker is up at a time.
  private final class Delegate: NSObject, PHPickerViewControllerDelegate, UIDocumentPickerDelegate,
    UIAdaptivePresentationControllerDelegate
  {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
      picker.dismiss(animated: true)
      guard let session = current as? Session else { return }
      for (i, result) in results.enumerated() {
        let provider = result.itemProvider
        guard provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { continue }
        session.group.enter()
        provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
          // On a queue of the provider's: the file at `url` is deleted when this returns, so it
          // waits until Scala has copied it.
          if let url = url {
            let copied = DispatchSemaphore(value: 0)
            DispatchQueue.main.async { session.deliver(path: url.path, index: i) { copied.signal() } }
            copied.wait()
          }
          session.group.leave()
        }
      }
      session.finish()
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
      if let request = current as? SaveRequest {
        answerSave(request, 1)
      } else if let session = current as? Session {
        // asCopy: the system has already copied each file into this app's tmp; that copy goes
        // once Scala has its own.
        for (i, url) in urls.enumerated() {
          session.deliver(path: url.path, index: i) { try? FileManager.default.removeItem(at: url) }
        }
        session.finish()
      }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancelled() }

    // Swiped down: neither picker's own callback may come.
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { cancelled() }

    private func cancelled() {
      if let request = current as? SaveRequest {
        answerSave(request, 0)
      } else if let session = current as? Session {
        session.finish()
      }
    }
  }

  private let delegate = Delegate()

  private func present(_ picker: UIViewController, _ request: AnyObject) -> Int32 {
    guard current == nil, let over = presenter() else { return 0 }
    current = request
    shown += 1
    picker.presentationController?.delegate = delegate
    show(picker, over: over)
    return 1
  }

  private func impl_photos(
    _ limit: Int32, _ file: @escaping thk_pick_int_cb, _ done: @escaping thk_pick_int_cb, _ ctx: Int64
  ) -> Int32 {
    var config = PHPickerConfiguration()
    config.selectionLimit = Int(max(limit, 1))
    config.filter = .images
    let picker = PHPickerViewController(configuration: config)
    picker.delegate = delegate
    let session = Session(file: file, done: done, ctx: ctx, description: "photos limit=\(max(limit, 1))")
    return present(picker, session)
  }

  private func impl_documents(
    _ types: UnsafePointer<CChar>, _ multiple: Int32, _ file: @escaping thk_pick_int_cb,
    _ done: @escaping thk_pick_int_cb, _ ctx: Int64
  ) -> Int32 {
    let wanted = contentTypes(String(cString: types))
    let picker = UIDocumentPickerViewController(forOpeningContentTypes: wanted.isEmpty ? [.item] : wanted, asCopy: true)
    picker.allowsMultipleSelection = multiple != 0
    picker.delegate = delegate
    let session = Session(
      file: file, done: done, ctx: ctx, description: "documents types=\(typeNames(wanted)) multiple=\(multiple)")
    return present(picker, session)
  }

  private func impl_save(
    _ file: UnsafePointer<CChar>, _ mime: UnsafePointer<CChar>, _ answered: @escaping thk_pick_int_cb, _ ctx: Int64
  ) -> Int32 {
    let url = URL(fileURLWithPath: String(cString: file))
    let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
    picker.delegate = delegate
    let request = SaveRequest(answered: answered, ctx: ctx, description: "export name=\(url.lastPathComponent)")
    return present(picker, request)
  }

  private func impl_test_cancel() -> Int32 {
    0
  }

  private func impl_test_choose(_ paths: UnsafePointer<CChar>) -> Int32 {
    0
  }

#else

  // MARK: - macOS

  /// The panel that is up, and what the self-test chose in its place (thk_pick_test_choose).
  private var panel: NSSavePanel?
  private var chosen: [URL]?

  private func window() -> NSWindow? {
    NSApp.keyWindow ?? NSApp.mainWindow ?? NSApp.windows.first { $0.isVisible && !($0 is NSPanel) }
  }

  /// Shows `p` as a sheet on the app's window; `answered` gets the URLs, empty for a cancel.
  private func begin(_ p: NSSavePanel, _ request: AnyObject, _ answered: @escaping ([URL]) -> Void) -> Int32 {
    guard current == nil, let w = window() else { return 0 }
    current = request
    panel = p
    shown += 1
    p.beginSheetModal(for: w) { response in
      let urls: [URL]
      if let c = chosen {
        urls = c
      } else if response == .OK {
        let open = (p as? NSOpenPanel)?.urls ?? []
        urls = open.isEmpty ? (p.url.map { [$0] } ?? []) : open
      } else {
        urls = []
      }
      chosen = nil
      panel = nil
      answered(urls)
    }
    return 1
  }

  private func open(_ p: NSOpenPanel, _ session: Session, limit: Int) -> Int32 {
    p.canChooseFiles = true
    p.canChooseDirectories = false
    return begin(p, session) { urls in
      for (i, url) in urls.prefix(limit).enumerated() {
        // Unsandboxed, the scope is not needed and the call answers false; sandboxed, it is.
        let scoped = url.startAccessingSecurityScopedResource()
        session.deliver(path: url.path, index: i) { if scoped { url.stopAccessingSecurityScopedResource() } }
      }
      session.finish()
    }
  }

  private func impl_photos(
    _ limit: Int32, _ file: @escaping thk_pick_int_cb, _ done: @escaping thk_pick_int_cb, _ ctx: Int64
  ) -> Int32 {
    let n = Int(max(limit, 1))
    let p = NSOpenPanel()
    p.allowedContentTypes = [.image]
    p.allowsMultipleSelection = n > 1
    let session = Session(
      file: file, done: done, ctx: ctx, description: "open types=public.image multiple=\(n > 1 ? 1 : 0)")
    return open(p, session, limit: n)
  }

  private func impl_documents(
    _ types: UnsafePointer<CChar>, _ multiple: Int32, _ file: @escaping thk_pick_int_cb,
    _ done: @escaping thk_pick_int_cb, _ ctx: Int64
  ) -> Int32 {
    let wanted = contentTypes(String(cString: types))
    let p = NSOpenPanel()
    if !wanted.isEmpty { p.allowedContentTypes = wanted }
    p.allowsMultipleSelection = multiple != 0
    let session = Session(
      file: file, done: done, ctx: ctx, description: "open types=\(typeNames(wanted)) multiple=\(multiple)")
    return open(p, session, limit: multiple != 0 ? Int.max : 1)
  }

  private func impl_save(
    _ file: UnsafePointer<CChar>, _ mime: UnsafePointer<CChar>, _ answered: @escaping thk_pick_int_cb, _ ctx: Int64
  ) -> Int32 {
    let name = URL(fileURLWithPath: String(cString: file)).lastPathComponent
    let p = NSSavePanel()
    p.nameFieldStringValue = name
    let types = contentTypes(String(cString: mime))
    if !types.isEmpty { p.allowedContentTypes = types }
    p.canCreateDirectories = true
    let request = SaveRequest(answered: answered, ctx: ctx, description: "save types=\(typeNames(types))")
    return begin(p, request) { urls in
      if let url = urls.first { answerSave(request, 2, path: url.path) } else { answerSave(request, 0) }
    }
  }

  private func impl_test_cancel() -> Int32 {
    guard let p = panel else { return 0 }
    p.cancel(nil)
    return 1
  }

  private func impl_test_choose(_ paths: UnsafePointer<CChar>) -> Int32 {
    guard let p = panel else { return 0 }
    chosen = String(cString: paths).split(separator: "\n").map { URL(fileURLWithPath: String($0)) }
    p.cancel(nil)
    return 1
  }

#endif

// MARK: - both

@_cdecl("thk_pick_photos")
public func thk_pick_photos(_ limit: Int32, _ file: @escaping thk_pick_int_cb, _ done: @escaping thk_pick_int_cb, _ ctx: Int64) -> Int32 {
  impl_photos(limit, file, done, ctx)
}

@_cdecl("thk_pick_documents")
public func thk_pick_documents(
  _ types: UnsafePointer<CChar>, _ multiple: Int32, _ file: @escaping thk_pick_int_cb,
  _ done: @escaping thk_pick_int_cb, _ ctx: Int64
) -> Int32 {
  impl_documents(types, multiple, file, done, ctx)
}

@_cdecl("thk_pick_save")
public func thk_pick_save(_ file: UnsafePointer<CChar>, _ mime: UnsafePointer<CChar>, _ answered: @escaping thk_pick_int_cb, _ ctx: Int64) -> Int32 {
  impl_save(file, mime, answered, ctx)
}

@_cdecl("thk_pick_path")
public func thk_pick_path(_ hold: Int32) -> UnsafePointer<CChar>? {
  lookup(hold).map { UnsafePointer($0.path) }
}

@_cdecl("thk_pick_index")
public func thk_pick_index(_ hold: Int32) -> Int32 {
  lookup(hold)?.index ?? -1
}

@_cdecl("thk_pick_release")
public func thk_pick_release(_ hold: Int32) {
  holdsLock.lock()
  let h = holds.removeValue(forKey: hold)
  holdsLock.unlock()
  guard let h = h else { return }
  h.released()
  free(h.path)
}

@_cdecl("thk_pick_save_path")
public func thk_pick_save_path() -> UnsafePointer<CChar>? {
  savePath.map { UnsafePointer($0) }
}

// MARK: - testing

@_cdecl("thk_pick_test_cancel")
public func thk_pick_test_cancel() -> Int32 {
  impl_test_cancel()
}

@_cdecl("thk_pick_test_choose")
public func thk_pick_test_choose(_ paths: UnsafePointer<CChar>) -> Int32 {
  impl_test_choose(paths)
}

private var described: UnsafeMutablePointer<CChar>?

@_cdecl("thk_pick_test_shown")
public func thk_pick_test_shown() -> Int32 {
  shown
}

@_cdecl("thk_pick_test_describe")
public func thk_pick_test_describe() -> UnsafePointer<CChar>? {
  var text = ""
  if let s = current as? Session {
    text = s.description
  } else if let r = current as? SaveRequest {
    text = r.description
  }
  #if os(macOS)
    if let p = panel {
      text += " sheet=\(p.isSheet ? 1 : 0)"
      if !(p is NSOpenPanel) { text += " name=\(p.nameFieldStringValue)" }
    }
  #endif
  if let d = described { free(d) }
  described = strdup(text)
  return described.map { UnsafePointer($0) }
}
