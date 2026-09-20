package example.android

import android.util.Log
import android.view.{View, ViewGroup}
import android.widget.TextView
import example.TodoUi

/** Drives the app and reads the view tree back out of Android, so the demo proves the
  * renderer really reorders rather than merely not crashing — the same check the GTK
  * host performs, against a structurally different toolkit.
  */
object SelfTest:

  private val Tag = "scalaui"
  private var failures = 0

  private def labels(v: View): List[String] = v match
    case g: ViewGroup =>
      (0 until g.getChildCount).toList.flatMap: i =>
        g.getChildAt(i) match
          case t: TextView => Some(t.getText.toString)
          case _           => None
    case _ => Nil

  private def allLabels(v: View): List[String] = v match
    case g: ViewGroup =>
      (0 until g.getChildCount).toList.flatMap(i => allLabels(g.getChildAt(i)))
    case t: TextView => List(t.getText.toString)
    case _           => Nil

  def run(model: TodoUi.Model, root: View): Unit =
    def listView: View = root.asInstanceOf[ViewGroup].getChildAt(1)
    def expected: List[String] = model.items.now.map(TodoUi.bullet).toList

    def check(name: String, want: List[String]): Unit =
      val got = labels(listView)
      val ok  = got == want
      if !ok then failures += 1
      Log.i(Tag, s"[selftest] ${if ok then "ok  " else "FAIL"} $name")
      if !ok then Log.i(Tag, s"[selftest]      expected=$want actual=$got")

    Log.i(Tag, "[selftest] driving the app and reading the tree back out of Android")
    check("initial", expected)
    model.add(); check("after add", expected)
    model.rotate(); check("after rotate (views moved, not rebuilt)", expected)
    model.toggleFirst(); check("after toggling the first item", expected)
    model.dropLast(); check("after dropping the last item", expected)

    while model.items.now.nonEmpty do model.dropLast()
    check("emptied", Nil)
    val shown = allLabels(root).contains("Nothing left to do.")
    if !shown then failures += 1
    Log.i(Tag, s"[selftest] ${if shown then "ok  " else "FAIL"} Show appeared when emptied")

    model.add(); check("re-populated after being empty", expected)
    val hidden = !allLabels(root).contains("Nothing left to do.")
    if !hidden then failures += 1
    Log.i(Tag, s"[selftest] ${if hidden then "ok  " else "FAIL"} Show disappeared again")

    Log.i(
      Tag,
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
