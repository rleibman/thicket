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

import thicket.renderer.{CalendarDate, WidgetKind}
import thicket.renderer.apple.{AppleApp, AppleInspect}

/** The gallery's conformance check on AppKit and UIKit — the same questions the GTK host
  * asks, through `AppleInspect` instead of `GtkInspect`.
  *
  * Deliberately the *same* checks, not Apple-flavoured ones. The gallery's whole claim is
  * that one screen renders natively on every platform, and that claim is only tested if
  * each platform is asked the same thing. Where a check has to differ it is because the
  * platform genuinely differs, and that is noted at the check.
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
    val root  = AppleApp.rootHandle
    val all   = AppleInspect.all(root)
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
      "Image — the three content fits",
      "Scroll — horizontal, inside a vertical one",
      "ForEach — keyed, with a context menu on each row",
      "Provide — a theme scoped to one subtree",
      "Presented — Sheet and Alert are shown over the app, never inserted"
    ).foreach(h => check(s"section is on screen: $h", texts.contains(h)))

    check("labels in every role", texts.contains("Title") && texts.contains("Body") &&
      texts.contains("Caption") && texts.contains("Secondary"))
    check("a disabled button is on screen, not just a reachable state",
      texts.contains("Disabled"))

    // Two text fields, exactly one of which hides its characters. `isSecure` is the only
    // observable difference between a TextField and a SecureField.
    val secure = all.count(AppleInspect.isSecure)
    check("exactly one field hides its characters", secure == 1, s"$secure secure fields")

    // The slider drives the progress bar, so a progress value in (0, 1) proves both the
    // bar exists and the binding ran — the initial volume is 4 of 11.
    val progressed = all.map(AppleInspect.progress).filter(p => p > 0.0 && p < 1.0)
    check("a ProgressBar is bound to the Slider above it",
      progressed.nonEmpty, s"progress values: $progressed")
    val values = all.map(AppleInspect.value).filter(_ > 0.0)
    check("a Slider carries its value", values.contains(4.0), s"values: $values")

    // One context menu per ForEach row. On Apple this is NSView.menu or a
    // UIContextMenuInteraction rather than a popover widget, so the question is "which
    // views have menu items", not "how many popovers are in the tree".
    val withMenus = all.filter(h => AppleInspect.menuItems(h).nonEmpty)
    check("one context menu per row", withMenus.length >= 3, s"${withMenus.length} views with menus")
    check("the menus hold the menu's items",
      withMenus.exists(h => AppleInspect.menuItems(h).contains("Remove")),
      withMenus.map(AppleInspect.menuItems).toString)

    // The Image section's heading being on screen says nothing about the pictures: an image
    // view whose file did not load is an empty frame of the right size. So ask each image
    // view whether it holds an image. The source is a repo-relative path, which a binary run
    // from the repo resolves and an app bundle may not (#31).
    //
    // Only the framework's own Image widgets: a UIKit switch, slider or button contains
    // image views of its own, and an unfiltered walk counted ten where the screen has two.
    val imageWidgets = all.filter(h => AppleApp.renderer.kindOf(h).contains(WidgetKind.Image))
    val images = imageWidgets.flatMap(h => AppleInspect.hasImage(h))
    check("every Image shows its picture", images.nonEmpty && images.forall(identity),
      s"${images.count(identity)} of ${images.size} Image widgets hold an image")

    newControls(all)

    // Presented widgets must not be in the tree until asked for.
    check("nothing is presented before it is asked for",
      AppleInspect.presentedCount == 0, s"${AppleInspect.presentedCount} presented")

    if failures == 0 then println("[gallery] ALL CHECKS PASSED")
    else println(s"[gallery] $failures CHECK(S) FAILED")
  }


  /** The questions the GTK gallery asks of the controls #45 and #46 added, asked of AppKit and UIKit.
    *
    * Each control is found through the renderer (which handles are the framework's own Picker, ZStack, …) and every
    * answer is read from the platform's control, so a renderer that drew the right widget and bound it wrongly fails.
    * Choices are made through the control's own action, the path a user's click takes; where UIKit offers no way to
    * do that from code — a `UIAction` cannot be performed — the check says so instead of passing.
    */
  private def newControls(all: List[thicket.renderer.apple.Shim.Handle]): Unit = {
    def ofKind(k: WidgetKind) = all.filter(h => AppleApp.renderer.kindOf(h).contains(k))
    def texts = AppleInspect.allTexts(AppleApp.rootHandle)

    // --- Picker ---
    val pickers = ofKind(WidgetKind.Picker)
    check("one Picker", pickers.length == 1, pickers.length.toString)
    pickers.headOption.foreach { p =>
      check("the Picker offers its options", AppleInspect.options(p) == Gallery.fruit, AppleInspect.options(p).toString)
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
      check("choosing a day reaches the app", texts.contains("Due 2029-01-31"), texts.filter(_.startsWith("Due")).toString)
      check("and the control holds it", AppleInspect.date(d) == target.toEpochDay,
        CalendarDate.fromEpochDay(AppleInspect.date(d)).toString)
    }

    // --- SegmentedControl ---
    val segmented = ofKind(WidgetKind.SegmentedControl)
    check("one SegmentedControl", segmented.length == 1, segmented.length.toString)
    segmented.headOption.foreach { s =>
      check("a segment per option, in order", AppleInspect.options(s) == Gallery.spans, AppleInspect.options(s).toString)
      check("the model's selection is the selected segment", AppleInspect.selected(s) == 1, AppleInspect.selected(s).toString)
      check("a segment can be chosen", AppleInspect.choose(s, 2))
      check("choosing one selects it", AppleInspect.selected(s) == 2, AppleInspect.selected(s).toString)
      check("and reaches the app", texts.contains("Showing a Month"), texts.filter(_.startsWith("Showing")).toString)
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
      check("a link with a tap opens its URL", opened().lastOption.contains(Gallery.licenceUrl), opened().toString)
      check("and runs the tap as well", texts.contains("The licence link was tapped 1 times"),
        texts.filter(_.contains("licence")).toString)
    }
    check("both links were found and clicked", opened().length == 2, opened().toString)
    AppleInspect.stopRecordingUrls()

    // --- ZStack: paint order is subview order; placement and size read as frames ---
    val stacks = ofKind(WidgetKind.ZStack)
    check("two ZStacks", stacks.length == 2, stacks.length.toString)
    def near(a: Double, b: Double) = math.abs(a - b) <= 1.0
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
        check("the badge sits top-trailing", near(bx + bw, sx + sw) && near(by, sy), s"badge ($bx,$by,$bw) in stack ($sx,$sy,$sw,$sh)")
      }
      val (cw, ch) = (kids.map(k => AppleInspect.frame(k)._3).max, kids.map(k => AppleInspect.frame(k)._4).max)
      check("the stack is as large as its largest child", near(sw, cw) && near(sh, ch) && sw > 0 && sh > 0,
        s"stack ${sw}x$sh, largest child ${cw}x$ch")
    }
    stacks.lift(1).foreach { centred =>
      val kids = AppleInspect.children(centred)
      val (sx, sy, sw, sh) = AppleInspect.frame(centred)
      val spinnerCentred = kids.lift(1).exists { k =>
        val (x, y, w, h) = AppleInspect.frame(k)
        AppleApp.renderer.kindOf(k).contains(WidgetKind.ActivityIndicator) &&
        near(x + w / 2, sx + sw / 2) && near(y + h / 2, sy + sh / 2)
      }
      check("the spinner is over its content, centred", kids.length == 2 && spinnerCentred,
        kids.map(k => AppleInspect.frame(k)).toString)
    }
  }

}
