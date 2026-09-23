package scalaui.renderer.android

import android.content.Context
import android.os.{Handler, Looper}
import android.graphics.Typeface
import android.util.TypedValue
import android.view.{Gravity, View, ViewGroup}
import android.text.{Editable, TextWatcher}
import android.widget.{Button, CheckBox, CompoundButton, EditText, LinearLayout, ScrollView, TextView}
import scala.collection.mutable
import scalaui.renderer.*

/** Android implementation of [[Renderer]], over `android.view.*`.
  *
  * Note the `_root_.android.R` references: this package is `scalaui.renderer.android`, which
  * shadows the platform's own `android` package — the same trap `scalaui.zio` has with `zio`.
  *
  * Runs on the JVM: Scala compiles to bytecode, R8 dexes it, ART runs it (S2). Nothing
  * here is Android-specific beyond the widget calls — the reconciler above is the same
  * code the GTK renderer drives.
  */
final class AndroidRenderer(context: Context) extends Renderer {
  type Handle = View

  private val kinds  = mutable.Map.empty[View, WidgetKind]

  /** True while the renderer writes a value in, so the widget's own change listener can
    * tell an app-driven update from a user edit. Without it, a signal bound to a text
    * field loops: write -> listener -> signal -> write.
    */
  private val suppress = mutable.Set.empty[View]
  private val mainHandler = Handler(Looper.getMainLooper)

  /** Resolve a theme attribute, so colours and backgrounds come from the user's theme
    * rather than from values we invent. This is what makes dark mode work for free.
    */
  private def themeAttr(attr: Int): TypedValue = {
    val tv = TypedValue()
    context.getTheme.resolveAttribute(attr, tv, true)
    tv
  }

  private def argb(c: scalaui.renderer.Rgb): Int =
    (0xff << 24) | (c.r << 16) | (c.g << 8) | c.b

  private def dp(v: Int): Int =
    (v * context.getResources.getDisplayMetrics.density).toInt

  def platform: String = "android"

  /** `LinearLayout` lays out its own children, so every v0 container is toolkit-managed,
    * exactly as on GTK. A Yoga-driven `FrameLayout` will be `FrameBased` when absolute
    * layout lands.
    */
  def layoutMode(kind: WidgetKind): LayoutMode = LayoutMode.ToolkitManaged

  def create(kind: WidgetKind, props: Seq[Prop]): Handle = {
    val view: View = kind match {
      case WidgetKind.Column =>
        val l = LinearLayout(context)
        l.setOrientation(LinearLayout.VERTICAL)
        l
      case WidgetKind.Row =>
        val l = LinearLayout(context)
        l.setOrientation(LinearLayout.HORIZONTAL)
        l
      case WidgetKind.Label     => TextView(context)
      case WidgetKind.Button    => Button(context)
      case WidgetKind.TextField => EditText(context)
      case WidgetKind.Checkbox  => CheckBox(context)
      case WidgetKind.Scroll    => ScrollView(context)
      case WidgetKind.Divider =>
        val v  = View(context)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)))
        v.setLayoutParams(lp)
        // `listDivider` is a *drawable* attribute, not a colour: reading `TypedValue.data`
        // as a colour silently yields an invisible line.
        v.setBackgroundResource(themeAttr(_root_.android.R.attr.listDivider).resourceId)
        v
    }
    kinds(view) = kind
    update(view, props)
    view
  }

  def update(handle: Handle, patch: Seq[Prop]): Unit =
    patch.foreach {
      case Prop.Text(v) =>
        handle match {
          case e: EditText =>
            // Only write when it differs, or the caret jumps to the end on every keystroke
            // as the app writes back what the user just typed.
            if e.getText.toString != v then {
              suppress += handle
              e.setText(v)
              e.setSelection(v.length)
              suppress -= handle
            }
          case t: TextView => t.setText(v)
          case _           => ()
        }

      case Prop.Placeholder(v) =>
        handle match {
          case e: EditText => e.setHint(v)
          case _           => ()
        }

      case Prop.OnTextChange(f) =>
        handle match {
          case e: EditText =>
            e.addTextChangedListener(new TextWatcher {
              def beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit = ()
              def onTextChanged(s: CharSequence, a: Int, b: Int, c: Int): Unit     = ()
              def afterTextChanged(s: Editable): Unit =
                if !suppress.contains(handle) then f(s.toString)
            })
          case _ => ()
        }

      case Prop.Checked(v) =>
        handle match {
          case c: CompoundButton =>
            if c.isChecked != v then {
              suppress += handle
              c.setChecked(v)
              suppress -= handle
            }
          case _ => ()
        }

      case Prop.OnCheckedChange(f) =>
        handle match {
          case c: CompoundButton =>
            c.setOnCheckedChangeListener { (_: CompoundButton, checked: Boolean) =>
              if !suppress.contains(handle) then f(checked)
            }
          case _ => ()
        }

      case Prop.Style(role) =>
        handle match {
          case t: TextView =>
            val (sp, bold) = role match {
              case TextRole.Title   => (24f, true)
              case TextRole.Body    => (16f, false)
              case TextRole.Caption => (13f, false)
            }
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            t.setTypeface(null, if bold then Typeface.BOLD else Typeface.NORMAL)
          case _ => ()
        }

      case Prop.Grow(v) =>
        val lp = handle.getLayoutParams match {
          case p: LinearLayout.LayoutParams => p
          case _ =>
            LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        lp.weight = if v then 1f else 0f
        if v then lp.width = 0
        handle.setLayoutParams(lp)

      case Prop.Tint(color) =>
        // `None` means "leave it to the platform", so the emphasis handling below keeps
        // using the theme attribute. Only an explicit override sets a literal colour.
        color.foreach { c =>
          handle match {
            case t: TextView => t.setTextColor(argb(c))
            case _           => ()
          }
        }

      case Prop.Fill(color) =>
        color.foreach(c => handle.setBackgroundColor(argb(c)))

      case Prop.TextEmphasis(level) =>
        handle match {
          case t: TextView =>
            val attr = level match {
              case Emphasis.Secondary => _root_.android.R.attr.textColorSecondary
              case Emphasis.Normal    => _root_.android.R.attr.textColorPrimary
            }
            val tv = themeAttr(attr)
            t.setTextColor(context.getResources.getColor(tv.resourceId, context.getTheme))
          case _ => ()
        }

      case Prop.Align(a) =>
        handle match {
          case t: TextView =>
            t.setGravity(a match {
              case Alignment.Start  => Gravity.START
              case Alignment.Center => Gravity.CENTER_HORIZONTAL
              case Alignment.End    => Gravity.END
            })
          case _ => ()
        }

      case Prop.OnTap(f) =>
        // Setting a listener replaces the previous one, so repeated updates cannot
        // stack handlers — the same property the GTK renderer gets by swapping the
        // closure behind a single connected signal.
        handle.setOnClickListener((_: View) => f())
        // Something tappable should look tappable: a container picks up the platform's
        // own ripple. Buttons already have theirs.
        handle match {
          case _: Button => ()
          case v =>
            v.setBackgroundResource(themeAttr(_root_.android.R.attr.selectableItemBackground).resourceId)
        }

      case Prop.Enabled(v) =>
        handle.setEnabled(v)

      case Prop.Padding(v) =>
        val p = dp(v)
        handle.setPadding(p, p, p, p)

      case Prop.Spacing(v) =>
        handle match {
          case l: LinearLayout =>
            spacing(l) = dp(v)
            applySpacing(l)
          case _ => ()
        }
    }

  private val spacing = mutable.Map.empty[LinearLayout, Int]

  /** `LinearLayout` has no spacing property, so gaps are child margins. They have to be
    * recomputed whenever the children change, because "which child is first" changes.
    */
  private def applySpacing(l: LinearLayout): Unit = {
    val gap        = spacing.getOrElse(l, 0)
    val horizontal = l.getOrientation == LinearLayout.HORIZONTAL
    var i          = 0
    while i < l.getChildCount do {
      val child = l.getChildAt(i)
      val lp = child.getLayoutParams match {
        case p: LinearLayout.LayoutParams => p
        case _ =>
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
          )
      }
      val lead = if i == 0 then 0 else gap
      if horizontal then lp.leftMargin = lead else lp.topMargin = lead
      child.setLayoutParams(lp)
      i += 1
    }
  }

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    if kinds.get(parent).contains(WidgetKind.Scroll) then {
      // A ScrollView holds one child, so "insert" is "set".
      val sv = parent.asInstanceOf[ScrollView]
      sv.removeAllViews()
      sv.addView(child)
      return
    }
    val vg = parent.asInstanceOf[ViewGroup]
    // `indexOfChild` is a linear scan, and mounting a list is n appends, so checking the
    // tail first is the difference between O(n) and O(n^2).
    val count = vg.getChildCount
    val index = after match {
      case None                                            => 0
      case Some(a) if count > 0 && vg.getChildAt(count - 1) == a => count
      case Some(a)                                         => vg.indexOfChild(a) + 1
    }
    vg.addView(child, index)
    vg match {
      case l: LinearLayout => applySpacing(l)
      case _               => ()
    }
  }

  def removeChild(parent: Handle, child: Handle): Unit = {
    val vg = parent.asInstanceOf[ViewGroup]
    vg.removeView(child)
    vg match {
      case l: LinearLayout => applySpacing(l)
      case _               => ()
    }
  }

  def destroy(handle: Handle): Unit = {
    handle.getParent match {
      case vg: ViewGroup =>
        vg.removeView(handle)
        vg match {
          case l: LinearLayout => applySpacing(l)
          case _               => ()
        }
      case _ => ()
    }
    handle.setOnClickListener(null)
    suppress -= handle
    val _ = kinds.remove(handle)
    handle match {
      case l: LinearLayout => val _ = spacing.remove(l)
      case _               => ()
    }
  }

  def measure(handle: Handle, constraints: Constraints): Measurement = {
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
  }

  /** No-op while every container is toolkit-managed. */
  def setFrame(handle: Handle, frame: Frame): Unit = ()

  def runOnUiThread(f: () => Unit): Unit =
    if Looper.myLooper eq Looper.getMainLooper then f()
    else {
      val _ = mainHandler.post(() => f())
    }
}
