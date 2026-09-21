package example.android

import android.util.Log
import android.view.{View, ViewGroup}
import android.widget.{EditText, TextView}
import example.TodoApp
import scalaui.core.NavHost

/** Drives navigation and reads the view tree back out of Android — the same checks the GTK
  * host runs, against a structurally different toolkit.
  */
object SelfTest {

  private val Tag      = "scalaui"
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

  private def check(name: String, cond: Boolean, detail: => String = ""): Unit = {
    if !cond then failures += 1
    Log.i(Tag, s"[selftest] ${if cond then "ok  " else "FAIL"} $name")
    if !cond && detail.nonEmpty then Log.i(Tag, s"[selftest]      $detail")
  }

  def run(model: TodoApp.Model, app: NavHost[TodoApp.Route], root: View): Unit = {
    Log.i(Tag, "[selftest] driving navigation and reading back out of Android")

    check("starts on the items screen", app.title.now == "Todo")
    val onItems = allTexts(root)
    check("item rows are rendered", onItems.exists(_.contains("Navigation")), onItems.toString)

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
    check("items screen is back", allTexts(root).exists(_.contains("Navigation")))
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

    Log.i(
      Tag,
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
  }
}
