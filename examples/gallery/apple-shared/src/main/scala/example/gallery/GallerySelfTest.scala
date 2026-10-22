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

import thicket.renderer.{CalendarDate, Icon, Prop, WidgetKind}
import thicket.renderer.apple.{AppleApp, AppleIcons, AppleInspect}

/** The gallery's conformance check on AppKit and UIKit — the same questions the GTK host asks, through `AppleInspect`
  * instead of `GtkInspect`.
  *
  * Deliberately the *same* checks, not Apple-flavoured ones. The gallery's whole claim is that one screen renders
  * natively on every platform, and that claim is only tested if each platform is asked the same thing. Where a check
  * has to differ it is because the platform genuinely differs, and that is noted at the check.
  *
  * Shared by both Apple hosts: one screen, one set of checks, two entry shapes.
  */
object GallerySelfTest {

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

  def run(): Unit = {
    val root = AppleApp.rootHandle
    val all = AppleInspect.all(root)
    val texts = AppleInspect.allTexts(root)

    List(
      "Label — every text role",
      "Button — roles, and one disabled",
      "TextField and SecureField",
      "Checkbox and Toggle",
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
      "Presented — Sheet and Alert are shown over the app, never inserted"
    ).foreach(h => check(s"section is on screen: $h", texts.contains(h)))

    check(
      "labels in every role",
      texts.contains("Title") && texts.contains("Body") &&
        texts.contains("Caption") && texts.contains("Secondary")
    )
    check("a disabled button is on screen, not just a reachable state", texts.contains("Disabled"))

    // Two text fields, exactly one of which hides its characters. `isSecure` is the only
    // observable difference between a TextField and a SecureField.
    val secure = all.count(AppleInspect.isSecure)
    check("exactly one field hides its characters", secure == 1, s"$secure secure fields")

    // The slider drives the progress bar, so a progress value in (0, 1) proves both the
    // bar exists and the binding ran — the initial volume is 4 of 11.
    val progressed = all.map(AppleInspect.progress).filter(p => p > 0.0 && p < 1.0)
    check(
      "a ProgressBar is bound to the Slider above it",
      progressed.nonEmpty,
      s"progress values: $progressed"
    )
    val values = all.map(AppleInspect.value).filter(_ > 0.0)
    check("a Slider carries its value", values.contains(4.0), s"values: $values")

    // One context menu per ForEach row. On Apple this is NSView.menu or a
    // UIContextMenuInteraction rather than a popover widget, so the question is "which
    // views have menu items", not "how many popovers are in the tree".
    val withMenus = all.filter(h => AppleInspect.menuItems(h).nonEmpty)
    check("one context menu per row", withMenus.length >= 3, s"${withMenus.length} views with menus")
    check(
      "the menus hold the menu's items",
      withMenus.exists(h => AppleInspect.menuItems(h).contains("Remove")),
      withMenus.map(AppleInspect.menuItems).toString
    )

    // The Image section's heading being on screen says nothing about the pictures: an image
    // view whose file did not load is an empty frame of the right size. So ask each image
    // view whether it holds an image. The source is a repo-relative path, which a binary run
    // from the repo resolves and an app bundle may not (#31).
    //
    // Only the framework's own Image widgets: a UIKit switch, slider or button contains
    // image views of its own, and an unfiltered walk counted ten where the screen has two.
    val imageWidgets = all.filter(h => AppleApp.renderer.kindOf(h).contains(WidgetKind.Image))
    val images = imageWidgets.flatMap(h => AppleInspect.hasImage(h))
    check(
      "every Image shows its picture",
      images.nonEmpty && images.forall(identity),
      s"${images.count(identity)} of ${images.size} Image widgets hold an image"
    )

    newControls(all)

    // Presented widgets must not be in the tree until asked for.
    check(
      "nothing is presented before it is asked for",
      AppleInspect.presentedCount == 0,
      s"${AppleInspect.presentedCount} presented"
    )

    if failures == 0 then println("[gallery] ALL CHECKS PASSED")
    else println(s"[gallery] $failures CHECK(S) FAILED")
  }

  private type H = thicket.renderer.apple.Shim.Handle

  /** The button labelled `label` in the same section as `control`: the gallery has two "Clear"s. The nearest ancestor
    * holding such a button is the last one in a pre-order walk, which visits ancestors before descendants.
    */
  private def buttonNear(
    all:     List[H],
    control: H,
    label:   String
  ): Option[H] = {
    def button(h: H) =
      AppleInspect
        .all(h).find(b =>
          AppleApp.renderer.kindOf(b).contains(WidgetKind.Button) && AppleInspect.text(b).contains(label)
        )
    all.filter(h => h != control && AppleInspect.all(h).contains(control)).flatMap(button).lastOption
  }

  /** #77 (review 2026-10-20 P1-10, P1-11; D13). The Picker keeps its selection — an index — when its options are
    * replaced, reports no choice the user did not make, and shows a blank for `-1`. What it shows is read from what the
    * control draws (`shownOption`), not from what the renderer told it.
    */
  private def pickerSelection(
    all: List[H],
    p:   H
  ): Unit = {
    def texts = AppleInspect.allTexts(AppleApp.rootHandle)
    def reports = texts.find(_.startsWith("The picker reported")).getOrElse("")
    def chose = texts.filter(t => t.startsWith("Chose") || t == "(nothing selected)").toString
    def shows =
      s"shows '${AppleInspect.shownOption(p)}', selected ${AppleInspect.selected(p)}, options ${AppleInspect.options(p)}"
    def press(label: String): Unit =
      buttonNear(all, p, label) match {
        case Some(b) => AppleInspect.click(b)
        case None    => check(s"the Picker's '$label' button is on screen", false)
      }
    val reportsBefore = reports
    press("Select last")
    press("Swap the options")
    check(
      "swapping the Picker's options keeps its selection",
      AppleInspect.options(p) == Gallery.vegetables && AppleInspect.shownOption(p) == Gallery.vegetables.last &&
        AppleInspect.selected(p) == Gallery.vegetables.length - 1,
      shows
    )
    check("and the app agrees", texts.contains(s"Chose ${Gallery.vegetables.last} (index 2)"), chose)
    val (_, _, chosenW, chosenH) = AppleInspect.frame(p)
    press("Clear")
    check(
      "Selected(-1) shows a blank Picker, not the first option (D13)",
      AppleInspect.shownOption(p) == "" && AppleInspect.selected(p) == -1,
      shows
    )
    // A blank title must not take the control with it: a pop-up the user cannot see or tap is
    // a selection they cannot make again.
    val (_, _, blankW, blankH) = AppleInspect.frame(p)
    check(
      "and the blank Picker is still there to tap",
      blankW >= 20 && blankH >= 20,
      f"$blankW%.0fx$blankH%.0f blank, $chosenW%.0fx$chosenH%.0f showing ${Gallery.vegetables.last}"
    )
    println(f"[gallery]   picker frame: $chosenW%.0fx$chosenH%.0f showing an option, $blankW%.0fx$blankH%.0f blank")
    press("Swap the options")
    check(
      "and swapping its options with nothing selected selects nothing",
      AppleInspect.options(p) == Gallery.fruit && AppleInspect.shownOption(p) == "" && AppleInspect.selected(p) == -1,
      shows
    )
    check("and the app agrees", texts.contains("(nothing selected)"), chose)
    check(
      "and no swap or app write reported a choice the user did not make",
      reports == reportsBefore,
      s"$reportsBefore -> $reports"
    )
  }

  /** The SegmentedControl's half of #77. The gallery has no button that replaces its options, so the renderer is given
    * new ones directly — the patch the reconciler would send — and then the originals back.
    */
  private def segmentedSelection(
    all: List[H],
    s:   H
  ): Unit = {
    def texts = AppleInspect.allTexts(AppleApp.rootHandle)
    def showing = texts.filter(t => t.startsWith("Showing a ") || t == "(no span)")
    def shows =
      s"shows '${AppleInspect.shownOption(s)}', selected ${AppleInspect.selected(s)}, options ${AppleInspect.options(s)}"
    val before = AppleInspect.selected(s)
    val appBefore = showing
    val renamed = Gallery.spans.map(_.toUpperCase)
    AppleApp.renderer.update(s, Seq(Prop.Options(renamed)))
    check(
      "new segments keep the selected index",
      AppleInspect.options(s) == renamed && AppleInspect.selected(s) == before &&
        AppleInspect.shownOption(s) == renamed.lift(before).getOrElse(""),
      shows
    )
    AppleApp.renderer.update(s, Seq(Prop.Options(Gallery.spans)))
    check(
      "and so do the old ones, put back",
      AppleInspect.selected(s) == before && AppleInspect.shownOption(s) == Gallery.spans.lift(before).getOrElse(""),
      shows
    )
    check("and neither change reported a choice", showing == appBefore, s"$appBefore -> $showing")
    buttonNear(all, s, "Clear") match {
      case Some(b) =>
        AppleInspect.click(b)
        check(
          "Selected(-1) leaves no segment chosen (D13)",
          AppleInspect.selected(s) == -1 && AppleInspect.shownOption(s) == "",
          shows
        )
        check("and the app agrees", showing == List("(no span)"), showing.toString)
      case None => check("the SegmentedControl's 'Clear' button is on screen", false)
    }
  }

  /** The questions the GTK gallery asks of the controls #45 and #46 added, asked of AppKit and UIKit.
    *
    * Each control is found through the renderer (which handles are the framework's own Picker, ZStack, …) and every
    * answer is read from the platform's control, so a renderer that drew the right widget and bound it wrongly fails.
    * Choices are made through the control's own action, the path a user's click takes; where UIKit offers no way to do
    * that from code — a `UIAction` cannot be performed — the check says so instead of passing.
    */
  private def newControls(all: List[thicket.renderer.apple.Shim.Handle]): Unit = {
    // D9: under a self-test an unbuilt kind throws, so this is reached with placeholders only when
    // THICKET_ALLOW_UNBUILT was set for a local run. Say so, so "all passed" is not misread.
    val stand = AppleApp.renderer.placeholderHandles.flatMap(AppleApp.renderer.kindOf).map(_.toString).toList.sorted
    if stand.nonEmpty then
      println(
        s"[gallery]   (placeholders, allowed by THICKET_ALLOW_UNBUILT: ${stand.distinct.mkString(", ")} not built)"
      )
    def ofKind(k: WidgetKind) = all.filter(h => AppleApp.renderer.kindOf(h).contains(k))
    def texts = AppleInspect.allTexts(AppleApp.rootHandle)

    // --- Picker ---
    val pickers = ofKind(WidgetKind.Picker)
    check("one Picker", pickers.length == 1, pickers.length.toString)
    pickers.headOption.foreach { p =>
      check(
        "the Picker offers its options",
        AppleInspect.options(p) == Gallery.fruit,
        AppleInspect.options(p).toString
      )
      check("and shows the model's selection", AppleInspect.selected(p) == 1, AppleInspect.selected(p).toString)
      if AppleInspect.choose(p, Gallery.fruit.length - 1) then
        check(
          "choosing an option reaches the app",
          texts.exists(_.startsWith(s"Chose ${Gallery.fruit.last}")),
          texts.filter(_.startsWith("Chose")).toString
        )
      else println("[gallery]   (this platform offers no way to choose a Picker option from code; not exercised)")
      pickerSelection(all, p)
    }

    // --- DatePicker ---
    val datePickers = ofKind(WidgetKind.DatePicker)
    check("one DatePicker", datePickers.length == 1, datePickers.length.toString)
    datePickers.headOption.foreach { d =>
      check(
        "the DatePicker shows the model's date, leap day and all",
        AppleInspect.date(d) == Gallery.firstDue.toEpochDay,
        CalendarDate.fromEpochDay(AppleInspect.date(d)).toString
      )
      val target = CalendarDate(2029, 1, 31)
      check("a day can be chosen", AppleInspect.chooseDate(d, target.toEpochDay))
      check(
        "choosing a day reaches the app",
        texts.contains("Due 2029-01-31"),
        texts.filter(_.startsWith("Due")).toString
      )
      check(
        "and the control holds it",
        AppleInspect.date(d) == target.toEpochDay,
        CalendarDate.fromEpochDay(AppleInspect.date(d)).toString
      )
    }

    // --- SegmentedControl ---
    val segmented = ofKind(WidgetKind.SegmentedControl)
    check("one SegmentedControl", segmented.length == 1, segmented.length.toString)
    segmented.headOption.foreach { s =>
      check(
        "a segment per option, in order",
        AppleInspect.options(s) == Gallery.spans,
        AppleInspect.options(s).toString
      )
      check(
        "the model's selection is the selected segment",
        AppleInspect.selected(s) == 1,
        AppleInspect.selected(s).toString
      )
      check("a segment can be chosen", AppleInspect.choose(s, 2))
      check("choosing one selects it", AppleInspect.selected(s) == 2, AppleInspect.selected(s).toString)
      check(
        "and reaches the app",
        texts.contains("Showing a Month"),
        texts.filter(_.startsWith("Showing")).toString
      )
      segmentedSelection(all, s)
    }

    // --- Link: clicked for real, with the opener recording instead of launching a browser ---
    val opened = AppleInspect.recordOpenedUrls()
    def linkTitled(t: String) = ofKind(WidgetKind.Button).find(h => AppleInspect.text(h).contains(t))
    linkTitled("thicket on GitHub").foreach { l =>
      AppleInspect.click(l)
      check("clicking a link opens its URL", opened() == List(Gallery.repoUrl), opened().toString)
    }
    linkTitled("Apache License 2.0").foreach { l =>
      AppleInspect.click(l)
      check(
        "a link with a tap opens its URL",
        opened().lastOption.contains(Gallery.licenceUrl),
        opened().toString
      )
      check(
        "and runs the tap as well",
        texts.contains("The licence link was tapped 1 times"),
        texts.filter(_.contains("licence")).toString
      )
    }
    check("both links were found and clicked", opened().length == 2, opened().toString)
    AppleInspect.stopRecordingUrls()

    // --- ZStack: paint order is subview order; placement and size read as frames ---
    val stacks = ofKind(WidgetKind.ZStack)
    check("two ZStacks", stacks.length == 2, stacks.length.toString)
    def near(
      a: Double,
      b: Double
    ) = math.abs(a - b) <= 1.0
    stacks.headOption.foreach { badge =>
      val kids = AppleInspect.children(badge)
      check(
        "the badge is the last child, so drawn on top",
        kids.lastOption.exists(k => AppleInspect.allTexts(k).exists(_.endsWith(" rows"))),
        kids.map(AppleInspect.allTexts).toString
      )
      val (sx, sy, sw, sh) = AppleInspect.frame(badge)
      kids.lastOption.foreach { b =>
        val (bx, by, bw, _) = AppleInspect.frame(b)
        check(
          "the badge sits top-trailing",
          near(bx + bw, sx + sw) && near(by, sy),
          s"badge ($bx,$by,$bw) in stack ($sx,$sy,$sw,$sh)"
        )
      }
      val (cw, ch) = (kids.map(k => AppleInspect.frame(k)._3).max, kids.map(k => AppleInspect.frame(k)._4).max)
      check(
        "the stack is as large as its largest child",
        near(sw, cw) && near(sh, ch) && sw > 0 && sh > 0,
        s"stack ${sw}x$sh, largest child ${cw}x$ch"
      )
    }
    stacks.lift(1).foreach { centred =>
      val kids = AppleInspect.children(centred)
      val (sx, sy, sw, sh) = AppleInspect.frame(centred)
      val spinnerCentred = kids.lift(1).exists { k =>
        val (x, y, w, h) = AppleInspect.frame(k)
        AppleApp.renderer.kindOf(k).contains(WidgetKind.ActivityIndicator) &&
        near(x + w / 2, sx + sw / 2) && near(y + h / 2, sy + sh / 2)
      }
      check(
        "the spinner is over its content, centred",
        kids.length == 2 && spinnerCentred,
        kids.map(k => AppleInspect.frame(k)).toString
      )
    }

    // --- Grid: the questions GtkGrid answers, asked of NSGridView's rows and columns on AppKit
    // and of the construction on UIKit — and, on both, of the frames, which are the
    // platform's own layout whichever builds it.
    // The form grid, by its first cell: the IconButton section is a Grid too.
    val grids = ofKind(WidgetKind.Grid).filter(g => AppleInspect.allTexts(g).contains("Typed above"))
    check("one form Grid", grids.length == 1, grids.length.toString)
    grids.headOption.foreach { grid =>
      def cells = AppleInspect.children(grid).map(c => (AppleInspect.allTexts(c).mkString, c))
      def at(text: String) =
        cells
          .find(_._1 == text).flatMap(
            (
              _,
              c
            ) => AppleInspect.gridCell(grid, c)
          )
      def placement =
        cells
          .map(
            (
              t,
              c
            ) => t -> AppleInspect.gridCell(grid, c)
          ).toString
      // Each column starts where the one before it ends, at its widest cell, plus the 8 of
      // spacing; each row likewise at its tallest cell. Every cell's leading and top edge is
      // its column's and its row's. Within a point, as the ZStack's checks are.
      def laidOut: (Boolean, String) = {
        val placed = cells.flatMap(
          (
            _,
            c
          ) => AppleInspect.gridCell(grid, c).map(_ -> AppleInspect.frame(c))
        )
        val byCol = placed.groupBy(_._1._2).toList.sortBy(_._1).map(_._2.map(_._2))
        val byRow = placed.groupBy(_._1._1).toList.sortBy(_._1).map(_._2.map(_._2))
        def near(
          a: Double,
          b: Double
        ) = math.abs(a - b) <= 1.0
        val colsLine = byCol.forall(fs => fs.forall(f => near(f._1, fs.head._1)))
        val rowsLine = byRow.forall(fs => fs.forall(f => near(f._2, fs.head._2)))
        val colsStep = byCol.sliding(2).forall {
          case List(a, b) => near(b.head._1, a.head._1 + a.map(_._3).max + 8)
          case _          => true
        }
        val rowsStep = byRow.sliding(2).forall {
          case List(a, b) => near(b.head._2, a.head._2 + a.map(_._4).max + 8)
          case _          => true
        }
        (
          colsLine && rowsLine && colsStep && rowsStep,
          s"cols line=$colsLine step=$colsStep rows line=$rowsLine step=$rowsStep " +
            byCol.map(_.map(f => f"(${f._1}%.0f,${f._2}%.0f ${f._3}%.0fx${f._4}%.0f)")).toString
        )
      }
      def button(t: String) = ofKind(WidgetKind.Button).find(h => AppleInspect.text(h).contains(t))

      check(
        "cells flow row by row into two columns",
        at("Typed above").contains((0, 0)) && at("Volume").contains((1, 0)) &&
          at("A much longer label than the others").contains((2, 0)) && at("short").contains((2, 1)),
        placement
      )
      check(
        "the bound cell sits beside its label",
        cells.exists(
          (
            t,
            c
          ) => t.matches("""\d+\.\d""") && AppleInspect.gridCell(grid, c).contains((1, 1))
        ),
        placement
      )
      val (ok, how) = laidOut
      check("columns as wide as their widest cell, rows as tall as their tallest, 8 apart", ok, how)
      println(s"[gallery]   grid frames: $how")

      button("Insert a row").foreach(AppleInspect.click)
      check(
        "a row inserted in the middle lands there",
        at("Inserted").contains((1, 0)) && at("a whole row").contains((1, 1)),
        placement
      )
      check(
        "and every cell after it moves down a row",
        at("Volume").contains((2, 0)) && at("short").contains((3, 1)),
        placement
      )
      val (ok2, how2) = laidOut
      check("and the layout follows", ok2, how2)

      button("Remove the row").foreach(AppleInspect.click)
      check("and removing it moves them back", at("Volume").contains((1, 0)) && at("Inserted").isEmpty, placement)
      val (ok3, how3) = laidOut
      check("and so does the layout", ok3, how3)
    }

    // --- IconButton: the questions GTK asks of its theme icons, asked of the real buttons.
    // Every mapped name is first asked of the SDK, because a symbol it lacks draws nothing
    // and would otherwise pass as a blank button. Then each button: its symbol, its label as
    // VoiceOver's and as the tooltip, and the pixels the platform actually paints for it.
    val missing = Icon.curated.filterNot(i => AppleInspect.symbolExists(AppleIcons.name(i)))
    check(
      "the SDK has an SF Symbol for every curated icon",
      missing.isEmpty,
      missing.map(i => s"$i -> ${AppleIcons.name(i)}").toString
    )
    val ourLabels = Icon.curated.map(_.toString).toSet + "Delete (disabled)"
    val iconButtons = ofKind(WidgetKind.IconButton).filter(b => AppleInspect.accessibleLabel(b).exists(ourLabels))
    val byLabel = iconButtons.flatMap(b => AppleInspect.accessibleLabel(b).map(_ -> b)).toMap
    check(
      "an icon button per curated icon, plus the disabled one",
      iconButtons.length == Icon.curated.size + 1,
      iconButtons.map(b => s"${AppleInspect.symbol(b)}[${AppleInspect.accessibleLabel(b)}]").toString
    )
    val wrongSymbol = Icon.curated.filterNot(i =>
      byLabel.get(i.toString).exists(b => AppleInspect.symbol(b).contains(AppleIcons.name(i)))
    )
    check(
      "each shows its mapped symbol",
      wrongSymbol.isEmpty,
      wrongSymbol.map(i => s"$i -> ${byLabel.get(i.toString).flatMap(AppleInspect.symbol)}").toString
    )
    val painted =
      iconButtons.map(b => AppleInspect.accessibleLabel(b).getOrElse("?") -> AppleInspect.paintedFraction(b))
    // A glyph covers part of its button, never none of it and never all of it.
    check(
      "and the platform paints each one",
      painted.forall(
        (
          _,
          f
        ) => f > 0.02 && f < 0.9
      ),
      painted
        .map(
          (
            l,
            f
          ) => f"$l ${f * 100}%.0f%%"
        ).mkString(", ")
    )
    println(
      painted
        .map(
          (
            _,
            f
          ) => f * 100
        ).sorted match {
        case fs if fs.nonEmpty => f"[gallery]   icon pixels painted: ${fs.head}%.1f%%–${fs.last}%.1f%% of each button"
        case _                 => "[gallery]   icon pixels painted: none measured"
      }
    )
    val tipless = iconButtons.filterNot(b => AppleInspect.tooltip(b) == AppleInspect.accessibleLabel(b))
    check(
      "the label is also the tooltip",
      tipless.isEmpty,
      tipless.map(b => s"${AppleInspect.accessibleLabel(b)} / ${AppleInspect.tooltip(b)}").toString
    )
    byLabel.get("Share").foreach(AppleInspect.click)
    check(
      "a tap reaches the app",
      texts.contains("Tapped Share"),
      texts.filter(_.startsWith("Tapped")).toString
    )

    tabView(ofKind(WidgetKind.TabView), ofKind)
  }

  /** The questions GTK asks of its `AdwViewStack` (#69), asked of `NSTabViewController` and `UITabBarController`.
    *
    * The bar's items are read from the bar the controller draws — AppKit's segmented control, UIKit's `UITabBar` — the
    * pages from the controller's own items, and the page on screen from the controller and the window. A tab is
    * switched through the bar, the way a user does. Where GTK reads a page's content from the stack, this reads it from
    * the item's view: a controller keeps only the selected page in the window, and the others alive but out of it,
    * which is what "kept alive" means on Apple.
    */
  private def tabView(
    tabViews: List[thicket.renderer.apple.Shim.Handle],
    ofKind:   WidgetKind => List[thicket.renderer.apple.Shim.Handle]
  ): Unit = {
    def texts = AppleInspect.allTexts(AppleApp.rootHandle)
    def showing = texts.filter(_.startsWith("Showing")).toString
    check(
      "one TabView, with the platform's tab bar",
      tabViews.length == 1 && tabViews.forall(t => AppleInspect.options(t).nonEmpty),
      s"${tabViews.length} TabViews, bars: ${tabViews.map(AppleInspect.options)}"
    )
    tabViews.headOption.foreach { tv =>
      def pages = AppleInspect.children(tv)
      def bar = AppleInspect.options(tv)
      def contentOf(t: String) = s"The ${t.toLowerCase} tab's content"
      def pagesHold(titles: Seq[String]) =
        pages.length == titles.length && titles
          .zip(pages).forall(
            (
              t,
              p
            ) => AppleInspect.allTexts(p) == List(contentOf(t))
          )
      def described = s"bar=$bar symbols=${AppleInspect.optionSymbols(tv)} pages=${pages.map(AppleInspect.allTexts)}"
      def shownIndex = AppleInspect.tabShown(tv).map(pages.indexOf).getOrElse(-1)
      def button(t: String) = ofKind(WidgetKind.Button).find(h => AppleInspect.text(h).contains(t))

      check(
        "the bar's items are the tabs, in order, with their mapped symbols",
        bar == Gallery.tabTitles &&
          AppleInspect.optionSymbols(tv) == List(Icon.Home, Icon.Mail, Icon.Settings).map(i =>
            Some(AppleIcons.name(i))
          ),
        described
      )
      check(
        "every tab's content is mounted, not only the visible one's",
        pagesHold(Gallery.tabTitles),
        described
      )
      // The gallery starts on Mail, index 1, not the controller's default first item.
      check(
        "the app's initial tab is the visible page, not the controller's default",
        shownIndex == 1 && AppleInspect.selected(tv) == 1 && texts.contains("Showing tab 1"),
        s"shown=$shownIndex selected=${AppleInspect.selected(tv)} $showing"
      )
      // Laid out, not only present: the page on screen holds its content, inside the TabView,
      // which is tall enough for the bar and the page. A controller's page is placed by frame,
      // so nothing in it would size the TabView unless the shim does.
      def framed: (Boolean, String) = {
        val (tx, ty, tw, th) = AppleInspect.frame(tv)
        AppleInspect.tabShown(tv).flatMap(AppleInspect.children(_).headOption) match {
          case Some(c) =>
            val (cx, cy, cw, ch) = AppleInspect.frame(c)
            val inside = cx >= tx - 0.5 && cy >= ty - 0.5 && cx + cw <= tx + tw + 0.5 && cy + ch <= ty + th + 0.5
            (
              inside && cw > 0 && ch > 0 && th >= ch + 20,
              f"tabView ($tx%.0f,$ty%.0f $tw%.0fx$th%.0f) content ($cx%.0f,$cy%.0f $cw%.0fx$ch%.0f)"
            )
          case None => (false, f"tabView ($tx%.0f,$ty%.0f $tw%.0fx$th%.0f), no page on screen")
        }
      }
      val (laidOut, frames) = framed
      check("the page on screen is laid out inside the TabView, below or above its bar", laidOut, frames)
      println(s"[gallery]   tab frames: $frames")

      check("the bar's Settings item can be clicked", AppleInspect.choose(tv, bar.indexOf("Settings")))
      check(
        "clicking the bar's Settings item shows that page",
        shownIndex == 2 && AppleInspect.selected(tv) == 2,
        s"shown=$shownIndex selected=${AppleInspect.selected(tv)}"
      )
      check("and reaches the app, as its index", texts.contains("Showing tab 2"), showing)

      button("Add a tab in the middle").foreach(AppleInspect.click)
      check(
        "a tab inserted in the middle lands there, and the others keep their order",
        bar == List("Home", "Extra", "Mail", "Settings"),
        described
      )
      check(
        "the user stays on Settings, and the app is told its new index",
        shownIndex == 3 && texts.contains("Showing tab 3"),
        s"shown=$shownIndex $showing"
      )
      check("and every tab still has its content", pagesHold(List("Home", "Extra", "Mail", "Settings")), described)

      button("Remove the extra tab").foreach(AppleInspect.click)
      check(
        "and removing it restores the three",
        bar == Gallery.tabTitles && pagesHold(Gallery.tabTitles),
        described
      )
      check(
        "and the user is still on Settings, told its index again",
        shownIndex == 2 && texts.contains("Showing tab 2"),
        s"shown=$shownIndex $showing"
      )

      // Selected(-1) and an index past the end leave the visible tab alone. Sent to the renderer
      // directly, as a model would send them; the answer is read from the controller.
      AppleApp.renderer.update(tv, Seq(Prop.Selected(-1)))
      val afterNone = shownIndex
      AppleApp.renderer.update(tv, Seq(Prop.Selected(7)))
      val afterPast = shownIndex
      AppleApp.renderer.update(tv, Seq(Prop.Selected(2)))
      check(
        "Selected(-1) or past the end leaves the visible tab alone",
        afterNone == 2 && afterPast == 2,
        s"after -1: $afterNone, after 7: $afterPast"
      )
    }
  }

}
