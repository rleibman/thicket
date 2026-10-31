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
final class AndroidCamera(
  context: Context,
  onSwept: Int => Unit = _ => ()
) extends Camera {

  import AndroidCamera.*

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)
  private val picked = PickedFiles(app.getCacheDir.toPath)

  /** Deletes the entries an earlier run left behind (review of #130). An entry is deleted once the camera answers, but
    * only this process remembers it: if Android kills the app while the camera app is in front, as it may under memory
    * pressure, the entry would stay in the user's photo library. So each new `AndroidCamera` removes every
    * `thicket-*.jpg` entry this app owns and is not waiting on, on a worker thread, and tells `onSwept` how many it
    * deleted. Only the app's own entries (`OWNER_PACKAGE_NAME`), never another app's photos.
    */
  private def sweep(onDone: Int => Unit): Unit =
    if Build.VERSION.SDK_INT >= 29 then {
      val t = Thread(() => {
        val waiting = pending.map(_._1.toString).toSet
        val resolver = app.getContentResolver
        val owned = scala.util
          .Try {
            val c = resolver.query(
              MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
              Array(_root_.android.provider.BaseColumns._ID),
              s"${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
              Array(app.getPackageName, "thicket-%.jpg"),
              null
            )
            try {
              val ids = scala.collection.mutable.ListBuffer.empty[Uri]
              while c != null && c.moveToNext() do
                ids += android.content.ContentUris
                  .withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
              ids.toList
            } finally if c != null then c.close()
          }.getOrElse(Nil)
        val deleted = owned
          .filterNot(u => waiting(u.toString)).map(u => scala.util.Try(resolver.delete(u, null, null)).getOrElse(0)).sum
        post(onDone(deleted))
      })
      t.setName("thicket-camera-sweep")
      t.start()
    } else post(onDone(0))

  sweep(onSwept)

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
            pending = Some((uri, answer(uri, onResult)))
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

  /** The request waiting for the camera: its entry, and what to call with whether the user took a photo. */
  private var pending: Option[(Uri, Boolean => Unit)] = None

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
          waiting.foreach(_._2(resultCode == Activity.RESULT_OK))
          true
        }

    })
  }

}
