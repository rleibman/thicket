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
import thicket.renderer.*

/** An in-memory renderer, so the reconciler can be tested without a toolkit.
  *
  * This doubles as the reference implementation of the contract: if something cannot be expressed here, the contract is
  * leaking platform assumptions.
  */
final class TestRenderer extends Renderer {

  final case class Node(
    id:                  Int,
    kind:                WidgetKind,
    props:               mutable.Map[String, String] = mutable.Map.empty,
    children:            mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty,
    var taps:            Int = 0,
    var onTap:           Option[() => Unit] = None,
    var onTextChange:    Option[String => Unit] = None,
    var onCheckedChange: Option[Boolean => Unit] = None,
    var onValueChange:   Option[Double => Unit] = None,
    var onDismiss:       Option[() => Unit] = None,
    var actions:         Seq[thicket.renderer.AlertAction] = Nil,
    var menu:            Seq[thicket.renderer.MenuItem] = Nil,
    // Numbers are kept as numbers, never stringified. Scala.js has no int/double
    // distinction, so `7.0.toString` is "7" there and "7.0" on the JVM — a test that
    // compares the rendered string passes on one backend and fails on the other.
    nums: mutable.Map[String, Double] = mutable.Map.empty
  )

  type Handle = Int

  val nodes: mutable.Map[Int, Node] = mutable.Map.empty
  private var nextId = 0
  var destroyed: List[Int] = Nil

  /** Counters so tests can assert that reconciliation did *no* work, not merely that it produced the right answer.
    * "Right output via rebuild-everything" is the failure mode keying exists to prevent.
    */
  var createCount: Int = 0
  var opCount:     Int = 0

  /** Writes that actually changed a widget. The contract says a renderer must not disturb a widget written a value it
    * already shows, so this stays flat when the app echoes a user's edit back through a signal.
    */
  var textWrites: Int = 0

  def platform: String = "test"

  // -- virtualisation -------------------------------------------------------
  //
  // Models a recycling container: a viewport of `windowSize` rows, and a small pool of
  // recycled handles. Scrolling is explicit so a test can assert exactly which rows exist.

  var virtualising:                 Boolean = false
  override def supportsVirtualRows: Boolean = virtualising

  private var rowSource: Option[RowSource[Int]] = None
  private var shown:     Map[Int, Int] = Map.empty // index -> handle
  private var spare:     List[Int] = Nil
  var windowSize:        Int = 5
  var bindCount:         Int = 0

  override def createVirtualList(source: RowSource[Int]): Handle = {
    val h = create(WidgetKind.Column, Nil)
    rowSource = Some(source)
    // `notifyDataSetChanged` makes the container ask for its visible rows again, so the
    // model here does the same: re-bind what is on screen, then re-window.
    source.onInvalidate { () =>
      rebindVisible()
      scrollTo(firstVisible)
    }
    h
  }

  private var firstVisible = 0

  /** Show the window starting at `from`, recycling whatever scrolls out of it. */
  def scrollTo(from: Int): Unit =
    rowSource.foreach { source =>
      firstVisible = from
      val wanted = (from until Math.min(from + windowSize, source.count)).toSet
      // Recycle rows that left the viewport.
      shown.filterNot { case (i, _) => wanted.contains(i) }.foreach { case (i, h) =>
        shown -= i
        spare = h :: spare
      }
      wanted.toList.sorted.foreach { i =>
        if !shown.contains(i) then {
          val recycled = spare.headOption
          spare = spare.drop(1)
          bindCount += 1
          shown += i -> source.bind(i, recycled)
        }
      }
    }

  /** Re-bind every row currently on screen, as a recycling container does when told the data changed.
    */
  private def rebindVisible(): Unit =
    rowSource.foreach { source =>
      shown.toSeq.sortBy(_._1).foreach { case (i, h) =>
        if i < source.count then {
          bindCount += 1
          val rebound = source.bind(i, Some(h))
          shown += i -> rebound
        }
      }
      // Drop anything now past the end of the data.
      shown.filter(_._1 >= source.count).foreach { case (i, h) =>
        shown -= i
        spare = h :: spare
      }
    }

  /** Handles currently materialised, in row order. */
  def visibleRows: Seq[Int] = shown.toSeq.sortBy(_._1).map(_._2)

  def liveRowCount:                 Int = shown.size + spare.size
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  def create(
    kind:  WidgetKind,
    props: Seq[Prop]
  ): Handle = {
    createCount += 1
    opCount += 1
    nextId += 1
    nodes(nextId) = Node(nextId, kind)
    update(nextId, props)
    nextId
  }

  /** Every prop the renderer was handed, in the order it was handed them.
    *
    * Order is part of the contract for at least one pair — a slider's `Range` must arrive before its `Value`, because a
    * value outside the bounds is meaningless and every toolkit clamps it silently rather than complaining.
    */
  val appliedProps: mutable.ArrayBuffer[Prop] = mutable.ArrayBuffer.empty

  def update(
    handle: Handle,
    patch:  Seq[Prop]
  ): Unit = {
    opCount += 1
    val n = nodes(handle)
    appliedProps ++= patch
    patch.foreach {
      case Prop.Text(v) =>
        if n.props.get("text").contains(v) then ()
        else {
          textWrites += 1
          n.props("text") = v
        }
      case Prop.Spacing(v)         => n.props("spacing") = v.toString
      case Prop.Padding(v)         => n.props("padding") = v.toString
      case Prop.Enabled(v)         => n.props("enabled") = v.toString
      case Prop.OnTap(f)           => n.onTap = Some(f)
      case Prop.Placeholder(v)     => n.props("placeholder") = v
      case Prop.Checked(v)         => n.props("checked") = v.toString
      case Prop.OnTextChange(f)    => n.onTextChange = Some(f)
      case Prop.OnCheckedChange(f) => n.onCheckedChange = Some(f)
      case Prop.Style(role)        => n.props("style") = role.toString
      case Prop.Grow(v)            => n.props("grow") = v.toString
      case Prop.Align(a)           => n.props("align") = a.toString
      case Prop.TextEmphasis(e)    => n.props("emphasis") = e.toString
      case Prop.Tint(c)            => c.foreach(v => n.props("tint") = s"${v.r},${v.g},${v.b}")
      case Prop.Fill(c)            => c.foreach(v => n.props("fill") = s"${v.r},${v.g},${v.b}")
      case Prop.Picture(src) =>
        n.props("picture") = src match {
          case Some(thicket.renderer.ImageSource.FromFile(p))  => s"file:$p"
          case Some(thicket.renderer.ImageSource.FromBytes(b)) => s"bytes:${b.length}"
          case None                                            => "none"
        }
      case Prop.Fit(f)  => n.props("fit") = f.toString
      case Prop.Axis(a) => n.props("axis") = a.toString
      case Prop.Progress(v) =>
        n.props("progress") = v.fold("indeterminate")(_ => "determinate")
        v.foreach(d => n.nums("progress") = d)
      case Prop.Value(v) => n.nums("value") = v
      case Prop.Range(lo, hi) =>
        n.nums("rangeMin") = lo
        n.nums("rangeMax") = hi
      case Prop.OnValueChange(f) => n.onValueChange = Some(f)
      case Prop.Message(v)       => n.props("message") = v
      case Prop.OnDismiss(f)     => n.onDismiss = Some(f)
      case Prop.ContextMenu(items) =>
        n.menu = items
        n.props("menu") = items.map(_.label).mkString(",")
      case Prop.Actions(as) =>
        n.actions = as
        n.props("actions") = as.map(_.label).mkString(",")
    }
  }

  /** Presented widgets, in the order they were presented. A test asserts on this rather than on `childrenOf`, because a
    * presented widget is deliberately not a child.
    */
  val presentedNow: mutable.ArrayBuffer[Handle] = mutable.ArrayBuffer.empty

  override def present(handle: Handle): Unit = {
    opCount += 1
    presentedNow += handle
  }

  override def dismiss(handle: Handle): Unit = {
    opCount += 1
    val _ = presentedNow.subtractOne(handle)
  }

  def insertAfter(
    parent: Handle,
    child:  Handle,
    after:  Option[Handle]
  ): Unit = {
    opCount += 1
    val kids = nodes(parent).children
    after match {
      case None    => kids.prepend(child)
      case Some(a) =>
        // Appending is the overwhelmingly common case — mounting a list is n appends —
        // and `indexOf` would make that O(n^2). Check the tail first.
        if kids.nonEmpty && kids.last == a then kids.append(child)
        else kids.insert(kids.indexOf(a) + 1, child)
    }
  }

  def removeChild(
    parent: Handle,
    child:  Handle
  ): Unit = {
    opCount += 1
    val _ = nodes(parent).children.subtractOne(child)
  }

  /** Detaches as well as releases, per the contract. */
  def destroy(handle: Handle): Unit = {
    opCount += 1
    destroyed = handle :: destroyed
    nodes.valuesIterator.foreach(n => n.children.subtractOne(handle))
    val _ = nodes.remove(handle)
  }

  def measure(
    handle: Handle,
    c:      Constraints
  ): Measurement = {
    val len = nodes(handle).props.getOrElse("text", "").length.toFloat
    Measurement(minW = len, minH = 10f, natW = len * 8f, natH = 18f)
  }

  def setFrame(
    handle: Handle,
    frame:  Frame
  ): Unit = ()
  def runOnUiThread(f: () => Unit): Unit = f()

  // -- test helpers ---------------------------------------------------------
  def text(handle:       Handle): String = nodes(handle).props.getOrElse("text", "")
  def kind(handle:       Handle): WidgetKind = nodes(handle).kind
  def childrenOf(handle: Handle): Seq[Int] = nodes(handle).children.toSeq

  /** Simulate the user typing. Mirrors a real renderer: the app is told, and the widget shows what was typed — the app
    * is *not* obliged to write it back.
    */
  def typeText(
    handle: Handle,
    value:  String
  ): Unit = {
    val n = nodes(handle)
    n.props("text") = value
    n.onTextChange.foreach(_(value))
  }

  def toggle(handle: Handle): Unit = {
    val n = nodes(handle)
    val now = !n.props.getOrElse("checked", "false").toBoolean
    n.props("checked") = now.toString
    n.onCheckedChange.foreach(_(now))
  }

  def tap(handle: Handle): Unit = {
    val n = nodes(handle)
    n.taps += 1
    n.onTap.foreach(_())
  }

}
