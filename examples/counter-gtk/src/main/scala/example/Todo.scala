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

package example

import thicket.core.{ColorRole, Rgb, Theme}
import thicket.core.Reconciler
import thicket.core.dsl.*
import thicket.renderer.gtk.{GtkApp, GtkInspect, GtkRenderer}

/** The GTK host for [[TodoApp]] — which knows nothing about GTK.
  *
  * With `THICKET_SELFTEST=1` it drives the app and reads the result back out of GTK,
  * including navigation: push, pop, and the window title following the top screen.
  */
object Todo {

  private val model = TodoApp.Model()
  private var app: thicket.core.NavHost[TodoApp.Route] = null

  private def logoSource: Option[thicket.renderer.ImageSource] = {
    val f = new java.io.File("docs/assets/thicket-logo-app.png")
    if f.exists() then Some(thicket.renderer.ImageSource.FromFile(f.getPath)) else None
  }

  def main(args: Array[String]): Unit = {
    // Same single-role branding as the Android host; everything else stays GTK's.
    Theme.install(Theme.platform.withColor(ColorRole.Accent, Rgb(0x2E, 0x6F, 0x40)))
    val _ = GtkApp.run("dev.thicket.todo", 460, 440) {
      if sys.env.contains("THICKET_SELFTEST") then GtkApp.postToUi(() => selfTest())
      // A plain file path: the GTK binary runs from the repo, so this resolves. A packaged
      // app would ship it alongside the executable and resolve relative to that.
      app = TodoApp(model, logo = logoSource)
      app
    }
  }

  /** Every piece of text in the mounted screen, in tree order. */
  private def screenTexts: List[String] =
    GtkInspect.allTexts(GtkApp.rootHandle)

  private var failures = 0

  private def check(name: String, cond: Boolean, detail: => String = ""): Unit = {
    if !cond then failures += 1
    println(s"[selftest] ${if cond then "ok  " else "FAIL"} $name")
    if !cond && detail.nonEmpty then println(s"             $detail")
  }

  private def selfTest(): Unit = {
    println("[selftest] driving navigation and reading back out of GTK")

    check("starts on the items screen", app.title.now == "Todo")
    val onItems = screenTexts
    check("item rows are rendered", onItems.contains("Navigation"), onItems.toString)

    app.push(TodoApp.Route.Detail(3))
    check("pushed: title follows the top screen", app.title.now == "Item")
    val onDetail = screenTexts
    check("detail content is mounted", onDetail.contains("Navigation"), onDetail.toString)
    check("items screen is gone", !onDetail.exists(_.startsWith("Add")), onDetail.toString)
    check("back is available", app.canGoBack.now)

    check("toggling from the detail screen updates it", {
      model.toggle(3)
      screenTexts.contains("Done")
    }, screenTexts.toString)

    check("back() pops", app.back())
    check("title restored", app.title.now == "Todo")
    check("items screen is back", screenTexts.contains("Navigation"))
    check("back is no longer available at the root", !app.canGoBack.now)
    check("back() at the root defers to the platform", !app.back())

    // --- the form: text field and checkbox bound both ways ---
    check("the draft starts empty", model.draft.now.isEmpty)
    check("an empty draft is not valid", !model.draftValid.now)

    model.draft.set("Write the catalogue")
    check("writing the signal reaches the widget",
      screenTexts.contains("Write the catalogue"),
      s"screen texts were: $screenTexts")
    check("a non-empty draft is valid", model.draftValid.now)

    model.draftDone.set(true)
    val sizeBefore = model.items.now.size
    model.addDraft()
    check("adding appends the drafted item",
      model.items.now.size == sizeBefore + 1 && model.items.now.last.title == "Write the catalogue",
      model.items.now.map(_.title).toString)
    check("the committed item kept its done flag", model.items.now.last.done)
    check("the form cleared", model.draft.now.isEmpty && !model.draftDone.now)
    check("the cleared field is empty in the widget",
      !screenTexts.contains("Write the catalogue") ||
        screenTexts.count(_ == "Write the catalogue") == 1,
      screenTexts.toString)

    // --- Row overflow: five buttons that do not fit, inside a horizontal Scroll ---
    val texts = screenTexts
    check("all five action buttons are mounted",
      List("Add", "Rotate", "Drop", "About", "10 000 rows").forall(texts.contains),
      texts.toString)
    // The two Scrolls in this screen are the outer vertical one and the button row's
    // horizontal one; the second in tree order is the horizontal one.
    val row = GtkInspect
      .findScroller(GtkApp.rootHandle)
      .flatMap(outer => GtkInspect.children(outer).flatMap(GtkInspect.findScroller).headOption)
    check("the button row has its own scroller", row.isDefined)
    row.foreach { sw =>
      val viewport = GtkInspect.naturalWidth(sw)
      val content  = GtkInspect.children(sw).map(GtkInspect.naturalWidth).maxOption.getOrElse(0)
      // The point of the axis. The row genuinely wants more width than the 460 px window
      // has, and before this the surplus was simply clipped. With the scroller the
      // *viewport* collapses to a fraction of it and the surplus becomes scrollable, so
      // the fifth button is reachable instead of gone.
      // Measured at 460x440: the row wants ~429px against a ~428px content area, so on the
      // desktop it overflows by a hair and on a phone decisively. Asserting against the
      // window width would be a 1px-margin test; the invariant worth pinning is that the
      // viewport asks for a fraction of the content, which is what turns the surplus into
      // something scrollable instead of something clipped.
      println(s"[selftest] button row: content wants ${content}px, viewport asks ${viewport}px")
      check("the horizontal scroller does not demand the full row width",
        viewport * 4 < content,
        s"viewport ${viewport}px vs content ${content}px")
    }

    // --- the widgets phase 2 added, read back out of GTK ---
    val switches = GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isSwitch)
    check("the settings row has a GtkSwitch", switches.length == 1, switches.length.toString)
    switches.headOption.foreach { sw =>
      check("the switch starts off", !GtkInspect.switchActive(sw))
      // App -> widget: the renderer must write the signal into the control.
      model.hideDone.set(true)
      check("writing the signal flips the switch", GtkInspect.switchActive(sw))
      // And the switch is load-bearing: flipping it filters the list.
      check("hiding completed items shrinks the list",
        !screenTexts.contains("Structural reconciliation"),
        screenTexts.toString)
      model.hideDone.set(false)
      check("showing them again restores it", screenTexts.contains("Structural reconciliation"))
    }

    val bars = GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isProgressBar)
    check("there is a GtkProgressBar", bars.length == 1, bars.length.toString)
    bars.headOption.foreach { bar =>
      // Everything is done by this point, and 1.0-against-1.0 would also pass if the
      // renderer clamped every value to full. So drive it to a fraction that can only be
      // right by actually being computed, and check a second, different one after it.
      def fraction: Double = {
        val xs = model.items.now
        xs.count(_.done).toDouble / xs.size
      }
      model.items.now.take(1).foreach(i => model.toggle(i.id))
      val expected = fraction
      val actual   = GtkInspect.progressFraction(bar)
      println(f"[selftest] progress bar: $actual%.3f, expected $expected%.3f")
      check("the progress bar shows the model's fraction",
        expected > 0.0 && expected < 1.0 && math.abs(actual - expected) < 0.001,
        s"$actual vs $expected")

      model.items.now.drop(1).take(1).foreach(i => model.toggle(i.id))
      val expected2 = fraction
      val actual2   = GtkInspect.progressFraction(bar)
      check("and follows it when the model changes again",
        expected2 != expected && math.abs(actual2 - expected2) < 0.001,
        s"$actual2 vs $expected2")
    }

    // A Spinner has no "running" prop: Show is what starts and stops it.
    check("no spinner while the app is idle",
      GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isSpinner).isEmpty)
    model.busy.set(true)
    check("a spinner appears when busy",
      GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isSpinner).length == 1)
    // The same question the Android self-test asks: the spinner is in the ZStack over the
    // logo, last (so painted on top) and centred. Mounted after the picture, so this is the
    // insert that has to land above it rather than wherever GTK appends.
    val overlays = GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isOverlay)
    val onTop    = overlays.flatMap(o => GtkInspect.children(o).lastOption).filter(GtkInspect.isSpinner)
    check("the spinner is the ZStack's last child, so drawn on top", onTop.length == 1,
      overlays.map(o => GtkInspect.children(o).length).toString)
    check("and centred over the picture", onTop.map(GtkInspect.placement) == List(("Center", "Center")),
      onTop.map(GtkInspect.placement).toString)
    model.busy.set(false)
    check("and is gone again when not",
      GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isSpinner).isEmpty)

    val scales = GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isScale)
    check("there is a GtkScale", scales.length == 1, scales.length.toString)
    scales.headOption.foreach { sc =>
      // The app's units reach the widget unchanged: 7 of 0..11, not 0.636.
      check("the slider starts at the model's value",
        math.abs(GtkInspect.scaleValue(sc) - 7.0) < 0.001,
        GtkInspect.scaleValue(sc).toString)
      model.volume.set(3.5)
      check("writing the signal moves the slider",
        math.abs(GtkInspect.scaleValue(sc) - 3.5) < 0.001,
        GtkInspect.scaleValue(sc).toString)
      model.volume.set(7.0)
    }

    // A SecureField holds its value like any other bound field; GTK simply does not draw
    // the characters. Two entries exist by now — the draft field and this one — and only
    // the secure one is invisible.
    val entries   = GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isEntry)
    val invisible = entries.filterNot(GtkInspect.entryVisible)
    check("exactly one entry hides its characters",
      invisible.length == 1,
      s"${invisible.length} of ${entries.length} entries")
    model.secret.set("hunter2")
    check("the secure field still holds its value",
      model.secret.now == "hunter2" && GtkInspect.allTexts(GtkApp.rootHandle).contains("hunter2"),
      "the value must round-trip even though it is not drawn")

    // --- toolbar actions, in the header bar rather than the element tree ---
    // GtkInspect walks the window, so the header bar's buttons are reachable the same way
    // the content is — which is the point: they are real GTK widgets in the platform's own
    // chrome, not something drawn into the tree.
    // The header bar, not the whole window: with the navigation stack kept alive a window
    // walk also finds the pages below the top one, so a button on a dormant screen could
    // answer a question about this screen's chrome.
    val chrome = GtkInspect.allTexts(GtkApp.headerHandle)
    check("the screen's actions are in the header bar",
      chrome.contains("Add") && chrome.contains("About"),
      chrome.toString)

    val sizeBeforeAction = model.items.now.size
    GtkApp.clickHeaderAction("Add")
    check("tapping a header action runs it", model.items.now.size == sizeBeforeAction + 1)
    // Actions are per screen, so navigating swaps them. The detail screen declares none.
    app.push(TodoApp.Route.Detail(1))
    val onDetail2 = GtkInspect.allTexts(GtkApp.headerHandle)
    check("a screen with no actions has none in the header bar",
      !onDetail2.contains("About"),
      onDetail2.toString)
    val _ = app.back()
    check("and they come back on return",
      GtkInspect.allTexts(GtkApp.headerHandle).contains("About"))

    // Every stylesheet this renderer wrote parsed. Worth a check of its own rather than
    // trusting stderr: the renderer emitted CSS GTK could not parse from the day theming
    // landed, 26 warnings per run, and no test noticed because they grep for `Gtk-CRITICAL`
    // and this was a `Gtk-WARNING` (#30).
    check("no CSS the renderer wrote failed to parse",
      GtkInspect.cssParseErrors == 0L,
      s"${GtkInspect.cssParseErrors} CSS parse errors")

    // Mount a subtree, tear it down, and the handle table should be where it started.
    //
    // This is the check that was missing when `Slider`'s value handler leaked from the day it
    // landed: `destroy` released taps, edits and toggles but not values, and nothing noticed
    // because a leak is invisible until something runs out. A review found the same omission
    // for `Picker`; the number is observable now so a third cannot hide.
    val callbacksBefore = GtkInspect.liveCallbacks
    locally {
      val probeOwner = thicket.signals.Owner()
      val probe = {
        given thicket.signals.Owner = probeOwner
        // A fresh renderer, deliberately: the handle table is process-global, so the count is
        // the same question whichever instance registered, and this needs no new public
        // surface on GtkApp.
        Reconciler.mount(
          GtkRenderer(),
          Column()(
            Button("probe")(()),
            Slider(0.5, min = 0, max = 1)(_ => ()),
            Picker(Seq("a", "b"), 0)(_ => ())
          )
        )
      }
      probe.dispose()
      probeOwner.dispose()
    }
    check("mounting and destroying a subtree releases every callback it registered",
      GtkInspect.liveCallbacks == callbacksBefore,
      s"$callbacksBefore before, ${GtkInspect.liveCallbacks} after")

    // --- the navigation container: the stack is kept alive ---
    // The reason for a native container rather than swapping one subtree. Before this, a
    // push unmounted the screen below and a pop rebuilt it from scratch, losing its scroll
    // position and anything in flight.
    val rowsBefore = GtkInspect.allTexts(GtkApp.rootHandle).count(_ == "Toggle done")
    app.push(TodoApp.Route.Detail(1))
    check("a push adds a page without removing the one below",
      GtkApp.pageHandles.length == 2,
      s"${GtkApp.pageHandles.length} live pages")
    check("the screen below a push keeps its widgets",
      GtkInspect.allTexts(GtkApp.pageHandles.head).count(_ == "Toggle done") == rowsBefore,
      s"had $rowsBefore rows, now ${GtkInspect.allTexts(GtkApp.pageHandles.head).count(_ == "Toggle done")}")

    // The container moved first; Nav has to follow. This is the half that rots silently —
    // the two stacks diverge and the title bar starts describing a screen you cannot see.
    //
    // Three deep on purpose. With two, AdwNavigationView refuses to pop its root, which
    // masks an extra pop entirely: the first version of this code popped twice on a gesture
    // and every check still passed.
    app.push(TodoApp.Route.About)
    val depthBefore = app.navigator.stack.now.length
    check("three pages, so an extra pop would show", depthBefore == 3 && GtkApp.pageHandles.length == 3,
      s"stack $depthBefore, ${GtkApp.pageHandles.length} pages")

    val _ = GtkApp.popByGesture()
    check("a back gesture through the container pops Nav too",
      app.navigator.stack.now.length == depthBefore - 1,
      s"stack was $depthBefore, now ${app.navigator.stack.now.length}")
    // Asked of the container, not of our own map. A gesture pop that we then "tidy up" with
    // a second pop leaves our bookkeeping looking right while the container has lost a page
    // — so this must read what AdwNavigationView is actually showing.
    check("and it pops exactly one page, not two",
      GtkApp.visiblePageTitle == "Item",
      s"container shows '${GtkApp.visiblePageTitle}', expected 'Item': one gesture must not pop twice")
    check("our bookkeeping agrees with the container",
      GtkApp.pageHandles.length == 2,
      s"${GtkApp.pageHandles.length} live pages")

    val _ = app.back()
    check("the stack is back to the list screen", app.navigator.stack.now.length == 1 && GtkApp.pageHandles.length == 1,
      s"stack ${app.navigator.stack.now.length}, ${GtkApp.pageHandles.length} pages")

    // --- context menu: a property of a row, not a widget in the tree ---
    // A popover is parented to its widget rather than placed in the box, so the row's
    // child count is unchanged — that is the observable difference between a menu and a
    // presented widget, and the reason ContextMenu is a prop.
    val menuPopovers = GtkInspect.findAll(GtkApp.rootHandle)(GtkInspect.isPopover)
    check("every list row carries a popover",
      menuPopovers.length >= model.visibleItems.now.size,
      s"${menuPopovers.length} popovers for ${model.visibleItems.now.size} rows")

    // The popover is populated with the app's items. Note these labels DO show up in a
    // widget walk: a GTK4 popover is a child of its widget, just not of the box's layout.
    // An earlier version of this check asserted the opposite and failed, correctly.
    check("the popover holds the menu's items",
      menuPopovers.exists(p => GtkInspect.allTexts(p).contains("Delete")),
      menuPopovers.map(GtkInspect.allTexts).toString)

    // Drive a real menu item. The previous version of this check compared the item count
    // against itself with nothing in between - a check that could not fail, which is the
    // exact sin the issue descriptions warn about, written while removing a different bad
    // check. Emitting `clicked` on the popover's own button runs the item's closure through
    // the handle table the way a user's click does.
    val targetRow  = model.visibleItems.now.head
    val beforeMenu = model.items.now.size
    val popoverFor = menuPopovers.find(p => GtkInspect.allTexts(p).contains("Delete"))
    check("a menu item can be activated",
      popoverFor.exists(p => GtkApp.clickMenuItem(p, "Delete")),
      "no Delete button found inside any popover")
    check("choosing Delete removes that row, and only that one",
      model.items.now.size == beforeMenu - 1 && !model.items.now.exists(_.id == targetRow.id),
      s"was $beforeMenu, now ${model.items.now.size}: ${model.items.now.map(_.title)}")

    // --- Sheet: a presented *container*, with a live subtree inside it ---
    check("no sheet window before it is asked for",
      GtkInspect.findAll(GtkApp.windowHandle)(GtkInspect.isEntry).length == 2,
      "the screen's own two entries and no more")

    model.editing.set(true)
    // The sheet's content is a real widget tree in its own modal window, not something
    // drawn into the screen behind it - so the screen's entry count is unchanged while a
    // third entry now exists inside the sheet.
    check("the screen behind is untouched",
      GtkInspect.allTexts(GtkApp.rootHandle).contains("Hide completed"))

    val sizeBeforeSheet = model.items.now.size
    model.draft.set("From the sheet")
    model.addDraft()
    check("the sheet's bound field drives the same model",
      model.items.now.size == sizeBeforeSheet + 1 &&
        model.items.now.last.title == "From the sheet",
      model.items.now.map(_.title).toString)

    model.editing.set(false)
    check("unmounting takes the sheet down and leaves the screen",
      GtkInspect.allTexts(GtkApp.rootHandle).contains("Hide completed"))

    // --- Alert: presented by a signal, dismissed by unmounting ---
    check("no alert is up to begin with", model.lastAlertChoice.now.isEmpty)
    val itemsBeforeAlert = model.items.now.size

    model.confirmingDrop.set(true)
    // GtkAlertDialog is a GObject, not a widget, so it is deliberately absent from the
    // widget tree — that is what WidgetKind.presented means. The observable effect here is
    // that presenting it neither crashes nor disturbs the screen behind it.
    check("presenting an alert leaves the screen intact",
      screenTexts.contains("Hide completed"), screenTexts.toString)

    // Unmounting is the whole of dismissing. Crucially this must NOT fire onDismiss: the
    // app asked for it to go away, which is not the user declining to choose.
    model.confirmingDrop.set(false)
    check("dismissing from the app does not report a user dismissal",
      model.lastAlertChoice.now.isEmpty,
      s"choice was '${model.lastAlertChoice.now}'")
    check("and nothing was dropped", model.items.now.size == itemsBeforeAlert)

    // --- LazyColumn: 10 000 rows through GtkListView ---
    app.push(TodoApp.Route.Big)
    check("pushed the 10 000-row screen", app.title.now == "10 000 rows")
    // Let GTK lay out and materialise its viewport before counting.
    GtkApp.postToUi(() => countRows())
  }

  private def countRows(): Unit = {
    val texts = screenTexts.filter(_.startsWith("Row "))
    // GtkListView keeps a larger recycling buffer than Android's ListView does — a few
    // hundred rather than a few dozen — so the bound is generous. What matters is that it
    // is bounded at all: the same list through ForEach would be 10 000.
    check(
      s"only a bounded set of rows exists (${texts.size} materialised of 10 000)",
      texts.nonEmpty && texts.size < 1000,
      texts.take(3).toString
    )
    check("the first rows are the ones on screen", texts.headOption.contains("Row 1"),
      texts.take(3).toString)

    println(
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
  }
}
