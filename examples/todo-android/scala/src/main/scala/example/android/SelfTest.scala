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

package example.android

import android.util.Log
import android.view.{Gravity, View, ViewGroup}
import android.text.InputType
import android.widget.{EditText, FrameLayout, ProgressBar, RadioButton, RadioGroup, ScrollView, SeekBar, Switch, TextView}
import example.TodoApp
import thicket.core.NavHost
import thicket.renderer.CalendarDate
import thicket.renderer.android.AndroidRenderer
import android.content.DialogInterface

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

  /** `root` is a thunk, deliberately.
    *
    * Each entry on the navigation stack is now its own view, so "the screen" is whatever is
    * on top *now*. A `View` captured once goes stale as soon as the stack changes, and
    * `navigator.reset` replaces the items page outright — after which every remaining check
    * was walking a disposed view and finding nothing. Twelve of them failed that way, all
    * reporting an empty tree, which is a good signature for this mistake.
    */
  def run(
    model: TodoApp.Model,
    app:   NavHost[TodoApp.Route],
    root:  () => View,
    stack: () => View
  ): Unit = {
    Log.i(Tag, "[selftest] driving navigation and reading back out of Android")

    check("starts on the items screen", app.title.now == "Todo")
    val onItems = allTexts(root())
    check("item rows are rendered", onItems.contains("Navigation"), onItems.toString)

    app.push(TodoApp.Route.Detail(3))
    check("pushed: title follows the top screen", app.title.now == "Item")
    val onDetail = allTexts(root())
    check("detail content is mounted", onDetail.contains("Navigation"), onDetail.toString)
    // Used to assert the items screen was *gone*: a push unmounted it and a pop rebuilt it
    // from scratch. Keeping the stack alive is the point of the native container, so the
    // honest assertion is the opposite one — and it is the more useful of the two, because
    // it is what preserves a screen's scroll position and its in-flight requests.
    // Asked of the *container*, not of `root()`. `root()` is the page on top, which is the
    // detail screen and rightly has no "Add" on it; the question here is whether the page
    // underneath is still in the view tree, and only the container can answer that.
    val wholeStack = allTexts(stack())
    check("the screen below a push stays mounted", wholeStack.contains("Add"), wholeStack.toString)
    check("back is available", app.canGoBack.now)

    model.toggle(3)
    check("toggling from the detail screen updates it", allTexts(root()).contains("Done"),
      allTexts(root()).toString)

    check("back() pops", app.back())
    check("title restored", app.title.now == "Todo")
    check("items screen is back", allTexts(root()).contains("Navigation"))
    check("back is no longer available at the root", !app.canGoBack.now)
    check("back() at the root defers to the platform", !app.back())

    // The back stack is a List of a route ADT, so round-tripping it is plain data.
    app.push(TodoApp.Route.Detail(2))
    app.push(TodoApp.Route.About)
    val saved = app.navigator.routes.now.map(TodoApp.showRoute)
    val parsed = saved.flatMap(TodoApp.parseRoute)
    check("the back stack round-trips through strings", parsed == app.navigator.routes.now,
      s"saved=$saved parsed=$parsed")

    // SafeArea, which replaced the Activity's hand-rolled inset handling (docs/05 F-02).
    //
    // Worth an actual check rather than trust: the old version was never verified either, so
    // without this the change would swap one unchecked implementation for another. The
    // emulator has a status bar, so the top inset is non-zero and the view the screen applies
    // `.safeArea()` to must have picked it up.
    // The ScrollView specifically — the view `.safeArea()` is applied to — and against the
    // *actual* status-bar inset, not merely "greater than zero".
    //
    // The first version of this check asked whether ANY view in the tree had top padding,
    // and passed for the wrong reason: the screen's `Column(padding = 16)` sets top padding
    // of its own. Asking for `.safeArea(Edge.Bottom)` instead of every edge still passed it,
    // which is how the mistake surfaced.
    // Asked of the view itself: getRootWindowInsets is what the platform actually handed the
    // window, so this compares against the real number rather than a guess at it.
    // Deferred to after the first layout pass, which is the only time this can be asked.
    //
    // The rest of the suite runs inside `onCreate`, where texts and properties are already
    // set — but window insets are not: the platform dispatches them during layout, so
    // `getRootWindowInsets` reads zero here and the padding has not been applied yet. Asking
    // early is how the first version of this check came to pass for the wrong reason (the
    // screen's own `Column(padding = 16)`) and then to fail for the right one.
    //
    // `post` runs it after layout. It logs after the summary line, which is untidy, but
    // `build.sh` greps the whole log for FAIL so a failure is still caught.
    stack().post { () =>
      // The ScrollView `.safeArea()` is applied to, and only that view.
      //
      // Deliberately NOT compared against `getRootWindowInsets`: that reports 128 here while
      // the insets actually dispatched to this view are 275, and asserting against the wrong
      // one of those failed a correct implementation. The number a view is handed and the
      // number the root reports are not the same question.
      //
      // So the claim is the one that holds whatever the device's insets are: the view this is
      // applied to gets top padding, and it is the *only* thing giving it that padding — a
      // `Scroll` declares none of its own. An earlier version asked whether ANY view had top
      // padding and passed because the screen's `Column(padding = 16)` has some.
      val scrolls = findAll(stack())(_.isInstanceOf[ScrollView])
      check("SafeArea pads the view it is applied to",
        scrolls.nonEmpty && scrolls.forall(_.getPaddingTop > 0),
        s"${scrolls.length} scroll views, top paddings ${scrolls.map(_.getPaddingTop)}")
    }

    // Restoring the stack must *mount* it, not merely set it.
    //
    // This is the one path the rest of the suite does not reach: `onCreate` restores from
    // the Bundle before the page-syncing effect is created, so a process-death restore
    // depends on ordering that nothing was checking. Reasoning about the line numbers is
    // not the same as watching it happen, and it is exactly the kind of runtime behaviour a
    // reviewer cannot confirm by reading the diff.
    app.navigator.restore(List(TodoApp.Route.Detail(1), TodoApp.Route.Items))
    val restored = allTexts(stack())
    check("a restored stack mounts every page, not just the top",
      restored.contains("Add") && restored.contains("Back"),
      restored.toString)
    check("and the restored top screen is the one on top", app.title.now == "Item")

    val _ = app.navigator.reset(TodoApp.Route.Items)
    check("reset collapses the stack to one page",
      !app.canGoBack.now && !allTexts(stack()).contains("Back"),
      allTexts(stack()).toString)

    // --- the form: text field and checkbox bound both ways ---
    check("the draft starts empty", model.draft.now.isEmpty)
    check("an empty draft is not valid", !model.draftValid.now)

    model.draft.set("Write the catalogue")
    check("writing the signal reaches the widget",
      allTexts(root()).contains("Write the catalogue"), allTexts(root()).toString)
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
    val switches = findAll(root()) { case _: Switch => true; case _ => false }
    check("the settings row has a Switch", switches.length == 1, switches.length.toString)
    switches.headOption.collect { case s: Switch => s }.foreach { s =>
      check("the switch starts off", !s.isChecked)
      model.hideDone.set(true)
      check("writing the signal flips the switch", s.isChecked)
      check("hiding completed items shrinks the list",
        !allTexts(root()).contains("Structural reconciliation"),
        allTexts(root()).toString)
      model.hideDone.set(false)
      check("showing them again restores it", allTexts(root()).contains("Structural reconciliation"))
    }

    // A horizontal ProgressBar and an indeterminate spinner are the same Android class, so
    // they are told apart by isIndeterminate rather than by type.
    //
    // And SeekBar extends ProgressBar, so a slider is a determinate progress bar as far as
    // `isInstanceOf` is concerned. It has to be excluded explicitly — the first version of
    // this check reported two bars once the slider landed.
    def bars = findAll(root()) {
      case _: SeekBar     => false
      case p: ProgressBar => !p.isIndeterminate
      case _              => false
    }
    def spinners = findAll(root()) { case p: ProgressBar => p.isIndeterminate; case _ => false }

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

    // --- DatePicker: a field that opens the platform's DatePickerDialog ---
    // Driven through the user's own path: click the field, which opens the real dialog; set
    // the dialog's DatePicker; press the dialog's own OK button, whose listener is the
    // renderer's. The expected text is formatted here, independently of the renderer.
    def medium(d: CalendarDate): String = {
      val f = _root_.android.text.format.DateFormat.getMediumDateFormat(root().getContext)
      f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
      f.format(java.util.Date(d.toEpochDay.toLong * 86400000L))
    }
    val start = model.reviewBy.now
    val dateFields = findAll(root()) {
      case b: _root_.android.widget.Button => b.getText.toString == medium(start)
      case _                                => false
    }
    check("the date field shows the model's date in the user's format", dateFields.length == 1,
      s"looking for ${medium(start)}")
    dateFields.headOption.foreach { field =>
      field.performClick()
      val dialog = AndroidRenderer.dateDialog
      check("clicking it opens a DatePickerDialog", dialog.exists(_.isShowing))
      dialog.foreach { d =>
        val p = d.getDatePicker
        check("the dialog opens on the model's date, leap day and all",
          (p.getYear, p.getMonth + 1, p.getDayOfMonth) == (start.year, start.month, start.day),
          s"${p.getYear}-${p.getMonth + 1}-${p.getDayOfMonth}")
        d.updateDate(2029, 0, 31)
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        // AlertDialog delivers a button's listener and its own dismissal as Handler messages,
        // queued by that click, so the outcome exists only after they run. Posted behind
        // them; like the SafeArea check it logs after the summary, and build.sh's grep for
        // FAIL still catches it. The first version asked straight away and saw nothing.
        field.post { () =>
          check("choosing a date reaches the app", model.reviewBy.now == CalendarDate(2029, 1, 31),
            model.reviewBy.now.toString)
          check("and the field shows it",
            field.asInstanceOf[TextView].getText.toString == medium(CalendarDate(2029, 1, 31)),
            field.asInstanceOf[TextView].getText.toString)
          check("and the dialog has closed", !d.isShowing)
          model.reviewBy.set(start)
        }
      }
    }

    // --- SegmentedControl: a horizontal RadioGroup, one RadioButton per option ---
    val groups = findAll(root()) { case _: RadioGroup => true; case _ => false }
      .collect { case g: RadioGroup => g }
    check("the order control is one RadioGroup", groups.length == 1, groups.length.toString)
    groups.headOption.foreach { g =>
      def buttons = (0 until g.getChildCount).map(g.getChildAt).collect { case b: RadioButton => b }
      def checked = buttons.map(_.isChecked).toList
      def rowsFirst: Option[String] = {
        val texts = allTexts(root())
        val titles = model.items.now.map(_.title)
        texts.find(t => titles.exists(t.endsWith))
      }
      check("a RadioButton per option, in order",
        buttons.map(_.getText.toString).toList == List("Oldest first", "Newest first"),
        buttons.map(_.getText.toString).toString)
      check("the model's choice is the checked one", checked == List(true, false), checked.toString)
      val oldestFirst = rowsFirst
      // A real click: RadioButton.performClick checks it, the group's listener runs, and the
      // renderer reports the index to the app.
      buttons(1).performClick()
      check("clicking a segment reaches the app", model.order.now == 1, model.order.now.toString)
      check("and only that one is checked", checked == List(false, true), checked.toString)
      check("and the list reverses", rowsFirst != oldestFirst && rowsFirst.nonEmpty,
        s"$oldestFirst then $rowsFirst")
      model.order.set(0)
      check("writing the signal checks the other segment", checked == List(true, false), checked.toString)
    }

    // --- ZStack: the spinner is drawn over the logo, in a FrameLayout ---
    // Mounted after the picture, so this is the insert that must land on top. Child index is
    // paint order in a FrameLayout, so "last" is the claim that it is visible.
    spinners.headOption.foreach { sp =>
      sp.getParent match {
        case stack: FrameLayout =>
          check("the spinner is the stack's last child, so drawn on top",
            stack.indexOfChild(sp) == stack.getChildCount - 1 && stack.getChildCount == 2,
            s"index ${stack.indexOfChild(sp)} of ${stack.getChildCount}")
          val g = sp.getLayoutParams.asInstanceOf[FrameLayout.LayoutParams].gravity
          check("and centred over the picture", g == (Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL),
            s"gravity 0x${g.toHexString}")
          // Measured, not laid out: FrameLayout measures its children inside its own measure,
          // so this needs no layout pass and asks exactly "is the stack its largest child".
          val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
          stack.measure(unspecified, unspecified)
          val kids = (0 until stack.getChildCount).map(stack.getChildAt)
          val (w, h) = (stack.getMeasuredWidth, stack.getMeasuredHeight)
          val (cw, ch) = (kids.map(_.getMeasuredWidth).max, kids.map(_.getMeasuredHeight).max)
          check("the stack is as large as its largest child, and no larger",
            w == cw + stack.getPaddingLeft + stack.getPaddingRight &&
              h == ch + stack.getPaddingTop + stack.getPaddingBottom && cw > sp.getMeasuredWidth,
            s"stack ${w}x$h, largest child ${cw}x$ch, spinner ${sp.getMeasuredWidth}x${sp.getMeasuredHeight}")
        case other =>
          check("the spinner sits in a FrameLayout", false, String.valueOf(other))
      }
    }
    model.busy.set(false)
    check("and is gone again when not", spinners.isEmpty, spinners.length.toString)

    val seeks = findAll(root()) { case _: SeekBar => true; case _ => false }
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
    val fields = findAll(root()) { case _: EditText => true; case _ => false }
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
    check("the items screen declares actions", app.actions.now.map(_.label) == Seq("Add", "Note", "About"),
      app.actions.now.map(_.label).toString)

    val sizeBeforeAction = model.items.now.size
    app.actions.now.find(_.label == "Add").foreach(_.onTap())
    check("invoking a toolbar action runs it", model.items.now.size == sizeBeforeAction + 1)

    app.push(TodoApp.Route.Detail(1))
    check("a screen with no actions declares none", app.actions.now.isEmpty,
      app.actions.now.map(_.label).toString)
    val _ = app.back()
    check("and they come back on return", app.actions.now.map(_.label) == Seq("Add", "Note", "About"))

    // --- context menu: a property of a row, not a widget in the tree ---
    // Android's PopupMenu is built on demand inside the long-press handler, so there is
    // nothing in the view tree to find before the gesture. What is observable here is that
    // the rows are long-clickable at all - the renderer set a listener - and the real
    // gesture is driven from adb in the build script's screenshot pass.
    val longClickable = findAll(root())(v => v.isLongClickable)
    check("list rows are long-clickable", longClickable.nonEmpty,
      "a row with a ContextMenu must accept the platform's gesture for one")

    // --- Sheet: a presented *container*, with a live subtree inside it ---
    check("no sheet before it is asked for", !model.editing.now)
    model.editing.set(true)
    check("the screen behind is untouched", allTexts(root()).contains("Hide completed"),
      allTexts(root()).toString)

    val sizeBeforeSheet = model.items.now.size
    model.draft.set("From the sheet")
    model.addDraft()
    // The sheet's title is deliberately NOT checked here. A presented widget lives in its
    // own window, so neither this View tree nor GTK's can see into it - that is what
    // `presented` means. It is asserted at the renderer boundary instead, in SheetSpec,
    // which is where the observation is actually available.

    check("the sheet's bound field drives the same model",
      model.items.now.size == sizeBeforeSheet + 1 &&
        model.items.now.last.title == "From the sheet",
      model.items.now.map(_.title).toString)

    model.editing.set(false)
    check("unmounting takes the sheet down and leaves the screen",
      allTexts(root()).contains("Hide completed"))

    // --- Alert: presented by a signal, dismissed by unmounting ---
    check("no alert is up to begin with", model.lastAlertChoice.now.isEmpty)
    val itemsBeforeAlert = model.items.now.size

    model.confirmingDrop.set(true)
    check("presenting an alert leaves the screen intact",
      allTexts(root()).contains("Hide completed"), allTexts(root()).toString)

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
