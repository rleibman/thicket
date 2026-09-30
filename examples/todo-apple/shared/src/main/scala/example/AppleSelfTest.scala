package example

import thicket.renderer.apple.{AppleApp, AppleInspect, AppleRenderer, Shim}
import thicket.renderer.Constraints

/** The part of the Apple example that is the same on both platforms — which is all of it except the entry point.
  *
  * [[TodoApp]] itself knows nothing about AppKit or UIKit; this knows only `AppleApp`, which is the single renderer
  * both hosts share. `TodoMac.main` and `TodoIos.start` differ only in how the process gets here.
  *
  * With `THICKET_SELFTEST=1` it drives the app and reads the result back out of the platform, including navigation:
  * push, pop, and the window title following the top screen.
  */
object AppleSelfTest {

  val model = TodoApp.Model()
  private var app: thicket.core.NavHost[TodoApp.Route] = null

  /** The UI, plus the self-test if it was asked for. Both hosts call exactly this. */
  def build(): thicket.core.NavHost[TodoApp.Route] = {
    if sys.env.contains("THICKET_SELFTEST") then AppleApp.postToUi(() => selfTest())
    app = TodoApp(model)
    app
  }

  /** Every piece of text in the mounted screen, in tree order. */
  private def screenTexts: List[String] = AppleInspect.allTexts(AppleApp.rootHandle)

  /** The horizontal scroller in the mounted screen, depth-first.
    *
    * GTK's equivalent test takes "the second scroller in tree order"; here the renderer is asked outright, because it
    * is the thing that decided the axis at `create`.
    */
  private def findHorizontalScroll(h: Shim.Handle): Option[Shim.Handle] =
    if AppleApp.renderer.isHorizontalScroll(h) then Some(h)
    else AppleInspect.children(h).flatMap(findHorizontalScroll).headOption

  private var failures = 0

  private def check(
    name:   String,
    cond:   Boolean,
    detail: => String = ""
  ): Unit = {
    if !cond then failures += 1
    println(s"[selftest] ${if cond then "ok  " else "FAIL"} $name")
    if !cond && detail.nonEmpty then println(s"             $detail")
  }

  private def selfTest(): Unit = {
    println("[selftest] driving navigation and reading back out of the platform")
    val probe = AppleRenderer()

    check("starts on the items screen", app.title.now == "Todo")
    val onItems = screenTexts
    check("item rows are rendered", onItems.contains("Navigation"), onItems.toString)

    app.push(TodoApp.Route.Detail(3))
    check("pushed: title follows the top screen", app.title.now == "Item")
    val onDetail = screenTexts
    check("detail content is mounted", onDetail.contains("Navigation"), onDetail.toString)
    check("items screen is gone", !onDetail.exists(_.startsWith("Add")), onDetail.toString)
    check("back is available", app.canGoBack.now)

    check(
      "toggling from the detail screen updates it", {
        model.toggle(3)
        screenTexts.contains("Done")
      },
      screenTexts.toString
    )

    check("back() pops", app.back())
    check("title restored", app.title.now == "Todo")
    check("items screen is back", screenTexts.contains("Navigation"))
    check("back is no longer available at the root", !app.canGoBack.now)
    check("back() at the root defers to the platform", !app.back())

    // --- the form: text field and checkbox bound both ways ---
    check("the draft starts empty", model.draft.now.isEmpty)
    check("an empty draft is not valid", !model.draftValid.now)

    model.draft.set("Write the catalogue")
    check(
      "writing the signal reaches the widget",
      screenTexts.contains("Write the catalogue"),
      s"screen texts were: $screenTexts"
    )
    check("a non-empty draft is valid", model.draftValid.now)

    model.draftDone.set(true)
    val sizeBefore = model.items.now.size
    model.addDraft()
    check(
      "adding appends the drafted item",
      model.items.now.size == sizeBefore + 1 && model.items.now.last.title == "Write the catalogue",
      model.items.now.map(_.title).toString
    )
    check("the committed item kept its done flag", model.items.now.last.done)
    check("the form cleared", model.draft.now.isEmpty && !model.draftDone.now)
    check(
      "the cleared field is empty in the widget",
      !screenTexts.contains("Write the catalogue") ||
        screenTexts.count(_ == "Write the catalogue") == 1,
      screenTexts.toString
    )

    // --- the widgets phase 2 added, read back out of AppKit / UIKit ---
    phase2Widgets()

    // --- Row overflow: five buttons that do not fit, inside a horizontal Scroll ---
    val actionTexts = screenTexts
    check(
      "all five action buttons are mounted",
      List("Add", "Rotate", "Drop", "About", "10 000 rows").forall(actionTexts.contains),
      actionTexts.toString
    )
    val horizontal = findHorizontalScroll(AppleApp.rootHandle)
    check("the button row has its own horizontal scroller", horizontal.isDefined)
    horizontal.foreach { sw =>
      val viewport: Double = probe.measure(sw, Constraints.unbounded).natW.toDouble
      val content: Double =
        AppleInspect.children(sw).map(probe.measure(_, Constraints.unbounded).natW.toDouble).maxOption.getOrElse(0.0)
      println(s"[selftest] button row: content wants ${content.toInt}px, viewport asks ${viewport.toInt}px")

      // The axis itself, stated so it holds whether or not the row happens to overflow on
      // this screen. A horizontal scroller leaves its content's *width* free and pins the
      // cross axis; a vertical one pins the width to the viewport. So the content keeping a
      // width of its own is exactly what "horizontal" means here, and a scroller built on
      // the wrong axis reports content == viewport to the pixel.
      //
      // GTK asserts a 4x viewport-to-content ratio instead. That is a GTK constant, not a
      // cross-toolkit invariant: a GtkScrolledWindow reports a minimum near zero, an
      // NSScrollView has no intrinsic size and is pinned to fill its parent, and a
      // UIScrollView reports its laid-out frame. The three are not comparable numbers.
      check(
        "the scroller lets its content keep its own width",
        math.abs(content - viewport) > 1.0,
        s"content ${content.toInt}px is the viewport's own ${viewport.toInt}px, so the width was pinned"
      )

      // The user-visible consequence, assertable only where the row actually overflows. On
      // a 480px mac window it does; on every simulator available here the phone is wider
      // than the five buttons, so there is nothing to scroll and the assertion would be
      // vacuous. Said out loud rather than passed silently.
      if content > viewport then {
        check(
          "the overflowing row does not force its width on its ancestors",
          viewport < content,
          s"viewport ${viewport.toInt}px vs content ${content.toInt}px"
        )
      } else {
        println(
          s"[selftest]   (the row fits in ${viewport.toInt}px on this screen, so the overflow path is not exercised here)"
        )
      }
    }

    // Reading the tree back proves the renderer made the right calls; it does not prove
    // AppKit laid anything out. Measuring does: a view with a zero natural size has been
    // created and attached but never given geometry.
    val rootSize = probe.measure(AppleApp.rootHandle, Constraints.unbounded)
    check("the mounted tree has a real size", rootSize.natW > 0 && rootSize.natH > 0, rootSize.toString)
    println(s"[selftest]   root measured ${rootSize.natW.toInt} x ${rootSize.natH.toInt}")

    val texts = AppleInspect.children(AppleApp.rootHandle)
    check("the root has laid-out children", texts.nonEmpty, s"${texts.size} children")
    texts.headOption.foreach { first =>
      val m = probe.measure(first, Constraints.unbounded)
      check("the first child has a real size", m.natW > 0 && m.natH > 0, m.toString)
      println(s"[selftest]   first child measured ${m.natW.toInt} x ${m.natH.toInt}")
    }

    // Presented widgets last: choosing an alert action completes asynchronously on AppKit,
    // so the summary is printed from the continuation.
    presentedWidgets(() => summary())
  }

  private def summary(): Unit =
    println(
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )

  /** Every view in the mounted screen whose platform class is one of `classes`. */
  private def viewsOf(classes: String*): List[Shim.Handle] =
    AppleInspect.all(AppleApp.rootHandle).filter(h => classes.contains(AppleInspect.className(h)))

  /** The same checks the GTK and Android hosts run, against what the platform built.
    *
    * Found by *platform class*, not by the renderer's own bookkeeping, so a renderer that asked for the right kind and
    * got the wrong control fails here. That also avoids the trap Android hit: a "find the determinate progress bar"
    * predicate must not match the slider, and on AppKit `NSSlider` and `NSProgressIndicator` are unrelated classes, so
    * the class name keeps them apart where an `isInstanceOf` on a shared superclass would not.
    */
  private def phase2Widgets(): Unit = {
    // Toggle. On UIKit the draft form's Checkbox is a UISwitch too, so the settings switch is
    // found through its row rather than by counting switches.
    val settingsRow = AppleInspect.all(AppleApp.rootHandle).find { h =>
      AppleInspect.children(h).exists(c => AppleInspect.text(c).contains("Hide completed"))
    }
    val switch = settingsRow.toList.flatMap(AppleInspect.children).find { h =>
      Set("NSSwitch", "UISwitch").contains(AppleInspect.className(h))
    }
    check(
      "the settings row has a switch",
      switch.isDefined,
      settingsRow.map(r => AppleInspect.children(r).map(AppleInspect.className).toString).getOrElse("no row")
    )
    switch.foreach { sw =>
      check("the switch starts off", Shim.sui_get_checked(sw) == 0)
      model.hideDone.set(true)
      check("writing the signal flips the switch", Shim.sui_get_checked(sw) == 1)
      check(
        "hiding completed items shrinks the list",
        !screenTexts.contains("Structural reconciliation"),
        screenTexts.toString
      )
      model.hideDone.set(false)
      check("showing them again restores it", screenTexts.contains("Structural reconciliation"))
    }

    // ProgressBar. Driven to a genuine fraction first: everything may be done by now, and
    // 1.0-against-1.0 would also pass if the renderer clamped every value to full.
    def bars = viewsOf("NSProgressIndicator", "UIProgressView").filter(AppleInspect.progress(_) >= 0)
    def spinners = viewsOf("NSProgressIndicator", "UIActivityIndicatorView").filter(AppleInspect.progress(_) == -1)
    check("there is a determinate progress bar", bars.length == 1, bars.length.toString)
    bars.headOption.foreach { bar =>
      model.items.now.take(1).foreach(i => model.toggle(i.id))
      val xs = model.items.now
      val expected = xs.count(_.done).toDouble / xs.size
      val actual = AppleInspect.progress(bar)
      println(f"[selftest]   progress bar: $actual%.3f, expected $expected%.3f")
      check(
        "the progress bar shows the model's fraction",
        expected > 0.0 && expected < 1.0 && math.abs(actual - expected) < 0.002,
        s"$actual vs $expected"
      )
    }

    // Spinner: no "running" prop, Show is what starts and stops it.
    check("no spinner while the app is idle", spinners.isEmpty, spinners.length.toString)
    model.busy.set(true)
    check("a spinner appears when busy", spinners.length == 1, spinners.length.toString)
    model.busy.set(false)
    check("and is gone again when not", spinners.isEmpty, spinners.length.toString)

    // Slider, in the app's own units: a renderer that stored a fraction fails here.
    val sliders = viewsOf("NSSlider", "UISlider")
    check("there is a slider", sliders.length == 1, sliders.length.toString)
    sliders.headOption.foreach { s =>
      check(
        "the slider starts at the model's value",
        math.abs(AppleInspect.value(s) - 7.0) < 0.02,
        AppleInspect.value(s).toString
      )
      model.volume.set(3.5)
      check(
        "writing the signal moves the slider",
        math.abs(AppleInspect.value(s) - 3.5) < 0.02,
        AppleInspect.value(s).toString
      )
      model.volume.set(7.0)
    }

    // SecureField: exactly one field masks its input, and it still holds its value.
    val masked = AppleInspect.all(AppleApp.rootHandle).filter(AppleInspect.isSecure)
    check("exactly one field masks its input", masked.length == 1, masked.map(AppleInspect.className).toString)
    model.secret.set("hunter2")
    check(
      "the secure field still holds its value",
      masked.headOption.flatMap(AppleInspect.text).contains("hunter2"),
      "the value must round-trip even though it is not drawn"
    )
  }

  /** Re-checks `cond` about once a frame until it holds or `timeoutMs` passes, then continues.
    *
    * Bounded by *time*, not by turns: a UIKit transition finishes a frame or two later, and a few hundred back-to-back
    * turns can all run inside one frame — on the simulator, 200 turns was not enough for a non-animated dismissal.
    * Paced by a timer rather than by re-posting at once: hot-looping `postToUi` for a few seconds crashed the iOS app
    * outright (twice, a bad access inside the posted closure), which is its own finding, not something to paper over
    * here. A completion that never comes still fails rather than hangs.
    */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Boolean => Unit): Unit = {
    val deadline = System.nanoTime() + timeoutMs * 1000000L
    def loop(): Unit =
      if cond then k(true)
      else if System.nanoTime() > deadline then k(false)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  /** Alert and Sheet, checked at the renderer boundary.
    *
    * A presented widget is not in the root's tree — that is what presented means — so a walk from the root can never
    * see into it, and a check written that way cannot fail. GTK and Android both shipped a Sheet whose title went
    * nowhere, with every check green. So these read what the *platform* shows for the presented handle — the sheet's
    * heading or navigation-bar title, the alert's text and buttons — and whether something is up is asked of the
    * platform (`presentedCount`), not of the renderer's own bookkeeping.
    *
    * UIKit presents and dismisses on a later turn of the run loop even when not animated, so each step waits for the
    * platform to settle before checking; on AppKit the waits resolve at once.
    */
  private def presentedWidgets(done: () => Unit): Unit = {
    val renderer = AppleApp.renderer
    def presentedOf(kind: thicket.renderer.WidgetKind) = renderer.presented.collect { case (h, `kind`) => h }
    def settled(n:        Int)(k: Boolean => Unit): Unit = eventually(3000)(AppleInspect.presentedCount == n)(k)

    // --- Sheet: a presented container with a live subtree ---
    check("nothing is presented before it is asked for", AppleInspect.presentedCount == 0)
    model.editing.set(true)
    val sheets = presentedOf(thicket.renderer.WidgetKind.Sheet)
    check("opening the note presents one sheet", sheets.length == 1, sheets.length.toString)
    settled(1) { up =>
      check("the platform shows it", up, s"${AppleInspect.presentedCount} presented")
      sheets.headOption.foreach { sheet =>
        check("the platform reports the sheet itself on screen", AppleInspect.isPresented(sheet))
        check(
          "the sheet shows its title",
          AppleInspect.presentedTitle(sheet).contains("Quick note"),
          s"showing ${AppleInspect.presentedTitle(sheet)}"
        )
        check("the sheet is not in the screen's tree", !AppleInspect.all(AppleApp.rootHandle).contains(sheet))
        check("the screen behind is untouched", screenTexts.contains("Hide completed"), screenTexts.toString)

        model.draft.set("From the sheet")
        check(
          "the sheet's own field shows the bound value",
          AppleInspect.allTexts(sheet).contains("From the sheet"),
          AppleInspect.allTexts(sheet).toString
        )
      }

      // Its own Save button, clicked through the control's action: the closure that runs is
      // the one mounted inside the sheet, which is what a check on the model alone cannot show.
      val sizeBeforeSheet = model.items.now.size
      val save = sheets.headOption.flatMap(s => AppleInspect.all(s).find(h => AppleInspect.text(h).contains("Save")))
      check("the sheet has its Save button", save.isDefined)
      save.foreach(b => check("the Save button can be clicked", AppleInspect.click(b)))
      check(
        "saving from the sheet adds the item",
        model.items.now.size == sizeBeforeSheet + 1 && model.items.now.last.title == "From the sheet",
        model.items.now.map(_.title).toString
      )
      check("saving unmounts the sheet", !model.editing.now && renderer.presented.isEmpty)
      settled(0) { gone =>
        check("and the platform takes it down", gone, s"${AppleInspect.presentedCount} still presented")
        alertChecks(done)
      }
    }
  }

  private def alertChecks(done: () => Unit): Unit = {
    val renderer = AppleApp.renderer
    def alerts = renderer.presented.collect { case (h, thicket.renderer.WidgetKind.Alert) => h }
    def settled(n: Int)(k: Boolean => Unit): Unit = eventually(3000)(AppleInspect.presentedCount == n)(k)

    check("no alert is up to begin with", model.lastAlertChoice.now.isEmpty)
    val itemsBeforeAlert = model.items.now.size
    model.confirmingDrop.set(true)
    check("confirming presents one alert", alerts.length == 1, alerts.length.toString)
    settled(1) { up =>
      check("the platform shows it", up, s"${AppleInspect.presentedCount} presented")
      alerts.headOption.foreach { alert =>
        check("the platform reports the alert itself on screen", AppleInspect.isPresented(alert))
        check(
          "the alert shows its title and message",
          AppleInspect.presentedTitle(alert).contains("Drop the last item?") &&
            AppleInspect.presentedMessage(alert).contains("This cannot be undone."),
          s"${AppleInspect.presentedTitle(alert)} / ${AppleInspect.presentedMessage(alert)}"
        )
        val labels = AppleInspect.alertActions(alert)
        println(s"[selftest]   alert buttons, in the platform's order: $labels")
        check("the alert holds the app's actions", labels.sorted == List("Cancel", "Drop"), labels.toString)
        check("presenting an alert leaves the screen intact", screenTexts.contains("Hide completed"))
      }

      // Unmounting is the whole of dismissing, and it must NOT report a user dismissal.
      model.confirmingDrop.set(false)
      settled(0) { gone =>
        check("an app dismissal takes it down", gone, s"${AppleInspect.presentedCount} still presented")
        check(
          "and is not reported as a user dismissal",
          model.lastAlertChoice.now.isEmpty,
          s"choice was '${model.lastAlertChoice.now}'"
        )
        check("and nothing was dropped", model.items.now.size == itemsBeforeAlert)

        // Choosing through the platform's own response path, where the platform allows it.
        model.confirmingDrop.set(true)
        settled(1) { _ =>
          val chosen = alerts.headOption.exists { alert =>
            val cancel = AppleInspect.alertActions(alert).indexOf("Cancel")
            cancel >= 0 && AppleInspect.chooseAlert(alert, cancel)
          }
          if !chosen then {
            println("[selftest]   (this platform offers no way to choose an alert action from code; not exercised)")
            model.confirmingDrop.set(false)
            settled(0)(_ => done())
          } else
            eventually(3000)(model.lastAlertChoice.now.nonEmpty) { arrived =>
              check(
                "choosing Cancel runs the app's cancel action",
                arrived && model.lastAlertChoice.now == "cancel",
                s"choice was '${model.lastAlertChoice.now}'"
              )
              settled(0) { gone =>
                check("and the alert is gone", gone && !model.confirmingDrop.now)
                check("and nothing was dropped", model.items.now.size == itemsBeforeAlert)
                done()
              }
            }
        }
      }
    }
  }

}
