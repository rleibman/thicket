import CoreGraphics
import Foundation

// The half of the Apple shim that knows nothing of AppKit or UIKit (#79). `build-shim.sh`
// compiles it with exactly one of `Shim+AppKit.swift` / `Shim+UIKit.swift`, into one module,
// so everything here is `internal` rather than `private`: the toolkit file is a different
// file of the same module. It holds no `@_cdecl` function — every C entry point stays in the
// toolkit files, which is where `ConsistencySpec` looks for them.
//
// Before #79 each toolkit file carried its own copy of all of this, and the copies were kept
// identical by hand.

/// A C string from Scala as a Swift one, `""` for null. #49's picker called this without it
/// ever being defined, which nothing on Linux can notice: the Swift is only compiled on a Mac.
func str(_ p: UnsafePointer<CChar>?) -> String {
  p.map { String(cString: $0) } ?? ""
}

// MARK: - callback plumbing

struct Tap { let cb: sui_void_cb; let ctx: Int64 }
struct TextEdit { let cb: sui_text_cb; let ctx: Int64 }
struct Toggle { let cb: sui_bool_cb; let ctx: Int64 }
struct ValueChange { let cb: sui_value_cb; let ctx: Int64 }
struct Selection { let cb: sui_int_cb; let ctx: Int64 }

var taps: [ObjectIdentifier: Tap] = [:]

/// URLs that views open when activated (`Prop.OpenUrl`). Beside `taps`, not instead of a tap:
/// every tap path goes through `fireTap`, which runs both, so `.link(url)` on a tappable row
/// cannot displace the row's own handler — the same rule GTK and Android keep.
var links: [ObjectIdentifier: URL] = [:]
/// The SF Symbol each view shows, kept only when the toolkit resolved the name: neither
/// `NSImage` nor `UIImage` has a public way to say which symbol it is, so this is what
/// `sui_get_symbol` reads back.
var symbols: [ObjectIdentifier: String] = [:]

/// Non-nil while a test is recording what links would open (`sui_record_opened_urls`).
var openedUrls: [String]?
var edits: [ObjectIdentifier: TextEdit] = [:]
var toggles: [ObjectIdentifier: Toggle] = [:]
var valueChanges: [ObjectIdentifier: ValueChange] = [:]
var selections: [ObjectIdentifier: Selection] = [:]
var dateChanges: [ObjectIdentifier: Selection] = [:]

/// True while the renderer is writing a value in, so a control's own change notification
/// can tell an app-driven update from a user edit and stay silent for the former. Without
/// it, binding a signal to a text field is an infinite loop — the same rule the GTK
/// renderer encodes with its `suppress` set.
var suppressed: Set<ObjectIdentifier> = []

/// A `DatePicker`'s value crosses the boundary as an epoch day. It is read at midnight UTC,
/// and every picker here is set to UTC, so it is a whole day — the same day — whatever zone
/// the device is in. The picker keeps the user's own *calendar*, so a locale that shows a
/// Buddhist or Japanese year still shows the right day.
func epochDay(_ d: Date) -> Int32 { Int32((d.timeIntervalSince1970 / 86400).rounded(.down)) }
func dateOf(_ day: Int32) -> Date { Date(timeIntervalSince1970: Double(day) * 86400) }

// MARK: - strings handed back to Scala

/// The buffer every string the shim returns is copied into: owned by the shim and valid only
/// until the next call, so Scala copies immediately (`AppleInspect.text`). The alternative —
/// handing back a pointer into a Swift String's storage — is a use-after-free, and
/// strdup-per-call would leak (S3).
///
/// Memory of our own, allocated and grown here, rather than a Swift `[CChar]` (#79, review
/// 2026-10-20 P2-27). The array version returned the pointer out of `withUnsafeBufferPointer`,
/// which Swift only promises inside the closure — undefined behaviour that happened to work
/// because nothing else touched the array — and its fixed 4096 bytes made any longer string
/// come back as NULL, which `sui_get_text` documents as "this view carries no text". This
/// buffer only ever grows, so a long text costs its size once, and the shim runs on the main
/// thread alone, so one buffer is enough.
private var scratchBuffer = UnsafeMutablePointer<CChar>.allocate(capacity: 4096)
private var scratchCapacity = 4096

/// `value` as a NUL-terminated UTF-8 C string in the scratch buffer. Never NULL; the optional
/// return keeps it usable straight from `Optional.flatMap`.
func scratch(_ value: String) -> UnsafePointer<CChar>? {
  let bytes = value.utf8CString
  if bytes.count > scratchCapacity {
    var capacity = scratchCapacity
    while capacity < bytes.count { capacity *= 2 }
    scratchBuffer.deallocate()
    scratchBuffer = .allocate(capacity: capacity)
    scratchCapacity = capacity
  }
  bytes.withUnsafeBufferPointer { src in
    scratchBuffer.update(from: src.baseAddress!, count: src.count)
  }
  return UnsafePointer(scratchBuffer)
}

/// A string that lives as long as the process, for answers that never change
/// (`sui_platform`). Not the scratch buffer, so it survives any later call.
func staticCString(_ s: StaticString) -> UnsafePointer<CChar> {
  UnsafeRawPointer(s.utf8Start).assumingMemoryBound(to: CChar.self)
}

// MARK: - diagnostics

/// #72: an Objective-C exception that unwinds into Scala Native frames ends in `std::terminate`
/// as "uncaught foreign exception", and its name and reason are lost. So every exception is
/// written to stderr as it is thrown, before any unwinding. One the toolkit throws and catches
/// itself is logged too; that is rare, and a line too many beats a crash with no reason.
private var previousPreprocessor: objc_exception_preprocessor?
private var exceptionLogInstalled = false

func logObjCExceptions() {
  guard !exceptionLogInstalled else { return }
  exceptionLogInstalled = true
  previousPreprocessor = objc_setExceptionPreprocessor { e in
    if let ex = e as? NSException {
      let line = "thicket: Objective-C exception \(ex.name.rawValue): \(ex.reason ?? "(no reason)")\n"
      FileHandle.standardError.write(line.data(using: .utf8)!)
    }
    return previousPreprocessor?(e) ?? e
  }
}

// MARK: - images

/// Where `ImageSource.FromFile` actually is. An absolute path, or a relative one that exists
/// from the working directory, is used as given — a binary run from the repo, as the macOS
/// examples are. Otherwise a relative path is looked up in the app bundle's resources: an iOS
/// app's working directory is `/`, and the bundle is the only place its own files live. The
/// gallery on the simulator loaded neither of its two images before this (#31).
func resolveImagePath(_ path: String) -> String {
  if path.hasPrefix("/") || FileManager.default.fileExists(atPath: path) { return path }
  if let base = Bundle.main.resourcePath {
    let inBundle = (base as NSString).appendingPathComponent(path)
    if FileManager.default.fileExists(atPath: inBundle) { return inBundle }
  }
  return path
}

// MARK: - pixels, for the self-tests

/// `image` drawn into an RGBA buffer of our own, so the pixel format is known whatever the
/// source's: premultiplied, 4 bytes a pixel, rows top to bottom. Nil when it cannot be drawn.
private func rgba(_ image: CGImage) -> (pixels: [UInt8], width: Int, height: Int)? {
  let w = image.width, h = image.height
  guard w > 0, h > 0 else { return nil }
  var pixels = [UInt8](repeating: 0, count: w * h * 4)
  let drawn: Bool = pixels.withUnsafeMutableBytes { buf in
    guard let ctx = CGContext(
      data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return false }
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    return true
  }
  return drawn ? (pixels, w, h) : nil
}

/// The share of `image`'s pixels that are painted (alpha above 25 of 255); -1 when it cannot
/// be drawn.
func paintedFraction(_ image: CGImage) -> Double {
  guard let (pixels, w, h) = rgba(image) else { return -1 }
  var painted = 0
  for i in stride(from: 3, to: pixels.count, by: 4) where pixels[i] > 25 { painted += 1 }
  return Double(painted) / Double(w * h)
}

/// Where the painted pixels of a drawn view sit across it: the first and last column holding
/// any, as fractions of its width (0 = the left edge, 1 = the right). Measures where text
/// actually lands, which `textAlignment` alone cannot say for `.natural`.
func inkSpan(_ image: CGImage) -> (Double, Double)? {
  guard let (pixels, w, h) = rgba(image) else { return nil }
  var lo = Int.max, hi = -1
  for y in 0..<h {
    for x in 0..<w where pixels[(y * w + x) * 4 + 3] > 25 {
      lo = min(lo, x)
      hi = max(hi, x)
    }
  }
  guard hi >= 0 else { return nil }
  return (Double(lo) / Double(w), Double(hi + 1) / Double(w))
}
