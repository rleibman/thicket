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

package thicket.camera.android

import _root_.android.Manifest
import _root_.android.app.Activity
import _root_.android.content.{ContentValues, Context, Intent}
import _root_.android.content.pm.PackageManager
import _root_.android.net.Uri
import _root_.android.os.{Build, Handler, Looper}
import _root_.android.provider.MediaStore
import java.nio.file.Path
import thicket.camera.Camera
import thicket.device.{Availability, Permission}
import thicket.pickers.PickedFiles
import thicket.renderer.android.ThicketActivity

/** The camera app's capture screen (`ACTION_IMAGE_CAPTURE`), asked from the current [[ThicketActivity]].
  *
  * The camera app writes the photo to a MediaStore entry Thicket creates for it (`docs/16` §16.3: `FileProvider`, the
  * usual target, is AndroidX). Once it answers, the photo is copied into the app's cache on a worker thread and the
  * entry is deleted, so the user's photo library keeps nothing the app did not ask to keep.
  *
  *   - **Android 10+.** Before that, a MediaStore entry needs storage permission: `Unavailable`.
  *   - **`CAMERA` permission.** Not needed, and the gallery does not declare it. But an app that declares it must have
  *     it granted, or Android refuses the camera screen: `NeedsPermission(Camera)` until it is.
  *   - One request at a time; a second answers `None` at once.
  */
final class AndroidCamera(context: Context) extends Camera {

  import AndroidCamera.*

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)
  private val picked = PickedFiles(app.getCacheDir.toPath)

  def availability: Availability =
    if Build.VERSION.SDK_INT < 29 then Availability.Unavailable("Taking a photo needs Android 10 or later")
    else if !app.getPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) then
      Availability.Unavailable("This device has no camera")
    else if declaresCamera && app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
    then Availability.NeedsPermission(Permission.Camera)
    else Availability.Available

  def capturePhoto(onResult: Option[Path] => Unit): Unit =
    (availability, pending, ThicketActivity.current) match {
      case (Availability.Available, None, Some(activity)) =>
        val resolver = app.getContentResolver
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, s"thicket-${System.currentTimeMillis()}.jpg")
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        // Not IS_PENDING: a pending entry is only open to the app that made it, and the camera app must write to it.
        Option(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) match {
          case None      => post(onResult(None))
          case Some(uri) =>
            pending = Some(answer(uri, onResult))
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            try activity.startActivityForResult(intent, RequestCode)
            catch {
              case _: _root_.android.content.ActivityNotFoundException =>
                pending = None
                val _ = resolver.delete(uri, null, null)
                post(onResult(None))
            }
        }
      case _ => post(onResult(None))
    }

  /** What to do once the camera answers: copy the photo it wrote to `uri`, if it took one, and delete the entry. */
  private def answer(
    uri:      Uri,
    onResult: Option[Path] => Unit
  ): Boolean => Unit =
    took => {
      val t = Thread(() => {
        val resolver = app.getContentResolver
        val photo =
          if !took then None
          else
            scala.util
              .Try(picked.copy(s"photo-${System.currentTimeMillis()}.jpg", resolver.openInputStream(uri)))
              .toOption
              .filter(p => java.nio.file.Files.size(p) > 0)
        val _ = scala.util.Try(resolver.delete(uri, null, null))
        post(onResult(photo))
      })
      t.setName("thicket-camera")
      t.start()
    }

  private def declaresCamera: Boolean =
    Option(
      app.getPackageManager.getPackageInfo(app.getPackageName, PackageManager.GET_PERMISSIONS).requestedPermissions
    )
      .exists(_.contains(Manifest.permission.CAMERA))

}

object AndroidCamera {

  /** The request code Thicket's camera uses. */
  val RequestCode: Int = 0x746a

  private val main = Handler(Looper.getMainLooper)

  private def post(f: => Unit): Unit = { val _ = main.post(() => f) }

  /** The request waiting for the camera: called with whether the user took a photo. */
  private var pending: Option[Boolean => Unit] = None

  locally {
    val _ = ThicketActivity.listen(new ThicketActivity.Listener {

      override def activityResult(
        activity:    ThicketActivity,
        requestCode: Int,
        resultCode:  Int,
        data:        Option[Intent]
      ): Boolean =
        requestCode == RequestCode && {
          val waiting = pending
          pending = None
          waiting.foreach(_(resultCode == Activity.RESULT_OK))
          true
        }

    })
  }

}
