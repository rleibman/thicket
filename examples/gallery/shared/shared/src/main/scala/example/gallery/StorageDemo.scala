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

import thicket.core.UiThread
import thicket.device.Device
import thicket.location.Location
import thicket.motion.Motion
import thicket.clipboard.Clipboard
import thicket.haptics.Haptics
import thicket.pickers.{Documents, Photos}
import thicket.camera.Camera
import thicket.share.Share
import thicket.secrets.Secrets
import thicket.signals.Var
import thicket.sqlite.{Sqlite, SqliteDriver}
import thicket.storage.Storage

import java.sql.Connection
import java.util.concurrent.{Executors, ThreadFactory}

/** The device services a host provides to the gallery (`docs/16-device-services.md`). A host that has none passes
  * `None`, and the section says so; every host has storage and SQLite today.
  *
  * @param secrets
  *   opens the platform's secure store, or says why there is none (no Secret Service on this desktop, or not built
  *   yet). A function, because opening may block: the gallery calls it on a thread of its own.
  * @param device
  *   the app's lifecycle and permissions, where the host has them (GTK and Android since `docs/16` §16.10 item 3).
  * @param location
  *   the device's position: Android's `LocationManager`, iOS's `CLLocationManager`; on GTK and macOS,
  *   `Location.unavailable` (`docs/16` §16.10 item 4).
  * @param motion
  *   the motion sensors: Android's `SensorManager`; on GTK, `Motion.unavailable` (`docs/16` §16.10 item 4).
  * @param clipboard
  *   the system clipboard: `GdkClipboard`, Android's `ClipboardManager`, `NSPasteboard` and `UIPasteboard` (`docs/16`
  *   §16.10 item 5, #123).
  * @param haptics
  *   haptic taps: Android's `performHapticFeedback`, Apple's feedback generators; on GTK, `Haptics.none` (`docs/16`
  *   §16.10 item 5).
  * @param pickers
  *   the photo picker and the document picker and save dialog (`docs/16` §16.10 item 5).
  * @param camera
  *   taking a photo with the platform's camera screen: Android's camera app; on GTK, `Camera.unavailable`.
  * @param share
  *   the share sheet: Android's chooser, files served by Thicket's own provider; on GTK, `Share.unavailable`.
  */
final case class Services(
  storage:   Storage,
  sqlite:    SqliteDriver,
  secrets:   () => Either[String, Secrets] = () => Left("not on this platform yet"),
  device:    Option[Device] = None,
  location:  Option[Location] = None,
  motion:    Option[Motion] = None,
  clipboard: Option[Clipboard] = None,
  haptics:   Option[Haptics] = None,
  pickers:   Option[(Photos, Documents)] = None,
  camera:    Option[Camera] = None,
  share:     Option[Share] = None
)

/** The Storage section's state: a launch counter in preferences, and a table of notes in SQLite.
  *
  * Preferences are read on the UI thread, which is what they are for. The database is not: one worker thread owns the
  * connection — Android ties a transaction to the thread that began it — and posts each result back through `UiThread`,
  * as any app's database code should.
  */
final class StorageDemo(services: Services) {

  /** How many times the gallery has started, this start included. */
  val opens: Long = {
    val p = services.storage.preferences
    val n = p.getLong(StorageDemo.opensKey).getOrElse(0L) + 1
    p.putLong(StorageDemo.opensKey, n)
    n
  }

  /** The number of notes, once the worker has counted them; an error's message if the database failed. */
  val notes: Var[Either[String, Int]] = Var(Left("Opening the database…"))

  private val worker = Executors.newSingleThreadExecutor(new ThreadFactory {
    def newThread(r: Runnable): Thread = {
      val t = new Thread(r, "gallery-sqlite")
      t.setDaemon(true)
      t
    }
  })

  lazy private val connection: Connection = {
    val c = Sqlite.open(services.storage.dirs, "gallery.db")(using services.sqlite)
    val s = c.prepareStatement("create table if not exists note(id integer primary key, text text not null)")
    try s.executeUpdate()
    finally s.close()
    c
  }

  private def count(): Int = {
    val s = connection.prepareStatement("select count(*) from note")
    try {
      val rs = s.executeQuery()
      rs.next()
      rs.getInt(1)
    } finally s.close()
  }

  private def onWorker(work: => Int): Unit =
    worker.execute { () =>
      val result =
        try Right(work)
        catch { case e: Exception => Left(s"SQLite failed: ${e.getMessage}") }
      UiThread.run(() => notes.set(result))
    }

  onWorker(count())

  /** What became of the gallery's secret this run, once its own thread has asked the store: [[StorageDemo.secretKept]],
    * [[StorageDemo.secretStored]], or why there is no store or it failed. The secret itself is never shown.
    */
  val secret: Var[String] = Var(StorageDemo.secretPending)

  // A thread of its own, not the database worker: a keyring that is slow to answer — D-Bus
  // gives up on an absent one after 25 s — must not hold up the notes.
  private val secretThread = new Thread(
    () => {
      val result =
        services.secrets() match {
          case Left(reason) => s"Secrets unavailable: $reason"
          case Right(s)     =>
            try
              s.get(StorageDemo.secretKey) match {
                case Some(_) => StorageDemo.secretKept
                case None    =>
                  s.set(StorageDemo.secretKey, s"${StorageDemo.secretPrefix}${System.currentTimeMillis()}")
                  StorageDemo.secretStored
              }
            catch { case e: Exception => s"Secrets failed: ${e.getMessage}" }
        }
      UiThread.run(() => secret.set(result))
    },
    "gallery-secrets"
  )
  secretThread.setDaemon(true)
  secretThread.start()

  /** Adds a note on the worker; the count updates when it is done. */
  def addNote(): Unit =
    onWorker {
      val s = connection.prepareStatement("insert into note(text) values (?)")
      try {
        s.setString(1, s"Note written at ${System.currentTimeMillis()}")
        s.executeUpdate()
      } finally s.close()
      count()
    }

}

object StorageDemo {

  val opensKey: String = "gallery.opens"

  /** The gallery's one secret, made on the first run and read back on every later one. A labelled timestamp, not a
    * token: a demo has nothing to protect, and Scala Native has no `SecureRandom` to make a real one with.
    */
  val secretKey: String = "gallery.secret"

  val secretPrefix: String = "gallery secret, stored at "

  val secretPending: String = "Asking the secure store…"
  val secretKept:    String = "A secret kept from an earlier run"
  val secretStored:  String = "A secret stored, for the next run to find"

  /** The label the notes count is shown in, so a self-test can wait for a given count. */
  def notesLabel(n: Int): String = s"$n notes in SQLite"

}
