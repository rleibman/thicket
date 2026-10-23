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
//   - UIKit has no checkbox, so Checkbox is a UISwitch beside a UILabel (`CheckboxView`);
//     AppKit has NSButton(checkboxWithTitle:).
//   - UILabel and UITextField are separate types; AppKit uses NSTextField for both.
//   - iOS 27 refuses to launch an app that does not adopt the UIScene lifecycle (S3), so
//     the application setup here has no AppKit counterpart at all.

private func view(_ h: UnsafeMutableRawPointer) -> UIView {
  Unmanaged<UIView>.fromOpaque(h).takeUnretainedValue()
}

/// A C string from Scala as a Swift one, `""` for null. #49's picker called this without it
/// ever being defined, which nothing on Linux can notice: the Swift is only compiled on a Mac.
private func str(_ p: UnsafePointer<CChar>?) -> String {
  p.map { String(cString: $0) } ?? ""
}

private func retained(_ v: UIView) -> UnsafeMutableRawPointer {
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
/// The SF Symbol each view shows, kept only when UIKit resolved the name: a `UIImage` has no
/// public way to say which symbol it is, so this is what `sui_get_symbol` reads back.
private var symbols: [ObjectIdentifier: String] = [:]

private func fireTap(_ id: ObjectIdentifier) {
  if let t = taps[id] { t.cb(t.ctx) }
  // Handed to the platform, which picks the browser or the app registered for the scheme.
  if let url = links[id] {
    if var log = openedUrls {
      log.append(url.absoluteString)
      openedUrls = log
    } else {
      UIApplication.shared.open(url)
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

/// A picker's options and selection, kept here rather than read back off the control.
///
/// UIKit has no `indexOfSelectedItem`: the state lives in the `UIMenu`'s elements, and the
/// only way to answer "which index is chosen" is to find the one whose state is `.on`. That
/// works, but rebuilding the menu on every option change makes it easy to lose, so the
/// titles and the index are held alongside and the menu is rebuilt from them.
private var pickerOptions: [ObjectIdentifier: [String]] = [:]
private var pickerSelected: [ObjectIdentifier: Int] = [:]

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
private final class Proxy: NSObject, UIGestureRecognizerDelegate {
  @objc func tapped(_ sender: UIControl) {
    fireTap(ObjectIdentifier(sender))
  }

  @objc func dated(_ sender: UIDatePicker) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let s = dateChanges[id] else { return }
    s.cb(s.ctx, epochDay(sender.date))
  }

  @objc func segmented(_ sender: UISegmentedControl) {
    let id = ObjectIdentifier(sender)
    guard !suppressed.contains(id), let s = selections[id] else { return }
    s.cb(s.ctx, Int32(sender.selectedSegmentIndex))
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
    fireTap(ObjectIdentifier(v))
  }

  /// A view's tap recogniser begins only when the view has something to run. Every Column
  /// and Row carries one from birth, and one with nothing to do would otherwise compete for
  /// a touch that a tappable label or image inside it is waiting for.
  func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
    guard g is TapRecognizer, let v = g.view else { return true }
    let id = ObjectIdentifier(v)
    return taps[id] != nil || links[id] != nil
  }

  /// A tap on a Checkbox's caption flips its switch, as a click on the label of GTK's check
  /// button or Android's CheckBox does: through the switch's own value-changed event.
  @objc func captionTapped(_ gesture: UITapGestureRecognizer) {
    guard let box = gesture.view?.superview as? CheckboxView, box.toggle.isEnabled else { return }
    box.toggle.setOn(!box.toggle.isOn, animated: true)
    box.toggle.sendActions(for: .valueChanged)
  }
}

/// The recogniser `OnTap` and `OpenUrl` put on a view that has no action of its own — every
/// kind but a button. Its own class, so the shim can tell it from UIKit's private ones.
private final class TapRecognizer: UITapGestureRecognizer {}

/// Makes any view report taps (#75): a Label, an Image, a ZStack — not only a Button, whose
/// `.touchUpInside` already goes through `fireTap`, and a Column or Row, which have their
/// recogniser from birth. GTK puts a click gesture on any widget and Android a click listener
/// on any view; before this, `Image(...).onTap` did nothing on iOS.
private func makeTappable(_ v: UIView) {
  if v is UIButton { return }
  if v.gestureRecognizers?.contains(where: { $0 is TapRecognizer }) == true { return }
  let g = TapRecognizer(target: proxy, action: #selector(Proxy.containerTapped(_:)))
  g.delegate = proxy
  // A control keeps its own touches: a tappable slider still slides.
  if v is UIControl { g.cancelsTouchesInView = false }
  v.addGestureRecognizer(g)
  // UILabel and UIImageView ignore touches by default.
  v.isUserInteractionEnabled = true
}

/// A Checkbox: UIKit has no such control, so it is the platform's boolean control, a
/// `UISwitch`, beside a `UILabel` carrying the caption — the iOS settings-row idiom, caption
/// leading and switch trailing. The switch stays the event source; tapping the caption flips
/// it. Before this a Checkbox was a bare switch and its label went nowhere (#75).
private final class CheckboxView: UIStackView {
  let label = UILabel()
  let toggle = UISwitch()

  override init(frame: CGRect) {
    super.init(frame: frame)
    axis = .horizontal
    alignment = .center
    spacing = 8
    label.numberOfLines = 0
    label.font = .preferredFont(forTextStyle: .body)
    label.setContentHuggingPriority(.defaultLow, for: .horizontal)
    label.isUserInteractionEnabled = true
    label.addGestureRecognizer(UITapGestureRecognizer(target: proxy, action: #selector(Proxy.captionTapped(_:))))
    addArrangedSubview(label)
    addArrangedSubview(toggle)
  }

  required init(coder: NSCoder) { fatalError("not from a nib") }
}

/// The switch a Checkbox or a Toggle flips.
private func switchOf(_ v: UIView) -> UISwitch? {
  (v as? UISwitch) ?? (v as? CheckboxView)?.toggle
}

/// `Alignment` as UIKit draws it. Start is `.natural`, which UIKit resolves against the view's
/// effective layout direction whenever it draws. End has no such value, so a `TextLabel`
/// resolves it again as it lays out; a field resolves it when it is set. `.left`/`.right`,
/// as before #75, put Start text on the left under Arabic or Hebrew.
private func resolvedAlignment(_ align: Int32, _ v: UIView) -> NSTextAlignment {
  switch align {
  case 1: return .center
  case 2: return v.effectiveUserInterfaceLayoutDirection == .rightToLeft ? .left : .right
  default: return .natural
  }
}

/// A Label: a `UILabel` that remembers its `Alignment`, so End follows the layout direction
/// the way Start's `.natural` does.
private final class TextLabel: UILabel {
  var logicalAlign: Int32 = 0
  override func layoutSubviews() {
    super.layoutSubviews()
    let a = thicketAlignment
    if textAlignment != a { textAlignment = a }
  }
  override var semanticContentAttribute: UISemanticContentAttribute {
    didSet { textAlignment = thicketAlignment }
  }
  var thicketAlignment: NSTextAlignment { resolvedAlignment(logicalAlign, self) }
}

private let proxy = Proxy()

/// Kinds 0 and 1, a `Column` or `Row`: a stack that hears about its safe area (#76), and lets
/// a grown label go past half a row (#87).
///
/// A horizontal UIStackView holding two or more multi-line labels caps each at half its width,
/// at priority 951 (`UISV-text-width-disambiguation`), which outranks any hugging. So a grown
/// label stopped at half the row and its plain sibling was stretched over the other half: 181
/// and 181 pt on a 370 pt row. A grown label is already disambiguated by its lower hugging, so
/// its cap is let go each time the stack rebuilds its constraints; a plain label keeps its own.
private final class StackView: UIStackView {
  override init(frame: CGRect) {
    super.init(frame: frame)
    // Its margins are the padding plus the edges `SafeArea` chose, and nothing else: left on,
    // UIKit would add the whole safe area to them as well, chosen or not.
    insetsLayoutMarginsFromSafeArea = false
  }

  required init(coder: NSCoder) { fatalError("not used") }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    refreshInsets(self)
  }

  override func safeAreaInsetsDidChange() {
    super.safeAreaInsetsDidChange()
    refreshInsets(self)
  }

  override func updateConstraints() {
    super.updateConstraints()
    guard axis == .horizontal else { return }
    for c in constraints where c.isActive && c.identifier == "UISV-text-width-disambiguation" {
      if let l = c.firstItem as? UILabel, l.contentHuggingPriority(for: .horizontal) < .defaultLow {
        c.isActive = false
      }
    }
  }
}

/// Kinds 6 and 15, a `Scroll`. Its own class for the safe-area hooks and for the constraints
/// that depend on its insets — the content's cross-axis tie, and a horizontal scroller's one
/// height constraint (#76).
private final class ScrollView: UIScrollView {
  /// Vertical: the content's width, the frame's less the side insets. Horizontal: the
  /// scroller's height, the content's plus the top and bottom insets. One per scroller,
  /// replaced with the content and updated with the insets.
  var tie: NSLayoutConstraint?

  override init(frame: CGRect) {
    super.init(frame: frame)
    // The insets are the padding plus the edges `SafeArea` chose. `.automatic` would add the
    // whole safe area to every scroller, chosen or not.
    contentInsetAdjustmentBehavior = .never
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  func setInsets(_ e: UIEdgeInsets) {
    let old = contentInset
    // At the start before, at the start after: the padding is where the content begins.
    let atStart = abs(contentOffset.x + old.left) < 0.5 && abs(contentOffset.y + old.top) < 0.5
    contentInset = e
    retie()
    if atStart { contentOffset = CGPoint(x: -e.left, y: -e.top) }
  }

  func retie() {
    let e = contentInset
    tie?.constant = isHorizontalScroller(self) ? e.top + e.bottom : -(e.left + e.right)
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    refreshInsets(self)
  }

  override func safeAreaInsetsDidChange() {
    super.safeAreaInsetsDidChange()
    refreshInsets(self)
  }
}

// MARK: - padding and the safe area (#76)

/// What a container's insets are made of: the app's `Padding`, plus the safe-area insets on
/// the edges `SafeArea` chose. Kept per view, so that each can change without losing the
/// other — the safe area used to be written over the padding (review M8).
private struct InsetState {
  var padding: CGFloat = 0
  var edges: Int32 = 0
  var applied: UIEdgeInsets?
}

private var insetStates: [ObjectIdentifier: InsetState] = [:]

/// The safe-area insets on the chosen edges; zero until the view is in a window. Leading and
/// trailing are resolved against the view's layout direction.
private func safeInsets(_ v: UIView, _ edges: Int32) -> UIEdgeInsets {
  guard edges != 0, v.window != nil else { return .zero }
  let i = v.safeAreaInsets
  let rtl = v.effectiveUserInterfaceLayoutDirection == .rightToLeft
  return UIEdgeInsets(
    top: (edges & 1) != 0 ? i.top : 0,
    left: (edges & (rtl ? 8 : 4)) != 0 ? i.left : 0,
    bottom: (edges & 2) != 0 ? i.bottom : 0,
    right: (edges & (rtl ? 4 : 8)) != 0 ? i.right : 0
  )
}

/// Writes padding + safe area to the view, if it changed: when either prop is set, when the
/// view enters a window, and when UIKit says its safe area changed.
private func refreshInsets(_ v: UIView) {
  let id = ObjectIdentifier(v)
  guard var st = insetStates[id] else { return }
  let s = safeInsets(v, st.edges)
  let p = st.padding
  let e = UIEdgeInsets(top: p + s.top, left: p + s.left, bottom: p + s.bottom, right: p + s.right)
  if st.applied == e { return }
  st.applied = e
  insetStates[id] = st
  switch v {
  case let g as GridView: g.insets = e
  case let s as ScrollView: s.setInsets(e)
  case let s as UIStackView:
    s.isLayoutMarginsRelativeArrangement = true
    s.layoutMargins = e
  default: break
  }
}

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

/// Kind 19, a `ZStack`: children drawn over one another, later subviews on top — which is
/// subview order on both Apple toolkits, so the insert position *is* the paint order.
///
/// Neither toolkit has a view for this (`NSStackView`/`UIStackView` are `Column` and `Row`),
/// so it is a plain view and constraints. Each child gets one constraint per axis that places
/// it and four inequalities that keep it inside; the stack's own wish to be 0×0 is weaker
/// than anything a child asks for, so it settles at exactly its largest child — the size
/// GTK gets from `measure` on each overlay and Android from `FrameLayout`.
private final class ZStackView: UIView {
  /// 0 start, 1 centre, 2 end, per axis. Centred until told otherwise, as in the contract.
  var horizontal: Int32 = 1
  var vertical: Int32 = 1
  private var placed: [ObjectIdentifier: [NSLayoutConstraint]] = [:]

  init() {
    super.init(frame: .zero)
    let w = widthAnchor.constraint(equalToConstant: 0)
    let h = heightAnchor.constraint(equalToConstant: 0)
    w.priority = .fittingSizeLevel
    h.priority = .fittingSizeLevel
    NSLayoutConstraint.activate([w, h])
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  func insert(_ c: UIView, after: UIView?) {
    c.translatesAutoresizingMaskIntoConstraints = false
    if let a = after, let i = subviews.firstIndex(of: a) {
      insertSubview(c, at: i + 1)
    } else if after == nil {
      insertSubview(c, at: 0)
    } else {
      addSubview(c)
    }
    place(c)
  }

  func realign() { subviews.forEach(place) }

  private func place(_ c: UIView) {
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
  override func willRemoveSubview(_ subview: UIView) {
    super.willRemoveSubview(subview)
    placed[ObjectIdentifier(subview)] = nil
  }
}
/// Kind 22, a `Grid`: cells flow into `columns` columns in order, cell `i` at row
/// `i / columns`, column `i % columns`, each column as wide as its widest cell and each row
/// as tall as its tallest.
///
/// UIKit has no grid view (a `UICollectionView` is a list of recycled items, not a layout of
/// given views), so this is the §12.2a construction: a layout guide per column and per row.
/// Each guide wants to be zero, and each cell sits inside its column's and its row's guide;
/// so a column settles at its widest cell and a row at its tallest — what `NSGridView`,
/// `GtkGrid` and `GridLayout` all do by default.
///
/// The layout is rebuilt from the order kept here, with two exceptions that keep a mount and
/// a teardown linear (#78, review 2026-10-20 P2-14): an append only adds the new cell's
/// constraints, and a removal marks the layout stale and leaves the rebuild to the next
/// constraint pass, so a teardown that removes every cell rebuilds once rather than once per
/// cell. Every way a cell leaves comes through [[remove]]: `sui_remove_child` and
/// `sui_destroy` call it.
private final class GridView: UIView {
  var columns = 1 {
    didSet { if columns != oldValue { relayout() } }
  }
  var spacing: CGFloat = 0 {
    didSet { relayout() }
  }
  /// Padding plus any safe area, per edge (#76); absolute, resolved to leading and trailing
  /// against the layout direction when laid out.
  var insets: UIEdgeInsets = .zero {
    didSet { relayout() }
  }
  private(set) var cells: [UIView] = []
  private var laid: [NSLayoutConstraint] = []
  private var cols: [UILayoutGuide] = []
  private var rows: [UILayoutGuide] = []
  /// The grid's edges against its last column and last row: the only constraints an append
  /// replaces rather than adds to.
  private var trailingHug: [NSLayoutConstraint] = []
  private var bottomHug: [NSLayoutConstraint] = []
  /// A cell was removed since the last rebuild; the next constraint pass rebuilds.
  private var stale = false

  init() {
    super.init(frame: .zero)
    relayout()
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  func insert(_ c: UIView, after: UIView?) {
    // Appending a new cell, which is every cell of a mount, adds its own constraints and
    // leaves everyone else's: nothing before it moves. Rebuilding made mounting n cells
    // O(n²) (#78, review 2026-10-20 P2-14). A move is never this case, because a moved view
    // is already a subview.
    if !stale, let a = after, a === cells.last, c.superview !== self {
      c.translatesAutoresizingMaskIntoConstraints = false
      cells.append(c)
      addSubview(c)
      append(c)
      return
    }
    // A move arrives as an insert of a view that is already a cell.
    cells.removeAll { $0 === c }
    let at: Int
    if let a = after { at = cells.firstIndex(of: a).map { $0 + 1 } ?? cells.count } else { at = 0 }
    c.translatesAutoresizingMaskIntoConstraints = false
    cells.insert(c, at: at)
    if c.superview !== self { addSubview(c) }
    relayout()
  }

  /// The first removal also takes the whole placement down, in one batch. A view leaving its
  /// superview has UIKit search the constraints there for the ones that name it, so with the
  /// placement left up each removal cost O(n), and a teardown was still O(n²) (measured on
  /// the simulator: 0.5 s for 1 000 cells, 2.2 s for 2 000). Nothing is laid out from the
  /// missing constraints: the constraint pass that rebuilds them comes before any layout.
  func remove(_ c: UIView) {
    guard let i = cells.firstIndex(of: c) else { return }
    cells.remove(at: i)
    if !stale {
      stale = true
      takeDown()
      setNeedsUpdateConstraints()
    }
  }

  override func updateConstraints() {
    if stale { relayout() }
    super.updateConstraints()
  }

  /// Where this construction placed a cell. Ours, so the self-test checks the frames too.
  func position(of c: UIView) -> (row: Int, column: Int)? {
    cells.firstIndex(of: c).map { ($0 / columns, $0 % columns) }
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    refreshInsets(self)
  }

  override func safeAreaInsetsDidChange() {
    super.safeAreaInsetsDidChange()
    refreshInsets(self)
  }

  /// The insets on the leading and trailing sides, against the layout direction (#76).
  private var lead: CGFloat { effectiveUserInterfaceLayoutDirection == .rightToLeft ? insets.right : insets.left }
  private var trail: CGFloat { effectiveUserInterfaceLayoutDirection == .rightToLeft ? insets.left : insets.right }

  // A guide's wish to be zero sits between the grid's wish to hug its columns (250, below)
  // and a cell's compression resistance (750): it gives way to the cells, so a column is
  // never narrower than its widest one, but not to a parent stretching the grid. At 50 it
  // lost to the hug, and the stretch went into a column (measured: the first, 264 wide
  // around a widest cell of 193).
  private func weakly(_ c: NSLayoutConstraint) -> NSLayoutConstraint {
    c.priority = UILayoutPriority(500)
    return c
  }

  /// Column `j`'s guide, after column `j - 1` (or the padding), wanting to be zero wide.
  private func columnGuide(_ j: Int) -> (UILayoutGuide, [NSLayoutConstraint]) {
    let g = UILayoutGuide()
    addLayoutGuide(g)
    return (g, [
      j == 0
        ? g.leadingAnchor.constraint(equalTo: leadingAnchor, constant: lead)
        : g.leadingAnchor.constraint(equalTo: cols[j - 1].trailingAnchor, constant: spacing),
      weakly(g.widthAnchor.constraint(equalToConstant: 0))
    ])
  }

  /// Row `i`'s guide, below row `i - 1` (or the padding), wanting to be zero tall.
  private func rowGuide(_ i: Int) -> (UILayoutGuide, [NSLayoutConstraint]) {
    let g = UILayoutGuide()
    addLayoutGuide(g)
    return (g, [
      i == 0
        ? g.topAnchor.constraint(equalTo: topAnchor, constant: insets.top)
        : g.topAnchor.constraint(equalTo: rows[i - 1].bottomAnchor, constant: spacing),
      weakly(g.heightAnchor.constraint(equalToConstant: 0))
    ])
  }

  // The grid hugs its columns, but a parent that stretches it — a `.fill` stack does — gets
  // the extra as space after the last column, as GTK leaves it, rather than in a column.
  private func hug(_ edge: NSLayoutConstraint, _ floor: NSLayoutConstraint) -> [NSLayoutConstraint] {
    edge.priority = .defaultLow
    return [floor, edge]
  }

  private func hugTrailing() -> [NSLayoutConstraint] {
    let last = cols[cols.count - 1]
    return hug(
      trailingAnchor.constraint(equalTo: last.trailingAnchor, constant: trail),
      trailingAnchor.constraint(greaterThanOrEqualTo: last.trailingAnchor, constant: trail)
    )
  }

  private func hugBottom() -> [NSLayoutConstraint] {
    let last = rows[rows.count - 1]
    return hug(
      bottomAnchor.constraint(equalTo: last.bottomAnchor, constant: insets.bottom),
      bottomAnchor.constraint(greaterThanOrEqualTo: last.bottomAnchor, constant: insets.bottom)
    )
  }

  // Leading and top, inside the guides: a guide is pushed out to its largest cell, and a
  // smaller cell keeps its own size rather than being stretched.
  private func place(_ c: UIView, at k: Int) -> [NSLayoutConstraint] {
    let col = cols[k % columns]
    let row = rows[k / columns]
    return [
      c.leadingAnchor.constraint(equalTo: col.leadingAnchor),
      c.trailingAnchor.constraint(lessThanOrEqualTo: col.trailingAnchor),
      c.topAnchor.constraint(equalTo: row.topAnchor),
      c.bottomAnchor.constraint(lessThanOrEqualTo: row.bottomAnchor)
    ]
  }

  /// The last of `cells`, constrained as [[relayout]] would constrain it. A new column only
  /// while the first row is filling, a new row when the last one is full; then the grid's
  /// edge moves out to it. Nothing else is touched.
  private func append(_ c: UIView) {
    let k = cells.count - 1
    var cs: [NSLayoutConstraint] = []
    if k % columns == cols.count {
      let (g, gc) = columnGuide(cols.count)
      cols.append(g)
      cs += gc
      NSLayoutConstraint.deactivate(trailingHug)
      trailingHug = hugTrailing()
      cs += trailingHug
    }
    if k / columns == rows.count {
      let (g, gc) = rowGuide(rows.count)
      rows.append(g)
      cs += gc
      NSLayoutConstraint.deactivate(bottomHug)
      bottomHug = hugBottom()
      cs += bottomHug
    }
    cs += place(c, at: k)
    laid += cs
    NSLayoutConstraint.activate(cs)
  }

  private func takeDown() {
    NSLayoutConstraint.deactivate(laid)
    (cols + rows).forEach(removeLayoutGuide)
    laid = []
    cols = []
    rows = []
    trailingHug = []
    bottomHug = []
  }

  private func relayout() {
    stale = false
    takeDown()
    guard !cells.isEmpty else {
      laid = [
        widthAnchor.constraint(equalToConstant: lead + trail),
        heightAnchor.constraint(equalToConstant: insets.top + insets.bottom)
      ]
      NSLayoutConstraint.activate(laid)
      return
    }
    // As many columns as there are cells to fill them, up to `columns`, as on GTK.
    let width = min(columns, cells.count)
    let height = (cells.count + columns - 1) / columns
    var cs: [NSLayoutConstraint] = []
    for j in 0..<width {
      let (g, gc) = columnGuide(j)
      cols.append(g)
      cs += gc
    }
    for i in 0..<height {
      let (g, gc) = rowGuide(i)
      rows.append(g)
      cs += gc
    }
    trailingHug = hugTrailing()
    bottomHug = hugBottom()
    cs += trailingHug + bottomHug
    for (k, c) in cells.enumerated() { cs += place(c, at: k) }
    laid = cs
    NSLayoutConstraint.activate(laid)
  }
}

private func gridOf(_ v: UIView) -> GridView? { v.superview as? GridView }

/// Kind 25, one `Tab`: a plain view holding its one child. Inserting a second child replaces
/// the first, as on GTK and Android. The title and the symbol are kept here and put on the
/// page controller's `tabBarItem` while the page is in a TabView, which is where UIKit shows them.
private final class TabPage: UIView {
  var title = "" { didSet { controller?.tabBarItem.title = title } }
  var symbol: String?
  var image: UIImage? { didSet { controller?.tabBarItem.image = image } }
  /// The controller the tab bar controller holds for this page while it is in a TabView. Weak:
  /// it holds this view, and the tab bar controller holds it.
  weak var controller: UIViewController?
  weak var host: TabHostView?

  init() { super.init(frame: .zero) }
  required init?(coder: NSCoder) { fatalError("not used") }

  var content: UIView? { subviews.first }

  func setContent(_ c: UIView) {
    subviews.forEach { $0.removeFromSuperview() }
    c.translatesAutoresizingMaskIntoConstraints = false
    addSubview(c)
    // The tab bar controller lays its page out under the bar and says so through the page's
    // safe area, so the bottom stops there. Top-aligned, as GTK's homogeneous stack leaves a
    // short page.
    NSLayoutConstraint.activate([
      c.leadingAnchor.constraint(equalTo: leadingAnchor),
      c.trailingAnchor.constraint(equalTo: trailingAnchor),
      c.topAnchor.constraint(equalTo: topAnchor),
      c.bottomAnchor.constraint(lessThanOrEqualTo: safeAreaLayoutGuide.bottomAnchor)
    ])
    host?.refit()
  }
}

/// Kind 24, a `TabView`: a `UITabBarController` whose view this view holds.
///
/// A TabView here is a view in a tree, not the window's root controller, so the tab bar
/// controller is made a child of whichever controller hosts this view when it reaches a
/// window, and leaves it when it leaves the window — view controller containment, which is
/// what keeps its appearance callbacks and trait changes flowing.
///
/// The order is the controller's own `viewControllers`, so every question about tabs is
/// answered by UIKit. The rules for keeping the user on their tab are the renderer's
/// (`TabSelection`); this only inserts, removes and selects, and reports the user's choices,
/// which `tabBarController(_:didSelect:)` receives and a programmatic selection never does.
private final class TabHostView: UIView, UITabBarControllerDelegate {
  let controller = UITabBarController()
  private var programmatic = 0
  private var fitHeight: NSLayoutConstraint!
  private var fitWidth: NSLayoutConstraint!

  init() {
    super.init(frame: .zero)
    controller.delegate = self
    if #available(iOS 26.0, *) { controller.tabBarMinimizeBehavior = .never }
    let v = controller.view!
    v.translatesAutoresizingMaskIntoConstraints = false
    addSubview(v)
    fitHeight = heightAnchor.constraint(greaterThanOrEqualToConstant: 0)
    fitWidth = widthAnchor.constraint(greaterThanOrEqualToConstant: 0)
    NSLayoutConstraint.activate([
      v.leadingAnchor.constraint(equalTo: leadingAnchor),
      v.trailingAnchor.constraint(equalTo: trailingAnchor),
      v.topAnchor.constraint(equalTo: topAnchor),
      v.bottomAnchor.constraint(equalTo: bottomAnchor),
      fitHeight, fitWidth
    ])
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil {
      if controller.parent != nil {
        controller.willMove(toParent: nil)
        controller.removeFromParent()
      }
      return
    }
    guard controller.parent == nil else { return }
    var responder: UIResponder? = next
    while let r = responder {
      if let vc = r as? UIViewController {
        vc.addChild(controller)
        controller.didMove(toParent: vc)
        return
      }
      responder = r.next
    }
  }

  var pages: [TabPage] { (controller.viewControllers ?? []).compactMap { $0.viewIfLoaded as? TabPage } }

  /// Our own changes to the controller: none of them is the user's choice.
  private func quietly(_ f: () -> Void) {
    programmatic += 1
    f()
    programmatic -= 1
  }

  func insert(_ p: TabPage, after: UIView?) {
    quietly {
      // A move arrives as an insert of a page that is already here.
      var vcs = (controller.viewControllers ?? []).filter { $0.viewIfLoaded !== p }
      let at: Int
      if let a = after {
        at = vcs.firstIndex { $0.viewIfLoaded === a }.map { $0 + 1 } ?? vcs.count
      } else {
        at = 0
      }
      let vc = p.controller ?? UIViewController()
      vc.view = p
      vc.tabBarItem = UITabBarItem(title: p.title, image: p.image, tag: 0)
      p.controller = vc
      p.host = self
      vcs.insert(vc, at: at)
      controller.setViewControllers(vcs, animated: false)
    }
    refit()
  }

  func remove(_ p: TabPage) {
    quietly {
      let vcs = (controller.viewControllers ?? []).filter { $0.viewIfLoaded !== p }
      controller.setViewControllers(vcs, animated: false)
    }
    p.host = nil
    refit()
  }

  var selected: Int {
    let n = controller.viewControllers?.count ?? 0
    let i = controller.selectedIndex
    return n > 0 && i >= 0 && i < n ? i : -1
  }

  /// -1 or past the end leaves the tab shown, as on GTK and Android.
  func select(_ i: Int) {
    guard i >= 0, i < (controller.viewControllers?.count ?? 0), i != selected else { return }
    quietly { controller.selectedIndex = i }
  }

  func tabBarController(_ tabBarController: UITabBarController, didSelect viewController: UIViewController) {
    guard programmatic == 0, selected >= 0, let s = selections[ObjectIdentifier(self)] else { return }
    s.cb(s.ctx, Int32(selected))
  }

  /// The controller lays its page out by frame, so nothing in a page's content reaches this
  /// view's size. It is given one: the bar, and the tallest page's content — homogeneous, as
  /// GTK's stack is, so switching tabs does not move the views below.
  func refit() {
    let contents = pages.compactMap(\.content)
    let h = contents.map { $0.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize).height }.max() ?? 0
    let w = contents.map { $0.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize).width }.max() ?? 0
    let barWidth = bounds.width > 0 ? bounds.width : max(w, 320)
    let bar = controller.tabBar.frame.height > 0
      ? controller.tabBar.frame.height
      : controller.tabBar.sizeThatFits(CGSize(width: barWidth, height: 0)).height
    if abs(fitHeight.constant - (h + bar)) > 0.5 { fitHeight.constant = h + bar }
    if abs(fitWidth.constant - w) > 0.5 { fitWidth.constant = w }
  }

  /// The bar's height is known for certain only once it is laid out, and changes with the
  /// safe area; settle on it here. `refit` writes only a changed constant, so this settles.
  override func layoutSubviews() {
    super.layoutSubviews()
    let before = fitHeight.constant
    refit()
    if abs(fitHeight.constant - before) > 0.5 { setNeedsLayout() }
  }
}


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

@_cdecl("sui_set_root_view")
public func sui_set_root_view(_ h: UnsafeMutableRawPointer) {
  // The host calls this before any Scala runs, so every later exception is logged.
  logObjCExceptions()
  rootView = view(h)
  hostController().map(noExtendedLayout)
}

/// A screen starts below its navigation bar, as an Android screen starts below its action bar:
/// the bar is the app's own chrome, which `Screen.title` put there, not the system's. So a
/// page's view controller does not extend under it, and what `SafeArea` keeps content clear
/// of is the system's — the status bar when there is no bar, the home indicator, a notch (#76).
private func noExtendedLayout(_ vc: UIViewController) {
  vc.edgesForExtendedLayout = []
  // Behind a bar the content no longer runs under is the navigation controller's own view,
  // which has no colour: a transparent bar over it showed black, with a black title.
  vc.navigationController?.view.backgroundColor = .systemBackground
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

/// The lookup `sui_set_image_file` makes, for an app that wants the bytes of its own file: an
/// iOS app's working directory is `/`, so a repo-relative path reads nothing there (#73).
@_cdecl("sui_resource_path")
public func sui_resource_path(_ path: UnsafePointer<CChar>) -> UnsafePointer<CChar>? {
  scratch(resolveImagePath(String(cString: path)))
}

// MARK: - construction

@_cdecl("sui_create")
public func sui_create(_ kind: Int32) -> UnsafeMutableRawPointer {
  switch kind {
  case 0, 1:
    let s = StackView()
    s.axis = (kind == 0) ? .vertical : .horizontal
    s.alignment = (kind == 0) ? .fill : .center
    s.distribution = .fill
    s.spacing = 0
    // A container must be tappable: UIStackView has no action, so taps arrive through a
    // gesture recogniser — the same shape GTK needs a GtkGestureClick on a GtkBox.
    let g = TapRecognizer(target: proxy, action: #selector(Proxy.containerTapped(_:)))
    g.delegate = proxy
    s.addGestureRecognizer(g)
    s.isUserInteractionEnabled = true
    return retained(s)

  case 2:
    let l = TextLabel()
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
    // UIKit has no checkbox; the platform's boolean control is a switch, captioned.
    let c = CheckboxView()
    c.toggle.addTarget(proxy, action: #selector(Proxy.switched(_:)), for: .valueChanged)
    return retained(c)

  case 6:
    let s = ScrollView()
    s.alwaysBounceVertical = true
    return retained(s)

  case 15:
    // The same class as 6, scrolling the other way.
    let s = ScrollView()
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

  case 18:
    // A menu-backed button, not a UIPickerView: a picker wheel is for long or continuous
    // ranges, and for a short list the platform idiom since iOS 14 is a button that shows
    // the current choice and opens a menu. `changesSelectionAsPrimaryAction` is what makes
    // it behave as a selector rather than a command.
    // `changesSelectionAsPrimaryAction` starts off: UIKit raises
    // NSInternalInconsistencyException ("Menu does not have a valid element for default
    // selection") when it is on and the menu is empty, and the options arrive afterwards,
    // clear-then-add. `rebuildPickerMenu` turns it on once there is something to select.
    let b = UIButton(type: .system)
    b.showsMenuAsPrimaryAction = true
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
    // A plain button showing only its image, as in a toolbar or a navigation bar. The symbol
    // is sized by the body text style, so it follows Dynamic Type like the text beside it.
    var c = UIButton.Configuration.plain()
    c.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(textStyle: .body, scale: .large)
    let b = UIButton(configuration: c)
    b.addTarget(proxy, action: #selector(Proxy.tapped(_:)), for: .touchUpInside)
    return retained(b)

  case 20:
    let s = UISegmentedControl()
    s.addTarget(proxy, action: #selector(Proxy.segmented(_:)), for: .valueChanged)
    return retained(s)

  case 21:
    // .compact: a button showing the date that opens a calendar, UIKit's form idiom since
    // iOS 14. Not .inline (a whole calendar) and not .wheels.
    let p = UIDatePicker()
    p.datePickerMode = .date
    p.preferredDatePickerStyle = .compact
    p.timeZone = TimeZone(identifier: "UTC")
    p.addTarget(proxy, action: #selector(Proxy.dated(_:)), for: .valueChanged)
    return retained(p)

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
  if let box = v as? CheckboxView {
    toggles.removeValue(forKey: ObjectIdentifier(box.toggle))
    suppressed.remove(ObjectIdentifier(box.toggle))
  }
  taps.removeValue(forKey: id)
  dateChanges.removeValue(forKey: id)
  links.removeValue(forKey: id)
  symbols.removeValue(forKey: id)
  insetStates.removeValue(forKey: id)
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
  selections.removeValue(forKey: id)
  // A page leaves its TabView's controller, which would otherwise keep it alive and shown.
  if let page = v as? TabPage { page.host?.remove(page) }
  // Detaching is part of destroying, not a separate step the caller performs first.
  if let stack = v.superview as? UIStackView { stack.removeArrangedSubview(v) }
  gridOf(v)?.remove(v)
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
  case let c as CheckboxView:
    c.label.text = s
    // VoiceOver reads the switch as "<caption>, switch button, on", as it would a labelled one.
    c.toggle.accessibilityLabel = s
  case let page as TabPage:
    page.title = s
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
  guard let sw = switchOf(view(h)) else { return }
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
  (switchOf(view(h))?.isOn ?? false) ? 1 : 0
}

@_cdecl("sui_set_enabled")
public func sui_set_enabled(_ h: UnsafeMutableRawPointer, _ on: Int32) {
  if let c = view(h) as? CheckboxView {
    c.toggle.isEnabled = on != 0
    c.label.isEnabled = on != 0
  } else {
    (view(h) as? UIControl)?.isEnabled = on != 0
  }
}

@_cdecl("sui_set_spacing")
public func sui_set_spacing(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  switch view(h) {
  case let s as UIStackView: s.spacing = CGFloat(dp)
  case let g as GridView: g.spacing = CGFloat(dp)
  default: break
  }
}

@_cdecl("sui_set_padding")
public func sui_set_padding(_ h: UnsafeMutableRawPointer, _ dp: Int32) {
  // A stack's and a grid's padding is around their children; a scroller's insets its content,
  // which scrolls with it, as on GTK and Android (#76: it used to be dropped here).
  let v = view(h)
  guard v is UIStackView || v is GridView || v is ScrollView else { return }
  insetStates[ObjectIdentifier(v), default: InsetState()].padding = CGFloat(dp)
  refreshInsets(v)
}

@_cdecl("sui_set_grid_columns")
public func sui_set_grid_columns(_ h: UnsafeMutableRawPointer, _ count: Int32) {
  (view(h) as? GridView)?.columns = max(1, Int(count))
}

/// An SF Symbol. A name the SDK lacks leaves the view with no image and is not recorded,
/// which the self-test reports: `UIImage(systemName:)` returns nil and would draw nothing.
@_cdecl("sui_set_symbol")
public func sui_set_symbol(_ h: UnsafeMutableRawPointer, _ name: UnsafePointer<CChar>) {
  let n = String(cString: name)
  let v = view(h)
  let image = UIImage(systemName: n)
  symbols[ObjectIdentifier(v)] = image == nil ? nil : n
  switch v {
  case let b as UIButton:
    var c = b.configuration ?? .plain()
    c.image = image
    b.configuration = c
  case let i as UIImageView: i.image = image
  case let page as TabPage:
    page.symbol = image == nil ? nil : n
    page.image = image
  default: break
  }
}

/// What VoiceOver announces, and the pointer tooltip, which iPadOS shows with a trackpad or
/// mouse — the same label GNOME, Android and macOS show as theirs.
@_cdecl("sui_set_accessible_label")
public func sui_set_accessible_label(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  let s = String(cString: text)
  let v = view(h)
  v.accessibilityLabel = s
  for old in v.interactions where old is UIToolTipInteraction { v.removeInteraction(old) }
  v.addInteraction(UIToolTipInteraction(defaultToolTip: s))
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
  // Below every plain view's 250, so a grown child is the one a .fill stack stretches (#87). At
  // .defaultLow it tied with a label's own 250. The same 248 as AppKit, where a stack's own
  // slack sits between the two.
  let priority: UILayoutPriority = on != 0 ? UILayoutPriority(248) : .defaultHigh
  v.setContentHuggingPriority(priority, for: .horizontal)
  v.setContentHuggingPriority(priority, for: .vertical)
  // A grown label's half-width cap is let go by its Row (`StackView`) when it next rebuilds.
  v.superview?.setNeedsUpdateConstraints()
}

@_cdecl("sui_set_align")
public func sui_set_align(_ h: UnsafeMutableRawPointer, _ align: Int32) {
  switch view(h) {
  case let l as TextLabel:
    l.logicalAlign = align
    l.textAlignment = l.thicketAlignment
  case let l as UILabel: l.textAlignment = resolvedAlignment(align, l)
  case let f as UITextField: f.textAlignment = resolvedAlignment(align, f)
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
  // Nothing to restyle: a `.system` UIButton with no fill — which is what a Link is, since
  // it never sends one — is already UIKit's link idiom, tinted text that opens something.
}

@_cdecl("sui_on_text_change")
public func sui_on_text_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_text_cb, _ ctx: Int64) {
  edits[ObjectIdentifier(view(h))] = TextEdit(cb: cb, ctx: ctx)
}

@_cdecl("sui_on_checked_change")
public func sui_on_checked_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_bool_cb, _ ctx: Int64) {
  // Keyed by the switch, which is what sends the event, even when the handle is a Checkbox.
  let v = view(h)
  toggles[ObjectIdentifier(switchOf(v) ?? v)] = Toggle(cb: cb, ctx: ctx)
}

@_cdecl("sui_on_value_change")
public func sui_on_value_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_value_cb, _ ctx: Int64) {
  valueChanges[ObjectIdentifier(view(h))] = ValueChange(cb: cb, ctx: ctx)
}

// MARK: - picker

/// Rebuild the menu from the stored titles, marking the selected one.
///
/// A `UIMenu` is immutable, so there is no "add one item" — every change replaces it. That
/// is why the titles are kept in `pickerOptions` rather than read back off the control.
private let blankPickerElement = UIAction.Identifier("thicket.picker.blank")

/// The picker's own options in its installed menu: the blank lead (D13) is not one of them.
private func pickerElements(_ b: UIButton) -> [UIMenuElement] {
  (b.menu?.children ?? []).filter { ($0 as? UIAction)?.identifier != blankPickerElement }
}

private func rebuildPickerMenu(_ b: UIButton) {
  let id = ObjectIdentifier(b)
  let titles = pickerOptions[id] ?? []
  let chosen = pickerSelected[id] ?? -1
  // D13 (#77): with nothing chosen the button shows a blank. A selecting menu always has one
  // element on, and with none on UIKit turns the first one on and shows it — the picker read
  // "Apple" after Clear. So while nothing is chosen the menu leads with a blank element, as
  // Android's list leads with a blank entry; the read-backs skip it, and it goes again once
  // the user chooses something.
  let blank = !titles.isEmpty && (chosen < 0 || chosen >= titles.count)
  let actions = titles.enumerated().map { (i, title) in
    UIAction(title: title, state: i == chosen ? .on : .off) { [weak b] _ in
      pickerSelected[id] = i
      if blank, let b { rebuildPickerMenu(b) }
      guard !suppressed.contains(id), let s = selections[id] else { return }
      s.cb(s.ctx, Int32(i))
    }
  }
  let lead: [UIAction] = blank ? [UIAction(title: "", identifier: blankPickerElement, state: .on) { _ in }] : []
  // An Objective-C exception cannot be caught on the Scala side — it unwinds into Scala
  // frames and Scala Native's catch handler fails on it — so the empty menu UIKit rejects is
  // never handed to it: with no options the button is a plain menu button, and the selector
  // behaviour goes on only once there is an element to select, menu first.
  if actions.isEmpty {
    b.changesSelectionAsPrimaryAction = false
    b.menu = nil
  } else {
    b.menu = UIMenu(children: lead + actions)
    b.changesSelectionAsPrimaryAction = true
  }
}

@_cdecl("sui_picker_clear_options")
public func sui_picker_clear_options(_ h: UnsafeMutableRawPointer) {
  if let seg = view(h) as? UISegmentedControl { seg.removeAllSegments(); return }
  guard let b = view(h) as? UIButton else { return }
  pickerOptions[ObjectIdentifier(b)] = []
  rebuildPickerMenu(b)
}

@_cdecl("sui_picker_add_option")
public func sui_picker_add_option(_ h: UnsafeMutableRawPointer, _ label: UnsafePointer<CChar>?) {
  if let seg = view(h) as? UISegmentedControl {
    seg.insertSegment(withTitle: label.map { String(cString: $0) } ?? "", at: seg.numberOfSegments, animated: false)
    return
  }
  guard let b = view(h) as? UIButton else { return }
  pickerOptions[ObjectIdentifier(b), default: []].append(str(label))
  rebuildPickerMenu(b)
}

@_cdecl("sui_set_selected")
public func sui_set_selected(_ h: UnsafeMutableRawPointer, _ index: Int32) {
  if let t = view(h) as? TabHostView { t.select(Int(index)); return }
  if let seg = view(h) as? UISegmentedControl {
    // UIKit sends no .valueChanged for a programmatic change, so no suppression is needed.
    seg.selectedSegmentIndex =
      (index >= 0 && Int(index) < seg.numberOfSegments) ? Int(index) : UISegmentedControl.noSegment
    return
  }
  guard let b = view(h) as? UIButton else { return }
  let id = ObjectIdentifier(b)
  guard (pickerSelected[id] ?? -1) != Int(index) else { return }
  suppressed.insert(id)
  pickerSelected[id] = Int(index)
  rebuildPickerMenu(b)
  suppressed.remove(id)
}

@_cdecl("sui_get_selected")
public func sui_get_selected(_ h: UnsafeMutableRawPointer) -> Int32 {
  if let t = view(h) as? TabHostView { return Int32(t.selected) }
  if let seg = view(h) as? UISegmentedControl { return Int32(seg.selectedSegmentIndex) }
  guard let b = view(h) as? UIButton else { return -1 }
  return Int32(pickerSelected[ObjectIdentifier(b)] ?? -1)
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
  guard let p = view(h) as? UIDatePicker else { return }
  let id = ObjectIdentifier(p)
  // An app's own write is not a choice. UIKit sends no action for it anyway; AppKit may.
  suppressed.insert(id)
  p.date = dateOf(epochDayValue)
  suppressed.remove(id)
}

@_cdecl("sui_get_date")
public func sui_get_date(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let p = view(h) as? UIDatePicker else { return Int32.min }
  return epochDay(p.date)
}

@_cdecl("sui_on_date_change")
public func sui_on_date_change(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_int_cb, _ ctx: Int64) {
  dateChanges[ObjectIdentifier(view(h))] = Selection(cb: cb, ctx: ctx)
}

@_cdecl("sui_set_safe_area")
public func sui_set_safe_area(_ h: UnsafeMutableRawPointer, _ edges: Int32) {
  // The insets on the chosen edges are *added* to the view's padding by `refreshInsets`, once
  // the view is in a window and again whenever UIKit says they changed. They used to be read
  // here, at mount, from a view in no window — always zero — and written over the padding
  // into the same layout margins; and the root was pinned to the safe area whatever this
  // said, so an edge not chosen could not run under the chrome (#76, review M8).
  insetStates[ObjectIdentifier(view(h)), default: InsetState()].edges = edges
  refreshInsets(view(h))
}

/// Test only: extra safe area on the host view controller, as a toolbar would add, so a
/// self-test can watch the insets reach a view and change.
@_cdecl("sui_set_extra_safe_area")
public func sui_set_extra_safe_area(_ top: Double, _ left: Double, _ bottom: Double, _ right: Double) {
  hostController()?.additionalSafeAreaInsets = UIEdgeInsets(
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

  if let scroll = p as? UIScrollView {
    // A scroll view holds exactly one child here, so "insert" is "set".
    scroll.subviews.forEach { $0.removeFromSuperview() }
    c.translatesAutoresizingMaskIntoConstraints = false
    scroll.addSubview(c)
    // All four edges go to the contentLayoutGuide, which is what gives the scroll view its
    // contentSize. The cross axis is tied to the scroller's frame: that is the direction the
    // content may not exceed, and so the direction it does not scroll. The scroller's padding
    // is its `contentInset` (#76), outside the content size, so the tie is short by the
    // insets on that axis.
    NSLayoutConstraint.activate([
      c.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
      c.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
      c.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
      c.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor)
    ])
    // Horizontal: a UIScrollView has no intrinsic size, so in a vertical UIStackView it would
    // stretch to whatever slack there is rather than hug the row. Its height is the content's
    // through one constraint tied to the content, so it follows the content when that changes;
    // it used to be a constant read here and added afresh per child, which stacked when the
    // child was replaced and never followed a taller label (#76, review L4). (The width needs
    // no help: a vertical UIStackView is .fill-aligned here, so it stretches its children.)
    let tie = isHorizontalScroller(scroll)
      ? scroll.heightAnchor.constraint(equalTo: c.heightAnchor)
      : c.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor)
    let mine = scroll as? ScrollView
    mine?.tie?.isActive = false
    tie.isActive = true
    mine?.tie = tie
    mine?.retie()
    return
  }

  guard let stack = p as? UIStackView else {
    // The root view controller's view: pin the child, or it keeps
    // translatesAutoresizingMaskIntoConstraints = true, Auto Layout never sizes it, and
    // the whole tree measures zero — the defect the macOS self-test caught.
    //
    // To the view's edges, not its safe area: keeping clear of the system's chrome is what
    // `SafeArea` asks for, per edge, and pinning every root to the safe area made an edge the
    // app did not choose impossible (#76, review M8). The navigation bar is not the system's:
    // see `noExtendedLayout`.
    c.translatesAutoresizingMaskIntoConstraints = false
    p.addSubview(c)
    NSLayoutConstraint.activate([
      c.leadingAnchor.constraint(equalTo: p.leadingAnchor),
      c.trailingAnchor.constraint(equalTo: p.trailingAnchor),
      c.topAnchor.constraint(equalTo: p.topAnchor),
      c.bottomAnchor.constraint(equalTo: p.bottomAnchor)
    ])
    p.layoutIfNeeded()
    return
  }

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

  // #87: a Column or Row inside a Row is as tall as the Row, as GTK's boxes fill their cross
  // axis. The Row is .center aligned, so a control beside a taller one sits in the middle
  // rather than stretching; but a shorter Column was centred too, and a Spacer in it had no
  // room to push "bottom" down to line up with its taller neighbour.
  //
  // At 999, not required: the Row's own required constraints keep a child inside its margins,
  // so it stops there — padding and safe area included, whenever they change.
  if stack.axis == .horizontal, c is UIStackView, !stack.constraints.contains(where: {
    $0.identifier == rowFillId && $0.firstItem === c
  }) {
    let fill = c.heightAnchor.constraint(equalTo: stack.heightAnchor)
    fill.priority = UILayoutPriority(999)
    fill.identifier = rowFillId
    fill.isActive = true
  }
}

/// Identifies a Row's stretch constraint on a nested stack (#87), so a moved child does not get two.
private let rowFillId = "thicket.row-fill"

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
///
/// A row view leaves for good in two ways, and both are handed back to Scala as
/// `RowSource.discard` (index -1), which disposes its slot and owner (#78, review 2026-10-20
/// P2-8): the table frees the cell hosting it, or a re-bind returns a different view and
/// the old one is taken out of the cell. Before, nothing was handed back, so every row view a
/// freed cell had hosted kept its slot, its owner and its effects for as long as the list
/// was mounted.
private final class TableSource: NSObject, UITableViewDataSource, UITableViewDelegate {
  let cb: sui_row_cb
  let ctx: Int64
  var count: Int = 0
  /// Row views the table was handed that it did not already hold, and how many of those it
  /// has handed back. Counts, not a set of identities: a freed view's address is reused.
  var built = 0
  var discarded = 0

  init(cb: @escaping sui_row_cb, ctx: Int64) {
    self.cb = cb
    self.ctx = ctx
  }

  func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { count }

  func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
    let cell = (tableView.dequeueReusableCell(withIdentifier: rowId) as? RowCell)
      ?? RowCell(style: .default, reuseIdentifier: rowId)
    cell.source = self

    // Whatever this cell last hosted is the recycled view.
    let previous = cell.hosted
    let recycledPtr = previous.map { Unmanaged.passUnretained($0).toOpaque() }
    guard let produced = cb(ctx, Int32(indexPath.row), recycledPtr) else { return cell }
    let v = Unmanaged<UIView>.fromOpaque(produced).takeUnretainedValue()

    // Re-binding usually returns the same view, in which case re-pinning it would add a
    // duplicate set of constraints on every scroll.
    if v !== previous {
      built += 1
      if let old = previous {
        old.removeFromSuperview()
        discard(old)
      }
      cell.hosted = v
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

  /// Hands `v` back to Scala. The Scala side ignores a view it no longer holds, so a late or
  /// repeated discard is harmless; a view Scala has already destroyed is never passed,
  /// because destroying one takes it out of its cell first.
  func discard(_ v: UIView) {
    discarded += 1
    _ = cb(ctx, -1, Unmanaged.passUnretained(v).toOpaque())
  }
}

/// A table cell that says when the table has freed it. `UITableView` frees cells on its own
/// schedule — its reuse queue is purged on a memory warning, for one — and the row view the
/// cell hosted is then shown by nothing, but still held by Scala until it is discarded.
private final class RowCell: UITableViewCell {
  weak var source: TableSource?
  var hosted: UIView?

  deinit {
    // Not from inside the cell's deallocation: discarding destroys the view, which detaches
    // it from this cell's content view, mid-teardown. On the next turn instead, holding the
    // view until then. A source gone by then went with its table, whose rows were disposed
    // with it.
    guard let v = hosted, let s = source else { return }
    DispatchQueue.main.async { [weak s] in
      s?.discard(v)
    }
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
  t.scrollToRow(at: IndexPath(row: Int(row), section: 0), at: .top, animated: false)
  t.layoutIfNeeded()
}

/// The notification the system posts, which a `UITableView` answers by freeing the cells in
/// its reuse queue — and so the one way to have it free cells on demand.
@_cdecl("sui_post_memory_warning")
public func sui_post_memory_warning() -> Int32 {
  NotificationCenter.default.post(
    name: UIApplication.didReceiveMemoryWarningNotification, object: UIApplication.shared)
  return 1
}

@_cdecl("sui_table_live")
public func sui_table_live() -> Int32 {
  Int32(tableSources.count)
}

/// The view the cell for `row` hosts, only while that row is on screen: `cellForRow(at:)` is
/// nil for a row UITableView is not showing, and asking never makes it bind one.
@_cdecl("sui_table_row_view")
public func sui_table_row_view(_ h: UnsafeMutableRawPointer, _ row: Int32) -> UnsafeMutableRawPointer? {
  guard let (t, _) = table(of: h), row >= 0, Int(row) < t.numberOfRows(inSection: 0) else { return nil }
  t.layoutIfNeeded()
  guard let v = t.cellForRow(at: IndexPath(row: Int(row), section: 0))?.contentView.subviews.first
  else { return nil }
  return Unmanaged.passUnretained(v).toOpaque()
}

@_cdecl("sui_remove_child")
public func sui_remove_child(_ parent: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer) {
  let c = view(child)
  if let t = view(parent) as? TabHostView, let page = c as? TabPage {
    t.remove(page)
    return
  }
  if let stack = view(parent) as? UIStackView { stack.removeArrangedSubview(c) }
  (view(parent) as? GridView)?.remove(c)
  c.removeFromSuperview()
  (view(parent) as? TabPage)?.host?.refit()
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
  if let g = v as? GridView { return g.cells }
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

/// Owned by the shim and valid only until the next call, so Scala copies immediately.
private var textScratch = [CChar](repeating: 0, count: 4096)

@_cdecl("sui_get_text")
public func sui_get_text(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  let s: String?
  switch view(h) {
  case let c as CheckboxView: s = c.label.text
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
  return (v is UILabel || v is UITextField || v is UIButton || v is CheckboxView) ? 1 : 0
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
  if let box = view(h) as? CheckboxView {
    // What the switch sends when a finger flips it.
    guard box.toggle.isEnabled else { return 0 }
    box.toggle.setOn(!box.toggle.isOn, animated: false)
    box.toggle.sendActions(for: .valueChanged)
    return 1
  }
  guard let b = view(h) as? UIButton else { return 0 }
  b.sendActions(for: .touchUpInside)
  return 1
}

/// UIKit has no public way to synthesise a touch, so this asks UIKit everything short of
/// one: that the view is scrolled on screen, that a touch at its centre would hit-test to it
/// (a UILabel with user interaction off would not), that it carries an enabled tap
/// recogniser, and that the recogniser agrees to begin. Only then is the recogniser's action
/// run, with the recogniser itself, as UIKit runs it on recognising.
@_cdecl("sui_perform_tap")
public func sui_perform_tap(_ h: UnsafeMutableRawPointer) -> Int32 {
  let v = view(h)
  if v is UIButton { return sui_perform_click(h) }
  guard let w = v.window,
    let g = v.gestureRecognizers?.first(where: { $0 is TapRecognizer && $0.isEnabled }) as? TapRecognizer
  else { return 0 }
  var up = v.superview
  while let s = up {
    if let scroll = s as? UIScrollView {
      scroll.scrollRectToVisible(v.convert(v.bounds, to: scroll), animated: false)
    }
    up = s.superview
  }
  w.layoutIfNeeded()
  let centre = v.convert(CGPoint(x: v.bounds.midX, y: v.bounds.midY), to: w)
  guard let hit = w.hitTest(centre, with: nil), hit === v || hit.isDescendant(of: v) else { return 0 }
  guard proxy.gestureRecognizerShouldBegin(g) else { return 0 }
  proxy.containerTapped(g)
  return 1
}

/// Forces a view's layout direction, as a right-to-left locale sets it for the whole app, so
/// a test can measure what the view does under it. 1 right-to-left, 0 back to inherited.
@_cdecl("sui_set_layout_direction")
public func sui_set_layout_direction(_ h: UnsafeMutableRawPointer, _ rtl: Int32) {
  let v = view(h)
  v.semanticContentAttribute = rtl != 0 ? .forceRightToLeft : .unspecified
  v.setNeedsLayout()
  v.layoutIfNeeded()
  v.setNeedsDisplay()
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
      noExtendedLayout(vc)
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

@_cdecl("sui_get_content_fit")
public func sui_get_content_fit(_ h: UnsafeMutableRawPointer) -> Int32 {
  guard let iv = view(h) as? UIImageView else { return -1 }
  switch iv.contentMode {
  case .scaleAspectFill: return 1
  case .scaleToFill: return 2
  default: return 0
  }
}

@_cdecl("sui_get_align")
public func sui_get_align(_ h: UnsafeMutableRawPointer) -> Int32 {
  let a: NSTextAlignment
  switch view(h) {
  case let l as UILabel: a = l.textAlignment
  case let f as UITextField: a = f.textAlignment
  default: return -1
  }
  switch a {
  case .center: return 1
  case .right: return 2
  default: return 0
  }
}

/// UIKit scroll views are top-origin; the adjusted inset is where "the top" rests.
@_cdecl("sui_scroll_offset")
public func sui_scroll_offset(_ h: UnsafeMutableRawPointer) -> Double {
  guard let scroll = view(h) as? UIScrollView else { return -1 }
  return Double(scroll.contentOffset.y + scroll.adjustedContentInset.top)
}

// MARK: - read-back for the new controls (#45, #46)

@_cdecl("sui_option_count")
public func sui_option_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  switch view(h) {
  case let s as UISegmentedControl: return Int32(s.numberOfSegments)
  case let b as UIButton: return Int32(pickerElements(b).count)
  case let t as TabHostView: return Int32(t.controller.tabBar.items?.count ?? 0)
  default: return 0
  }
}

/// Read from the control: the segments, or the button's installed menu, less the blank lead.
@_cdecl("sui_option_label")
public func sui_option_label(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  let i = Int(index)
  switch view(h) {
  case let s as UISegmentedControl where i >= 0 && i < s.numberOfSegments:
    return scratch(s.titleForSegment(at: i) ?? "")
  case let b as UIButton:
    let items = pickerElements(b)
    guard i >= 0, i < items.count else { return nil }
    return scratch(items[i].title)
  case let t as TabHostView:
    guard let items = t.controller.tabBar.items, i >= 0, i < items.count else { return nil }
    return scratch(items[i].title ?? "")
  default:
    return nil
  }
}

/// What the control draws as the choice (#77, D13): the menu button's title, which UIKit
/// sets from the selected element, and not `pickerSelected`, which is what the shim was told.
/// With no element on, UIKit turns the first one on itself and shows it, which is what this
/// caught.
@_cdecl("sui_shown_option")
public func sui_shown_option(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  switch view(h) {
  case let s as UISegmentedControl:
    let i = s.selectedSegmentIndex
    return scratch(i >= 0 && i < s.numberOfSegments ? (s.titleForSegment(at: i) ?? "") : "")
  case let b as UIButton:
    // `currentTitle`, not `titleLabel.text`: the label is redrawn on a later pass, and read
    // straight after a change it still held the previous choice (measured, #77).
    return scratch(b.currentTitle ?? "")
  default:
    return nil
  }
}

/// A segmented control is chosen by selecting and sending `.valueChanged`, which is what a
/// tap does. UIKit offers no public way to perform a `UIAction`, so the menu-backed Picker
/// says it cannot rather than calling the stored callback and passing without UIKit.
@_cdecl("sui_choose")
public func sui_choose(_ h: UnsafeMutableRawPointer, _ index: Int32) -> Int32 {
  if let t = view(h) as? TabHostView { return chooseTab(t, Int(index)) }
  guard let s = view(h) as? UISegmentedControl, index >= 0, Int(index) < s.numberOfSegments else { return 0 }
  s.selectedSegmentIndex = Int(index)
  s.sendActions(for: .valueChanged)
  return 1
}

/// A tab is chosen by tapping the bar's own button for it: the controls the `UITabBar` lays out
/// for its items, left to right, sent `.touchUpInside` as a tap does. The tab bar controller
/// then selects and tells its delegate, as for a finger.
///
/// iOS 26's floating bar draws each item twice — once in the platter, once in the "selected"
/// lens laid over it — so there are two buttons at each position (measured: six `_UITabButton`s
/// for three items on the iOS 27 simulator). They are grouped by position, and the first of a
/// position that actually selects the tab is the tap. 0 when the bar's buttons cannot be
/// matched one position per item, or none of them selects: never a selection made behind the
/// bar's back.
private func chooseTab(_ t: TabHostView, _ i: Int) -> Int32 {
  let bar = t.controller.tabBar
  bar.layoutIfNeeded()
  guard let items = bar.items, i >= 0, i < items.count else { return 0 }
  var controls: [UIControl] = []
  func walk(_ v: UIView) {
    for c in v.subviews {
      if let ctl = c as? UIControl, ctl.bounds.width > 0, !ctl.isHidden { controls.append(ctl) } else { walk(c) }
    }
  }
  walk(bar)
  let x = { (c: UIControl) in (c.convert(c.bounds, to: bar).midX).rounded() }
  let positions = Array(Set(controls.map(x))).sorted()
  guard positions.count == items.count else {
    let found = controls.map { "\(NSStringFromClass(type(of: $0))) at \(x($0))" }
    FileHandle.standardError.write("thicket: tab bar has \(items.count) items but buttons \(found)\n".data(using: .utf8)!)
    return 0
  }
  for c in controls where x(c) == positions[i] {
    c.sendActions(for: .touchUpInside)
    if t.selected == i { return 1 }
  }
  return 0
}

/// The symbol the bar shows for item `index`: recorded only when UIKit resolved the name, and
/// only while the bar's item holds that very image.
@_cdecl("sui_option_symbol")
public func sui_option_symbol(_ h: UnsafeMutableRawPointer, _ index: Int32) -> UnsafePointer<CChar>? {
  let i = Int(index)
  guard let t = view(h) as? TabHostView, let items = t.controller.tabBar.items, i >= 0, i < items.count,
    i < t.pages.count
  else { return nil }
  let page = t.pages[i]
  guard let shown = items[i].image, shown === page.image, let n = page.symbol else { return nil }
  return scratch(n)
}

/// The page UIKit has on screen: the selected controller's view, while it is in the window.
@_cdecl("sui_tab_shown")
public func sui_tab_shown(_ h: UnsafeMutableRawPointer) -> UnsafeMutableRawPointer? {
  guard let t = view(h) as? TabHostView, let v = t.controller.selectedViewController?.viewIfLoaded,
    v.window != nil, !v.isHidden
  else { return nil }
  return Unmanaged.passUnretained(v).toOpaque()
}

@_cdecl("sui_choose_date")
public func sui_choose_date(_ h: UnsafeMutableRawPointer, _ epochDayValue: Int32) -> Int32 {
  guard let p = view(h) as? UIDatePicker else { return 0 }
  p.date = dateOf(epochDayValue)
  p.sendActions(for: .valueChanged)
  return 1
}

@_cdecl("sui_get_frame")
public func sui_get_frame(
  _ h: UnsafeMutableRawPointer, _ outX: UnsafeMutablePointer<Double>, _ outY: UnsafeMutablePointer<Double>,
  _ outW: UnsafeMutablePointer<Double>, _ outH: UnsafeMutablePointer<Double>
) {
  let v = view(h)
  v.window?.layoutIfNeeded()
  // The *alignment* rect, which is what Auto Layout positions, as on AppKit.
  let align = v.alignmentRect(forFrame: v.frame)
  let r = v.superview.map { $0.convert(align, to: nil) } ?? v.convert(v.bounds, to: nil)
  outX.pointee = Double(r.minX)
  outY.pointee = Double(r.minY)
  outW.pointee = Double(r.width)
  outH.pointee = Double(r.height)
}

private func write(
  _ e: UIEdgeInsets, _ t: UnsafeMutablePointer<Double>, _ l: UnsafeMutablePointer<Double>,
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
  let e: UIEdgeInsets
  switch v {
  case let g as GridView: e = g.insets
  case let s as UIScrollView: e = s.adjustedContentInset
  case let s as UIStackView: e = s.isLayoutMarginsRelativeArrangement ? s.layoutMargins : .zero
  default: e = .zero
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

/// Every active constraint on a view's height, wherever it is held: the view's own and its
/// ancestors'. Constraints UIKit makes for itself (an autoresizing mask, an intrinsic size, a
/// stack's arrangement) are left out — they are not the renderer's.
@_cdecl("sui_height_constraint_count")
public func sui_height_constraint_count(_ h: UnsafeMutableRawPointer) -> Int32 {
  let v = view(h)
  var n: Int32 = 0
  var holder: UIView? = v
  while let hv = holder {
    for c in hv.constraints where c.isActive && type(of: c) == NSLayoutConstraint.self {
      if (c.firstItem === v && c.firstAttribute == .height) || (c.secondItem === v && c.secondAttribute == .height) {
        n += 1
      }
    }
    holder = hv.superview
  }
  return n
}

/// UIKit has no public way to synthesise a touch, so this sends nothing and says so. UIKit's
/// container tap is a `UITapGestureRecognizer`, which already fires on touch-up.
@_cdecl("sui_send_mouse")
public func sui_send_mouse(_ h: UnsafeMutableRawPointer, _ kind: Int32, _ x: Double, _ y: Double) -> Int32 {
  _ = (h, kind, x, y)
  return 0
}

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

/// Where the construction placed a cell. UIKit has no grid to ask, so the self-test also
/// measures the frames, which are the platform's.
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
  UIImage(systemName: String(cString: name)) == nil ? 0 : 1
}

/// The symbol a view shows: recorded only when UIKit resolved it, and only while the button's
/// configuration still holds an image.
@_cdecl("sui_get_symbol")
public func sui_get_symbol(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  let v = view(h)
  let shown: UIImage?
  switch v {
  case let b as UIButton: shown = b.configuration?.image ?? b.image(for: .normal)
  case let i as UIImageView: shown = i.image
  default: shown = nil
  }
  guard shown?.isSymbolImage == true, let n = symbols[ObjectIdentifier(v)] else { return nil }
  return scratch(n)
}

@_cdecl("sui_get_accessible_label")
public func sui_get_accessible_label(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  guard let s = view(h).accessibilityLabel else { return nil }
  return scratch(s)
}

@_cdecl("sui_get_tooltip")
public func sui_get_tooltip(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  let tip = view(h).interactions.compactMap { $0 as? UIToolTipInteraction }.first?.defaultToolTip
  guard let s = tip else { return nil }
  return scratch(s)
}

/// The share of a view's pixels that are painted, from UIKit rendering its layer into a
/// bitmap: a button whose symbol resolved but drew nothing reads 0. -1 when it has no size.
@_cdecl("sui_painted_fraction")
public func sui_painted_fraction(_ h: UnsafeMutableRawPointer) -> Double {
  guard let cg = drawnImage(view(h)) else { return -1 }
  return paintedFraction(cg)
}

/// The view drawn by UIKit into a transparent bitmap; nil when it has no size.
private func drawnImage(_ v: UIView) -> CGImage? {
  v.window?.layoutIfNeeded()
  guard v.bounds.width >= 1, v.bounds.height >= 1 else { return nil }
  let format = UIGraphicsImageRendererFormat.preferred()
  format.opaque = false
  let image = UIGraphicsImageRenderer(bounds: v.bounds, format: format).image { ctx in
    v.layer.render(in: ctx.cgContext)
  }
  return image.cgImage
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
