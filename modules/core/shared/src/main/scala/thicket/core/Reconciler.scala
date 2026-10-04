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

package thicket.core

import scala.collection.mutable
import thicket.renderer.{Renderer, RowSource}
import thicket.signals.{Owner, Signal, Var}

/** Mounts an [[Element]] tree onto a [[Renderer]] and keeps it in step with its signals.
  *
  * The model is a tree of *slots*. A slot is one position in a parent's child list, and it contributes zero or more
  * handles to that parent. Static widgets contribute exactly one; dynamic regions ([[Element.Show]],
  * [[Element.ForEach]]) contribute a number that changes over time, which is the whole difficulty.
  *
  * Two consequences drive the design:
  *
  *   - A region must know *where* to insert, and the contract only offers "after this sibling". So a slot can be asked
  *     for its last handle, and a region finds its anchor by walking backwards over its preceding siblings until one is
  *     non-empty. Empty regions are therefore transparent, which is what lets two adjacent `Show`s work.
  *   - Re-rendering a region must not make the region's effect depend on everything its body reads. The body is mounted
  *     inside `Signal.untracked`, so only the region's own signal re-triggers it; effects created *inside* the body
  *     still track normally and are owned by a child `Owner` that is disposed when that content goes away.
  */
object Reconciler {

  def mount[H](
    renderer:    Renderer { type Handle = H },
    element:     Element
  )(using owner: Owner
  ): Mounted[H] = {
    val slot = Slot.build(renderer, element, parent = None, anchor = () => None)
    Mounted(slot)
  }

}

/** A mounted tree. Dispose it to destroy the widgets; dispose the `Owner` that mounted it to stop the effects.
  * (`dispose` does both for anything the tree created itself.)
  */
final class Mounted[H] private[core] (private[core] val slot: Slot[H]) {

  /** The root handle, when the tree has exactly one. A `Fragment` root has none. */
  def handle: H =
    slot.ownedHandles.headOption.getOrElse(
      throw new IllegalStateException("this tree has no root widget (a Fragment root?)")
    )

  def handles:   Vector[H] = slot.handles
  def dispose(): Unit = slot.dispose()

}

// ---------------------------------------------------------------------------------------

sealed private[core] trait Slot[H] {

  /** The handles this slot contributes to its parent *for positioning*, in order.
    *
    * A presented slot contributes none: its widget was never attached, so using it as an anchor asks the toolkit to
    * position against something that is not a child. On Android `ViewGroup.indexOfChild` answers -1 and the next
    * sibling silently jumps to the front; on GTK `gtk_box_insert_child_after` emits a critical. Neither fails loudly,
    * which is exactly why this has to be empty rather than merely skipped at the call sites.
    */
  def handles: Vector[H]

  /** Every handle this slot owns, positioned or not.
    *
    * Only [[Mounted.handle]] wants this: a host mounting a single `Alert` still needs its handle back, even though that
    * handle anchors nothing.
    */
  def ownedHandles: Vector[H] = handles

  /** The last handle this slot contributes, if any — the anchor a following sibling uses. */
  def lastHandle: Option[H] = handles.lastOption

  def dispose(): Unit

}

private[core] object Slot {

  /** `anchor` is evaluated lazily: a region's insertion point depends on how many handles its preceding siblings are
    * contributing *right now*, which changes over time.
    */
  def build[H](
    renderer:    Renderer { type Handle = H },
    element:     Element,
    parent:      Option[H],
    anchor:      () => Option[H]
  )(using owner: Owner
  ): Slot[H] =
    element match {
      case w: Element.Widget        => widget(renderer, w, parent, anchor)
      case s: Element.Show          => region(renderer, parent, anchor, ShowSource(s))
      case f: Element.ForEach[?, ?] => region(renderer, parent, anchor, ForEachSource(f))
      case Element.Fragment(kids) => fragment(renderer, kids, parent, anchor)

      // Contributes no node: it only changes which theme is in scope while its child is
      // built, because roles resolve to colours at build time.
      case Element.Provide(theme, child) =>
        Theme.withActive(theme)(build(renderer, child, parent, anchor))
      case l: Element.LazyColumn[?, ?] =>
        if renderer.supportsVirtualRows then virtualList(renderer, l, parent, anchor)
        else
          // Correct, just heavier: every row is mounted. Wrapped in a container so that a
          // `LazyColumn` is one widget on every renderer — otherwise the same element would
          // produce a different tree shape depending on who is drawing it.
          build(
            renderer,
            Element.Widget(
              thicket.renderer.WidgetKind.Column,
              Nil,
              Seq(Element.ForEach(l.items, l.key, l.body))
            ),
            parent,
            anchor
          )
    }

  private def widget[H](
    renderer:    Renderer { type Handle = H },
    w:           Element.Widget,
    parent:      Option[H],
    anchor:      () => Option[H]
  )(using owner: Owner
  ): Slot[H] = {
    val statics = w.attrs.collect { case Attr.Static(p) => p }
    val handle = renderer.create(w.kind, statics)

    w.attrs.foreach {
      case r: Attr.Reactive[?] =>
        // `Signal.map` is a stateless view and does not memoise, so a coarse source can
        // re-fire an effect whose derived value is unchanged. Deduplicating here puts the
        // cutoff exactly where it pays — at the renderer boundary — without asking app
        // code to carry an `Owner` for `Signal.computed`.
        var last: Option[thicket.renderer.Prop] = None
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
    // A presented kind is shown over the app rather than attached to a parent — see
    // WidgetKind.presented. Mounting is what presents it, so `Show(flag)(Alert(...))` is
    // the whole of opening and closing one.
    if w.kind.presented then renderer.present(handle)
    else parent.foreach(p => renderer.insertAfter(p, handle, anchor()))
    WidgetSlot(renderer, handle, children, presented = w.kind.presented)
  }

  private def fragment[H](
    renderer: Renderer { type Handle = H },
    kids:     Seq[Element],
    parent:   Option[H],
    anchor:   () => Option[H]
  )(using Owner
  ): Slot[H] =
    parent match {
      case None =>
        // A fragment at the root has nothing to attach to.
        FragmentSlot(Vector.empty)
      case Some(p) =>
        val slots = mutable.ArrayBuffer.empty[Slot[H]]
        kids.foreach { kid =>
          val here = slots.toVector
          val a: () => Option[H] = () =>
            here.reverseIterator
              .map(_.lastHandle).collectFirst { case Some(h) => h }
              .orElse(anchor())
          slots += build(renderer, kid, Some(p), a)
        }
        FragmentSlot(slots.toVector)
    }

  /** Mounts a widget's children, giving each the anchor of its preceding siblings. */
  private def mountChildren[H](
    renderer: Renderer { type Handle = H },
    kids:     Seq[Element],
    parent:   H
  )(using Owner
  ): Vector[Slot[H]] = {
    val slots = mutable.ArrayBuffer.empty[Slot[H]]
    kids.foreach { kid =>
      val preceding = slots.toVector
      val anchor: () => Option[H] = () => preceding.reverseIterator.map(_.lastHandle).collectFirst { case Some(h) => h }
      slots += build(renderer, kid, Some(parent), anchor)
    }
    slots.toVector
  }

  /** Back a virtualising container with rows mounted on demand. */
  private def virtualList[H, A, K](
    renderer:    Renderer { type Handle = H },
    spec:        Element.LazyColumn[A, K],
    parent:      Option[H],
    anchor:      () => Option[H]
  )(using owner: Owner
  ): Slot[H] = {
    val source = new VirtualRows[H, A, K](renderer, spec, owner)
    val handle = renderer.createVirtualList(source)
    parent.foreach(p => renderer.insertAfter(p, handle, anchor()))
    // One effect for the whole list: the renderer is told the data changed and re-pulls
    // whatever it is showing. Individual rows still update through their own signals.
    val _ = Signal.effect {
      val n = spec.items().length
      source.setCount(n)
      source.invalidate()
    }
    VirtualListSlot(renderer, handle, source)
  }

  private def region[H](
    renderer:    Renderer { type Handle = H },
    parent:      Option[H],
    anchor:      () => Option[H],
    source:      RegionSource
  )(using owner: Owner
  ): Slot[H] =
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

final private class WidgetSlot[H](
  renderer:     Renderer { type Handle = H },
  val handle:   H,
  val children: Vector[Slot[H]],
  presented:    Boolean = false
) extends Slot[H] {

  // Empty when presented: see Slot.handles. The widget exists and is owned, but it is not
  // in the parent's child list, so it must not act as an anchor for what follows it.
  def handles:               Vector[H] = if presented then Vector.empty else Vector(handle)
  override def ownedHandles: Vector[H] = Vector(handle)

  def dispose(): Unit = {
    children.foreach(_.dispose())
    // A presented widget is dismissed before it is destroyed. `destroy` detaches, and a
    // presented widget was never attached — so without this the alert would be freed while
    // still on screen.
    if presented then renderer.dismiss(handle)
    renderer.destroy(handle)
  }

}

/** A virtualising container. Its rows are owned by the [[VirtualRows]] source, not by the slot tree, because the
  * renderer decides which of them exist.
  */
final private class VirtualListSlot[H](
  renderer:   Renderer { type Handle = H },
  val handle: H,
  source:     VirtualRows[H, ?, ?]
) extends Slot[H] {

  def handles: Vector[H] = Vector(handle)
  def dispose(): Unit = {
    source.disposeAll()
    renderer.destroy(handle)
  }

}

/** Mounts rows on demand for a virtualising container, and re-binds recycled ones by writing the new item into the
  * row's own signal — so a scroll costs one property update per changed field rather than a rebuild.
  */
final private class VirtualRows[H, A, K](
  renderer: Renderer { type Handle = H },
  spec:     Element.LazyColumn[A, K],
  owner:    Owner
) extends RowSource[H] {

  final private class Row(
    val value:    Var[A],
    val rowOwner: Owner,
    val slot:     Slot[H]
  )

  private val rows = scala.collection.mutable.Map.empty[H, Row]
  private var listeners = () => ()
  private var total = 0
  private var disposed = false

  // After the container is gone the source reports an empty list and stops notifying, so a
  // late data change cannot mount rows into a destroyed container. The list's *effect* is
  // owned by the enclosing `Owner`, which may outlive the slot — disposing the widgets and
  // disposing the effects are separate acts.
  def count:                                 Int = if disposed then 0 else total
  private[core] def setCount(n: Int):        Unit = total = n
  def onInvalidate(callback:    () => Unit): Unit = listeners = callback
  private[core] def invalidate():            Unit = if !disposed then listeners()

  def bind(
    index:    Int,
    recycled: Option[H]
  ): H = {
    if disposed then throw new IllegalStateException("bind on a disposed LazyColumn")
    val items = Signal.untracked(spec.items())
    if index < 0 || index >= items.length then throw new IndexOutOfBoundsException(s"row $index of ${items.length}")
    val item = items(index)

    recycled.flatMap(h => rows.get(h).map(h -> _)) match {
      case Some((handle, row)) =>
        row.value.set(item) // the row's own signals do the rest
        handle
      case None =>
        val rowOwner = Owner.child(using owner)
        val v = Var(item)
        val slot = Signal.untracked {
          given Owner = rowOwner
          // No parent: the container places the row itself.
          Slot.build(renderer, spec.body(v), None, () => None)
        }
        val handle = slot.handles.headOption.getOrElse(
          throw new IllegalArgumentException(
            "a LazyColumn row must have a single root widget that the container can place " +
              "(a Fragment has none, and a presented widget such as Alert places itself)"
          )
        )
        rows(handle) = new Row(v, rowOwner, slot)
        handle
    }
  }

  def discard(handle: H): Unit =
    rows.remove(handle).foreach { row =>
      row.slot.dispose()
      row.rowOwner.dispose()
    }

  private[core] def disposeAll(): Unit = {
    disposed = true
    rows.values.foreach { row =>
      row.slot.dispose()
      row.rowOwner.dispose()
    }
    rows.clear()
  }

}

final private class FragmentSlot[H](val slots: Vector[Slot[H]]) extends Slot[H] {

  def handles:               Vector[H] = slots.flatMap(_.handles)
  override def ownedHandles: Vector[H] = slots.flatMap(_.ownedHandles)
  def dispose():             Unit = slots.foreach(_.dispose())

}

/** What a dynamic region contributes, erased to `Any` so one `RegionSlot` serves both `Show` and `ForEach`. The two
  * sources below do the casting.
  */
private[core] trait RegionSource {

  /** The current keys and values. Read *tracked*: this is what re-triggers the region. */
  def current()(using thicket.signals.Tracking): Vector[(Any, Any)]

  /** Build the content for one entry, given a signal of that entry's value. */
  def build(value: Signal[Any]): Element

}

final private[core] class ShowSource(s: Element.Show) extends RegionSource {

  def current()(using thicket.signals.Tracking): Vector[(Any, Any)] =
    if s.when() then Vector((true, ())) else Vector.empty
  def build(value: Signal[Any]): Element = s.body()

}

final private[core] class ForEachSource[A, K](f: Element.ForEach[A, K]) extends RegionSource {

  def current()(using thicket.signals.Tracking): Vector[(Any, Any)] =
    f.items().iterator.map(a => (f.key(a).asInstanceOf[Any], a.asInstanceOf[Any])).toVector
  def build(value: Signal[Any]): Element = f.body(value.asInstanceOf[Signal[A]])

}

/** One entry currently mounted inside a region. `value` is a `Var` so that an item which keeps its key but changes its
  * data updates in place instead of being rebuilt.
  */
final private class RegionEntry[H](
  val key:   Any,
  val value: Var[Any],
  val owner: Owner,
  val slot:  Slot[H],
  var after: Option[H]
)

/** The mutable heart of structural reconciliation. */
final private class RegionSlot[H](
  renderer: Renderer { type Handle = H },
  parent:   H,
  anchor:   () => Option[H],
  source:   RegionSource
) extends Slot[H] {

  /** The theme in scope where this region was declared.
    *
    * Captured once, at construction, because that is the only moment a `Provide` enclosing this region is still on the
    * stack. Every later rebuild — a `Show` flipping, a row appended an hour from now — restores it, so a themed list
    * stays themed.
    */
  private val declaredTheme: Theme = Theme.active

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

  /** Mount one entry at `at`.
    *
    * Untracked: the body's own signals belong to the body's effects, not to the region's, or every region would re-run
    * for any change anywhere beneath it.
    */
  private def mountEntry(
    key:   Any,
    value: Any,
    at:    Option[H]
  )(using
    parentOwner: Owner
  ): RegionEntry[H] = {
    val childOwner = Owner.child(using parentOwner)
    val v = Var[Any](value)
    val slot = Signal.untracked {
      given Owner = childOwner
      // `declaredTheme`, not `Theme.active`: a region rebuilds its entries whenever its
      // signal fires, which is long after the `Provide` that enclosed it has returned. A
      // row added to a themed list an hour later must still be themed.
      Theme.withActive(declaredTheme)(Slot.build(renderer, source.build(v), Some(parent), () => at))
    }
    RegionEntry(key, v, childOwner, slot, after = at)
  }

  /** Diff the current entries against `next`, reusing anything whose key survived.
    *
    * Position is assigned in the same pass that mounts, so a new entry is inserted once, at its final place — inserting
    * during `Slot.build` and again here would duplicate it.
    */
  def reconcile(next: Vector[(Any, Any)])(using parentOwner: Owner): Unit = {
    // Fast path: the same keys in the same order, which is what every data-only change
    // looks like — an item edited, a checkbox toggled, a field typed into. The general
    // path below builds a key map, a key set, a filtered list and a fresh vector, which
    // for a 10 000-row list costs ~19 ms: a dropped frame on every keystroke. Here there
    // is nothing to allocate and nothing to move; `Var.set` no-ops on an unchanged value,
    // so untouched rows cost one comparison each.
    if next.length == entries.length then {
      var i = 0
      var same = true
      while (i < entries.length && same) {
        if entries(i).key != next(i)._1 then same = false
        i += 1
      }
      if same then {
        i = 0
        while (i < entries.length) {
          entries(i).value.set(next(i)._2)
          i += 1
        }
        return
      }
    }

    // Fast path 2: everything currently mounted is a prefix of what is wanted — an append.
    // Chat messages, log lines, an infinite scroll. The general path below is O(existing)
    // with a large constant; this is O(added).
    if next.length > entries.length then {
      var i = 0
      var same = true
      while (i < entries.length && same) {
        if entries(i).key != next(i)._1 then same = false
        i += 1
      }
      if same then {
        i = 0
        while (i < entries.length) {
          entries(i).value.set(next(i)._2)
          i += 1
        }
        var previous = entries.lastOption.flatMap(_.slot.lastHandle).orElse(anchor())
        val added = Vector.newBuilder[RegionEntry[H]]
        i = entries.length
        while (i < next.length) {
          val e = mountEntry(next(i)._1, next(i)._2, previous)
          e.slot.lastHandle.foreach(h => previous = Some(h))
          added += e
          i += 1
        }
        entries = entries ++ added.result()
        return
      }
    }

    val byKey = entries.iterator.map(e => e.key -> e).toMap
    val wanted = next.iterator.map(_._1).toSet

    // Drop what is gone first, so removals do not disturb the positions computed below.
    entries.filterNot(e => wanted.contains(e.key)).foreach(disposeEntry)

    var previous = anchor()

    val rebuilt = next.map {
      (
        key,
        value
      ) =>
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

          case None => mountEntry(key, value, previous)
        }

        entry.slot.lastHandle.foreach(h => previous = Some(h))
        entry

    }
    entries = rebuilt
  }

}
