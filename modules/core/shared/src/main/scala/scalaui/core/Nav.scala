package scalaui.core

import scalaui.signals.{Signal, Var}

/** One entry on the back stack.
  *
  * The `id` matters: two pushes of the *same* route are two different screens, and popping
  * then pushing again must produce a fresh one. Keying navigation on the route alone would
  * silently reuse the old screen's widgets and state.
  */
final case class Entry[R](id: Long, route: R)

/** A screen: a titled element, optionally with toolbar actions.
  *
  * Title and actions are deliberately *not* part of the element tree. They are platform
  * chrome — a GTK header bar, an Android action bar, a UINavigationItem — so the host binds
  * them natively rather than the reconciler drawing an imitation.
  */
final case class Screen(
    title: String,
    content: Element,
    actions: Seq[Action] = Nil
)

final case class Action(label: String, onTap: () => Unit, enabled: Boolean = true)

/** The back stack, as a value.
  *
  * Navigation state is `List[R]` of a route ADT the app defines, which is what makes it
  * testable (assert on `routes.now`), restorable (serialise a list) and impossible to get
  * wrong (a route cannot be constructed without its arguments).
  */
final class Nav[R] private (home: R):
  // Head is the top of the stack. Never empty: the root cannot be popped.
  private val entries: Var[List[Entry[R]]] = Var(List(Entry(0L, home)))
  private var nextId: Long                 = 1L

  /** The whole stack, top first. */
  val stack: Signal[List[Entry[R]]] = entries

  /** Just the routes, top first — the shape to assert on and to persist. */
  val routes: Signal[List[R]] = entries.map(_.map(_.route))

  val current: Signal[R]           = entries.map(_.head.route)
  val depth: Signal[Int]           = entries.map(_.size)
  val canGoBack: Signal[Boolean]   = entries.map(_.sizeIs > 1)

  def push(route: R): Unit =
    val id = nextId
    nextId += 1
    entries.update(Entry(id, route) :: _)

  /** Pops the top screen. Returns false at the root, which is what a platform Back handler
    * needs in order to fall through to the system (finish the Activity, close the window).
    */
  def pop(): Boolean =
    entries.now match
      case _ :: rest if rest.nonEmpty =>
        entries.set(rest)
        true
      case _ => false

  /** Replaces the top screen without growing the stack. */
  def replace(route: R): Unit =
    val id = nextId
    nextId += 1
    entries.update(es => Entry(id, route) :: es.tail)

  /** Clears the stack down to a single screen. */
  def reset(route: R): Unit =
    val id = nextId
    nextId += 1
    entries.set(List(Entry(id, route)))

  /** Pops until `route` is on top, if it is on the stack at all. */
  def popTo(route: R): Boolean =
    val target = entries.now.dropWhile(_.route != route)
    if target.isEmpty then false
    else
      entries.set(target)
      true

  /** Restores a previously captured stack, e.g. after process death. */
  def restore(rs: List[R]): Unit =
    if rs.nonEmpty then
      val restored = rs.map: r =>
        val id = nextId
        nextId += 1
        Entry(id, r)
      entries.set(restored)

object Nav:
  def apply[R](home: R): Nav[R] = new Nav(home)
