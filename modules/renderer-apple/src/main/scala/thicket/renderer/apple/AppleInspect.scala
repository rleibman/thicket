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

package thicket.renderer.apple

import scala.scalanative.unsafe.*

/** Reads the view tree back out of AppKit.
  *
  * The unit tests prove the reconciler against an in-memory renderer, which by construction cannot catch a wrong AppKit
  * call. This is how the demo verifies that the renderer's calls produced the intended result in a real toolkit — the
  * same job `GtkInspect` does.
  */
object AppleInspect {

  def children(h: Shim.Handle): List[Shim.Handle] = {
    val n = Shim.sui_child_count(h)
    val out = scala.collection.mutable.ListBuffer.empty[Shim.Handle]
    var i = 0
    while i < n do {
      val c = Shim.sui_child_at(h, i)
      if c != null then out += c
      i += 1
    }
    out.toList
  }

  /** The text a single view carries, if any. */
  def text(h: Shim.Handle): Option[String] = {
    val p = Shim.sui_get_text(h)
    // The shim's buffer is valid only until the next call, so copy here and now.
    if p == null then None else Some(fromCString(p))
  }

  /** Every piece of text in the subtree, in tree order.
    *
    * Text-bearing views are checked *before* recursing: an `NSTextField` and an `NSButton` both have internal subviews,
    * and walking into them would report AppKit's private structure instead of the value.
    */
  def allTexts(h: Shim.Handle): List[String] =
    if Shim.sui_is_text_bearing(h) != 0 then text(h).toList
    else children(h).flatMap(allTexts)

  /** The platform class of a view — what AppKit or UIKit actually built, not what the renderer asked for. */
  def className(h: Shim.Handle): String = {
    val p = Shim.sui_class_name(h)
    if p == null then "" else fromCString(p)
  }

  /** Every view in the subtree, parent before children. */
  def all(h: Shim.Handle): List[Shim.Handle] = h :: children(h).flatMap(all)

  /** A determinate fraction 0.0-1.0; -1 when indeterminate; -2 when the view shows no progress. */
  def progress(h: Shim.Handle): Double = Shim.sui_get_progress(h)

  /** A slider's value, in the app's own units. */
  def value(h: Shim.Handle): Double = Shim.sui_get_value(h)

  /** Whether a field masks what is typed into it. */
  def isSecure(h: Shim.Handle): Boolean = Shim.sui_is_secure(h) != 0

  private def str(p: CString): Option[String] = if p == null then None else Some(fromCString(p))

  /** Whether an Alert or Sheet is on screen, as the platform reports it. */
  def isPresented(h: Shim.Handle): Boolean = Shim.sui_is_presented(h) != 0

  /** The title the platform is *showing* — the alert's text, the sheet's heading or navigation bar — not what the
    * renderer was told. `None` when it is not presented.
    */
  def presentedTitle(h: Shim.Handle): Option[String] = str(Shim.sui_presented_title(h))

  def presentedMessage(h: Shim.Handle): Option[String] = str(Shim.sui_presented_message(h))

  /** An alert's actions, in the order the platform holds them. */
  def alertActions(h: Shim.Handle): List[String] =
    (0 until Shim.sui_alert_action_count(h)).toList.flatMap(i => str(Shim.sui_alert_action_label(h, i)))

  /** Clicks a button through its own action, as a user would. False if `h` is not a button. */
  def click(h: Shim.Handle): Boolean = Shim.sui_perform_click(h) != 0

  /** Taps any view at its centre through the platform's event path (see `sui_perform_tap`). False when it could not be
    * tapped: off screen, covered, or — on UIKit — carrying no tap recogniser.
    */
  def tap(h: Shim.Handle): Boolean = Shim.sui_perform_tap(h) != 0

  /** A Checkbox's or Toggle's state, read from the platform's control. */
  def checked(h: Shim.Handle): Boolean = Shim.sui_get_checked(h) != 0

  /** Forces `h` right-to-left, as an Arabic or Hebrew locale would, or returns it to the inherited direction. */
  def setRightToLeft(
    h:   Shim.Handle,
    rtl: Boolean
  ): Unit = Shim.sui_set_layout_direction(h, if rtl then 1 else 0)

  /** Pins `h`'s width for a measurement; `None` takes the pin off. */
  def pinWidth(
    h:     Shim.Handle,
    width: Option[Double]
  ): Unit = Shim.sui_pin_width(h, width.getOrElse(-1.0))

  /** Where the platform paints `h`'s pixels across its width, as (first, last) fractions; `None` if it paints none. */
  def inkSpan(h: Shim.Handle): Option[(Double, Double)] =
    Zone {
      val out = alloc[Double](2)
      if Shim.sui_ink_span(h, out, out + 1) != 0 then Some((out(0), out(1))) else None
    }

  /** Chooses an alert action through the platform's response path. False where the platform offers no way to. */
  def chooseAlert(
    h:     Shim.Handle,
    index: Int
  ): Boolean = Shim.sui_alert_choose(h, index) != 0

  /** How many things the platform has presented over the app right now — its answer, not the renderer's. */
  def presentedCount: Int = Shim.sui_presented_count()

  /** The labels of the platform's context menu for this view, in order; empty when it has none. */
  def menuItems(h: Shim.Handle): List[String] =
    (0 until Shim.sui_menu_item_count(h)).toList.flatMap(i => str(Shim.sui_menu_item_label(h, i)))

  /** Chooses a menu item through the platform's own menu path. False where the platform offers no way to. */
  def activateMenu(
    h:     Shim.Handle,
    index: Int
  ): Boolean = Shim.sui_menu_activate(h, index) != 0

  /** Menu items the shim still holds, counted as they are freed. */
  def liveMenuItems: Int = Shim.sui_menu_live()

  /** The platform's own navigation stack: depth, the title shown for each page bottom first, and which is on screen. */
  def pagesDepth: Int = Shim.sui_pages_depth()
  def pageTitles: List[String] = (0 until pagesDepth).toList.flatMap(i => str(Shim.sui_page_title(i)))
  def pageShown:  Int = Shim.sui_pages_shown()

  /** Whether the platform's own chrome offers going back. */
  def backOffered: Boolean = Shim.sui_pages_back_offered() != 0

  /** Goes back one page through the platform's own path, as a user would. */
  def platformBack(): Boolean = Shim.sui_pages_back() != 0

  /** `Some(true)` when an image view holds a decoded image, `Some(false)` when it shows nothing, `None` when `h` is not
    * an image view.
    */
  def hasImage(h: Shim.Handle): Option[Boolean] =
    Shim.sui_has_image(h) match {
      case 1 => Some(true)
      case 0 => Some(false)
      case _ => None
    }

  /** The size, in points, of the image an image view holds (`NSImage.size`, `UIImage.size`); `None` when it holds none
    * or `h` is not an image view.
    */
  def imageSize(h: Shim.Handle): Option[(Double, Double)] =
    Zone {
      val out = alloc[Double](2)
      if Shim.sui_image_size(h, out, out + 1) != 0 then Some((out(0), out(1))) else None
    }

  /** The fit an image view draws with, read from it: `Some(ContentFit)`, or `None` when `h` is not an image view. */
  def contentFit(h: Shim.Handle): Option[thicket.renderer.ContentFit] =
    Shim.sui_get_content_fit(h) match {
      case 0 => Some(thicket.renderer.ContentFit.Contain)
      case 1 => Some(thicket.renderer.ContentFit.Cover)
      case 2 => Some(thicket.renderer.ContentFit.Fill)
      case _ => None
    }

  /** A text view's horizontal text alignment, read from it; `None` when it has none. */
  def textAlign(h: Shim.Handle): Option[thicket.renderer.Alignment] =
    Shim.sui_get_align(h) match {
      case 0 => Some(thicket.renderer.Alignment.Start)
      case 1 => Some(thicket.renderer.Alignment.Center)
      case 2 => Some(thicket.renderer.Alignment.End)
      case _ => None
    }

  /** The view a virtual list's table is showing for `row` now; `None` when that row is not on screen. */
  def rowView(
    table: Shim.Handle,
    row:   Int
  ): Option[Shim.Handle] = Option(Shim.sui_table_row_view(table, row))

  /** How far a vertical scroller is from the top of its content; 0 at the top, -1 if `h` is not a scroller. */
  def scrollOffset(h: Shim.Handle): Double = Shim.sui_scroll_offset(h)

  /** The options a Picker or SegmentedControl holds, as the platform's control has them. */
  def options(h: Shim.Handle): List[String] =
    (0 until Shim.sui_option_count(h)).toList.flatMap(i => str(Shim.sui_option_label(h, i)))

  /** The SF Symbol a TabView's bar shows for each item, in the bar's order; `None` where it shows none that resolved.
    */
  def optionSymbols(h: Shim.Handle): List[Option[String]] =
    (0 until Shim.sui_option_count(h)).toList.map(i => str(Shim.sui_option_symbol(h, i)))

  /** The Tab a TabView's controller has on screen, read from the controller and the window; `None` when none is. */
  def tabShown(h: Shim.Handle): Option[Shim.Handle] = Option(Shim.sui_tab_shown(h))

  /** The selected index, read from the control; -1 for none. */
  def selected(h: Shim.Handle): Int = Shim.sui_get_selected(h)

  /** The option the control draws as chosen; `""` when it shows none (D13). */
  def shownOption(h: Shim.Handle): String = str(Shim.sui_shown_option(h)).getOrElse("")

  /** Chooses an option through the control's own action. False where the platform offers no way to. */
  def choose(
    h:     Shim.Handle,
    index: Int
  ): Boolean = Shim.sui_choose(h, index) != 0

  /** A DatePicker's day, read from the control, as an epoch day. */
  def date(h: Shim.Handle): Int = Shim.sui_get_date(h)

  /** Sets a DatePicker's day through the control's own action. */
  def chooseDate(
    h:        Shim.Handle,
    epochDay: Int
  ): Boolean = Shim.sui_choose_date(h, epochDay) != 0

  /** A view's frame in its window, top-left origin: (x, y, width, height). */
  def frame(h: Shim.Handle): (Double, Double, Double, Double) =
    Zone {
      val out = alloc[Double](4)
      Shim.sui_get_frame(h, out, out + 1, out + 2, out + 3)
      (out(0), out(1), out(2), out(3))
    }

  /** Where a Grid has placed `child`, as (row, column), or `None` if it is not one of its cells. */
  def gridCell(
    grid:  Shim.Handle,
    child: Shim.Handle
  ): Option[(Int, Int)] =
    Zone {
      val out = alloc[Double](2)
      if Shim.sui_grid_cell(grid, child, out, out + 1) != 0 then Some((out(0).toInt, out(1).toInt)) else None
    }

  /** Four edges read from the shim: (top, left, bottom, right). */
  private def edges(read: (Ptr[Double], Ptr[Double], Ptr[Double], Ptr[Double]) => Unit)
    : (Double, Double, Double, Double) =
    Zone {
      val out = alloc[Double](4)
      read(out, out + 1, out + 2, out + 3)
      (out(0), out(1), out(2), out(3))
    }

  /** The insets the platform holds for a container — padding plus any safe area: (top, left, bottom, right). */
  def insets(h: Shim.Handle): (Double, Double, Double, Double) =
    edges(
      (
        t,
        l,
        b,
        r
      ) => Shim.sui_get_insets(h, t, l, b, r)
    )

  /** A view's safe-area insets as the platform reports them now: (top, left, bottom, right). */
  def safeArea(h: Shim.Handle): (Double, Double, Double, Double) =
    edges(
      (
        t,
        l,
        b,
        r
      ) => Shim.sui_get_safe_area(h, t, l, b, r)
    )

  /** Test only: adds safe area at the root, as a toolbar or a full-size content view would. */
  def setExtraSafeArea(
    top:    Double,
    left:   Double,
    bottom: Double,
    right:  Double
  ): Unit = Shim.sui_set_extra_safe_area(top, left, bottom, right)

  /** How many active constraints, the renderer's own, are on a view's height. */
  def heightConstraints(h: Shim.Handle): Int = Shim.sui_height_constraint_count(h)

  /** A mouse event sent through the window at (x, y) in `h`, top-left origin. False where the platform cannot. */
  enum Mouse(val code: Int) {

    case Down extends Mouse(0)
    case Dragged extends Mouse(1)
    case Up extends Mouse(2)

  }

  def sendMouse(
    h:     Shim.Handle,
    event: Mouse,
    x:     Double,
    y:     Double
  ): Boolean = Shim.sui_send_mouse(h, event.code, x, y) != 0

  /** Whether the SDK has an SF Symbol of this name. */
  def symbolExists(name: String): Boolean = Zone(Shim.sui_symbol_exists(toCString(name)) != 0)

  /** The SF Symbol a view shows, or `None` when its name did not resolve. */
  def symbol(h: Shim.Handle): Option[String] = str(Shim.sui_get_symbol(h))

  def accessibleLabel(h: Shim.Handle): Option[String] = str(Shim.sui_get_accessible_label(h))
  def tooltip(h:         Shim.Handle): Option[String] = str(Shim.sui_get_tooltip(h))

  /** The share of a view's pixels the platform paints when drawing it into a bitmap. */
  def paintedFraction(h: Shim.Handle): Double = Shim.sui_painted_fraction(h)

  /** Records the URLs links would open instead of opening them, until stopped; returns a reader for them. */
  def recordOpenedUrls(): () => List[String] = {
    Shim.sui_record_opened_urls(1)
    () => (0 until Shim.sui_opened_url_count()).toList.flatMap(i => str(Shim.sui_opened_url(i)))
  }

  def stopRecordingUrls(): Unit = Shim.sui_record_opened_urls(0)

}
