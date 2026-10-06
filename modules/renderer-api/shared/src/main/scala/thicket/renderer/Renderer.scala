/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package thicket.renderer

/** The seam between the framework and a platform toolkit.
  *
  * Revised after S7 built it against GTK4 and found four defects in the §7.4 sketch: `setFrame` is not universally
  * expressible, `insertChild`-by-index has no GTK primitive, `measure` must report min *and* natural size, and a type
  * named `Size` is shadowed by `scalanative.unsafe.Size`. All four are addressed here.
  */

/** Who positions a container's children.
  *
  * The §7.4 sketch assumed the framework always does, and that the toolkit obeys `setFrame`. A `GtkBox` does not: it
  * lays out its own children, and forcing absolute positioning would mean `GtkFixed` everywhere, discarding GTK's
  * sizing, RTL handling and baseline alignment. So the renderer *declares* which model each container uses and the
  * reconciler adapts.
  */
enum LayoutMode {

  /** The framework computes rectangles (Yoga) and calls `setFrame` on every child. e.g. a plain `UIView`, a `GtkFixed`,
    * an Android `FrameLayout` used as a canvas.
    */
  case FrameBased

  /** The toolkit lays out its own children; `setFrame` is ignored. The framework instead hands the container its layout
    * *style* (spacing, padding, alignment) as properties. e.g. `GtkBox`, `UIStackView`, Android `LinearLayout`.
    */
  case ToolkitManaged

}

/** A widget's minimum and natural size along both axes.
  *
  * One size is not enough: GTK reports minimum and natural separately (as do CSS `min-content`/`max-content`), and
  * collapsing them loses information the toolkit needs.
  */
final case class Measurement(
  minW: Float,
  minH: Float,
  natW: Float,
  natH: Float
)

object Measurement {

  def exact(
    w: Float,
    h: Float
  ): Measurement = Measurement(w, h, w, h)

}

final case class Frame(
  x: Float,
  y: Float,
  w: Float,
  h: Float
)

/** `NaN` on an axis means "unconstrained". */
final case class Constraints(
  maxW: Float,
  maxH: Float
)

object Constraints {

  val unbounded: Constraints = Constraints(Float.NaN, Float.NaN)

}

/** The widget vocabulary a renderer must understand. Deliberately small for v0; every addition costs work in every
  * renderer, so the list grows only with evidence.
  */
enum WidgetKind {

  case Column, Row, Label, Button

  /** Single-line text input. The first widget whose data flows *back* to the app. */
  case TextField

  /** A boolean toggle. */
  case Checkbox

  /** A scrolling viewport around exactly one child. Unlike Column/Row it does not hold a list, which the reconciler has
    * to respect: inserting a second child replaces the first.
    *
    * Scrolls vertically unless created with [[Prop.Axis]]. A horizontal one is the answer to `Row` overflow: `Row`
    * itself clips, and every toolkit here has a scroller but only some have a wrapping box.
    */
  case Scroll

  /** A hairline rule between items, drawn by the platform at the platform's own weight and colour.
    */
  case Divider

  /** A picture. */
  case Image

  /** A boolean control shown as a sliding switch rather than a box with a tick.
    *
    * The same state as [[Checkbox]] and the same props — [[Prop.Checked]] and [[Prop.OnCheckedChange]] — deliberately a
    * separate kind rather than a style flag, because the platforms disagree about which one a given setting *is*: a
    * `GtkSwitch` is not a themed `GtkCheckButton`, and `UISwitch` is a different class from a checkbox AppKit has and
    * iOS does not. An app that picks one is making a platform-idiom choice, and the framework should not silently make
    * it for them.
    */
  case Toggle

  /** Blank, flexible space. Has no appearance of its own; its whole purpose is to take up the room its siblings do not,
    * which it does through [[Prop.Grow]].
    *
    * A `Row` with a `Spacer` between two children pushes them to the edges. This is how every toolkit here expects that
    * to be expressed, and it is why `Spacer` is a widget rather than an alignment prop on the parent.
    */
  case Spacer

  /** Determinate or indeterminate progress, per [[Prop.Progress]]. */
  case ProgressBar

  /** A continuous value chosen by dragging, within [[Prop.Range]]. */
  case Slider

  /** Single-line text input that does not show what it holds.
    *
    * A separate kind rather than a flag on [[TextField]] because AppKit makes it one: `NSSecureTextField` is a
    * different class, and a renderer whose widget is chosen at `create` cannot switch later. GTK could have done it
    * with a property and Android with an input type, but a kind that two renderers must have anyway is cheaper than a
    * prop that two renderers must refuse to honour.
    */
  case SecureField

  /** A modal alert: a title, a message, and one or more choices.
    *
    * **Presented while it is mounted**, not inserted into the tree. `Show(confirming)(Alert(...))` is how it opens and
    * closes, which is the same mechanism [[ActivityIndicator]] uses and the same one the reconciler already provides —
    * rather than a second, imperative way to say the same thing.
    *
    * It has no children. Every toolkit here takes its buttons as *data* — an array of labels returning an index
    * (`gtk_alert_dialog_set_buttons`), or a label plus a callback (`AlertDialog.Builder.setButton`,
    * `NSAlert.addButton`, `UIAlertAction`) — so representing them as child widgets would mean every renderer creating
    * widgets it then had to throw away. They travel in [[Prop.Actions]] instead.
    */
  case Alert

  /** A modal sheet: an element subtree presented over the app.
    *
    * Like [[Alert]] it is [[presented]] rather than inserted, and like every container it holds children — which is the
    * point of building it second. `Alert` could have been special-cased; a presented kind that is *also* a container is
    * what shows the `present`/`dismiss` seam generalises, because the children mount into its handle by the ordinary
    * path and only the attachment differs.
    */
  case Sheet

  /** A spinner: work is happening and its extent is unknown.
    *
    * It has no "running" prop. It spins while it is mounted, which makes `Show(loading)` the way to stop it — the same
    * mechanism as any other conditional subtree, rather than a second way to express the same thing.
    */
  case ActivityIndicator

  /** A choice from a fixed list: `GtkDropDown`, `Spinner`, `NSPopUpButton`, a menu-backed `UIButton`. Every toolkit has
    * one, so unlike `Radio` and `SegmentedControl` it passes §12.2a — the platforms differ in how the list is shown,
    * which is exactly what a native renderer is supposed to decide.
    */
  case Picker

  /** Children drawn over one another, later ones on top: `GtkOverlay`, `FrameLayout`, a plain `NSView` / `UIView`.
    *
    * Named for the z-axis on purpose. "Stack" already means three other things here — `NSStackView` and `UIStackView`
    * are what `Column` and `Row` *are*, and a `GtkStack` shows one child at a time — so a widget called `Stack` would
    * be misread by exactly the people who know the toolkits best.
    *
    * Each child keeps its natural size and is placed by [[Prop.StackAlignment]]; the stack is as large as its largest
    * child. [[Prop.Grow]] means nothing here, because a stack has no main axis to grow along.
    */
  case ZStack

  /** One choice from a few, every option visible at once: `NSSegmentedControl` / `UISegmentedControl`.
    *
    * **The one §12.2a exception, by the project owner's decision on #50.** Only the two Apple platforms have a native
    * one. It is built anyway, with each of the other two given its own closest construction rather than an imitation of
    * Apple's: on GTK a `linked` box of grouped toggle buttons, which is how GNOME apps drew this before
    * `AdwToggleGroup` existed; on Android a horizontal `RadioGroup`, the framework's one-of-a-few control.
    *
    * The same three props as [[Picker]] — [[Prop.Options]], [[Prop.Selected]] as an index, [[Prop.OnSelect]] — because
    * it is the same choice drawn differently: which of the two an app picks is about how many options there are and
    * whether they should all be on screen, not about what is being chosen.
    */
  case SegmentedControl

  /** A calendar date, chosen with the platform's own date chooser: a `.compact` `UIDatePicker`, a text-field
    * `NSDatePicker`, a `GtkMenuButton` that opens a `GtkCalendar`, a button that opens Android's `DatePickerDialog`.
    *
    * All four are the *compact* idiom — a control showing the date, which opens a calendar — rather than an inline
    * calendar, because that is what each platform puts in a form. The value is a [[CalendarDate]]: a day, with no time
    * and no time zone, so nothing can move it by a day on the way through.
    */
  case DatePicker

  /** Whether this kind is *presented over* the app rather than placed in the tree.
    *
    * A framework-level fact rather than a per-renderer one: an alert is not a child of anything on any of the four
    * toolkits. GTK says so in its types — `GtkAlertDialog` is a `GObject`, not a `GtkWidget`, so it could not be
    * inserted even if we wanted to — and AppKit, UIKit and Android all present rather than attach.
    *
    * The reconciler reads this and calls [[Renderer.present]] / [[Renderer.dismiss]] instead of `insertAfter` /
    * `removeChild`. Without it every renderer would need the same special case in three methods, which is twelve places
    * to keep in step and twelve chances to forget.
    */
  def presented: Boolean =
    this match {
      case Alert | Sheet => true
      case _             => false
    }

}

/** Where a picture's data comes from.
  *
  * Deliberately only *local* data. The framework does not fetch, cache or retry: an app that needs an image over the
  * network already has an effect system for that, and `RemoteData[E, ImageSource]` composes with everything else —
  * loading and failure states become the same exhaustive match as any other async value. Building an HTTP client and a
  * cache eviction policy into a UI framework would duplicate what the app already has, and do it worse.
  */
enum ImageSource {

  case FromFile(path: String)
  case FromBytes(data: Array[Byte])

}

/** How a picture fills the space it is given. */
enum ContentFit {

  case Contain, Cover, Fill

}

/** A property change. An enum rather than `Map[String, Any]` so the compiler checks that each renderer handles every
  * case — one of the reasons for doing this in Scala at all.
  */
enum Prop {

  case Text(value: String)
  case OnTap(handler: () => Unit)
  case Spacing(dp: Int)
  case Padding(dp: Int)
  case Enabled(value: Boolean)

  /** Greyed-out hint shown while a text field is empty. */
  case Placeholder(value: String)

  /** Fired as the user edits. The renderer must *not* fire this when the app pushes a new value in — that would be an
    * echo, and with a signal bound to it, a loop.
    */
  case OnTextChange(handler: String => Unit)

  case Checked(value: Boolean)
  case OnCheckedChange(handler: Boolean => Unit)

  /** Type role, mapped to each platform's own type scale rather than to a pixel size. Asking for "17pt semibold" would
    * be exactly the cross-platform lowest-common- denominator this project exists to avoid.
    */
  case Style(role: TextRole)

  /** Whether this child should absorb spare space along its parent's main axis. */
  case Grow(value: Boolean)

  /** Horizontal alignment of a widget's own content. */
  case Align(value: Alignment)

  /** An explicit colour override for this widget's foreground, or `None` for the platform's own token.
    */
  case Tint(color: Option[Rgb])

  /** An explicit colour override for this widget's background, or `None`. */
  case Fill(color: Option[Rgb])

  case Picture(source: Option[ImageSource])
  case Fit(value: ContentFit)

  /** Which way a [[WidgetKind.Scroll]] scrolls. Default [[Orientation.Vertical]].
    *
    * **Read at `create`, not at `update`.** On Android the two directions are different widget classes (`ScrollView`
    * and `HorizontalScrollView`), so a renderer cannot honour a later change without replacing the widget. A renderer
    * may ignore this prop in `update`; the framework does not animate or toggle it.
    */
  case Axis(value: Orientation)

  /** How far along a [[WidgetKind.ProgressBar]] is.
    *
    * `Some(fraction)` for determinate, clamped to 0.0–1.0 by the renderer; **`None` means indeterminate** — the work is
    * happening and its extent is unknown. Every toolkit here distinguishes the two, and the distinction is visible: an
    * indeterminate bar animates and a determinate one at 0.0 does not, so collapsing them would make "nothing has
    * happened yet" and "we cannot say" look identical.
    */
  case Progress(value: Option[Double])

  /** Where a [[WidgetKind.Slider]] currently sits, in the units of its [[Range]].
    *
    * Not a fraction. An app choosing a volume between 0 and 11 should say 7, not 0.636, and a renderer whose control is
    * integral underneath (Android's `SeekBar`) is the one that converts — which is where the conversion belongs, since
    * only it knows its own resolution.
    */
  case Value(value: Double)

  /** A slider's bounds. Sent before [[Value]] by the framework, because a value outside the range is meaningless and
    * every toolkit clamps it silently.
    */
  case Range(
    min: Double,
    max: Double
  )

  case OnValueChange(handler: Double => Unit)

  /** The body text of an [[WidgetKind.Alert]]. [[Text]] is its title.
    *
    * Two fields rather than one because all four toolkits have two and style them differently — `message`/`detail` on
    * GTK, `messageText`/`informativeText` on AppKit — and collapsing them would throw away the platform's own
    * typography.
    */
  case Message(value: String)

  /** An alert's choices, in order. The first is conventionally the default.
    *
    * Data, not child widgets: see [[WidgetKind.Alert]].
    */
  case Actions(value: Seq[AlertAction])

  /** Dismissed without choosing — Escape, a tap outside, the system back gesture.
    *
    * Distinct from choosing a cancel button: a platform can dismiss an alert without the app offering that choice, and
    * an app that treats the two as the same will eventually be wrong about whether the user declined or simply looked
    * away.
    */
  case OnDismiss(handler: () => Unit)

  /** A context menu the widget *has*, shown by the platform's own gesture.
    *
    * A prop rather than a widget kind, because that is how all four toolkits model it: `NSView.menu`,
    * `UIContextMenuInteraction`, a `GtkPopover` parented to the widget, a `PopupMenu` anchored at the view. None of
    * them is a sibling you place; each is a property of something already on screen.
    *
    * Which gesture opens it is the platform's business and deliberately not the app's: secondary click on a desktop,
    * long press on a phone. An app that hard-coded "right-click" would be wrong on Android and an app that hard-coded
    * "long press" would be wrong on GTK.
    */
  case ContextMenu(items: Seq[MenuItem])

  /** How prominent text should be, relative to the platform's own foreground colours.
    *
    * Not a colour. There is deliberately no way to say "grey #767676": a theme that pushes its own palette at every
    * platform is how cross-platform apps come to look like none of them, and it breaks dark mode and accessibility
    * contrast settings that the platform would otherwise handle. Roles map onto each platform's own tokens.
    */
  case TextEmphasis(value: Emphasis)

  /** A `Picker`'s choices, in order. */
  case Options(values: Seq[String])

  /** Which choice is selected, as an index into [[Options]]. `-1` for none.
    *
    * An index rather than the string: two options may share a label, and every toolkit's selection API is index-based
    * underneath.
    */
  case Selected(index: Int)

  case OnSelect(handler: Int => Unit)

  /** A [[WidgetKind.DatePicker]]'s date. */
  case DateValue(date: CalendarDate)

  /** The date the user chose. Not called for the app's own writes. */
  case OnDateChange(handler: CalendarDate => Unit)

  /** Where a [[WidgetKind.ZStack]] places each child within itself, on both axes.
    *
    * On the *stack*, not per child: the largest child fills the stack whatever its alignment, so what this decides is
    * where the smaller ones sit — a badge in a corner, a spinner in the middle. `Start` and `End` follow the layout
    * direction, as everywhere else in the contract.
    */
  case StackAlignment(
    horizontal: Alignment,
    vertical:   Alignment
  )

  /** Keep this widget's content out from under the system's own chrome — a notch, a status bar, a home indicator, a
    * rounded corner.
    *
    * A **prop, not a widget**, which is §12.2a answered a fifth time. No toolkit models a safe area as something you
    * place: Apple exposes `safeAreaInsets` on a view, Android hands insets to a listener on a view, and **GTK4 has no
    * such concept at all**. All three that have it describe a property of a view, so that is what this is.
    *
    * GTK's answer is therefore no padding, and that is *correct* rather than a stub: a desktop window's safe area is
    * the whole window. The distinction matters — a `SegmentedControl` on GTK would have to be imitated, where zero
    * insets here is the right answer honestly arrived at.
    */
  case SafeArea(edges: Set[Edge])

}

/** A day in the proleptic Gregorian calendar: no time, no time zone. `month` is 1–12 and `day` 1–31, as people write
  * them — the renderers convert, since Android's and GTK's months count from 0.
  *
  * Not `java.time.LocalDate`, which Scala Native does not have. Across the Apple boundary it travels as an **epoch
  * day** (days since 1970-01-01), and the shims read it at midnight UTC in a picker set to UTC: a whole day in any
  * calendar the user's locale shows, and never a different day because of where the device is.
  */
final case class CalendarDate(
  year:  Int,
  month: Int,
  day:   Int
) {

  require(month >= 1 && month <= 12, s"month $month is not 1-12")
  require(day >= 1 && day <= CalendarDate.daysIn(year, month), s"$year-$month has no day $day")

  /** Days since 1970-01-01, negative before it. Howard Hinnant's `days_from_civil`: exact for every Gregorian date, and
    * arithmetic only.
    */
  def toEpochDay: Int = {
    val y = if month <= 2 then year - 1 else year
    val era = (if y >= 0 then y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if month > 2 then month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468
  }

  override def toString: String = f"$year%04d-$month%02d-$day%02d"

}

object CalendarDate {

  /** The inverse of [[CalendarDate.toEpochDay]], Hinnant's `civil_from_days`. */
  def fromEpochDay(epochDay: Int): CalendarDate = {
    val z = epochDay + 719468
    val era = (if z >= 0 then z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if mp < 10 then mp + 3 else mp - 9
    CalendarDate(yoe + era * 400 + (if m <= 2 then 1 else 0), m, d)
  }

  def isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

  def daysIn(
    year:  Int,
    month: Int
  ): Int =
    month match {
      case 2              => if isLeap(year) then 29 else 28
      case 4 | 6 | 9 | 11 => 30
      case _              => 31
    }

}

enum Emphasis {

  case Normal, Secondary

}

/** An app-supplied colour for a role, resolved by the framework and handed to the renderer.
  *
  * `None` means "the platform's own token", which is the default and the native-looking choice. A renderer must treat
  * `None` as "do not set a colour at all" rather than as a colour of its own — that is the difference between following
  * the user's theme and ignoring it.
  */
final case class Rgb(
  r: Int,
  g: Int,
  b: Int
)

enum TextRole {

  case Title, Body, Caption

}

/** An edge of the screen, for [[Prop.SafeArea]].
  *
  * `Leading`/`Trailing` rather than left/right: both Apple platforms and Android lay out right-to-left under an RTL
  * locale, and a safe area pinned to "left" is wrong in Arabic on exactly the hardware that has a notch.
  */
enum Edge {

  case Top, Bottom, Leading, Trailing

}

object Edge {

  /** Every edge — the common case, and what `.safeArea` means with no arguments. */
  val all: Set[Edge] = Edge.values.toSet

}

enum Alignment {

  case Start, Center, End

}

enum Orientation {

  case Vertical, Horizontal

}

/** One entry in a [[Prop.ContextMenu]].
  *
  * By-name body, re-evaluated per invocation, like `Button` and `AlertAction`.
  */
final case class MenuItem(
  label:   String,
  enabled: Boolean = true
)(
  body: => Unit
) {

  def onSelect(): Unit = body

}

/** One choice in an [[WidgetKind.Alert]].
  *
  * `destructive` and `cancel` are *roles*, not styling: every platform draws them differently and puts them in a
  * different position — iOS red and last, AppKit leftmost — and an app that hard-codes a colour gets it wrong on three
  * of four.
  */
final case class AlertAction(
  label:       String,
  destructive: Boolean = false,
  cancel:      Boolean = false
)(
  body: => Unit
) {

  /** By-name, and re-evaluated per call, so `AlertAction("Delete")(model.delete())` reads the same as
    * `Button("Delete")(model.delete())` rather than making the caller write a thunk the rest of the DSL never asks for.
    */
  def onSelect(): Unit = body

}

/** Rows for a virtualising container to pull from.
  *
  * This is the one place the framework hands control *to* the renderer. Everywhere else the reconciler builds a tree
  * and the renderer obeys; a `ListView`, `RecyclerView`, `GtkListView` or `UITableView` instead asks for the row it is
  * about to show, and recycles the ones it is not. Implemented by the framework, called by the renderer.
  */
trait RowSource[H] {

  /** How many rows exist right now. */
  def count: Int

  /** Produce the handle for row `index`.
    *
    * `recycled` is a handle the renderer previously got from this source and is no longer showing. Returning it
    * re-bound — rather than a fresh one — is what makes scrolling a long list cheap, and the framework does that by
    * writing the new item into the row's own signal, so only the widgets bound to changed fields are touched.
    */
  def bind(
    index:    Int,
    recycled: Option[H]
  ): H

  /** The renderer will never show this handle again; the framework may dispose it. */
  def discard(handle: H): Unit

  /** The renderer registers here to be told when the data changed. */
  def onInvalidate(callback: () => Unit): Unit

}

trait Renderer {

  /** An opaque per-renderer widget reference. */
  type Handle

  def platform: String

  /** Declares how this container positions children. See [[LayoutMode]]. */
  def layoutMode(kind: WidgetKind): LayoutMode

  def create(
    kind:  WidgetKind,
    props: Seq[Prop]
  ): Handle

  /** Apply properties to an existing widget.
    *
    * **A renderer must not disturb a widget when written a value it already shows.** The reconciler cannot enforce
    * this: after a user edits a text field, the app writes the new value back through a signal, and from the
    * reconciler's side that is a genuine change it has never applied. Only the renderer can compare against what the
    * widget actually holds. Skipping the write is what stops the caret jumping to the end on every keystroke, and — for
    * renderers whose widgets emit a change event when set programmatically — what stops a bound field looping.
    */
  def update(
    handle: Handle,
    patch:  Seq[Prop]
  ): Unit

  /** Insert `child` into `parent` directly after `after`, or first when `after` is `None`.
    *
    * Specified by preceding sibling rather than index because every toolkit can express that, while several (GTK among
    * them) have no insert-at-index primitive.
    */
  def insertAfter(
    parent: Handle,
    child:  Handle,
    after:  Option[Handle]
  ): Unit

  def removeChild(
    parent: Handle,
    child:  Handle
  ): Unit

  /** Move an already-attached `child` to sit directly after `after` (first when `None`).
    *
    * Keyed list reconciliation reorders existing widgets, and doing that as remove-then-insert destroys focus, scroll
    * position and in-flight animations on most toolkits. The default is still remove+insert so a renderer need not
    * implement it; anything with a native reorder should override.
    */
  def moveAfter(
    parent: Handle,
    child:  Handle,
    after:  Option[Handle]
  ): Unit = {
    removeChild(parent, child)
    insertAfter(parent, child, after)
  }

  /** Detach `handle` from its parent, if attached, and release it.
    *
    * Detaching is part of destroying, not a separate step the caller performs first. GTK forced this: `gtk_box_remove`
    * frees the removed widget's whole subtree, so a reconciler that removed a container and *then* destroyed its
    * children would be destroying freed memory. The reconciler therefore destroys depth-first — children before parents
    * — and each renderer detaches whatever it is given.
    */
  def destroy(handle: Handle): Unit

  /** Show a [[WidgetKind.presented]] widget over the app.
    *
    * Called instead of `insertAfter` when the node is mounted. The default throws rather than silently doing nothing: a
    * renderer that grows a presented kind and forgets to implement this would otherwise mount an alert that never
    * appears, which looks like the app ignoring the user.
    */
  def present(handle: Handle): Unit = throw new UnsupportedOperationException(s"$platform cannot present this widget")

  /** Dismiss a presented widget. Called when it unmounts, before `destroy`. */
  def dismiss(handle: Handle): Unit = throw new UnsupportedOperationException(s"$platform cannot dismiss this widget")

  def measure(
    handle:      Handle,
    constraints: Constraints
  ): Measurement

  /** Position a child. Only meaningful when its parent is [[LayoutMode.FrameBased]]; renderers may ignore it otherwise.
    */
  def setFrame(
    handle: Handle,
    frame:  Frame
  ): Unit

  /** Whether this renderer has a container that materialises only visible rows.
    *
    * Default `false`, and a renderer that says so is not deficient — the framework falls back to mounting every row,
    * which is correct, just heavier. That fallback is what lets a renderer be written without a virtualising container
    * on day one.
    */
  def supportsVirtualRows: Boolean = false

  /** Create a container that pulls rows from `source`. Only called when [[supportsVirtualRows]] is true.
    */
  def createVirtualList(source: RowSource[Handle]): Handle =
    throw new UnsupportedOperationException(s"$platform cannot virtualise rows")

  /** Run `f` on the UI thread. Safe to call from any thread the platform permits.
    *
    * On Apple targets, "any thread" means the main thread or a Scala-created thread — never a GCD queue, which
    * segfaults in the GC allocator (S1).
    */
  def runOnUiThread(f: () => Unit): Unit

}
