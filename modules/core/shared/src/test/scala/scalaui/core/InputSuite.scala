package scalaui.core

import scalaui.core.dsl.*
import scalaui.signals.{Owner, Var}

/** Input widgets: the first case where data flows from the widget back to the app.
  *
  * The dangerous bug here is the echo loop — the renderer writes a value, the widget
  * reports a change, the app writes it again. These tests pin the guard against it.
  */
class InputSuite extends munit.FunSuite {

  test("a text field shows its bound value and reports edits") {
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val name = Var("")
    val m    = Reconciler.mount(r, Column()(TextField(name, placeholder = "Name")(name.set)))
    val field = r.childrenOf(m.handle).head

    assertEquals(r.nodes(field).props("placeholder"), "Name")
    r.typeText(field, "Rob")
    assertEquals(name.now, "Rob", "the edit reached the app")
    assertEquals(r.text(field), "Rob")
    o.dispose()
  }

  test("writing the signal updates the field") {
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val name = Var("a")
    val m    = Reconciler.mount(r, Column()(TextField(name)(name.set)))
    val field = r.childrenOf(m.handle).head

    assertEquals(r.text(field), "a")
    name.set("b")
    assertEquals(r.text(field), "b")
    o.dispose()
  }

  test("typing does not cause a redundant write back into the widget") {
    // The echo: type -> signal -> reconciler writes the same text back. The prop-dedupe in
    // the reconciler must swallow it, or a real toolkit moves the caret on every keystroke.
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val name = Var("")
    val m    = Reconciler.mount(r, Column()(TextField(name)(name.set)))
    val field = r.childrenOf(m.handle).head

    // The reconciler *will* call update: from its side the signal genuinely changed, and it
    // cannot know the widget already shows "x". Suppressing the echo is the renderer's job,
    // which is why the contract requires it and why this counts writes that landed.
    val before = r.textWrites
    r.typeText(field, "x")
    assertEquals(name.now, "x")
    assertEquals(r.textWrites - before, 0, "the widget was not disturbed by the echo")
    o.dispose()
  }

  test("an app may reject or transform an edit") {
    // Nothing closes the loop automatically, which is what makes validation possible.
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val name = Var("")
    val m = Reconciler.mount(
      r,
      Column()(TextField(name)(v => name.set(v.filter(_.isLetter).toUpperCase)))
    )
    val field = r.childrenOf(m.handle).head

    r.typeText(field, "a1b2")
    assertEquals(name.now, "AB")
    assertEquals(r.text(field), "AB", "the widget shows the transformed value")
    o.dispose()
  }

  test("a checkbox binds both ways") {
    val o = Owner(); given Owner = o
    val r    = TestRenderer()
    val done = Var(false)
    val m    = Reconciler.mount(r, Column()(Checkbox(done, label = "Done")(done.set)))
    val box  = r.childrenOf(m.handle).head

    assertEquals(r.nodes(box).props("checked"), "false")
    r.toggle(box)
    assertEquals(done.now, true)
    done.set(false)
    assertEquals(r.nodes(box).props("checked"), "false")
    o.dispose()
  }

  test("Scroll holds exactly one child") {
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Scroll(padding = 8)(Column()(Label("a"), Label("b"))))
    assertEquals(r.kind(m.handle), scalaui.renderer.WidgetKind.Scroll)
    assertEquals(r.childrenOf(m.handle).length, 1)
    assertEquals(r.childrenOf(r.childrenOf(m.handle).head).map(r.text), Seq("a", "b"))
    o.dispose()
  }

  test("a form: two fields and a checkbox driving one model") {
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
    val title = Var("")
    val notes = Var("")
    val done  = Var(false)
    val valid = title.map(_.trim.nonEmpty)

    val m = Reconciler.mount(
      r,
      Column(spacing = 8)(
        TextField(title, placeholder = "Title")(title.set),
        TextField(notes, placeholder = "Notes")(notes.set),
        Checkbox(done, "Done")(done.set),
        Button("Save", enabled = false)(())
      )
    )
    val kids = r.childrenOf(m.handle)

    assert(!valid.now, "empty title is not valid")
    r.typeText(kids(0), "Buy milk")
    r.typeText(kids(1), "semi-skimmed")
    r.toggle(kids(2))

    assertEquals((title.now, notes.now, done.now), ("Buy milk", "semi-skimmed", true))
    assert(valid.now, "a title makes it valid")
    o.dispose()
  }
}
