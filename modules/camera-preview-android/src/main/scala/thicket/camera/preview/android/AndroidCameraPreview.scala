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

package thicket.camera.preview.android

import _root_.android.content.Context
import _root_.android.graphics.{ImageFormat, Matrix, SurfaceTexture}
import _root_.android.hardware.camera2.{
  CameraCaptureSession,
  CameraCharacteristics,
  CameraDevice,
  CameraManager,
  CameraMetadata,
  CaptureFailure,
  CaptureRequest
}
import _root_.android.media.ImageReader
import _root_.android.os.{Handler, HandlerThread, Looper}
import _root_.android.util.{Log, Size}
import _root_.android.view.{Surface, TextureView, View}
import java.nio.file.{Files, Path}
import scala.annotation.nowarn
import scala.util.Try
import thicket.camera.preview.{CameraPreview, Facing}
import thicket.device.{Availability, Device, Permission, PermissionStatus}
import thicket.renderer.{ContentFit, PlatformView}
import thicket.renderer.android.AndroidPlatformView
import thicket.signals.{Owner, Signal, Var}

/** The camera preview on Android (`docs/17` §17.4): Camera2 into a `TextureView`, and a JPEG `ImageReader` on the same
  * session for [[capture]]. Not CameraX, which is AndroidX (`docs/16` §16.9).
  *
  *   - **Runs** while its widget is mounted, its surface is ready, the app is in front ([[Device]]'s lifecycle) and the
  *     `CAMERA` permission is granted; anything else closes the camera. The app declares `CAMERA` and asks for it
  *     through `device.permissions`.
  *   - **Orientation** is composed by hand, as Camera2 does not: the texture is upright in the device's natural
  *     orientation, so the view's transform turns it back by the display's rotation and scales it to the fit.
  *   - **One preview at a time** in the app: a second answers `Unavailable` while the first holds the camera.
  *   - Everything but the JPEG's write runs on the UI thread, the camera's callbacks included; the write is on a thread
  *     of the preview's own.
  */
final class AndroidCameraPreview(
  context:     Context,
  device:      Device
)(using owner: Owner
) extends CameraPreview {

  import AndroidCameraPreview.*

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)
  private val manager = app.getSystemService(classOf[CameraManager])
  private val main = Handler(Looper.getMainLooper)

  lazy private val worker: Handler = {
    val t = HandlerThread("thicket-camera-preview")
    t.start()
    owner.own(() => { val _ = t.quitSafely() })
    Handler(t.getLooper)
  }

  val facing: Var[Facing] = Var(Facing.Back)

  private val runningVar = Var(false)

  def running: Signal[Boolean] = runningVar

  /** The mounted widget, if any, and the fit it was made with. */
  private var mounted: Option[(PreviewTexture, ContentFit)] = None
  private var surfaceReady = false
  private var inFront = false

  /** The camera, once its session is configured. */
  private var open: Option[Opened] = None

  /** Bumped by every stop, so a callback for a camera asked for before it is ignored (and that camera closed). */
  private var generation = 0
  private var opening = false
  private var capturing: Option[Option[Path] => Unit] = None

  // Follows the app in and out of the foreground, and the facing the app asks for.
  locally {
    val _ = Signal.effect {
      val front = device.lifecycle.state() == thicket.device.AppState.Foreground
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
    cameraId(facing.now) match {
      case None => Availability.Unavailable(s"no ${facing.now.toString.toLowerCase} camera")
      case Some(_) if holder.exists(_ ne this) => Availability.Unavailable("in use by another preview")
      case Some(_)                             =>
        Try(device.permissions.status(Permission.Camera)).getOrElse(PermissionStatus.Denied) match {
          case PermissionStatus.Granted    => Availability.Available
          case PermissionStatus.Restricted => Availability.Unavailable("the camera is restricted on this device")
          case PermissionStatus.Denied     => Availability.NeedsPermission(Permission.Camera)
        }
    }

  def platformView(fit: ContentFit): PlatformView =
    new AndroidPlatformView {

      val description = "Camera preview"

      def create(context: Context): View = {
        val t = PreviewTexture(context)
        mount(t, fit)
        t
      }

      override def release(view: View): Unit = unmount(view)

    }

  def capture(onResult: Option[Path] => Unit): Unit =
    (open, capturing) match {
      case (Some(o), None) =>
        capturing = Some(onResult)
        val g = generation
        val request = o.camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        request.addTarget(o.reader.getSurface)
        request.set(CaptureRequest.JPEG_ORIENTATION, Integer.valueOf(jpegOrientation(o)))
        val sent = Try(
          o.session.capture(
            request.build(),
            new CameraCaptureSession.CaptureCallback {
              override def onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
              ): Unit = finishCapture(None)
            },
            main
          )
        )
        if sent.isFailure then finishCapture(None)
        // A capture the camera never answers must still answer the app.
        else main.postDelayed(() => if generation == g then finishCapture(None), CaptureTimeoutMs)
      case _ => onResult(None)
    }

  /** For the self-tests: what the preview is doing, read on the UI thread. */
  object testing {

    /** Frames the widget has shown since it was made: the texture's updates. */
    def framesShown: Int = frames

    /** Camera devices this preview has open: 0 or 1. */
    def devicesOpen: Int = openDevices

    /** The widget, while mounted. */
    def textureView: Option[TextureView] = mounted.map(_._1)

    /** Why the camera was closed, each time it was open or opening: "background", "surface destroyed", … */
    def stopReasons: List[String] = stops

    /** The sizes in use: the preview's buffer and the JPEG's. */
    def sizes: Option[(Size, Size)] = open.map(o => (o.previewSize, o.jpegSize))

  }

  private var frames = 0
  private var stops = List.empty[String]
  private var openDevices = 0

  private def mount(
    t:   PreviewTexture,
    fit: ContentFit
  ): Unit = {
    stop("remounted")
    surfaceReady = false
    mounted = Some((t, fit))
    t.setSurfaceTextureListener(new TextureView.SurfaceTextureListener {
      def onSurfaceTextureAvailable(
        st: SurfaceTexture,
        w:  Int,
        h:  Int
      ): Unit =
        if mounted.exists(_._1 eq t) then {
          surfaceReady = true
          tryStart()
        }
      def onSurfaceTextureSizeChanged(
        st: SurfaceTexture,
        w:  Int,
        h:  Int
      ):                                                 Unit = applyTransform()
      def onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = {
        if mounted.exists(_._1 eq t) then {
          stop("surface destroyed")
          surfaceReady = false
        }
        true
      }
      def onSurfaceTextureUpdated(st: SurfaceTexture): Unit = frames += 1
    })
    t.addOnLayoutChangeListener(
      (
        _,
        _,
        _,
        _,
        _,
        _,
        _,
        _,
        _
      ) => applyTransform()
    )
  }

  private def unmount(view: View): Unit =
    if mounted.exists(_._1 eq view) then {
      stop("unmounted")
      mounted = None
      surfaceReady = false
    }

  private def cameraId(f: Facing): Option[String] = {
    val wanted = f match {
      case Facing.Back  => CameraMetadata.LENS_FACING_BACK
      case Facing.Front => CameraMetadata.LENS_FACING_FRONT
    }
    Try(manager.getCameraIdList.toList)
      .getOrElse(Nil).find(id =>
        Try(manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)).toOption
          .exists(l => l != null && l.intValue == wanted)
      )
  }

  private def shouldRun: Boolean = mounted.nonEmpty && surfaceReady && inFront

  private def tryStart(): Unit =
    if shouldRun && open.isEmpty && !opening && availability == Availability.Available then
      cameraId(facing.now).foreach { id =>
        holder = Some(this)
        opening = true
        val g = generation
        val sent = Try(
          manager.openCamera(
            id,
            new CameraDevice.StateCallback {
              def onOpened(camera: CameraDevice): Unit = {
                openDevices += 1
                if g != generation || !shouldRun then camera.close()
                else configure(camera, id, g)
              }
              def onDisconnected(camera: CameraDevice): Unit = {
                camera.close()
                if g == generation then stop("disconnected")
              }
              def onError(
                camera: CameraDevice,
                error:  Int
              ): Unit = {
                Log.w(Tag, s"camera preview: camera $id error $error")
                camera.close()
                if g == generation then stop(s"error $error")
              }
              override def onClosed(camera: CameraDevice): Unit = openDevices -= 1
            },
            main
          )
        )
        sent.failed.foreach { e =>
          Log.w(Tag, s"camera preview: opening camera $id failed: $e")
          opening = false
          holder = holder.filter(_ ne this)
        }
      }

  // createCaptureSession(List, …): SessionConfiguration needs API 28, and the framework's minimum is 26.
  @nowarn("cat=deprecation")
  private def configure(
    camera: CameraDevice,
    id:     String,
    g:      Int
  ): Unit = {
    val chars = manager.getCameraCharacteristics(id)
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val (previewSize, jpegSize) = chooseSizes(
      map.getOutputSizes(classOf[SurfaceTexture]).toList,
      map.getOutputSizes(ImageFormat.JPEG).toList
    )
    val sensor = Option(chars.get(CameraCharacteristics.SENSOR_ORIENTATION)).map(_.intValue).getOrElse(0)
    mounted match {
      case None               => camera.close()
      case Some((texture, _)) =>
        val st = texture.getSurfaceTexture
        st.setDefaultBufferSize(previewSize.getWidth, previewSize.getHeight)
        val surface = Surface(st)
        val reader = ImageReader.newInstance(jpegSize.getWidth, jpegSize.getHeight, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener(r => saveJpeg(r, g), worker)
        val sessionCallback = new CameraCaptureSession.StateCallback {
          def onConfigured(session: CameraCaptureSession): Unit =
            if g != generation then { session.close(); camera.close(); reader.close(); surface.release() }
            else {
              val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
              request.addTarget(surface)
              session.setRepeatingRequest(request.build(), null, main)
              opening = false
              open = Some(Opened(camera, session, reader, surface, previewSize, jpegSize, sensor, facing.now))
              runningVar.set(true)
              applyTransform()
            }
          def onConfigureFailed(session: CameraCaptureSession): Unit = {
            Log.w(Tag, s"camera preview: the session for camera $id could not be configured")
            session.close()
            camera.close()
            reader.close()
            surface.release()
            if g == generation then stop("configure failed")
          }
        }
        camera.createCaptureSession(java.util.List.of(surface, reader.getSurface), sessionCallback, main)
    }
  }

  /** Closes the camera, if open or opening, and answers a capture under way with `None`. `reason` is recorded. */
  private def stop(reason: String): Unit = {
    if open.nonEmpty || opening then stops = stops :+ reason
    generation += 1
    opening = false
    open.foreach { o =>
      Try(o.session.close())
      Try(o.camera.close())
      Try(o.reader.close())
      Try(o.surface.release())
    }
    open = None
    holder = holder.filter(_ ne this)
    if runningVar.now then runningVar.set(false)
    finishCapture(None)
  }

  private def finishCapture(result: Option[Path]): Unit =
    capturing.foreach { cb =>
      capturing = None
      cb(result)
    }

  /** On the worker thread: the JPEG into the app's cache, then the answer on the UI thread. */
  private def saveJpeg(
    reader: ImageReader,
    g:      Int
  ): Unit = {
    val written = Try {
      val image = reader.acquireLatestImage()
      try {
        val buffer = image.getPlanes()(0).getBuffer
        val bytes = new Array[Byte](buffer.remaining)
        buffer.get(bytes)
        val dir = app.getCacheDir.toPath.resolve("thicket-preview")
        Files.createDirectories(dir)
        Files.write(dir.resolve(s"preview-${System.currentTimeMillis()}.jpg"), bytes)
      } finally image.close()
    }
    written.failed.foreach(e => Log.w(Tag, s"camera preview: the photo could not be written: $e"))
    val _ = main.post(() => if g == generation then finishCapture(written.toOption))
  }

  /** Degrees the display is turned from the device's natural orientation. */
  private def displayDegrees(v: View): Int =
    Option(v.getDisplay).map(_.getRotation).getOrElse(Surface.ROTATION_0) match {
      case Surface.ROTATION_90  => 90
      case Surface.ROTATION_180 => 180
      case Surface.ROTATION_270 => 270
      case _                    => 0
    }

  /** As `CaptureRequest.JPEG_ORIENTATION` documents it, with the display's rotation for the device's. */
  private def jpegOrientation(o: Opened): Int = {
    val device = mounted
      .map(
        (
          t,
          _
        ) => displayDegrees(t)
      ).getOrElse(0)
    val turned = if o.facing == Facing.Front then -device else device
    (o.sensorOrientation - turned + 360) % 360
  }

  /** The texture is drawn stretched over the view, upright in the device's natural orientation. This undoes the
    * stretch, turns it back by the display's rotation, and scales it to the fit.
    */
  private def applyTransform(): Unit =
    for {
      (t, fit) <- mounted
      o        <- open
    } {
      val vw = t.getWidth.toFloat
      val vh = t.getHeight.toFloat
      val (pw, ph) = (o.previewSize.getWidth.toFloat, o.previewSize.getHeight.toFloat)
      // The frame in the natural orientation, then on screen.
      val (nw, nh) = if o.sensorOrientation % 180 != 0 then (ph, pw) else (pw, ph)
      val rotation = displayDegrees(t)
      val (dw, dh) = if rotation % 180 != 0 then (nh, nw) else (nw, nh)
      t.setContentAspect(dh / dw)
      if vw > 0 && vh > 0 then {
        val (cx, cy) = (vw / 2, vh / 2)
        val m = Matrix()
        m.postScale(nw / vw, nh / vh, cx, cy)
        m.postRotate(-rotation.toFloat, cx, cy)
        fit match {
          case ContentFit.Cover =>
            val s = math.max(vw / dw, vh / dh)
            m.postScale(s, s, cx, cy)
          case ContentFit.Contain =>
            val s = math.min(vw / dw, vh / dh)
            m.postScale(s, s, cx, cy)
          case ContentFit.Fill =>
            m.postScale(vw / dw, vh / dh, cx, cy)
        }
        t.setTransform(m)
      }
    }

}

object AndroidCameraPreview {

  private val Tag = "thicket"
  private val CaptureTimeoutMs = 5000L

  /** The preview holding the camera: one at a time in the app (`docs/17` §17.5). */
  private var holder: Option[AndroidCameraPreview] = None

  final private case class Opened(
    camera:            CameraDevice,
    session:           CameraCaptureSession,
    reader:            ImageReader,
    surface:           Surface,
    previewSize:       Size,
    jpegSize:          Size,
    sensorOrientation: Int,
    facing:            Facing
  )

  /** At most 1920×1080, the largest size both the preview and the JPEG offer, so the photo is the preview's size; if
    * none is common, the largest of each within the bound.
    */
  private[android] def chooseSizes(
    preview: List[Size],
    jpeg:    List[Size]
  ): (Size, Size) = {
    def within(s:      Size) = s.getWidth.toLong * s.getHeight <= 1920L * 1080
    def area(s:        Size) = s.getWidth.toLong * s.getHeight
    def largest(sizes: List[Size]) = sizes.filter(within).maxByOption(area).getOrElse(sizes.minBy(area))
    val common = preview.filter(p => jpeg.exists(j => j.getWidth == p.getWidth && j.getHeight == p.getHeight))
    common.filter(within).maxByOption(area) match {
      case Some(s) => (s, s)
      case None    => (largest(preview), largest(jpeg))
    }
  }

}

/** The preview's widget: a `TextureView` that takes the frame's aspect when the layout leaves its height open (in a
  * scrolling column, say), where a plain one would be 0 px tall.
  */
final private class PreviewTexture(context: Context) extends TextureView(context) {

  /** Height over width of the frame as shown; 4:3 portrait until the camera says. */
  private var aspect: Float = 4f / 3f

  def setContentAspect(a: Float): Unit =
    if math.abs(a - aspect) > 0.001f then {
      aspect = a
      requestLayout()
    }

  override def onMeasure(
    widthSpec:  Int,
    heightSpec: Int
  ): Unit = {
    val w = View.MeasureSpec.getSize(widthSpec)
    val h = View.MeasureSpec.getMode(heightSpec) match {
      case View.MeasureSpec.EXACTLY => View.MeasureSpec.getSize(heightSpec)
      case View.MeasureSpec.AT_MOST => math.min((w * aspect).toInt, View.MeasureSpec.getSize(heightSpec))
      case _                        => (w * aspect).toInt
    }
    setMeasuredDimension(w, h)
  }

}
