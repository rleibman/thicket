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

package thicket.share.android

import _root_.android.content.{ClipData, ContentProvider, ContentValues, Context, Intent}
import _root_.android.database.{Cursor, MatrixCursor}
import _root_.android.net.Uri
import _root_.android.os.{Handler, Looper, ParcelFileDescriptor}
import _root_.android.provider.OpenableColumns
import java.nio.file.{Files, Path}
import thicket.device.Availability
import thicket.renderer.android.ThicketActivity
import thicket.share.{Share, SharedFiles}

import scala.collection.mutable

/** Serves the files [[AndroidShare]] shares, read-only, to the apps they were shared with: Thicket's own, so an app
  * needs no AndroidX (owner's decision, 2026-11-01). Only copies under `<cache>/thicket-shared` are served, by their
  * `content://<package>.thicket.files/<id>/<name>` URIs; nothing else in the app is reachable through it.
  *
  * The app declares it in `AndroidManifest.xml`, inside `<application>`:
  * {{{
  * <provider android:name="thicket.share.android.ThicketFileProvider"
  *           android:authorities="${applicationId}.thicket.files"
  *           android:exported="false" android:grantUriPermissions="true" />
  * }}}
  * Not exported: an app reads a file only through the grant that comes with the share.
  */
final class ThicketFileProvider extends ContentProvider {

  import ThicketFileProvider.*

  private def shared: SharedFiles = SharedFiles(directory(getContext))

  def onCreate(): Boolean = true

  private def parts(uri: Uri): Option[(String, String)] =
    Option(uri.getPathSegments).map(s => (0 until s.size).map(s.get).toList) match {
      case Some(List(id, name)) => Some((id, name))
      case _                    => None
    }

  private def file(uri: Uri): Option[Path] =
    parts(uri).flatMap(
      (
        id,
        name
      ) => shared.resolve(id, name)
    )

  def getType(uri: Uri): String =
    parts(uri)
      .flatMap(
        (
          id,
          _
        ) => shared.mimeType(id)
      ).orNull

  def query(
    uri:           Uri,
    projection:    Array[String],
    selection:     String,
    selectionArgs: Array[String],
    sortOrder:     String
  ): Cursor =
    file(uri) match {
      case None    => null
      case Some(p) =>
        record(s"query ${p.getFileName}")
        val columns = Option(projection)
          .getOrElse(Array(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
          .filter(c => c == OpenableColumns.DISPLAY_NAME || c == OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map {
          case OpenableColumns.DISPLAY_NAME => p.getFileName.toString:                AnyRef
          case _                            => java.lang.Long.valueOf(Files.size(p)): AnyRef
        })
        cursor
    }

  override def openFile(
    uri:  Uri,
    mode: String
  ): ParcelFileDescriptor = {
    if mode != "r" then throw SecurityException(s"$uri is read-only")
    file(uri) match {
      case None    => throw java.io.FileNotFoundException(uri.toString)
      case Some(p) =>
        record(s"open ${p.getFileName}")
        ParcelFileDescriptor.open(p.toFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }
  }

  def insert(
    uri:    Uri,
    values: ContentValues
  ): Uri = throw UnsupportedOperationException("read-only")

  def update(
    uri:           Uri,
    values:        ContentValues,
    selection:     String,
    selectionArgs: Array[String]
  ): Int = throw UnsupportedOperationException("read-only")

  def delete(
    uri:           Uri,
    selection:     String,
    selectionArgs: Array[String]
  ): Int = throw UnsupportedOperationException("read-only")

  /** Who read what, for a self-test: the calling package and the request. */
  private def record(what: String): Unit = {
    val who = Option(getCallingPackage).getOrElse("?")
    reads.synchronized { val _ = reads += s"$who: $what" }
  }

}

object ThicketFileProvider {

  /** The authority the app's manifest gives the provider: `<package>.thicket.files`. */
  def authority(context: Context): String = s"${context.getPackageName}.thicket.files"

  /** Where shared copies live. */
  def directory(context: Context): Path = context.getCacheDir.toPath.resolve("thicket-shared")

  /** The `<provider>` element an app must declare, for the error that says so. */
  def manifestEntry(context: Context): String =
    """<provider android:name="thicket.share.android.ThicketFileProvider" """ +
      s"""android:authorities="${authority(context)}" android:exported="false" android:grantUriPermissions="true" />"""

  /** Each read the provider served this process, as "package: request", for a self-test to see who read a share. */
  val reads: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

}

/** Sharing through Android's chooser (`ACTION_SEND`), from the current [[ThicketActivity]].
  *
  *   - **Text** needs nothing.
  *   - **A file** is copied into the app's cache and served by [[ThicketFileProvider]], with a read grant for the app
  *     the user picks. The app must declare the provider (see there); a file shared without it throws
  *     `IllegalStateException` naming the element to add, as an undeclared permission does.
  *   - Copies older than a day are deleted at each share.
  */
final class AndroidShare(context: Context) extends Share {

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)
  private val main = Handler(Looper.getMainLooper)

  def availability: Availability = Availability.Available

  def text(
    text:    String,
    subject: Option[String]
  ): Unit = {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    subject.foreach(s => intent.putExtra(Intent.EXTRA_SUBJECT, s))
    choose(intent)
  }

  def file(
    path:     Path,
    mimeType: String
  ): Unit = {
    val authority = ThicketFileProvider.authority(app)
    if app.getPackageManager.resolveContentProvider(authority, 0) == null then
      throw IllegalStateException(
        s"sharing a file needs Thicket's file provider in AndroidManifest.xml: ${ThicketFileProvider.manifestEntry(app)}"
      )
    val t = Thread(() => {
      val shared = SharedFiles(ThicketFileProvider.directory(app))
      scala.util.Try(shared.prune())
      val (id, name) = shared.put(path, mimeType)
      val uri = Uri.Builder().scheme("content").authority(authority).appendPath(id).appendPath(name).build()
      val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      // The chooser shows a preview, and reads it through this grant.
      intent.setClipData(ClipData.newRawUri(name, uri))
      val _ = main.post(() => choose(intent))
    })
    t.setName("thicket-share")
    t.start()
  }

  private def choose(intent: Intent): Unit = {
    val chooser = Intent.createChooser(intent, null)
    ThicketActivity.current match {
      case Some(activity) => activity.startActivity(chooser)
      case None           =>
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(chooser)
    }
  }

}
