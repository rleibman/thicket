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

package thicket.camera.preview.apple

import java.nio.file.{Files, Path, Paths}
import scala.scalanative.unsafe.*
import thicket.camera.preview.{CameraPreview, Facing}
import thicket.core.{GcState, UiThread}
import thicket.device.{AppState, Availability, Device, Permission, PermissionStatus}
import thicket.renderer.{ContentFit, PlatformView}
import thicket.renderer.apple.ApplePlatformView
import thicket.signals.{Owner, Signal, Var}

/** The camera preview on iOS and macOS (`docs/17` §17.4, #148): an `AVCaptureSession` shown by an
  * `AVCaptureVideoPreviewLayer` in a `UIView` or an `NSView`, and an `AVCapturePhotoOutput` on the same session for
  * [[capture]] (`libthicketpreview.a`).
  *
  *   - **Runs** while its widget is mounted, the app is in front ([[Device]]'s lifecycle) and the camera permission is
  *     granted; anything else stops the session. The app declares `NSCameraUsageDescription` and asks through
  *     `device.permissions` (`AVCaptureDevice.requestAccess`).
  *   - **Fit:** `Cover` is `.resizeAspectFill`, `Contain` `.resizeAspect`, `Fill` `.resize`. The view takes the
  *     frame's aspect as shown, so a layout that leaves its height open still gives it one.
  *   - **Facing:** on iOS the built-in front or back camera. On macOS `Front` is the first camera, built-in or USB, and
  *     `Back` is none, `Unavailable`, unless the system reports one on the back.
  *   - **Orientation:** the preview and the photo upright through `AVCaptureDevice.RotationCoordinator` on iOS; a Mac's
  *     camera needs none.
  *   - **One preview at a time** in the app: a second answers `Unavailable` while the first holds the camera.
  *   - Everything runs on the main thread; the library starts and stops the session on a queue of its own, and writes
  *     the JPEG under `<cache>/thicket-preview/` before answering on the main thread.
  *
  * Needs `libthicketpreview.a` at the app's final link (`macos/` or `ios-sim/` in this jar) and `thicket-device-apple`.
  *
  * @param device
  *   the lifecycle and the permission
  * @param cache
  *   the app's cache directory, e.g. `AppleStorage(bundleId).dirs.cache`: photos go under `<cache>/thicket-preview`
  */
final class AppleCameraPreview(
  device: Device,
  cache:  Path
)(using owner: Owner
) extends CameraPreview {

  import AppleCameraPreview.*

  val facing: Var[Facing] = Var(Facing.Back)

  private val runningVar = Var(false)

  def running: Signal[Boolean] = runningVar

  /** The mounted view, if any: an `NSView *` / `UIView *` the shim holds; this keeps no retain on it. */
  private var mounted: Option[Ptr[Byte]] = None
  private var inFront = false

  /** The session is started (asked for, or running). */
  private var started = false

  /** Renewed by every stop, so an answer for a session or a capture asked for before it is ignored. Unique across the
    * app's previews, since the library's answers find their preview by it.
    */
  private var generation = nextGeneration()
  private var capturing: Option[Option[Path] => Unit] = None

  private var stops = List.empty[String]

  // Follows the app in and out of the foreground, and the facing the app asks for.
  locally {
    val _ = Signal.effect {
      val front = device.lifecycle.state() == AppState.Foreground
      val _ = facing()
      Signal.untracked {
        inFront = front
        stop(if front then "facing" else "background")
        tryStart()
      }
    }
    owner.own(() => stop("disposed"))
  }

  def availability: Availability =
    if PreviewShim.thk_prev_has_camera(facing.now.ordinal) == 0 then
      Availability.Unavailable(s"no ${facing.now.toString.toLowerCase} camera")
    else if holder.exists(_ ne this) then Availability.Unavailable("in use by another preview")
    else
      // Throws, naming NSCameraUsageDescription, when the app does not declare it.
      device.permissions.status(Permission.Camera) match {
        case PermissionStatus.Granted    => Availability.Available
        case PermissionStatus.Restricted => Availability.Unavailable("the camera is restricted on this device")
        case PermissionStatus.Denied     => Availability.NeedsPermission(Permission.Camera)
      }

  def platformView(fit: ContentFit): PlatformView =
    new ApplePlatformView {

      val description = "Camera preview"

      def create(): Ptr[Byte] = {
        val v = PreviewShim.thk_prev_view_new(fit.ordinal)
        mount(v)
        v
      }

      override def release(view: Ptr[Byte]): Unit = unmount(view)

    }

  def capture(onResult: Option[Path] => Unit): Unit =
    if !runningVar.now || capturing.nonEmpty then UiThread.run(() => onResult(None))
    else {
      capturing = Some(onResult)
      val sent = Zone(PreviewShim.thk_prev_capture(toCString(dir.toString), captured, generation))
      if sent == 0 then finishCapture(None)
    }

  /** For the self-tests: what the preview and AVFoundation are doing, read on the main thread. */
  object testing {

    /** The view, while mounted. */
    def view: Option[Ptr[Byte]] = mounted

    /** Why the session was stopped, each time it was started: "background", "unmounted", … */
    def stopReasons: List[String] = stops

    /** Sessions the library has running: 0 or 1. */
    def sessionsRunning: Int = PreviewShim.thk_prev_test_running()

    /** Frames the sessions have delivered since the library was loaded. */
    def frames: Long = PreviewShim.thk_prev_test_frames()

    /** The running camera's active format, in pixels. */
    def size: Option[(Int, Int)] = {
      val w = stackalloc[CDouble]()
      val h = stackalloc[CDouble]()
      if PreviewShim.thk_prev_test_size(w, h) == 0 then None else Some((!w).toInt -> (!h).toInt)
    }

    /** The running camera's facing. */
    def facingRunning: Option[Facing] =
      PreviewShim.thk_prev_test_facing() match {
        case 0 => Some(Facing.Front)
        case 1 => Some(Facing.Back)
        case _ => None
      }

    /** The fit the mounted view's preview layer has, read back from its gravity. */
    def fit: Option[ContentFit] =
      mounted.flatMap(v => ContentFit.values.lift(PreviewShim.thk_prev_test_gravity(v)))

    /** The aspect the mounted view gives itself, height over width. */
    def aspect: Option[Double] = mounted.map(PreviewShim.thk_prev_test_aspect)

    /** Where captures are written. */
    def photoDir: Path = dir

  }

  private val dir: Path = cache.resolve("thicket-preview")

  private def mount(v: Ptr[Byte]): Unit = {
    stop("remounted")
    mounted = Some(v)
    tryStart()
  }

  private def unmount(v: Ptr[Byte]): Unit =
    if mounted.contains(v) then {
      stop("unmounted")
      mounted = None
    }

  private def shouldRun: Boolean = mounted.nonEmpty && inFront

  private def tryStart(): Unit =
    if shouldRun && !started && availability == Availability.Available then
      mounted.foreach { v =>
        holder = Some(this)
        started = true
        starting(generation) = this
        if PreviewShim.thk_prev_start(v, facing.now.ordinal, onStarted, generation) == 0 then {
          started = false
          starting.remove(generation)
          holder = holder.filter(_ ne this)
        }
      }

  private def onStart(
    g:  Long,
    ok: Boolean
  ): Unit =
    if g == generation && started then {
      if ok then runningVar.set(true)
      else stop("did not start")
    }

  /** Stops the session, if started, and answers a capture under way with `None`. `reason` is recorded. */
  private def stop(reason: String): Unit = {
    if started then {
      stops = stops :+ reason
      val _ = PreviewShim.thk_prev_stop()
    }
    starting.remove(generation)
    generation = nextGeneration()
    started = false
    holder = holder.filter(_ ne this)
    if runningVar.now then runningVar.set(false)
    finishCapture(None)
  }

  private def finishCapture(result: Option[Path]): Unit =
    capturing.foreach { cb =>
      capturing = None
      cb(result)
    }

  private def photo(
    g:    Long,
    path: String
  ): Unit =
    if g == generation then
      finishCapture(Option.when(path.nonEmpty)(Paths.get(path)).filter(p => Files.isRegularFile(p) && Files.size(p) > 0))

}

object AppleCameraPreview {

  private var generations = 0L

  private def nextGeneration(): Long = {
    generations += 1
    generations
  }

  /** The preview holding the camera: one at a time in the app (`docs/17` §17.5). */
  private var holder: Option[AppleCameraPreview] = None

  /** The preview each start was asked for by, keyed by its generation: a `CFuncPtr` cannot close over state. */
  private val starting = scala.collection.mutable.Map.empty[Long, AppleCameraPreview]

  private val onStarted: PreviewShim.IntCb =
    CFuncPtr2.fromScalaFunction(
      (
        g:      Long,
        result: CInt
      ) =>
        GcState.guarded {
          starting.remove(g).foreach(_.onStart(g, result == 1))
        }
    )

  private val captured: PreviewShim.TextCb =
    CFuncPtr2.fromScalaFunction(
      (
        g:    Long,
        path: CString
      ) =>
        GcState.guarded {
          val p = fromCString(path)
          holder.foreach(_.photo(g, p))
        }
    )

}
