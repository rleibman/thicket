package example

import scalaui.core.*
import scalaui.core.dsl.*
import scalaui.signals.{Signal, Var}

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
  }

  /** A route is a value, so persisting the back stack is ordinary serialisation.
    *
    * Hand-written here; `derives Routable` in the mockup is what this should become.
    */
  def showRoute(r: Route): String = r match {
    case Route.Items     => "items"
    case Route.Detail(i) => s"detail/$i"
    case Route.About     => "about"
  }

  def parseRoute(s: String): Option[Route] = s match {
    case "items" => Some(Route.Items)
    case "about" => Some(Route.About)
    case other   =>
      other.split("/") match {
        case Array("detail", id) => id.toIntOption.map(Route.Detail(_))
        case _                   => None
      }
  }

  final class Model {
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

    def rotate(): Unit = items.update {
      case head +: rest => rest :+ head
      case empty        => empty
    }

    def toggle(id: Int): Unit =
      items.update(_.map(i => if i.id == id then i.copy(done = !i.done) else i))

    def dropLast(): Unit = items.update(_.dropRight(1))

    def itemSignal(id: Int): Signal[Option[Item]] = items.map(_.find(_.id == id))
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
    }
  }

  private def itemsScreen(model: Model, nav: Nav[Route]): Screen =
    Screen(
      title = "Todo",
      content = Column(spacing = 12, padding = 20)(
        Column(spacing = 4)(
          ForEach(model.items, key = (i: Item) => i.id) { item =>
            // Tapping a row navigates; the route carries the id, so the detail screen
            // cannot be built without one.
            Button(item.map(bullet))(nav.push(Route.Detail(item.now.id)))
          }
        ),
        Show(model.items.map(_.isEmpty))(Label("Nothing left to do.")),
        Row(spacing = 8)(
          Button("Add")(model.add()),
          Button("Rotate")(model.rotate()),
          Button("Drop")(model.dropLast()),
          Button("About")(nav.push(Route.About))
        ),
        Label(model.items.map(xs => s"${xs.count(_.done)} of ${xs.size} done"))
      )
    )

  private def detailScreen(model: Model, nav: Nav[Route], id: Int): Screen = {
    val item = model.itemSignal(id)
    Screen(
      title = "Item",
      content = Column(spacing = 12, padding = 20)(
        Label(item.map(_.fold("(deleted)")(_.title))),
        Label(item.map(_.fold("")(i => if i.done then "Done" else "Not done"))),
        Row(spacing = 8)(
          Button("Toggle")(model.toggle(id)),
          Button("Back")(nav.pop())
        )
      )
    )
  }

  private def aboutScreen(): Screen =
    Screen(
      title = "About",
      content = Column(spacing = 12, padding = 20)(
        Label("scala-ui"),
        Label("One element tree, rendered by GTK4 on Linux and android.view on Android.")
      )
    )
}
