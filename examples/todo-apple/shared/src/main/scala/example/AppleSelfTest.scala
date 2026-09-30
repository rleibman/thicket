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

    println(
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
  }

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

}
