package scalaui.renderer.appkit

import scala.scalanative.unsafe.*

/** Reads the view tree back out of AppKit.
  *
  * The unit tests prove the reconciler against an in-memory renderer, which by construction cannot catch a wrong AppKit
  * call. This is how the demo verifies that the renderer's calls produced the intended result in a real toolkit — the
  * same job `GtkInspect` does.
  */
object AppKitInspect {

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

}
