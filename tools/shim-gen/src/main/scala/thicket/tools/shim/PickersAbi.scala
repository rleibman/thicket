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

package thicket.tools.shim

import thicket.tools.shim.Abi.{Fn, Group}
import thicket.tools.shim.Abi.CType.*

/** The `thk_pick_*` ABI: `thicket-pickers-apple`'s Swift library, the system's pickers on macOS and iOS (#125, docs/16
  * §16.10 item 5): `PHPickerViewController` and `UIDocumentPickerViewController` on iOS, `NSOpenPanel` and
  * `NSSavePanel` as sheets on macOS.
  *
  * Its own surface, header, externs and Swift library, like [[DeviceAbi]]: an app that does not depend on
  * `thicket-pickers-apple` links none of it, nor PhotosUI.
  *
  * **Push, on the main thread only**, as [[DeviceAbi]]. A pick hands Scala each file as a *hold*: `file` is called on
  * the main thread with the hold's id, Scala reads its path with `thk_pick_path` there, copies the file into the app's
  * cache on a worker thread (`thicket.pickers.PickedFiles`), and calls `thk_pick_release` from that thread. The file
  * stays readable until then: `PHPickerViewController`'s temporary file is deleted when its loading callback returns,
  * so that callback waits for the release. `done` comes on the main thread once every hold is released, with how many
  * there were: 0 for a cancel.
  *
  * No struct and no new callback shape crosses: the one callback type is `thk_pick_int_cb`.
  */
object PickersAbi {

  private val ctx = Abi.p("ctx", I64)
  private val hold = Abi.p("hold", I32)

  val picking: List[Fn] = List(
    Fn(
      "thk_pick_photos",
      I32,
      List(Abi.p("limit", I32), Abi.p("file", IntCb), Abi.p("done", IntCb), ctx),
      doc = "Shows the photo picker for up to `limit` images: PHPickerViewController (iOS), an\n" +
        "   NSOpenPanel sheet for images (macOS). Returns 1, or 0 with nothing shown and nothing\n" +
        "   called when there is no window to show it over or a picker is already up."
    ),
    Fn(
      "thk_pick_documents",
      I32,
      List(Abi.p("types", Str), Abi.p("multiple", I32), Abi.p("file", IntCb), Abi.p("done", IntCb), ctx),
      doc = "Shows the document picker for `types`, MIME types separated by newlines (\"\" for any):\n" +
        "   UIDocumentPickerViewController(forOpeningContentTypes:asCopy: true) on iOS, an NSOpenPanel\n" +
        "   sheet on macOS. `multiple` 1 lets the user choose several. Returns as thk_pick_photos."
    ),
    Fn(
      "thk_pick_path",
      Str,
      List(hold),
      doc = "The file a hold is for: a path, valid until thk_pick_release. NULL for an unknown hold."
    ),
    Fn(
      "thk_pick_index",
      I32,
      List(hold),
      doc = "Where the hold's file stands in the user's selection, from 0; -1 for an unknown hold."
    ),
    Fn(
      "thk_pick_release",
      Void,
      List(hold),
      doc = "Scala has copied the file: the platform may delete it. Any thread. An unknown hold is ignored."
    ),
    Fn(
      "thk_pick_save",
      I32,
      List(Abi.p("file", Str), Abi.p("mime", Str), Abi.p("answered", IntCb), ctx),
      doc = "Asks where to save `file`, which Scala wrote under the suggested name. iOS:\n" +
        "   UIDocumentPickerViewController(forExporting:asCopy: true), which copies it itself. macOS: an\n" +
        "   NSSavePanel sheet suggesting its name, of the type of `mime`. `answered` on the main thread:\n" +
        "   0 cancelled, 1 the system saved it, 2 the user chose a destination, which\n" +
        "   thk_pick_save_path gives inside the call, for Scala to write. Returns as thk_pick_photos."
    ),
    Fn(
      "thk_pick_save_path",
      Str,
      Nil,
      doc = "The destination chosen in the latest save, valid inside `answered`; NULL if none."
    )
  )

  val testing: List[Fn] = List(
    Fn(
      "thk_pick_test_shown",
      I32,
      Nil,
      doc = "How many pickers this process has shown."
    ),
    Fn(
      "thk_pick_test_describe",
      Str,
      Nil,
      doc = "The picker that is up, as the platform holds it, or \"\" when none is: e.g.\n" +
        "   \"open sheet types=public.image multiple=0\". Valid until the next call."
    ),
    Fn(
      "thk_pick_test_cancel",
      I32,
      Nil,
      doc = "macOS: the panel's own cancel(_:). Returns 1, or 0 when no panel is up, and on iOS, whose\n" +
        "   pickers are other processes' and are answered through XCUITest."
    ),
    Fn(
      "thk_pick_test_choose",
      I32,
      List(Abi.p("paths", Str)),
      doc = "macOS: closes the panel that is up and answers it with `paths` (newline-separated) as if\n" +
        "   the user had chosen them, through the panel's own completion. For self-tests: macOS 27's\n" +
        "   panels run in another process, whose OK cannot be pressed from this one. Returns 1, or 0\n" +
        "   when no panel is up, and on iOS."
    )
  )

  val groups: List[Group] = List(
    Group("picking", picking),
    Group("testing", testing, doc = "For self-tests: what a user would do, and what the platform holds.")
  )

  val all: List[Fn] = groups.flatMap(_.fns)

}
