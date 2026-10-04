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

package example.gallery

import thicket.core.*
import thicket.core.dsl.*
import thicket.renderer.{AlertAction, ContentFit, Emphasis, ImageSource, MenuItem, Orientation, TextRole}
import example.gallery.buildinfo.BuildInfo
import thicket.signals.{Signal, Var}

/** Every component the framework has, on one screen, on every platform.
  *
  * This is a **conformance surface**, not a showcase. The catalogue is the framework's main
  * claim — "these widgets exist and are native everywhere" — and the only way that claim
  * stays honest is if one screen exercises all of it and is built on each platform we
  * support. A widget that renders on GTK and silently does nothing on Android is a failure
  * the unit tests cannot see, because `TestRenderer` answers for all of them.
  *
  * **If you add a component, add it here in the same change.** `docs/12-component-status.md`
  * §12.10 says so too, and that is deliberate repetition: this file is the one a newcomer
  * reads and that doc is the one a reviewer checks.
  *
  * Kept free of platform types so it compiles for JVM (Android) and Native (GTK, AppKit,
  * UIKit) alike. Nothing here may import a renderer.
  */
object Gallery {

  /** Everything with state lives here, so the screen itself is a pure function of signals. */
  final class Model {

    val text:     Var[String]  = Var("")
    val secret:   Var[String]  = Var("")
    val checked:  Var[Boolean] = Var(false)
    val toggled:  Var[Boolean] = Var(true)
    val volume:   Var[Double]  = Var(4.0)
    val busy:     Var[Boolean] = Var(false)
    val sheet:    Var[Boolean] = Var(false)
    val alerting: Var[Boolean] = Var(false)

    /** Rows for `ForEach`, so keyed reconciliation is exercised rather than described. */
    val rows: Var[Seq[Row_]] = Var(Seq(Row_(1, "First"), Row_(2, "Second"), Row_(3, "Third")))

    /** Determinate progress, derived rather than stored — the common real shape. */
    val progress: Signal[Option[Double]] = volume.map(v => Some(v / 11.0))

    private var nextId = 4

    def add(): Unit = {
      rows.set(rows.now :+ Row_(nextId, s"Row $nextId"))
      nextId += 1
    }

    def remove(id: Int): Unit = rows.set(rows.now.filterNot(_.id == id))

    def rotate(): Unit = rows.now match {
      case Seq()           => ()
      case head +: rest    => rows.set(rest :+ head)
    }

  }

  final case class Row_(
    id:    Int,
    title: String
  )

  /** A labelled block, so the screen reads as a catalogue rather than a pile of widgets. */
  private def section(
    name:  String
  )(body: Element*
  ): Element =
    Column(spacing = 8)(
      Label(name, style = TextRole.Caption, emphasis = Emphasis.Secondary),
      Column(spacing = 8)(body*),
      Divider()
    )

  def screen(model: Model): Screen =
    Screen(
      title = "Thicket gallery",
      actions = Seq(Action("Add")(model.add()), Action("Rotate")(model.rotate())),
      content = Scroll()(
        Column(spacing = 16, padding = 16)(
          section("Label — every text role")(
            Label("Title", style = TextRole.Title),
            Label("Body"),
            Label("Caption", style = TextRole.Caption),
            Label("Secondary", emphasis = Emphasis.Secondary)
          ),
          section("Button — roles, and one disabled")(
            Row(spacing = 8)(
              Button("Accent")(()),
              Button("Danger", role = ColorRole.Danger)(()),
              Button("Disabled", enabled = false)(())
            )
          ),
          section("TextField and SecureField")(
            TextField(model.text, placeholder = "Type here")(model.text.set),
            SecureField(model.secret, placeholder = "Passphrase")(model.secret.set),
            // Proves the field is bound both ways rather than just displayed.
            Label(model.text.map(s => if s.isEmpty then "(empty)" else s"You typed: $s"))
          ),
          section("Checkbox and Toggle")(
            Checkbox(model.checked, label = "Checkbox")(model.checked.set),
            Row(spacing = 8)(
              Label("Toggle").grow,
              Toggle(model.toggled)(model.toggled.set)
            ),
            Label(model.toggled.map(b => if b then "on" else "off"))
          ),
          section("Slider, ProgressBar, ActivityIndicator")(
            Slider(model.volume, min = 0, max = 11)(model.volume.set),
            // Determinate, driven by the slider above, so the binding is visible.
            ProgressBar(model.progress),
            Row(spacing = 8)(
              Button(model.busy.map(b => if b then "Stop" else "Start"))(model.busy.set(!model.busy.now)),
              Show(model.busy)(Spinner())
            )
          ),
          section("Layout — Spacer, Grow, Divider, nested Row and Column")(
            Row(spacing = 8)(Label("left"), Spacer(), Label("right")),
            Row(spacing = 8)(Label("grown").grow, Label("fixed"))
          ),
          section("Image — the three content fits")(
            Row(spacing = 8)(
              Image(ImageSource.FromFile("docs/assets/thicket-logo.png"), fit = ContentFit.Contain),
              Image(ImageSource.FromFile("docs/assets/thicket-logo.png"), fit = ContentFit.Cover)
            )
          ),
          section("Scroll — horizontal, inside a vertical one")(
            Scroll(axis = Orientation.Horizontal)(
              Row(spacing = 8)(Seq.tabulate(12)(i => Label(s"col $i"))*)
            )
          ),
          section("ForEach — keyed, with a context menu on each row")(
            Column(spacing = 4)(
              ForEach(model.rows, key = (r: Row_) => r.id) { row =>
                Row(spacing = 8)(
                  Label(row.map(_.title)).grow,
                  Button("Remove")(model.remove(row.now.id))
                ).contextMenu(
                  MenuItem("Remove")(model.remove(row.now.id)),
                  MenuItem("Rotate")(model.rotate())
                )
              }
            ),
            Label(model.rows.map(rs => s"${rs.length} rows — Add and Rotate are in the toolbar"))
          ),
          section("Provide — a theme scoped to one subtree")(
            Row(spacing = 8)(
              Button("App accent")(()),
              Provide(Theme.active.withColor(ColorRole.Accent, Rgb(0xB0, 0x30, 0x30))) {
                Button("Scoped red")(())
              }
            )
          ),
          // Proof that BuildInfo is wired, not just generated: this is `galleryShared`'s own
          // object, in its own package, carrying the git-derived version. Each module has
          // one, so a bug report from any platform can name the commit it was built from.
          section("BuildInfo — this module's own, from git")(
            Label(s"${BuildInfo.name} ${BuildInfo.version}", style = TextRole.Caption),
            Label(
              s"${BuildInfo.gitCurrentBranch} @ ${BuildInfo.gitHeadCommit.map(_.take(8)).getOrElse("unknown")}" +
                (if BuildInfo.gitUncommittedChanges then " (dirty)" else ""),
              style = TextRole.Caption,
              emphasis = Emphasis.Secondary
            ),
            Label(s"built ${BuildInfo.builtAtString}", style = TextRole.Caption, emphasis = Emphasis.Secondary)
          ),
          section("Presented — Sheet and Alert are shown over the app, never inserted")(
            Row(spacing = 8)(
              Button("Open sheet")(model.sheet.set(true)),
              Button("Show alert", role = ColorRole.Danger)(model.alerting.set(true))
            ),
            Show(model.sheet) {
              Sheet("A sheet")(
                Column(spacing = 12, padding = 16)(
                  Label("A presented container with a live subtree inside it."),
                  TextField(model.text, placeholder = "Still bound")(model.text.set),
                  Button("Close")(model.sheet.set(false))
                )
              )(model.sheet.set(false))
            },
            Show(model.alerting) {
              Alert("Delete everything?", "This cannot be undone.")(
                AlertAction("Delete", destructive = true) {
                  model.rows.set(Nil)
                  model.alerting.set(false)
                },
                AlertAction("Cancel", cancel = true)(model.alerting.set(false))
              )(model.alerting.set(false))
            }
          )
        )
      )
    )

  /** A second screen, so navigation is exercised: a push, a title and a way back. */
  private def listScreen(): Screen =
    Screen(
      title = "10 000 rows",
      content = Column(spacing = 8, padding = 16)(
        Label("LazyColumn — virtualised where the platform has a recycling container.", style = TextRole.Caption),
        LazyColumn(Signal.const(Seq.tabulate(10000)(i => Row_(i, s"Row $i"))), key = (r: Row_) => r.id) { row =>
          Row(spacing = 8)(Label(row.map(_.title)).grow)
        }.grow
      )
    )

  enum Route {

    case Catalogue, Big

  }

  /** The whole app, for a host to mount. Hosts differ only in how they start. */
  def app(): AppRoot = {
    val model = Model()
    val nav   = Nav(Route.Catalogue)
    NavHost(nav) {
      case Route.Catalogue =>
        val s = screen(model)
        s.copy(actions = s.actions :+ Action("Big list")(nav.push(Route.Big)))
      case Route.Big => listScreen()
    }
  }

}
