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

package thicket.renderer.android

import android.content.Context
import android.os.{Build, Handler, Looper}
import android.graphics.{BitmapFactory, Typeface}
import android.util.TypedValue
import android.view.{Gravity, View, ViewGroup, WindowInsets}
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.{Editable, InputType, TextWatcher}
import android.widget.{AdapterView, ArrayAdapter, BaseAdapter, Button, CheckBox, CompoundButton, EditText, FrameLayout, HorizontalScrollView, ImageView, LinearLayout, ListView, PopupMenu, ProgressBar, ScrollView, SeekBar, Spinner, Switch, TextView}
import scala.collection.mutable
import thicket.renderer.*

/** Android implementation of [[Renderer]], over `android.view.*`.
  *
  * Note the `_root_.android.R` references: this package is `thicket.renderer.android`, which
  * shadows the platform's own `android` package — the same trap `thicket.zio` has with `zio`.
  *
  * Runs on the JVM: Scala compiles to bytecode, R8 dexes it, ART runs it (S2). Nothing
  * here is Android-specific beyond the widget calls — the reconciler above is the same
  * code the GTK renderer drives.
  */
final class AndroidRenderer(context: Context) extends Renderer {
  type Handle = View

  private val SliderSteps = 1000

  private val kinds  = mutable.Map.empty[View, WidgetKind]

  /** True while the renderer writes a value in, so the widget's own change listener can
    * tell an app-driven update from a user edit. Without it, a signal bound to a text
    * field loops: write -> listener -> signal -> write.
    */
  private val suppress = mutable.Set.empty[View]

  /** A slider's app-facing bounds, kept per widget because `SeekBar` has none: it counts
    * integer steps, and the renderer converts.
    */
  private val ranges = mutable.Map.empty[View, (Double, Double)]

  // An Alert's state, keyed by its placeholder handle. Held here rather than on the View
  // because the View is a stand-in: the real object is the Dialog, and it does not exist
  // until `present`.
  private val alertTitle   = mutable.Map.empty[View, String]
  private val alertMessage = mutable.Map.empty[View, String]
  private val alertActions = mutable.Map.empty[View, Seq[AlertAction]]
  private val alertDismiss = mutable.Map.empty[View, () => Unit]
  private val alertShowing = mutable.Map.empty[View, AlertDialog]
  private val sheetShowing = mutable.Map.empty[View, AlertDialog]
  private val mainHandler = Handler(Looper.getMainLooper)

  /** Resolve a theme attribute, so colours and backgrounds come from the user's theme
    * rather than from values we invent. This is what makes dark mode work for free.
    */
  private def themeAttr(attr: Int): TypedValue = {
    val tv = TypedValue()
    context.getTheme.resolveAttribute(attr, tv, true)
    tv
  }

  private def argb(c: thicket.renderer.Rgb): Int =
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
      // A CompoundButton exactly as CheckBox is, so Checked and OnCheckedChange need no
      // special case below: only the drawable differs, which is the whole point of Toggle
      // being a separate kind rather than a style flag.
      case WidgetKind.Toggle => Switch(context)

      // An Alert is presented, never attached (WidgetKind.presented), so this View exists
      // only to be a handle the renderer can key its dialog off. It is never added to any
      // parent and never drawn.
      case WidgetKind.Alert => View(context)

      // The sheet's content container. The Dialog that carries it is built in `present`.
      case WidgetKind.Sheet =>
        val l = LinearLayout(context)
        l.setOrientation(LinearLayout.VERTICAL)
        l

      case WidgetKind.SecureField =>
        val e = EditText(context)
        // The same EditText; only the input type differs. AppKit is the renderer that
        // needs a separate class, which is why this is a widget kind and not a prop.
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
        e

      case WidgetKind.Slider => SeekBar(context)

      // FrameLayout is exactly the contract: children drawn in order, each at its own size
      // and placed by its gravity, the layout as large as the largest of them.
      case WidgetKind.ZStack => FrameLayout(context)
      case WidgetKind.Picker =>
        val s = Spinner(context)
        // simple_spinner_dropdown_item, not simple_spinner_item: the latter is the *closed*
        // row's layout and renders the open list unreadably on a dark theme.
        s.setAdapter(
          new ArrayAdapter[String](
            context,
            _root_.android.R.layout.simple_spinner_dropdown_item,
            new java.util.ArrayList[String]()
          )
        )
        s

      case WidgetKind.Spacer =>
        val v = View(context)
        v.setLayoutParams(LinearLayout.LayoutParams(0, 0))
        v

      // The style is fixed at construction on Android — a horizontal bar and a circular
      // spinner are the same class with different styles, and the style cannot be changed
      // afterwards, which is why these are two widget kinds and not one with a prop.
      case WidgetKind.ProgressBar =>
        ProgressBar(context, null, _root_.android.R.attr.progressBarStyleHorizontal)

      case WidgetKind.ActivityIndicator =>
        val p = ProgressBar(context, null, _root_.android.R.attr.progressBarStyleLarge)
        p.setIndeterminate(true)
        p

      case WidgetKind.Scroll =>
        // The two directions are different classes on Android, which is why the contract
        // says the axis is read at create and never at update.
        val horizontal = props.exists {
          case Prop.Axis(Orientation.Horizontal) => true
          case _                                 => false
        }
        if horizontal then HorizontalScrollView(context) else ScrollView(context)
      case WidgetKind.Image => ImageView(context)
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
          // An Alert's title arrives as Prop.Text on its placeholder View.
          // Both presented kinds take their title as Prop.Text on the placeholder. This
          // read Alert only, so a Sheet's title silently went nowhere — invisible to the
          // self-tests, which never looked at it, and obvious the moment one was on screen.
          case _ if kinds.get(handle).exists(k => k == WidgetKind.Alert || k == WidgetKind.Sheet) =>
            alertTitle(handle) = v
          case _                                                 => ()
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

      // Grow means "along the parent's main axis", and a ZStack has none. Ignoring it there
      // is also what keeps the child's FrameLayout.LayoutParams: replacing them with a
      // LinearLayout's would make the FrameLayout throw on its next measure.
      case Prop.Grow(_) if handle.getParent.isInstanceOf[View] && isZStack(handle.getParent.asInstanceOf[View]) => ()

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

      case Prop.Picture(source) =>
        handle match {
          case iv: ImageView =>
            source match {
              case None => iv.setImageDrawable(null)
              case Some(ImageSource.FromFile(path)) =>
                iv.setImageBitmap(BitmapFactory.decodeFile(path))
              case Some(ImageSource.FromBytes(data)) =>
                iv.setImageBitmap(BitmapFactory.decodeByteArray(data, 0, data.length))
            }
          case _ => ()
        }

      case Prop.Fit(fit) =>
        handle match {
          case iv: ImageView =>
            iv.setScaleType(fit match {
              case ContentFit.Contain => ImageView.ScaleType.FIT_CENTER
              case ContentFit.Cover   => ImageView.ScaleType.CENTER_CROP
              case ContentFit.Fill    => ImageView.ScaleType.FIT_XY
            })
          case _ => ()
        }

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

      // Create-only: honouring a change would mean swapping ScrollView for
      // HorizontalScrollView under a live subtree. See Prop.Axis.
      case Prop.Axis(_) => ()

      // A SeekBar is integral, so the renderer owns the conversion — it is the only side
      // that knows its own resolution. 1000 steps rather than 100 so a fraction does not
      // quantise visibly on a wide control.
      case Prop.Range(min, max) =>
        handle match {
          case s: SeekBar =>
            ranges(s) = (min, max)
            s.setMax(SliderSteps)
          case _ => ()
        }

      case Prop.Value(v) =>
        handle match {
          case s: SeekBar =>
            val (min, max) = ranges.getOrElse(s, (0.0, 1.0))
            val span       = if max - min == 0.0 then 1.0 else max - min
            val steps      = (((v - min) / span) * SliderSteps).round.toInt
            val clamped    = math.max(0, math.min(SliderSteps, steps))
            // Same rule as the text field: writing unconditionally fights the user's drag.
            if s.getProgress != clamped then {
              suppress += s
              s.setProgress(clamped)
              suppress -= s
            }
          case _ => ()
        }

      case Prop.SafeArea(edges) =>
        // The framework's version of what the demo's Activity used to do by hand (docs/05
        // F-02). targetSdk 35+ forces edge-to-edge, so without this a screen draws behind
        // the status bar and its first row is simply invisible.
        if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R then {
          handle.setOnApplyWindowInsetsListener { (v: View, insets: WindowInsets) =>
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            // Leading/Trailing, not left/right: under an RTL locale the leading edge is the
            // right-hand one, and `getLayoutDirection` is how Android says which.
            val rtl = v.getLayoutDirection == View.LAYOUT_DIRECTION_RTL
            val leading = if rtl then Edge.Trailing else Edge.Leading
            val trailing = if rtl then Edge.Leading else Edge.Trailing
            v.setPadding(
              if edges.contains(leading) then bars.left else 0,
              if edges.contains(Edge.Top) then bars.top else 0,
              if edges.contains(trailing) then bars.right else 0,
              if edges.contains(Edge.Bottom) then bars.bottom else 0
            )
            insets
          }
          // `requestApplyInsets()` on a DETACHED view is a no-op, and a prop is applied
          // before the reconciler inserts the view — so asking here achieved nothing and the
          // listener never fired at all. The window's insets had already been dispatched by
          // the time this view existed, and nothing dispatches them again.
          //
          // So ask once the view is actually in the hierarchy. Measured: without this the
          // listener is never called, which the self-test's inset check is what caught.
          if handle.isAttachedToWindow then handle.requestApplyInsets()
          else
            handle.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener {

              def onViewAttachedToWindow(v: View): Unit = v.requestApplyInsets()

              def onViewDetachedFromWindow(v: View): Unit = ()

            })
        }

      case Prop.Options(values) =>
        handle match {
          case s: Spinner =>
            // Rebuild the adapter's contents rather than the adapter: a Spinner keeps its
            // selection by index across a data change, so swapping the adapter would reset
            // the selection to 0 every time the options were re-set with the same list.
            val a = s.getAdapter.asInstanceOf[ArrayAdapter[String]]
            suppress += handle
            a.clear()
            values.foreach(a.add)
            a.notifyDataSetChanged()
            suppress -= handle
          case _ => ()
        }

      case Prop.Selected(index) =>
        handle match {
          case s: Spinner =>
            // Android has no "nothing selected" for a Spinner - INVALID_POSITION is a read
            // value, not something you can set - so a negative index leaves it alone rather
            // than being coerced to 0, which would silently select the first option.
            if index >= 0 && index < s.getCount && s.getSelectedItemPosition != index then {
              suppress += handle
              s.setSelection(index)
              suppress -= handle
            }
          case _ => ()
        }

      case Prop.StackAlignment(h, v) =>
        handle match {
          case f: FrameLayout =>
            stackGravity(f) = gravity(h, v)
            var i = 0
            while i < f.getChildCount do {
              placeInStack(f, f.getChildAt(i))
              i += 1
            }
          case _ => ()
        }

      case Prop.OnSelect(f) =>
        handle match {
          case s: Spinner =>
            s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener {

              def onItemSelected(
                parent:   AdapterView[?],
                view:     View,
                position: Int,
                id:       Long
              ): Unit = if !suppress.contains(s) then f(position)

              def onNothingSelected(parent: AdapterView[?]): Unit = ()

            })
          case _ => ()
        }

      case Prop.OnValueChange(f) =>
        handle match {
          case s: SeekBar =>
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener {
              def onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean): Unit = {
                if !suppress.contains(bar) then {
                  val (min, max) = ranges.getOrElse(bar, (0.0, 1.0))
                  f(min + (progress.toDouble / SliderSteps) * (max - min))
                }
              }
              def onStartTrackingTouch(bar: SeekBar): Unit = ()
              def onStopTrackingTouch(bar: SeekBar): Unit  = ()
            })
          case _ => ()
        }

      case Prop.Message(v) => alertMessage(handle) = v

      case Prop.OnDismiss(f) => alertDismiss(handle) = f

      case Prop.Actions(as) => alertActions(handle) = as

      case Prop.ContextMenu(items) =>
        // Long press, which is Android's gesture for this — not right-click, which the
        // desktop renderers use. The app says "this widget has a menu" and each platform
        // decides how it is summoned.
        handle.setOnLongClickListener { (v: View) =>
          val menu = PopupMenu(context, v)
          items.zipWithIndex.foreach { (item, i) =>
            val mi = menu.getMenu.add(_root_.android.view.Menu.NONE, i, i, item.label)
            mi.setEnabled(item.enabled)
          }
          // `_root_.` because this file's own package is `thicket.renderer.android`, so a bare
          // `android.view` resolves to the wrong thing — the same trap as `_root_.android.R`.
          menu.setOnMenuItemClickListener { (mi: _root_.android.view.MenuItem) =>
            items.lift(mi.getItemId) match {
              case Some(item) => item.onSelect(); true
              case None       => false
            }
          }
          menu.show()
          true
        }

      case Prop.Progress(value) =>
        handle match {
          case p: ProgressBar =>
            value match {
              case Some(f) =>
                p.setIndeterminate(false)
                // Android's ProgressBar is integral; 0-1000 rather than 0-100 so a
                // fraction does not quantise visibly on a wide bar.
                p.setMax(1000)
                p.setProgress((math.max(0.0, math.min(1.0, f)) * 1000).toInt)
              case None => p.setIndeterminate(true)
            }
          case _ => ()
        }

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

  /** Each ZStack's gravity, so a child inserted later is placed like the ones already there. */
  private val stackGravity = mutable.Map.empty[FrameLayout, Int]

  /** Asked of `kinds`, not of the class: a ScrollView is a FrameLayout too, and so is the navigation container. */
  private def isZStack(v: View): Boolean = kinds.get(v).contains(WidgetKind.ZStack)

  private def gravity(h: Alignment, v: Alignment): Int = {
    // START and END rather than LEFT and RIGHT, so a right-to-left locale mirrors it.
    val x = h match {
      case Alignment.Start  => Gravity.START
      case Alignment.Center => Gravity.CENTER_HORIZONTAL
      case Alignment.End    => Gravity.END
    }
    val y = v match {
      case Alignment.Start  => Gravity.TOP
      case Alignment.Center => Gravity.CENTER_VERTICAL
      case Alignment.End    => Gravity.BOTTOM
    }
    x | y
  }

  /** A FrameLayout places each child by that child's own `gravity`, so the stack's alignment is written onto each. */
  private def placeInStack(stack: FrameLayout, child: View): Unit =
    stackGravity.get(stack).foreach { g =>
      // addView has already converted whatever the child carried into FrameLayout's own
      // params, keeping its size and margins; only the gravity is ours to set.
      val lp = child.getLayoutParams.asInstanceOf[FrameLayout.LayoutParams]
      lp.gravity = g
      child.setLayoutParams(lp)
    }

  def insertAfter(parent: Handle, child: Handle, after: Option[Handle]): Unit = {
    if kinds.get(parent).contains(WidgetKind.Scroll) then {
      // A scroll view holds one child, so "insert" is "set". Typed as ViewGroup, not
      // ScrollView: a horizontal Scroll is a HorizontalScrollView and the two share no
      // subclass below FrameLayout.
      val sv = parent.asInstanceOf[ViewGroup]
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
    // A view that carries no params of its own is given the parent's defaults, and
    // FrameLayout's are MATCH_PARENT both ways where LinearLayout's are WRAP_CONTENT — so
    // without this every plain child of a ZStack is stretched to fill it. The self-test
    // caught it as a spinner measuring exactly the size of the picture it sat on.
    if isZStack(vg) && child.getLayoutParams == null then
      child.setLayoutParams(
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      )
    // Index order is drawing order in a FrameLayout, so placing the child by its preceding
    // sibling is also what puts it at the right depth.
    vg.addView(child, index)
    vg match {
      case l: LinearLayout              => applySpacing(l)
      case f: FrameLayout if isZStack(f) => placeInStack(f, child)
      case _                            => ()
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
      case f: FrameLayout  => val _ = stackGravity.remove(f)
      case _               => ()
    }
    // Keyed by View, so an entry left behind keeps the View itself alive for the life of
    // the renderer. Android needs no handle table — the closures sit in these maps and the
    // GC collects them once nothing references the View — which is exactly why the removal
    // was easy to forget here and nowhere else.
    val _ = ranges.remove(handle)
    val _ = alertActions.remove(handle)
    val _ = alertDismiss.remove(handle)
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

  /** `ListView` materialises only the rows on screen and recycles the rest, which is the
    * whole point of `LazyColumn`. It is the platform's own widget — no AndroidX dependency.
    */
  override def supportsVirtualRows: Boolean = true

  /** Uses the platform `android.app.AlertDialog`, not AndroidX's.
    *
    * The class carries a deprecation flag in android-36's bytecode, though scalac does not
    * warn on it. Taking the AndroidX dependency to get the undeprecated one would add a
    * library this renderer otherwise does not need, for a dialog the platform still draws
    * correctly — and the reason this APK is 165 KB is that it depends on nothing. Revisit
    * if it is ever actually removed rather than merely discouraged.
    */
  override def present(handle: View): Unit =
    if kinds.get(handle).contains(WidgetKind.Sheet) then presentSheet(handle)
    else presentAlert(handle)

  /** Built on `AlertDialog.Builder().setView(...)`, not a plain `Dialog`.
    *
    * A plain `Dialog` reserves a title band under the current themes and then draws nothing
    * in it — a blank strip above the content, whether or not `setTitle` is called, before
    * or after `setContentView`. `AlertDialog` renders the title properly and handles the
    * content's padding and insets, and taking a custom view is what its `setView` is for.
    * It is the Android idiom for a modal with your own content; the plain `Dialog` is the
    * lower-level thing underneath it.
    */
  private def presentSheet(handle: View): Unit = {
    val b = AlertDialog.Builder(context)
    alertTitle.get(handle).filter(_.nonEmpty).foreach(b.setTitle)
    b.setView(handle)
    // Back gesture and tapping outside, which is what OnDismiss means. Not fired by
    // `dismiss()`, so an app-initiated take-down stays distinguishable.
    alertDismiss.get(handle).foreach(f => b.setOnCancelListener((_: DialogInterface) => f()))
    val d = b.create()
    sheetShowing(handle) = d
    d.show()
  }

  private def presentAlert(handle: View): Unit = {
    val b = AlertDialog.Builder(context)
    alertTitle.get(handle).foreach(b.setTitle)
    alertMessage.get(handle).filter(_.nonEmpty).foreach(b.setMessage)

    // Android has three button *slots*, not a list, and their on-screen order is fixed by
    // the platform rather than by the order given. So the roles decide the slot: cancel
    // goes to NEGATIVE, the first non-cancel to POSITIVE, and a third to NEUTRAL. An app
    // that assumed its own order would be wrong here and right everywhere else.
    val actions = alertActions.getOrElse(handle, Nil)
    val cancel  = actions.find(_.cancel)
    val rest    = actions.filterNot(_.cancel)

    def listener(a: AlertAction): DialogInterface.OnClickListener =
      (_: DialogInterface, _: Int) => a.onSelect()

    rest.headOption.foreach(a => b.setPositiveButton(a.label, listener(a)))
    cancel.foreach(a => b.setNegativeButton(a.label, listener(a)))
    rest.drop(1).headOption.foreach(a => b.setNeutralButton(a.label, listener(a)))

    // Dismissal without a choice: back gesture or a tap outside. `setOnCancelListener`
    // fires for exactly those and not for a button, which is the distinction Prop.OnDismiss
    // exists to preserve.
    alertDismiss.get(handle).foreach(f => b.setOnCancelListener((_: DialogInterface) => f()))

    val d = b.create()
    alertShowing(handle) = d
    d.show()
  }

  override def dismiss(handle: View): Unit = {
    alertShowing.remove(handle).foreach(_.dismiss())
    sheetShowing.remove(handle).foreach { d =>
      d.dismiss()
      // setContentView parented this view inside the dialog's window. Detach it, or the
      // reconciler's destroy runs against a view the dialog still owns.
      handle.getParent match {
        case g: ViewGroup => g.removeView(handle)
        case _            => ()
      }
    }
  }

  override def createVirtualList(source: RowSource[View]): View = {
    val list = ListView(context)

    val adapter = new BaseAdapter {
      def getCount: Int            = source.count
      def getItem(i: Int): Object  = Integer.valueOf(i)
      def getItemId(i: Int): Long  = i.toLong

      override def getView(position: Int, convertView: View, parent: ViewGroup): View =
        // Handing `convertView` back to the framework is what turns a scroll into a few
        // property writes: it re-binds that row's signal rather than building widgets.
        source.bind(position, Option(convertView))
    }

    list.setAdapter(adapter)
    // The platform draws its own dividers here, so rows need not supply them.
    source.onInvalidate(() => adapter.notifyDataSetChanged())
    list
  }

  def runOnUiThread(f: () => Unit): Unit =
    if Looper.myLooper eq Looper.getMainLooper then f()
    else {
      val _ = mainHandler.post(() => f())
    }
}
