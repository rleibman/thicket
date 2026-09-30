package example

import thicket.core.*
import thicket.core.dsl.*
import thicket.renderer.{Alignment, AlertAction, Emphasis, Orientation, TextRole}
import thicket.signals.{Signal, Var}

/** A two-screen todo app, with no reference to any platform.
  *
  * The same value is mounted by the GTK host on Linux and the Android host on a phone, and
  * each applies its own chrome — a header bar with a back arrow, an action bar with Up and
  * the system Back button — from `AppRoot`.
  */
object TodoApp {

  final case class Item(id: Int, title: String, done: Boolean)

  /** Navigation is an ADT: a screen cannot be reached without its arguments, the back stack
    * is `List[Route]`, and the `screenFor` match below is checked for exhaustiveness.
    */
  enum Route {
    case Items
    case Detail(id: Int)
    case About

    /** A deliberately huge list, to show `LazyColumn` materialising only what is visible. */
    case Big
  }

  /** A route is a value, so persisting the back stack is ordinary serialisation.
    *
    * Hand-written here; `derives Routable` in the mockup is what this should become.
    */
  def showRoute(r: Route): String = r match {
    case Route.Big       => "big"
    case Route.Items     => "items"
    case Route.Detail(i) => s"detail/$i"
    case Route.About     => "about"
  }

  def parseRoute(s: String): Option[Route] = s match {
    case "items" => Some(Route.Items)
    case "big"   => Some(Route.Big)
    case "about" => Some(Route.About)
    case other   =>
      other.split("/") match {
        case Array("detail", id) => id.toIntOption.map(Route.Detail(_))
        case _                   => None
      }
  }

  final class Model {
    /** The "add item" form's live state. */
    val draft: Var[String]     = Var("")
    val draftDone: Var[Boolean] = Var(false)
    val draftValid: Signal[Boolean] = draft.map(_.trim.nonEmpty)

    /** Drives the settings-row `Toggle`. */
    val hideDone: Var[Boolean] = Var(false)

    /** Drives the `Spinner`, which has no prop of its own: it spins while it is mounted. */
    val busy: Var[Boolean] = Var(false)

    /** Drives the confirmation `Alert`. Presented while true; there is no `show()`. */
    val confirmingDrop: Var[Boolean] = Var(false)

    /** Set by the alert's actions, so a test can tell which branch ran. */
    val lastAlertChoice: Var[String] = Var("")

    /** A slider in the app's own units — 0 to 11, not 0.0 to 1.0. */
    val volume: Var[Double] = Var(7.0)

    /** A `SecureField`'s value, to show it round-trips like any other bound field. */
    val secret: Var[String] = Var("")

    val items: Var[Seq[Item]] = Var(
      Seq(
        Item(1, "Structural reconciliation", true),
        Item(2, "Android renderer", true),
        Item(3, "Navigation", false)
      )
    )
    private var nextId = 4

    def add(): Unit = {
      items.update(_ :+ Item(nextId, s"New item $nextId", false))
      nextId += 1
    }

    /** Commits the form, if it is valid, and clears it. */
    def addDraft(): Unit =
      if draftValid.now then {
        items.update(_ :+ Item(nextId, draft.now.trim, draftDone.now))
        nextId += 1
        draft.set("")
        draftDone.set(false)
      }

    def rotate(): Unit = items.update {
      case head +: rest => rest :+ head
      case empty        => empty
    }

    def toggle(id: Int): Unit =
      items.update(_.map(i => if i.id == id then i.copy(done = !i.done) else i))

    def dropLast(): Unit = items.update(_.dropRight(1))

    def itemSignal(id: Int): Signal[Option[Item]] = items.map(_.find(_.id == id))

    /** What the list actually shows. A derived view, so flicking the switch re-renders the
      * list and nothing else — the caption, the progress bar and the form are untouched.
      */
    val visibleItems: Signal[Seq[Item]] =
      items.zip(hideDone).map((xs, hide) => if hide then xs.filterNot(_.done) else xs)
  }

  def bullet(i: Item): String =
    (if i.done then "✓" else "•") + "  " + i.title

  /** The app: a `Nav` plus a total function from `Route` to `Screen`. */
  def apply(model: Model): NavHost[Route] = {
    val nav = Nav[Route](Route.Items)

    NavHost(nav) {
      case Route.Items     => itemsScreen(model, nav)
      case Route.Detail(i) => detailScreen(model, nav, i)
      case Route.About     => aboutScreen()
      case Route.Big       => bigScreen(nav)
    }
  }

  private def itemsScreen(model: Model, nav: Nav[Route]): Screen =
    Screen(
      title = "Todo",
      // Toolbar actions: a GTK header-bar button, an Android action-bar item. They are
      // never in the element tree — that is what makes each platform put them where its
      // users expect rather than where this file happens to list them.
      actions = Seq(
        Action("Add")(model.add()),
        Action("About")(nav.push(Route.About))
      ),
      // No title label in the content: `Screen.title` already drives the platform's own
      // chrome (action bar, header bar), and repeating it is how cross-platform apps end
      // up looking like neither platform.
      content = Scroll()(Column(spacing = 16, padding = 16)(
        // A small form: a bound text field, a bound checkbox, and a button whose enabled
        // state is derived from the model rather than remembered.
        Row(spacing = 8)(
          TextField(model.draft, placeholder = "What needs doing?")(model.draft.set),
          Checkbox(model.draftDone, "Done")(model.draftDone.set)
        ),
        Button("Add item")(model.addDraft()),

        // A real list row: a tappable *container*, not a button pretending to be one.
        // The title grows to fill the row and the status sits at the trailing edge.
        Column(spacing = 0)(
          ForEach(model.visibleItems, key = (i: Item) => i.id) { item =>
            Fragment(
              Row(spacing = 12, padding = 12)(
                Label(item.map(_.title)).grow,
                Label(
                  item.map(i => if i.done then "\u2713" else ""),
                  style = TextRole.Caption,
                  align = Alignment.End,
                  emphasis = Emphasis.Secondary
                )
              ).onTap(nav.push(Route.Detail(item.now.id))),
              Divider()
            )
          }
        ),
        Show(model.items.map(_.isEmpty))(Label("Nothing left to do.")),
        // Five buttons do not fit one line on a phone, and a `Row` on its own clips
        // them: there is no wrapping and no ellipsis. A horizontal `Scroll` is the
        // framework's answer — every toolkit here has a scroller, only some have a
        // wrapping box — and it costs the app one word.
        Scroll(axis = Orientation.Horizontal)(
          Row(spacing = 8)(
            Button("Add")(model.add()),
            Button("Rotate")(model.rotate()),
            // Goes through the confirmation alert rather than dropping outright.
            Button("Drop")(model.confirmingDrop.set(true)),
            Button("About")(nav.push(Route.About)),
            Button("10 000 rows")(nav.push(Route.Big))
          )
        ),
        Label(
          model.items.map(xs => s"${xs.count(_.done)} of ${xs.size} done"),
          style = TextRole.Caption,
          emphasis = Emphasis.Secondary
        ),

        Divider(),

        // A settings row, which is the shape a switch takes on every platform here: the
        // caption is a sibling, not a property of the control, because a GtkSwitch and a
        // UISwitch have nowhere to put one. `Spacer` is what pushes them apart.
        Row(spacing = 8)(
          Label("Hide completed"),
          Spacer(),
          Toggle(model.hideDone)(model.hideDone.set)
        ),

        // Determinate progress driven by the model rather than by a timer: the bar is a
        // view of the data, exactly as the caption above it is.
        ProgressBar(model.items.map { xs =>
          if xs.isEmpty then None else Some(xs.count(_.done).toDouble / xs.size)
        }),

        // The spinner has no "running" prop. `Show` is what starts and stops it.
        Show(model.busy)(Spinner()),

        // A slider in the app's own units: 0 to 11, never a fraction. The renderer whose
        // control is integral underneath does that conversion, because only it knows its
        // own resolution.
        Row(spacing = 8)(
          Label("Volume"),
          Spacer(),
          Label(model.volume.map(v => f"$v%.1f"), style = TextRole.Caption)
        ),
        Slider(model.volume, min = 0, max = 11)(model.volume.set),

        // Same shape as TextField; a separate widget only because NSSecureTextField is a
        // separate class.
        SecureField(model.secret, placeholder = "Passphrase")(model.secret.set),

        // A modal alert, presented by a signal rather than by a call. Flipping
        // `confirmingDrop` off is the whole of dismissing it.
        Show(model.confirmingDrop) {
          Alert("Drop the last item?", "This cannot be undone.")(
            AlertAction("Drop", destructive = true) {
              model.dropLast()
              model.lastAlertChoice.set("drop")
              model.confirmingDrop.set(false)
            },
            AlertAction("Cancel", cancel = true) {
              model.lastAlertChoice.set("cancel")
              model.confirmingDrop.set(false)
            }
          ) {
            // A platform dismissal - back gesture, tap outside - does not unmount the
            // alert, so the app has to take it down itself. Without this the signal stays
            // true, the native dialog is already gone, and `Drop` can never reopen it.
            model.lastAlertChoice.set("dismissed")
            model.confirmingDrop.set(false)
          }
        }
      ))
    )

  private def detailScreen(model: Model, nav: Nav[Route], id: Int): Screen = {
    val item = model.itemSignal(id)
    Screen(
      title = "Item",
      content = Column(spacing = 12, padding = 16)(
        Label(item.map(_.fold("(deleted)")(_.title)), style = TextRole.Title),
        Label(
          item.map(_.fold("")(i => if i.done then "Done" else "Not done")),
          style = TextRole.Caption,
          emphasis = Emphasis.Secondary
        ),
        Row(spacing = 8)(
          Button("Toggle")(model.toggle(id)),
          Button("Back")(nav.pop())
        )
      )
    )
  }

  /** 10 000 rows through `LazyColumn`. On Android that is a `ListView`, so only the rows on
    * screen exist; on a renderer without a virtualising container every row is mounted and
    * the screen still works, just heavier.
    */
  private def bigScreen(nav: Nav[Route]): Screen = {
    val many = Signal.const((1 to 10000).map(i => Item(i, s"Row $i", i % 7 == 0)))
    Screen(
      title = "10 000 rows",
      content = Column(spacing = 8, padding = 8)(
        Label("LazyColumn: only the visible rows exist", emphasis = Emphasis.Secondary),
        LazyColumn(many, key = (i: Item) => i.id) { item =>
          Row(spacing = 12, padding = 12)(
            Label(item.map(_.title)).grow,
            Label(
              item.map(i => if i.done then "\u2713" else ""),
              style = TextRole.Caption,
              align = Alignment.End,
              emphasis = Emphasis.Secondary
            )
          )
        }.grow,
        Button("Back")(nav.pop())
      )
    )
  }

  private def aboutScreen(): Screen =
    Screen(
      title = "About",
      content = Column(spacing = 12, padding = 16)(
        Label("Thicket", style = TextRole.Title),
        Label("One element tree, rendered by GTK4 on Linux and android.view on Android.")
      )
    )
}
