package example

import scalaui.renderer.apple.{AppleApp, AppleInspect, AppleRenderer}
import scalaui.renderer.Constraints

/** The part of the Apple example that is the same on both platforms — which is all of it
  * except the entry point.
  *
  * [[TodoApp]] itself knows nothing about AppKit or UIKit; this knows only `AppleApp`,
  * which is the single renderer both hosts share. `TodoMac.main` and `TodoIos.start`
  * differ only in how the process gets here.
  *
  * With `SCALAUI_SELFTEST=1` it drives the app and reads the result back out of the
  * platform, including navigation: push, pop, and the window title following the top
  * screen.
  */
object AppleSelfTest {

  val model = TodoApp.Model()
  private var app: scalaui.core.NavHost[TodoApp.Route] = null

  /** The UI, plus the self-test if it was asked for. Both hosts call exactly this. */
  def build(): scalaui.core.NavHost[TodoApp.Route] = {
    if sys.env.contains("SCALAUI_SELFTEST") then AppleApp.postToUi(() => selfTest())
    app = TodoApp(model)
    app
  }

  /** Every piece of text in the mounted screen, in tree order. */
  private def screenTexts: List[String] = AppleInspect.allTexts(AppleApp.rootHandle)

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

    // Reading the tree back proves the renderer made the right calls; it does not prove
    // AppKit laid anything out. Measuring does: a view with a zero natural size has been
    // created and attached but never given geometry.
    val probe = AppleRenderer()
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

}
