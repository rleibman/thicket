package scalaui.gtk

import scala.scalanative.unsafe.*

/** Draft of the renderer contract from docs/07 §7.4, written here so S7 can test the
  * shape against a real toolkit rather than against a sketch. Deviations found are
  * recorded in REPORT.md.
  *
  * Only what a Box/Label/Button counter needs is present: S7 is validating the *shape*,
  * not delivering a renderer.
  */
enum WidgetKind:
  case Column, Row, Label, Button

/** A property change. Kept as an enum rather than a `Map[String, Any]` so the compiler
  * checks renderer exhaustiveness — one of the reasons for doing this in Scala at all.
  */
enum Prop:
  case Text(value: String)
  case OnTap(handler: () => Unit)
  case Spacing(dp: Int)
  case Padding(dp: Int)

final case class Rect(x: Float, y: Float, w: Float, h: Float)
/** Named `MeasuredSize`, not `Size`: `scalanative.unsafe.Size` is in scope in every
  * renderer and shadows it. A real renderer-api module must avoid these collisions. */
final case class MeasuredSize(w: Float, h: Float)
final case class Constraints(maxW: Float, maxH: Float)

trait Renderer:
  type Handle

  def create(kind: WidgetKind, props: Seq[Prop]): Handle
  def update(h: Handle, patch: Seq[Prop]): Unit
  def insertChild(parent: Handle, child: Handle, index: Int): Unit
  def removeChild(parent: Handle, child: Handle): Unit
  def measure(h: Handle, c: Constraints): MeasuredSize
  def setFrame(h: Handle, frame: Rect): Unit
  def destroy(h: Handle): Unit
  def runOnUiThread(f: () => Unit): Unit
  def platform: String
