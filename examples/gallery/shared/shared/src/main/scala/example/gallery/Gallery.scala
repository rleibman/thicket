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
import thicket.renderer.{
  AlertAction,
  Alignment,
  CalendarDate,
  ContentFit,
  Emphasis,
  Icon,
  ImageSource,
  MenuItem,
  Orientation,
  TextRole
}
import example.gallery.buildinfo.BuildInfo
import thicket.effect.zio.{UiRuntime, asSignal}
import thicket.signals.{Owner, Signal, Var}
import _root_.zio.ZIO

/** Every component the framework has, on one screen, on every platform.
  *
  * This is a **conformance surface**, not a showcase. The catalogue is the framework's main claim — "these widgets
  * exist and are native everywhere" — and the only way that claim stays honest is if one screen exercises all of it and
  * is built on each platform we support. A widget that renders on GTK and silently does nothing on Android is a failure
  * the unit tests cannot see, because `TestRenderer` answers for all of them.
  *
  * **If you add a component, add it here in the same change.** `docs/12-component-status.md` §12.10 says so too, and
  * that is deliberate repetition: this file is the one a newcomer reads and that doc is the one a reviewer checks.
  *
  * Kept free of platform types so it compiles for JVM (Android) and Native (GTK, AppKit, UIKit) alike. Nothing here may
  * import a renderer.
  */
object Gallery {

  /** Everything with state lives here, so the screen itself is a pure function of signals. */
  final class Model {

    val text:    Var[String] = Var("")
    val secret:  Var[String] = Var("")
    val checked: Var[Boolean] = Var(false)
    val toggled: Var[Boolean] = Var(true)
    val volume:  Var[Double] = Var(4.0)
    val picked:  Var[Int] = Var(1)
    val produce: Var[Seq[String]] = Var(Gallery.fruit)
    // How many choices the picker has reported: one the user did not make shows up here.
    val reports:   Var[Int] = Var(0)
    val span:      Var[Int] = Var(1)
    val due:       Var[CalendarDate] = Var(Gallery.firstDue)
    val linkTaps:  Var[Int] = Var(0)
    val labelTaps: Var[Int] = Var(0)
    val rowTaps:   Var[Int] = Var(0)
    // Swaps the horizontal Scroll's child for a taller one: its height has to follow (#76).
    val tallCols:  Var[Boolean] = Var(false)
    val gridExtra: Var[Boolean] = Var(false)
    val lastIcon:  Var[String] = Var("")
    // Not 0: the platform shows the first tab by default, so starting anywhere else is what
    // proves the app's initial selection reaches it (#70 review: GTK dropped it).
    val tab:      Var[Int] = Var(1)
    val extraTab: Var[Boolean] = Var(false)
    val busy:     Var[Boolean] = Var(false)
    val sheet:    Var[Boolean] = Var(false)
    val alerting: Var[Boolean] = Var(false)

    /** The big list's data. A `Var`, not a constant: a list whose data never changes cannot show that a renderer
      * re-binds the rows it is showing when it does (review 2026-10-20, P1-3).
      */
    val bigRows: Var[Vector[Row_]] = Var(Vector.tabulate(10000)(i => Row_(i, s"Row $i")))

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

    def rotate(): Unit =
      rows.now match {
        case Seq()        => ()
        case head +: rest => rows.set(rest :+ head)
      }

  }

  /** The date picker's starting date. A leap day, so a conversion that slips by one is visible as March 1. */
  val firstDue: CalendarDate = CalendarDate(2028, 2, 29)

  /** The TabView's tab titles. On the object so the self-test can name them. */
  val tabTitles: Seq[String] = Seq("Home", "Mail", "Settings")

  /** The segmented control's options. On the object so the self-test can name them. */
  val spans: Seq[String] = Seq("Day", "Week", "Month")

  /** Where the gallery's links go. On the object so the self-test can say what should have opened. */
  val repoUrl:    String = "https://github.com/rleibman/thicket"
  val licenceUrl: String = "https://www.apache.org/licenses/LICENSE-2.0"

  /** The message of the exception the "Throw from a handler" button raises, so a self-test can tell it apart. */
  val deliberate: String = "deliberate, from the gallery's Errors section"

  /** The logo, as a file and as the bytes of that file. A host with no filesystem path to it — an APK — passes the
    * bytes to [[app]] itself; `FromFile` then finds it in the APK's assets at the same relative path.
    *
    * [[logoSvg]] where the platform's image decoder draws SVG (GTK, through gdk-pixbuf; AppKit on macOS 14+, through
    * `NSImage`, #85); [[logo]], the PNG made from it by `bin/logo-assets.sh`, where it takes raster only (Android's
    * `BitmapFactory`, UIKit's `UIImage`, AppKit on macOS 13).
    */
  val logo:    String = "docs/assets/thicket-logo.png"
  val logoSvg: String = "docs/assets/thicket-logo.svg"

  /** The SVG's own size, from its `width` and `height`: what a decoder that reads the SVG reports for it (#85). */
  val logoSvgSize: (Double, Double) = (472.658, 599.168)

  /** The logo file the running app was given. Set by [[app]], for a host's self-test to know which picture it expects.
    */
  var currentLogoFile: String = logo

  def logoBytes(path: String = logo): Array[Byte] =
    scala.util.Try(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))).getOrElse(Array.emptyByteArray)

  /** The picker's options. On the object so the self-test can name them. */
  val fruit:      Seq[String] = Seq("Apple", "Banana", "Cherry")
  val vegetables: Seq[String] = Seq("Leek", "Okra", "Pea")

  final case class Row_(
    id:    Int,
    title: String
  )

  /** A labelled block, so the screen reads as a catalogue rather than a pile of widgets. */
  private def section(
    name: String
  )(
    body: Element*
  ): Element =
    Column(spacing = 8)(
      Label(name, style = TextRole.Caption, emphasis = Emphasis.Secondary),
      Column(spacing = 8)(body*),
      Divider()
    )

  /** What the effect in the Effects section produces, so a self-test can wait for it. */
  val effectResult: String = "Loaded 42, on the UI thread"

  def screen(
    model:    Model,
    loaded:   Signal[RemoteData[Nothing, String]],
    logoFile: String,
    logo:     Array[Byte],
    storage:  Option[StorageDemo] = None,
    device:   Option[DeviceDemo] = None,
    location: Option[LocationDemo] = None,
    motion:   Option[MotionDemo] = None,
    clip:     Option[ClipboardDemo] = None,
    pickers:  Option[PickersDemo] = None
  ): Screen =
    Screen(
      title = "Thicket gallery",
      actions = Seq(Action("Add")(model.add()), Action("Rotate")(model.rotate())),
      // The gallery asks for it too, so a screenshot on a notched device is not clipped.
      // The padding is the Scroll's, not the Column's, so the one view `.safeArea()` is on has
      // both: the insets are added to the padding, not written over it (#76).
      content = Scroll(padding = 16)(
        Column(spacing = 16)(
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
            Picker(model.produce, model.picked) { i =>
              model.reports.set(model.reports.now + 1)
              model.picked.set(i)
            },
            Label(model.reports.map(n => s"The picker reported $n choices"), style = TextRole.Caption),
            // Observably bound, per the gallery's own rule: the label proves the selection
            // round-tripped rather than the control merely being drawn.
            Label(
              model.produce
                .zip(model.picked).map(
                  (
                    opts,
                    i
                  ) => opts.lift(i).fold("(nothing selected)")(f => s"Chose $f (index $i)")
                )
            ),
            Row(spacing = 8)(
              Button("Select last")(model.picked.set(Gallery.fruit.length - 1)),
              Button("Clear")(model.picked.set(-1)),
              // New options, same length: the selection is an index and must stay where it is,
              // with no choice reported that the user did not make (review 2026-10-20, P1-10).
              Button("Swap the options")(
                model.produce.set(if model.produce.now == Gallery.fruit then Gallery.vegetables else Gallery.fruit)
              )
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
            Label(model.linkTaps.map(n => s"The licence link was tapped $n times"), style = TextRole.Caption),
            // A leaf with no action of its own, made tappable: OnTap must fire on any element, not
            // only on a Button or a container (review 2026-10-20 M6, #75).
            Label("Tap this label").onTap(model.labelTaps.set(model.labelTaps.now + 1)),
            Label(model.labelTaps.map(n => s"The label was tapped $n times"), style = TextRole.Caption)
          ),
          section("Slider, ProgressBar, ActivityIndicator")(
            Slider(model.volume, min = 0, max = 11)(model.volume.set),
            // Determinate, driven by the slider above, so the binding is visible.
            ProgressBar(model.progress),
            // Indeterminate: animates on every platform, which is what tells it apart from 0.
            ProgressBar(None),
            Row(spacing = 8)(
              Button(model.busy.map(b => if b then "Stop" else "Start"))(model.busy.set(!model.busy.now)),
              Show(model.busy)(Spinner())
            )
          ),
          section("Layout — Spacer, Grow, Divider, nested Row and Column")(
            Row(spacing = 8)(Label("left"), Spacer(), Label("right")),
            Row(spacing = 8)(Label("grown").grow, Label("fixed")),
            // A Spacer in a *Column*: Grow is along the parent's main axis, so here it pushes
            // "bottom" down to line up with the taller column beside it (review 2026-10-20, P1-4).
            Row(spacing = 8)(
              Column(spacing = 4)(Label("top"), Spacer(), Label("bottom")),
              Column(spacing = 4)(Label("one"), Label("two"), Label("three"), Label("four"))
            ),
            // Start, Center and End, so a renderer that maps them wrongly — or not RTL-aware — shows it.
            Label("Start", align = Alignment.Start),
            Label("Center", align = Alignment.Center),
            Label("End", align = Alignment.End),
            // A tappable Row: a tap is a press and a release inside it, not the press alone (#76).
            Row(spacing = 8)(
              Label("Tap this row"),
              Label(model.rowTaps.map(n => s"Row tapped $n times"), style = TextRole.Caption)
            ).onTap(model.rowTaps.set(model.rowTaps.now + 1))
          ),
          section("ZStack — children drawn over one another, last on top")(
            Row(spacing = 16)(
              // The badge case: trailing and top, so the two axes are told apart. The count is
              // the ForEach section's row count, so "Add" in the toolbar proves it is bound.
              ZStack(Alignment.End, Alignment.Start)(
                Image(ImageSource.FromFile(logoFile), fit = ContentFit.Contain),
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
          section("Grid — cells flow into columns, each as wide as its widest cell")(
            Grid(columns = 2, spacing = 8)(
              Label("Typed above"),
              // Bound, per the gallery's rule: the TextField and the Slider earlier on this
              // screen drive these, so the cells are live rather than drawn once.
              Label(model.text.map(t => if t.isEmpty then "(nothing yet)" else t)),
              // A row appearing in the middle of the grid: every cell after it moves along.
              Show(model.gridExtra)(Fragment(Label("Inserted"), Label("a whole row"))),
              Label("Volume"),
              Label(model.volume.map(v => f"$v%.1f")),
              Label("A much longer label than the others"),
              Label("short")
            ),
            Button(model.gridExtra.map(b => if b then "Remove the row" else "Insert a row"))(
              model.gridExtra.set(!model.gridExtra.now)
            )
          ),
          section("IconButton — every curated icon, each with its label")(
            // All 24 at once, so a reviewer sees every mapping on every platform; in a Grid,
            // which is what a toolbar of icons is.
            Grid(columns = 8, spacing = 4)(
              Icon.curated.map(i => IconButton(i, i.toString)(model.lastIcon.set(i.toString)))*
            ),
            Row(spacing = 8)(
              IconButton(Icon.Delete, "Delete (disabled)", enabled = false)(()),
              // Bound: proves a tap went through the renderer to the app.
              Label(model.lastIcon.map(n => if n.isEmpty then "Tap an icon" else s"Tapped $n"))
            )
          ),
          section("TabView — sections kept alive, switched with the platform's tab bar")(
            TabView(model.tab)(model.tab.set)(
              Tab(Gallery.tabTitles(0), Icon.Home)(Label("The home tab's content")),
              // A tab that appears in the middle: the others must keep their order and content.
              Show(model.extraTab)(Tab("Extra", Icon.Star)(Label("The extra tab's content"))),
              Tab(Gallery.tabTitles(1), Icon.Mail)(Label("The mail tab's content")),
              Tab(Gallery.tabTitles(2), Icon.Settings)(Label("The settings tab's content"))
            ),
            // Bound: the label is the app's own record of the selection, so a tap on the
            // platform's bar showing up here proves it went through the renderer.
            Label(model.tab.map(i => s"Showing tab $i")),
            Button(model.extraTab.map(b => if b then "Remove the extra tab" else "Add a tab in the middle"))(
              model.extraTab.set(!model.extraTab.now)
            )
          ),
          section("Image — the three content fits")(
            Row(spacing = 8)(
              Image(ImageSource.FromFile(logoFile), fit = ContentFit.Contain),
              Image(ImageSource.FromFile(logoFile), fit = ContentFit.Cover),
              // The third fit, and the other source: bytes the app already holds, decoded by the
              // platform (review 2026-10-20, P2-23).
              Image(ImageSource.FromBytes(logo), fit = ContentFit.Fill)
            )
          ),
          section("Scroll — horizontal, inside a vertical one")(
            // Padded: on every platform the padding insets the content and scrolls with it.
            Scroll(padding = 8, axis = Orientation.Horizontal)(
              // Replaced, not changed: the scroller's height follows its new child (#76).
              Switch(model.tallCols) { tall =>
                if tall then Row(spacing = 8)(Seq.tabulate(12)(i => Label(s"col $i\ntaller"))*)
                else Row(spacing = 8)(Seq.tabulate(12)(i => Label(s"col $i"))*)
              }
            ),
            Button(model.tallCols.map(t => if t then "Shorter columns" else "Taller columns"))(
              model.tallCols.set(!model.tallCols.now)
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
              Provide(Theme.active.withColor(ColorRole.Accent, Rgb(0xb0, 0x30, 0x30))) {
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
          // A handler that throws must not take the app down: the renderer reports it to
          // ErrorSink and returns to the toolkit normally (review 2026-10-20, P0-3).
          section("Errors — a handler that throws is reported, and the app carries on")(
            Button("Throw from a handler")(throw RuntimeException(Gallery.deliberate))
          ),
          // A real ZIO effect, run on a worker and posted back through UiThread. It only ever
          // leaves Loading if the host installed a poster (review 2026-10-20, P0-5): before
          // that, no host did, and this would have sat at Loading on every platform.
          section("Effects — a ZIO effect, its result posted back to the UI thread")(
            RemoteData(loaded) {
              case RemoteData.Loading      => Label("Loading…")
              case RemoteData.Done(result) => Label(result)
              case RemoteData.Failed(_)    => Label("Failed")
            }
          ),
          // Device services (docs/16): preferences, SQLite and the platform's secure store, the
          // database and the secrets off the UI thread. Every host passes its own (Apple's storage since #98,
          // its Keychain since #108).
          section("Storage — preferences, SQLite and secrets, in the app's own places")(
            storage match {
              case None =>
                Label("No storage on this platform yet", emphasis = Emphasis.Secondary)
              case Some(demo) =>
                Column(spacing = 8)(
                  Label(s"Opened ${demo.opens} times"),
                  Label(demo.notes.map(_.fold(identity, StorageDemo.notesLabel))),
                  Button("Add a note")(demo.addNote()),
                  Label(demo.secret)
                )
            }
          ),
          // The app's lifecycle and a permission (docs/16 §16.10 item 3): the plumbing every later device service sits
          // on. GTK and Android so far; Apple's is a Mac issue.
          section("Device — the app's lifecycle, and asking for a permission")(
            device match {
              case None =>
                Label("No lifecycle or permissions on this platform yet", emphasis = Emphasis.Secondary)
              case Some(demo) =>
                Column(spacing = 8)(
                  Label(demo.state),
                  Label(demo.returns.map(DeviceDemo.returnsLabel)),
                  Label(demo.notifications),
                  Button("Ask for notifications")(demo.askForNotifications())
                )
            }
          ),
          // The device's position (docs/16 §16.10 item 4), followed only while the app is in front. Android's
          // LocationManager; GTK has no location service yet and says so.
          section("Location — the device's position, while the app is in front")(
            location match {
              case None       => Label("No location on this platform yet", emphasis = Emphasis.Secondary)
              case Some(demo) =>
                Column(spacing = 8)(
                  Label(demo.availability),
                  Label(demo.position),
                  Button("Use my location")(demo.use())
                )
            }
          ),
          // The motion sensors (docs/16 §16.10 item 4), running only while the app is in front. Android's
          // SensorManager; GTK has none to offer yet and says so.
          section("Motion — acceleration, rotation and attitude, while the app is in front")(
            motion match {
              case None       => Label("No motion sensors on this platform yet", emphasis = Emphasis.Secondary)
              case Some(demo) =>
                Column(spacing = 8)(
                  Label(demo.sensors),
                  Label(demo.acceleration),
                  Label(demo.rotationRate),
                  Label(demo.attitude)
                )
            }
          ),
          // The system clipboard and haptic taps (docs/16 §16.10 item 5). GTK has no haptics and says so.
          section("Clipboard and haptics — copy, paste, and a tap to feel")(
            clip match {
              case None       => Label("No clipboard or haptics on this platform yet", emphasis = Emphasis.Secondary)
              case Some(demo) =>
                Column(spacing = 8)(
                  Row(spacing = 8)(
                    Button("Copy") { val _ = demo.copy() },
                    Button("Paste")(demo.paste())
                  ),
                  Label(demo.pasted),
                  Label(demo.hapticsLabel),
                  Button("Tap")(demo.tap())
                )
            }
          ),
          // The system's pickers (docs/16 §16.10 item 5): each picked file comes back as a copy in the app's cache.
          section("Pickers — a photo, a document, and saving one")(
            pickers match {
              case None       => Label("No pickers on this platform yet", emphasis = Emphasis.Secondary)
              case Some(demo) =>
                Column(spacing = 8)(
                  Button("Pick a photo")(demo.pickPhoto()),
                  Label(demo.photo),
                  Button("Open a document")(demo.openDocument()),
                  Label(demo.document),
                  Button("Save a document")(demo.saveDocument()),
                  Label(demo.saved)
                )
            }
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
  private def listScreen(model: Model): Screen =
    Screen(
      title = "10 000 rows",
      content = Column(spacing = 8, padding = 16)(
        Label("LazyColumn — virtualised where the platform has a recycling container.", style = TextRole.Caption),
        // Changes the rows on screen without changing what scrolls, so a renderer that only
        // tracks the count shows stale rows.
        Row(spacing = 8)(
          Button("Delete the first row")(model.bigRows.set(model.bigRows.now.drop(1))),
          Button("Rename the first row")(
            model.bigRows.set(
              model.bigRows.now.headOption.fold(Vector.empty[Row_])(r =>
                r.copy(title = s"Renamed ${r.id}") +: model.bigRows.now.tail
              )
            )
          )
        ),
        LazyColumn(model.bigRows, key = (r: Row_) => r.id) { row =>
          Row(spacing = 8)(Label(row.map(_.title)).grow)
        }.grow
      )
    )

  enum Route {

    case Catalogue, Big

  }

  /** The running app's navigation, for a host's self-test to drive. Set by [[app]]. */
  var currentNav: Option[Nav[Route]] = None

  /** The running app's storage demo, for a host's self-test. Set by [[app]] when the host provides services. */
  var currentStorage: Option[StorageDemo] = None

  /** The running app's device demo, for a host's self-test. Set by [[app]] when the host provides a `Device`. */
  var currentDevice: Option[DeviceDemo] = None

  /** The running app's location demo, for a host's self-test. Set by [[app]] when the host provides a `Location`. */
  var currentLocation: Option[LocationDemo] = None

  /** The running app's motion demo, for a host's self-test. Set by [[app]] when the host provides `Motion`. */
  var currentMotion: Option[MotionDemo] = None

  /** The running app's clipboard and haptics demo, for a host's self-test. */
  var currentClipboard: Option[ClipboardDemo] = None

  /** The running app's pickers demo, for a host's self-test. */
  var currentPickers: Option[PickersDemo] = None

  /** The whole app, for a host to mount. Hosts differ only in how they start, in which logo file their platform can
    * draw, and in the device services they have.
    */
  def app(
    logoFile: String = logo,
    logo:     Array[Byte] = Array.emptyByteArray,
    services: Option[Services] = None
  )(using Owner
  ): AppRoot = {
    val bytes = if logo.nonEmpty then logo else logoBytes(logoFile)
    currentLogoFile = logoFile
    val model = Model()
    val storage = services.map(StorageDemo(_))
    currentStorage = storage
    val device = services.flatMap(_.device).map(DeviceDemo(_))
    currentDevice = device
    val location = services.flatMap(svc => svc.location.map(LocationDemo(_, svc.device)))
    currentLocation = location
    val motion = services.flatMap(_.motion).map(MotionDemo(_))
    currentMotion = motion
    val clip =
      services.filter(s => s.clipboard.isDefined || s.haptics.isDefined).map(s => ClipboardDemo(s.clipboard, s.haptics))
    currentClipboard = clip
    val pickers = services
      .flatMap(_.pickers).map(
        (
          photos,
          documents
        ) => PickersDemo(photos, documents)
      )
    currentPickers = pickers
    given UiRuntime[Any] = UiRuntime.default
    val loaded = ZIO.succeed(effectResult).asSignal
    val nav = Nav(Route.Catalogue)
    currentNav = Some(nav)
    NavHost(nav) {
      case Route.Catalogue =>
        val s = screen(model, loaded, logoFile, bytes, storage, device, location, motion, clip, pickers)
        s.copy(actions = s.actions :+ Action("Big list")(nav.push(Route.Big)))
      case Route.Big => listScreen(model)
    }
  }

}
