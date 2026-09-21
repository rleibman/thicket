package example

import scalaui.renderer.gtk.{GtkApp, GtkInspect}

/** The GTK host for [[TodoApp]] — which knows nothing about GTK.
  *
  * With `SCALAUI_SELFTEST=1` it drives the app and reads the result back out of GTK,
  * including navigation: push, pop, and the window title following the top screen.
  */
object Todo {

  private val model = TodoApp.Model()
  private var app: scalaui.core.NavHost[TodoApp.Route] = null

  def main(args: Array[String]): Unit = {
    val _ = GtkApp.run("dev.scalaui.todo", 460, 440) {
      if sys.env.contains("SCALAUI_SELFTEST") then GtkApp.postToUi(() => selfTest())
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
    check("item rows are rendered", onItems.exists(_.contains("Navigation")), onItems.toString)

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
    check("items screen is back", screenTexts.exists(_.contains("Navigation")))
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

    println(
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
  }
}
