package example

import scala.scalanative.unsafe.Ptr
import scalaui.core.dsl.*
import scalaui.renderer.gtk.{GtkApp, GtkInspect}
import scalaui.signals.Var
import sn.gnome.gtk4.internal.GtkWidget

/** Exercises structural reconciliation on a real toolkit: a keyed list that grows,
  * shrinks and reorders, plus a conditional region.
  *
  * With `SCALAUI_SELFTEST=1` the demo drives itself and reads the resulting order back
  * out of GTK. That matters: the unit tests prove the diffing algorithm against an
  * in-memory renderer, which cannot catch a *wrong GTK call*. This can.
  */
object Todo:

  final case class Item(id: Int, title: String, done: Boolean)

  private val items = Var(
    Seq(Item(1, "Structural reconciliation", true), Item(2, "Android renderer", false))
  )
  private var nextId = 3

  private def add(): Unit =
    items.update(_ :+ Item(nextId, s"New item $nextId", false))
    nextId += 1

  private def rotate(): Unit =
    items.update:
      case head +: rest => rest :+ head
      case empty        => empty

  private def toggleFirst(): Unit =
    items.update:
      case head +: rest => head.copy(done = !head.done) +: rest
      case empty        => empty

  private def dropLast(): Unit = items.update(_.dropRight(1))

  private def bullet(i: Item): String =
    (if i.done then "✓" else "•") + "  " + i.title

  def main(args: Array[String]): Unit =
    val _ = GtkApp.run("dev.scalaui.todo", "scala-ui todo", 460, 420):
      val ui = Column(spacing = 12, padding = 20)(
        Label("Todo"),
        Column(spacing = 4)(
          ForEach(items, key = (i: Item) => i.id)(item => Label(item.map(bullet)))
        ),
        Show(items.map(_.isEmpty))(Label("Nothing left to do.")),
        Row(spacing = 8)(
          Button("Add")(add()),
          Button("Toggle first")(toggleFirst()),
          Button("Rotate")(rotate()),
          Button("Drop last")(dropLast())
        ),
        Label(items.map(xs => s"${xs.count(_.done)} of ${xs.size} done"))
      )
      if sys.env.contains("SCALAUI_SELFTEST") then GtkApp.postToUi(() => selfTest())
      ui

  // -------------------------------------------------------------------------

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

  private def selfTest(): Unit =
    println("[selftest] driving the app and reading the order back out of GTK")

    check("initial", items.now.map(bullet).toList)

    add()
    check("after add", items.now.map(bullet).toList)

    rotate()
    check("after rotate (widgets moved, not rebuilt)", items.now.map(bullet).toList)

    toggleFirst()
    check("after toggling the first item", items.now.map(bullet).toList)

    dropLast()
    check("after dropping the last item", items.now.map(bullet).toList)

    // Empty the list: the ForEach region goes empty and the Show region appears.
    while items.now.nonEmpty do dropLast()
    check("emptied", Nil)
    val rootTexts = GtkInspect.labelTexts(GtkApp.rootHandle)
    val shown     = rootTexts.contains("Nothing left to do.")
    if !shown then failures += 1
    println(s"[selftest] ${if shown then "ok  " else "FAIL"} Show appeared when the list emptied")

    add()
    check("re-populated after being empty", items.now.map(bullet).toList)
    val hidden = !GtkInspect.labelTexts(GtkApp.rootHandle).contains("Nothing left to do.")
    if !hidden then failures += 1
    println(s"[selftest] ${if hidden then "ok  " else "FAIL"} Show disappeared again")

    println(
      if failures == 0 then "[selftest] ALL CHECKS PASSED"
      else s"[selftest] $failures CHECK(S) FAILED"
    )
