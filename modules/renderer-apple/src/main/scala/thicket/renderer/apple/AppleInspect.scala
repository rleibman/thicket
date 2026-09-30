package thicket.renderer.apple

import scala.scalanative.unsafe.*

/** Reads the view tree back out of AppKit.
  *
  * The unit tests prove the reconciler against an in-memory renderer, which by construction cannot catch a wrong AppKit
  * call. This is how the demo verifies that the renderer's calls produced the intended result in a real toolkit — the
  * same job `GtkInspect` does.
  */
object AppleInspect {

  def children(h: Shim.Handle): List[Shim.Handle] = {
    val n = Shim.sui_child_count(h)
    val out = scala.collection.mutable.ListBuffer.empty[Shim.Handle]
    var i = 0
    while i < n do {
      val c = Shim.sui_child_at(h, i)
      if c != null then out += c
      i += 1
    }
    out.toList
  }

  /** The text a single view carries, if any. */
  def text(h: Shim.Handle): Option[String] = {
    val p = Shim.sui_get_text(h)
    // The shim's buffer is valid only until the next call, so copy here and now.
    if p == null then None else Some(fromCString(p))
  }

  /** Every piece of text in the subtree, in tree order.
    *
    * Text-bearing views are checked *before* recursing: an `NSTextField` and an `NSButton` both have internal subviews,
    * and walking into them would report AppKit's private structure instead of the value.
    */
  def allTexts(h: Shim.Handle): List[String] =
    if Shim.sui_is_text_bearing(h) != 0 then text(h).toList
    else children(h).flatMap(allTexts)

  /** The platform class of a view — what AppKit or UIKit actually built, not what the renderer asked for. */
  def className(h: Shim.Handle): String = {
    val p = Shim.sui_class_name(h)
    if p == null then "" else fromCString(p)
  }

  /** Every view in the subtree, parent before children. */
  def all(h: Shim.Handle): List[Shim.Handle] = h :: children(h).flatMap(all)

  /** A determinate fraction 0.0-1.0; -1 when indeterminate; -2 when the view shows no progress. */
  def progress(h: Shim.Handle): Double = Shim.sui_get_progress(h)

  /** A slider's value, in the app's own units. */
  def value(h: Shim.Handle): Double = Shim.sui_get_value(h)

  /** Whether a field masks what is typed into it. */
  def isSecure(h: Shim.Handle): Boolean = Shim.sui_is_secure(h) != 0

  private def str(p: CString): Option[String] = if p == null then None else Some(fromCString(p))

  /** Whether an Alert or Sheet is on screen, as the platform reports it. */
  def isPresented(h: Shim.Handle): Boolean = Shim.sui_is_presented(h) != 0

  /** The title the platform is *showing* — the alert's text, the sheet's heading or navigation bar — not what the
    * renderer was told. `None` when it is not presented.
    */
  def presentedTitle(h: Shim.Handle): Option[String] = str(Shim.sui_presented_title(h))

  def presentedMessage(h: Shim.Handle): Option[String] = str(Shim.sui_presented_message(h))

  /** An alert's actions, in the order the platform holds them. */
  def alertActions(h: Shim.Handle): List[String] =
    (0 until Shim.sui_alert_action_count(h)).toList.flatMap(i => str(Shim.sui_alert_action_label(h, i)))

  /** Clicks a button through its own action, as a user would. False if `h` is not a button. */
  def click(h: Shim.Handle): Boolean = Shim.sui_perform_click(h) != 0

  /** Chooses an alert action through the platform's response path. False where the platform offers no way to. */
  def chooseAlert(
    h:     Shim.Handle,
    index: Int
  ): Boolean = Shim.sui_alert_choose(h, index) != 0

  /** How many things the platform has presented over the app right now — its answer, not the renderer's. */
  def presentedCount: Int = Shim.sui_presented_count()

}
