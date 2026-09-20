package scalaui.core

import scalaui.renderer.{Prop, Renderer}
import scalaui.signals.{Owner, Signal}

/** Mounts an [[Element]] tree onto a [[Renderer]], wiring reactive attributes to the
  * widgets they describe.
  *
  * v0 mounts once and keeps the structure fixed; only *properties* update, driven by
  * signals. Structural reconciliation (`Show`, `ForEach`) comes next and is why `Mounted`
  * keeps its children and handle rather than discarding them.
  */
final class Mounted[H](
    val handle: H,
    private val children: Seq[Mounted[H]]
):
  def dispose(renderer: Renderer { type Handle = H }): Unit =
    children.foreach(_.dispose(renderer))
    renderer.destroy(handle)

object Reconciler:

  /** Builds the widget tree described by `element` and returns its root.
    *
    * Reactive attributes create effects owned by `owner`, so unmounting is a matter of
    * disposing the owner — no weak references, no GC dependence (docs/07 §7.2).
    */
  def mount[H](
      renderer: Renderer { type Handle = H },
      element: Element
  )(using owner: Owner): Mounted[H] =
    val statics = element.attrs.collect { case Attr.Static(p) => p }
    val handle  = renderer.create(element.kind, statics)

    // Reactive attributes: one effect per attribute, patching just this widget.
    element.attrs.foreach:
      case r: Attr.Reactive[?] =>
        val _ = Signal.effect(renderer.update(handle, Seq(r.toProp(r.signal()))))
      case Attr.Static(_) => ()

    val mountedChildren = element.children.map(mount(renderer, _))

    // insertAfter rather than an index: see the contract's note on GTK.
    var previous: Option[H] = None
    mountedChildren.foreach: child =>
      renderer.insertAfter(handle, child.handle, previous)
      previous = Some(child.handle)

    Mounted(handle, mountedChildren)
