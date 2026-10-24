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
import thicket.signals.Var
import thicket.sqlite.{Sqlite, SqliteDriver}
import thicket.storage.Storage

import java.sql.Connection
import java.util.concurrent.{Executors, ThreadFactory}

/** The device services a host provides to the gallery: storage and SQLite so far (`docs/16-device-services.md`). A host
  * that has none yet — the Apple ones, until their half is built — passes `None`, and the section says so.
  */
final case class Services(
  storage: Storage,
  sqlite:  SqliteDriver
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

  /** The label the notes count is shown in, so a self-test can wait for a given count. */
  def notesLabel(n: Int): String = s"$n notes in SQLite"

}
