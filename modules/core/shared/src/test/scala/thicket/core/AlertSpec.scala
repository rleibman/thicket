package thicket.core

import thicket.core.dsl.*
import thicket.renderer.{AlertAction, WidgetKind}
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `Alert` is the first widget that is *presented* rather than inserted, so what these
  * tests pin down is mostly the lifecycle: mounting presents it, unmounting dismisses it,
  * and there is no third way to do either.
  */
object AlertSpec extends ZIOSpecDefault {

  def spec = suite("Alert")(
    test("Alert is the only presented kind so far") {
      val chk = Checks()
      chk.yes(WidgetKind.Alert.presented)
      chk.no(WidgetKind.Column.presented)
      chk.no(WidgetKind.Scroll.presented, "a Scroll is a container, not a presentation")
      chk.result
    },
    test("mounting presents it and unmounting dismisses it") {
      val o = Owner(); given Owner = o
      val chk       = Checks()
      val r         = TestRenderer()
      val confirming = Var(false)

      val m = Reconciler.mount(
        r,
        Column()(Show(confirming) {
          Alert("Delete this item?", "This cannot be undone.")(
            AlertAction("Delete", destructive = true)(()),
            AlertAction("Cancel", cancel = true)(())
          )()
        })
      )

      chk.eq(r.presentedNow.toSeq, Seq.empty, "nothing is presented until the signal flips")
      confirming.set(true)
      chk.eq(r.presentedNow.map(r.kind).toSeq, Seq(WidgetKind.Alert))
      // An alert is presented *over* the app, never attached to a parent — that is the
      // whole point of WidgetKind.presented, and GTK enforces it in its types.
      chk.eq(r.childrenOf(m.handle), Seq.empty, "a presented widget is not a child")
      confirming.set(false)
      chk.eq(r.presentedNow.toSeq, Seq.empty, "unmounting is what dismisses it")

      o.dispose()
      chk.result
    },
    test("title, message and actions reach the renderer in order") {
      val o = Owner(); given Owner = o
      val chk = Checks()
      val r   = TestRenderer()
      val m = Reconciler.mount(
        r,
        Alert("Discard draft?", "Your changes will be lost.")(
          AlertAction("Discard", destructive = true)(()),
          AlertAction("Keep editing", cancel = true)(())
        )()
      )
      val n = r.nodes(m.handle)

      chk.eq(n.props("text"), "Discard draft?", "Text is the title")
      chk.eq(n.props("message"), "Your changes will be lost.")
      // Order matters: the first action is conventionally the default, and every toolkit
      // lays them out in the order it is given.
      chk.eq(n.props("actions"), "Discard,Keep editing")
      chk.yes(n.actions.head.destructive, "the destructive role survives the trip")
      chk.yes(n.actions(1).cancel, "so does the cancel role")
      chk.no(n.actions.head.cancel)

      o.dispose()
      chk.result
    },
    test("choosing an action runs that action, and only that one") {
      val o = Owner(); given Owner = o
      val chk     = Checks()
      val r       = TestRenderer()
      var deleted = 0
      var kept    = 0
      val m = Reconciler.mount(
        r,
        Alert("Delete?")(
          AlertAction("Delete", destructive = true)(deleted += 1),
          AlertAction("Cancel", cancel = true)(kept += 1)
        )()
      )

      r.nodes(m.handle).actions.head.onSelect()
      chk.eq(deleted, 1)
      chk.eq(kept, 0, "the other action must not fire")

      o.dispose()
      chk.result
    },
    test("a platform dismissal is not the same as choosing cancel") {
      val o = Owner(); given Owner = o
      val chk       = Checks()
      val r         = TestRenderer()
      var cancelled = 0
      var dismissed = 0

      val m = Reconciler.mount(
        r,
        Alert("Delete?")(AlertAction("Cancel", cancel = true)(cancelled += 1))(dismissed += 1)
      )

      // Escape, a tap outside, a back gesture. The user declined to *choose*, which an app
      // may well want to treat differently from choosing Cancel — so the framework keeps
      // them apart rather than deciding on the app's behalf.
      r.nodes(m.handle).onDismiss.foreach(_())
      chk.eq(dismissed, 1)
      chk.eq(cancelled, 0)

      o.dispose()
      chk.result
    },
    test("an alert with a single action is still valid") {
      val o = Owner(); given Owner = o
      val chk = Checks()
      val r   = TestRenderer()
      // The "OK" alert: one choice, nothing destructive. Every toolkit allows it and it is
      // the most common alert there is.
      val m = Reconciler.mount(r, Alert("Saved", "Your changes are safe.")(AlertAction("OK")(()))())
      chk.eq(r.nodes(m.handle).actions.length, 1)
      chk.eq(r.nodes(m.handle).props("actions"), "OK")
      o.dispose()
      chk.result
    }
  ) @@ TestAspect.sequential
}
