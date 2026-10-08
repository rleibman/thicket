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

import _root_.android.content.Context
import _root_.android.graphics.drawable.Drawable
import _root_.android.view.{Gravity, View, ViewGroup}
import _root_.android.widget.{FrameLayout, ImageView, LinearLayout, TextView}
import scala.collection.mutable
import thicket.renderer.Icon

/** `TabView` on Android (#61): a content area and a bottom bar, built from framework views.
  *
  * **The §12.2a construction.** Every tab control in the framework is deprecated (`TabHost`, `TabWidget`,
  * `ActionBar.Tab`) and the current one, `BottomNavigationView`, is Material — a dependency this project does not take.
  * So the bar is a row of items, each an icon over a label, the shape of Android's own bottom navigation, tinted with
  * the theme's primary colour when selected and its secondary text colour otherwise.
  *
  * Every tab stays in the content area — the unselected ones `GONE`, not removed — so a tab keeps its scroll position
  * and its state while the user is elsewhere, as GTK's `AdwViewStack` and UIKit's `UITabBarController` do.
  */
final private[android] class AndroidTabs(
  context:  Context,
  dp:       Int => Int,
  colourOf: Int => Int,
  icon:     Icon => Drawable
) {

  /** The parts of one TabView. */
  final private class Parts(
    val content: FrameLayout,
    val bar:     LinearLayout
  ) {

    var tabs:     Vector[FrameLayout] = Vector.empty
    var selected: Int                 = -1
    var onSelect: Option[Int => Unit] = None

    /** The app's selection, kept because `Selected` arrives at create, before any tab exists to show. */
    var wanted: Int = -1

  }

  private val views  = mutable.Map.empty[View, Parts]
  private val items  = mutable.Map.empty[View, LinearLayout] // tab -> its bar item
  private val titles = mutable.Map.empty[View, String]
  private val glyphs = mutable.Map.empty[View, Icon]
  private val owner  = mutable.Map.empty[View, View]         // tab -> its TabView

  def isTabView(v: View): Boolean = views.contains(v)
  def isTab(v: View): Boolean     = titles.contains(v) || glyphs.contains(v) || owner.contains(v)

  def createTabView(): View = {
    val root = LinearLayout(context)
    root.setOrientation(LinearLayout.VERTICAL)
    val content = FrameLayout(context)
    // WRAP_CONTENT plus weight, not 0 plus weight: inside a Scroll the TabView's own height
    // is wrap_content, and a zero-height weighted child of that is zero high.
    root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val bar = LinearLayout(context)
    bar.setOrientation(LinearLayout.HORIZONTAL)
    root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    views(root) = Parts(content, bar)
    root
  }

  def createTab(): FrameLayout = {
    val t = FrameLayout(context)
    titles(t) = ""
    t
  }

  /** A Tab holds one child, so inserting its content is replacing it. */
  def setContent(tab: FrameLayout, child: View): Unit = {
    tab.removeAllViews()
    tab.addView(child)
  }

  def setTitle(tab: View, title: String): Unit = {
    titles(tab) = title
    items.get(tab).foreach(item => label(item).setText(title))
  }

  def setIcon(tab: View, i: Icon): Unit = {
    glyphs(tab) = i
    items.get(tab).foreach(item => image(item).setImageDrawable(icon(i)))
  }

  private def label(item: LinearLayout): TextView = item.getChildAt(1).asInstanceOf[TextView]
  private def image(item: LinearLayout): ImageView = item.getChildAt(0).asInstanceOf[ImageView]

  private def newItem(view: View, tab: View): LinearLayout = {
    val item = LinearLayout(context)
    item.setOrientation(LinearLayout.VERTICAL)
    item.setGravity(Gravity.CENTER)
    item.setPadding(0, dp(8), 0, dp(8))
    item.setClickable(true)
    item.setBackgroundResource(attr(_root_.android.R.attr.selectableItemBackgroundBorderless))
    val img = ImageView(context)
    glyphs.get(tab).foreach(i => img.setImageDrawable(icon(i)))
    item.addView(img, LinearLayout.LayoutParams(dp(24), dp(24)))
    val txt = TextView(context)
    txt.setText(titles.getOrElse(tab, ""))
    txt.setGravity(Gravity.CENTER)
    item.addView(txt)
    item.setContentDescription(titles.getOrElse(tab, ""))
    // The user's choice: show it, and tell the app. The index is looked up at click time,
    // because tabs inserted before this one move it along.
    item.setOnClickListener { (_: View) =>
      val p = views(view)
      val i = p.tabs.indexOf(tab)
      if i >= 0 && i != p.selected then {
        show(p, i)
        p.onSelect.foreach(_(i))
      }
    }
    item
  }

  private def attr(a: Int): Int = {
    val tv = _root_.android.util.TypedValue()
    context.getTheme.resolveAttribute(a, tv, true)
    tv.resourceId
  }

  /** Show tab `i`, hide the others, and tint the bar to match. */
  private def show(p: Parts, i: Int): Unit = {
    p.selected = i
    val on  = colourOf(_root_.android.R.attr.colorPrimary)
    val off = colourOf(_root_.android.R.attr.textColorSecondary)
    p.tabs.zipWithIndex.foreach { (t, j) =>
      t.setVisibility(if j == i then View.VISIBLE else View.GONE)
      items.get(t).foreach { item =>
        val c = if j == i then on else off
        image(item).setColorFilter(c)
        label(item).setTextColor(c)
        item.setSelected(j == i)
      }
    }
  }

  def select(view: View, index: Int): Unit = {
    val p = views(view)
    p.wanted = index
    // -1, or past the end: keep showing what is shown, as GTK's stack does.
    if index >= 0 && index < p.tabs.length && index != p.selected then show(p, index)
  }

  def onSelect(view: View, f: Int => Unit): Unit = views(view).onSelect = Some(f)

  /** Keep the user on the tab they are looking at when tabs come and go; if its index moved, tell the app. */
  private def keepSelection(p: Parts, visible: Option[View]): Unit =
    visible.map(v => p.tabs.indexOf(v)).filter(_ >= 0) match {
      case Some(i) =>
        val moved = i != p.selected
        show(p, i)
        if moved then p.onSelect.foreach(_(i))
      case None if p.tabs.nonEmpty =>
        // The visible tab was removed: the platform's answer is the nearest one, which is reported.
        val i = math.min(math.max(p.selected, 0), p.tabs.length - 1)
        show(p, i)
        p.onSelect.foreach(_(i))
      case None => p.selected = -1
    }

  def insertAfter(view: View, tab: FrameLayout, after: Option[View]): Unit = {
    val p       = views(view)
    val visible = p.tabs.lift(p.selected)
    val current = p.tabs.filterNot(_ == tab)
    val at      = after.map(a => current.indexOf(a) + 1).getOrElse(0)
    if tab.getParent != null then p.content.removeView(tab)
    items.remove(tab).foreach(p.bar.removeView)
    p.content.addView(tab, at, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    val item = newItem(view, tab)
    items(tab) = item
    p.bar.addView(item, at, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    owner(tab) = view
    p.tabs = current.take(at) ++ (tab +: current.drop(at))
    if p.selected >= 0 then keepSelection(p, visible)
    else if p.wanted >= 0 && p.wanted < p.tabs.length then show(p, p.wanted)
    else tab.setVisibility(View.GONE) // nothing chosen yet; Selected will show one
  }

  def remove(view: View, tab: View): Unit =
    views.get(view).foreach { p =>
      val visible = p.tabs.lift(p.selected).filterNot(_ == tab)
      p.content.removeView(tab)
      items.remove(tab).foreach(p.bar.removeView)
      p.tabs = p.tabs.filterNot(_ == tab)
      val _ = owner.remove(tab)
      if p.selected >= 0 then keepSelection(p, visible)
    }

  /** Forget a TabView or a Tab; a Tab still in a TabView is taken out of it first. */
  def destroy(v: View): Unit = {
    owner.get(v).foreach(view => remove(view, v))
    val _ = views.remove(v)
    val _ = titles.remove(v)
    val _ = glyphs.remove(v)
  }

}
