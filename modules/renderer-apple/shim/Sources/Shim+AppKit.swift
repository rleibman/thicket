import AppKit
import Foundation

// The AppKit shim. Every function is @_cdecl, so Scala Native reaches it as a plain C
// symbol with no Objective-C bridging on the Scala side.
//
// Handle convention: Unmanaged<NSView>.passRetained(...).toOpaque(). The shim owns one +1
// retain per handle and drops it in sui_destroy, so a view stays alive as long as Scala
// holds its handle even when it is not in the view hierarchy.
//
// S3 recommended per-*file* separation for the AppKit/UIKit split rather than `#if` inside
// function bodies, because the divergence is structural: NSView's coordinate system is
// flipped relative to UIKit's, NSButton uses target/action with different enums, and there
// is no NSControl.Event. This file is the AppKit half; a Shim+UIKit.swift would mirror it.

private func view(_ h: UnsafeMutableRawPointer) -> NSView {
  Unmanaged<NSView>.fromOpaque(h).takeUnretainedValue()
}

private func retained(_ v: NSView) -> UnsafeMutableRawPointer {
  Unmanaged.passRetained(v).toOpaque()
}

// MARK: - callback plumbing

private struct Tap { let cb: sui_void_cb; let ctx: Int64 }
private struct TextEdit { let cb: sui_text_cb; let ctx: Int64 }
private struct Toggle { let cb: sui_bool_cb; let ctx: Int64 }

private var taps: [ObjectIdentifier: Tap] = [:]
private var edits: [ObjectIdentifier: TextEdit] = [:]
private var toggles: [ObjectIdentifier: Toggle] = [:]

/// True while the renderer is writing a value in, so a control's own change notification
/// can tell an app-driven update from a user edit and stay silent for the former. Without
/// it, binding a signal to a text field is an infinite loop — the same rule the GTK
/// renderer encodes with its `suppress` set.
private var suppressed: Set<ObjectIdentifier> = []

/// NSControl target/action needs an ObjC object, and a Swift closure cannot be one — the
/// same constraint Scala hits with CFuncPtr, met again on this side of the boundary.
private final class Proxy: NSObject, NSTextFieldDelegate {
  @objc func tapped(_ sender: NSControl) {
    if let t = taps[ObjectIdentifier(sender)] { t.cb(t.ctx) }
  }

  @objc func toggled(_ sender: NSButton) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let t = toggles[id] else { return }
    t.cb(t.ctx, sender.state == .on ? 1 : 0)
  }

  func controlTextDidChange(_ note: Notification) {
    guard let field = note.object as? NSTextField else { return }
    let id = ObjectIdentifier(field)
    guard !suppressed.contains(id), let e = edits[id] else { return }
    field.stringValue.withCString { e.cb(e.ctx, $0) }
  }
}

private let proxy = Proxy()

/// Taps on a plain container: NSStackView emits no action, so a click recogniser is
/// attached the way GTK needs a GtkGestureClick on a GtkBox.
private final class TapView: NSStackView {
  override func mouseDown(with event: NSEvent) {
    if let t = taps[ObjectIdentifier(self)] { t.cb(t.ctx) } else { super.mouseDown(with: event) }
  }
}

/// `ContentFit.Cover` — fill the frame, keep the aspect ratio, crop the overflow.
///
/// `NSImageView.imageScaling` has no such mode: `.scaleProportionallyUpOrDown` is Contain
/// and `.scaleAxesIndependently` is Fill. Cover used to map to the latter, which is the one
/// thing it must not do, since Cover is the fit for avatars and hero images where a wrong
/// aspect ratio is immediately visible. UIKit needs none of this — `.scaleAspectFill` is
/// exactly Cover — so the divergence lives here rather than in the contract.
///
/// Drawn rather than done with a layer's `contentsGravity`: `NSImageView` draws its own
/// image, so setting layer contents underneath it fights the view instead of replacing it.
private final class ImageView: NSImageView {
  var cover = false

  override func draw(_ dirtyRect: NSRect) {
    let b = bounds
    guard cover, let img = image, img.size.width > 0, img.size.height > 0, b.width > 0, b.height > 0
    else {
      super.draw(dirtyRect)
      return
    }
    // The one line that is the whole fit: `max` covers and crops, `min` would contain and
    // letterbox.
    let scale = max(b.width / img.size.width, b.height / img.size.height)
    let size = NSSize(width: img.size.width * scale, height: img.size.height * scale)
    let rect = NSRect(
      x: b.midX - size.width / 2, y: b.midY - size.height / 2,
      width: size.width, height: size.height
    )
    NSGraphicsContext.saveGraphicsState()
    NSBezierPath(rect: b).setClip()
    img.draw(in: rect)
    NSGraphicsContext.restoreGraphicsState()
  }
}

// MARK: - application lifecycle

private var window: NSWindow?
private var rootView: NSView?

private final class AppDelegate: NSObject, NSApplicationDelegate {
  var ready: sui_void_cb?
  var ctx: Int64 = 0

  func applicationDidFinishLaunching(_ note: Notification) {
    ready?(ctx)
  }

  func applicationShouldTerminateAfterLastWindowClosed(_ app: NSApplication) -> Bool { true }
}

private let appDelegate = AppDelegate()

@_cdecl("sui_app_start")
public func sui_app_start(
  _ width: Int32, _ height: Int32, _ title: UnsafePointer<CChar>,
  _ ready: @escaping sui_void_cb, _ ctx: Int64
) {
  let app = NSApplication.shared
  app.setActivationPolicy(.regular)

  let w = NSWindow(
    contentRect: NSRect(x: 0, y: 0, width: Double(width), height: Double(height)),
    styleMask: [.titled, .closable, .miniaturizable, .resizable],
    backing: .buffered,
    defer: false
  )
  w.title = String(cString: title)
  w.center()

  // A plain NSView as the root, so the framework's own Column/Row stacks sit inside it and
  // AppKit is not also trying to lay the root out.
  let root = NSView(frame: w.contentView?.bounds ?? .zero)
  root.autoresizingMask = [.width, .height]
  w.contentView?.addSubview(root)
  rootView = root
  window = w

  appDelegate.ready = ready
  appDelegate.ctx = ctx
  app.delegate = appDelegate

  w.makeKeyAndOrderFront(nil)
  app.activate(ignoringOtherApps: true)
  app.run()
}

@_cdecl("sui_root_view")
public func sui_root_view() -> UnsafeMutableRawPointer? {
  guard let r = rootView else { return nil }
  return Unmanaged.passUnretained(r).toOpaque()
}

@_cdecl("sui_window_set_title")
public func sui_window_set_title(_ title: UnsafePointer<CChar>) {
  window?.title = String(cString: title)
}

// MARK: - construction

@_cdecl("sui_create")
public func sui_create(_ kind: Int32) -> UnsafeMutableRawPointer {
  switch kind {
  case 0, 1:
    let s = TapView()
    s.orientation = (kind == 0) ? .vertical : .horizontal
    s.alignment = (kind == 0) ? .leading : .centerY
    s.spacing = 0
    // Without this a stack hugs its content and a Grow child cannot expand into it.
    s.setHuggingPriority(.defaultLow, for: kind == 0 ? .vertical : .horizontal)
    return retained(s)

  case 2:
    // AppKit has no NSLabel; a non-editable, non-bordered NSTextField is the label.
    let l = NSTextField(labelWithString: "")
    l.lineBreakMode = .byWordWrapping
    l.maximumNumberOfLines = 0
    return retained(l)

  case 3:
    let b = NSButton(title: "", target: proxy, action: #selector(Proxy.tapped(_:)))
    b.bezelStyle = .rounded
    return retained(b)

  case 4:
    let f = NSTextField(string: "")
    f.delegate = proxy
    f.isEditable = true
    f.isBordered = true
    return retained(f)

  case 5:
    let c = NSButton(checkboxWithTitle: "", target: proxy, action: #selector(Proxy.toggled(_:)))
    return retained(c)

  case 6:
    let s = NSScrollView()
    s.hasVerticalScroller = true
    s.drawsBackground = false
    return retained(s)

  case 15:
    // The same class as 6, scrolling the other way. Turning the *cross*-axis scroller off
    // is what makes the scroller give its natural size there instead of reserving room for
    // a bar it will never show — the same reason GTK sets its cross-axis policy to NEVER.
    let s = NSScrollView()
    s.hasHorizontalScroller = true
    s.hasVerticalScroller = false
    s.drawsBackground = false
    return retained(s)

  case 8:
    let iv = ImageView()
    iv.imageScaling = .scaleProportionallyUpOrDown
    return retained(iv)

  default:
    let box = NSBox()
    box.boxType = .separator
    return retained(box)
  }
}

// MARK: - colour and images

private func colour(_ has: Int32, _ r: Int32, _ g: Int32, _ b: Int32) -> NSColor? {
  guard has != 0 else { return nil }
  return NSColor(
    srgbRed: CGFloat(r) / 255.0, green: CGFloat(g) / 255.0, blue: CGFloat(b) / 255.0, alpha: 1.0
  )
}

@_cdecl("sui_set_tint")
public func sui_set_tint(_ h: UnsafeMutableRawPointer, _ has: Int32, _ r: Int32, _ g: Int32, _ b: Int32) {
  // nil means "leave it to the platform", deliberately not "use black" — the same rule the
  // GTK renderer follows by only applying CSS when a colour is given.
  guard let c = colour(has, r, g, b) else { return }
  switch view(h) {
  case let f as NSTextField: f.textColor = c
  case let b as NSButton: b.contentTintColor = c
  case let i as NSImageView: i.contentTintColor = c
  default: break
  }
}

@_cdecl("sui_set_fill")
public func sui_set_fill(_ h: UnsafeMutableRawPointer, _ has: Int32, _ r: Int32, _ g: Int32, _ b: Int32) {
  guard let c = colour(has, r, g, b) else { return }
  let v = view(h)
  v.wantsLayer = true
  v.layer?.backgroundColor = c.cgColor
}

@_cdecl("sui_set_image_file")
public func sui_set_image_file(_ h: UnsafeMutableRawPointer, _ path: UnsafePointer<CChar>) {
  (view(h) as? NSImageView)?.image = NSImage(contentsOfFile: String(cString: path))
}

@_cdecl("sui_set_image_bytes")
public func sui_set_image_bytes(_ h: UnsafeMutableRawPointer, _ data: UnsafePointer<UInt8>, _ length: Int32) {
  // Decoding happens here, on the main thread. For anything large an app should decode off
  // the UI thread and hand over a file instead.
  let d = Data(bytes: data, count: Int(length))
  (view(h) as? NSImageView)?.image = NSImage(data: d)
}

@_cdecl("sui_clear_image")
public func sui_clear_image(_ h: UnsafeMutableRawPointer) {
  (view(h) as? NSImageView)?.image = nil
}

@_cdecl("sui_set_content_fit")
public func sui_set_content_fit(_ h: UnsafeMutableRawPointer, _ fit: Int32) {
  guard let iv = view(h) as? NSImageView else { return }
  // Cover is drawn by ImageView rather than expressed as an imageScaling, because AppKit
  // has no scaling mode that crops. The others are the platform's own.
  (iv as? ImageView)?.cover = (fit == 1)
  switch fit {
  case 1: iv.imageScaling = .scaleNone
  case 2: iv.imageScaling = .scaleAxesIndependently
  default: iv.imageScaling = .scaleProportionallyUpOrDown
  }
  iv.needsDisplay = true
}

@_cdecl("sui_destroy")
public func sui_destroy(_ h: UnsafeMutableRawPointer) {
  let v = view(h)
  let id = ObjectIdentifier(v)
  taps.removeValue(forKey: id)
  edits.removeValue(forKey: id)
  toggles.removeValue(forKey: id)
  suppressed.remove(id)
  // Detaching is part of destroying, not a separate step the caller performs first — the
  // contract says so, and the reconciler destroys depth-first.
  v.removeFromSuperview()
  Unmanaged<NSView>.fromOpaque(h).release()
}

// MARK: - properties

@_cdecl("sui_set_text")
public func sui_set_text(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  let v = view(h)
  let s = String(cString: text)
  switch v {
  case let f as NSTextField:
    // Only write when it actually differs. Writing unconditionally moves the caret to the
    // end on every keystroke, because the app writes back what the user just typed.
    if f.stringValue != s {
      let id = ObjectIdentifier(f)
      suppressed.insert(id)
      f.stringValue = s
      suppressed.remove(id)
    }
  case let b as NSButton:
    b.title = s
  default:
    break
  }
}

@_cdecl("sui_set_placeholder")
public func sui_set_placeholder(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  (view(h) as? NSTextField)?.placeholderString = String(cString: text)
}

@_cdecl("sui_set_checked")
public func sui_set_checked(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  guard let b = view(h) as? NSButton else { return }
  let want: NSControl.StateValue = on != 0 ? .on : .off
  if b.state != want {
    let id = ObjectIdentifier(b)
    suppressed.insert(id)
    b.state = want
    suppressed.remove(id)
  }
}

@_cdecl("sui_get_checked")
public func sui_get_checked(_ h: UnsafeMutableRawPointer) -> Int32 {
  ((view(h) as? NSButton)?.state == .on) ? 1 : 0
}

@_cdecl("sui_set_enabled")
public func sui_set_enabled(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  (view(h) as? NSControl)?.isEnabled = on != 0
}

@_cdecl("sui_set_spacing")
public func sui_set_spacing(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  (view(h) as? NSStackView)?.spacing = CGFloat(dp)
}

@_cdecl("sui_set_padding")
public func sui_set_padding(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  guard let s = view(h) as? NSStackView else { return }
  let p = CGFloat(dp)
  s.edgeInsets = NSEdgeInsets(top: p, left: p, bottom: p, right: p)
}

@_cdecl("sui_set_text_role")
public func sui_set_text_role(_ h: UnsafeMutableRawPointer, _ role: Int32) {
  guard let f = view(h) as? NSTextField else { return }
  // AppKit's own type scale, not a pixel size chosen here.
  switch role {
  case 0: f.font = NSFont.preferredFont(forTextStyle: .title1)
  case 2: f.font = NSFont.preferredFont(forTextStyle: .caption1)
  default: f.font = NSFont.preferredFont(forTextStyle: .body)
  }
}

@_cdecl("sui_set_text_emphasis")
public func sui_set_text_emphasis(_ h: UnsafeMutableRawPointer, _ emphasis: Int32) {
  guard let f = view(h) as? NSTextField else { return }
  // A semantic colour, so it follows appearance and contrast settings rather than a
  // palette picked here.
  f.textColor = emphasis == 1 ? .secondaryLabelColor : .labelColor
}

@_cdecl("sui_set_grow")
public func sui_set_grow(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  let v = view(h)
  let priority: NSLayoutConstraint.Priority = on != 0 ? .defaultLow : .defaultHigh
  v.setContentHuggingPriority(priority, for: .horizontal)
  v.setContentHuggingPriority(priority, for: .vertical)
}

@_cdecl("sui_set_align")
public func sui_set_align(_ h: UnsafeMutableRawPointer, _ align: Int32) {
  guard let f = view(h) as? NSTextField else { return }
  switch align {
  case 1: f.alignment = .center
  case 2: f.alignment = .right
  default: f.alignment = .left
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

  if let scroll = p as? NSScrollView {
    // A scroll view holds exactly one child, so "insert" is "set" — the same shape as
    // gtk_scrolled_window_set_child.
    scroll.documentView = c

    // The document view is pinned on the *cross* axis and left free on the scrolling one.
    // That asymmetry is the whole of the axis here.
    //
    // Pinning the cross axis is not optional even for the vertical case, which used to be
    // left on AppKit's defaults: an unpinned document view takes its width from its own
    // content, so a child that in turn wants the container's width — a horizontal scroller
    // is exactly that — closes a loop, and Auto Layout resolves a circular width as zero.
    // That is what collapsed the button row to 0px rather than clipping it.
    c.translatesAutoresizingMaskIntoConstraints = false
    if isHorizontalScroller(scroll) {
      NSLayoutConstraint.activate([
        c.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor),
        c.topAnchor.constraint(equalTo: scroll.contentView.topAnchor),
        c.bottomAnchor.constraint(equalTo: scroll.contentView.bottomAnchor)
      ])
      // An NSScrollView has no intrinsic size in either direction. The height is the
      // content's, fixed here so the scroller hugs the row instead of stretching; the width
      // is the parent's, pinned below where the parent is known.
      scroll.heightAnchor.constraint(equalToConstant: c.fittingSize.height).isActive = true
    } else {
      NSLayoutConstraint.activate([
        c.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor),
        c.trailingAnchor.constraint(equalTo: scroll.contentView.trailingAnchor),
        c.topAnchor.constraint(equalTo: scroll.contentView.topAnchor)
      ])
    }
    scroll.layoutSubtreeIfNeeded()
    return
  }

  guard let stack = p as? NSStackView else {
    // The root NSView is not a stack, so the child has to be pinned explicitly. Without
    // constraints a programmatically created view keeps translatesAutoresizingMaskInto-
    // Constraints = true, Auto Layout never sizes it, and the whole tree measures zero —
    // which is exactly what the self-test caught.
    c.translatesAutoresizingMaskIntoConstraints = false
    p.addSubview(c)
    NSLayoutConstraint.activate([
      c.leadingAnchor.constraint(equalTo: p.leadingAnchor),
      c.trailingAnchor.constraint(equalTo: p.trailingAnchor),
      c.topAnchor.constraint(equalTo: p.topAnchor),
      c.bottomAnchor.constraint(equalTo: p.bottomAnchor)
    ])
    p.layoutSubtreeIfNeeded()
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

  // A vertical NSStackView aligned .leading gives each child its own natural width, which
  // for a horizontal scroller is zero. Filling the stack is what gives it a viewport to
  // clip against, and is what turns the overflow into something scrollable.
  if let sv = c as? NSScrollView, isHorizontalScroller(sv), stack.orientation == .vertical {
    sv.widthAnchor.constraint(equalTo: stack.widthAnchor).isActive = true
    stack.layoutSubtreeIfNeeded()
  }
}

/// Kind 9 rather than kind 6. The axis is not stored anywhere on the Scala side of the
/// boundary, so it is read back off the scroller's own configuration.
private func isHorizontalScroller(_ s: NSScrollView) -> Bool {
  s.hasHorizontalScroller && !s.hasVerticalScroller
}

// MARK: - virtual rows

/// The one place control is inverted: everywhere else Scala builds a tree and this obeys,
/// but a table asks for the row it is about to show and recycles the ones it is not.
/// `GtkSignalListItemFactory`'s bind callback and `BaseAdapter.getView` have the same shape.
///
/// `makeView(withIdentifier:owner:)` is what supplies the recycled view. Handing it back to
/// Scala — rather than always building a fresh one — is the whole point: the framework
/// re-binds that row's signal, so scrolling becomes a few property writes instead of a
/// subtree.
private final class TableSource: NSObject, NSTableViewDataSource, NSTableViewDelegate {
  let cb: sui_row_cb
  let ctx: Int64
  var count: Int = 0

  /// Every row view handed out, so `sui_table_materialised` can answer with a number rather
  /// than an impression. Identity, not equality: two rows are different views even when
  /// they show the same text.
  var materialised = Set<ObjectIdentifier>()

  init(cb: @escaping sui_row_cb, ctx: Int64) {
    self.cb = cb
    self.ctx = ctx
  }

  func numberOfRows(in tableView: NSTableView) -> Int { count }

  func tableView(_ tableView: NSTableView, viewFor column: NSTableColumn?, row: Int) -> NSView? {
    guard row >= 0, row < count else { return nil }
    let recycled = tableView.makeView(withIdentifier: rowId, owner: nil)
    let recycledPtr = recycled.map { Unmanaged.passUnretained($0).toOpaque() }
    guard let produced = cb(ctx, Int32(row), recycledPtr) else { return nil }
    let view = Unmanaged<NSView>.fromOpaque(produced).takeUnretainedValue()
    // The identifier is what makes the view eligible for recycling next time; without it
    // `makeView` always returns nil and the table quietly builds every row.
    view.identifier = rowId
    materialised.insert(ObjectIdentifier(view))
    return view
  }
}

private let rowId = NSUserInterfaceItemIdentifier("sui_row")
private var tableSources: [ObjectIdentifier: TableSource] = [:]

@_cdecl("sui_create_table")
public func sui_create_table(_ cb: @escaping sui_row_cb, _ ctx: Int64) -> UnsafeMutableRawPointer {
  let table = NSTableView()
  let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("sui_column"))
  column.resizingMask = .autoresizingMask
  table.addTableColumn(column)
  table.headerView = nil
  table.style = .plain
  table.rowSizeStyle = .custom
  table.usesAutomaticRowHeights = true

  let source = TableSource(cb: cb, ctx: ctx)
  table.dataSource = source
  table.delegate = source

  // A table virtualises only inside something that scrolls; the scroller is the handle
  // Scala holds, exactly as GTK returns its `GtkScrolledWindow`.
  let scroll = NSScrollView()
  scroll.hasVerticalScroller = true
  scroll.drawsBackground = false
  scroll.documentView = table
  table.translatesAutoresizingMaskIntoConstraints = false
  NSLayoutConstraint.activate([
    table.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor),
    table.trailingAnchor.constraint(equalTo: scroll.contentView.trailingAnchor),
    table.topAnchor.constraint(equalTo: scroll.contentView.topAnchor)
  ])

  // The source is owned here: the delegate and dataSource references are weak, so without
  // this the whole table stops binding as soon as this function returns.
  tableSources[ObjectIdentifier(scroll)] = source
  return retained(scroll)
}

private func table(of h: UnsafeMutableRawPointer) -> (NSTableView, TableSource)? {
  guard let scroll = view(h) as? NSScrollView,
        let t = scroll.documentView as? NSTableView,
        let s = tableSources[ObjectIdentifier(scroll)]
  else { return nil }
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

@_cdecl("sui_remove_child")
public func sui_remove_child(_ parent: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer) {
  let p = view(parent)
  let c = view(child)
  if let scroll = p as? NSScrollView {
    if scroll.documentView === c { scroll.documentView = nil }
    return
  }
  if let stack = p as? NSStackView { stack.removeArrangedSubview(c) }
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
  // Lay out before asking: fittingSize on a view whose constraints have not been resolved
  // reports zero, which reads as "this widget has no size" rather than "ask again later".
  v.layoutSubtreeIfNeeded()
  let fitting = v.fittingSize
  let intrinsic = v.intrinsicContentSize
  // Fall back to the view's actual frame. `fittingSize` answers "how big must this be to
  // satisfy its constraints", which is zero for a container that is *already* sized by its
  // parent — an NSStackView pinned to the window reports 0 even though it fills it. The
  // frame is what layout produced, and is what a caller measuring a mounted widget means.
  let frame = v.frame.size
  let w = fitting.width > 0 ? fitting.width : frame.width
  let h = fitting.height > 0 ? fitting.height : frame.height
  let natW = intrinsic.width > 0 ? intrinsic.width : w
  let natH = intrinsic.height > 0 ? intrinsic.height : h
  outMinW.pointee = Double(w)
  outMinH.pointee = Double(h)
  outNatW.pointee = Double(maxW.isNaN ? natW : min(natW, maxW))
  outNatH.pointee = Double(maxH.isNaN ? natH : min(natH, maxH))
}

@_cdecl("sui_set_frame")
public func sui_set_frame(
  _ h: UnsafeMutableRawPointer, _ x: Double, _ y: Double, _ w: Double, _ height: Double
) {
  // Four doubles rather than an NSRect: no structs by value across this boundary (S4/S3).
  view(h).frame = NSRect(x: x, y: y, width: w, height: height)
}

// MARK: - threading

@_cdecl("sui_run_on_main")
public func sui_run_on_main(_ cb: @escaping sui_void_cb, _ ctx: Int64) {
  DispatchQueue.main.async { cb(ctx) }
}

// MARK: - inspection

private func arranged(_ v: NSView) -> [NSView] {
  if let s = v as? NSStackView { return s.arrangedSubviews }
  if let s = v as? NSScrollView { return s.documentView.map { [$0] } ?? [] }
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

/// Owned by the shim and valid only until the next call, so Scala copies immediately. The
/// alternative — handing back a pointer into a Swift String's storage — is a
/// use-after-free, and strdup-per-call would leak (S3).
private var textScratch = [CChar](repeating: 0, count: 4096)

@_cdecl("sui_get_text")
public func sui_get_text(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  let v = view(h)
  let s: String?
  switch v {
  case let f as NSTextField: s = f.stringValue
  case let b as NSButton: s = b.title
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
  return (v is NSTextField || v is NSButton) ? 1 : 0
}
