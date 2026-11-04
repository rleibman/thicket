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

import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import thicket.camera.preview.Facing
import thicket.camera.preview.apple.AppleCameraPreview
import thicket.device.{AppState, Availability, Permission, PermissionStatus}
import thicket.device.apple.{AppleDevice, AppleLifecycle, ApplePermissions}
import thicket.renderer.ContentFit
import thicket.renderer.apple.{AppleApp, AppleInspect}

/** The Camera preview section on AppKit and UIKit (#148, `docs/17` §17.6): `AVCaptureSession` in the library's own
  * view, through the embedding point.
  *
  * Asked of whichever camera the device has, in each facing:
  *   - **Available** (the iOS simulator's simulated camera, granted by `simctl privacy`; a Mac's own camera once its user
  *     has allowed it): the session runs, one of it, on that facing, and frames arrive; the view takes the frame's
  *     aspect; a capture is a JPEG (`FF D8 FF`) under `<cache>/thicket-preview/` of the format's shape, deleted once
  *     read; in the background the preview's own lifecycle stops the session, and back in front it runs again.
  *   - **Not permitted** (a Mac whose user has not answered): nothing runs and the section says so. With
  *     `THICKET_PREVIEW_ASK=1` the macOS run asks through the system's own dialog and waits for its user.
  *   - **No such camera** (a Mac's back): `Unavailable` with that reason, and nothing runs.
  *
  * Then, if a session ran, hiding the widget unmounts it, which stops the session, and showing it again starts one. The
  * three fits map to the layer's three gravities whatever the camera. Photos from a Mac's camera are checked only for
  * their format and size, and deleted unread.
  */
object GalleryPreviewChecks {

  type Check = (String, Boolean, String) => Unit

  private def ios: Boolean = AppleApp.renderer.platform == "uikit"

  private def drive(action: String): Unit = {
    println(s"[gallery] DRIVE $action")
    System.out.flush()
  }

  /** Re-checks `cond` every 16 ms until it holds or `timeoutMs` passes, then continues with the time it took. */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  /** Width and height from a JPEG's start-of-frame marker. */
  def jpegSize(b: Array[Byte]): Option[(Int, Int)] = {
    def u8(i:  Int) = b(i) & 0xff
    def u16(i: Int) = (u8(i) << 8) | u8(i + 1)
    @tailrec def scan(i: Int): Option[(Int, Int)] =
      if i + 9 >= b.length || u8(i) != 0xff then None
      else {
        val m = u8(i + 1)
        if m >= 0xc0 && m <= 0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc then Some((u16(i + 7), u16(i + 5)))
        else scan(i + 2 + u16(i + 2))
      }
    if b.length > 4 && u8(0) == 0xff && u8(1) == 0xd8 then scan(2) else None
  }

  private def ratio(s: (Int, Int)): Double = s._2.toDouble / s._1

  def run(
    check: Check,
    click: String => Boolean,
    texts: () => List[String]
  )(
    next: () => Unit
  ): Unit =
    Gallery.currentPreview match {
      case None =>
        check("Preview: the Apple host provides a camera preview", false, "Gallery.currentPreview is None")
        next()
      case Some(demo) =>
        demo.preview match {
          case p: AppleCameraPreview => Steps(check, click, texts, demo, p).start(next)
          case other                 =>
            check("Preview: the Apple host's preview is AppleCameraPreview", false, other.toString)
            next()
        }
    }

  final private case class Steps(
    check: Check,
    click: String => Boolean,
    texts: () => List[String],
    demo:  CameraPreviewDemo,
    p:     AppleCameraPreview
  ) {

    private val t = p.testing
    private def running = p.running.now
    private def sessions = t.sessionsRunning
    private def statusTexts = texts().filter(_.startsWith("Preview:"))
    private def lc = AppleLifecycle.state.now

    /** Whether a session ran in some facing: the unmount checks need one. */
    private var ran = false

    def start(next: () => Unit): Unit = {
      val viewFrame = t.view.map(AppleInspect.frame)
      check(
        "Preview: the widget is the library's view, mounted, its layer Cover's gravity; Contain and Fill map to theirs",
        t.view.isDefined && t.fit.contains(ContentFit.Cover) &&
          ContentFit.values.forall(f => t.fitOfNew(f).contains(f)),
        s"view ${t.view.isDefined}; fit ${t.fit}; ${ContentFit.values.map(f => f -> t.fitOfNew(f)).toList}"
      )
      check(
        "Preview: the view gives itself an aspect, and its host lays it out at that aspect",
        viewFrame.exists(f => f._3 > 0 && f._4 > 0) && t.aspect.exists(a =>
          viewFrame.exists(f => math.abs(f._4 / f._3 - a) < 0.02)
        ),
        s"frame $viewFrame; aspect ${t.aspect}"
      )
      facingSteps { () =>
        flip()
        AppleApp.postToUiAfter(100) { () =>
          facingSteps { () =>
            unmountSteps { () =>
              // Back to the facing the section started with.
              flip()
              AppleApp.postToUiAfter(100)(() => next())
            }
          }
        }
      }
    }

    private def flip(): Unit = {
      val clicked = click("Flip the camera")
      println(s"[gallery] preview: flipped to ${p.facing.now} (clicked $clicked)")
    }

    /** The checks for the facing the preview has now. */
    private def facingSteps(k: () => Unit): Unit = {
      val facing = p.facing.now
      val name = facing.toString.toLowerCase
      val availability = p.availability
      println(s"[gallery] preview: $name camera: $availability")
      availability match {
        case Availability.Available =>
          live(facing)(k)
        case Availability.NeedsPermission(Permission.Camera) =>
          check(
            s"Preview ($name): not permitted, nothing runs, and the section says so",
            !running && sessions == 0 && statusTexts.contains(CameraPreviewDemo.statusLabel(false, availability)),
            s"running $running; sessions $sessions; $statusTexts"
          )
          if !ios && sys.env.contains("THICKET_PREVIEW_ASK") then ask(facing)(k)
          else {
            println(
              s"[gallery] UNMEASURED preview ($name): the camera permission is not granted, so no session ran. " +
                "THICKET_PREVIEW_ASK=1 asks through the system's dialog."
            )
            k()
          }
        case Availability.Unavailable(why) =>
          check(
            s"Preview ($name): a camera the device does not have says so, and nothing runs",
            why == s"no $name camera" && !running && sessions == 0 &&
              statusTexts.contains(CameraPreviewDemo.statusLabel(false, availability)),
            s"$availability; running $running; sessions $sessions; $statusTexts"
          )
          k()
        case other =>
          check(s"Preview ($name): an availability the checks know", false, other.toString)
          k()
      }
    }

    /** The system's own dialog, for its user to answer: 120 s, then the run goes on without. */
    private def ask(facing: Facing)(k: () => Unit): Unit = {
      var answer = Option.empty[PermissionStatus]
      println("[gallery] preview: asking for the camera through the system's dialog; waiting 120 s for its user")
      ApplePermissions.request(Permission.Camera)(s => answer = Some(s))
      eventually(120000)(answer.isDefined) { ms =>
        println(s"[gallery] preview: the camera permission answered $answer after $ms ms")
        demo.refresh()
        // The lifecycle's effect starts the preview; a granted permission alone does not re-run it.
        if answer.contains(PermissionStatus.Granted) then {
          demo.toggleShown()
          AppleApp.postToUiAfter(100) { () =>
            demo.toggleShown()
            AppleApp.postToUiAfter(100)(() => live(facing)(k))
          }
        } else {
          println("[gallery] UNMEASURED preview: the camera permission was not granted; no session ran")
          k()
        }
      }
    }

    /** A session on `facing`: frames, the aspect, a capture, the background and back. */
    private def live(facing: Facing)(k: () => Unit): Unit = {
      val name = facing.toString.toLowerCase
      val f0 = t.frames
      eventually(15000)(running && t.frames > f0 + 5) { ms =>
        ran = ran || running
        val size = t.size
        println(s"[gallery] measured: preview ($name) running with ${t.frames - f0} frames after $ms ms, at $size")
        check(
          s"Preview ($name): one session runs on the $name camera, and frames arrive",
          running && sessions == 1 && t.facingRunning.contains(facing) && t.frames > f0 + 5 && size.isDefined &&
            statusTexts.contains(CameraPreviewDemo.statusLabel(true, Availability.Available)),
          s"running $running; sessions $sessions; facing ${t.facingRunning}; frames ${t.frames - f0}; size $size; " +
            statusTexts
        )
        val frame = t.view.map(AppleInspect.frame)
        check(
          s"Preview ($name): the view takes the frame's aspect, and is laid out at it",
          size.exists(s => t.aspect.exists(a => math.abs(a - ratio(s)) < 0.01 || math.abs(a - 1 / ratio(s)) < 0.01)) &&
            frame.exists(f => t.aspect.exists(a => f._3 > 0 && math.abs(f._4 / f._3 - a) < 0.02)),
          s"format $size; aspect ${t.aspect}; frame $frame"
        )
        demo.lastPhoto.set(None)
        val clicked = click("Take from the preview")
        eventually(20000)(demo.lastPhoto.now.isDefined) { ms =>
          val photo = demo.lastPhoto.now.flatten
          val bytes = photo.map(Files.readAllBytes)
          val dims = bytes.flatMap(jpegSize)
          val magic = bytes.exists(b => b.length > 3 && (b(0) & 0xff) == 0xff && (b(1) & 0xff) == 0xd8 && (b(2) & 0xff) == 0xff)
          println(
            s"[gallery] measured: preview ($name) capture answered in $ms ms: ${bytes.map(_.length)} bytes, $dims"
          )
          check(
            s"Preview ($name): a capture is a JPEG under <cache>/thicket-preview/, of the format's shape",
            clicked && photo.exists(_.startsWith(t.photoDir)) && magic && bytes.exists(_.length > 1000) &&
              dims.exists(d => size.exists(s => math.abs(ratio(d) - ratio(s)) < 0.01 || math.abs(ratio(d) - 1 / ratio(s)) < 0.01)) &&
              texts().exists(_.startsWith("Photo from the preview: ")),
            s"clicked $clicked; $photo; magic $magic; ${bytes.map(_.length)} bytes; $dims; format $size"
          )
          // Checked for its format and size only, then deleted: on a Mac it is the user's own camera.
          photo.foreach(Files.deleteIfExists)
          background(name)(k)
        }
      }
    }

    private def leave(): Unit = if ios then drive("home") else { val _ = AppleDevice.testing.hide(true) }
    private def come(): Unit = if ios then drive("front") else { val _ = AppleDevice.testing.hide(false) }

    private def background(name: String)(k: () => Unit): Unit = {
      leave()
      eventually(30000)(lc == AppState.Background && sessions == 0 && !running) { ms =>
        val reasons = t.stopReasons
        println(s"[gallery] measured: preview ($name) stopped $ms ms after leaving; stops $reasons")
        check(
          s"Preview ($name): in the background the session is stopped, by the preview's own lifecycle",
          lc == AppState.Background && !running && sessions == 0 && reasons.lastOption.contains("background"),
          s"$lc; running $running; sessions $sessions; stops $reasons"
        )
        val f0 = t.frames
        come()
        eventually(30000)(lc == AppState.Foreground && running && t.frames > f0 + 5) { ms =>
          println(s"[gallery] measured: preview ($name) running again $ms ms after coming back")
          check(
            s"Preview ($name): back in front the session runs again, and frames arrive",
            running && sessions == 1 && t.frames > f0 + 5,
            s"$lc; running $running; sessions $sessions; frames ${t.frames - f0}"
          )
          k()
        }
      }
    }

    /** Hiding the widget unmounts it: `release` stops the session. Showing it again makes a new view and starts one. */
    private def unmountSteps(k: () => Unit): Unit =
      if !ran || !running then {
        println("[gallery] UNMEASURED preview: no session runs now, so unmounting has none to stop")
        k()
      } else {
        val clicked = click("Hide or show the preview")
        eventually(5000)(!running && sessions == 0 && t.view.isEmpty) { ms =>
          check(
            "Preview: hiding the widget unmounts it, and the session stops",
            clicked && t.view.isEmpty && !running && sessions == 0 && t.stopReasons.lastOption.contains("unmounted"),
            s"clicked $clicked; view ${t.view.isDefined}; running $running; sessions $sessions; stops ${t.stopReasons}; $ms ms"
          )
          val _ = click("Hide or show the preview")
          eventually(15000)(running && sessions == 1) { ms =>
            check(
              "Preview: showing it again mounts a new view, and a session runs in it",
              running && sessions == 1 && t.view.isDefined,
              s"running $running; sessions $sessions; view ${t.view.isDefined}; $ms ms"
            )
            k()
          }
        }
      }

  }

}
