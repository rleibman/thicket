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

package thicket.pickers.android

import _root_.android.app.Activity
import _root_.android.content.{ContentResolver, Context, Intent}
import _root_.android.net.Uri
import _root_.android.os.{Build, Handler, Looper}
import _root_.android.provider.{MediaStore, OpenableColumns}
import java.nio.file.Path
import thicket.device.Availability
import thicket.pickers.{Documents, Photos, PickedFiles}
import thicket.renderer.android.ThicketActivity

/** The system's pickers, asked from the current [[ThicketActivity]] with `startActivityForResult`; its
  * `onActivityResult` brings the answer back.
  *
  *   - **Photos:** Android 13+'s photo picker (`ACTION_PICK_IMAGES`), which needs no permission; before that,
  *     `ACTION_GET_CONTENT` for `image/...`.
  *   - **Documents:** the Storage Access Framework, `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT`.
  *
  * Picked files are copied into the app's cache directory, on a worker thread (a video can be large), and handed back
  * on the main thread. One picker at a time: a request while one is up answers empty at once.
  */
final class AndroidPickers(context: Context) {

  import AndroidPickers.*

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)
  private val picked = PickedFiles(app.getCacheDir.toPath)

  val photos: Photos = new Photos {

    def availability: Availability = Availability.Available

    def pick(limit: Int)(onResult: Seq[Path] => Unit): Unit = {
      val intent =
        if Build.VERSION.SDK_INT >= 33 then {
          val i = Intent(MediaStore.ACTION_PICK_IMAGES)
          if limit > 1 then
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, math.min(limit, MediaStore.getPickImagesMaxLimit))
          i
        } else
          Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, limit > 1)
      ask(intent)(uris => copyAll(uris.take(limit))(onResult), () => onResult(Nil))
    }

  }

  val documents: Documents = new Documents {

    def availability: Availability = Availability.Available

    def open(
      mimeTypes: Seq[String],
      multiple:  Boolean
    )(
      onResult: Seq[Path] => Unit
    ): Unit = {
      val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(if mimeTypes.size == 1 then mimeTypes.head else "*/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
      if mimeTypes.size > 1 then intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray)
      ask(intent)(uris => copyAll(if multiple then uris else uris.take(1))(onResult), () => onResult(Nil))
    }

    def save(
      name:     String,
      mimeType: String,
      bytes:    Array[Byte]
    )(
      onResult: Option[String] => Unit
    ): Unit = {
      val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_TITLE, name)
      ask(intent)(
        {
          case uri :: _ =>
            onWorker {
              scala.util.Try {
                val out = app.getContentResolver.openOutputStream(uri, "wt")
                try out.write(bytes)
                finally out.close()
                displayName(app.getContentResolver, uri).getOrElse(name)
              }.toOption
            }(onResult)
          case Nil => onResult(None)
        },
        () => onResult(None)
      )
    }

  }

  private def copyAll(uris: List[Uri])(onResult: Seq[Path] => Unit): Unit =
    onWorker {
      val resolver = app.getContentResolver
      uris.flatMap { uri =>
        scala.util
          .Try(picked.copy(displayName(resolver, uri).getOrElse("picked"), resolver.openInputStream(uri))).toOption
      }
    }(onResult)

}

object AndroidPickers {

  /** The request code Thicket's pickers use. One suffices: one picker is up at a time. */
  val RequestCode: Int = 0x7469

  private val main = Handler(Looper.getMainLooper)

  /** The one request waiting for its answer: what to do with the URIs, and what to do on a cancel. */
  private var pending: Option[(List[Uri] => Unit, () => Unit)] = None

  /** Starts `intent` from the current Activity; `onUris` gets what it answered, `onCancel` runs if it was cancelled or
    * could not be asked.
    */
  private def ask(
    intent: Intent
  )(
    onUris:   List[Uri] => Unit,
    onCancel: () => Unit
  ): Unit =
    (pending, ThicketActivity.current) match {
      case (None, Some(activity)) =>
        pending = Some((onUris, onCancel))
        try activity.startActivityForResult(intent, RequestCode)
        catch {
          case _: _root_.android.content.ActivityNotFoundException =>
            pending = None
            val _ = main.post(() => onCancel())
        }
      case _ => val _ = main.post(() => onCancel())
    }

  /** The URIs an answer carries: several in its `ClipData`, or one as its data. */
  def uris(data: Intent): List[Uri] =
    Option(data.getClipData) match {
      case Some(clip) => (0 until clip.getItemCount).toList.flatMap(i => Option(clip.getItemAt(i).getUri))
      case None       => Option(data.getData).toList
    }

  private def displayName(
    resolver: ContentResolver,
    uri:      Uri
  ): Option[String] =
    scala.util
      .Try {
        val c = resolver.query(uri, Array(OpenableColumns.DISPLAY_NAME), null, null, null)
        try Option.when(c != null && c.moveToFirst())(c.getString(0)).flatMap(Option(_))
        finally if c != null then c.close()
      }.toOption.flatten

  /** Runs `work` on a thread of its own and hands its result to `deliver` on the main thread. */
  private def onWorker[A](work: => A)(deliver: A => Unit): Unit = {
    val t = Thread(() => {
      val a = work
      val _ = main.post(() => deliver(a))
    })
    t.setName("thicket-pickers")
    t.start()
  }

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
          waiting.foreach {
            (
              onUris,
              onCancel
            ) =>
              data.filter(_ => resultCode == Activity.RESULT_OK).map(uris) match {
                case Some(found) if found.nonEmpty => onUris(found)
                case _                             => onCancel()
              }
          }
          true
        }

    })
  }

}
