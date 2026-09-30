package example.android

import android.util.Log
import android.view.{View, ViewGroup}
import android.text.InputType
import android.widget.{EditText, ProgressBar, SeekBar, Switch, TextView}
import example.TodoApp
import thicket.core.NavHost

/** Drives navigation and reads the view tree back out of Android — the same checks the GTK
  * host runs, against a structurally different toolkit.
  */
object SelfTest {

  private val Tag      = "thicket"
  private var failures = 0

  /** Every piece of text in the subtree, in tree order. */
  /** EditText is a TextView, so it must be matched first or its value is read as a label. */
  private def allTexts(v: View): List[String] = v match {
    case e: EditText => List(e.getText.toString)
    case g: ViewGroup =>
      (0 until g.getChildCount).toList.flatMap(i => allTexts(g.getChildAt(i)))
    case t: TextView => List(t.getText.toString)
    case _           => Nil
  }

  /** Every descendant for which `p` holds, in tree order. */
  private def findAll(v: View)(p: View => Boolean): List[View] = {
    val here = if p(v) then List(v) else Nil
    val kids = v match {
      case g: ViewGroup => (0 until g.getChildCount).toList.flatMap(i => findAll(g.getChildAt(i))(p))
      case _            => Nil
    }
    here ++ kids
  }

  private def check(name: String, cond: Boolean, detail: => String = ""): Unit = {
    if !cond then failures += 1
    Log.i(Tag, s"[selftest] ${if cond then "ok  " else "FAIL"} $name")
    if !cond && detail.nonEmpty then Log.i(Tag, s"[selftest]      $detail")
  }

  def run(model: TodoApp.Model, app: NavHost[TodoApp.Route], root: View): Unit = {
    Log.i(Tag, "[selftest] driving navigation and reading back out of Android")

    check("starts on the items screen", app.title.now == "Todo")
    val onItems = allTexts(root)
    check("item rows are rendered", onItems.contains("Navigation"), onItems.toString)

    app.push(TodoApp.Route.Detail(3))
    check("pushed: title follows the top screen", app.title.now == "Item")
    val onDetail = allTexts(root)
    check("detail content is mounted", onDetail.contains("Navigation"), onDetail.toString)
    check("items screen is gone", !onDetail.contains("Add"), onDetail.toString)
    check("back is available", app.canGoBack.now)

    model.toggle(3)
    check("toggling from the detail screen updates it", allTexts(root).contains("Done"),
      allTexts(root).toString)

    check("back() pops", app.back())
    check("title restored", app.title.now == "Todo")
    check("items screen is back", allTexts(root).contains("Navigation"))
    check("back is no longer available at the root", !app.canGoBack.now)
    check("back() at the root defers to the platform", !app.back())

    // The back stack is a List of a route ADT, so round-tripping it is plain data.
    app.push(TodoApp.Route.Detail(2))
    app.push(TodoApp.Route.About)
    val saved = app.navigator.routes.now.map(TodoApp.showRoute)
    val parsed = saved.flatMap(TodoApp.parseRoute)
    check("the back stack round-trips through strings", parsed == app.navigator.routes.now,
      s"saved=$saved parsed=$parsed")
    val _ = app.navigator.reset(TodoApp.Route.Items)

    // --- the form: text field and checkbox bound both ways ---
    check("the draft starts empty", model.draft.now.isEmpty)
    check("an empty draft is not valid", !model.draftValid.now)

    model.draft.set("Write the catalogue")
    check("writing the signal reaches the widget",
      allTexts(root).contains("Write the catalogue"), allTexts(root).toString)
    check("a non-empty draft is valid", model.draftValid.now)

    model.draftDone.set(true)
    val sizeBefore = model.items.now.size
    model.addDraft()
    check("adding appends the drafted item",
      model.items.now.size == sizeBefore + 1 &&
        model.items.now.last.title == "Write the catalogue",
      model.items.now.map(_.title).toString)
    check("the committed item kept its done flag", model.items.now.last.done)
    check("the form cleared", model.draft.now.isEmpty && !model.draftDone.now)

    // --- the widgets phase 2 added, read back out of Android ---
    val switches = findAll(root) { case _: Switch => true; case _ => false }
    check("the settings row has a Switch", switches.length == 1, switches.length.toString)
    switches.headOption.collect { case s: Switch => s }.foreach { s =>
      check("the switch starts off", !s.isChecked)
      model.hideDone.set(true)
      check("writing the signal flips the switch", s.isChecked)
      check("hiding completed items shrinks the list",
        !allTexts(root).contains("Structural reconciliation"),
        allTexts(root).toString)
      model.hideDone.set(false)
      check("showing them again restores it", allTexts(root).contains("Structural reconciliation"))
    }

    // A horizontal ProgressBar and an indeterminate spinner are the same Android class, so
    // they are told apart by isIndeterminate rather than by type.
    //
    // And SeekBar extends ProgressBar, so a slider is a determinate progress bar as far as
    // `isInstanceOf` is concerned. It has to be excluded explicitly — the first version of
    // this check reported two bars once the slider landed.
    def bars = findAll(root) {
      case _: SeekBar     => false
      case p: ProgressBar => !p.isIndeterminate
      case _              => false
    }
    def spinners = findAll(root) { case p: ProgressBar => p.isIndeterminate; case _ => false }

    check("there is a determinate ProgressBar", bars.length == 1, bars.length.toString)
    bars.headOption.collect { case p: ProgressBar => p }.foreach { bar =>
      // Driven to a genuine fraction: everything is done by now, and 1.0-against-1.0 would
      // also pass if the renderer clamped every value to full.
      model.items.now.take(1).foreach(i => model.toggle(i.id))
      val xs       = model.items.now
      val expected = xs.count(_.done).toDouble / xs.size
      val actual   = bar.getProgress.toDouble / bar.getMax
      Log.i(Tag, f"[selftest] progress bar: $actual%.3f, expected $expected%.3f")
      check("the progress bar shows the model's fraction",
        expected > 0.0 && expected < 1.0 && math.abs(actual - expected) < 0.002,
        s"$actual vs $expected")
    }

    // A Spinner has no "running" prop: Show is what starts and stops it.
    check("no spinner while the app is idle", spinners.isEmpty, spinners.length.toString)
    model.busy.set(true)
    check("a spinner appears when busy", spinners.length == 1, spinners.length.toString)
    model.busy.set(false)
    check("and is gone again when not", spinners.isEmpty, spinners.length.toString)

    val seeks = findAll(root) { case _: SeekBar => true; case _ => false }
    check("there is a SeekBar", seeks.length == 1, seeks.length.toString)
    seeks.headOption.collect { case s: SeekBar => s }.foreach { bar =>
      // A SeekBar counts integer steps, so the renderer converts. The app's units are
      // 0..11 and it must never see the conversion.
      def units: Double = bar.getProgress.toDouble / bar.getMax * 11.0
      check("the slider starts at the model's value",
        math.abs(units - 7.0) < 0.02, units.toString)
      model.volume.set(3.5)
      check("writing the signal moves the slider",
        math.abs(units - 3.5) < 0.02, units.toString)
      model.volume.set(7.0)
    }

    // A SecureField is an EditText with a password input type; two exist by now and only
    // the secure one is masked.
    val fields = findAll(root) { case _: EditText => true; case _ => false }
      .collect { case e: EditText => e }
    val masked = fields.filter { e =>
      (e.getInputType & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
    }
    check("exactly one field masks its input",
      masked.length == 1, s"${masked.length} of ${fields.length} fields")
    model.secret.set("hunter2")
    check("the secure field still holds its value",
      masked.headOption.exists(_.getText.toString == "hunter2"),
      "the value must round-trip even though it is not drawn")

    // --- toolbar actions, in the action bar rather than the element tree ---
    // Android builds the options menu on its own schedule, so the check is that the app
    // declared them and that invoking one works - the menu itself belongs to the platform
    // and is not in this View tree by design.
    check("the items screen declares actions", app.actions.now.map(_.label) == Seq("Add", "About"),
      app.actions.now.map(_.label).toString)

    val sizeBeforeAction = model.items.now.size
    app.actions.now.find(_.label == "Add").foreach(_.onTap())
    check("invoking a toolbar action runs it", model.items.now.size == sizeBeforeAction + 1)

    app.push(TodoApp.Route.Detail(1))
    check("a screen with no actions declares none", app.actions.now.isEmpty,
      app.actions.now.map(_.label).toString)
    val _ = app.back()
    check("and they come back on return", app.actions.now.map(_.label) == Seq("Add", "About"))

    // --- Alert: presented by a signal, dismissed by unmounting ---
    check("no alert is up to begin with", model.lastAlertChoice.now.isEmpty)
    val itemsBeforeAlert = model.items.now.size

    model.confirmingDrop.set(true)
    check("presenting an alert leaves the screen intact",
      allTexts(root).contains("Hide completed"), allTexts(root).toString)

    // Unmounting is the whole of dismissing, and it must NOT report a user dismissal: the
    // app asked for it to go away, which is not the user declining to choose. On Android
    // that distinction is real - setOnCancelListener fires for the back gesture and a tap
    // outside, but not for Dialog.dismiss().
    model.confirmingDrop.set(false)
    check("dismissing from the app does not report a user dismissal",
      model.lastAlertChoice.now.isEmpty,
      s"choice was '${model.lastAlertChoice.now}'")
    check("and nothing was dropped", model.items.now.size == itemsBeforeAlert)

    Log.i(
      Tag,
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
  }
}
