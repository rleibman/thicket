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

/// A C string from Scala as a Swift one, `""` for null. #49's picker called this without it
/// ever being defined, which nothing on Linux can notice: the Swift is only compiled on a Mac.
private func str(_ p: UnsafePointer<CChar>?) -> String {
  p.map { String(cString: $0) } ?? ""
}

private func retained(_ v: NSView) -> UnsafeMutableRawPointer {
  Unmanaged.passRetained(v).toOpaque()
}

// MARK: - callback plumbing

private struct Tap { let cb: sui_void_cb; let ctx: Int64 }
private struct TextEdit { let cb: sui_text_cb; let ctx: Int64 }
private struct Toggle { let cb: sui_bool_cb; let ctx: Int64 }
private struct ValueChange { let cb: sui_value_cb; let ctx: Int64 }
private struct Selection { let cb: sui_int_cb; let ctx: Int64 }

private var taps: [ObjectIdentifier: Tap] = [:]

/// URLs that views open when activated (`Prop.OpenUrl`). Beside `taps`, not instead of a tap:
/// every tap path goes through `fireTap`, which runs both, so `.link(url)` on a tappable row
/// cannot displace the row's own handler — the same rule GTK and Android keep.
private var links: [ObjectIdentifier: URL] = [:]
/// The SF Symbol each view shows, kept only when AppKit resolved the name: an `NSImage` has
/// no public way to say which symbol it is, so this is what `sui_get_symbol` reads back.
private var symbols: [ObjectIdentifier: String] = [:]

private func fireTap(_ id: ObjectIdentifier) {
  if let t = taps[id] { t.cb(t.ctx) }
  // Handed to the platform, which picks the browser or the app registered for the scheme.
  if let url = links[id] {
    if var log = openedUrls {
      log.append(url.absoluteString)
      openedUrls = log
    } else {
      _ = NSWorkspace.shared.open(url)
    }
  }
}

/// Non-nil while a test is recording what links would open (`sui_record_opened_urls`).
private var openedUrls: [String]?
private var edits: [ObjectIdentifier: TextEdit] = [:]
private var toggles: [ObjectIdentifier: Toggle] = [:]
private var valueChanges: [ObjectIdentifier: ValueChange] = [:]
private var selections: [ObjectIdentifier: Selection] = [:]
private var dateChanges: [ObjectIdentifier: Selection] = [:]

/// A `DatePicker`'s value crosses the boundary as an epoch day. It is read at midnight UTC,
/// and every picker here is set to UTC, so it is a whole day — the same day — whatever zone
/// the device is in. The picker keeps the user's own *calendar*, so a locale that shows a
/// Buddhist or Japanese year still shows the right day.
private func epochDay(_ d: Date) -> Int32 { Int32((d.timeIntervalSince1970 / 86400).rounded(.down)) }
private func dateOf(_ day: Int32) -> Date { Date(timeIntervalSince1970: Double(day) * 86400) }

/// True while the renderer is writing a value in, so a control's own change notification
/// can tell an app-driven update from a user edit and stay silent for the former. Without
/// it, binding a signal to a text field is an infinite loop — the same rule the GTK
/// renderer encodes with its `suppress` set.
private var suppressed: Set<ObjectIdentifier> = []

/// NSControl target/action needs an ObjC object, and a Swift closure cannot be one — the
/// same constraint Scala hits with CFuncPtr, met again on this side of the boundary.
private final class Proxy: NSObject, NSTextFieldDelegate {
  @objc func tapped(_ sender: NSControl) {
    fireTap(ObjectIdentifier(sender))
  }

  /// A click recogniser on a view with no action of its own recognises on mouse-up inside it.
  @objc func clicked(_ gesture: NSGestureRecognizer) {
    guard let v = gesture.view else { return }
    fireTap(ObjectIdentifier(v))
  }

  /// A checkbox is an `NSButton` and a `Toggle` is an `NSSwitch`, which is not one; both
  /// carry `state`, so this reads it from whichever it is.
  @objc func toggled(_ sender: NSControl) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let t = toggles[id] else { return }
    t.cb(t.ctx, isOn(sender) ? 1 : 0)
  }

  @objc func dated(_ sender: NSDatePicker) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let s = dateChanges[id] else { return }
    s.cb(s.ctx, epochDay(sender.dateValue))
  }

  @objc func segmented(_ sender: NSSegmentedControl) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let s = selections[id] else { return }
    s.cb(s.ctx, Int32(sender.selectedSegment))
  }

  @objc func chose(_ sender: NSPopUpButton) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let s = selections[id] else { return }
    s.cb(s.ctx, Int32(sender.indexOfSelectedItem))
  }

  @objc func slid(_ sender: NSSlider) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let c = valueChanges[id] else { return }
    c.cb(c.ctx, sender.doubleValue)
  }

  func controlTextDidChange(_ note: Notification) {
    guard let field = note.object as? NSTextField else { return }
    let id = ObjectIdentifier(field)
    guard !suppressed.contains(id), let e = edits[id] else { return }
    field.stringValue.withCString { e.cb(e.ctx, $0) }
  }
}

private let proxy = Proxy()

private func isOn(_ v: NSView) -> Bool {
  if let b = v as? NSButton { return b.state == .on }
  if let s = v as? NSSwitch { return s.state == .on }
  return false
}

/// Taps on a plain container: NSStackView emits no action, so a click recogniser is
/// attached the way GTK needs a GtkGestureClick on a GtkBox.
private final class TapView: NSStackView {
  /// Top-left origin, as on UIKit and in reading order. It matters as a scroll view's document
  /// view: AppKit keeps an unflipped document's *bottom* edge in view, so a Column taller than
  /// its window opened scrolled to the bottom with its first row under the title bar — 48pt
  /// from the top on the todo screen (#28). Layout is by constraints, so nothing else moves.
  override var isFlipped: Bool { true }

  /// A Column's stretched children follow its insets (#87).
  override var edgeInsets: NSEdgeInsets {
    didSet { syncColumnFill(self) }
  }

  /// True between a press this view claimed and its release.
  private var pressed = false

  /// A tap is a press *and* a release inside the view, as on GTK (`released`), Android and
  /// UIKit (touch-up). Firing on the press, as this did, activated a row the user then dragged
  /// off, and fired before a drag could start (#76, review 2026-10-20 L10). AppKit sends the
  /// mouse-up to the view that took the mouse-down, wherever the pointer is by then.
  override func mouseDown(with event: NSEvent) {
    let id = ObjectIdentifier(self)
    if taps[id] != nil || links[id] != nil { pressed = true } else { super.mouseDown(with: event) }
  }

  override func mouseUp(with event: NSEvent) {
    guard pressed else {
      super.mouseUp(with: event)
      return
    }
    pressed = false
    if bounds.contains(convert(event.locationInWindow, from: nil)) { fireTap(ObjectIdentifier(self)) }
  }

  override func viewDidMoveToWindow() {
    super.viewDidMoveToWindow()
    refreshInsets(self)
  }

  override func layout() {
    super.layout()
    refreshInsets(self)
  }
}

/// Kinds 6 and 15, a `Scroll`. Its own class for two things a plain `NSScrollView` cannot
/// hold (#76): the safe-area hooks, and the constraints that depend on its insets — the
/// document view's cross-axis edge, and a horizontal scroller's one height constraint.
private final class ScrollView: NSScrollView {
  /// Vertical: the document's trailing edge, short of the clip view's by the side insets.
  /// Horizontal: the scroller's own height, the document's plus the top and bottom insets.
  /// One per scroller, replaced with the document view and updated with the insets — it used
  /// to be a fresh `heightAnchor == constant` per child, which stacked (review L4).
  var tie: NSLayoutConstraint?

  func setInsets(_ e: NSEdgeInsets) {
    let old = contentInsets
    let clip = contentView.bounds.origin
    // At the start before, at the start after: the padding is where the content begins.
    let atStart = abs(clip.x + old.left) < 0.5 && abs(clip.y + old.top) < 0.5
    automaticallyAdjustsContentInsets = false
    contentInsets = e
    retie()
    if atStart { scrollToStart() }
  }

  /// Where the content begins, its insets in from the corner. A new document view needs this:
  /// AppKit shows it from the clip view's origin, which is the insets' far side.
  func scrollToStart() {
    contentView.scroll(to: NSPoint(x: -contentInsets.left, y: -contentInsets.top))
    reflectScrolledClipView(contentView)
  }

  func retie() {
    let e = contentInsets
    if isHorizontalScroller(self) {
      tie?.constant = e.top + e.bottom
    } else {
      tie?.constant = -(e.left + e.right)
    }
  }

  override func viewDidMoveToWindow() {
    super.viewDidMoveToWindow()
    refreshInsets(self)
  }

  override func layout() {
    super.layout()
    refreshInsets(self)
  }
}

// MARK: - padding and the safe area (#76)

/// What a container's insets are made of: the app's `Padding`, plus the safe-area insets on
/// the edges `SafeArea` chose. Kept per view, so that each can change without losing the
/// other — the safe area used to be written over the padding on UIKit and Android, and was
/// read at create time, from a view in no window, where it is always zero (review M8).
private struct InsetState {
  var padding: CGFloat = 0
  var edges: Int32 = 0
  var applied: NSEdgeInsets?
}

private var insetStates: [ObjectIdentifier: InsetState] = [:]

/// The window's safe-area insets on the chosen edges; zero until the view is in a window.
/// Leading and trailing are resolved against the view's layout direction.
private func safeInsets(_ v: NSView, _ edges: Int32) -> NSEdgeInsets {
  guard edges != 0, v.window != nil else { return NSEdgeInsetsZero }
  let i = v.safeAreaInsets
  let rtl = v.userInterfaceLayoutDirection == .rightToLeft
  return NSEdgeInsets(
    top: (edges & 1) != 0 ? i.top : 0,
    left: (edges & (rtl ? 8 : 4)) != 0 ? i.left : 0,
    bottom: (edges & 2) != 0 ? i.bottom : 0,
    right: (edges & (rtl ? 4 : 8)) != 0 ? i.right : 0
  )
}

/// Writes padding + safe area to the view, if it changed. Called when either prop is set, when
/// the view enters a window, and on every layout — which is how a safe area that changes
/// (full screen, a titlebar accessory) reaches it, since AppKit has no
/// `safeAreaInsetsDidChange`. Unchanged insets write nothing, so layout settles.
private func refreshInsets(_ v: NSView) {
  let id = ObjectIdentifier(v)
  guard var st = insetStates[id] else { return }
  let s = safeInsets(v, st.edges)
  let p = st.padding
  let e = NSEdgeInsets(top: p + s.top, left: p + s.left, bottom: p + s.bottom, right: p + s.right)
  if let a = st.applied, NSEdgeInsetsEqual(a, e) { return }
  st.applied = e
  insetStates[id] = st
  switch v {
  case let g as GridView: g.setInsets(e)
  case let s as ScrollView: s.setInsets(e)
  case let s as NSStackView: s.edgeInsets = e
  default: break
  }
}

/// The recogniser `OnTap` and `OpenUrl` put on a view with no action of its own. Its own class,
/// so the shim can tell it from any other.
private final class ClickRecognizer: NSClickGestureRecognizer {}

/// Makes any view report clicks (#75): a Label, an Image, a ZStack — not only a Button, whose
/// target/action already goes through `fireTap`, and a Column or Row, whose `TapView` takes
/// its own mouse-down. GTK puts a click gesture on any widget and Android a click listener on
/// any view; before this, `Image(...).onTap` did nothing on macOS. `NSClickGestureRecognizer`
/// recognises on mouse-up inside the view, so a press dragged off it does not fire.
private func makeTappable(_ v: NSView) {
  if v is NSButton || v is TapView { return }
  if v.gestureRecognizers.contains(where: { $0 is ClickRecognizer }) { return }
  let g = ClickRecognizer(target: proxy, action: #selector(Proxy.clicked(_:)))
  // A control other than a label keeps its own mouse-down: a tappable slider still slides.
  let isLabel = (v as? NSTextField).map { !$0.isEditable } ?? false
  if v is NSControl && !isLabel { g.delaysPrimaryMouseButtonEvents = false }
  v.addGestureRecognizer(g)
}

/// `Alignment` against a layout direction. AppKit's `.natural` follows the *text's* script, not
/// the view's direction (measured: "Start" in a right-to-left NSTextField still draws at the
/// left with `.natural`), so Start and End are resolved here, and a `LabelField` resolves them
/// again when its direction changes. `.left`/`.right`, as before #75, ignored the direction.
private func resolvedAlignment(_ align: Int32, _ direction: NSUserInterfaceLayoutDirection) -> NSTextAlignment {
  let rtl = direction == .rightToLeft
  switch align {
  case 1: return .center
  case 2: return rtl ? .left : .right
  default: return rtl ? .right : .left
  }
}

/// A Label: a non-editable `NSTextField` that remembers its `Alignment`, so a change of layout
/// direction moves its text. A view's direction starts as the app's, which a right-to-left
/// locale makes `.rightToLeft`.
private final class LabelField: NSTextField {
  var logicalAlign: Int32 = 0
  override var userInterfaceLayoutDirection: NSUserInterfaceLayoutDirection {
    didSet { alignment = resolvedAlignment(logicalAlign, userInterfaceLayoutDirection) }
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

/// An alert's handle. `NSAlert` is not a view and cannot be one, so the handle is a
/// placeholder that holds the alert's configuration until `sui_present` builds the real
/// thing — the same split GTK needs for `GtkAlertDialog`, which is not a widget either.
private final class AlertView: NSView {
  var title = ""
  var message = ""
  var actions: [(label: String, role: Int32, tap: Tap)] = []
  var onDismiss: Tap?
  /// Non-nil exactly while it is on screen. Cleared *before* the app's own dismissal ends
  /// the sheet, which is how the completion handler tells that apart from a user's choice.
  var alert: NSAlert?
  /// The actions in the order NSAlert holds its buttons.
  var shown: [(label: String, role: Int32, tap: Tap)] = []
}

/// A sheet's handle: an ordinary vertical stack, so its children mount by the ordinary
/// `sui_insert_after` path. `sui_present` puts it inside a sheet window.
private final class SheetView: NSStackView {
  var title = ""
  var onDismiss: Tap?
  var sheetWindow: SheetWindow?
  /// A sheet has no title bar on macOS, so the title is drawn as the sheet's own heading —
  /// otherwise `Sheet("Quick note")` would set a window title nobody can see, which is the
  /// exact bug GTK and Android shipped.
  var heading: NSTextField?

  /// Its stretched children follow its insets, as a Column's do (#87).
  override var edgeInsets: NSEdgeInsets {
    didSet { syncColumnFill(self) }
  }
}

/// Kind 19, a `ZStack`: children drawn over one another, later subviews on top — which is
/// subview order on both Apple toolkits, so the insert position *is* the paint order.
///
/// Neither toolkit has a view for this (`NSStackView`/`UIStackView` are `Column` and `Row`),
/// so it is a plain view and constraints. Each child gets one constraint per axis that places
/// it and four inequalities that keep it inside; the stack's own wish to be 0×0 is weaker
/// than anything a child asks for, so it settles at exactly its largest child — the size
/// GTK gets from `measure` on each overlay and Android from `FrameLayout`.
private final class ZStackView: NSView {
  /// 0 start, 1 centre, 2 end, per axis. Centred until told otherwise, as in the contract.
  var horizontal: Int32 = 1
  var vertical: Int32 = 1
  private var placed: [ObjectIdentifier: [NSLayoutConstraint]] = [:]

  init() {
    super.init(frame: .zero)
    let w = widthAnchor.constraint(equalToConstant: 0)
    let h = heightAnchor.constraint(equalToConstant: 0)
    // Just above a stack's own hugging (249, #87), so in a Row with room to spare the slack
    // goes after the children rather than into the ZStack — at the weakest priority, 50, it
    // took all of it, 630pt — and still below anything a child asks for.
    w.priority = NSLayoutConstraint.Priority(251)
    h.priority = NSLayoutConstraint.Priority(251)
    NSLayoutConstraint.activate([w, h])
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  func insert(_ c: NSView, after: NSView?) {
    c.translatesAutoresizingMaskIntoConstraints = false
    // NSView has no insert-at-index; positioned/relativeTo is AppKit's way of saying it.
    // `.below` relative to nil is "bottom of the pile", which is where "first" belongs.
    if let a = after, a.superview === self {
      addSubview(c, positioned: .above, relativeTo: a)
    } else if after == nil {
      addSubview(c, positioned: .below, relativeTo: nil)
    } else {
      addSubview(c)
    }
    place(c)
  }

  func realign() { subviews.forEach(place) }

  private func place(_ c: NSView) {
    NSLayoutConstraint.deactivate(placed[ObjectIdentifier(c)] ?? [])
    // Leading and trailing, not left and right: a right-to-left layout mirrors them.
    let x: NSLayoutConstraint
    switch horizontal {
    case 0: x = c.leadingAnchor.constraint(equalTo: leadingAnchor)
    case 2: x = c.trailingAnchor.constraint(equalTo: trailingAnchor)
    default: x = c.centerXAnchor.constraint(equalTo: centerXAnchor)
    }
    let y: NSLayoutConstraint
    switch vertical {
    case 0: y = c.topAnchor.constraint(equalTo: topAnchor)
    case 2: y = c.bottomAnchor.constraint(equalTo: bottomAnchor)
    default: y = c.centerYAnchor.constraint(equalTo: centerYAnchor)
    }
    let cs = [
      x, y,
      c.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor),
      c.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor),
      c.topAnchor.constraint(greaterThanOrEqualTo: topAnchor),
      c.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor)
    ]
    NSLayoutConstraint.activate(cs)
    placed[ObjectIdentifier(c)] = cs
  }

  /// Every way a child leaves — `sui_remove_child`, a destroy, a move — ends in
  /// `removeFromSuperview`, so this is the one place that forgets it.
  override func willRemoveSubview(_ subview: NSView) {
    super.willRemoveSubview(subview)
    placed[ObjectIdentifier(subview)] = nil
  }
}

/// Kind 22, a `Grid`: cells flow into `columns` columns in order, cell `i` at row
/// `i / columns`, column `i % columns`, each column as wide as its widest cell.
///
/// An `NSGridView`, AppKit's own grid, inside a plain view that supplies the padding
/// (`NSGridView` has none). Its rows are built from arrays of views, so it has no notion of
/// a cell's *index*: inserting one in the middle means every later cell moves. So the order
/// is kept here, and the rows are rebuilt from it — the same re-placing GTK does through
/// `GtkGridLayoutChild` — except that an append fills the next slot, and a removal empties
/// its slot and leaves the rebuild to the next constraint pass, so mounting and tearing down
/// n cells is linear (#78, review 2026-10-20 P2-14).
///
/// Every way a cell leaves comes through [[remove]]: `sui_remove_child` and `sui_destroy`
/// call it before detaching, because a removed view would otherwise linger in the row arrays.
private final class GridView: NSView {
  let grid = NSGridView()
  var columns = 1 {
    didSet { if columns != oldValue { reflow() } }
  }
  private(set) var cells: [NSView] = []
  /// A cell was removed since the rows were last built; see [[remove]].
  private var stale = false
  private var insets: [NSLayoutConstraint] = []
  /// What the constraints in `insets` hold, for `sui_get_insets`.
  private(set) var current = NSEdgeInsetsZero

  init() {
    super.init(frame: .zero)
    grid.translatesAutoresizingMaskIntoConstraints = false
    // Leading and top, as GTK's labels sit and as the UIKit construction places them; the
    // grid's default centres a narrow cell in a wide column.
    grid.xPlacement = .leading
    grid.yPlacement = .top
    addSubview(grid)
    setInsets(NSEdgeInsetsZero)
    // As narrow as its cells allow (#87). It has no intrinsic size, so in a Column wider than it
    // — every one, now that Columns stretch — the stack's own pull towards its trailing edge
    // (at its hugging, 250 at most) widened the last column to 883pt. Just above that pull,
    // and below any cell's compression resistance.
    let hug = widthAnchor.constraint(equalToConstant: 0)
    hug.priority = NSLayoutConstraint.Priority(251)
    hug.isActive = true
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  /// Padding plus any safe area, per edge (#76).
  func setInsets(_ e: NSEdgeInsets) {
    current = e
    NSLayoutConstraint.deactivate(insets)
    insets = [
      grid.leftAnchor.constraint(equalTo: leftAnchor, constant: e.left),
      grid.topAnchor.constraint(equalTo: topAnchor, constant: e.top),
      rightAnchor.constraint(equalTo: grid.rightAnchor, constant: e.right),
      bottomAnchor.constraint(equalTo: grid.bottomAnchor, constant: e.bottom)
    ]
    NSLayoutConstraint.activate(insets)
  }

  override func viewDidMoveToWindow() {
    super.viewDidMoveToWindow()
    refreshInsets(self)
  }

  override func layout() {
    super.layout()
    refreshInsets(self)
  }

  func setSpacing(_ s: CGFloat) {
    grid.rowSpacing = s
    grid.columnSpacing = s
  }

  func insert(_ c: NSView, after: NSView?) {
    // Appending a new cell, which is every cell of a mount, fills the next slot rather than
    // rebuilding every row: nothing before it moves. Rebuilding made mounting n cells O(n²)
    // (#78, review 2026-10-20 P2-14). A move is never this case, because a moved view is
    // already a cell.
    if !stale, let a = after, a === cells.last, gridOf(c) !== self {
      c.translatesAutoresizingMaskIntoConstraints = false
      cells.append(c)
      append(c)
      return
    }
    // A move arrives as an insert of a view that is already a cell.
    cells.removeAll { $0 === c }
    let at: Int
    if let a = after { at = cells.firstIndex(of: a).map { $0 + 1 } ?? cells.count } else { at = 0 }
    c.translatesAutoresizingMaskIntoConstraints = false
    cells.insert(c, at: at)
    reflow()
  }

  /// The cell's slot is emptied at once, so the grid never holds a view that has left it;
  /// moving every later cell along waits for the next constraint pass, so a teardown that
  /// removes every cell rebuilds once rather than once per cell (#78).
  func remove(_ c: NSView) {
    guard let i = cells.firstIndex(of: c) else { return }
    cells.remove(at: i)
    grid.cell(for: c)?.contentView = NSGridCell.emptyContentView
    stale = true
    needsUpdateConstraints = true
  }

  override func updateConstraints() {
    flush()
    super.updateConstraints()
  }

  /// Brings the rows up to date with `cells` after a removal. Anything that reads them first
  /// calls this.
  func flush() {
    if stale { reflow() }
  }

  /// Places the last of `cells`, which every other cell precedes, exactly where [[reflow]]
  /// would: into the empty slot of a short last row, as a new column while the first row is
  /// still filling, or as a new row padded to the grid's width.
  private func append(_ c: NSView) {
    let k = cells.count - 1
    let row = k / columns
    let column = k % columns
    if row == grid.numberOfRows {
      var views = [c]
      while views.count < grid.numberOfColumns { views.append(NSGridCell.emptyContentView) }
      grid.addRow(with: views)
    } else if column == grid.numberOfColumns {
      grid.addColumn(with: [c])
    } else {
      grid.cell(atColumnIndex: column, rowIndex: row).contentView = c
    }
  }

  private func reflow() {
    stale = false
    while grid.numberOfRows > 0 { grid.removeRow(at: 0) }
    while grid.numberOfColumns > 0 { grid.removeColumn(at: 0) }
    // As many columns as there are cells to fill them, up to `columns`: a grid of two cells
    // and three columns has no empty third column taking spacing, as on GTK.
    let width = min(columns, cells.count)
    for start in stride(from: 0, to: cells.count, by: columns) {
      var row = Array(cells[start..<min(start + columns, cells.count)])
      while row.count < width { row.append(NSGridCell.emptyContentView) }
      grid.addRow(with: row)
    }
  }

  /// Where the grid itself has placed `c`, read from its rows and columns.
  func position(of c: NSView) -> (row: Int, column: Int)? {
    flush()
    guard let cell = grid.cell(for: c), let r = cell.row, let col = cell.column else { return nil }
    return (grid.index(of: r), grid.index(of: col))
  }

  /// The cells in reading order, as the grid holds them — not as `cells` says.
  var placed: [NSView] {
    flush()
    return (0..<grid.numberOfRows).flatMap { r in
      (0..<grid.numberOfColumns).compactMap { c -> NSView? in
        let v = grid.cell(atColumnIndex: c, rowIndex: r).contentView
        return v === NSGridCell.emptyContentView ? nil : v
      }
    }
  }
}

/// The grid a view is a cell of: its superview is the `NSGridView`, whose superview is ours.
private func gridOf(_ v: NSView) -> GridView? { v.superview?.superview as? GridView }

/// Kind 25, one `Tab`: a plain view holding its one child. Inserting a second child replaces
/// the first, as on GTK and Android. The title and the symbol are kept here and put on the
/// `NSTabViewItem` while the page is in a TabView, which is where AppKit shows them.
private final class TabPage: NSView {
  var title = "" { didSet { item?.label = title } }
  var symbol: String?
  var image: NSImage? { didSet { item?.image = image } }
  /// This page's item while it is in a TabView. The controller owns the item, the item's view
  /// controller owns this view; the handle's own retain is what keeps the page alive between.
  weak var item: NSTabViewItem?
  weak var host: TabHostView?

  init() { super.init(frame: .zero) }
  required init?(coder: NSCoder) { fatalError("not used") }

  var content: NSView? { subviews.first }

  func setContent(_ c: NSView) {
    subviews.forEach { $0.removeFromSuperview() }
    c.translatesAutoresizingMaskIntoConstraints = false
    addSubview(c)
    // Top-aligned: a page as tall as the TabView's tallest leaves a short one at the top,
    // as GTK's homogeneous stack does.
    NSLayoutConstraint.activate([
      c.leadingAnchor.constraint(equalTo: leadingAnchor),
      c.trailingAnchor.constraint(equalTo: trailingAnchor),
      c.topAnchor.constraint(equalTo: topAnchor),
      c.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor)
    ])
    host?.refit()
  }
}

/// `tabView(_:didSelect:)` is the one place every selection passes, the user's and ours, so
/// ours are counted here and only the user's reach the app.
private final class TabsController: NSTabViewController {
  var programmatic = 0
  var onUser: (() -> Void)?

  override func tabView(_ tabView: NSTabView, didSelect tabViewItem: NSTabViewItem?) {
    super.tabView(tabView, didSelect: tabViewItem)
    if programmatic == 0 { onUser?() }
  }
}

/// Kind 24, a `TabView`: an `NSTabViewController` whose view this view holds.
///
/// `.segmentedControlOnTop`, not `.toolbar`: the toolbar style puts the tabs in the *window's*
/// toolbar, which is the idiom for a window that is nothing but tabs (a Settings window) and
/// allows one per window; a TabView here is a view in a tree, with siblings, and a segmented
/// control over its content is AppKit's in-content idiom for that.
///
/// The order is the controller's own `tabViewItems`, so every question about tabs — which are
/// there, which is selected, which is on screen — is answered by AppKit, not by a list kept
/// beside it. The rules for keeping the user on their tab are the renderer's (`TabSelection`);
/// this only inserts, removes and selects, and reports the user's choices.
private final class TabHostView: NSView {
  let controller = TabsController()
  private var fit: [NSLayoutConstraint] = []

  init() {
    super.init(frame: .zero)
    controller.tabStyle = .segmentedControlOnTop
    // No cross-fade: a switch is immediate, and a test reads the result in the same turn.
    controller.transitionOptions = []
    let v = controller.view
    v.translatesAutoresizingMaskIntoConstraints = false
    addSubview(v)
    NSLayoutConstraint.activate([
      v.leadingAnchor.constraint(equalTo: leadingAnchor),
      v.trailingAnchor.constraint(equalTo: trailingAnchor),
      v.topAnchor.constraint(equalTo: topAnchor),
      v.bottomAnchor.constraint(equalTo: bottomAnchor)
    ])
    controller.onUser = { [weak self] in self?.userChose() }
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  var pages: [TabPage] { controller.tabViewItems.compactMap { $0.viewController?.view as? TabPage } }

  /// Our own changes to the controller: none of them is the user's choice.
  private func quietly(_ f: () -> Void) {
    controller.programmatic += 1
    f()
    controller.programmatic -= 1
  }

  func insert(_ p: TabPage, after: NSView?) {
    quietly {
      // A move arrives as an insert of a page that is already here.
      if let old = p.item, controller.tabViewItems.contains(old) { controller.removeTabViewItem(old) }
      let current = pages
      let at: Int
      if let a = after { at = current.firstIndex { $0 === a }.map { $0 + 1 } ?? current.count } else { at = 0 }
      let vc = NSViewController()
      vc.view = p
      let item = NSTabViewItem(viewController: vc)
      item.label = p.title
      item.image = p.image
      p.item = item
      p.host = self
      controller.insertTabViewItem(item, at: at)
    }
    refit()
  }

  func remove(_ p: TabPage) {
    quietly {
      if let item = p.item, controller.tabViewItems.contains(item) { controller.removeTabViewItem(item) }
    }
    p.item = nil
    p.host = nil
    refit()
  }

  var selected: Int {
    let i = controller.selectedTabViewItemIndex
    return i >= 0 && i < controller.tabViewItems.count ? i : -1
  }

  /// -1 or past the end leaves the tab shown, as on GTK and Android.
  func select(_ i: Int) {
    guard i >= 0, i < controller.tabViewItems.count, i != selected else { return }
    quietly { controller.selectedTabViewItemIndex = i }
  }

  private func userChose() {
    guard let s = selections[ObjectIdentifier(self)], selected >= 0 else { return }
    s.cb(s.ctx, Int32(selected))
  }

  /// The segmented control the controller draws its tabs with: what a user clicks.
  var bar: NSSegmentedControl? { firstSegmented(in: controller.view) }

  private func firstSegmented(in v: NSView) -> NSSegmentedControl? {
    if let s = v as? NSSegmentedControl { return s }
    for c in v.subviews { if let s = firstSegmented(in: c) { return s } }
    return nil
  }

  /// The tab view lays a page out by its frame, so nothing in a page's content reaches the
  /// TabView's own size. It is given one: as tall as the tallest page's content and as wide
  /// as the widest — homogeneous, as GTK's stack is, so switching tabs does not move the
  /// views below.
  func refit() {
    NSLayoutConstraint.deactivate(fit)
    let contents = pages.compactMap(\.content)
    let h = contents.map { $0.fittingSize.height }.max() ?? 0
    let w = contents.map { $0.fittingSize.width }.max() ?? 0
    let tv = controller.tabView
    fit = [
      tv.heightAnchor.constraint(greaterThanOrEqualToConstant: h),
      tv.widthAnchor.constraint(greaterThanOrEqualToConstant: w)
    ]
    NSLayoutConstraint.activate(fit)
  }
}

/// Escape on a sheet arrives as `cancelOperation`. That is the platform closing it without
/// a choice, which is what `OnDismiss` reports; the app then takes it down by unmounting.
private final class SheetWindow: NSWindow {
  var onCancel: (() -> Void)?
  override func cancelOperation(_ sender: Any?) { onCancel?() }
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

/// #72: an Objective-C exception that unwinds into Scala Native frames ends in `std::terminate`
/// as "uncaught foreign exception", and its name and reason are lost. So every exception is
/// written to stderr as it is thrown, before any unwinding. One the toolkit throws and catches
/// itself is logged too; that is rare, and a line too many beats a crash with no reason.
private var previousPreprocessor: objc_exception_preprocessor?
private var exceptionLogInstalled = false

private func logObjCExceptions() {
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

@_cdecl("sui_app_start")
public func sui_app_start(
  _ width: Int32, _ height: Int32, _ title: UnsafePointer<CChar>,
  _ ready: @escaping sui_void_cb, _ ctx: Int64
) {
  logObjCExceptions()
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

/// The lookup `sui_set_image_file` makes, for an app that wants the bytes of its own file.
@_cdecl("sui_resource_path")
public func sui_resource_path(_ path: UnsafePointer<CChar>) -> UnsafePointer<CChar>? {
  scratch(resolveImagePath(String(cString: path)))
}

/// Whether NSImage(data:) decodes these bytes. A host asks before handing over a format the
/// platform may not take: on AppKit, SVG decodes on macOS 14+ (`_NSSVGImageRep`) and, by every
/// report, not on 13, the shim's minimum (#85).
@_cdecl("sui_image_decodes")
public func sui_image_decodes(_ data: UnsafePointer<UInt8>, _ length: Int32) -> Int32 {
  guard length > 0 else { return 0 }
  return NSImage(data: Data(bytes: data, count: Int(length))) != nil ? 1 : 0
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
    // Without this a stack hugs its content and a Grow child cannot expand into it. Below a
    // plain child's hugging (250) and above a grown one's (248): a stack wider than its
    // children — every Row, now that a Column stretches it across (#87) — puts the slack after
    // them, as GTK does, unless a child grows into it. At 250 it tied with a button's hugging,
    // and AppKit stretched the first button in the row to 800pt.
    s.setHuggingPriority(stackHugging, for: kind == 0 ? .vertical : .horizontal)
    return retained(s)

  case 2:
    // AppKit has no NSLabel; a non-editable, non-bordered NSTextField is the label.
    let l = LabelField(labelWithString: "")
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
    let s = ScrollView()
    s.hasVerticalScroller = true
    s.drawsBackground = false
    return retained(s)

  case 15:
    // The same class as 6, scrolling the other way. Turning the *cross*-axis scroller off
    // is what makes the scroller give its natural size there instead of reserving room for
    // a bar it will never show — the same reason GTK sets its cross-axis policy to NEVER.
    let s = ScrollView()
    s.hasHorizontalScroller = true
    s.hasVerticalScroller = false
    s.drawsBackground = false
    return retained(s)

  case 8:
    let iv = ImageView()
    iv.imageScaling = .scaleProportionallyUpOrDown
    return retained(iv)

  case 9:
    // No label: NSSwitch has nowhere to put one, so the caption is a sibling.
    let s = NSSwitch()
    s.target = proxy
    s.action = #selector(Proxy.toggled(_:))
    return retained(s)

  case 10:
    // Nothing to draw and no intrinsic size; it takes the room its siblings do not, through
    // Prop.Grow, which lowers its hugging exactly as for any other growing child.
    let v = NSView()
    v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    v.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
    return retained(v)

  case 11:
    let p = NSProgressIndicator()
    p.style = .bar
    p.isIndeterminate = false
    p.minValue = 0
    p.maxValue = 100
    return retained(p)

  case 12:
    // No "running" prop: it spins while mounted, and Show is what stops it.
    let p = NSProgressIndicator()
    p.style = .spinning
    p.isIndeterminate = true
    p.startAnimation(nil)
    return retained(p)

  case 13:
    let s = NSSlider()
    s.isContinuous = true
    s.target = proxy
    s.action = #selector(Proxy.slid(_:))
    return retained(s)

  case 16:
    return retained(AlertView())

  case 17:
    let s = SheetView()
    s.orientation = .vertical
    s.alignment = .leading
    s.spacing = 8
    return retained(s)

  case 18:
    // pullsDown false: a pop-up shows the current selection and is what a Picker means.
    // A pull-down menu would always show its title instead, which is a different control.
    let b = NSPopUpButton(frame: .zero, pullsDown: false)
    b.target = proxy
    b.action = #selector(Proxy.chose(_:))
    return retained(b)

  case 19:
    return retained(ZStackView())

  case 22:
    return retained(GridView())

  case 24:
    return retained(TabHostView())

  case 25:
    return retained(TabPage())

  case 23:
    // Borderless and image-only: AppKit's icon button, as in a window's toolbar. The symbol
    // is a template image, so it draws in the control's text colour and dims when disabled.
    let b = NSButton(title: "", target: proxy, action: #selector(Proxy.tapped(_:)))
    b.isBordered = false
    b.imagePosition = .imageOnly
    return retained(b)

  case 20:
    // .selectOne: one segment at a time, and a click on the chosen one leaves it chosen —
    // a segmented control is a choice, not a set of toggles.
    let s = NSSegmentedControl()
    s.trackingMode = .selectOne
    s.segmentCount = 0
    s.target = proxy
    s.action = #selector(Proxy.segmented(_:))
    return retained(s)

  case 21:
    // A text field with a stepper, whose click opens a calendar: AppKit's own form control
    // for a date. Not .clockAndCalendar, which is a whole calendar inline.
    let p = NSDatePicker()
    p.datePickerStyle = .textFieldAndStepper
    p.datePickerElements = .yearMonthDay
    p.datePickerMode = .single
    p.timeZone = TimeZone(identifier: "UTC")
    p.target = proxy
    p.action = #selector(Proxy.dated(_:))
    return retained(p)

  case 14:
    // A separate class on AppKit, which is why SecureField is a kind rather than a prop.
    let f = NSSecureTextField(string: "")
    f.delegate = proxy
    f.isEditable = true
    f.isBordered = true
    return retained(f)

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
  (view(h) as? NSImageView)?.image = NSImage(contentsOfFile: resolveImagePath(String(cString: path)))
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
  dateChanges.removeValue(forKey: id)
  links.removeValue(forKey: id)
  symbols.removeValue(forKey: id)
  insetStates.removeValue(forKey: id)
  edits.removeValue(forKey: id)
  toggles.removeValue(forKey: id)
  valueChanges.removeValue(forKey: id)
  // A virtual list's source is owned here (the table's references to it are weak), so it
  // goes with the view; keyed by the handle, which is the same object `sui_create_table`
  // keyed it by.
  tableSources.removeValue(forKey: id)
  suppressed.remove(id)
  selections.removeValue(forKey: id)
  // A page leaves its TabView's controller, which would otherwise keep it alive and shown.
  if let page = v as? TabPage { page.host?.remove(page) }
  if let sheet = v as? SheetView {
    // The window held the stack as its content; the handle's own retain is what keeps the
    // view alive until here, so dropping the window now frees nothing early.
    sheet.sheetWindow?.onCancel = nil
    sheet.sheetWindow?.contentView = nil
    sheet.sheetWindow = nil
  }
  // Detaching is part of destroying, not a separate step the caller performs first — the
  // contract says so, and the reconciler destroys depth-first.
  gridOf(v)?.remove(v)
  v.removeFromSuperview()
  Unmanaged<NSView>.fromOpaque(h).release()
}

// MARK: - properties

@_cdecl("sui_set_text")
public func sui_set_text(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  let v = view(h)
  let s = String(cString: text)
  switch v {
  // A presented widget's title arrives as Prop.Text, for *both* presented kinds.
  case let a as AlertView:
    a.title = s
    a.alert?.messageText = s
  case let sheet as SheetView:
    sheet.title = s
    sheet.heading?.stringValue = s
  case let page as TabPage:
    page.title = s
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
  let v = view(h)
  let want = on != 0
  guard isOn(v) != want else { return }
  let id = ObjectIdentifier(v)
  suppressed.insert(id)
  let state: NSControl.StateValue = want ? .on : .off
  if let b = v as? NSButton { b.state = state } else if let s = v as? NSSwitch { s.state = state }
  suppressed.remove(id)
}

@_cdecl("sui_get_checked")
public func sui_get_checked(_ h: UnsafeMutableRawPointer) -> Int32 {
  isOn(view(h)) ? 1 : 0
}

@_cdecl("sui_set_enabled")
public func sui_set_enabled(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  (view(h) as? NSControl)?.isEnabled = on != 0
}

@_cdecl("sui_set_spacing")
public func sui_set_spacing(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  switch view(h) {
  case let s as NSStackView: s.spacing = CGFloat(dp)
  case let g as GridView: g.setSpacing(CGFloat(dp))
  default: break
  }
}

@_cdecl("sui_set_padding")
public func sui_set_padding(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  // A stack's and a grid's padding is around their children; a scroller's insets its content,
  // which scrolls with it, as on GTK and Android (#76: it used to be dropped here).
  let v = view(h)
  guard v is NSStackView || v is GridView || v is ScrollView else { return }
  insetStates[ObjectIdentifier(v), default: InsetState()].padding = CGFloat(dp)
  refreshInsets(v)
}

@_cdecl("sui_set_grid_columns")
public func sui_set_grid_columns(_ h: UnsafeMutableRawPointer, _ count: Int32) {
  (view(h) as? GridView)?.columns = max(1, Int(count))
}

/// An SF Symbol, at the body text style's size so it scales with the text beside it. A
/// name the SDK lacks leaves the view with no image and is not recorded, which the
/// self-test reports: `NSImage(systemSymbolName:)` returns nil and would draw nothing.
@_cdecl("sui_set_symbol")
public func sui_set_symbol(_ h: UnsafeMutableRawPointer, _ name: UnsafePointer<CChar>) {
  let n = String(cString: name)
  let v = view(h)
  let image = NSImage(systemSymbolName: n, accessibilityDescription: nil)?
    .withSymbolConfiguration(NSImage.SymbolConfiguration(textStyle: .body, scale: .large))
  symbols[ObjectIdentifier(v)] = image == nil ? nil : n
  switch v {
  case let b as NSButton: b.image = image
  case let i as NSImageView: i.image = image
  case let page as TabPage:
    page.symbol = image == nil ? nil : n
    page.image = image
  default: break
  }
}

/// What VoiceOver announces, and the tooltip — which macOS shows, as GNOME and Android do.
@_cdecl("sui_set_accessible_label")
public func sui_set_accessible_label(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  let s = String(cString: text)
  let v = view(h)
  v.setAccessibilityLabel(s)
  v.toolTip = s
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
  // Below the stacks' own hugging and every plain view's 250, so a grown child takes the slack
  // (#87). At .defaultLow it tied with a label's own 250, and only the row being exactly as wide
  // as its children hid that.
  let priority: NSLayoutConstraint.Priority = on != 0 ? grownHugging : .defaultHigh
  v.setContentHuggingPriority(priority, for: .horizontal)
  v.setContentHuggingPriority(priority, for: .vertical)
}

@_cdecl("sui_set_align")
public func sui_set_align(_ h: UnsafeMutableRawPointer, _ align: Int32) {
  guard let f = view(h) as? NSTextField else { return }
  (f as? LabelField)?.logicalAlign = align
  f.alignment = resolvedAlignment(align, f.userInterfaceLayoutDirection)
}

@_cdecl("sui_set_progress")
public func sui_set_progress(_ h: UnsafeMutableRawPointer, _ has: Int32, _ fraction: Double) {
  guard let p = view(h) as? NSProgressIndicator, p.style == .bar else { return }
  if has == 0 {
    // None is "no idea how far", not "not started": an animating bar, not an empty one.
    p.isIndeterminate = true
    p.startAnimation(nil)
  } else {
    p.stopAnimation(nil)
    p.isIndeterminate = false
    p.doubleValue = min(max(fraction, 0), 1) * p.maxValue
  }
}

@_cdecl("sui_set_range")
public func sui_set_range(_ h: UnsafeMutableRawPointer, _ lo: Double, _ hi: Double) {
  guard let s = view(h) as? NSSlider else { return }
  s.minValue = lo
  s.maxValue = hi
}

@_cdecl("sui_set_value")
public func sui_set_value(_ h: UnsafeMutableRawPointer, _ value: Double) {
  guard let s = view(h) as? NSSlider, s.doubleValue != value else { return }
  let id = ObjectIdentifier(s)
  suppressed.insert(id)
  s.doubleValue = value
  suppressed.remove(id)
}

// MARK: - events

@_cdecl("sui_on_tap")
public func sui_on_tap(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_void_cb, _ ctx: Int64) {
  let v = view(h)
  taps[ObjectIdentifier(v)] = Tap(cb: cb, ctx: ctx)
  makeTappable(v)
}

@_cdecl("sui_set_open_url")
public func sui_set_open_url(_ h: UnsafeMutableRawPointer, _ url: UnsafePointer<CChar>?) {
  let v = view(h)
  // A string the platform cannot parse as a URL opens nothing, rather than crashing a tap.
  links[ObjectIdentifier(v)] = URL(string: str(url))
  makeTappable(v)
  // AppKit has no link button. A borderless button titled in the system link colour is
  // what Finder and System Settings use for one; the accent fill a Link never sends is
  // what would otherwise make it look like a command.
  if let b = v as? NSButton {
    b.isBordered = false
    b.contentTintColor = .linkColor
  }
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

// MARK: - picker

@_cdecl("sui_picker_clear_options")
public func sui_picker_clear_options(_ h: UnsafeMutableRawPointer) {
  if let seg = view(h) as? NSSegmentedControl { seg.segmentCount = 0; return }
  guard let b = view(h) as? NSPopUpButton else { return }
  b.removeAllItems()
}

@_cdecl("sui_picker_add_option")
public func sui_picker_add_option(_ h: UnsafeMutableRawPointer, _ label: UnsafePointer<CChar>?) {
  if let seg = view(h) as? NSSegmentedControl {
    seg.segmentCount += 1
    seg.setLabel(label.map { String(cString: $0) } ?? "", forSegment: seg.segmentCount - 1)
    return
  }
  guard let b = view(h) as? NSPopUpButton else { return }
  // addItem(withTitle:) silently refuses a duplicate title, which would make two options
  // sharing a label collapse into one and shift every index after it. Build the item.
  let item = NSMenuItem(title: str(label), action: nil, keyEquivalent: "")
  b.menu?.addItem(item)
}

@_cdecl("sui_set_selected")
public func sui_set_selected(_ h: UnsafeMutableRawPointer, _ index: Int32) {
  if let t = view(h) as? TabHostView { t.select(Int(index)); return }
  if let seg = view(h) as? NSSegmentedControl {
    let id = ObjectIdentifier(seg)
    suppressed.insert(id)
    if index >= 0 && Int(index) < seg.segmentCount {
      seg.selectedSegment = Int(index)
    } else {
      // -1: nothing chosen, which .selectOne allows when no segment is selected. Deselecting
      // segment by segment did not do it: under .selectOne the chosen segment stayed chosen
      // (measured, #77) — which nothing saw while a change of options cleared it anyway.
      seg.selectedSegment = -1
      for i in 0..<seg.segmentCount where seg.isSelected(forSegment: i) { seg.setSelected(false, forSegment: i) }
    }
    suppressed.remove(id)
    return
  }
  guard let b = view(h) as? NSPopUpButton, b.indexOfSelectedItem != Int(index) else { return }
  let id = ObjectIdentifier(b)
  suppressed.insert(id)
  // -1, or an index past the end after the options shrank: nothing chosen, which a pop-up
  // draws as a blank title (D13).
  if index < 0 || Int(index) >= b.numberOfItems { b.select(nil) } else { b.selectItem(at: Int(index)) }
  suppressed.remove(id)
}

@_cdecl("sui_get_selected")
public func sui_get_selected(_ h: UnsafeMutableRawPointer) -> Int32 {
  if let t = view(h) as? TabHostView { return Int32(t.selected) }
  if let seg = view(h) as? NSSegmentedControl { return Int32(seg.selectedSegment) }
  guard let b = view(h) as? NSPopUpButton else { return -1 }
  return Int32(b.indexOfSelectedItem)
}

@_cdecl("sui_set_stack_alignment")
public func sui_set_stack_alignment(_ h: UnsafeMutableRawPointer, _ horizontal: Int32, _ vertical: Int32) {
  guard let z = view(h) as? ZStackView else { return }
  z.horizontal = horizontal
  z.vertical = vertical
  z.realign()
}

@_cdecl("sui_on_select")
public func sui_on_select(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_int_cb, _ ctx: Int64) {
  selections[ObjectIdentifier(view(h))] = Selection(cb: cb, ctx: ctx)
}

@_cdecl("sui_set_date")
public func sui_set_date(_ h: UnsafeMutableRawPointer, _ epochDayValue: Int32) {
  guard let p = view(h) as? NSDatePicker else { return }
  let id = ObjectIdentifier(p)
  // An app's own write is not a choice. UIKit sends no action for it anyway; AppKit may.
  suppressed.insert(id)
  p.dateValue = dateOf(epochDayValue)
  suppressed.remove(id)
}

@_cdecl("sui_get_date")
public func sui_get_date(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let p = view(h) as? NSDatePicker else { return Int32.min }
  return epochDay(p.dateValue)
}

@_cdecl("sui_on_date_change")
public func sui_on_date_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_int_cb, _ ctx: Int64) {
  dateChanges[ObjectIdentifier(view(h))] = Selection(cb: cb, ctx: ctx)
}

@_cdecl("sui_set_safe_area")
public func sui_set_safe_area(_ h: UnsafeMutableRawPointer, _ edges: Int32) {
  let v = view(h)
  // AppKit has `safeAreaInsets` but no automatic padding, so the insets on the chosen edges are
  // added to the view's own padding by `refreshInsets` — once the view is in a window, and
  // again whenever it lays out. Here, at mount, the view is in no window and they are zero,
  // which is all the old code here ever read (#76). On a Mac they are usually zero anyway: a
  // titled window's content view sits below its title bar. The exceptions are a full-size
  // content view, full screen under the menu bar, and the camera housing.
  insetStates[ObjectIdentifier(v), default: InsetState()].edges = edges
  refreshInsets(v)
}

/// Test only: extra safe area on the root view, as a full-size content view's title bar would
/// add, so a self-test can see the insets arrive and change on a Mac whose safe area is zero.
@_cdecl("sui_set_extra_safe_area")
public func sui_set_extra_safe_area(_ top: Double, _ left: Double, _ bottom: Double, _ right: Double) {
  rootView?.additionalSafeAreaInsets = NSEdgeInsets(
    top: CGFloat(top), left: CGFloat(left), bottom: CGFloat(bottom), right: CGFloat(right)
  )
  rootView?.window?.layoutIfNeeded()
}

// MARK: - tree

@_cdecl("sui_insert_after")
public func sui_insert_after(
  _ parent: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer,
  _ after: UnsafeMutableRawPointer?
) {
  let p = view(parent)
  let c = view(child)

  if let z = p as? ZStackView {
    z.insert(c, after: after.map(view))
    return
  }

  if let g = p as? GridView {
    g.insert(c, after: after.map(view))
    return
  }

  if let t = p as? TabHostView, let page = c as? TabPage {
    t.insert(page, after: after.map(view))
    return
  }

  if let page = p as? TabPage {
    page.setContent(c)
    return
  }

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
    //
    // A scroller's padding is its `contentInsets` (#76): the clip view starts the document that
    // far in, and the document scrolls with it. So the cross-axis pin is short by the insets on
    // that axis, or the document would overflow it by exactly them and scroll the wrong way.
    c.translatesAutoresizingMaskIntoConstraints = false
    let mine = scroll as? ScrollView
    let tie: NSLayoutConstraint
    if isHorizontalScroller(scroll) {
      NSLayoutConstraint.activate([
        c.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor),
        c.topAnchor.constraint(equalTo: scroll.contentView.topAnchor)
      ])
      // An NSScrollView has no intrinsic size in either direction. Its height is the
      // document's, through one constraint tied to the document, so it follows the content
      // when that changes (#76). It used to be a constant read here, added afresh for every
      // child — a replaced child left the old one behind to conflict, and a taller label
      // never resized the row (review L4). The width is the parent's, pinned below.
      tie = scroll.heightAnchor.constraint(equalTo: c.heightAnchor)
    } else {
      NSLayoutConstraint.activate([
        c.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor),
        c.topAnchor.constraint(equalTo: scroll.contentView.topAnchor)
      ])
      tie = c.trailingAnchor.constraint(equalTo: scroll.contentView.trailingAnchor)
    }
    // The previous document's tie went with it: a constraint between two views is removed when
    // either leaves the hierarchy. Deactivated anyway, in case the old document stays put.
    mine?.tie?.isActive = false
    tie.isActive = true
    mine?.tie = tie
    mine?.retie()
    scroll.layoutSubtreeIfNeeded()
    mine?.scrollToStart()
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

  // A move re-inserts a child already in the stack, whose fill constraint is still there.
  let moving = c.superview === stack

  // Appending after the last child, which is every child of a mount, is answered without a
  // search. The search below is linear, and doing it for every child made mounting n
  // children O(n²) (#78, review 2026-10-20 P2-14).
  let arranged = stack.arrangedSubviews
  let index: Int
  if let a = after {
    let target = view(a)
    index = arranged.last === target
      ? arranged.count
      : (arranged.firstIndex(of: target).map { $0 + 1 }) ?? arranged.count
  } else {
    index = 0
  }
  stack.insertArrangedSubview(c, at: min(index, arranged.count))

  // #87: a Column stretches its children across, as GTK's boxes, Android's LinearLayout and
  // UIKit's .fill stack do. A vertical NSStackView aligned .leading gave each child its own
  // natural width instead, so a Row did not span its Column, a Spacer in it had no room to
  // push into, and an aligned Label hugged its text. A horizontal scroller was the one child
  // that already filled, because its natural width is zero.
  //
  // The stack's width less its side insets, kept in step with them by `syncColumnFill`. Not a
  // weaker "as wide as the stack" left to the stack's own inset constraints to cut short: those
  // are not required, and at 999 the child ran over the padding to the stack's edge.
  //
  // O(1) per child, so mounting a Column stays linear (#78): the constant is set here rather
  // than by re-syncing every fill constraint, a move is told by the child already being in the
  // stack rather than by searching its constraints, and nothing is laid out until the next
  // pass. Laying out the stack after each insert, as the first version did, made mounting 2 000
  // children take minutes off screen (measured: sampled inside layoutSubtreeIfNeeded).
  if stack.orientation == .vertical, fillsColumn(c), !moving {
    let fill = c.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -(stack.edgeInsets.left + stack.edgeInsets.right))
    fill.identifier = columnFillId
    fill.isActive = true
  }
}

/// The main-axis order of who takes a stack's slack (#87): a grown child first, then the gap
/// after the children, and a plain child (hugging 250, AppKit's default) never.
private let grownHugging = NSLayoutConstraint.Priority(248)
private let stackHugging = NSLayoutConstraint.Priority(249)

/// Identifies a Column's stretch constraint (#87).
private let columnFillId = "thicket.column-fill"

/// A Column's stretched children are as wide as it, less its side insets. Called on insert and
/// whenever the insets are written, by whatever writes them (padding, safe area).
private func syncColumnFill(_ stack: NSStackView) {
  let inset = -(stack.edgeInsets.left + stack.edgeInsets.right)
  for c in stack.constraints where c.identifier == columnFillId && c.constant != inset { c.constant = inset }
}

/// Whether a Column stretches `c` across its width (#87). Everything does except the controls
/// AppKit draws at their own size — a push button's bezel, a checkbox, a switch, a pop-up, a
/// segmented control, a date field, a spinner — and the two whose content would change with
/// the width: an Image would scale up to fill it, and a Grid's last column would widen, where
/// each column is meant to be as wide as its widest cell. Those keep the leading edge.
private func fillsColumn(_ c: NSView) -> Bool {
  switch c {
  case is NSButton, is NSSwitch, is NSSegmentedControl, is NSDatePicker, is ImageView, is GridView:
    return false
  case let p as NSProgressIndicator:
    return p.style == .bar
  default:
    return true
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

  /// Row views the table was handed that it did not already hold, and how many of those it
  /// has handed back, so `sui_table_materialised` can answer with a number rather than an
  /// impression.
  var built = 0
  var discarded = 0

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
    if view !== recycled { built += 1 }
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
  return Int32(s.built)
}

@_cdecl("sui_table_discarded")
public func sui_table_discarded(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let (_, s) = table(of: h) else { return 0 }
  return Int32(s.discarded)
}

@_cdecl("sui_table_scroll_to")
public func sui_table_scroll_to(_ h: UnsafeMutableRawPointer, _ row: Int32) {
  guard let (t, s) = table(of: h), row >= 0, Int(row) < s.count else { return }
  t.scrollRowToVisible(Int(row))
  t.enclosingScrollView?.layoutSubtreeIfNeeded()
  t.layoutSubtreeIfNeeded()
}

/// AppKit has no memory warning to post. Nor, measured, does an `NSTableView` let go of a
/// row view while it is mounted: scrolling all 10 000 rows of the probe built 43 and freed
/// none (#78). The row view is AppKit's unit of reuse, and Scala holds a reference to it, so
/// one the table dropped could not be noticed here; none is.
@_cdecl("sui_post_memory_warning")
public func sui_post_memory_warning() -> Int32 {
  0
}

@_cdecl("sui_table_live")
public func sui_table_live() -> Int32 {
  Int32(tableSources.count)
}

/// The row view NSTableView holds for `row`, only while that row is on screen: `makeIfNecessary`
/// false, so asking never makes the table bind a row it would not otherwise show.
@_cdecl("sui_table_row_view")
public func sui_table_row_view(_ h: UnsafeMutableRawPointer, _ row: Int32) -> UnsafeMutableRawPointer? {
  guard let (t, _) = table(of: h), row >= 0, Int(row) < t.numberOfRows else { return nil }
  t.layoutSubtreeIfNeeded()
  guard NSLocationInRange(Int(row), t.rows(in: t.visibleRect)),
        let v = t.view(atColumn: 0, row: Int(row), makeIfNecessary: false)
  else { return nil }
  return Unmanaged.passUnretained(v).toOpaque()
}

@_cdecl("sui_remove_child")
public func sui_remove_child(_ parent: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer) {
  let p = view(parent)
  let c = view(child)
  if let scroll = p as? NSScrollView {
    if scroll.documentView === c { scroll.documentView = nil }
    return
  }
  if let t = p as? TabHostView, let page = c as? TabPage {
    t.remove(page)
    return
  }
  if let stack = p as? NSStackView { stack.removeArrangedSubview(c) }
  (p as? GridView)?.remove(c)
  c.removeFromSuperview()
  (p as? TabPage)?.host?.refit()
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

@_cdecl("sui_run_on_main_after")
public func sui_run_on_main_after(_ delayMs: Int32, _ cb: @escaping sui_void_cb, _ ctx: Int64) {
  DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(delayMs))) { cb(ctx) }
}

// MARK: - inspection

private func arranged(_ v: NSView) -> [NSView] {
  if let s = v as? NSStackView { return s.arrangedSubviews }
  if let s = v as? NSScrollView { return s.documentView.map { [$0] } ?? [] }
  if let g = v as? GridView { return g.placed }
  // Every tab, in the controller's order, whether or not its view is in the window now.
  if let t = v as? TabHostView { return t.pages }
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
  guard let p = view(h) as? NSProgressIndicator else { return -2 }
  if p.isIndeterminate { return -1 }
  let span = p.maxValue - p.minValue
  return span > 0 ? (p.doubleValue - p.minValue) / span : 0
}

@_cdecl("sui_get_value")
public func sui_get_value(_ h: UnsafeMutableRawPointer) -> Double {
  (view(h) as? NSSlider)?.doubleValue ?? 0
}

@_cdecl("sui_is_secure")
public func sui_is_secure(_ h: UnsafeMutableRawPointer) -> Int32 {
  view(h) is NSSecureTextField ? 1 : 0
}

// MARK: - presentation

private func appWindow() -> NSWindow? { window ?? NSApp.mainWindow ?? NSApp.windows.first }

@_cdecl("sui_present")
public func sui_present(_ h: UnsafeMutableRawPointer) {
  guard let parent = appWindow() else { return }
  switch view(h) {
  case let a as AlertView: presentAlert(a, over: parent)
  case let s as SheetView: presentSheet(s, over: parent)
  default: break
  }
}

private func presentAlert(_ a: AlertView, over parent: NSWindow) {
  let alert = NSAlert()
  alert.messageText = a.title
  alert.informativeText = a.message
  // NSAlert has no cancel *role*, only a convention: the cancel button goes leftmost, which
  // is the last one added, and answers Escape. A destructive button is marked as such and
  // coloured by AppKit, not here.
  let ordered = a.actions.filter { $0.role != 2 } + a.actions.filter { $0.role == 2 }
  for action in ordered {
    let b = alert.addButton(withTitle: action.label)
    if action.role == 1 { b.hasDestructiveAction = true }
    if action.role == 2 { b.keyEquivalent = "\u{1b}" }
  }
  a.shown = ordered
  a.alert = alert
  alert.beginSheetModal(for: parent) { [weak a] response in
    // Nil means the app dismissed it first: not the user's choice, and not reported.
    guard let a = a, a.alert === alert else { return }
    a.alert = nil
    let i = response.rawValue - NSApplication.ModalResponse.alertFirstButtonReturn.rawValue
    if i >= 0 && i < a.shown.count {
      let t = a.shown[i].tap
      t.cb(t.ctx)
    } else if let d = a.onDismiss {
      d.cb(d.ctx)
    }
  }
}

private func presentSheet(_ s: SheetView, over parent: NSWindow) {
  let heading = NSTextField(labelWithString: s.title)
  heading.font = NSFont.preferredFont(forTextStyle: .headline)
  s.heading = heading
  let content = NSStackView(views: [heading, s])
  content.orientation = .vertical
  content.alignment = .leading
  content.spacing = 12
  content.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)

  let win = SheetWindow(
    contentRect: NSRect(x: 0, y: 0, width: 360, height: 10),
    styleMask: [.titled],
    backing: .buffered,
    defer: false
  )
  win.contentView = content
  win.setContentSize(NSSize(width: max(360, content.fittingSize.width), height: content.fittingSize.height))
  win.onCancel = { [weak s] in
    guard let s = s, let d = s.onDismiss else { return }
    d.cb(d.ctx)
  }
  s.sheetWindow = win
  parent.beginSheet(win)
}

@_cdecl("sui_dismiss")
public func sui_dismiss(_ h: UnsafeMutableRawPointer) {
  switch view(h) {
  case let a as AlertView:
    // Cleared first, so the completion handler sees an app dismissal and stays silent.
    guard let alert = a.alert else { return }
    a.alert = nil
    appWindow()?.endSheet(alert.window)
  case let s as SheetView:
    // Only take it off screen. `sui_destroy` owns the teardown.
    guard let win = s.sheetWindow else { return }
    win.onCancel = nil
    win.sheetParent?.endSheet(win)
    win.orderOut(nil)
  default:
    break
  }
}

@_cdecl("sui_set_message")
public func sui_set_message(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  guard let a = view(h) as? AlertView else { return }
  a.message = String(cString: text)
  a.alert?.informativeText = a.message
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
  case let a as AlertView: return a.alert != nil ? 1 : 0
  case let s as SheetView: return (s.sheetWindow?.sheetParent != nil) ? 1 : 0
  default: return 0
  }
}

@_cdecl("sui_presented_title")
public func sui_presented_title(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  switch view(h) {
  case let a as AlertView: return a.alert.flatMap { scratch($0.messageText) }
  // Gated the same way as `sui_is_presented`: the window outlives an app dismissal until
  // `sui_destroy`, so its existence is not the question; being attached as a sheet is.
  case let s as SheetView:
    return s.sheetWindow?.sheetParent == nil ? nil : s.heading.flatMap { scratch($0.stringValue) }
  default: return nil
  }
}

@_cdecl("sui_presented_message")
public func sui_presented_message(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  (view(h) as? AlertView)?.alert.flatMap { scratch($0.informativeText) }
}

@_cdecl("sui_alert_action_count")
public func sui_alert_action_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  Int32((view(h) as? AlertView)?.alert?.buttons.count ?? 0)
}

@_cdecl("sui_alert_action_label")
public func sui_alert_action_label(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  guard let buttons = (view(h) as? AlertView)?.alert?.buttons, index >= 0, Int(index) < buttons.count
  else { return nil }
  return scratch(buttons[Int(index)].title)
}

@_cdecl("sui_perform_click")
public func sui_perform_click(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let b = view(h) as? NSButton else { return 0 }
  b.performClick(nil)
  return 1
}

/// A real click: the view is scrolled into sight, a point at its centre must hit-test to it,
/// and a left mouse-down and mouse-up there are sent through the window's `sendEvent`, the path
/// the window server's events take — so gesture recognisers, `mouseDown` overrides and
/// controls all see what a user's click gives them. 0 when the view is not in a window or
/// something else would take the click.
@_cdecl("sui_perform_tap")
public func sui_perform_tap(_ h: UnsafeMutableRawPointer) -> Int32 {
  let v = view(h)
  guard let w = v.window, let content = w.contentView else { return 0 }
  v.scrollToVisible(v.bounds)
  w.layoutIfNeeded()
  let centre = v.convert(NSPoint(x: v.bounds.midX, y: v.bounds.midY), to: nil)
  let probe = content.superview.map { $0.convert(centre, from: nil) } ?? centre
  guard let hit = (content.superview ?? content).hitTest(probe), hit === v || hit.isDescendant(of: v)
  else { return 0 }
  for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
    guard let e = NSEvent.mouseEvent(
      with: type, location: centre, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
      windowNumber: w.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: 1
    ) else { return 0 }
    w.sendEvent(e)
  }
  return 1
}

/// Forces a view's layout direction, as a right-to-left locale sets it for the whole app, so
/// a test can measure what the view does under it. 1 right-to-left, 0 back to the app's.
@_cdecl("sui_set_layout_direction")
public func sui_set_layout_direction(_ h: UnsafeMutableRawPointer, _ rtl: Int32) {
  let v = view(h)
  v.userInterfaceLayoutDirection = rtl != 0 ? .rightToLeft : NSApp.userInterfaceLayoutDirection
  v.needsDisplay = true
}

@_cdecl("sui_alert_choose")
public func sui_alert_choose(_ h: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
  guard let buttons = (view(h) as? AlertView)?.alert?.buttons, index >= 0, Int(index) < buttons.count
  else { return 0 }
  // The alert's own button, so the choice travels NSAlert's response path to the
  // completion handler exactly as a click does.
  buttons[Int(index)].performClick(nil)
  return 1
}

@_cdecl("sui_presented_count")
public func sui_presented_count() -> Int32 {
  var n: Int32 = 0
  var w = appWindow()
  while let sheet = w?.attachedSheet {
    n += 1
    w = sheet
  }
  return n
}

// MARK: - context menus

/// Menu items alive right now, counted in `init` and `deinit` so that "the menu went with
/// its view" is a number rather than an assumption.
private var liveMenuItems: Int32 = 0

/// An item's callback, carried on the `NSMenuItem` itself. `representedObject` needs an
/// object, and `Tap` is a struct. Owned by the item, which the menu owns, which the view
/// owns: nothing here outlives the view, and the count says so.
private final class MenuChoice: NSObject {
  let tap: Tap
  init(_ tap: Tap) {
    self.tap = tap
    liveMenuItems += 1
  }
  deinit { liveMenuItems -= 1 }
}

/// `NSMenuItem.target` is weak; this one is a global and lives for the process.

private final class MenuTarget: NSObject {
  @objc func chosen(_ sender: NSMenuItem) {
    guard let c = sender.representedObject as? MenuChoice else { return }
    c.tap.cb(c.tap.ctx)
  }
}
private let menuTarget = MenuTarget()

@_cdecl("sui_menu_clear")
public func sui_menu_clear(_ h: UnsafeMutableRawPointer) {
  view(h).menu = nil
}

/// `NSView.menu` is all AppKit needs: a secondary click on the view — or on a subview that
/// has no menu of its own, through the responder chain — opens it. No gesture is installed.
@_cdecl("sui_menu_add_item")
public func sui_menu_add_item(
  _ h: UnsafeMutableRawPointer, _ label: UnsafePointer<CChar>, _ enabled: Int32,
  _ cb: @escaping sui_void_cb, _ ctx: Int64
) {
  let v = view(h)
  let menu: NSMenu
  if let m = v.menu {
    menu = m
  } else {
    menu = NSMenu()
    // Otherwise AppKit enables items itself by asking the target, and `enabled` is ignored.
    menu.autoenablesItems = false
    v.menu = menu
  }
  let item = NSMenuItem(title: String(cString: label), action: #selector(MenuTarget.chosen(_:)), keyEquivalent: "")
  item.target = menuTarget
  item.isEnabled = enabled != 0
  item.representedObject = MenuChoice(Tap(cb: cb, ctx: ctx))
  menu.addItem(item)
}

@_cdecl("sui_menu_item_count")
public func sui_menu_item_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  Int32(view(h).menu?.items.count ?? 0)
}

@_cdecl("sui_menu_item_label")
public func sui_menu_item_label(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  guard let items = view(h).menu?.items, index >= 0, Int(index) < items.count else { return nil }
  return scratch(items[Int(index)].title)
}

/// `performActionForItem(at:)` is NSMenu's own dispatch — the path a click on the item
/// takes once the menu is open — so the item's closure runs through AppKit, not around it.
@_cdecl("sui_menu_activate")
public func sui_menu_activate(_ h: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
  guard let menu = view(h).menu, index >= 0, Int(index) < menu.items.count,
        menu.items[Int(index)].isEnabled
  else { return 0 }
  menu.performActionForItem(at: Int(index))
  return 1
}

@_cdecl("sui_menu_live")
public func sui_menu_live() -> Int32 {
  liveMenuItems
}

// MARK: - navigation (AppRoot.pages)

// macOS has no push idiom, so the stack is a sidebar: an NSSplitViewController with the live
// pages as a source list and the selected page's content beside it. Every page stays
// mounted, which is what `pages` exists for; only the selected one is in the detail view.
// Choosing an earlier entry is going back to it, reported to the app like any platform pop.
// With a single page there is nothing to choose between, so the sidebar is collapsed.

private var pendingPages: [(id: Int64, content: NSView, title: String)] = []
private var pageList: [(id: Int64, content: NSView, title: String)] = []
private var applyingPages = false
private var onPopped: (cb: sui_int_cb, ctx: Int64)?

private final class PagesSidebar: NSObject, NSTableViewDataSource, NSTableViewDelegate {
  let table = NSTableView()

  func numberOfRows(in tableView: NSTableView) -> Int { pageList.count }

  func tableView(_ tableView: NSTableView, viewFor column: NSTableColumn?, row: Int) -> NSView? {
    let cell = NSTableCellView()
    let label = NSTextField(labelWithString: row < pageList.count ? pageList[row].title : "")
    label.translatesAutoresizingMaskIntoConstraints = false
    cell.addSubview(label)
    cell.textField = label
    NSLayoutConstraint.activate([
      label.leadingAnchor.constraint(equalTo: cell.leadingAnchor, constant: 4),
      label.trailingAnchor.constraint(equalTo: cell.trailingAnchor, constant: -4),
      label.centerYAnchor.constraint(equalTo: cell.centerYAnchor)
    ])
    return cell
  }

  /// A user choosing an earlier entry. The app's own commits select rows too, and are
  /// suppressed by `applyingPages` so they are not echoed back as pops.
  func tableViewSelectionDidChange(_ notification: Notification) {
    let row = table.selectedRow
    guard !applyingPages, row >= 0, row < pageList.count - 1, let p = onPopped else { return }
    pageList = Array(pageList.prefix(row + 1))
    p.cb(p.ctx, Int32(row + 1))
  }
}

private let sidebar = PagesSidebar()
private var splitController: NSSplitViewController?
private var sidebarItem: NSSplitViewItem?
private let detailView = NSView()

private func ensureSplit() -> Bool {
  if splitController != nil { return true }
  guard let root = rootView else { return false }

  let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("sui_page"))
  sidebar.table.addTableColumn(column)
  sidebar.table.headerView = nil
  sidebar.table.style = .sourceList
  sidebar.table.dataSource = sidebar
  sidebar.table.delegate = sidebar
  let scroll = NSScrollView()
  scroll.documentView = sidebar.table
  scroll.hasVerticalScroller = true
  scroll.drawsBackground = false

  let sidebarController = NSViewController()
  sidebarController.view = scroll
  let detailController = NSViewController()
  detailController.view = detailView

  let split = NSSplitViewController()
  let item = NSSplitViewItem(sidebarWithViewController: sidebarController)
  item.minimumThickness = 140
  split.addSplitViewItem(item)
  split.addSplitViewItem(NSSplitViewItem(viewController: detailController))

  split.view.translatesAutoresizingMaskIntoConstraints = false
  root.addSubview(split.view)
  NSLayoutConstraint.activate([
    split.view.leadingAnchor.constraint(equalTo: root.leadingAnchor),
    split.view.trailingAnchor.constraint(equalTo: root.trailingAnchor),
    split.view.topAnchor.constraint(equalTo: root.topAnchor),
    split.view.bottomAnchor.constraint(equalTo: root.bottomAnchor)
  ])
  splitController = split
  sidebarItem = item
  return true
}

@_cdecl("sui_pages_begin")
public func sui_pages_begin() {
  pendingPages = []
}

@_cdecl("sui_pages_add")
public func sui_pages_add(_ id: Int64, _ content: UnsafeMutableRawPointer, _ title: UnsafePointer<CChar>) {
  pendingPages.append((id, view(content), String(cString: title)))
}

@_cdecl("sui_pages_commit")
public func sui_pages_commit() {
  guard ensureSplit(), let top = pendingPages.last else { return }
  pageList = pendingPages

  // The detail view shows the top page. The others stay mounted, just not attached here.
  for v in detailView.subviews where v !== top.content { v.removeFromSuperview() }
  if top.content.superview !== detailView {
    top.content.removeFromSuperview()
    sui_insert_after(
      Unmanaged.passUnretained(detailView).toOpaque(), Unmanaged.passUnretained(top.content).toOpaque(), nil
    )
  }

  applyingPages = true
  sidebar.table.reloadData()
  sidebar.table.selectRowIndexes(IndexSet(integer: pageList.count - 1), byExtendingSelection: false)
  applyingPages = false
  sidebarItem?.isCollapsed = pageList.count <= 1
}

@_cdecl("sui_on_pages_popped")
public func sui_on_pages_popped(_ cb: @escaping sui_int_cb, _ ctx: Int64) {
  onPopped = (cb, ctx)
}

@_cdecl("sui_pages_depth")
public func sui_pages_depth() -> Int32 {
  Int32(sidebar.table.numberOfRows)
}

/// Read from the sidebar's own cell for that row, which is what the user sees.
@_cdecl("sui_page_title")
public func sui_page_title(_ index: Int32) -> UnsafePointer<CChar>? {
  guard index >= 0, Int(index) < sidebar.table.numberOfRows,
        let cell = sidebar.table.view(atColumn: 0, row: Int(index), makeIfNecessary: true) as? NSTableCellView,
        let text = cell.textField?.stringValue
  else { return nil }
  return scratch(text)
}

/// Which page's content is actually in the detail view.
@_cdecl("sui_pages_shown")
public func sui_pages_shown() -> Int32 {
  guard let i = pageList.firstIndex(where: { $0.content.superview === detailView }) else { return -1 }
  return Int32(i)
}

@_cdecl("sui_pages_back_offered")
public func sui_pages_back_offered() -> Int32 {
  ((sidebarItem.map { !$0.isCollapsed } ?? false) && sidebar.table.numberOfRows > 1) ? 1 : 0
}

/// Selecting the previous sidebar entry, as a click on it would: the table's own selection
/// notification then reports it like any platform pop.
@_cdecl("sui_pages_back")
public func sui_pages_back() -> Int32 {
  let rows = sidebar.table.numberOfRows
  guard rows > 1 else { return 0 }
  sidebar.table.selectRowIndexes(IndexSet(integer: rows - 2), byExtendingSelection: false)
  return 1
}

@_cdecl("sui_has_image")
public func sui_has_image(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let iv = view(h) as? NSImageView else { return -1 }
  return iv.image != nil ? 1 : 0
}

/// The held image's size in points, as NSImage.size reports it: the SVG logo reads its own
/// 472.658x599.168, the PNG made from it something else, which is how the gallery tells them
/// apart (#85).
@_cdecl("sui_image_size")
public func sui_image_size(
  _ h: UnsafeMutableRawPointer, _ outWidth: UnsafeMutablePointer<Double>,
  _ outHeight: UnsafeMutablePointer<Double>
) -> Int32 {
  guard let image = (view(h) as? NSImageView)?.image else { return 0 }
  outWidth.pointee = Double(image.size.width)
  outHeight.pointee = Double(image.size.height)
  return 1
}

/// Read from what draws: ImageView's own cover flag, else NSImageView's scaling.
@_cdecl("sui_get_content_fit")
public func sui_get_content_fit(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let iv = view(h) as? NSImageView else { return -1 }
  if (iv as? ImageView)?.cover == true { return 1 }
  return iv.imageScaling == .scaleAxesIndependently ? 2 : 0
}

@_cdecl("sui_get_align")
public func sui_get_align(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let f = view(h) as? NSTextField else { return -1 }
  switch f.alignment {
  case .center: return 1
  case .right: return 2
  default: return 0
  }
}

/// Measured from the *top* of the content whatever the document view's coordinate system: in
/// an unflipped document the origin is bottom-left, so the top is at `frame.height`.
@_cdecl("sui_scroll_offset")
public func sui_scroll_offset(_ h: UnsafeMutableRawPointer) -> Double {
  guard let scroll = view(h) as? NSScrollView, let doc = scroll.documentView else { return -1 }
  let visible = scroll.contentView.bounds
  // From where the content starts: its top inset (padding, safe area) is not scrolled.
  let top = scroll.contentInsets.top
  return Double(doc.isFlipped ? visible.minY + top : doc.frame.height - visible.maxY)
}

// MARK: - read-back for the new controls (#45, #46)

@_cdecl("sui_option_count")
public func sui_option_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  switch view(h) {
  case let p as NSPopUpButton: return Int32(p.numberOfItems)
  case let s as NSSegmentedControl: return Int32(s.segmentCount)
  case let t as TabHostView: return Int32(t.bar?.segmentCount ?? 0)
  default: return 0
  }
}

@_cdecl("sui_option_label")
public func sui_option_label(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  let i = Int(index)
  switch view(h) {
  case let p as NSPopUpButton where i >= 0 && i < p.numberOfItems: return scratch(p.itemTitle(at: i))
  case let s as NSSegmentedControl where i >= 0 && i < s.segmentCount: return scratch(s.label(forSegment: i) ?? "")
  case let t as TabHostView:
    guard let s = t.bar, i >= 0, i < s.segmentCount else { return nil }
    return scratch(s.label(forSegment: i) ?? "")
  default: return nil
  }
}

/// What the control draws as the choice (#77, D13). For the pop-up that is the item its cell
/// draws — `NSPopUpButtonCell.menuItem` — rather than `indexOfSelectedItem`, which is the
/// state the renderer just wrote and so cannot disagree with it.
@_cdecl("sui_shown_option")
public func sui_shown_option(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  switch view(h) {
  case let p as NSPopUpButton:
    return scratch((p.cell as? NSPopUpButtonCell)?.menuItem?.title ?? "")
  case let s as NSSegmentedControl:
    let i = s.selectedSegment
    return scratch(i >= 0 && i < s.segmentCount ? (s.label(forSegment: i) ?? "") : "")
  default:
    return nil
  }
}

/// Selecting and then sending the control's own action is what a user's click does; the
/// action runs the same proxy method, which reports the choice to the app.
@_cdecl("sui_choose")
public func sui_choose(_ h: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
  let i = Int(index)
  switch view(h) {
  case let p as NSPopUpButton where i >= 0 && i < p.numberOfItems:
    p.selectItem(at: i)
    return p.sendAction(p.action, to: p.target) ? 1 : 0
  case let s as NSSegmentedControl where i >= 0 && i < s.segmentCount:
    s.selectedSegment = i
    return s.sendAction(s.action, to: s.target) ? 1 : 0
  case let t as TabHostView:
    // The controller's own segmented control, clicked through its own action.
    guard let s = t.bar, i >= 0, i < s.segmentCount else { return 0 }
    s.selectedSegment = i
    return s.sendAction(s.action, to: s.target) ? 1 : 0
  default:
    return 0
  }
}

/// The symbol the bar shows for item `index`: recorded only when AppKit resolved the name,
/// and only while the bar shows that very image.
@_cdecl("sui_option_symbol")
public func sui_option_symbol(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  let i = Int(index)
  guard let t = view(h) as? TabHostView, let s = t.bar, i >= 0, i < s.segmentCount, i < t.pages.count
  else { return nil }
  let page = t.pages[i]
  guard let shown = s.image(forSegment: i), shown === page.image, let n = page.symbol else { return nil }
  return scratch(n)
}

/// The page AppKit has on screen: the selected item's view, while it is in the window.
@_cdecl("sui_tab_shown")
public func sui_tab_shown(_ h: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer? {
  guard let t = view(h) as? TabHostView, let v = t.controller.tabView.selectedTabViewItem?.view,
    v.window != nil, !v.isHiddenOrHasHiddenAncestor
  else { return nil }
  return Unmanaged.passUnretained(v).toOpaque()
}

@_cdecl("sui_choose_date")
public func sui_choose_date(_ h: UnsafeMutableRawPointer, _ epochDayValue: Int32) -> Int32 {
  guard let p = view(h) as? NSDatePicker else { return 0 }
  p.dateValue = dateOf(epochDayValue)
  return p.sendAction(p.action, to: p.target) ? 1 : 0
}

/// In the window, with the origin top-left like UIKit's, whatever the views' own flipping.
@_cdecl("sui_get_frame")
public func sui_get_frame(
  _ h: UnsafeMutableRawPointer, _ outX: UnsafeMutablePointer<Double>, _ outY: UnsafeMutablePointer<Double>,
  _ outW: UnsafeMutablePointer<Double>, _ outH: UnsafeMutablePointer<Double>
) {
  let v = view(h)
  v.window?.layoutIfNeeded()
  // The *alignment* rect, which is what Auto Layout positions — not the frame. A label's
  // frame is 2pt wider each side than its alignment rect, so a trailing-aligned badge read by
  // frame overhangs its stack by 2pt while being exactly aligned.
  let align = v.alignmentRect(forFrame: v.frame)
  let r = v.superview.map { $0.convert(align, to: nil) } ?? v.convert(v.bounds, to: nil)
  let height = v.window?.contentView?.bounds.height ?? r.maxY
  outX.pointee = Double(r.minX)
  outY.pointee = Double(height - r.maxY)
  outW.pointee = Double(r.width)
  outH.pointee = Double(r.height)
}

/// Every active constraint on a view's height, wherever it is held: the view's own and its
/// ancestors'. Constraints AppKit makes for itself (an autoresizing mask, an intrinsic size)
/// are left out — they are not the renderer's.
private func heightConstraints(_ v: NSView) -> [NSLayoutConstraint] {
  var out: [NSLayoutConstraint] = []
  var holder: NSView? = v
  while let hv = holder {
    for c in hv.constraints where c.isActive && type(of: c) == NSLayoutConstraint.self {
      if (c.firstItem === v && c.firstAttribute == .height) || (c.secondItem === v && c.secondAttribute == .height) {
        out.append(c)
      }
    }
    holder = hv.superview
  }
  return out
}

private func write(
  _ e: NSEdgeInsets, _ t: UnsafeMutablePointer<Double>, _ l: UnsafeMutablePointer<Double>,
  _ b: UnsafeMutablePointer<Double>, _ r: UnsafeMutablePointer<Double>
) {
  t.pointee = Double(e.top)
  l.pointee = Double(e.left)
  b.pointee = Double(e.bottom)
  r.pointee = Double(e.right)
}

@_cdecl("sui_get_insets")
public func sui_get_insets(
  _ h: UnsafeMutableRawPointer, _ outTop: UnsafeMutablePointer<Double>, _ outLeft: UnsafeMutablePointer<Double>,
  _ outBottom: UnsafeMutablePointer<Double>, _ outRight: UnsafeMutablePointer<Double>
) {
  let v = view(h)
  v.window?.layoutIfNeeded()
  let e: NSEdgeInsets
  switch v {
  case let g as GridView: e = g.current
  case let s as NSScrollView: e = s.contentInsets
  case let s as NSStackView: e = s.edgeInsets
  default: e = NSEdgeInsetsZero
  }
  write(e, outTop, outLeft, outBottom, outRight)
}

@_cdecl("sui_get_safe_area")
public func sui_get_safe_area(
  _ h: UnsafeMutableRawPointer, _ outTop: UnsafeMutablePointer<Double>, _ outLeft: UnsafeMutablePointer<Double>,
  _ outBottom: UnsafeMutablePointer<Double>, _ outRight: UnsafeMutablePointer<Double>
) {
  let v = view(h)
  v.window?.layoutIfNeeded()
  write(v.safeAreaInsets, outTop, outLeft, outBottom, outRight)
}

@_cdecl("sui_height_constraint_count")
public func sui_height_constraint_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  Int32(heightConstraints(view(h)).count)
}

/// Sends a left-button event through the window, the path a real click takes: hit-tested by
/// `NSWindow.sendEvent`, and a mouse-up delivered to whatever took the mouse-down. (x, y) is a
/// point in `h`, top-left origin; it may be outside it. kind: 0 down, 1 dragged, 2 up.
@_cdecl("sui_send_mouse")
public func sui_send_mouse(_ h: UnsafeMutableRawPointer, _ kind: Int32, _ x: Double, _ y: Double) -> Int32 {
  let v = view(h)
  guard let w = v.window else { return 0 }
  w.layoutIfNeeded()
  // The window hit-tests the point, so the view has to be where the point is: on screen.
  if kind == 0 { _ = v.scrollToVisible(v.bounds) }
  let local = NSPoint(x: x, y: v.isFlipped ? y : v.bounds.height - y)
  let type: NSEvent.EventType
  switch kind {
  case 0: type = .leftMouseDown
  case 1: type = .leftMouseDragged
  default: type = .leftMouseUp
  }
  guard let e = NSEvent.mouseEvent(
    with: type, location: v.convert(local, to: nil), modifierFlags: [],
    timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: w.windowNumber, context: nil,
    eventNumber: 0, clickCount: 1, pressure: kind == 2 ? 0 : 1
  ) else { return 0 }
  // In a window that is not key, AppKit gives a press only to a view that accepts the first
  // mouse; any other view's press just activates the window, as on any Mac. A self-test runs
  // in the background and cannot make itself active, so a press on a label (which accepts it
  // and passes it up) arrived, but one on a tappable Row's own empty area did not — measured:
  // hit-tested to the Row, no mouseDown, app inactive. So in that case the events go where a
  // key window would send them: the press to the view it hit-tests, the drag and the release
  // to the view that took the press.
  if w.isKeyWindow {
    w.sendEvent(e)
  } else if kind == 0 {
    let cv = w.contentView
    let hit = cv.flatMap { $0.hitTest($0.superview?.convert(e.locationInWindow, from: nil) ?? e.locationInWindow) }
    mouseTarget = hit
    hit?.mouseDown(with: e)
  } else if let t = mouseTarget {
    if kind == 1 { t.mouseDragged(with: e) } else { t.mouseUp(with: e); mouseTarget = nil }
  }
  return 1
}

/// The view a synthesised press went to while the window was not key, for its drag and release.
private weak var mouseTarget: NSView?

@_cdecl("sui_record_opened_urls")
public func sui_record_opened_urls(_ on: Int32) {
  openedUrls = on != 0 ? [] : nil
}

@_cdecl("sui_opened_url_count")
public func sui_opened_url_count() -> Int32 {
  Int32(openedUrls?.count ?? 0)
}

@_cdecl("sui_opened_url")
public func sui_opened_url(_ index: Int32) -> UnsafePointer<CChar>? {
  guard let log = openedUrls, index >= 0, Int(index) < log.count else { return nil }
  return scratch(log[Int(index)])
}

/// What Foundation says the app's directories are, so the gallery's self-test can hold
/// thicket-storage-apple — which works them out without Foundation — to the same answer.
@_cdecl("sui_foundation_directory")
public func sui_foundation_directory(_ which: Int32) -> UnsafePointer<CChar>? {
  func user(_ d: FileManager.SearchPathDirectory) -> String? {
    FileManager.default.urls(for: d, in: .userDomainMask).first?.path
  }
  let value: String?
  switch which {
  case 0: value = user(.applicationSupportDirectory)
  case 1: value = user(.cachesDirectory)
  case 2: value = NSTemporaryDirectory()
  case 3: value = Bundle.main.bundleIdentifier
  default: value = nil
  }
  return value.flatMap(scratch)
}

/// Where the `NSGridView` has placed a cell, read from its own rows and columns.
@_cdecl("sui_grid_cell")
public func sui_grid_cell(
  _ h: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer,
  _ outRow: UnsafeMutablePointer<Double>, _ outColumn: UnsafeMutablePointer<Double>
) -> Int32 {
  guard let g = view(h) as? GridView, let at = g.position(of: view(child)) else { return 0 }
  outRow.pointee = Double(at.row)
  outColumn.pointee = Double(at.column)
  return 1
}

// MARK: - read-back for icons (#66)

@_cdecl("sui_symbol_exists")
public func sui_symbol_exists(_ name: UnsafePointer<CChar>) -> Int32 {
  NSImage(systemSymbolName: String(cString: name), accessibilityDescription: nil) == nil ? 0 : 1
}

/// The symbol a view shows: recorded only when AppKit resolved it, and only while the button
/// still holds an image.
@_cdecl("sui_get_symbol")
public func sui_get_symbol(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  let v = view(h)
  let shown: NSImage?
  switch v {
  case let b as NSButton: shown = b.image
  case let i as NSImageView: shown = i.image
  default: shown = nil
  }
  guard shown != nil, let n = symbols[ObjectIdentifier(v)] else { return nil }
  return scratch(n)
}

@_cdecl("sui_get_accessible_label")
public func sui_get_accessible_label(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  guard let s = view(h).accessibilityLabel() else { return nil }
  return scratch(s)
}

@_cdecl("sui_get_tooltip")
public func sui_get_tooltip(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  guard let s = view(h).toolTip else { return nil }
  return scratch(s)
}

/// The share of a view's pixels that are painted, from AppKit drawing it into a bitmap: a
/// button whose symbol resolved but drew nothing reads 0. -1 when the view has no size.
@_cdecl("sui_painted_fraction")
public func sui_painted_fraction(_ h: UnsafeMutableRawPointer) -> Double {
  guard let image = drawnImage(view(h)) else { return -1 }
  return paintedFraction(image)
}

/// The view drawn by AppKit into a bitmap; nil when it has no size.
private func drawnImage(_ v: NSView) -> CGImage? {
  v.window?.layoutIfNeeded()
  guard v.bounds.width >= 1, v.bounds.height >= 1, let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds)
  else { return nil }
  v.cacheDisplay(in: v.bounds, to: rep)
  return rep.cgImage
}

/// Drawn into an RGBA buffer of our own, so the pixel format is known whatever the source's.
private func paintedFraction(_ image: CGImage) -> Double {
  let w = image.width, h = image.height
  guard w > 0, h > 0 else { return -1 }
  var pixels = [UInt8](repeating: 0, count: w * h * 4)
  let drawn: Bool = pixels.withUnsafeMutableBytes { buf in
    guard let ctx = CGContext(
      data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return false }
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    return true
  }
  guard drawn else { return -1 }
  var painted = 0
  for i in stride(from: 3, to: pixels.count, by: 4) where pixels[i] > 25 { painted += 1 }
  return Double(painted) / Double(w * h)
}

/// Where the painted pixels of a drawn view sit across it: the first and last column holding
/// any, as fractions of its width (0 = the left edge, 1 = the right). Measures where text
/// actually lands, which `textAlignment` alone cannot say for `.natural`.
private func inkSpan(_ image: CGImage) -> (Double, Double)? {
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
  guard drawn else { return nil }
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

@_cdecl("sui_ink_span")
public func sui_ink_span(
  _ h: UnsafeMutableRawPointer, _ outLeft: UnsafeMutablePointer<Double>, _ outRight: UnsafeMutablePointer<Double>
) -> Int32 {
  guard let image = drawnImage(view(h)), let (l, r) = inkSpan(image) else { return 0 }
  outLeft.pointee = l
  outRight.pointee = r
  return 1
}

/// Width pins a test has put on views, so they can be taken off again.
private var widthPins: [ObjectIdentifier: NSLayoutConstraint] = [:]

/// Pins a view's width for a measurement, or takes the pin off when `width` is negative. An
/// AppKit Column hugs its children, so a one-word label is exactly as wide as its text and has
/// no room in which to show its alignment; this gives it the room a wider column would.
@_cdecl("sui_pin_width")
public func sui_pin_width(_ h: UnsafeMutableRawPointer, _ width: Double) {
  let v = view(h)
  let id = ObjectIdentifier(v)
  widthPins.removeValue(forKey: id)?.isActive = false
  if width >= 0 {
    let c = v.widthAnchor.constraint(equalToConstant: CGFloat(width))
    c.priority = .required
    c.isActive = true
    widthPins[id] = c
  }
  v.window?.layoutIfNeeded()
}
