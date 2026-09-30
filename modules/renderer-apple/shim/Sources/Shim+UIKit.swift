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

private var taps: [ObjectIdentifier: Tap] = [:]
private var edits: [ObjectIdentifier: TextEdit] = [:]
private var toggles: [ObjectIdentifier: Toggle] = [:]

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

@_cdecl("sui_set_image_file")
public func sui_set_image_file(_ h: UnsafeMutableRawPointer, _ path: UnsafePointer<CChar>) {
  (view(h) as? UIImageView)?.image = UIImage(contentsOfFile: String(cString: path))
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
