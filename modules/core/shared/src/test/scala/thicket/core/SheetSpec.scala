package thicket.core

import thicket.core.dsl.*
import thicket.renderer.WidgetKind
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `Sheet` is the second presented kind and the first that is also a container.
  *
  * That combination is what these tests are for: children have to mount into it by the ordinary path — `insertAfter` on
  * the sheet's own handle — while the sheet itself is presented rather than attached. If `presented` had been
  * special-cased for `Alert`, this is where it would come apart.
  */
object SheetSpec extends ZIOSpecDefault {

  def spec =
    suite("Sheet")(
      test("it is presented, and its children are its own") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val editing = Var(false)

        val m = Reconciler.mount(
          r,
          Column()(
            Label("behind"),
            Show(editing)(Sheet("Edit")(Label("inside"), Label("also inside"))())
          )
        )

        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("behind"))
        chk.eq(r.presentedNow.toSeq, Seq.empty)

        editing.set(true)
        // The sheet is presented, not attached: the column behind it is untouched.
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("behind"))
        chk.eq(r.presentedNow.map(r.kind).toSeq, Seq(WidgetKind.Sheet))
        // But its own children went in the ordinary way.
        chk.eq(r.childrenOf(r.presentedNow.head).map(r.text), Seq("inside", "also inside"))

        editing.set(false)
        chk.eq(r.presentedNow.toSeq, Seq.empty)
        o.dispose()
        chk.result
      },
      test("the title reaches the renderer") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val m = Reconciler.mount(r, Sheet("Quick note")(Label("x"))())
        // Asserted here rather than in a self-test because a presented widget is in its own
        // window and no in-process view walk can see into it. Both renderers routed
        // `Prop.Text` to their title only when the kind was `Alert`, so a Sheet's title went
        // nowhere — invisible to every automated check until someone looked at a screenshot.
        chk.eq(r.nodes(m.handle).props("text"), "Quick note")
        o.dispose()
        chk.result
      },
      test("content stays live while the sheet is up") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val draft = Var("one")
        val m = Reconciler.mount(r, Sheet("Edit")(Label(draft))())

        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("one"))
        // A presented subtree is a subtree: its signals drive it exactly as they would
        // anywhere else. Presenting changes where it appears, not how it works.
        draft.set("two")
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("two"))
        o.dispose()
        chk.result
      },
      test("a sheet anchors nothing that follows it") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        // Same trap as Alert: the placeholder was never attached, so it must not act as an
        // anchor. Checked separately because Sheet reaches the same code by a different
        // route — it has children, so it is a container as well as a presentation.
        val m = Reconciler.mount(
          r,
          Column()(Label("before"), Sheet()(Label("in"))(), Label("after"))
        )
        chk.eq(r.childrenOf(m.handle).map(r.text), Seq("before", "after"))
        o.dispose()
        chk.result
      },
      test("a platform dismissal is reported, and is not a choice") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        var dismissed = 0
        val m = Reconciler.mount(r, Sheet("Edit")(Label("x"))(dismissed += 1))

        r.nodes(m.handle).onDismiss.foreach(_())
        chk.eq(dismissed, 1)
        o.dispose()
        chk.result
      },
      test("both presented kinds report themselves as presented") {
        val chk = Checks()
        chk.yes(WidgetKind.Sheet.presented)
        chk.yes(WidgetKind.Alert.presented)
        chk.no(WidgetKind.Column.presented)
        chk.result
      }
    ) @@ TestAspect.sequential

}
