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

package example.gallery

import scala.collection.mutable
import scala.scalanative.unsafe.*
import thicket.renderer.apple.{AppleApp, AppleInspect, ApplePlatformView}

/** `thicket_gallery_native_label.c`: a native label made outside the renderer, and what the checks ask of it. */
@extern
private[gallery] object NativeLabelC {

  def thk_gallery_label_new(utf8: CString): Ptr[Byte] = extern

  def thk_gallery_has_superview(view: Ptr[Byte]): CInt = extern

  def thk_gallery_weak_new(view: Ptr[Byte]): Ptr[Byte] = extern

  def thk_gallery_weak_alive(slot: Ptr[Byte]): CInt = extern

  def thk_gallery_weak_free(slot: Ptr[Byte]): Unit = extern

}

/** The Platform section's view on Apple (#145): an `NSTextField` on macOS, a `UILabel` on iOS, made in C outside the
  * renderer, as a camera preview's module makes its view (`docs/17` §17.3), and handed over +1 retained. It records
  * what it made, and — when told to release one — whether that view was still alive and still in its host.
  */
final class NativeLabel(text: String) extends ApplePlatformView {

  val description = "A native label"

  def create(): Ptr[Byte] = {
    val v = Zone(NativeLabelC.thk_gallery_label_new(toCString(text)))
    NativeLabel.made += NativeLabel.Made(v, NativeLabelC.thk_gallery_weak_new(v))
    v
  }

  /** Asks the weak reference first: reading a freed view's text would be a use-after-free. */
  override def release(view: Ptr[Byte]): Unit = {
    val made = NativeLabel.made.find(_.view == view)
    val alive = made.exists(m => NativeLabelC.thk_gallery_weak_alive(m.weak) != 0)
    NativeLabel.released += NativeLabel.Released(
      view,
      alive,
      alive && NativeLabelC.thk_gallery_has_superview(view) != 0,
      if alive then AppleInspect.text(view) else None
    )
  }

}

object NativeLabel {

  /** A view made, and a weak reference to it, which holds no retain. */
  final case class Made(
    view: Ptr[Byte],
    weak: Ptr[Byte]
  ) {

    def alive: Boolean = NativeLabelC.thk_gallery_weak_alive(weak) != 0

  }

  /** What `release` found: whether the view was alive, whether it was in a superview, and its text. */
  final case class Released(
    view:      Ptr[Byte],
    alive:     Boolean,
    inHost:    Boolean,
    textAtEnd: Option[String]
  )

  val made = mutable.ArrayBuffer.empty[Made]
  val released = mutable.ArrayBuffer.empty[Released]

}

/** The embedding point (`docs/17` §17.3, #145), the same five questions the GTK and Android hosts ask: the view the
  * host made is the one in the window, a view Apple has nothing for says so, and replacing the view releases the old
  * one while it is alive and in its host, after which the shim drops it — read through a weak reference, which the
  * runtime clears when the view is deallocated — and leaves the renderer's tables as they were.
  */
object GalleryPlatformChecks {

  type Check = (String, Boolean, String) => Unit

  private def toolkit: String = if AppleApp.renderer.platform == "uikit" then "UIKit" else "AppKit"

  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  def run(
    check: Check,
    click: String => Boolean
  )(
    next: () => Unit
  ): Unit = {
    def root = AppleApp.rootHandle
    def all = AppleInspect.all(root)
    def texts = AppleInspect.allTexts(root)
    def parentOf(v: Ptr[Byte]) = all.find(h => AppleInspect.children(h).contains(v))
    def size(h:     Ptr[Byte]) = { val f = AppleInspect.frame(h); (f._3, f._4) }
    def fmt(s:      (Double, Double)) = f"${s._1}%.1fx${s._2}%.1f"

    val first = NativeLabel.made.headOption
    val label = if toolkit == "UIKit" then "UILabel" else "NSTextField"
    val firstHost = first.flatMap(m => parentOf(m.view))
    // Read now: once the view is freed, only its weak reference may be asked anything.
    val firstSize = first.map(m => fmt(size(m.view)))
    check(
      s"Platform: the $label the host's view made is in the window, with its text, filling its host",
      NativeLabel.made.length == 1 && first.exists(m =>
        all.contains(m.view) && AppleInspect.className(m.view) == label &&
          AppleInspect.text(m.view).contains(Gallery.nativeLabelText(1)) &&
          firstHost.exists(h => size(h) == size(m.view) && size(h)._1 > 0 && size(h)._2 > 0)
      ),
      s"made ${NativeLabel.made.length}; in the window ${first.map(m => all.contains(m.view))}; " +
        s"class ${first.map(m => AppleInspect.className(m.view))}; text ${first.flatMap(m => AppleInspect.text(m.view))}; " +
        s"label $firstSize in host ${firstHost.map(h => fmt(size(h)))}"
    )
    val nowhere = s"${Gallery.nowhere.description}: not available on $toolkit"
    check(
      s"Platform: a view $toolkit has nothing for says so",
      texts.contains(nowhere),
      texts.filter(_.contains("not available")).toString
    )
    val entries = AppleApp.renderer.tableEntries
    val clicked = click("Make the native view again")
    eventually(2000)(NativeLabel.made.length >= 2) { _ =>
      val second = NativeLabel.made.lift(1)
      check(
        "Platform: a new view makes a new label, in the old one's place",
        clicked && NativeLabel.made.length == 2 && second.exists(m =>
          all.contains(m.view) && AppleInspect.text(m.view).contains(Gallery.nativeLabelText(2)) &&
            parentOf(m.view) == firstHost
        ) && !texts.contains(Gallery.nativeLabelText(1)),
        s"clicked $clicked; made ${NativeLabel.made.length}; same host ${second.map(m => parentOf(m.view) == firstHost)}; " +
          s"texts ${texts.filter(_.startsWith("A native"))}"
      )
      // The shim's reference was the only one: once it drops the view, the run loop's pool drains and the view goes.
      eventually(2000)(first.forall(!_.alive)) { ms =>
        val expected = first.toList.map(m => NativeLabel.Released(m.view, true, true, Some(Gallery.nativeLabelText(1))))
        check(
          "Platform: and the old view was released while still alive in its host, then freed",
          NativeLabel.released.toList == expected && first.exists(!_.alive),
          s"released ${NativeLabel.released.map(r => (r.alive, r.inHost, r.textAtEnd))}; " +
            s"old view alive now ${first.map(_.alive)}, after $ms ms"
        )
        check(
          "Platform: replacing the view leaves the renderer's tables as they were",
          AppleApp.renderer.tableEntries == entries,
          s"$entries before, ${AppleApp.renderer.tableEntries} after"
        )
        println(
          s"[gallery]   Platform: the first label was ${firstSize.getOrElse("-")}; " +
            (if first.exists(!_.alive) then s"freed $ms ms after the replacement" else s"still alive $ms ms after it")
        )
        // Cleanup: thk_gallery_weak_new mallocs a weak slot per view; free it once we're done observing it.
        NativeLabel.made.foreach(m => NativeLabelC.thk_gallery_weak_free(m.weak))
        NativeLabel.made.clear()
        NativeLabel.released.clear()
        next()
      }
    }
  }

}
