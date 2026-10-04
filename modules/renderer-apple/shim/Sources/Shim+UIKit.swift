import Foundation
import UIKit

// The UIKit half of the Apple shim, mirroring Shim+AppKit.swift function for function.
// Only one of the two is ever compiled; `build-shim.sh` picks.
//
// Per-file separation rather than `#if` inside function bodies, as S3 recommended, because
// the divergence is structural rather than per-call:
//
//   - UIView's origin is top-left; NSView's is bottom-left unless flipped.
//   - UIControl has addTarget(_:action:for:) with UIControl.Event; NSControl has a single
//     target/action pair and no event mask.
//   - UIKit has no checkbox, so Checkbox is a UISwitch; AppKit has NSButton(checkboxWithTitle:).
//   - UILabel and UITextField are separate types; AppKit uses NSTextField for both.
//   - iOS 27 refuses to launch an app that does not adopt the UIScene lifecycle (S3), so
//     the application setup here has no AppKit counterpart at all.

private func view(_ h: UnsafeMutableRawPointer) -> UIView {
  Unmanaged<UIView>.fromOpaque(h).takeUnretainedValue()
}

private func retained(_ v: UIView) -> UnsafeMutableRawPointer {
  Unmanaged.passRetained(v).toOpaque()
}

// MARK: - callback plumbing

private struct Tap { let cb: sui_void_cb; let ctx: Int64 }
private struct TextEdit { let cb: sui_text_cb; let ctx: Int64 }
private struct Toggle { let cb: sui_bool_cb; let ctx: Int64 }
private struct ValueChange { let cb: sui_value_cb; let ctx: Int64 }

private var taps: [ObjectIdentifier: Tap] = [:]
private var edits: [ObjectIdentifier: TextEdit] = [:]
private var toggles: [ObjectIdentifier: Toggle] = [:]
private var valueChanges: [ObjectIdentifier: ValueChange] = [:]

/// Progress views told `None`. UIProgressView has no indeterminate mode — there is no
/// UIKit equivalent of an animating bar — so the state is recorded here, where
/// `sui_get_progress` can report it, and the bar is drawn empty.
private var indeterminate: Set<ObjectIdentifier> = []

/// True while the renderer is writing a value in, so a control's own change event can tell
/// an app-driven update from a user edit and stay silent for the former. Without it,
/// binding a signal to a text field is an infinite loop.
private var suppressed: Set<ObjectIdentifier> = []

/// UIControl needs an ObjC target, and a Swift closure cannot be one — the same constraint
/// Scala hits with CFuncPtr, met again on this side of the boundary.
private final class Proxy: NSObject {
  @objc func tapped(_ sender: UIControl) {
    if let t = taps[ObjectIdentifier(sender)] { t.cb(t.ctx) }
  }

  @objc func edited(_ sender: UITextField) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let e = edits[id] else { return }
    (sender.text ?? "").withCString { e.cb(e.ctx, $0) }
  }

  @objc func slid(_ sender: UISlider) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let c = valueChanges[id] else { return }
    c.cb(c.ctx, Double(sender.value))
  }

  @objc func switched(_ sender: UISwitch) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let t = toggles[id] else { return }
    t.cb(t.ctx, sender.isOn ? 1 : 0)
  }

  @objc func containerTapped(_ gesture: UITapGestureRecognizer) {
    guard let v = gesture.view else { return }
    if let t = taps[ObjectIdentifier(v)] { t.cb(t.ctx) }
  }
}

private let proxy = Proxy()

/// An alert's handle. `UIAlertController` is a view *controller*, presented rather than
/// attached, so the handle is a placeholder holding its configuration until `sui_present`.
private final class AlertView: UIView {
  var title = ""
  var message = ""
  var actions: [(label: String, role: Int32, tap: Tap)] = []
  var onDismiss: Tap?
  /// Non-nil exactly while it is on screen; cleared before the app's own dismissal.
  var controller: UIAlertController?
}

/// A sheet's handle: an ordinary vertical stack, so its children mount by the ordinary
/// path. `sui_present` puts it in a view controller inside a navigation controller, whose
/// bar is where UIKit shows a sheet's title.
private final class SheetView: UIStackView {
  var title = ""
  var onDismiss: Tap?
  var host: UINavigationController?
}

/// A swipe down on a sheet is the platform closing it, which is what OnDismiss reports.
/// UIKit calls this only for a *user* dismissal, never for `dismiss(animated:)` from code —
/// the distinction the contract wants, made by the platform itself.
private final class SheetDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
  weak var sheet: SheetView?
  func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
    guard let s = sheet, let d = s.onDismiss else { return }
    d.cb(d.ctx)
  }
}
private var sheetDelegates: [ObjectIdentifier: SheetDelegate] = [:]

// MARK: - application lifecycle

// Unlike AppKit, the shim does not start the application here. On iOS the *host* bundle
// owns `@main` and the UIScene lifecycle — iOS 27 refuses to launch an app that does not
// (S3) — and a scene delegate cannot live in a static library the host links. So the host
// creates the window, hands its root view over with `sui_set_root_view`, and only then
// calls into Scala.
//
// `sui_app_start` is therefore called with the run loop already spinning, and returns
// immediately after scheduling `ready`, where the AppKit one never returns. That
// difference is invisible to `AppleApp`: either way the main thread is Unmanaged from the
// call onwards and `ready` fires with a root view that exists.

private var rootView: UIView?
private var windowTitle = ""

/// Called by the host, not by Scala, before `ScalaNativeInit`.
@_cdecl("sui_set_root_view")
public func sui_set_root_view(_ h: UnsafeMutableRawPointer) {
  rootView = view(h)
}

@_cdecl("sui_app_start")
public func sui_app_start(
  _ width: Int32, _ height: Int32, _ title: UnsafePointer<CChar>,
  _ ready: @escaping sui_void_cb, _ ctx: Int64
) {
  // width and height are ignored: on iOS the window is the screen.
  _ = (width, height)
  windowTitle = String(cString: title)
  applyTitle()
  // Deferred one run-loop turn so mounting does not happen inside the host's scene setup,
  // where iOS's launch watchdog is counting.
  DispatchQueue.main.async { ready(ctx) }
}

@_cdecl("sui_root_view")
public func sui_root_view() -> UnsafeMutableRawPointer? {
  guard let r = rootView else { return nil }
  return Unmanaged.passUnretained(r).toOpaque()
}

private func applyTitle() {
  // The title belongs to the view controller, which the host owns; reach it through the
  // responder chain rather than requiring the host to pass it as well.
  var responder: UIResponder? = rootView
  while let r = responder {
    if let vc = r as? UIViewController {
      vc.title = windowTitle
      vc.navigationItem.title = windowTitle
      return
    }
    responder = r.next
  }
}

@_cdecl("sui_window_set_title")
public func sui_window_set_title(_ title: UnsafePointer<CChar>) {
  windowTitle = String(cString: title)
  // With a page stack each page's controller carries its own title, and the root controller
  // is the *bottom* page — giving it the top page's title would be wrong.
  if pagesActive { return }
  applyTitle()
}

// MARK: - construction

@_cdecl("sui_create")
public func sui_create(_ kind: Int32) -> UnsafeMutableRawPointer {
  switch kind {
  case 0, 1:
    let s = UIStackView()
    s.axis = (kind == 0) ? .vertical : .horizontal
    s.alignment = (kind == 0) ? .fill : .center
    s.distribution = .fill
    s.spacing = 0
    // A container must be tappable: UIStackView has no action, so taps arrive through a
    // gesture recogniser — the same shape GTK needs a GtkGestureClick on a GtkBox.
    let g = UITapGestureRecognizer(target: proxy, action: #selector(Proxy.containerTapped(_:)))
    s.addGestureRecognizer(g)
    s.isUserInteractionEnabled = true
    return retained(s)

  case 2:
    let l = UILabel()
    l.numberOfLines = 0
    l.font = .preferredFont(forTextStyle: .body)
    return retained(l)

  case 3:
    let b = UIButton(type: .system)
    b.addTarget(proxy, action: #selector(Proxy.tapped(_:)), for: .touchUpInside)
    return retained(b)

  case 4:
    let f = UITextField()
    f.borderStyle = .roundedRect
    f.addTarget(proxy, action: #selector(Proxy.edited(_:)), for: .editingChanged)
    return retained(f)

  case 5:
    // UIKit has no checkbox; the platform's boolean control is a switch.
    let sw = UISwitch()
    sw.addTarget(proxy, action: #selector(Proxy.switched(_:)), for: .valueChanged)
    return retained(sw)

  case 6:
    let s = UIScrollView()
    s.alwaysBounceVertical = true
    return retained(s)

  case 15:
    // The same class as 6, scrolling the other way.
    let s = UIScrollView()
    s.alwaysBounceHorizontal = true
    s.showsHorizontalScrollIndicator = true
    s.showsVerticalScrollIndicator = false
    return retained(s)

  case 8:
    let iv = UIImageView()
    iv.contentMode = .scaleAspectFit
    iv.clipsToBounds = true
    return retained(iv)

  case 9:
    // The same control as a checkbox on UIKit. No label: the caption is a sibling.
    let sw = UISwitch()
    sw.addTarget(proxy, action: #selector(Proxy.switched(_:)), for: .valueChanged)
    return retained(sw)

  case 10:
    // Nothing to draw and no intrinsic size; it takes the room its siblings do not, through
    // Prop.Grow, which lowers its hugging exactly as for any other growing child.
    let v = UIView()
    v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    v.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
    return retained(v)

  case 11:
    return retained(UIProgressView(progressViewStyle: .default))

  case 12:
    // No "running" prop: it spins while mounted, and Show is what stops it.
    let a = UIActivityIndicatorView(style: .medium)
    a.startAnimating()
    return retained(a)

  case 13:
    let s = UISlider()
    s.isContinuous = true
    s.addTarget(proxy, action: #selector(Proxy.slid(_:)), for: .valueChanged)
    return retained(s)

  case 16:
    return retained(AlertView())

  case 17:
    let s = SheetView()
    s.axis = .vertical
    s.alignment = .fill
    s.spacing = 8
    return retained(s)

  case 14:
    // Only construction differs from a TextField; text, placeholder and edits are shared.
    let f = UITextField()
    f.isSecureTextEntry = true
    f.borderStyle = .roundedRect
    f.addTarget(proxy, action: #selector(Proxy.edited(_:)), for: .editingChanged)
    return retained(f)

  default:
    // No UIKit separator type: a hairline view at the platform's own separator colour.
    let v = UIView()
    v.backgroundColor = .separator
    v.translatesAutoresizingMaskIntoConstraints = false
    let scale = v.traitCollection.displayScale
    v.heightAnchor.constraint(equalToConstant: 1.0 / (scale > 0 ? scale : 2.0)).isActive = true
    return retained(v)
  }
}

@_cdecl("sui_destroy")
public func sui_destroy(_ h: UnsafeMutableRawPointer) {
  let v = view(h)
  let id = ObjectIdentifier(v)
  taps.removeValue(forKey: id)
  edits.removeValue(forKey: id)
  toggles.removeValue(forKey: id)
  valueChanges.removeValue(forKey: id)
  indeterminate.remove(id)
  sheetDelegates.removeValue(forKey: id)
  menuSources.removeValue(forKey: id)
  if let sheet = v as? SheetView { sheet.host = nil }
  // A virtual list's source is owned here (the table's references to it are weak), so it
  // goes with the view; keyed by the handle, which is the same object `sui_create_table`
  // keyed it by.
  tableSources.removeValue(forKey: id)
  suppressed.remove(id)
  // Detaching is part of destroying, not a separate step the caller performs first.
  if let stack = v.superview as? UIStackView { stack.removeArrangedSubview(v) }
  v.removeFromSuperview()
  Unmanaged<UIView>.fromOpaque(h).release()
}

// MARK: - properties

@_cdecl("sui_set_text")
public func sui_set_text(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  let s = String(cString: text)
  switch view(h) {
  // A presented widget's title arrives as Prop.Text, for *both* presented kinds.
  case let a as AlertView:
    a.title = s
    a.controller?.title = s
  case let sheet as SheetView:
    sheet.title = s
    sheet.host?.topViewController?.title = s
  case let l as UILabel:
    l.text = s
  case let f as UITextField:
    // Only write when it actually differs, or the caret jumps to the end on every
    // keystroke as the app writes back what the user just typed.
    if f.text != s {
      let id = ObjectIdentifier(f)
      suppressed.insert(id)
      f.text = s
      suppressed.remove(id)
    }
  case let b as UIButton:
    b.setTitle(s, for: .normal)
  default:
    break
  }
}

@_cdecl("sui_set_placeholder")
public func sui_set_placeholder(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  (view(h) as? UITextField)?.placeholder = String(cString: text)
}

@_cdecl("sui_set_checked")
public func sui_set_checked(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  guard let sw = view(h) as? UISwitch else { return }
  let want = on != 0
  if sw.isOn != want {
    let id = ObjectIdentifier(sw)
    suppressed.insert(id)
    sw.setOn(want, animated: false)
    suppressed.remove(id)
  }
}

@_cdecl("sui_get_checked")
public func sui_get_checked(_ h: UnsafeMutableRawPointer) -> Int32 {
  ((view(h) as? UISwitch)?.isOn ?? false) ? 1 : 0
}

@_cdecl("sui_set_enabled")
public func sui_set_enabled(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  (view(h) as? UIControl)?.isEnabled = on != 0
}

@_cdecl("sui_set_spacing")
public func sui_set_spacing(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  (view(h) as? UIStackView)?.spacing = CGFloat(dp)
}

@_cdecl("sui_set_padding")
public func sui_set_padding(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  guard let s = view(h) as? UIStackView else { return }
  let p = CGFloat(dp)
  s.isLayoutMarginsRelativeArrangement = true
  s.directionalLayoutMargins = NSDirectionalEdgeInsets(top: p, leading: p, bottom: p, trailing: p)
}

@_cdecl("sui_set_text_role")
public func sui_set_text_role(_ h: UnsafeMutableRawPointer, _ role: Int32) {
  // UIKit's own type scale, not a pixel size chosen here — and it follows Dynamic Type.
  let style: UIFont.TextStyle = (role == 0) ? .title1 : (role == 2) ? .caption1 : .body
  switch view(h) {
  case let l as UILabel: l.font = .preferredFont(forTextStyle: style)
  case let f as UITextField: f.font = .preferredFont(forTextStyle: style)
  default: break
  }
}

@_cdecl("sui_set_text_emphasis")
public func sui_set_text_emphasis(_ h: UnsafeMutableRawPointer, _ emphasis: Int32) {
  // A semantic colour, so it follows appearance and contrast settings rather than a
  // palette picked here.
  let c: UIColor = emphasis == 1 ? .secondaryLabel : .label
  switch view(h) {
  case let l as UILabel: l.textColor = c
  case let f as UITextField: f.textColor = c
  default: break
  }
}

@_cdecl("sui_set_grow")
public func sui_set_grow(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  let v = view(h)
  let priority: UILayoutPriority = on != 0 ? .defaultLow : .defaultHigh
  v.setContentHuggingPriority(priority, for: .horizontal)
  v.setContentHuggingPriority(priority, for: .vertical)
}

@_cdecl("sui_set_align")
public func sui_set_align(_ h: UnsafeMutableRawPointer, _ align: Int32) {
  let a: NSTextAlignment = (align == 1) ? .center : (align == 2) ? .right : .left
  switch view(h) {
  case let l as UILabel: l.textAlignment = a
  case let f as UITextField: f.textAlignment = a
  default: break
  }
}

// MARK: - colour and images

private func colour(_ has: Int32, _ r: Int32, _ g: Int32, _ b: Int32) -> UIColor? {
  guard has != 0 else { return nil }
  return UIColor(
    red: CGFloat(r) / 255.0, green: CGFloat(g) / 255.0, blue: CGFloat(b) / 255.0, alpha: 1.0
  )
}

@_cdecl("sui_set_tint")
public func sui_set_tint(_ h: UnsafeMutableRawPointer, _ has: Int32, _ r: Int32, _ g: Int32, _ b: Int32) {
  // nil means "leave it to the platform", deliberately not "use black".
  guard let c = colour(has, r, g, b) else { return }
  switch view(h) {
  case let l as UILabel: l.textColor = c
  case let f as UITextField: f.textColor = c
  case let b as UIButton: b.tintColor = c
  case let i as UIImageView: i.tintColor = c
  default: break
  }
}

@_cdecl("sui_set_fill")
public func sui_set_fill(_ h: UnsafeMutableRawPointer, _ has: Int32, _ r: Int32, _ g: Int32, _ b: Int32) {
  guard let c = colour(has, r, g, b) else { return }
  view(h).backgroundColor = c
}

/// Where `ImageSource.FromFile` actually is. An absolute path, or a relative one that exists
/// from the working directory, is used as given — a binary run from the repo, as the macOS
/// examples are. Otherwise a relative path is looked up in the app bundle's resources: an iOS
/// app's working directory is `/`, and the bundle is the only place its own files live. The
/// gallery on the simulator loaded neither of its two images before this (#31).
private func resolveImagePath(_ path: String) -> String {
  if path.hasPrefix("/") || FileManager.default.fileExists(atPath: path) { return path }
  if let base = Bundle.main.resourcePath {
    let inBundle = (base as NSString).appendingPathComponent(path)
    if FileManager.default.fileExists(atPath: inBundle) { return inBundle }
  }
  return path
}

@_cdecl("sui_set_image_file")
public func sui_set_image_file(_ h: UnsafeMutableRawPointer, _ path: UnsafePointer<CChar>) {
  (view(h) as? UIImageView)?.image = UIImage(contentsOfFile: resolveImagePath(String(cString: path)))
}

@_cdecl("sui_set_image_bytes")
public func sui_set_image_bytes(_ h: UnsafeMutableRawPointer, _ data: UnsafePointer<UInt8>, _ length: Int32) {
  // Decoding happens here, on the main thread. For anything large an app should decode off
  // the UI thread and hand over a file instead.
  let d = Data(bytes: data, count: Int(length))
  (view(h) as? UIImageView)?.image = UIImage(data: d)
}

@_cdecl("sui_clear_image")
public func sui_clear_image(_ h: UnsafeMutableRawPointer) {
  (view(h) as? UIImageView)?.image = nil
}

@_cdecl("sui_set_content_fit")
public func sui_set_content_fit(_ h: UnsafeMutableRawPointer, _ fit: Int32) {
  guard let iv = view(h) as? UIImageView else { return }
  // UIKit has a real "cover": scaleAspectFill crops rather than distorting, which is what
  // ContentFit.Cover means. AppKit's NSImageView has no equivalent — see that shim.
  switch fit {
  case 1: iv.contentMode = .scaleAspectFill
  case 2: iv.contentMode = .scaleToFill
  default: iv.contentMode = .scaleAspectFit
  }
}

@_cdecl("sui_set_progress")
public func sui_set_progress(_ h: UnsafeMutableRawPointer, _ has: Int32, _ fraction: Double) {
  guard let p = view(h) as? UIProgressView else { return }
  let id = ObjectIdentifier(p)
  if has == 0 {
    indeterminate.insert(id)
    p.setProgress(0, animated: false)
  } else {
    indeterminate.remove(id)
    p.setProgress(Float(min(max(fraction, 0), 1)), animated: false)
  }
}

@_cdecl("sui_set_range")
public func sui_set_range(_ h: UnsafeMutableRawPointer, _ lo: Double, _ hi: Double) {
  guard let s = view(h) as? UISlider else { return }
  s.minimumValue = Float(lo)
  s.maximumValue = Float(hi)
}

@_cdecl("sui_set_value")
public func sui_set_value(_ h: UnsafeMutableRawPointer, _ value: Double) {
  guard let s = view(h) as? UISlider, s.value != Float(value) else { return }
  let id = ObjectIdentifier(s)
  suppressed.insert(id)
  s.setValue(Float(value), animated: false)
  suppressed.remove(id)
}

// MARK: - events

@_cdecl("sui_on_tap")
public func sui_on_tap(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_void_cb, _ ctx: Int64) {
  taps[ObjectIdentifier(view(h))] = Tap(cb: cb, ctx: ctx)
}

@_cdecl("sui_on_text_change")
public func sui_on_text_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_text_cb, _ ctx: Int64) {
  edits[ObjectIdentifier(view(h))] = TextEdit(cb: cb, ctx: ctx)
}

@_cdecl("sui_on_checked_change")
public func sui_on_checked_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_bool_cb, _ ctx: Int64) {
  toggles[ObjectIdentifier(view(h))] = Toggle(cb: cb, ctx: ctx)
}

@_cdecl("sui_on_value_change")
public func sui_on_value_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_value_cb, _ ctx: Int64) {
  valueChanges[ObjectIdentifier(view(h))] = ValueChange(cb: cb, ctx: ctx)
}

// MARK: - tree

@_cdecl("sui_insert_after")
public func sui_insert_after(
  _ parent: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer,
  _ after: UnsafeMutableRawPointer?
) {
  let p = view(parent)
  let c = view(child)

  if let scroll = p as? UIScrollView {
    // A scroll view holds exactly one child here, so "insert" is "set".
    scroll.subviews.forEach { $0.removeFromSuperview() }
    c.translatesAutoresizingMaskIntoConstraints = false
    scroll.addSubview(c)
    // All four edges go to the contentLayoutGuide, which is what gives the scroll view its
    // contentSize. The one axis tied to frameLayoutGuide is the *cross* axis: that is the
    // direction the content may not exceed, and so the direction it does not scroll.
    NSLayoutConstraint.activate([
      c.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
      c.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
      c.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
      c.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
      isHorizontalScroller(scroll)
        ? c.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)
        : c.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor)
    ])
    if isHorizontalScroller(scroll) {
      // A UIScrollView has no intrinsic size, so in a vertical UIStackView it would stretch
      // to whatever slack there is rather than hug the row. The height is the content's.
      // (The width needs no such help: a vertical UIStackView is .fill-aligned here, unlike
      // AppKit's .leading, so it stretches its children across on its own.)
      let fit = c.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
      scroll.heightAnchor.constraint(equalToConstant: fit.height).isActive = true
    }
    return
  }

  guard let stack = p as? UIStackView else {
    // The root view controller's view: pin the child, or it keeps
    // translatesAutoresizingMaskIntoConstraints = true, Auto Layout never sizes it, and
    // the whole tree measures zero — the defect the macOS self-test caught.
    c.translatesAutoresizingMaskIntoConstraints = false
    p.addSubview(c)
    NSLayoutConstraint.activate([
      c.leadingAnchor.constraint(equalTo: p.safeAreaLayoutGuide.leadingAnchor),
      c.trailingAnchor.constraint(equalTo: p.safeAreaLayoutGuide.trailingAnchor),
      c.topAnchor.constraint(equalTo: p.safeAreaLayoutGuide.topAnchor),
      c.bottomAnchor.constraint(equalTo: p.safeAreaLayoutGuide.bottomAnchor)
    ])
    p.layoutIfNeeded()
    return
  }

  let index: Int
  if let a = after {
    let target = view(a)
    index = (stack.arrangedSubviews.firstIndex(of: target).map { $0 + 1 }) ?? stack.arrangedSubviews.count
  } else {
    index = 0
  }
  stack.insertArrangedSubview(c, at: min(index, stack.arrangedSubviews.count))
}

/// Kind 9 rather than kind 6. The axis is not stored anywhere on the Scala side of the
/// boundary, so it is read back off the scroller's own configuration.
private func isHorizontalScroller(_ s: UIScrollView) -> Bool {
  s.alwaysBounceHorizontal && !s.alwaysBounceVertical
}

// MARK: - virtual rows

/// The UIKit mirror of AppKit's TableSource. The inversion is the same; the recycling
/// mechanism is not, and that is why this is a separate file rather than an `#if`.
///
/// `UITableView` recycles *cells*, not the views inside them, so the Scala view is hosted in
/// a cell's `contentView` and the recycled handle is whatever that cell held last. AppKit
/// hands back the row view itself.
private final class TableSource: NSObject, UITableViewDataSource, UITableViewDelegate {
  let cb: sui_row_cb
  let ctx: Int64
  var count: Int = 0
  var materialised = Set<ObjectIdentifier>()

  init(cb: @escaping sui_row_cb, ctx: Int64) {
    self.cb = cb
    self.ctx = ctx
  }

  func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { count }

  func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
    let cell = tableView.dequeueReusableCell(withIdentifier: rowId)
      ?? UITableViewCell(style: .default, reuseIdentifier: rowId)

    // Whatever this cell last hosted is the recycled view.
    let previous = cell.contentView.subviews.first
    let recycledPtr = previous.map { Unmanaged.passUnretained($0).toOpaque() }
    guard let produced = cb(ctx, Int32(indexPath.row), recycledPtr) else { return cell }
    let v = Unmanaged<UIView>.fromOpaque(produced).takeUnretainedValue()
    materialised.insert(ObjectIdentifier(v))

    // Re-binding usually returns the same view, in which case re-pinning it would add a
    // duplicate set of constraints on every scroll.
    if v !== previous {
      previous?.removeFromSuperview()
      v.translatesAutoresizingMaskIntoConstraints = false
      cell.contentView.addSubview(v)
      NSLayoutConstraint.activate([
        v.leadingAnchor.constraint(equalTo: cell.contentView.leadingAnchor),
        v.trailingAnchor.constraint(equalTo: cell.contentView.trailingAnchor),
        v.topAnchor.constraint(equalTo: cell.contentView.topAnchor),
        v.bottomAnchor.constraint(equalTo: cell.contentView.bottomAnchor)
      ])
    }
    return cell
  }
}

private let rowId = "sui_row"
private var tableSources: [ObjectIdentifier: TableSource] = [:]

@_cdecl("sui_create_table")
public func sui_create_table(_ cb: @escaping sui_row_cb, _ ctx: Int64) -> UnsafeMutableRawPointer {
  let table = UITableView(frame: .zero, style: .plain)
  table.rowHeight = UITableView.automaticDimension
  table.estimatedRowHeight = 44
  let source = TableSource(cb: cb, ctx: ctx)
  table.dataSource = source
  table.delegate = source
  // UITableView scrolls on its own, so unlike AppKit there is no separate scroller: the
  // table itself is the handle Scala holds.
  tableSources[ObjectIdentifier(table)] = source
  return retained(table)
}

private func table(of h: UnsafeMutableRawPointer) -> (UITableView, TableSource)? {
  guard let t = view(h) as? UITableView, let s = tableSources[ObjectIdentifier(t)] else { return nil }
  return (t, s)
}

@_cdecl("sui_table_reload")
public func sui_table_reload(_ h: UnsafeMutableRawPointer, _ count: Int32) {
  guard let (t, s) = table(of: h) else { return }
  s.count = Int(count)
  t.reloadData()
}

@_cdecl("sui_table_materialised")
public func sui_table_materialised(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let (_, s) = table(of: h) else { return 0 }
  return Int32(s.materialised.count)
}

@_cdecl("sui_table_live")
public func sui_table_live() -> Int32 {
  Int32(tableSources.count)
}

@_cdecl("sui_remove_child")
public func sui_remove_child(_ parent: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer) {
  let c = view(child)
  if let stack = view(parent) as? UIStackView { stack.removeArrangedSubview(c) }
  c.removeFromSuperview()
}

// MARK: - layout

@_cdecl("sui_measure")
public func sui_measure(
  _ h: UnsafeMutableRawPointer, _ maxW: Double, _ maxH: Double,
  _ outMinW: UnsafeMutablePointer<Double>, _ outMinH: UnsafeMutablePointer<Double>,
  _ outNatW: UnsafeMutablePointer<Double>, _ outNatH: UnsafeMutablePointer<Double>
) {
  let v = view(h)
  v.layoutIfNeeded()
  let fitting = v.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
  let intrinsic = v.intrinsicContentSize
  // Fall back to the frame: systemLayoutSizeFitting answers "how big must this be to
  // satisfy its constraints", which is zero for a container already sized by its parent.
  // The macOS shim hit exactly this.
  let frame = v.bounds.size
  let w = fitting.width > 0 ? fitting.width : frame.width
  let hh = fitting.height > 0 ? fitting.height : frame.height
  let natW = intrinsic.width > 0 ? intrinsic.width : w
  let natH = intrinsic.height > 0 ? intrinsic.height : hh
  outMinW.pointee = Double(w)
  outMinH.pointee = Double(hh)
  outNatW.pointee = Double(maxW.isNaN ? natW : min(natW, maxW))
  outNatH.pointee = Double(maxH.isNaN ? natH : min(natH, maxH))
}

@_cdecl("sui_set_frame")
public func sui_set_frame(
  _ h: UnsafeMutableRawPointer, _ x: Double, _ y: Double, _ w: Double, _ height: Double
) {
  // Four doubles rather than a CGRect: no structs by value across this boundary (S4/S3).
  view(h).frame = CGRect(x: x, y: y, width: w, height: height)
}

// MARK: - threading

@_cdecl("sui_run_on_main")
public func sui_run_on_main(_ cb: @escaping sui_void_cb, _ ctx: Int64) {
  DispatchQueue.main.async { cb(ctx) }
}

@_cdecl("sui_run_on_main_after")
public func sui_run_on_main_after(_ delayMs: Int32, _ cb: @escaping sui_void_cb, _ ctx: Int64) {
  DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs))) { cb(ctx) }
}

// MARK: - inspection

private func arranged(_ v: UIView) -> [UIView] {
  if let s = v as? UIStackView { return s.arrangedSubviews }
  return v.subviews
}

@_cdecl("sui_child_count")
public func sui_child_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  Int32(arranged(view(h)).count)
}

@_cdecl("sui_child_at")
public func sui_child_at(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafeMutableRawPointer? {
  let kids = arranged(view(h))
  guard index >= 0, Int(index) < kids.count else { return nil }
  return Unmanaged.passUnretained(kids[Int(index)]).toOpaque()
}

/// Owned by the shim and valid only until the next call, so Scala copies immediately.
private var textScratch = [CChar](repeating: 0, count: 4096)

@_cdecl("sui_get_text")
public func sui_get_text(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  let s: String?
  switch view(h) {
  case let l as UILabel: s = l.text
  case let f as UITextField: s = f.text
  case let b as UIButton: s = b.title(for: .normal)
  default: s = nil
  }
  guard let value = s else { return nil }
  let bytes = Array(value.utf8CString)
  guard bytes.count <= textScratch.count else { return nil }
  textScratch.replaceSubrange(0..<bytes.count, with: bytes)
  return textScratch.withUnsafeBufferPointer { UnsafePointer($0.baseAddress!) }
}

@_cdecl("sui_is_text_bearing")
public func sui_is_text_bearing(_ h: UnsafeMutableRawPointer) -> Int32 {
  let v = view(h)
  return (v is UILabel || v is UITextField || v is UIButton) ? 1 : 0
}

private func scratch(_ value: String) -> UnsafePointer<CChar>? {
  let bytes = Array(value.utf8CString)
  guard bytes.count <= textScratch.count else { return nil }
  textScratch.replaceSubrange(0..<bytes.count, with: bytes)
  return textScratch.withUnsafeBufferPointer { UnsafePointer($0.baseAddress!) }
}

@_cdecl("sui_class_name")
public func sui_class_name(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  scratch(NSStringFromClass(type(of: view(h))))
}

@_cdecl("sui_get_progress")
public func sui_get_progress(_ h: UnsafeMutableRawPointer) -> Double {
  switch view(h) {
  case let p as UIProgressView: return indeterminate.contains(ObjectIdentifier(p)) ? -1 : Double(p.progress)
  case is UIActivityIndicatorView: return -1
  default: return -2
  }
}

@_cdecl("sui_get_value")
public func sui_get_value(_ h: UnsafeMutableRawPointer) -> Double {
  Double((view(h) as? UISlider)?.value ?? 0)
}

@_cdecl("sui_is_secure")
public func sui_is_secure(_ h: UnsafeMutableRawPointer) -> Int32 {
  ((view(h) as? UITextField)?.isSecureTextEntry ?? false) ? 1 : 0
}

// MARK: - presentation

/// The controller to present from: the window's root, or whatever it is already presenting.
private func presenter() -> UIViewController? {
  var top = rootView?.window?.rootViewController
  while let next = top?.presentedViewController { top = next }
  return top
}

/// UIKit presents and dismisses asynchronously even with `animated: false`: the transition
/// completes on a later turn of the run loop, and a present or dismiss requested before the
/// previous one finished is dropped — silently, apart from a console warning. `Show(flag)`
/// toggled twice in one turn does exactly that. The self-test found it: a sheet closed in
/// the same turn it opened stayed on screen, and the alert opened next was never shown.
///
/// So every presentation change goes through this queue and starts only when the previous
/// one's completion has run. The handle's own state (`controller`, `host`) is updated at
/// once, so the renderer's view of what is up never waits on UIKit.
private var presentationQueue: [(@escaping () -> Void) -> Void] = []
private var presentationBusy = false

private func enqueuePresentation(_ op: @escaping (@escaping () -> Void) -> Void) {
  presentationQueue.append(op)
  runNextPresentation()
}

private func runNextPresentation() {
  guard !presentationBusy, !presentationQueue.isEmpty else { return }
  presentationBusy = true
  let op = presentationQueue.removeFirst()
  op {
    presentationBusy = false
    runNextPresentation()
  }
}

@_cdecl("sui_present")
public func sui_present(_ h: UnsafeMutableRawPointer) {
  switch view(h) {
  case let a as AlertView:
    let c = UIAlertController(title: a.title, message: a.message, preferredStyle: .alert)
    var cancelTaken = false
    for action in a.actions {
      // Roles map directly. UIKit allows one cancel action and raises on a second, so a
      // second is shown as a plain one rather than crashing the app.
      let style: UIAlertAction.Style
      switch action.role {
      case 1: style = .destructive
      case 2 where !cancelTaken: style = .cancel; cancelTaken = true
      default: style = .default
      }
      let tap = action.tap
      c.addAction(UIAlertAction(title: action.label, style: style) { [weak a, weak c] _ in
        guard let a = a, let c = c, a.controller === c else { return }
        a.controller = nil
        tap.cb(tap.ctx)
      })
    }
    a.controller = c
    enqueuePresentation { done in
      guard let from = presenter() else { return done() }
      from.present(c, animated: false, completion: done)
    }

  case let s as SheetView:
    let vc = UIViewController()
    vc.title = s.title
    vc.view.backgroundColor = .systemBackground
    s.translatesAutoresizingMaskIntoConstraints = false
    vc.view.addSubview(s)
    let g = vc.view.layoutMarginsGuide
    NSLayoutConstraint.activate([
      s.leadingAnchor.constraint(equalTo: g.leadingAnchor),
      s.trailingAnchor.constraint(equalTo: g.trailingAnchor),
      s.topAnchor.constraint(equalTo: vc.view.safeAreaLayoutGuide.topAnchor, constant: 16)
    ])
    let nav = UINavigationController(rootViewController: vc)
    let d = SheetDelegate()
    d.sheet = s
    sheetDelegates[ObjectIdentifier(s)] = d
    nav.presentationController?.delegate = d
    s.host = nav
    enqueuePresentation { done in
      guard let from = presenter() else { return done() }
      from.present(nav, animated: false, completion: done)
    }

  default:
    break
  }
}

@_cdecl("sui_dismiss")
public func sui_dismiss(_ h: UnsafeMutableRawPointer) {
  switch view(h) {
  case let a as AlertView:
    guard let c = a.controller else { return }
    a.controller = nil
    enqueuePresentation { done in
      guard c.presentingViewController != nil else { return done() }
      c.dismiss(animated: false, completion: done)
    }
  case let s as SheetView:
    // Only take it off screen. The view controller keeps the stack until `sui_destroy`,
    // and the handle's own retain is what keeps it alive regardless.
    guard let nav = s.host else { return }
    enqueuePresentation { done in
      guard nav.presentingViewController != nil else { return done() }
      nav.dismiss(animated: false, completion: done)
    }
  default:
    break
  }
}

@_cdecl("sui_set_message")
public func sui_set_message(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  guard let a = view(h) as? AlertView else { return }
  a.message = String(cString: text)
  a.controller?.message = a.message
}

@_cdecl("sui_alert_clear_actions")
public func sui_alert_clear_actions(_ h: UnsafeMutableRawPointer) {
  (view(h) as? AlertView)?.actions = []
}

@_cdecl("sui_alert_add_action")
public func sui_alert_add_action(
  _ h: UnsafeMutableRawPointer, _ label: UnsafePointer<CChar>, _ role: Int32,
  _ cb: @escaping sui_void_cb, _ ctx: Int64
) {
  (view(h) as? AlertView)?.actions.append((String(cString: label), role, Tap(cb: cb, ctx: ctx)))
}

@_cdecl("sui_on_dismiss")
public func sui_on_dismiss(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_void_cb, _ ctx: Int64) {
  switch view(h) {
  case let a as AlertView: a.onDismiss = Tap(cb: cb, ctx: ctx)
  case let s as SheetView: s.onDismiss = Tap(cb: cb, ctx: ctx)
  default: break
  }
}

@_cdecl("sui_is_presented")
public func sui_is_presented(_ h: UnsafeMutableRawPointer) -> Int32 {
  switch view(h) {
  case let a as AlertView: return (a.controller?.presentingViewController != nil) ? 1 : 0
  case let s as SheetView: return (s.host?.presentingViewController != nil) ? 1 : 0
  default: return 0
  }
}

@_cdecl("sui_presented_title")
public func sui_presented_title(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  switch view(h) {
  case let a as AlertView: return a.controller.flatMap { $0.title }.flatMap { scratch($0) }
  case let s as SheetView:
    guard let nav = s.host, nav.presentingViewController != nil else { return nil }
    // What the navigation bar is showing, not what the renderer was told.
    return nav.navigationBar.topItem?.title.flatMap { scratch($0) }
  default: return nil
  }
}

@_cdecl("sui_presented_message")
public func sui_presented_message(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  (view(h) as? AlertView)?.controller?.message.flatMap { scratch($0) }
}

@_cdecl("sui_alert_action_count")
public func sui_alert_action_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  Int32((view(h) as? AlertView)?.controller?.actions.count ?? 0)
}

@_cdecl("sui_alert_action_label")
public func sui_alert_action_label(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  guard let actions = (view(h) as? AlertView)?.controller?.actions, index >= 0, Int(index) < actions.count
  else { return nil }
  return actions[Int(index)].title.flatMap { scratch($0) }
}

@_cdecl("sui_perform_click")
public func sui_perform_click(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let b = view(h) as? UIButton else { return 0 }
  b.sendActions(for: .touchUpInside)
  return 1
}

/// UIKit offers no public way to trigger a `UIAlertAction` from code. Calling the stored
/// callback here would pass without exercising UIKit at all, so this says it cannot.
@_cdecl("sui_alert_choose")
public func sui_alert_choose(_ h: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
  0
}

@_cdecl("sui_presented_count")
public func sui_presented_count() -> Int32 {
  var n: Int32 = 0
  var top = rootView?.window?.rootViewController
  while let next = top?.presentedViewController {
    n += 1
    top = next
  }
  return n
}

// MARK: - context menus

/// Menu items alive right now, counted in `init` and `deinit` so that "the menu went with
/// its view" is a number rather than an assumption.
private var liveMenuItems: Int32 = 0

private final class MenuChoice {
  let label: String
  let enabled: Bool
  let tap: Tap
  init(_ label: String, _ enabled: Bool, _ tap: Tap) {
    self.label = label
    self.enabled = enabled
    self.tap = tap
    liveMenuItems += 1
  }
  deinit { liveMenuItems -= 1 }
}

/// A view's menu items, and the delegate its `UIContextMenuInteraction` asks for them. The
/// menu is built when UIKit asks — on a long press — from whatever the items are then, so
/// replacing the items needs no new interaction.
///
/// The interaction holds its delegate **weakly**, so `menuSources` is what keeps this alive,
/// and `sui_destroy` is what lets it go. Inspection reads the menu back through the
/// interaction's own `delegate`, so a delegate that had been freed early would read as no
/// menu at all rather than being papered over by the dictionary.
private final class MenuSource: NSObject, UIContextMenuInteractionDelegate {
  var items: [MenuChoice] = []

  func makeMenu() -> UIMenu {
    UIMenu(children: items.map { item in
      let tap = item.tap
      return UIAction(title: item.label, attributes: item.enabled ? [] : .disabled) { _ in tap.cb(tap.ctx) }
    })
  }

  func contextMenuInteraction(
    _ interaction: UIContextMenuInteraction,
    configurationForMenuAtLocation location: CGPoint
  ) -> UIContextMenuConfiguration? {
    guard !items.isEmpty else { return nil }
    return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in self?.makeMenu() }
  }
}
private var menuSources: [ObjectIdentifier: MenuSource] = [:]

@_cdecl("sui_menu_clear")
public func sui_menu_clear(_ h: UnsafeMutableRawPointer) {
  menuSources[ObjectIdentifier(view(h))]?.items = []
}

@_cdecl("sui_menu_add_item")
public func sui_menu_add_item(
  _ h: UnsafeMutableRawPointer, _ label: UnsafePointer<CChar>, _ enabled: Int32,
  _ cb: @escaping sui_void_cb, _ ctx: Int64
) {
  let v = view(h)
  let id = ObjectIdentifier(v)
  let source: MenuSource
  if let s = menuSources[id] {
    source = s
  } else {
    source = MenuSource()
    menuSources[id] = source
    // The interaction holds its delegate weakly, so `menuSources` owns it.
    v.addInteraction(UIContextMenuInteraction(delegate: source))
    v.isUserInteractionEnabled = true
  }
  source.items.append(MenuChoice(String(cString: label), enabled != 0, Tap(cb: cb, ctx: ctx)))
}

/// Read from the menu UIKit would be given — `makeMenu()` is what the interaction's
/// configuration returns — reached through the attached interaction's own weak `delegate`,
/// not through `menuSources`, so this sees exactly what UIKit would see.
private func attachedMenu(_ h: UnsafeMutableRawPointer) -> UIMenu? {
  let interaction = view(h).interactions.compactMap { $0 as? UIContextMenuInteraction }.first
  guard let source = interaction?.delegate as? MenuSource, !source.items.isEmpty else { return nil }
  return source.makeMenu()
}

@_cdecl("sui_menu_item_count")
public func sui_menu_item_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  Int32(attachedMenu(h)?.children.count ?? 0)
}

@_cdecl("sui_menu_item_label")
public func sui_menu_item_label(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  guard let children = attachedMenu(h)?.children, index >= 0, Int(index) < children.count else { return nil }
  return scratch(children[Int(index)].title)
}

/// UIKit offers no public way to perform a `UIAction` from code; calling the stored
/// callback here would pass without exercising UIKit, so this says it cannot.
@_cdecl("sui_menu_activate")
public func sui_menu_activate(_ h: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
  0
}

@_cdecl("sui_menu_live")
public func sui_menu_live() -> Int32 {
  liveMenuItems
}

// MARK: - navigation (AppRoot.pages)

/// The host's controller for the root view, and the navigation controller it sits in. The
/// bottom page reuses that controller, so `rootView` stays in the window whatever the depth —
/// presentation and inspection reach the window through it.
private func hostController() -> UIViewController? {
  var responder: UIResponder? = rootView
  while let r = responder {
    if let vc = r as? UIViewController { return vc }
    responder = r.next
  }
  return nil
}

private var pagesActive = false
private var pendingPages: [(id: Int64, content: UIView, title: String)] = []
private var pageList: [(id: Int64, content: UIView, title: String)] = []
private var pageControllers: [Int64: UIViewController] = [:]
private var applyingPages = false
private var onPopped: (cb: sui_int_cb, ctx: Int64)?

/// A swipe back, or the back button, pops UIKit's stack without asking. Reported as the new
/// depth, so the app's `Nav` follows rather than the two stacks silently diverging and the
/// navigation bar starting to lie.
private final class PagesDelegate: NSObject, UINavigationControllerDelegate {
  func navigationController(
    _ navigationController: UINavigationController,
    didShow viewController: UIViewController,
    animated: Bool
  ) {
    let depth = navigationController.viewControllers.count
    guard !applyingPages, depth < pageList.count, let p = onPopped else { return }
    pageList = Array(pageList.prefix(depth))
    p.cb(p.ctx, Int32(depth))
  }
}
private let pagesDelegate = PagesDelegate()

@_cdecl("sui_pages_begin")
public func sui_pages_begin() {
  pendingPages = []
}

@_cdecl("sui_pages_add")
public func sui_pages_add(_ id: Int64, _ content: UnsafeMutableRawPointer, _ title: UnsafePointer<CChar>) {
  pendingPages.append((id, view(content), String(cString: title)))
}

/// Places a page's content in its controller's view, once; the content then stays there,
/// mounted, for as long as the page is on the stack.
private func place(_ content: UIView, in host: UIView) {
  guard content.superview !== host else { return }
  content.removeFromSuperview()
  sui_insert_after(
    Unmanaged.passUnretained(host).toOpaque(), Unmanaged.passUnretained(content).toOpaque(), nil
  )
}

@_cdecl("sui_pages_commit")
public func sui_pages_commit() {
  guard let root = hostController(), let nav = root.navigationController ?? (root as? UINavigationController) ?? nil,
        let rv = rootView
  else { return }
  pagesActive = true
  nav.delegate = pagesDelegate

  var controllers: [UIViewController] = []
  for (i, page) in pendingPages.enumerated() {
    let vc: UIViewController
    if i == 0 {
      vc = root
      place(page.content, in: rv)
    } else if let existing = pageControllers[page.id] {
      vc = existing
    } else {
      vc = UIViewController()
      vc.view.backgroundColor = .systemBackground
      place(page.content, in: vc.view)
      pageControllers[page.id] = vc
    }
    vc.title = page.title
    vc.navigationItem.title = page.title
    controllers.append(vc)
  }
  let live = Set(pendingPages.map(\.id))
  pageControllers = pageControllers.filter { live.contains($0.key) }

  pageList = pendingPages
  desiredControllers = controllers
  applyDesired(nav)
}

/// The stack the app wants, applied when UIKit is ready for it.
private var desiredControllers: [UIViewController] = []

/// UIKit does not apply a stack change made while a transition is still running, and a
/// presentation started mid-push leaves the push's transition unfinished. Measured: a push
/// followed by a pop in one turn left the platform two deep while `Nav` was one — the
/// divergence this seam exists to prevent — and deferring on the push's own coordinator was
/// not enough, because a sheet presented next kept that transition from ever completing.
///
/// So stack changes go through the same serial queue as presentations: every UIKit
/// transition, of either kind, starts only when the previous one has finished. Each apply
/// uses whatever the app wants *by then*, so a burst of changes settles in one step.
private func applyDesired(_ nav: UINavigationController) {
  enqueuePresentation { done in
    guard nav.viewControllers != desiredControllers else { return done() }
    // A push or pop of one page animates, as a user would see it; anything larger is a jump.
    let animated = nav.view.window != nil && abs(desiredControllers.count - nav.viewControllers.count) == 1
    applyingPages = true
    nav.setViewControllers(desiredControllers, animated: animated)
    applyingPages = false
    // `animate(alongsideTransition:completion:)` returns false, and never calls the
    // completion, when there is no running transition to attach to — a non-animated change.
    if !(nav.transitionCoordinator?.animate(alongsideTransition: nil, completion: { _ in done() }) ?? false) {
      DispatchQueue.main.async { done() }
    }
  }
}

@_cdecl("sui_on_pages_popped")
public func sui_on_pages_popped(_ cb: @escaping sui_int_cb, _ ctx: Int64) {
  onPopped = (cb, ctx)
}

private func hostNavigation() -> UINavigationController? {
  hostController()?.navigationController
}

@_cdecl("sui_pages_depth")
public func sui_pages_depth() -> Int32 {
  Int32(hostNavigation()?.viewControllers.count ?? 0)
}

@_cdecl("sui_page_title")
public func sui_page_title(_ index: Int32) -> UnsafePointer<CChar>? {
  guard let vcs = hostNavigation()?.viewControllers, index >= 0, Int(index) < vcs.count,
        let t = vcs[Int(index)].navigationItem.title
  else { return nil }
  return scratch(t)
}

/// -1 while a transition is running: mid-push the stack already holds the new page, but the
/// navigation bar has not caught up, so no page is "the one on screen" yet.
@_cdecl("sui_pages_shown")
public func sui_pages_shown() -> Int32 {
  guard let nav = hostNavigation(), nav.transitionCoordinator == nil, let top = nav.topViewController,
        let i = nav.viewControllers.firstIndex(of: top)
  else { return -1 }
  return Int32(i)
}

@_cdecl("sui_pages_back_offered")
public func sui_pages_back_offered() -> Int32 {
  (hostNavigation()?.navigationBar.backItem != nil) ? 1 : 0
}

/// `popViewController` is what the back button does; the delegate then reports it like any
/// other platform pop.
@_cdecl("sui_pages_back")
public func sui_pages_back() -> Int32 {
  guard let nav = hostNavigation(), nav.viewControllers.count > 1 else { return 0 }
  return nav.popViewController(animated: false) != nil ? 1 : 0
}

@_cdecl("sui_has_image")
public func sui_has_image(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let iv = view(h) as? UIImageView else { return -1 }
  return iv.image != nil ? 1 : 0
}
