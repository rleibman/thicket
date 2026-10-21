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

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.scalanative.unsafe.Ptr
import sn.gnome.gtk4.internal.GtkWidget
import thicket.core.GcState
import thicket.renderer.Icon
import thicket.renderer.gtk.{GtkApp, GtkIcons, GtkInspect}
import thicket.signals.ErrorSink

/** The gallery on GTK4.
  *
  * Deliberately thin: everything worth looking at is in `Gallery`, shared with the Android, macOS and iOS hosts, so the
  * same screen is what every platform renders. A host with its own version of the catalogue would hide the differences
  * this example exists to expose.
  *
  * With `THICKET_GALLERY_SELFTEST=1` it also checks that each component really is in the rendered tree. That is the
  * difference between a gallery and a screenshot: a widget can be written in `Gallery.scala`, compile on every
  * platform, and still render as nothing. Reading it back out of GTK is the only thing that notices.
  */
object GalleryGtk {

  private var failures = 0

  private def check(
    name:   String,
    ok:     Boolean,
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
    * Counts rather than presence, because "at least one" is a weak claim for a catalogue — the screen deliberately
    * shows several of each, and a renderer that collapsed them all into one widget would still satisfy a presence
    * check.
    */
  private def selfTest(): Unit = {
    val root = GtkApp.rootHandle
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
      "IconButton — every curated icon, each with its label",
      "TabView — sections kept alive, switched with the platform's tab bar",
      "Image — the three content fits",
      "Scroll — horizontal, inside a vertical one",
      "ForEach — keyed, with a context menu on each row",
      "Provide — a theme scoped to one subtree",
      "SafeArea — a prop, because no toolkit models it as a widget",
      "BuildInfo — this module's own, from git",
      "Errors — a handler that throws is reported, and the app carries on",
      "Effects — a ZIO effect, its result posted back to the UI thread",
      "Presented — Sheet and Alert are shown over the app, never inserted"
    ).foreach(h => check(s"section is on screen: $h", texts.contains(h)))

    check(
      "labels in every role",
      texts.contains("Title") && texts.contains("Body") &&
        texts.contains("Caption") && texts.contains("Secondary")
    )
    check("a disabled button is on screen, not just a reachable state", texts.contains("Disabled"))

    // BuildInfo is generated per module, so this proves the gallery's own object was wired
    // in rather than another module's winning the classpath — which is exactly what used to
    // happen when all fourteen generated `buildinfo.BuildInfo`.
    check(
      "the gallery shows its own BuildInfo, not another module's",
      texts.exists(_.startsWith("thicket-gallery-shared ")),
      texts.filter(_.contains("thicket-")).toString
    )

    // Two entries: TextField and SecureField. The secure one is the invisible one, which is
    // the only observable difference between them.
    val entries = GtkInspect.findAll(root)(GtkInspect.isEntry)
    check("both text entries exist", entries.length >= 2, s"${entries.length} entries")
    check(
      "exactly one entry hides its characters",
      entries.count(e => !GtkInspect.entryVisible(e)) == 1,
      s"${entries.count(e => !GtkInspect.entryVisible(e))} invisible entries"
    )

    check("a GtkSwitch for Toggle", GtkInspect.findAll(root)(GtkInspect.isSwitch).nonEmpty)
    check("a GtkScale for Slider", GtkInspect.findAll(root)(GtkInspect.isScale).nonEmpty)
    // The picker's options reach the real control, and the bound label proves the selection
    // round-tripped rather than the drop-down just existing.
    check("the Picker offers its options", texts.contains("Banana"), texts.filter(_.contains("an")).toString)
    check(
      "and the selection is bound",
      texts.exists(_.startsWith("Chose ")),
      texts.filter(_.startsWith("Chose")).toString
    )
    // DatePicker: a GtkMenuButton whose popover holds a GtkCalendar. Read back out of the
    // calendar itself, then a day is chosen the way a click does — "day-selected" — so the
    // bound label proves the choice went through the renderer and back to the app.
    val datePickers = GtkInspect.findAll(root)(w => GtkInspect.isMenuButton(w) && GtkInspect.calendarOf(w).nonEmpty)
    check("one GtkMenuButton with a GtkCalendar", datePickers.length == 1, datePickers.length.toString)
    datePickers.headOption.foreach { button =>
      GtkInspect.calendarOf(button).foreach { cal =>
        val start = Gallery.firstDue
        check(
          "the calendar shows the model's date, leap day and all",
          GtkInspect.calendarDate(cal) == (start.year, start.month, start.day),
          GtkInspect.calendarDate(cal).toString
        )
        val before = GtkInspect.menuButtonLabel(button)
        check("the button shows a date", before.nonEmpty, before)
        GtkInspect.chooseDay(cal, 2029, 1, 31)
        check(
          "choosing a day reaches the app",
          GtkInspect.allTexts(root).contains("Due 2029-01-31"),
          GtkInspect.allTexts(root).filter(_.startsWith("Due")).toString
        )
        check(
          "and the button shows the new date",
          GtkInspect.menuButtonLabel(button) != before,
          s"$before -> ${GtkInspect.menuButtonLabel(button)}"
        )
      }
    }

    // SegmentedControl: a `linked` box of grouped toggle buttons. Asked of the real
    // buttons — which one GTK says is active — and then clicked for real, so the bound
    // label proves the choice went through the renderer's handler and back.
    val segmented = GtkInspect.findAll(root)(w =>
      GtkInspect.hasCssClass(w, "linked") && GtkInspect.children(w).nonEmpty &&
        GtkInspect.children(w).forall(GtkInspect.isToggleButton)
    )
    check("one linked box of toggle buttons", segmented.length == 1, segmented.length.toString)
    segmented.headOption.foreach { box =>
      val segs = GtkInspect.children(box)
      def active = segs.map(GtkInspect.toggleActive)
      check(
        "a segment per option, in order",
        segs.map(GtkInspect.allTexts) == Gallery.spans.map(List(_)),
        segs.map(GtkInspect.allTexts).toString
      )
      check("the model's selection is the active segment", active == List(false, true, false), active.toString)
      GtkInspect.click(segs(2))
      check("clicking a segment makes it the only one active", active == List(false, false, true), active.toString)
      check(
        "and reaches the app",
        GtkInspect.allTexts(root).contains("Showing a Month"),
        GtkInspect.allTexts(root).filter(_.startsWith("Showing")).toString
      )
    }

    // Links. Clicked for real — "clicked" is emitted on the widget — with the opener
    // swapped for a recorder, so the check proves a click reaches the URL without launching
    // a browser on this machine. That GtkUriLauncher then opens it is not exercised here.
    val opened = GtkInspect.interceptUrls()
    val links = GtkInspect.findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.hasCssClass(w, "link"))
    check(
      "both links are GtkButtons drawn as links",
      links.length == 2,
      links.map(GtkInspect.allTexts).toString
    )
    links.find(l => GtkInspect.allTexts(l) == List("thicket on GitHub")).foreach { l =>
      GtkInspect.click(l)
      check("clicking a link opens its URL", opened.toList == List(Gallery.repoUrl), opened.toString)
    }
    links.find(l => GtkInspect.allTexts(l) == List("Apache License 2.0")).foreach { l =>
      GtkInspect.click(l)
      check("a link with a tap opens its URL", opened.lastOption.contains(Gallery.licenceUrl), opened.toString)
      check(
        "and runs the tap as well",
        GtkInspect.allTexts(root).contains("The licence link was tapped 1 times"),
        GtkInspect.allTexts(root).filter(_.contains("licence")).toString
      )
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
      check(
        "cells flow row by row into two columns",
        at("Typed above").contains((0, 0)) && at("Volume").contains((0, 1)) && at("4.0").contains((1, 1)) &&
          at("short").contains((1, 2)),
        cells.toString
      )
      GtkInspect
        .findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Insert a row"))
        .headOption.foreach(GtkInspect.click)
      check(
        "a row inserted in the middle lands there",
        at("Inserted").contains((0, 1)) && at("a whole row").contains((1, 1)),
        cells.toString
      )
      check(
        "and every cell after it moves down a row",
        at("Volume").contains((0, 2)) && at("short").contains((1, 3)),
        cells.toString
      )
      GtkInspect
        .findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Remove the row"))
        .headOption.foreach(GtkInspect.click)
      check(
        "and removing it moves them back",
        at("Volume").contains((0, 1)) && at("Inserted").isEmpty,
        cells.toString
      )
    }

    // IconButton: a GtkButton showing a theme icon. For every curated icon: the button carries
    // the mapped name, the installed theme can actually draw it (not GTK's missing-image
    // icon), and the label reaches the tooltip. Then one is clicked for real.
    // Ours, by label: GtkCalendar (the DatePicker's popover) has icon buttons of its own for
    // its month and year arrows, and the first version of this count included them.
    val ourLabels = Icon.curated.map(_.toString).toSet + "Delete (disabled)"
    val iconButtons = GtkInspect.findAll(root)(w =>
      GtkInspect.isButton(w) && GtkInspect.buttonIconName(w).nonEmpty && ourLabels(GtkInspect.tooltip(w))
    )
    val byLabel = iconButtons.map(b => GtkInspect.tooltip(b) -> b).toMap
    check(
      "an icon button per curated icon, plus the disabled one",
      iconButtons.length == Icon.curated.size + 1,
      iconButtons.map(b => s"${GtkInspect.buttonIconName(b)}[${GtkInspect.tooltip(b)}]").toString
    )
    val wrong = Icon.curated.filterNot { i =>
      byLabel
        .get(i.toString).exists(b =>
          GtkInspect.buttonIconName(b) == GtkIcons.name(i) && GtkInspect.themeHasIcon(b, GtkIcons.name(i))
        )
    }
    check(
      "each shows its mapped icon, and the theme can draw every one",
      wrong.isEmpty,
      wrong.map(i => s"$i -> ${GtkIcons.name(i)}").toString
    )
    byLabel.get("Share").foreach(GtkInspect.click)
    check(
      "a tap reaches the app",
      GtkInspect.allTexts(root).contains("Tapped Share"),
      GtkInspect.allTexts(root).filter(_.startsWith("Tapped")).toString
    )

    // TabView: an AdwViewStack with an AdwViewSwitcherBar. The pages carry the titles and the
    // mapped icons; every tab's content is mounted; and a tab is switched the way a user does,
    // by clicking the switcher bar's own button.
    val viewStacks = GtkInspect.findAll(root)(GtkInspect.isViewStack)
    check(
      "one AdwViewStack, with a switcher bar beside it",
      viewStacks.length == 1 && GtkInspect.findAll(root)(GtkInspect.isViewSwitcherBar).length == 1,
      s"${viewStacks.length} viewStacks"
    )
    viewStacks.headOption.foreach { stack =>
      val pages = GtkInspect.stackPages(stack)
      check(
        "the pages are the tabs, in order, with their mapped icons",
        pages.map(_._1) == Gallery.tabTitles &&
          pages.map(_._2) == List(Icon.Home, Icon.Mail, Icon.Settings).map(GtkIcons.name),
        pages.map(p => s"${p._1}/${p._2}").toString
      )
      check(
        "every tab's content is mounted, not only the visible one's",
        List("home", "mail", "settings").forall(t => GtkInspect.allTexts(stack).contains(s"The $t tab's content")),
        GtkInspect.allTexts(stack).toString
      )
      // The gallery starts on Mail, index 1, not the stack's default first page.
      check(
        "the app's initial tab is the visible page, not the stack's default",
        pages
          .lift(1).exists(_._3 == GtkInspect.visiblePage(stack)) && GtkInspect.allTexts(root).contains("Showing tab 1"),
        GtkInspect.allTexts(root).filter(_.startsWith("Showing")).toString
      )
      val bar = GtkInspect.findAll(root)(GtkInspect.isViewSwitcherBar).head
      GtkInspect
        .findAll(bar)(w => GtkInspect.isToggleButton(w) && GtkInspect.allTexts(w).contains("Settings"))
        .headOption.foreach(GtkInspect.click)
      check(
        "clicking the bar's Settings button shows that page",
        pages.lift(2).exists(_._3 == GtkInspect.visiblePage(stack))
      )
      check(
        "and reaches the app, as its index",
        GtkInspect.allTexts(root).contains("Showing tab 2"),
        GtkInspect.allTexts(root).filter(_.startsWith("Showing")).toString
      )
      // AdwViewStack can only append, so a tab inserted mid-list is placed by taking the later
      // pages out and adding them back: the order, the titles and the content must all survive.
      GtkInspect
        .findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Add a tab in the middle"))
        .headOption.foreach(GtkInspect.click)
      val after = GtkInspect.stackPages(stack)
      check(
        "a tab inserted in the middle lands there, and the others keep their order",
        after.map(_._1) == List("Home", "Extra", "Mail", "Settings"),
        after.map(_._1).toString
      )
      check(
        "the user stays on Settings, and the app is told its new index",
        GtkInspect.visiblePage(stack) == after.last._3 && GtkInspect.allTexts(root).contains("Showing tab 3"),
        GtkInspect.allTexts(root).filter(_.startsWith("Showing")).toString
      )
      check(
        "and every tab still has its content",
        List("home", "extra", "mail", "settings").forall(t =>
          GtkInspect.allTexts(stack).contains(s"The $t tab's content")
        ),
        GtkInspect.allTexts(stack).toString
      )
      GtkInspect
        .findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Remove the extra tab"))
        .headOption.foreach(GtkInspect.click)
      check(
        "and removing it restores the three",
        GtkInspect.stackPages(stack).map(_._1) == Gallery.tabTitles,
        GtkInspect.stackPages(stack).map(_._1).toString
      )
    }

    check("a GtkProgressBar", GtkInspect.findAll(root)(GtkInspect.isProgressBar).nonEmpty)

    // Layout (review 2026-10-20, P1-4, P2-26): Grow along the parent's main axis.
    def labelled(t: String) = GtkInspect.findAll(root)(w => GtkInspect.isLabel(w) && GtkInspect.allTexts(w) == List(t))
    val spacers = GtkInspect.findAll(root)(w =>
      GtkInspect.children(w).isEmpty && !GtkInspect.isLabel(w) && GtkInspect.expands(w) != (false, false) &&
        GtkInspect.allTexts(w).isEmpty && GtkInspect.naturalWidth(w) == 0 && GtkInspect.naturalHeight(w) == 0
    )
    check(
      "a Spacer in a Row expands across, one in a Column expands down",
      spacers.map(GtkInspect.expands).toSet == Set((true, false), (false, true)),
      spacers.map(GtkInspect.expands).toString
    )
    check(
      "a grown label in a Row expands across only",
      labelled("grown").map(GtkInspect.expands) == List((true, false)),
      labelled("grown").map(GtkInspect.expands).toString
    )
    check(
      "Start, Center and End labels sit where they say",
      // The last of each: the Spinner section's button is labelled "Start" too.
      List("Start", "Center", "End").flatMap(t => labelled(t).lastOption.map(GtkInspect.labelXAlign)) ==
        List(0.0f, 0.5f, 1.0f),
      List("Start", "Center", "End").map(t => t -> labelled(t).map(GtkInspect.labelXAlign)).toString
    )
    // A scroller's padding insets its content: it is on the child, not the scrolled window.
    val hscroll =
      GtkInspect.findAll(root)(w => GtkInspect.findScroller(w).contains(w) && GtkInspect.allTexts(w).contains("col 0"))
    // The innermost: the whole screen is inside a vertical Scroll too.
    hscroll.lastOption.foreach { s =>
      val content = GtkInspect.findAll(s)(w => GtkInspect.marginStart(w) == 8)
      check(
        "a padded Scroll insets its content, and not itself",
        GtkInspect.marginStart(s) == 0 && content.nonEmpty,
        s"scroller ${GtkInspect.marginStart(s)}, content margins ${content.size}"
      )
    }
    check("an indeterminate ProgressBar keeps a pulse timer running", GtkInspect.liveTimers >= 1)

    // Image: one GtkPicture per fit, each with something to paint — the bytes-backed one
    // included, so a decode that produced nothing would show.
    val pictures = GtkInspect.findAll(root)(GtkInspect.isPicture).map(GtkInspect.pictureState)
    check(
      "the three content fits, each with a picture to paint",
      List("Contain", "Cover", "Fill").forall(f => pictures.contains((f, true))),
      pictures.toString
    )

    // ZStack is a GtkOverlay. Child order is paint order, so the badge being *last* is the
    // claim that it is drawn on top; and the stack's size is the largest child's, which is
    // what `measure` on every overlay buys — without it a GtkOverlay with no main child
    // measures 0x0 and the whole section collapses.
    val stacks = GtkInspect.findAll(root)(GtkInspect.isOverlay)
    check("two GtkOverlays for the two ZStacks", stacks.length == 2, s"${stacks.length} overlays")
    stacks.headOption.foreach { badge =>
      val kids = GtkInspect.children(badge)
      check(
        "the badge is the last child, so painted on top",
        kids.lastOption.exists(k => GtkInspect.allTexts(k).exists(_.endsWith(" rows"))),
        kids.map(GtkInspect.allTexts).toString
      )
      check(
        "the badge sits top-trailing",
        kids.lastOption.map(GtkInspect.placement).contains(("End", "Start")),
        kids.map(GtkInspect.placement).toString
      )
      val (w, h) = (GtkInspect.naturalWidth(badge), GtkInspect.naturalHeight(badge))
      val (cw, ch) = (kids.map(GtkInspect.naturalWidth).max, kids.map(GtkInspect.naturalHeight).max)
      check(
        "the stack is as large as its largest child",
        w == cw && h == ch && w > 0 && h > 0,
        s"stack ${w}x$h, largest child ${cw}x$ch"
      )
    }
    stacks.lift(1).foreach { centred =>
      val kids = GtkInspect.children(centred)
      check(
        "the spinner is over its content, centred",
        kids.length == 2 && GtkInspect.isSpinner(kids(1)) && GtkInspect.placement(kids(1)) == ("Center", "Center"),
        kids.map(GtkInspect.placement).toString
      )
    }

    // The ForEach rows each carry a context menu, so there is one popover per row.
    val popovers = GtkInspect.findAll(root)(GtkInspect.isPopover)
    check("one context-menu popover per row", popovers.length >= 3, s"${popovers.length} popovers")
    check(
      "the popovers hold the menu's items",
      popovers.exists(p => GtkInspect.allTexts(p).contains("Remove"))
    )

    // Presented widgets must *not* be in the tree until asked for — the property that makes
    // them presented rather than inserted.
    check(
      "no sheet or alert before it is asked for",
      !texts.contains("A presented container with a live subtree inside it.")
    )

    check(
      "a ZIO effect's result reached the screen through UiThread",
      texts.contains(Gallery.effectResult),
      texts.filter(t => t.startsWith("Load") || t == "Failed").toString
    )

    robustness(root)

    // The gallery styles more widgets than any other screen, so it is the best place to
    // notice a stylesheet GTK cannot parse (#30).
    check(
      "no CSS the renderer wrote failed to parse",
      GtkInspect.cssParseErrors == 0L,
      s"${GtkInspect.cssParseErrors} CSS parse errors"
    )

    presentedAndNav(root)
  }

  private def finish(): Unit =
    if failures == 0 then println(s"[gallery] ALL CHECKS PASSED")
    else println(s"[gallery] $failures CHECK(S) FAILED")

  /** Runs `next` once `cond` holds, letting the main loop run — layout, GtkListView binding the rows it shows, a page's
    * transition ending — for 20 ms between tries. A list materialises its rows when it is laid out, not when it is told
    * the data changed, and a popped page is freed when its animation finishes.
    */
  private def when(tries: Int)(cond: => Boolean)(next: => Unit): Unit =
    if tries <= 0 || cond then next
    else GtkApp.postToUiAfter(20)(() => when(tries - 1)(cond)(next))

  private def listTexts: List[String] =
    GtkInspect.allTexts(GtkApp.rootHandle).filter(t => t.startsWith("Row ") || t.startsWith("Renamed "))

  /** The 10 000-row LazyColumn after its data changes under it (review 2026-10-20, P1-3, P2-2). */
  private def lazyList(
    baseline:  Int,
    providers: Int
  ): Unit = {
    def click(label: String) =
      GtkInspect
        .findAll(GtkApp.rootHandle)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List(label))
        .headOption.foreach(GtkInspect.click)
    check("the list shows its first rows", listTexts.headOption.contains("Row 0"), listTexts.take(3).toString)
    check("and only a bounded set of them", listTexts.nonEmpty && listTexts.size < 1000, listTexts.size.toString)
    click("Delete the first row")
    when(50)(listTexts.headOption.contains("Row 1")) {
      check(
        "deleting row 0 re-binds the rows on screen: the first is now Row 1",
        listTexts.take(2) == List("Row 1", "Row 2"),
        listTexts.take(3).toString
      )
      click("Rename the first row")
      when(50)(listTexts.headOption.contains("Renamed 1")) {
        check(
          "an edit that keeps the count reaches the row on screen",
          listTexts.take(2) == List("Renamed 1", "Row 2"),
          listTexts.take(3).toString
        )
        Gallery.currentNav.foreach(_.pop())
        // The page is freed when its transition ends, and its content released after that.
        when(200)(GtkApp.containerDepth == 1 && GtkInspect.liveListBinders == 0) {
          check(
            "leaving the list releases its list-view callbacks",
            GtkInspect.liveListBinders == 0,
            s"${GtkInspect.liveListBinders} still held"
          )
          // Not equal: the root page the reset replaced is released in the meantime too,
          // which is the same fix seen from the other side.
          // The reset replaced the catalogue with an identical one, and the old one's themed
          // widgets have gone: one stylesheet each, as many as before — not twice as many.
          check(
            "the replaced catalogue's CSS providers were removed from the display",
            GtkInspect.liveCssProviders == providers && providers > 0,
            s"$providers before the reset, ${GtkInspect.liveCssProviders} after"
          )
          check(
            "and the page's other callbacks with it: no more are held than before it was pushed",
            GtkInspect.liveCallbacks <= baseline,
            s"$baseline before, ${GtkInspect.liveCallbacks} after"
          )
          finish()
        }
      }
    }
  }

  /** Runs `then` once the Effects section's ZIO result has reached the screen, re-posting itself so the main loop keeps
    * draining posts in between — the result arrives through `UiThread`, as a post of its own. After `tries` it runs
    * anyway, and the check below reports what it saw.
    */
  private def afterEffect(tries: Int)(next: => Unit): Unit =
    if tries <= 0 || GtkInspect.allTexts(GtkApp.rootHandle).contains(Gallery.effectResult) then next
    else GtkApp.postToUi(() => afterEffect(tries - 1)(next))

  /** A sheet closed by its own window's close button, then the navigation stack reset from one page deep (review
    * 2026-10-20, P0-4 and P1-9). Last, because the reset remounts the catalogue: `root` is gone afterwards.
    */
  private def presentedAndNav(root: Ptr[GtkWidget]): Unit = {
    def button(label: String) =
      GtkInspect.findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List(label)).headOption
    val baseline = GtkInspect.liveCallbacks
    button("Open sheet").foreach(GtkInspect.click)
    val sheets = GtkInspect.windowsTitled("A sheet")
    check("opening the sheet shows a window titled after it", sheets.length == 1, sheets.length.toString)
    sheets.headOption.foreach { win =>
      // The window manager's close button: GTK emits close-request on the window. The
      // renderer vetoes it and reports it; the gallery's OnDismiss unmounts the sheet, and
      // the unmount destroys the window.
      GtkInspect.requestClose(win)
      check("the close button takes the sheet down", GtkInspect.windowsTitled("A sheet").isEmpty)
      check(
        "and the app was told: the sheet's own state is closed",
        button("Open sheet").nonEmpty && !GtkInspect.allTexts(root).contains("Still bound")
      )
      check(
        "and every callback the sheet registered is released",
        GtkInspect.liveCallbacks == baseline,
        s"$baseline before, ${GtkInspect.liveCallbacks} after"
      )
    }
    // Open and close it again, through the app this time, so the same window path runs twice.
    button("Open sheet").foreach(GtkInspect.click)
    GtkInspect
      .windowsTitled("A sheet").headOption
      .foreach(w =>
        GtkInspect
          .findAll(w)(c => GtkInspect.isButton(c) && GtkInspect.allTexts(c) == List("Close"))
          .headOption.foreach(GtkInspect.click)
      )
    check("a sheet reopens and closes from inside", GtkInspect.windowsTitled("A sheet").isEmpty)

    // The page releases below wait for pages to be freed, which happens when their transition
    // ends; without transitions that is at once, whatever the compositor is doing.
    GtkApp.animateTransitions(false)
    val providersBefore = GtkInspect.liveCssProviders

    // Reset from one page deep. AdwNavigationView will not pop its root, so the old root
    // used to stay underneath the new one.
    check("the stack starts one page deep", GtkApp.containerDepth == 1, GtkApp.containerDepth.toString)
    Gallery.currentNav.foreach(_.reset(Gallery.Route.Catalogue))
    check(
      "resetting replaces the root rather than stacking a second one on it",
      GtkApp.containerDepth == 1 && GtkApp.pageHandles.length == 1,
      s"container ${GtkApp.containerDepth}, live ${GtkApp.pageHandles.length}"
    )
    check(
      "and the new root is the one on screen",
      GtkApp.visiblePageTitle == "Thicket gallery",
      GtkApp.visiblePageTitle
    )
    val beforeList = GtkInspect.liveCallbacks
    Gallery.currentNav.foreach(_.push(Gallery.Route.Big))
    check(
      "a push after the reset goes on top of it",
      GtkApp.containerDepth == 2,
      GtkApp.containerDepth.toString
    )
    when(50)(listTexts.nonEmpty) {
      check("the mounted list holds bind, unbind and teardown callbacks", GtkInspect.liveListBinders == 3)
      lazyList(beforeList, providersBefore)
    }
  }

  /** Host → Scala entry points under stress (review 2026-10-20, P0-2 and P0-3). */
  private def robustness(root: Ptr[GtkWidget]): Unit = {
    // The self-test itself runs in a `postToUi` callback, one level into Scala.
    check("the self-test runs one level into Scala", GcState.scalaDepth == 1, GcState.scalaDepth.toString)

    // A setter that emits its signal synchronously runs the renderer's trampoline inside
    // this frame. Before `guarded` counted depth, the inner exit marked the thread Unmanaged
    // with this frame still running, so a collection on another thread skipped its roots.
    // Here a worker collects continuously while the main thread drives that nested path and
    // allocates; every string it keeps must come back intact.
    val entry = GtkInspect.findAll(root)(GtkInspect.isEntry).find(e => GtkInspect.entryVisible(e))
    check("there is a plain entry to drive", entry.nonEmpty)
    entry.foreach { e =>
      val stop = AtomicBoolean(false)
      val collector = Thread(() =>
        while !stop.get() do {
          val _ = Array.fill(2048)(new Array[Byte](64))
          System.gc()
        }
      )
      collector.start()
      // Time-bound rather than a fixed count: how long one collection takes depends on the
      // heap, and a fixed count that once took a few seconds took a minute after the gallery
      // gained a 10 000-row model. Three seconds is long enough to catch the old behaviour
      // every time (signal 11 in each falsification run) and short enough to keep the step cheap.
      val deadline = System.nanoTime() + 3_000_000_000L
      val kept = mutable.ArrayBuffer.empty[String]
      var deepest = 0
      var unmanaged = 0
      var i = 0
      while System.nanoTime() < deadline do {
        GtkInspect.setEntryText(e, s"gc $i")
        deepest = deepest max GcState.scalaDepth
        if !GcState.managed then unmanaged += 1
        kept += ("kept " * 8) + i
        i += 1
      }
      stop.set(true)
      collector.join()
      println(s"[gallery] info the stress step made $i nested calls in 3 s")
      check("a nested signal leaves the thread in Scala", GcState.scalaDepth == 1, GcState.scalaDepth.toString)
      check("and never drops it out", deepest == 1, deepest.toString)
      // The deterministic half: what the GC was told. The stress around it is a smoke test —
      // whether a wrong state corrupts anything within three seconds is a matter of timing.
      check(
        "the GC was never told the thread left Scala while Scala was still running on it",
        unmanaged == 0,
        s"$unmanaged of $i nested calls left it Unmanaged"
      )
      val corrupt = kept.zipWithIndex.count(
        (
          s,
          i
        ) => s != ("kept " * 8) + i
      )
      check(s"$i strings kept across collections on another thread survive", corrupt == 0, s"$corrupt corrupt")
      check(
        "and the last write reached the app",
        GtkInspect.allTexts(root).contains(s"You typed: gc ${i - 1}"),
        GtkInspect.allTexts(root).filter(_.startsWith("You typed")).toString
      )
      GtkInspect.setEntryText(e, "")
    }

    // A handler that throws: reported, not unwound through GTK, and the app carries on.
    val reported = mutable.ArrayBuffer.empty[Throwable]
    val previous = ErrorSink.installed
    ErrorSink.install(e => reported += e)
    GtkInspect
      .findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Throw from a handler"))
      .headOption.foreach(GtkInspect.click)
    ErrorSink.install(previous)
    check(
      "a handler's exception reaches ErrorSink",
      reported.map(_.getMessage).toList == List(Gallery.deliberate),
      reported.toString
    )
    check(
      "and the thread is back in the loop's hands afterwards",
      GcState.scalaDepth == 1,
      GcState.scalaDepth.toString
    )
    GtkInspect
      .findAll(root)(w => GtkInspect.isButton(w) && GtkInspect.allTexts(w) == List("Select first"))
      .headOption.foreach(GtkInspect.click)
    check(
      "and the next click still works",
      GtkInspect.allTexts(root).contains("Showing a Day"),
      GtkInspect.allTexts(root).filter(_.startsWith("Showing")).toString
    )
  }

  def main(args: Array[String]): Unit = {
    val _ = GtkApp.run("dev.thicket.gallery", width = 520, height = 760) {
      if sys.env.contains("THICKET_GALLERY_SELFTEST") then GtkApp.postToUi(() => afterEffect(200)(selfTest()))
      Gallery.app()
    }
  }

}
