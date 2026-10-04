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

package thicket.core

import zio.test.*
import thicket.signals.Checks

import thicket.core.dsl.*
import thicket.signals.{Owner, Var}

/** Input widgets: the first case where data flows from the widget back to the app.
  *
  * The dangerous bug here is the echo loop — the renderer writes a value, the widget reports a change, the app writes
  * it again. These tests pin the guard against it.
  */

object InputSpec extends ZIOSpecDefault {

  def spec =
    suite("Input")(
      test("a text field shows its bound value and reports edits") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val name = Var("")
        val m = Reconciler.mount(r, Column()(TextField(name, placeholder = "Name")(name.set)))
        val field = r.childrenOf(m.handle).head

        chk.eq(r.nodes(field).props("placeholder"), "Name")
        r.typeText(field, "Rob")
        chk.eq(name.now, "Rob", "the edit reached the app")
        chk.eq(r.text(field), "Rob")
        o.dispose()
        chk.result
      },
      test("writing the signal updates the field") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val name = Var("a")
        val m = Reconciler.mount(r, Column()(TextField(name)(name.set)))
        val field = r.childrenOf(m.handle).head

        chk.eq(r.text(field), "a")
        name.set("b")
        chk.eq(r.text(field), "b")
        o.dispose()
        chk.result
      },
      test("typing does not cause a redundant write back into the widget") {
        val chk = Checks()
        // The echo: type -> signal -> reconciler writes the same text back. The prop-dedupe in
        // the reconciler must swallow it, or a real toolkit moves the caret on every keystroke.
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val name = Var("")
        val m = Reconciler.mount(r, Column()(TextField(name)(name.set)))
        val field = r.childrenOf(m.handle).head

        // The reconciler *will* call update: from its side the signal genuinely changed, and it
        // cannot know the widget already shows "x". Suppressing the echo is the renderer's job,
        // which is why the contract requires it and why this counts writes that landed.
        val before = r.textWrites
        r.typeText(field, "x")
        chk.eq(name.now, "x")
        chk.eq(r.textWrites - before, 0, "the widget was not disturbed by the echo")
        o.dispose()
        chk.result
      },
      test("an app may reject or transform an edit") {
        val chk = Checks()
        // Nothing closes the loop automatically, which is what makes validation possible.
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val name = Var("")
        val m = Reconciler.mount(
          r,
          Column()(TextField(name)(v => name.set(v.filter(_.isLetter).toUpperCase)))
        )
        val field = r.childrenOf(m.handle).head

        r.typeText(field, "a1b2")
        chk.eq(name.now, "AB")
        chk.eq(r.text(field), "AB", "the widget shows the transformed value")
        o.dispose()
        chk.result
      },
      test("a checkbox binds both ways") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val done = Var(false)
        val m = Reconciler.mount(r, Column()(Checkbox(done, label = "Done")(done.set)))
        val box = r.childrenOf(m.handle).head

        chk.eq(r.nodes(box).props("checked"), "false")
        r.toggle(box)
        chk.eq(done.now, true)
        done.set(false)
        chk.eq(r.nodes(box).props("checked"), "false")
        o.dispose()
        chk.result
      },
      test("Scroll holds exactly one child") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Scroll(padding = 8)(Column()(Label("a"), Label("b"))))
        chk.eq(r.kind(m.handle), thicket.renderer.WidgetKind.Scroll)
        chk.eq(r.childrenOf(m.handle).length, 1)
        chk.eq(r.childrenOf(r.childrenOf(m.handle).head).map(r.text), Seq("a", "b"))
        o.dispose()
        chk.result
      },
      test("Scroll defaults to vertical and carries a horizontal axis when asked") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        // The overflow answer for Row: Row itself clips, so a row too wide for the screen
        // goes inside a horizontal Scroll. See docs/11 "Two gaps this exposed".
        val down = Reconciler.mount(r, Scroll()(Label("a")))
        val across = Reconciler.mount(
          r,
          Scroll(axis = thicket.renderer.Orientation.Horizontal)(
            Row()(Label("a"), Label("b"), Label("c"), Label("d"), Label("e"))
          )
        )
        chk.eq(r.nodes(down.handle).props("axis"), "Vertical")
        chk.eq(r.nodes(across.handle).props("axis"), "Horizontal")
        chk.eq(r.childrenOf(r.childrenOf(across.handle).head).length, 5)
        o.dispose()
        chk.result
      },
      test("a form: two fields and a checkbox driving one model") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val title = Var("")
        val notes = Var("")
        val done = Var(false)
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

        chk.yes(!valid.now, "empty title is not valid")
        r.typeText(kids(0), "Buy milk")
        r.typeText(kids(1), "semi-skimmed")
        r.toggle(kids(2))

        chk.eq((title.now, notes.now, done.now), ("Buy milk", "semi-skimmed", true))
        chk.yes(valid.now, "a title makes it valid")
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
