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

import thicket.renderer.WidgetKind
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
      "Link — opens a URL, drawn as each platform draws a link",
      "Slider, ProgressBar, ActivityIndicator",
      "Layout — Spacer, Grow, Divider, nested Row and Column",
      "ZStack — children drawn over one another, last on top",
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
    check("every Image shows its picture", images.size == 2 && images.forall(identity),
      s"${images.count(identity)} of ${images.size} Image widgets hold an image")

    // Presented widgets must not be in the tree until asked for.
    check("nothing is presented before it is asked for",
      AppleInspect.presentedCount == 0, s"${AppleInspect.presentedCount} presented")

    if failures == 0 then println("[gallery] ALL CHECKS PASSED")
    else println(s"[gallery] $failures CHECK(S) FAILED")
  }

}
