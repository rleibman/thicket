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
import thicket.renderer.{AlertAction, Alignment, CalendarDate, ContentFit, Emphasis, ImageSource, MenuItem, Orientation, TextRole}
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
    val picked:   Var[Int]     = Var(1)
    val span:     Var[Int]     = Var(1)
    val due:      Var[CalendarDate] = Var(Gallery.firstDue)
    val linkTaps: Var[Int]     = Var(0)
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

  /** The date picker's starting date. A leap day, so a conversion that slips by one is visible as March 1. */
  val firstDue: CalendarDate = CalendarDate(2028, 2, 29)

  /** The segmented control's options. On the object so the self-test can name them. */
  val spans: Seq[String] = Seq("Day", "Week", "Month")

  /** Where the gallery's links go. On the object so the self-test can say what should have opened. */
  val repoUrl:    String = "https://github.com/rleibman/thicket"
  val licenceUrl: String = "https://www.apache.org/licenses/LICENSE-2.0"

  /** The picker's options. On the object so the self-test can name them. */
  val fruit: Seq[String] = Seq("Apple", "Banana", "Cherry")

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
      // The gallery asks for it too, so a screenshot on a notched device is not clipped.
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
          section("Picker — a choice from a fixed list")(
            Picker(Gallery.fruit, model.picked)(model.picked.set),
            // Observably bound, per the gallery's own rule: the label proves the selection
            // round-tripped rather than the control merely being drawn.
            Label(model.picked.map(i => Gallery.fruit.lift(i).fold("(nothing selected)")(f => s"Chose $f (index $i)"))),
            Row(spacing = 8)(
              Button("Select last")(model.picked.set(Gallery.fruit.length - 1)),
              Button("Clear")(model.picked.set(-1))
            )
          ),
          section("DatePicker — a day, in the platform's own chooser")(
            DatePicker(model.due)(model.due.set),
            // Bound: the label is the model's date, in ISO form, so it can be compared with
            // what the platform shows in its own format.
            Label(model.due.map(d => s"Due $d")),
            Button("Back to the leap day")(model.due.set(Gallery.firstDue))
          ),
          section("SegmentedControl — one of a few, all on screen")(
            SegmentedControl(Gallery.spans, model.span)(model.span.set),
            // Bound, per the gallery's rule: the label proves the choice round-tripped.
            Label(model.span.map(i => Gallery.spans.lift(i).fold("(no span)")(s => s"Showing a $s"))),
            Row(spacing = 8)(
              Button("Select first")(model.span.set(0)),
              Button("Clear")(model.span.set(-1))
            )
          ),
          section("Link — opens a URL, drawn as each platform draws a link")(
            Link("thicket on GitHub", Gallery.repoUrl),
            // A link that also does something in the app. Both must run: a renderer that wires
            // one click handler per widget would let whichever came last win.
            Link("Apache License 2.0", Gallery.licenceUrl).onTap(model.linkTaps.set(model.linkTaps.now + 1)),
            Label(model.linkTaps.map(n => s"The licence link was tapped $n times"), style = TextRole.Caption)
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
          section("ZStack — children drawn over one another, last on top")(
            Row(spacing = 16)(
              // The badge case: trailing and top, so the two axes are told apart. The count is
              // the ForEach section's row count, so "Add" in the toolbar proves it is bound.
              ZStack(Alignment.End, Alignment.Start)(
                Image(ImageSource.FromFile("docs/assets/thicket-logo.png"), fit = ContentFit.Contain),
                Label(model.rows.map(rs => s"${rs.size} rows"), style = TextRole.Caption)
              ),
              // Centred, the default: a spinner over the content it is waiting on. Always
              // spinning, so the overlap is on screen without anyone having to start it.
              ZStack()(
                Label("Underneath\nthe spinner"),
                Spinner()
              )
            )
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
          // A prop, so there is nothing to *show* — the honest demonstration is the
          // declaration plus a note of what each platform does with it.
          section("SafeArea — a prop, because no toolkit models it as a widget")(
            Label("This screen is wrapped in .safeArea(), so its content clears the status bar."),
            Label(
              "Apple: safeAreaInsets. Android: WindowInsets on the view. GTK: nothing to do — " +
                "a desktop window's safe area is the whole window.",
              style = TextRole.Caption,
              emphasis = Emphasis.Secondary
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
      ).safeArea()
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
