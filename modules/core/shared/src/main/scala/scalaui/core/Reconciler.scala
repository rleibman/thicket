package scalaui.core

import scala.collection.mutable
import scalaui.renderer.Renderer
import scalaui.signals.{Owner, Signal, Var}

/** Mounts an [[Element]] tree onto a [[Renderer]] and keeps it in step with its signals.
  *
  * The model is a tree of *slots*. A slot is one position in a parent's child list, and it
  * contributes zero or more handles to that parent. Static widgets contribute exactly one;
  * dynamic regions ([[Element.Show]], [[Element.ForEach]]) contribute a number that changes
  * over time, which is the whole difficulty.
  *
  * Two consequences drive the design:
  *
  *   - A region must know *where* to insert, and the contract only offers "after this
  *     sibling". So a slot can be asked for its last handle, and a region finds its anchor
  *     by walking backwards over its preceding siblings until one is non-empty. Empty
  *     regions are therefore transparent, which is what lets two adjacent `Show`s work.
  *   - Re-rendering a region must not make the region's effect depend on everything its
  *     body reads. The body is mounted inside `Signal.untracked`, so only the region's own
  *     signal re-triggers it; effects created *inside* the body still track normally and
  *     are owned by a child `Owner` that is disposed when that content goes away.
  */
object Reconciler {

  def mount[H](
      renderer: Renderer { type Handle = H },
      element: Element
  )(using owner: Owner): Mounted[H] = {
    val slot = Slot.build(renderer, element, parent = None, anchor = () => None)
    Mounted(slot)
  }
}

/** A mounted tree. Dispose it to destroy the widgets; dispose the `Owner` that mounted it
  * to stop the effects. (`dispose` does both for anything the tree created itself.)
  */
final class Mounted[H] private[core] (private[core] val slot: Slot[H]) {
  /** The root handle, when the tree has exactly one. A `Fragment` root has none. */
  def handle: H = slot.handles.headOption.getOrElse(
    throw new IllegalStateException("this tree has no root widget (a Fragment root?)")
  )

  def handles: Vector[H] = slot.handles
  def dispose(): Unit    = slot.dispose()
}

// ---------------------------------------------------------------------------------------

private[core] sealed trait Slot[H] {
  /** The handles this slot currently contributes to its parent, in order. */
  def handles: Vector[H]

  /** The last handle this slot contributes, if any — the anchor a following sibling uses. */
  def lastHandle: Option[H] = handles.lastOption

  def dispose(): Unit
}

private[core] object Slot {

  /** `anchor` is evaluated lazily: a region's insertion point depends on how many handles
    * its preceding siblings are contributing *right now*, which changes over time.
    */
  def build[H](
      renderer: Renderer { type Handle = H },
      element: Element,
      parent: Option[H],
      anchor: () => Option[H]
  )(using owner: Owner): Slot[H] =
    element match {
      case w: Element.Widget         => widget(renderer, w, parent, anchor)
      case s: Element.Show           => region(renderer, parent, anchor, ShowSource(s))
      case f: Element.ForEach[?, ?]  => region(renderer, parent, anchor, ForEachSource(f))
      case Element.Fragment(kids)    => fragment(renderer, kids, parent, anchor)
    }

  private def widget[H](
      renderer: Renderer { type Handle = H },
      w: Element.Widget,
      parent: Option[H],
      anchor: () => Option[H]
  )(using owner: Owner): Slot[H] = {
    val statics = w.attrs.collect { case Attr.Static(p) => p }
    val handle  = renderer.create(w.kind, statics)

    w.attrs.foreach {
      case r: Attr.Reactive[?] =>
        // `Signal.map` is a stateless view and does not memoise, so a coarse source can
        // re-fire an effect whose derived value is unchanged. Deduplicating here puts the
        // cutoff exactly where it pays — at the renderer boundary — without asking app
        // code to carry an `Owner` for `Signal.computed`.
        var last: Option[scalaui.renderer.Prop] = None
        val _ = Signal.effect {
          val p = r.toProp(r.signal())
          if !last.contains(p) then {
            last = Some(p)
            renderer.update(handle, Seq(p))
          }
        }
      case Attr.Static(_) => ()
    }

    val children = mountChildren(renderer, w.children, handle)
    parent.foreach(p => renderer.insertAfter(p, handle, anchor()))
    WidgetSlot(renderer, handle, children)
  }

  private def fragment[H](
      renderer: Renderer { type Handle = H },
      kids: Seq[Element],
      parent: Option[H],
      anchor: () => Option[H]
  )(using Owner): Slot[H] =
    parent match {
      case None =>
        // A fragment at the root has nothing to attach to.
        FragmentSlot(Vector.empty)
      case Some(p) =>
        val slots = mutable.ArrayBuffer.empty[Slot[H]]
        kids.foreach { kid =>
          val here = slots.toVector
          val a: () => Option[H] = () =>
            here.reverseIterator.map(_.lastHandle).collectFirst { case Some(h) => h }
              .orElse(anchor())
          slots += build(renderer, kid, Some(p), a)
        }
        FragmentSlot(slots.toVector)
    }

  /** Mounts a widget's children, giving each the anchor of its preceding siblings. */
  private def mountChildren[H](
      renderer: Renderer { type Handle = H },
      kids: Seq[Element],
      parent: H
  )(using Owner): Vector[Slot[H]] = {
    val slots = mutable.ArrayBuffer.empty[Slot[H]]
    kids.foreach { kid =>
      val preceding = slots.toVector
      val anchor: () => Option[H] = () =>
        preceding.reverseIterator.map(_.lastHandle).collectFirst { case Some(h) => h }
      slots += build(renderer, kid, Some(parent), anchor)
    }
    slots.toVector
  }

  private def region[H](
      renderer: Renderer { type Handle = H },
      parent: Option[H],
      anchor: () => Option[H],
      source: RegionSource
  )(using owner: Owner): Slot[H] =
    parent match {
      case None =>
        throw new IllegalArgumentException(
          "a dynamic region (Show/ForEach) needs a parent widget; wrap it in a Column or Row"
        )
      case Some(p) =>
        val slot = RegionSlot[H](renderer, p, anchor, source)
        // Only `source.current()` is read tracked; the mounting inside `reconcile` is
        // untracked, so the body's own signals do not re-trigger this effect.
        val _ = Signal.effect(slot.reconcile(source.current()))
        slot
    }
}

// ---------------------------------------------------------------------------------------

private final class WidgetSlot[H](
    renderer: Renderer { type Handle = H },
    val handle: H,
    val children: Vector[Slot[H]]
) extends Slot[H] {
  def handles: Vector[H] = Vector(handle)
  def dispose(): Unit = {
    children.foreach(_.dispose())
    renderer.destroy(handle)
  }
}

private final class FragmentSlot[H](val slots: Vector[Slot[H]]) extends Slot[H] {
  def handles: Vector[H] = slots.flatMap(_.handles)
  def dispose(): Unit    = slots.foreach(_.dispose())
}

/** What a dynamic region contributes, erased to `Any` so one `RegionSlot` serves both
  * `Show` and `ForEach`. The two sources below do the casting.
  */
private[core] trait RegionSource {
  /** The current keys and values. Read *tracked*: this is what re-triggers the region. */
  def current()(using scalaui.signals.Tracking): Vector[(Any, Any)]

  /** Build the content for one entry, given a signal of that entry's value. */
  def build(value: Signal[Any]): Element
}

private[core] final class ShowSource(s: Element.Show) extends RegionSource {
  def current()(using scalaui.signals.Tracking): Vector[(Any, Any)] =
    if s.when() then Vector((true, ())) else Vector.empty
  def build(value: Signal[Any]): Element = s.body()
}

private[core] final class ForEachSource[A, K](f: Element.ForEach[A, K]) extends RegionSource {
  def current()(using scalaui.signals.Tracking): Vector[(Any, Any)] =
    f.items().iterator.map(a => (f.key(a).asInstanceOf[Any], a.asInstanceOf[Any])).toVector
  def build(value: Signal[Any]): Element =
    f.body(value.asInstanceOf[Signal[A]])
}

/** One entry currently mounted inside a region. `value` is a `Var` so that an item which
  * keeps its key but changes its data updates in place instead of being rebuilt.
  */
private final class RegionEntry[H](
    val key: Any,
    val value: Var[Any],
    val owner: Owner,
    val slot: Slot[H],
    var after: Option[H]
)

/** The mutable heart of structural reconciliation. */
private final class RegionSlot[H](
    renderer: Renderer { type Handle = H },
    parent: H,
    anchor: () => Option[H],
    source: RegionSource
) extends Slot[H] {

  private var entries: Vector[RegionEntry[H]] = Vector.empty

  def handles: Vector[H] = entries.flatMap(_.slot.handles)

  def dispose(): Unit = {
    entries.foreach(disposeEntry)
    entries = Vector.empty
  }

  private def disposeEntry(e: RegionEntry[H]): Unit = {
    // `Slot.dispose` destroys depth-first, and `Renderer.destroy` detaches. Removing the
    // entry's top handles here first would free their subtrees on toolkits where a
    // container owns its children, and the depth-first destroy would then touch freed
    // widgets — which is exactly what GTK reported.
    e.slot.dispose()
    e.owner.dispose() // stops every effect this entry's content created
  }

  /** Diff the current entries against `next`, reusing anything whose key survived.
    *
    * Position is assigned in the same pass that mounts, so a new entry is inserted once,
    * at its final place — inserting during `Slot.build` and again here would duplicate it.
    */
  def reconcile(next: Vector[(Any, Any)])(using parentOwner: Owner): Unit = {
    val byKey  = entries.iterator.map(e => e.key -> e).toMap
    val wanted = next.iterator.map(_._1).toSet

    // Drop what is gone first, so removals do not disturb the positions computed below.
    entries.filterNot(e => wanted.contains(e.key)).foreach(disposeEntry)

    var previous = anchor()

    val rebuilt = next.map { (key, value) =>
      val entry = byKey.get(key) match {
        case Some(existing) =>
          // Same key, possibly new data: push it through the Var and let the row's own
          // effects patch whatever depends on it.
          existing.value.set(value)
          val hs = existing.slot.handles
          if hs.nonEmpty && existing.after != previous then {
            var prev = previous
            hs.foreach { h =>
              renderer.moveAfter(parent, h, prev)
              prev = Some(h)
            }
          }
          existing.after = previous
          existing

        case None =>
          val childOwner = Owner.child(using parentOwner)
          val v          = Var[Any](value)
          val at         = previous
          // Untracked: the body's own signals belong to the body's effects, not to the
          // region's, or every region would re-run for any change anywhere beneath it.
          val slot = Signal.untracked {
            given Owner = childOwner
            Slot.build(renderer, source.build(v), Some(parent), () => at)
          }
          RegionEntry(key, v, childOwner, slot, after = at)
      }

      entry.slot.lastHandle.foreach(h => previous = Some(h))
      entry

    }
    entries = rebuilt
  }
}
