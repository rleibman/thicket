package thicket.core

import thicket.signals.Signal

/** What a platform host needs from an app, beyond the element tree.
  *
  * Titles, back affordances and toolbar actions are *chrome*: a GTK header bar, an Android action bar, a
  * `UINavigationItem`. Putting them in the element tree would mean the reconciler drawing an imitation of each
  * platform's bar, which is exactly the trade this project exists to avoid. So they travel beside the tree and each
  * host applies them natively.
  */
trait AppRoot {

  def element:   Element
  def title:     Signal[String]
  def canGoBack: Signal[Boolean]
  def actions:   Signal[Seq[Action]]

  /** Handle a platform back gesture — Android's Back button, a header-bar arrow, an iOS swipe. Returns false when there
    * is nowhere to go, which tells the host to defer to the platform (finish the activity, close the window).
    */
  def back(): Boolean

}

object AppRoot {

  /** A single-screen app, with no navigation. */
  def apply(
    title0:  String,
    content: Element
  ): AppRoot =
    new AppRoot {
      val element = content
      val title:     Signal[String] = Signal.const(title0)
      val canGoBack: Signal[Boolean] = Signal.const(false)
      val actions:   Signal[Seq[Action]] = Signal.const(Nil)
      def back():    Boolean = false
    }

}
