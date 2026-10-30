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
import scala.scalanative.unsafe.{Zone, toCString}
import thicket.core.{Element, GcState, Reconciler}
import thicket.core.dsl.*
import thicket.renderer.{Alignment, CalendarDate, ContentFit, Icon, Prop, WidgetKind}
import thicket.renderer.apple.{AppleApp, AppleIcons, AppleInspect, Handles, Shim, ShimKind}
import thicket.secrets.apple.AppleSecrets
import thicket.secrets.testing.SecretsConformance
import thicket.signals.{ErrorSink, Owner}
import thicket.sqlite.{NativeSqlite, Sqlite}
import thicket.sqlite.testing.JdbcConformance
import thicket.storage.{FilePreferences, Storage}

import java.nio.file.{Files, Paths}

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

  /** Re-checks `cond` about once a frame until it holds or `timeoutMs` passes, then continues — the todo self-test's
    * helper. UIKit presents, dismisses and lays out a reloaded table on a later turn of the run loop, and the Effects
    * section's result arrives through `UiThread` as a post of its own; on AppKit most waits resolve at once.
    */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Boolean => Unit): Unit = {
    val deadline = System.nanoTime() + timeoutMs * 1000000L
    def loop(): Unit =
      if cond then k(true)
      else if System.nanoTime() > deadline then k(false)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  /** The storage the host gave the gallery, and the bundle id it named it by. */
  private var storage:  Option[Storage] = None
  private var bundleId: String = ""

  /** Set by [[run]]: the host shows the SVG logo wherever the platform decodes it (#85). */
  private var svgWhereDecoded = false

  /** Waits for the Effects section's ZIO result first, as the GTK host does, so the synchronous checks see it.
    *
    * `svgWhereDecoded`: the host promises the SVG logo wherever the platform's image class decodes it - the macOS host
    * (#85). The iOS host stays on the PNG, whatever `UIImage` does.
    */
  def run(
    storage:         Option[Storage],
    bundleId:        String,
    svgWhereDecoded: Boolean = false
  ): Unit = {
    this.storage = storage
    this.bundleId = bundleId
    this.svgWhereDecoded = svgWhereDecoded
    eventually(5000)(AppleInspect.allTexts(AppleApp.rootHandle).contains(Gallery.effectResult))(_ => catalogue())
  }

  private def verdict(): Unit =
    if failures == 0 then println("[gallery] ALL CHECKS PASSED")
    else println(s"[gallery] $failures CHECK(S) FAILED")

  /** The verdict comes from the terminate hook (#114): the last check is that the hook runs, so the run ends through
    * the platform's terminate path — `NSApp.terminate` on macOS, which ends the process, and the will-terminate
    * notification on iOS. A hook that never runs leaves no verdict on macOS, which fails the run, and a FAIL after 5 s
    * on iOS.
    */
  private def finish(): Unit =
    GalleryDeviceChecks.terminate(checker, () => verdict())

  private def catalogue(): Unit = {
    val root = AppleApp.rootHandle
    val all = AppleInspect.all(root)
    val texts = AppleInspect.allTexts(root)

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

    // Generated per module, so this proves the gallery's own object was wired in.
    check(
      "the gallery shows its own BuildInfo, not another module's",
      texts.exists(_.startsWith("thicket-gallery-shared ")),
      texts.filter(_.contains("thicket-")).toString
    )

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
    // `ProgressBar(None)`: the platform's bar reports itself indeterminate (-1), not empty.
    val bars = all.filter(h => kind(h, WidgetKind.ProgressBar)).map(AppleInspect.progress)
    check("an indeterminate ProgressBar is indeterminate on the platform", bars.contains(-1.0), bars.toString)
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
    val imageWidgets = all.filter(h => kind(h, WidgetKind.Image))
    val images = imageWidgets.flatMap(h => AppleInspect.hasImage(h))
    val fits = imageWidgets.map(h => AppleInspect.contentFit(h) -> AppleInspect.hasImage(h).contains(true))
    check(
      "every Image shows its picture",
      images.nonEmpty && images.forall(identity),
      s"${images.count(identity)} of ${images.size} Image widgets hold an image: $fits"
    )
    // The three fits, read from the image views, each with a picture: the Fill one is the
    // bytes-backed one, so a host that handed it no bytes shows here (#73).
    check(
      "the three content fits, each with a picture to paint",
      List(ContentFit.Contain, ContentFit.Cover, ContentFit.Fill).forall(f => fits.contains(Some(f) -> true)),
      fits.toString
    )
    contentFitRow(imageWidgets)
    logoFormat(imageWidgets)

    check(
      "a ZIO effect's result reached the screen through UiThread",
      texts.contains(Gallery.effectResult),
      texts.filter(t => t.startsWith("Load") || t == "Failed").toString
    )

    newControls(all)
    // First, while the screen is scrolled to its top: it measures content positions against the
    // safe area. It swaps the horizontal Scroll's child and back, so the steps after it gather
    // their handles again rather than use `all`, whose labels in that Scroll are gone.
    insetsAndTaps()
    tapsCheckboxAndDirection(AppleInspect.all(root))
    layout(AppleInspect.all(root))
    crossAxis(AppleInspect.all(root))
    mountScaling()
    shimStrings()
    robustness(AppleInspect.all(root))

    // Presented widgets must not be in the tree until asked for.
    check(
      "nothing is presented before it is asked for",
      AppleInspect.presentedCount == 0,
      s"${AppleInspect.presentedCount} presented"
    )

    storageChecks(() => deviceChecks(() => sheet(all)(() => bigList(() => finish()))))
  }

  /** Device services (docs/16 §16.5–16.6), the GTK host's checks asked of Apple: preferences in Application Support,
    * the directories where Foundation says they are, SQLite through the Scala Native driver, and "Add a note" writing a
    * row on the worker thread.
    */
  private val checker: (String, Boolean, String) => Unit = (
    n,
    ok,
    d
  ) => check(n, ok, d)

  /** Lifecycle and permissions (#114), in [[GalleryDeviceChecks]]. */
  private def deviceChecks(next: () => Unit): Unit =
    GalleryDeviceChecks.run(
      checker,
      label => buttonIn(AppleInspect.all(AppleApp.rootHandle), label).exists(AppleInspect.click),
      () => AppleInspect.allTexts(AppleApp.rootHandle)
    )(next)

  private def storageChecks(next: () => Unit): Unit =
    (Gallery.currentStorage, storage) match {
      case (Some(demo), Some(store)) =>
        val root = AppleApp.rootHandle
        val dirs = store.dirs
        val texts = AppleInspect.allTexts(root)
        check(
          "the launch counter is on screen and stored in preferences",
          texts.contains(s"Opened ${demo.opens} times") &&
            store.preferences.getLong(StorageDemo.opensKey).contains(demo.opens),
          texts.filter(_.startsWith("Opened")).toString
        )
        // What Foundation says, through the shim: thicket-storage-apple works its paths out without it.
        val (support, caches, tmp, foundationId) = AppleInspect.foundationDirectories
        def under(base: Option[String]) = base.map(b => Paths.get(b).resolve(bundleId))
        println(s"[gallery] storage: data ${dirs.data}, cache ${dirs.cache}, temp ${dirs.temp}")
        println(s"[gallery] Foundation: support $support, caches $caches, temp $tmp, bundle id $foundationId")
        check(
          "the directories are Foundation's Application Support, Caches and NSTemporaryDirectory, under the bundle id",
          under(support).contains(dirs.data) && under(caches).contains(dirs.cache) && under(tmp).contains(dirs.temp) &&
            // An executable outside an .app (the macOS gallery) has no bundle id; an app's must be the one named.
            foundationId.forall(_ == bundleId) && Seq(dirs.data, dirs.cache, dirs.temp).forall(Files.isDirectory(_)),
          s"ours $dirs; Foundation's support $support, caches $caches, temp $tmp, bundle id $foundationId"
        )
        check(
          "preferences are in a file under Application Support, and a new instance reads them back",
          FilePreferences(dirs.data.resolve("preferences.properties"))
            .getLong(StorageDemo.opensKey).contains(demo.opens),
          dirs.data.toString
        )
        val results = JdbcConformance.run(NativeSqlite.driver, dirs.temp.resolve("conformance"))
        val failed = results.filterNot(_.passed).map(_.name).toSet
        println(s"[gallery] JDBC conformance: ${results.size} checks, ${failed.size} failed: ${failed.toList.sorted}")
        check(
          s"JDBC conformance on the Scala Native driver: ${results.size} checks, only the known upstream defects fail",
          results.size >= 17 && failed == JdbcConformance.knownNativeFailures,
          results.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
        )
        val magnum = MagnumChecks.run(Sqlite.dataSource(dirs, "magnum-checks.db")(using NativeSqlite.driver))
        check(
          s"Magnum (Scala Native build) over the Native driver: ${magnum.size} checks, only the known driver gaps fail",
          magnum.size == 7 && magnum.filterNot(_.passed).map(_.name).toSet == MagnumChecks.knownNativeFailures,
          magnum.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
        )
        def count = demo.notes.now.toOption
        eventually(5000)(count.isDefined) { _ =>
          val before = count.getOrElse(-1)
          val clicked = buttonIn(AppleInspect.all(root), "Add a note").exists(AppleInspect.click)
          eventually(5000)(count.contains(before + 1)) { _ =>
            check(
              "\"Add a note\" writes a row on the worker and the count reaches the screen",
              clicked && before >= 0 &&
                AppleInspect.allTexts(AppleApp.rootHandle).contains(StorageDemo.notesLabel(before + 1)) &&
                Files.exists(dirs.data.resolve("gallery.db")),
              s"clicked $clicked, before $before, now ${demo.notes.now}, " +
                AppleInspect.allTexts(AppleApp.rootHandle).filter(_.contains("SQLite"))
            )
            // The gallery asks the store on a thread of its own; wait for its answer first.
            eventually(10000)(demo.secret.now != StorageDemo.secretPending)(_ => secretChecks(demo)(next))
          }
        }
      case (demo, store) =>
        check("the host provides storage", false, s"Gallery.currentStorage $demo, the host's $store")
        next()
    }

  /** Secrets in the Keychain (#108), the GTK host's `secretChecks` asked of Apple: the gallery's secret is on screen as
    * kept or stored and reads back from the Keychain, and every conformance check passes. Required, not skipped: a
    * gallery that silently had no secrets would pass. The Keychain blocks, so it is asked on a thread of this check's
    * own, and the verdicts are posted back to the main thread.
    */
  private def secretChecks(demo: StorageDemo)(next: () => Unit): Unit = {
    val shown = demo.secret.now
    val texts = AppleInspect.allTexts(AppleApp.rootHandle)
    // Kept or stored: a second run of the same build should turn stored into kept.
    println(s"[gallery]      this run: $shown")
    val worker = new Thread(
      () => {
        val outcome = AppleSecrets.open(bundleId).map { s =>
          val token =
            try Right(s.get(StorageDemo.secretKey))
            catch { case e: Exception => Left(e.getMessage) }
          val results = SecretsConformance.run(() => AppleSecrets.open(bundleId).toOption.get, "selftest.")
          (s.keychain, token, results)
        }
        AppleApp.postToUi { () =>
          outcome match {
            case Left(reason) =>
              check("the Keychain is open to the gallery", false, reason)
            case Right((keychain, token, results)) =>
              println(s"[gallery] secrets: the $keychain keychain, service $bundleId")
              check(
                "the gallery's secret is on screen as kept or stored, and reads back from the Keychain",
                Set(StorageDemo.secretKept, StorageDemo.secretStored).contains(shown) && texts.contains(shown) &&
                  token.exists(_.exists(_.startsWith(StorageDemo.secretPrefix))),
                texts.filter(_.contains("ecret")).toString +
                  s"; read back ${token.map(_.map(_.take(StorageDemo.secretPrefix.length)))}"
              )
              check(
                s"secrets conformance on the Keychain: all ${results.size} checks pass",
                results.size == 11 && results.forall(_.passed),
                results.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
              )
          }
          next()
        }
      },
      "gallery-secrets-check"
    )
    worker.setDaemon(true)
    worker.start()
  }

  private def kind(
    h: Shim.Handle,
    k: WidgetKind
  ): Boolean = AppleApp.renderer.kindOf(h).contains(k)

  private def buttonIn(
    all:   List[Shim.Handle],
    label: String
  ): Option[Shim.Handle] = all.find(h => kind(h, WidgetKind.Button) && AppleInspect.text(h).contains(label))

  /** The Image section's Row of three pictures, one per fit (#104), from the frames and pixels the platform drew.
    * "Holds an image" is true of a view squeezed to 0 pt: on UIKit the Row kept the first at 342 pt and gave the Cover
    * and Fill pictures 0 x 710. Asked of both toolkits: the three are side by side in their Row without overlapping,
    * each at least a point in both directions and in its picture's shape (the same picture, so the same width), and
    * each paints. The three are the Images on the Fill picture's line; the ZStack's Image is elsewhere.
    */
  private def contentFitRow(imageWidgets: Seq[Shim.Handle]): Unit = {
    val fill = imageWidgets.find(h => AppleInspect.contentFit(h).contains(ContentFit.Fill))
    val row = fill.toList.flatMap { f =>
      val y = AppleInspect.frame(f)._2
      imageWidgets.filter(h => math.abs(AppleInspect.frame(h)._2 - y) < 1).sortBy(h => AppleInspect.frame(h)._1)
    }
    val frames = row.map(AppleInspect.frame)
    val painted = row.map(AppleInspect.paintedFraction)
    val detail = row
      .zip(frames)
      .zip(painted)
      .map { case ((h, (x, y, w, hh)), p) =>
        f"${AppleInspect.contentFit(h).getOrElse("?")} ($x%.0f,$y%.0f $w%.0fx$hh%.0f) painted ${p * 100}%.1f%%"
      }
      .mkString(", ")
    println(s"[gallery]   content-fit row: $detail")
    val ratio = 710.0 / 560.0 // the logo's shape, PNG and SVG alike
    check(
      "the three content-fit pictures are side by side, each visible, in its picture's shape",
      frames.size == 3 &&
        frames.forall { case (_, _, w, h) => w >= 1 && h >= 1 && math.abs(h / w - ratio) < 0.02 } &&
        frames.map(_._3).max - frames.map(_._3).min < 1 &&
        frames.sliding(2).forall { case Seq(a, b) => a._1 + a._3 <= b._1 + 0.5; case _ => true },
      s"(#104) $detail"
    )
    check(
      "each content-fit picture paints its pixels, the bytes-backed Fill one included",
      painted.size == 3 && painted.forall(_ > 0.05),
      s"(#104) $detail"
    )
  }

  /** Which file of the logo is drawn (#85): the SVG and the PNG made from it are one picture, so "holds an image"
    * cannot tell them apart. Their sizes can - the SVG reports its own 472.658x599.168 points, the PNG its pixels at
    * its DPI - and so can their pixels: the SVG's background is transparent and the PNG's opaque, so the Fill picture,
    * which covers its whole view, is partly painted from the SVG and wholly from the PNG. The Fill picture is the
    * bytes-backed one, so both the file and the bytes are read.
    */
  private def logoFormat(imageWidgets: Seq[Shim.Handle]): Unit = {
    val svgFile = Gallery.currentLogoFile.endsWith(".svg")
    val (sw, sh) = Gallery.logoSvgSize
    def isSvg(s:    (Double, Double)) = math.abs(s._1 - sw) < 0.01 && math.abs(s._2 - sh) < 0.01
    def pngShape(s: (Double, Double)) = s._2 > 0 && math.abs(s._1 / s._2 - 560.0 / 710.0) < 0.01
    def show(s:     (Double, Double)) = f"${s._1}%.3fx${s._2}%.3f"
    val sizes = imageWidgets.map(h => AppleInspect.contentFit(h) -> AppleInspect.imageSize(h))
    val shown = sizes.map { case (f, s) => s"${f.getOrElse("?")} ${s.map(show).getOrElse("none")}" }.mkString(", ")
    check(
      if svgFile then s"every Image draws the SVG logo, at its own ${show(Gallery.logoSvgSize)}"
      else "every Image draws the PNG logo, in its shape and not at the SVG's size",
      sizes.nonEmpty && sizes.forall {
        case (_, Some(s)) => if svgFile then isSvg(s) else !isSvg(s) && pngShape(s)
        case _            => false
      },
      s"(#85) ${Gallery.currentLogoFile}: $shown"
    )
    val fill = imageWidgets.find(h => AppleInspect.contentFit(h).contains(ContentFit.Fill))
    val painted = fill.map(AppleInspect.paintedFraction).getOrElse(-1.0)
    val fillFrame =
      fill.map(AppleInspect.frame).map(r => f"${r._3}%.0fx${r._4}%.0f at (${r._1}%.0f,${r._2}%.0f)").getOrElse("none")
    println(
      f"[gallery]   logo: ${Gallery.currentLogoFile}; $shown; Fill $fillFrame, painted ${painted * 100}%.1f%%"
    )
    // On both toolkits since #104: before it, UIKit's .fill Row squeezed the Fill picture to
    // 0 points wide, so this was asked of the macOS host only.
    check(
      if svgFile then "the bytes-backed Fill picture paints the SVG, its transparent background left clear"
      else "the bytes-backed Fill picture paints the opaque PNG",
      if svgFile then painted > 0.05 && painted < 0.9 else painted > 0.9,
      f"(#85) painted ${painted * 100}%.1f%% of the view, $fillFrame"
    )
    if svgWhereDecoded then {
      val decodes = AppleApp.decodesImage(Gallery.logoBytes(Gallery.logoSvg))
      check(
        "the host shows the SVG logo exactly where the platform decodes it",
        decodes == svgFile,
        s"(#85) the platform ${if decodes then "decodes" else "does not decode"} the SVG; the host chose " +
          Gallery.currentLogoFile
      )
    }
  }

  /** The Layout section and the padded Scroll (review 2026-10-20, P1-4, P2-26), from the frames the platform laid out
    * and the alignment its text views hold. Within a point, as the ZStack's checks are.
    *
    * Known failures, each with its issue: AppKit's Row takes its natural width and its aligned labels hug their text,
    * and UIKit's Grow and Spacer-in-Column do nothing (#87); the Scroll's padding is dropped on both (#76).
    */
  private def layout(all: List[Shim.Handle]): Unit = {
    def near(
      a: Double,
      b: Double
    ) = math.abs(a - b) <= 1.0
    def labels(t: String) = all.filter(h => kind(h, WidgetKind.Label) && AppleInspect.text(h).contains(t))
    def f(t:      String) = labels(t).lastOption.map(AppleInspect.frame).getOrElse((0.0, 0.0, 0.0, 0.0))
    def right(t:  String) = f(t)._1 + f(t)._3
    def bottom(t: String) = f(t)._2 + f(t)._4
    def show(ts: String*) =
      ts.map(t => f"$t(${f(t)._1}%.0f,${f(t)._2}%.0f ${f(t)._3}%.0fx${f(t)._4}%.0f)").mkString(" ")

    check(
      "a Spacer in a Row pushes the next label to the far edge",
      near(right("right"), right("fixed")) && f("right")._1 - right("left") > 100,
      s"(#87) ${show("left", "right", "fixed")}"
    )
    check(
      "a grown label in a Row takes the room its sibling does not",
      near(right("grown") + 8, f("fixed")._1) && f("grown")._3 > f("fixed")._3,
      s"(#87) ${show("grown", "fixed")}"
    )
    check(
      "a Spacer in a Column pushes the next label down, level with the taller column beside it",
      near(f("top")._2, f("one")._2) && near(bottom("bottom"), bottom("four")),
      s"(#87) ${show("top", "bottom", "one", "four")}"
    )
    // Labels only: the Spinner section's button is labelled "Start" too. Each must hold its
    // alignment and be as wide as the section, or the alignment has no room to show.
    val aligned = List(Alignment.Start, Alignment.Center, Alignment.End)
    val held = aligned.map(a => labels(a.toString).lastOption.flatMap(AppleInspect.textAlign))
    val width = right("right") - f("left")._1
    check(
      "Start, Center and End labels sit where they say",
      held == aligned.map(Some(_)) && aligned.forall(a => near(f(a.toString)._3, width)),
      s"(#87) held $held; ${show(aligned.map(_.toString)*)}, section width $width"
    )

    // The scroller is where the section puts it; its padding insets the content inside it.
    val hscroll = all.filter(AppleApp.renderer.isHorizontalScroll)
    check("one horizontal Scroll", hscroll.length == 1, hscroll.length.toString)
    hscroll.headOption.foreach { s =>
      val (sx, sy, _, _) = AppleInspect.frame(s)
      val (cx, cy, _, _) = f("col 0")
      check(
        "a padded Scroll insets its content by its padding, and not itself",
        near(cx - sx, 8) && near(cy - sy, 8) && near(sx, f("left")._1),
        f"(#76) scroller at ($sx%.0f,$sy%.0f), col 0 at ($cx%.0f,$cy%.0f), section at ${f("left")._1}%.0f"
      )
    }
  }

  /** Host → Scala entry points under stress (review 2026-10-20, P0-2 and P0-3), as the GTK gallery asks them. */
  private def robustness(all: List[Shim.Handle]): Unit = {
    // The self-test runs in a `postToUiAfter` callback, one level into Scala.
    check("the self-test runs one level into Scala", GcState.scalaDepth == 1, GcState.scalaDepth.toString)

    val field = all.find(h => kind(h, WidgetKind.TextField))
    val thrower = buttonIn(all, "Throw from a handler")
    check("there is a plain field and a throwing button to drive", field.nonEmpty && thrower.nonEmpty)

    // Scala -> Swift -> Scala, the nested path: a programmatic `sui_set_text` on the field
    // (which the shim writes with its change callback suppressed), and a click on the
    // throwing button, whose handler runs in the tap trampoline *inside* this frame. Before
    // `guarded` counted depth, the inner exit marked the thread Unmanaged with this frame
    // still running, so a collection on another thread skipped its roots. A worker collects
    // all the while.
    for e <- field; b <- thrower do {
      val stop = AtomicBoolean(false)
      val collector = Thread(() =>
        while !stop.get() do {
          val _ = Array.fill(2048)(new Array[Byte](64))
          System.gc()
        }
      )
      val inner = mutable.Map.empty[(Int, Boolean), Int].withDefaultValue(0)
      val previous = ErrorSink.installed
      ErrorSink.install(_ => inner((GcState.scalaDepth, GcState.managed)) += 1)
      collector.start()
      val deadline = System.nanoTime() + 3_000_000_000L
      val kept = mutable.ArrayBuffer.empty[String]
      var deepest = 0
      var unmanaged = 0
      var i = 0
      while System.nanoTime() < deadline do {
        Zone(Shim.sui_set_text(e, toCString(s"gc $i")))
        if !GcState.managed then unmanaged += 1
        AppleInspect.click(b)
        deepest = deepest max GcState.scalaDepth
        if !GcState.managed then unmanaged += 1
        kept += ("kept " * 8) + i
        i += 1
      }
      stop.set(true)
      collector.join()
      ErrorSink.install(previous)
      println(
        s"[gallery]   the stress step made ${2 * i} nested calls in 3 s; inside, (depth, managed) -> count: $inner"
      )
      check(
        "every click's handler ran nested, two deep and Managed",
        inner.keySet == Set((2, true)) && inner((2, true)) == i,
        s"$inner over $i clicks"
      )
      check("a nested call leaves the thread in Scala", GcState.scalaDepth == 1 && deepest == 1, s"$deepest")
      check(
        "the GC was never told the thread left Scala while Scala was still running on it",
        unmanaged == 0,
        s"$unmanaged of ${2 * i} nested calls left it Unmanaged"
      )
      val corrupt = kept.zipWithIndex.count(
        (
          s,
          n
        ) => s != ("kept " * 8) + n
      )
      check(s"$i strings kept across collections on another thread survive", corrupt == 0, s"$corrupt corrupt")
      check(
        "and the field holds the last write",
        AppleInspect.text(e).contains(s"gc ${i - 1}"),
        AppleInspect.text(e).toString
      )
      Zone(Shim.sui_set_text(e, toCString("")))
    }

    // A handler that throws: reported, not unwound through Swift, and the app carries on.
    val reported = mutable.ArrayBuffer.empty[Throwable]
    val previous = ErrorSink.installed
    ErrorSink.install(e => reported += e)
    thrower.foreach(AppleInspect.click)
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
    // The segmented control was left on Month by `newControls`.
    buttonIn(all, "Select first").foreach(AppleInspect.click)
    check(
      "and the next click still works",
      AppleInspect.allTexts(AppleApp.rootHandle).contains("Showing a Day"),
      AppleInspect.allTexts(AppleApp.rootHandle).filter(_.startsWith("Showing")).toString
    )
  }

  /** A sheet opened and closed from inside: every per-handle table entry and every callback its widgets registered is
    * let go (review 2026-10-20, P2-5, P3-1). Counted against a baseline taken just before, in the same state.
    */
  private def sheet(all: List[Shim.Handle])(done: () => Unit): Unit = {
    val renderer = AppleApp.renderer
    val entries = renderer.tableEntries
    val callbacks = Handles.count
    buttonIn(all, "Open sheet").foreach(AppleInspect.click)
    val sheets = renderer.presented.collect { case (h, WidgetKind.Sheet) => h }
    check("opening the sheet presents one", sheets.length == 1, sheets.length.toString)
    check(
      "and its widgets are in the renderer's tables while it is up",
      renderer.tableEntries > entries && Handles.count > callbacks,
      s"entries $entries -> ${renderer.tableEntries}, callbacks $callbacks -> ${Handles.count}"
    )
    eventually(3000)(AppleInspect.presentedCount == 1) { up =>
      check("the platform shows it", up, s"${AppleInspect.presentedCount} presented")
      val close = sheets.headOption.flatMap(s => buttonIn(AppleInspect.all(s), "Close"))
      check("the sheet's own Close button is clicked", close.exists(AppleInspect.click))
      check("closing unmounts the sheet", renderer.presented.isEmpty, renderer.presented.toString)
      eventually(3000)(AppleInspect.presentedCount == 0) { gone =>
        check("and the platform takes it down", gone, s"${AppleInspect.presentedCount} still presented")
        check(
          "every renderer table entry the sheet's widgets had is gone",
          renderer.tableEntries == entries,
          s"$entries before, ${renderer.tableEntries} after"
        )
        check(
          "and every callback they registered",
          Handles.count == callbacks,
          s"$callbacks before, ${Handles.count} after"
        )
        done()
      }
    }
  }

  /** The 10 000-row LazyColumn after its data changes under it (review 2026-10-20, P1-3): the table must re-bind the
    * rows it is showing. Read from the rows the platform's table holds, by position.
    */
  private def bigList(done: () => Unit): Unit = {
    val rowsBefore = Handles.rowCount
    val tablesBefore = Shim.sui_table_live()
    Gallery.currentNav.foreach(_.push(Gallery.Route.Big))
    def tableOf(h: Shim.Handle): Option[Shim.Handle] =
      if AppleApp.renderer.materialisedRows(h) > 0 then Some(h)
      else AppleInspect.children(h).flatMap(tableOf).headOption
    def table = tableOf(AppleApp.rootHandle)
    def row(i: Int) = table.flatMap(t => AppleInspect.rowView(t, i)).map(AppleInspect.allTexts).getOrElse(Nil)
    def first2 = List(row(0), row(1))
    def click(label: String) = buttonIn(AppleInspect.all(AppleApp.rootHandle), label).foreach(AppleInspect.click)

    eventually(3000)(row(0).nonEmpty) { _ =>
      check("the list shows its first rows", first2 == List(List("Row 0"), List("Row 1")), first2.toString)
      val built = table.map(AppleApp.renderer.materialisedRows).getOrElse(0)
      check("and only a bounded set of them", built > 0 && built < 1000, s"$built row views built")
      click("Delete the first row")
      eventually(3000)(row(0) == List("Row 1")) { _ =>
        check(
          "deleting row 0 re-binds the rows on screen: the first is now Row 1",
          first2 == List(List("Row 1"), List("Row 2")),
          first2.toString
        )
        click("Rename the first row")
        eventually(3000)(row(0) == List("Renamed 1")) { _ =>
          check(
            "an edit that keeps the count reaches the row on screen",
            first2 == List(List("Renamed 1"), List("Row 2")),
            first2.toString
          )
          Gallery.currentNav.foreach(_.pop())
          eventually(3000)(AppleInspect.pagesDepth == 1 && Handles.rowCount == rowsBefore) { _ =>
            check(
              "leaving the list releases its row callback and its table",
              Handles.rowCount == rowsBefore && Shim.sui_table_live() == tablesBefore,
              s"row callbacks $rowsBefore -> ${Handles.rowCount}, tables $tablesBefore -> ${Shim.sui_table_live()}"
            )
            done()
          }
        }
      }
    }
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

  /** The cross axis of the Layout section (#87), from the frames the platform laid out, beside [[layout]]'s checks
    * (#73): a Row spans its Column, children stay inside the screen's padding, and each aligned label is as wide as its
    * section, which is what it needs for its alignment to show. Within a point, as the ZStack's checks are.
    *
    * `THICKET_FRAME_DUMP=1` also prints every widget's kind, text and frame, so a layout change can be compared across
    * the whole screen before and after.
    */
  private def crossAxis(all: List[thicket.renderer.apple.Shim.Handle]): Unit = {
    type H = thicket.renderer.apple.Shim.Handle
    def kind(
      h: H,
      k: WidgetKind
    ) = AppleApp.renderer.kindOf(h).contains(k)
    def near(
      a: Double,
      b: Double
    ) = math.abs(a - b) <= 1.0
    def labels(t: String) = all.filter(h => kind(h, WidgetKind.Label) && AppleInspect.text(h).contains(t))
    def f(t:      String) = labels(t).lastOption.map(AppleInspect.frame).getOrElse((0.0, 0.0, 0.0, 0.0))
    def right(t:  String) = f(t)._1 + f(t)._3
    def bottom(t: String) = f(t)._2 + f(t)._4
    def fr(r:     (Double, Double, Double, Double)) = f"(${r._1}%.0f,${r._2}%.0f ${r._3}%.0fx${r._4}%.0f)"
    def show(ts:  String*) = ts.map(t => s"$t${fr(f(t))}").mkString(" ")
    val parentOf: Map[H, H] = all.flatMap(p => AppleInspect.children(p).map(_ -> p)).toMap

    // The section's body: the Column holding the Row that holds "left". Everything below is
    // measured against it, so a Column that does not stretch its children shows here.
    val leftRow = labels("left").lastOption.flatMap(parentOf.get)
    val body = leftRow.flatMap(parentOf.get)
    val (bx, _, bw, _) = body.map(AppleInspect.frame).getOrElse((0.0, 0.0, 0.0, 0.0))
    val bodyRight = bx + bw
    check(
      "a Row spans its Column",
      bw > 100 && leftRow.exists(r => near(AppleInspect.frame(r)._3, bw)),
      s"(#87) row ${leftRow.map(r => fr(AppleInspect.frame(r)))}, column ${body.map(c => fr(AppleInspect.frame(c)))}"
    )
    // A stretched child stays inside its Column's padding: the screen Column's first section is
    // as far in from its leading and trailing edges as from its top, which the stack's own
    // spacing rules set.
    val screen = body.flatMap(parentOf.get).flatMap(parentOf.get)
    val inset = for r <- screen; s <- AppleInspect.children(r).headOption yield {
      val (sx, sy, sw, _) = AppleInspect.frame(s)
      val (rx, ry, rw, _) = AppleInspect.frame(r)
      (sy - ry, sx - rx, (rx + rw) - (sx + sw))
    }
    check(
      "a stretched child stays inside its Column's padding",
      inset.exists(
        (
          t,
          l,
          r
        ) => near(l, t) && near(r, t)
      ),
      s"(#87) first section's inset from the screen Column (top, leading, trailing): $inset"
    )
    check(
      "a Spacer in a Row pushes the next label to the far edge",
      near(right("right"), bodyRight) && f("right")._1 - right("left") > 100,
      s"(#87) ${show("left", "right")}, section ends at $bodyRight"
    )
    check(
      "a grown label in a Row takes the room its sibling does not",
      near(right("grown") + 8, f("fixed")._1) && near(right("fixed"), bodyRight) && f("grown")._3 > f("fixed")._3,
      s"(#87) ${show("grown", "fixed")}, section ends at $bodyRight"
    )
    check(
      "a Spacer in a Column pushes the next label down, level with the taller column beside it",
      // and by the Spacer: "bottom" keeps its own height, rather than stretching to reach.
      near(f("top")._2, f("one")._2) && near(bottom("bottom"), bottom("four")) && near(f("bottom")._4, f("top")._4),
      s"(#87) ${show("top", "bottom", "one", "four")}"
    )
    // Labels only: the Spinner section's button is labelled "Start" too.
    val aligned = List(Alignment.Start, Alignment.Center, Alignment.End).map(_.toString)
    check(
      "Start, Center and End labels each span the section, so their alignment can show",
      aligned.forall(a => near(f(a)._1, bx) && near(f(a)._3, bw)),
      s"(#87) ${show(aligned*)}, section ${fr((bx, 0, bw, 0))}"
    )

    // What the cross-axis rule must not do on AppKit: stretch a bezelled push button across a
    // Column. The DatePicker section's button is a direct child of a section's body. UIKit's
    // Column is .fill, as GTK's and Android's are, and a UIButton has no bezel to stretch, so
    // there it fills like everything else; the width is printed for the record.
    val leap = all.find(h => kind(h, WidgetKind.Button) && AppleInspect.text(h).contains("Back to the leap day"))
    val leapW = leap.map(AppleInspect.frame(_)._3).getOrElse(0.0)
    val leapDetail = s"button ${leap.map(b => fr(AppleInspect.frame(b)))}, section ${fr((bx, 0, bw, 0))}"
    if leap.exists(b => AppleInspect.className(b) == "NSButton") then
      check("an AppKit push Button in a Column keeps its own width", leapW > 20 && leapW < bw / 2, s"(#87) $leapDetail")
    else println(s"[gallery]   (a Button in a Column fills it here: $leapDetail)")

    // A Row spans its Column, so it is wider than its children; with nothing grown the slack
    // goes after them, as on GTK — on AppKit by the stack's hugging (#87), on UIKit by the
    // slack view that ends every stack (#96). Before #96 UIKit's .fill Row gave it to one of
    // them: "Accent" was 232 pt of a 358 pt Row on the iOS 27 simulator.
    val accent = all.find(h => kind(h, WidgetKind.Button) && AppleInspect.text(h).contains("Accent"))
    val accentW = accent.map(AppleInspect.frame(_)._3).getOrElse(0.0)
    val lastButton = accent.flatMap(parentOf.get).flatMap(r => AppleInspect.children(r).lastOption)
    val lastRight = lastButton.map(b => AppleInspect.frame(b)._1 + AppleInspect.frame(b)._3).getOrElse(0.0)
    check(
      "a Row with nothing grown leaves its slack after its children",
      accentW > 20 && accentW < bw / 2 && lastRight < bodyRight - 50,
      s"(#87, #96) Accent ${accent.map(b => fr(AppleInspect.frame(b)))}, last button ends at $lastRight, section ${fr((bx, 0, bw, 0))}"
    )

    // A ZStack is as big as its largest child, in a Row with room to spare too.
    val spinnerStack =
      all.find(h => kind(h, WidgetKind.ZStack) && AppleInspect.allTexts(h).exists(_.startsWith("Underneath")))
    val under = labels("Underneath\nthe spinner").lastOption.map(AppleInspect.frame)
    check(
      "a ZStack in a Row is as wide as its widest child",
      spinnerStack.exists(z => under.exists(u => near(AppleInspect.frame(z)._3, u._3))),
      s"(#87) ZStack ${spinnerStack.map(z => fr(AppleInspect.frame(z)))}, its label ${under.map(fr)}"
    )

    if sys.env.get("THICKET_FRAME_DUMP").contains("1") then
      all.foreach { h =>
        val k = AppleApp.renderer.kindOf(h).map(_.toString).getOrElse("-")
        val t = AppleInspect.text(h).map(_.take(24)).getOrElse("")
        println(s"[frames] $k|$t|${fr(AppleInspect.frame(h))}")
      }
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
  /** Strings out of the shim, and which shim it is (#79).
    *
    * Every string the shim hands back is copied into one scratch buffer. It was a fixed 4096-byte Swift array, so text
    * of 4096 bytes or more came back as NULL — read as "this view carries no text" — and the pointer it returned had
    * escaped `withUnsafeBufferPointer`. The text here is over 6 000 bytes and multi-byte, set on each text-bearing kind
    * and read back through `sui_get_text`; a short one after it shows the grown buffer still ends where it should.
    *
    * `platform` came from a constant, "appkit", on UIKit too. It is checked against a class only the toolkit can name:
    * kind 9, a Toggle, is an `NSSwitch` on AppKit and a `UISwitch` on UIKit.
    */
  private def shimStrings(): Unit = {
    val long = "long text — ü → 文字 " * 300
    val longBytes = long.getBytes("UTF-8").length
    List(ShimKind.Label -> "Label", ShimKind.TextField -> "TextField", ShimKind.Button -> "Button").foreach {
      (
        code,
        name
      ) =>
        val h = Shim.sui_create(code)
        Zone(Shim.sui_set_text(h, toCString(long)))
        val back = AppleInspect.text(h)
        Zone(Shim.sui_set_text(h, toCString("short")))
        val short = AppleInspect.text(h)
        Shim.sui_destroy(h)
        check(
          s"a $longBytes-byte text round-trips through the shim on a $name, and a short one after it",
          back.contains(long) && short.contains("short"),
          s"read back ${back.map(_.getBytes("UTF-8").length)} bytes, then $short"
        )
    }

    val toggle = Shim.sui_create(ShimKind.Toggle)
    val switchClass = AppleInspect.className(toggle)
    Shim.sui_destroy(toggle)
    val expected = switchClass match {
      case "NSSwitch" => "appkit"
      case "UISwitch" => "uikit"
      case other      => s"(unknown: $other)"
    }
    val platform = AppleApp.renderer.platform
    check(s"the renderer names its toolkit: $platform", platform == expected, s"a Toggle is a $switchClass")
  }

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

  /** #75 (review 2026-10-20 M6, M7, M9): a tap on a leaf, a UIKit Checkbox's caption, and Start/End under a
    * right-to-left layout — each answered by the platform.
    */
  private def tapsCheckboxAndDirection(all: List[thicket.renderer.apple.Shim.Handle]): Unit = {
    def ofKind(k: WidgetKind) = all.filter(h => AppleApp.renderer.kindOf(h).contains(k))
    def texts = AppleInspect.allTexts(AppleApp.rootHandle)
    val labels = ofKind(WidgetKind.Label)

    // --- A tap on a leaf: a Label, which has no action of its own on either toolkit. Tapped
    // through the platform (a mouse-down and -up sent through the NSWindow; on UIKit, a hit test
    // at its centre and its tap recogniser), never by calling the stored handler.
    val tappable = labels.filter(h => AppleInspect.text(h).contains("Tap this label"))
    check("one tappable label", tappable.length == 1, tappable.length.toString)
    tappable.headOption.foreach { l =>
      check("the platform delivers a tap to the label", AppleInspect.tap(l))
      check(
        "and the tap reaches the app",
        texts.contains("The label was tapped 1 times"),
        texts.filter(_.startsWith("The label was")).toString
      )
    }

    // --- Checkbox: its caption is the platform's — NSButton's title, the UILabel beside the
    // UISwitch — and a click flips the control the platform shows.
    val boxes = ofKind(WidgetKind.Checkbox)
    check("one Checkbox", boxes.length == 1, boxes.length.toString)
    boxes.headOption.foreach { b =>
      check(
        "the Checkbox carries its label",
        AppleInspect.text(b).contains("Checkbox"),
        AppleInspect.text(b).toString
      )
      check(
        "and the label is drawn",
        AppleInspect.allTexts(b) == List("Checkbox") && AppleInspect.paintedFraction(b) > 0,
        s"${AppleInspect.allTexts(b)}, painted ${AppleInspect.paintedFraction(b)}"
      )
      val before = AppleInspect.checked(b)
      check("clicking it flips it", AppleInspect.click(b) && AppleInspect.checked(b) != before)
      AppleInspect.click(b)
    }

    // --- Start, Center and End, measured as where the platform paints the text across each
    // label: left to right, then with the label forced right-to-left as an Arabic or Hebrew
    // locale would lay it out. Start must follow the direction, End must oppose it, Center stay.
    val aligned = List("Start", "Center", "End").flatMap(t =>
      labels.filter(h => AppleInspect.text(h).contains(t)).lastOption.map(t -> _)
    )
    check("Start, Center and End labels", aligned.length == 3, aligned.map(_._1).toString)
    def spans(rtl: Boolean) =
      aligned.map {
        (
          t,
          h
        ) =>
          AppleInspect.setRightToLeft(h, rtl)
          t -> AppleInspect.inkSpan(h)
      }.toMap
    // An AppKit Column hugs its children, so each label is first given its column's width — the
    // width it has on GTK, Android and UIKit — or a one-word label has no room to show anything.
    aligned.foreach {
      (
        _,
        h
      ) =>
        val column = all.find(p => AppleInspect.children(p).contains(h))
        column.foreach(c => AppleInspect.pinWidth(h, Some(AppleInspect.frame(c)._3)))
    }
    println(
      s"[gallery]   label widths: ${aligned
          .map(
            (
              t,
              h
            ) => f"$t ${AppleInspect.frame(h)._3}%.0f"
          ).mkString(", ")}"
    )
    val ltr = spans(rtl = false)
    val rtl = spans(rtl = true)
    aligned.foreach {
      (
        _,
        h
      ) =>
        AppleInspect.setRightToLeft(h, false)
        AppleInspect.pinWidth(h, None)
    }
    def show(m: Map[String, Option[(Double, Double)]]) =
      List("Start", "Center", "End")
        .map(t =>
          t + m
            .get(t).flatten.fold(" none")(
              (
                a,
                b
              ) => f" $a%.2f–$b%.2f"
            )
        )
        .mkString(", ")
    println(s"[gallery]   ink, left to right: ${show(ltr)}")
    println(s"[gallery]   ink, right to left: ${show(rtl)}")
    // Each label is as wide as its column and its text is not, so the ink sits at one side.
    def atLeft(s: Option[(Double, Double)]) =
      s.exists(
        (
          a,
          b
        ) => a < 0.1 && b < 0.5
      )
    def atRight(s: Option[(Double, Double)]) =
      s.exists(
        (
          a,
          b
        ) => a > 0.5 && b > 0.9
      )
    def centred(s: Option[(Double, Double)]) =
      s.exists(
        (
          a,
          b
        ) => math.abs((a + b) / 2 - 0.5) < 0.05 && b - a < 0.5
      )
    check(
      "left to right: Start at the left, Center centred, End at the right",
      atLeft(ltr("Start")) && centred(ltr("Center")) && atRight(ltr("End")),
      show(ltr)
    )
    check(
      "right to left: Start at the right, Center centred, End at the left",
      atRight(rtl("Start")) && centred(rtl("Center")) && atLeft(rtl("End")),
      show(rtl)
    )
  }

  /** #76: the safe area added to padding, a Scroll's padding and its height, and a container's tap on release.
    *
    * Every number is the platform's: insets as the view holds them, safe areas as it reports them, and frames as Auto
    * Layout placed them. The safe area is also *changed* while the screen is up, through the root's
    * `additionalSafeAreaInsets`, because on a Mac it is zero and a check of zero plus 16 cannot tell "added" from
    * "never read".
    */
  private def insetsAndTaps(): Unit = {
    type Edges = (Double, Double, Double, Double)
    def all = AppleInspect.all(AppleApp.rootHandle)
    def ofKind(k: WidgetKind) = all.filter(h => AppleApp.renderer.kindOf(h).contains(k))
    def texts = AppleInspect.allTexts(AppleApp.rootHandle)
    def near(
      a: Double,
      b: Double
    ) = math.abs(a - b) <= 0.5
    def same(
      a: Edges,
      b: Edges
    ) = near(a._1, b._1) && near(a._2, b._2) && near(a._3, b._3) && near(a._4, b._4)
    def fmt(e: Edges) = f"(top ${e._1}%.1f, left ${e._2}%.1f, bottom ${e._3}%.1f, right ${e._4}%.1f)"
    def plus(
      p: Double,
      e: Edges
    ): Edges = (p + e._1, p + e._2, p + e._3, p + e._4)
    // The renderer's own child of a scroller: UIKit's scroll indicators are subviews too.
    def content(s: thicket.renderer.apple.Shim.Handle) =
      AppleInspect.children(s).find(c => AppleApp.renderer.kindOf(c).isDefined)
    // Where a scroller's content sits within it, and both heights: (dx, dy, scroller h, content h).
    def placed(s: thicket.renderer.apple.Shim.Handle) =
      content(s).map { c =>
        val (sx, sy, _, sh) = AppleInspect.frame(s)
        val (cx, cy, _, ch) = AppleInspect.frame(c)
        (cx - sx, cy - sy, sh, ch)
      }

    // --- SafeArea: on the screen's Scroll, which has a padding of 16 of its own ---
    // Parent first, so the first Scroll is the screen's.
    ofKind(WidgetKind.Scroll).headOption.foreach { s =>
      val safe0 = AppleInspect.safeArea(s)
      println(s"[gallery]   the screen's safe area ${fmt(safe0)}, insets ${fmt(AppleInspect.insets(s))}")
      check(
        "the screen's insets are its padding plus its safe area",
        same(AppleInspect.insets(s), plus(16, safe0)),
        s"insets ${fmt(AppleInspect.insets(s))}, safe area ${fmt(safe0)}"
      )
      def startsAtInsets =
        placed(s).exists(
          (
            x,
            y,
            _,
            _
          ) => near(x, AppleInspect.insets(s)._2) && near(y, AppleInspect.insets(s)._1)
        )
      check(
        "and its content starts there",
        startsAtInsets,
        s"${placed(s)} against ${fmt(AppleInspect.insets(s))}"
      )

      AppleInspect.setExtraSafeArea(40, 0, 24, 0)
      val safe1 = AppleInspect.safeArea(s)
      println(
        s"[gallery]   with 40 top and 24 bottom more: safe area ${fmt(safe1)}, insets ${fmt(AppleInspect.insets(s))}"
      )
      check(
        "a safe area that changes reaches the view",
        near(safe1._1, safe0._1 + 40) && near(safe1._3, safe0._3 + 24),
        s"${fmt(safe0)} -> ${fmt(safe1)}"
      )
      check(
        "and is added to the padding, which stays",
        same(AppleInspect.insets(s), plus(16, safe1)),
        s"insets ${fmt(AppleInspect.insets(s))}, safe area ${fmt(safe1)}"
      )
      check(
        "and the content moves with it",
        startsAtInsets,
        s"${placed(s)} against ${fmt(AppleInspect.insets(s))}"
      )
      AppleInspect.setExtraSafeArea(0, 0, 0, 0)
      check(
        "and back when it shrinks",
        same(AppleInspect.insets(s), plus(16, safe0)) && startsAtInsets,
        s"insets ${fmt(AppleInspect.insets(s))}, ${placed(s)}"
      )
    }

    // --- Scroll(padding = 8, horizontal): content inset by the padding, height its content's ---
    ofKind(WidgetKind.Scroll).filter(h => AppleInspect.allTexts(h).exists(_.startsWith("col 0"))).lastOption.foreach {
      s =>
        def button(t: String) = ofKind(WidgetKind.Button).find(h => AppleInspect.text(h).contains(t))
        def geometry =
          placed(s).map(
            (
              x,
              y,
              sh,
              ch
            ) => f"content at ($x%.1f, $y%.1f), scroller $sh%.1f tall, content $ch%.1f"
          )
        check(
          "a padded Scroll holds its padding as its content insets",
          same(AppleInspect.insets(s), (8, 8, 8, 8)),
          fmt(AppleInspect.insets(s))
        )
        check(
          "and its content starts 8 in",
          placed(s).exists(
            (
              x,
              y,
              _,
              _
            ) => near(x, 8) && near(y, 8)
          ),
          geometry.toString
        )
        def fits =
          placed(s).exists(
            (
              _,
              _,
              sh,
              ch
            ) => ch > 0 && near(sh, ch + 16)
          )
        check("a horizontal Scroll is its content's height plus its padding", fits, geometry.toString)
        check(
          "with one constraint on its height",
          AppleInspect.heightConstraints(s) == 1,
          s"${AppleInspect.heightConstraints(s)} height constraints"
        )
        println(s"[gallery]   horizontal Scroll: ${geometry.getOrElse("no content")}")
        val shortHeight = placed(s).map(_._4).getOrElse(0.0)
        button("Taller columns").foreach(AppleInspect.click)
        check(
          "its child replaced by a taller one, it follows the new height",
          fits && placed(s).exists(_._4 > shortHeight + 1),
          geometry.toString
        )
        check(
          "still with one constraint on its height",
          AppleInspect.heightConstraints(s) == 1,
          s"${AppleInspect.heightConstraints(s)} height constraints"
        )
        println(s"[gallery]   replaced: ${geometry.getOrElse("no content")}")
        button("Shorter columns").foreach(AppleInspect.click)
        check(
          "and back to the shorter one",
          fits && placed(s).exists(p => near(p._4, shortHeight)) && AppleInspect.heightConstraints(s) == 1,
          s"$geometry ${AppleInspect.heightConstraints(s)} height constraints"
        )
    }

    // --- A container's tap: a press and a release inside it, sent through the window ---
    ofKind(WidgetKind.Row).find(h => AppleInspect.allTexts(h).contains("Tap this row")).foreach { row =>
      import AppleInspect.Mouse
      def tapped = texts.find(_.startsWith("Row tapped")).getOrElse("(none)")
      val (_, _, w, h) = AppleInspect.frame(row)
      if AppleInspect.sendMouse(row, Mouse.Down, w / 2, h / 2) then {
        check("a press alone is not a tap", tapped == "Row tapped 0 times", tapped)
        AppleInspect.sendMouse(row, Mouse.Up, w / 2, h / 2)
        check("the release inside the row is", tapped == "Row tapped 1 times", tapped)
        AppleInspect.sendMouse(row, Mouse.Down, w / 2, h / 2)
        AppleInspect.sendMouse(row, Mouse.Dragged, w + 40, h / 2)
        AppleInspect.sendMouse(row, Mouse.Up, w + 40, h / 2)
        check("a press dragged off the row and released outside is not", tapped == "Row tapped 1 times", tapped)
      } else
        println(
          "[gallery]   (UIKit offers no public way to synthesise a touch; its container tap is a " +
            "UITapGestureRecognizer, which recognises on touch-up)"
        )
    }
  }

}
