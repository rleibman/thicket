package example

import scala.scalanative.unsafe.Ptr
import scalaui.renderer.gtk.{GtkApp, GtkInspect}
import sn.gnome.gtk4.internal.GtkWidget

/** The GTK host for [[TodoUi]] — which knows nothing about GTK.
  *
  * With `SCALAUI_SELFTEST=1` it drives the app and reads the resulting order back out of
  * GTK. The unit tests prove the diffing algorithm against an in-memory renderer; only
  * this can catch a *wrong GTK call*.
  */
object Todo:

  private val model = TodoUi.Model()

  def main(args: Array[String]): Unit =
    val _ = GtkApp.run("dev.scalaui.todo", "scala-ui todo", 460, 420):
      if sys.env.contains("SCALAUI_SELFTEST") then GtkApp.postToUi(() => selfTest())
      TodoUi(model)

  /** The list container is the second child of the root column. */
  private def listWidget: Ptr[GtkWidget] =
    GtkInspect.children(GtkApp.rootHandle)(1)

  private var failures = 0

  private def check(label: String, expected: List[String]): Unit =
    val actual = GtkInspect.labelTexts(listWidget)
    val ok     = actual == expected
    if !ok then failures += 1
    println(s"[selftest] ${if ok then "ok  " else "FAIL"} $label")
    if !ok then
      println(s"             expected: $expected")
      println(s"             actual:   $actual")

  private def expected: List[String] = model.items.now.map(TodoUi.bullet).toList

  private def selfTest(): Unit =
    println("[selftest] driving the app and reading the order back out of GTK")
    check("initial", expected)
    model.add(); check("after add", expected)
    model.rotate(); check("after rotate (widgets moved, not rebuilt)", expected)
    model.toggleFirst(); check("after toggling the first item", expected)
    model.dropLast(); check("after dropping the last item", expected)

    while model.items.now.nonEmpty do model.dropLast()
    check("emptied", Nil)
    val shown = GtkInspect.labelTexts(GtkApp.rootHandle).contains("Nothing left to do.")
    if !shown then failures += 1
    println(s"[selftest] ${if shown then "ok  " else "FAIL"} Show appeared when the list emptied")

    model.add(); check("re-populated after being empty", expected)
    val hidden = !GtkInspect.labelTexts(GtkApp.rootHandle).contains("Nothing left to do.")
    if !hidden then failures += 1
    println(s"[selftest] ${if hidden then "ok  " else "FAIL"} Show disappeared again")

    println(
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
