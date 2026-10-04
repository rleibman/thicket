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
      "Slider, ProgressBar, ActivityIndicator",
      "Layout — Spacer, Grow, Divider, nested Row and Column",
      "Image — the three content fits",
      "Scroll — horizontal, inside a vertical one",
      "ForEach — keyed, with a context menu on each row",
      "Provide — a theme scoped to one subtree",
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
    check("a GtkProgressBar", GtkInspect.findAll(root)(GtkInspect.isProgressBar).nonEmpty)

    // The ForEach rows each carry a context menu, so there is one popover per row.
    val popovers = GtkInspect.findAll(root)(GtkInspect.isPopover)
    check("one context-menu popover per row", popovers.length >= 3, s"${popovers.length} popovers")
    check("the popovers hold the menu's items",
      popovers.exists(p => GtkInspect.allTexts(p).contains("Remove")))

    // Presented widgets must *not* be in the tree until asked for — the property that makes
    // them presented rather than inserted.
    check("no sheet or alert before it is asked for",
      !texts.contains("A presented container with a live subtree inside it."))

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
