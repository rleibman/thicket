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

package example.gallery

import thicket.renderer.gtk.{GtkApp, GtkInspect}

/** The gallery on GTK4.
  *
  * Deliberately thin: everything worth looking at is in `Gallery`, shared with the Android,
  * macOS and iOS hosts, so the same screen is what every platform renders. A host with its
  * own version of the catalogue would hide the differences this example exists to expose.
  *
  * With `THICKET_GALLERY_SELFTEST=1` it also checks that each component really is in the
  * rendered tree. That is the difference between a gallery and a screenshot: a widget can
  * be written in `Gallery.scala`, compile on every platform, and still render as nothing.
  * Reading it back out of GTK is the only thing that notices.
  */
object GalleryGtk {

  private var failures = 0

  private def check(
    name: String,
    ok:   Boolean,
    detail: => String = ""
  ): Unit =
    if ok then println(s"[gallery] ok   $name")
    else {
      failures += 1
      println(s"[gallery] FAIL $name")
      if detail.nonEmpty then println(s"             $detail")
    }

  /** Counts the widget kinds the renderer is supposed to have produced.
    *
    * Counts rather than presence, because "at least one" is a weak claim for a catalogue —
    * the screen deliberately shows several of each, and a renderer that collapsed them all
    * into one widget would still satisfy a presence check.
    */
  private def selfTest(): Unit = {
    val root  = GtkApp.rootHandle
    val texts = GtkInspect.allTexts(root)

    // Every section heading, so nothing silently failed to mount.
    List(
      "Label — every text role",
      "Button — roles, and one disabled",
      "TextField and SecureField",
      "Checkbox and Toggle",
      "Picker — a choice from a fixed list",
      "DatePicker — a day, in the platform's own chooser",
      "SegmentedControl — one of a few, all on screen",
      "Link — opens a URL, drawn as each platform draws a link",
      "Slider, ProgressBar, ActivityIndicator",
      "Layout — Spacer, Grow, Divider, nested Row and Column",
      "ZStack — children drawn over one another, last on top",
      "Grid — cells flow into columns, each as wide as its widest cell",
      "Image — the three content fits",
      "Scroll — horizontal, inside a vertical one",
      "ForEach — keyed, with a context menu on each row",
      "Provide — a theme scoped to one subtree",
      "SafeArea — a prop, because no toolkit models it as a widget",
      "BuildInfo — this module's own, from git",
      "Presented — Sheet and Alert are shown over the app, never inserted"
    ).foreach(h => check(s"section is on screen: $h", texts.contains(h)))

    check("labels in every role", texts.contains("Title") && texts.contains("Body") &&
      texts.contains("Caption") && texts.contains("Secondary"))
    check("a disabled button is on screen, not just a reachable state",
      texts.contains("Disabled"))

    // BuildInfo is generated per module, so this proves the gallery's own object was wired
    // in rather than another module's winning the classpath — which is exactly what used to
    // happen when all fourteen generated `buildinfo.BuildInfo`.
    check("the gallery shows its own BuildInfo, not another module's",
      texts.exists(_.startsWith("thicket-gallery-shared ")),
      texts.filter(_.contains("thicket-")).toString)

    // Two entries: TextField and SecureField. The secure one is the invisible one, which is
    // the only observable difference between them.
    val entries = GtkInspect.findAll(root)(GtkInspect.isEntry)
    check("both text entries exist", entries.length >= 2, s"${entries.length} entries")
    check("exactly one entry hides its characters",
      entries.count(e => !GtkInspect.entryVisible(e)) == 1,
      s"${entries.count(e => !GtkInspect.entryVisible(e))} invisible entries")

    check("a GtkSwitch for Toggle", GtkInspect.findAll(root)(GtkInspect.isSwitch).nonEmpty)
    check("a GtkScale for Slider", GtkInspect.findAll(root)(GtkInspect.isScale).nonEmpty)
    // The picker's options reach the real control, and the bound label proves the selection
    // round-tripped rather than the drop-down just existing.
    check("the Picker offers its options", texts.contains("Banana"), texts.filter(_.contains("an")).toString)
    check("and the selection is bound", texts.exists(_.startsWith("Chose ")),
      texts.filter(_.startsWith("Chose")).toString)
    // DatePicker: a GtkMenuButton whose popover holds a GtkCalendar. Read back out of the
    // calendar itself, then a day is chosen the way a click does — "day-selected" — so the
    // bound label proves the choice went through the renderer and back to the app.
    val datePickers = GtkInspect.findAll(root)(w => GtkInspect.isMenuButton(w) && GtkInspect.calendarOf(w).nonEmpty)
    check("one GtkMenuButton with a GtkCalendar", datePickers.length == 1, datePickers.length.toString)
    datePickers.headOption.foreach { button =>
      GtkInspect.calendarOf(button).foreach { cal =>
        val start = Gallery.firstDue
        check("the calendar shows the model's date, leap day and all",
          GtkInspect.calendarDate(cal) == (start.year, start.month, start.day), GtkInspect.calendarDate(cal).toString)
        val before = GtkInspect.menuButtonLabel(button)
        check("the button shows a date", before.nonEmpty, before)
        GtkInspect.chooseDay(cal, 2029, 1, 31)
        check("choosing a day reaches the app", GtkInspect.allTexts(root).contains("Due 2029-01-31"),
          GtkInspect.allTexts(root).filter(_.startsWith("Due")).toString)
        check("and the button shows the new date", GtkInspect.menuButtonLabel(button) != before,
          s"$before -> ${GtkInspect.menuButtonLabel(button)}")
      }
    }

    // SegmentedControl: a `linked` box of grouped toggle buttons. Asked of the real
    // buttons — which one GTK says is active — and then clicked for real, so the bound
    // label proves the choice went through the renderer's handler and back.
    val segmented = GtkInspect.findAll(root)(w =>
      GtkInspect.hasCssClass(w, "linked") && GtkInspect.children(w).nonEmpty &&
        GtkInspect.children(w).forall(GtkInspect.isToggleButton))
    check("one linked box of toggle buttons", segmented.length == 1, segmented.length.toString)
    segmented.headOption.foreach { box =>
      val segs = GtkInspect.children(box)
      def active = segs.map(GtkInspect.toggleActive)
      check("a segment per option, in order", segs.map(GtkInspect.allTexts) == Gallery.spans.map(List(_)),
        segs.map(GtkInspect.allTexts).toString)
      check("the model's selection is the active segment", active == List(false, true, false), active.toString)
      GtkInspect.click(segs(2))
      check("clicking a segment makes it the only one active", active == List(false, false, true), active.toString)
      check("and reaches the app", GtkInspect.allTexts(root).contains("Showing a Month"),
        GtkInspect.allTexts(root).filter(_.startsWith("Showing")).toString)
    }

    // Links. Clicked for real — "clicked" is emitted on the widget — with the opener
    // swapped for a recorder, so the check proves a click reaches the URL without launching
    // a browser on this machine. That GtkUriLauncher then opens it is not exercised here.
    val opened = GtkInspect.interceptUrls()
    val links  = GtkInspect.findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.hasCssClass(w, "link"))
    check("both links are GtkButtons drawn as links", links.length == 2,
      links.map(GtkInspect.allTexts).toString)
    links.find(l => GtkInspect.allTexts(l) == List("thicket on GitHub")).foreach { l =>
      GtkInspect.click(l)
      check("clicking a link opens its URL", opened.toList == List(Gallery.repoUrl), opened.toString)
    }
    links.find(l => GtkInspect.allTexts(l) == List("Apache License 2.0")).foreach { l =>
      GtkInspect.click(l)
      check("a link with a tap opens its URL", opened.lastOption.contains(Gallery.licenceUrl), opened.toString)
      check("and runs the tap as well",
        GtkInspect.allTexts(root).contains("The licence link was tapped 1 times"),
        GtkInspect.allTexts(root).filter(_.contains("licence")).toString)
    }

    // Grid: a GtkGrid whose cells are where their order says, read back from GTK with
    // gtk_grid_query_child. Then a row is inserted in the middle by clicking the real button,
    // and the cells after it must have moved down a row.
    // Ours, not any GtkGrid: GtkCalendar — the DatePicker's popover — is built from one,
    // and the first version of this check found that instead.
    val grids = GtkInspect.findAll(root)(g => GtkInspect.isGrid(g) && GtkInspect.allTexts(g).contains("Typed above"))
    check("the Grid is a GtkGrid", grids.length == 1, grids.length.toString)
    grids.headOption.foreach { grid =>
      def cells = GtkInspect.children(grid).map(c => (GtkInspect.allTexts(c).mkString, GtkInspect.gridCell(grid, c)))
      def at(text: String) = cells.find(_._1 == text).map(_._2)
      check("cells flow row by row into two columns",
        at("Typed above").contains((0, 0)) && at("Volume").contains((0, 1)) && at("4.0").contains((1, 1)) &&
          at("short").contains((1, 2)),
        cells.toString)
      GtkInspect.findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Insert a row"))
        .headOption.foreach(GtkInspect.click)
      check("a row inserted in the middle lands there", at("Inserted").contains((0, 1)) && at("a whole row").contains((1, 1)),
        cells.toString)
      check("and every cell after it moves down a row", at("Volume").contains((0, 2)) && at("short").contains((1, 3)),
        cells.toString)
      GtkInspect.findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Remove the row"))
        .headOption.foreach(GtkInspect.click)
      check("and removing it moves them back", at("Volume").contains((0, 1)) && at("Inserted").isEmpty,
        cells.toString)
    }

    check("a GtkProgressBar", GtkInspect.findAll(root)(GtkInspect.isProgressBar).nonEmpty)

    // ZStack is a GtkOverlay. Child order is paint order, so the badge being *last* is the
    // claim that it is drawn on top; and the stack's size is the largest child's, which is
    // what `measure` on every overlay buys — without it a GtkOverlay with no main child
    // measures 0x0 and the whole section collapses.
    val stacks = GtkInspect.findAll(root)(GtkInspect.isOverlay)
    check("two GtkOverlays for the two ZStacks", stacks.length == 2, s"${stacks.length} overlays")
    stacks.headOption.foreach { badge =>
      val kids = GtkInspect.children(badge)
      check("the badge is the last child, so painted on top",
        kids.lastOption.exists(k => GtkInspect.allTexts(k).exists(_.endsWith(" rows"))),
        kids.map(GtkInspect.allTexts).toString)
      check("the badge sits top-trailing", kids.lastOption.map(GtkInspect.placement).contains(("End", "Start")),
        kids.map(GtkInspect.placement).toString)
      val (w, h) = (GtkInspect.naturalWidth(badge), GtkInspect.naturalHeight(badge))
      val (cw, ch) = (kids.map(GtkInspect.naturalWidth).max, kids.map(GtkInspect.naturalHeight).max)
      check("the stack is as large as its largest child", w == cw && h == ch && w > 0 && h > 0,
        s"stack ${w}x$h, largest child ${cw}x$ch")
    }
    stacks.lift(1).foreach { centred =>
      val kids = GtkInspect.children(centred)
      check("the spinner is over its content, centred",
        kids.length == 2 && GtkInspect.isSpinner(kids(1)) && GtkInspect.placement(kids(1)) == ("Center", "Center"),
        kids.map(GtkInspect.placement).toString)
    }

    // The ForEach rows each carry a context menu, so there is one popover per row.
    val popovers = GtkInspect.findAll(root)(GtkInspect.isPopover)
    check("one context-menu popover per row", popovers.length >= 3, s"${popovers.length} popovers")
    check("the popovers hold the menu's items",
      popovers.exists(p => GtkInspect.allTexts(p).contains("Remove")))

    // Presented widgets must *not* be in the tree until asked for — the property that makes
    // them presented rather than inserted.
    check("no sheet or alert before it is asked for",
      !texts.contains("A presented container with a live subtree inside it."))

    // The gallery styles more widgets than any other screen, so it is the best place to
    // notice a stylesheet GTK cannot parse (#30).
    check("no CSS the renderer wrote failed to parse",
      GtkInspect.cssParseErrors == 0L,
      s"${GtkInspect.cssParseErrors} CSS parse errors")

    if failures == 0 then println(s"[gallery] ALL CHECKS PASSED")
    else println(s"[gallery] $failures CHECK(S) FAILED")
  }

  def main(args: Array[String]): Unit = {
    val _ = GtkApp.run("dev.thicket.gallery", width = 520, height = 760) {
      if sys.env.contains("THICKET_GALLERY_SELFTEST") then GtkApp.postToUi(() => selfTest())
      Gallery.app()
    }
  }

}
