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

import thicket.core.{Element, Reconciler}
import thicket.core.dsl.*
import thicket.renderer.{CalendarDate, Icon, WidgetKind}
import thicket.renderer.apple.{AppleApp, AppleIcons, AppleInspect}
import thicket.signals.Owner

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
    mountScaling()

    // Presented widgets must not be in the tree until asked for.
    check(
      "nothing is presented before it is asked for",
      AppleInspect.presentedCount == 0,
      s"${AppleInspect.presentedCount} presented"
    )

    if failures == 0 then println("[gallery] ALL CHECKS PASSED")
    else println(s"[gallery] $failures CHECK(S) FAILED")
  }

  /** Twice the children should take about twice the time (#78, review 2026-10-20 P2-14). Every insert into a stack
    * searched its arranged subviews for the anchor, and every insert into a Grid rebuilt all of its placement, so
    * mounting n children was O(n²).
    *
    * Timed off screen (the container is never attached), and only the mount: the platform's own layout of 2 000 stacked
    * views is superlinear by itself (measured on AppKit before any change here: 0.5 s for 1 000, 3.9 s for 2 000), and
    * is not what this is about. Nothing is deferred to layout, so nothing is hidden by stopping before it. The teardown
    * is timed too, and printed rather than checked. Each size is mounted three times and the fastest is kept: the
    * machine is shared, and the slow runs are other work, not this. The check is the Android one: 2 000 under three
    * times 1 000.
    */
  private def mountScaling(): Unit = {
    val renderer = AppleApp.renderer
    def once(
      n:    Int,
      make: Seq[Element] => Element
    ): (Double, Double) = {
      val o = Owner()
      given Owner = o
      val t0 = System.nanoTime()
      val m = Reconciler.mount(renderer, make(Seq.tabulate(n)(i => Label(s"r$i"))))
      val t1 = System.nanoTime()
      m.dispose()
      o.dispose()
      val t2 = System.nanoTime()
      ((t1 - t0) / 1e6, (t2 - t1) / 1e6)
    }
    def best(
      n:    Int,
      make: Seq[Element] => Element
    ): (Double, Double) = {
      val runs = List.fill(3)(once(n, make))
      (runs.map(_._1).min, runs.map(_._2).min)
    }
    List[(String, Seq[Element] => Element)](
      "Column" -> (kids => Column(spacing = 4)(kids*)),
      "Row"    -> (kids => Row(spacing = 4)(kids*)),
      "Grid"   -> (kids => Grid(columns = 4, spacing = 4)(kids*))
    ).foreach {
      (
        name,
        make
      ) =>
        val _ = once(500, make) // warm-up
        val (small, goneSmall) = best(1000, make)
        val (large, goneLarge) = best(2000, make)
        println(
          f"[gallery] info mounting 1000 children in a $name: $small%.1f ms, 2000: $large%.1f ms " +
            f"(ratio ${large / small}%.2f); unmounting them: $goneSmall%.1f ms, $goneLarge%.1f ms"
        )
        check(
          s"mounting a $name scales linearly (2000 children < 3x 1000)",
          large < small * 3,
          f"$small%.1f -> $large%.1f ms"
        )
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
  }

}
