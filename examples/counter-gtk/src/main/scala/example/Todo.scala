package example

import thicket.core.{ColorRole, Rgb, Theme}
import thicket.renderer.gtk.{GtkApp, GtkInspect}

/** The GTK host for [[TodoApp]] — which knows nothing about GTK.
  *
  * With `THICKET_SELFTEST=1` it drives the app and reads the result back out of GTK,
  * including navigation: push, pop, and the window title following the top screen.
  */
object Todo {

  private val model = TodoApp.Model()
  private var app: thicket.core.NavHost[TodoApp.Route] = null

  def main(args: Array[String]): Unit = {
    // Same single-role branding as the Android host; everything else stays GTK's.
    Theme.install(Theme.platform.withColor(ColorRole.Accent, Rgb(0x2E, 0x6F, 0x40)))
    val _ = GtkApp.run("dev.thicket.todo", 460, 440) {
      if sys.env.contains("THICKET_SELFTEST") then GtkApp.postToUi(() => selfTest())
      app = TodoApp(model)
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
