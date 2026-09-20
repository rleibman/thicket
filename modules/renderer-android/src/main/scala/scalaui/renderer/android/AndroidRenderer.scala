package scalaui.renderer.android

import android.content.Context
import android.os.{Handler, Looper}
import android.view.{View, ViewGroup}
import android.widget.{Button, LinearLayout, TextView}
import scala.collection.mutable
import scalaui.renderer.*

/** Android implementation of [[Renderer]], over `android.view.*`.
  *
  * Runs on the JVM: Scala compiles to bytecode, R8 dexes it, ART runs it (S2). Nothing
  * here is Android-specific beyond the widget calls — the reconciler above is the same
  * code the GTK renderer drives.
  */
final class AndroidRenderer(context: Context) extends Renderer:
  type Handle = View

  private val kinds  = mutable.Map.empty[View, WidgetKind]
  private val mainHandler = Handler(Looper.getMainLooper)

  private def dp(v: Int): Int =
    (v * context.getResources.getDisplayMetrics.density).toInt

  def platform: String = "android"

  /** `LinearLayout` lays out its own children, so every v0 container is toolkit-managed,
    * exactly as on GTK. A Yoga-driven `FrameLayout` will be `FrameBased` when absolute
    * layout lands.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  def create(kind: WidgetKind, props: Seq[Prop]): Handle =
    val view: View = kind match
      case WidgetKind.Column =>
        val l = LinearLayout(context)
        l.setOrientation(LinearLayout.VERTICAL)
        l
      case WidgetKind.Row =>
        val l = LinearLayout(context)
        l.setOrientation(LinearLayout.HORIZONTAL)
        l
      case WidgetKind.Label  => TextView(context)
      case WidgetKind.Button => Button(context)
    kinds(view) = kind
    update(view, props)
    view

  def update(handle: Handle, patch: Seq[Prop]): Unit =
    patch.foreach:
      case Prop.Text(v) =>
        handle match
          case t: TextView => t.setText(v)
          case _           => ()

      case Prop.OnTap(f) =>
        // Setting a listener replaces the previous one, so repeated updates cannot
        // stack handlers — the same property the GTK renderer gets by swapping the
        // closure behind a single connected signal.
        handle.setOnClickListener((_: View) => f())

      case Prop.Enabled(v) =>
        handle.setEnabled(v)

      case Prop.Padding(v) =>
        val p = dp(v)
        handle.setPadding(p, p, p, p)

      case Prop.Spacing(v) =>
        handle match
          case l: LinearLayout =>
            spacing(l) = dp(v)
            applySpacing(l)
          case _ => ()

  private val spacing = mutable.Map.empty[LinearLayout, Int]

  /** `LinearLayout` has no spacing property, so gaps are child margins. They have to be
    * recomputed whenever the children change, because "which child is first" changes.
    */
  private def applySpacing(l: LinearLayout): Unit =
    val gap        = spacing.getOrElse(l, 0)
    val horizontal = l.getOrientation == LinearLayout.HORIZONTAL
    var i          = 0
    while i < l.getChildCount do
      val child = l.getChildAt(i)
      val lp = child.getLayoutParams match
        case p: LinearLayout.LayoutParams => p
        case _ =>
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
          )
      val lead = if i == 0 then 0 else gap
      if horizontal then lp.leftMargin = lead else lp.topMargin = lead
      child.setLayoutParams(lp)
      i += 1

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit =
    val vg = parent.asInstanceOf[ViewGroup]
    val index = after match
      case None    => 0
      case Some(a) => vg.indexOfChild(a) + 1
    vg.addView(child, index)
    vg match
      case l: LinearLayout => applySpacing(l)
      case _               => ()

  def removeChild(parent: Handle, child: Handle): Unit =
    val vg = parent.asInstanceOf[ViewGroup]
    vg.removeView(child)
    vg match
      case l: LinearLayout => applySpacing(l)
      case _               => ()

  def destroy(handle: Handle): Unit =
    handle.setOnClickListener(null)
    val _ = kinds.remove(handle)
    handle match
      case l: LinearLayout => val _ = spacing.remove(l)
      case _               => ()

  def measure(handle: Handle, constraints: Constraints): Measurement =
    def spec(v: Float): Int =
      if v.isNaN then View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
      else View.MeasureSpec.makeMeasureSpec(v.toInt, View.MeasureSpec.AT_MOST)
    handle.measure(spec(constraints.maxW), spec(constraints.maxH))
    Measurement(
      minW = handle.getMinimumWidth.toFloat,
      minH = handle.getMinimumHeight.toFloat,
      natW = handle.getMeasuredWidth.toFloat,
      natH = handle.getMeasuredHeight.toFloat
    )

  /** No-op while every container is toolkit-managed. */
  def setFrame(handle: Handle, frame: Frame): Unit = ()

  def runOnUiThread(f: () => Unit): Unit =
    if Looper.myLooper eq Looper.getMainLooper then f()
    else
      val _ = mainHandler.post(() => f())
