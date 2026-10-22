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

package thicket.sqlite

import thicket.storage.AppDirs

import java.nio.file.Path
import java.sql.{Connection, SQLException}

/** How a platform opens an SQLite connection: `NativeSqlite.driver` on GTK and Apple, `AndroidSqlite.driver` on
  * Android. The app passes it to [[Sqlite.open]], which is the same call everywhere.
  */
trait SqliteDriver {

  /** A connection for a `jdbc:sqlite:` URL: a file path, or `:memory:`. */
  def connect(url: String): Connection

}

object SqliteDriver {

  /** Any JDBC driver that accepts `jdbc:sqlite:` URLs — xerial's on the JVM, for tests. */
  def fromJdbc(driver: java.sql.Driver): SqliteDriver =
    url => {
      val c = driver.connect(url, new java.util.Properties())
      if c == null then throw new SQLException(s"the driver does not accept $url")
      c
    }

}

/** SQLite through plain JDBC (`docs/16-device-services.md` §16.5). What comes back is a `java.sql.Connection`; Thicket
  * adds no query layer.
  *
  * **The subset that works on every platform** is the one `testing.JdbcConformance` checks: `createStatement`,
  * `prepareStatement` (with and without generated keys), the typed `set*` and `get*` methods for `String`, `Int`,
  * `Long`, `Double`, `Float`, `Boolean`, `Array[Byte]` and `setNull`, by index and by label, `wasNull`, `setAutoCommit`
  * / `commit` / `rollback`, and `close`. Scala Native's `java.sql` has no `getObject` and no metadata.
  *
  * A connection is used from one thread at a time. Do database work off the UI thread — `ZIO.attemptBlocking`, or a
  * thread of the app's own.
  */
object Sqlite {

  /** Opens, creating if needed, the database `name` in the app's data directory. */
  def open(
    dirs: AppDirs,
    name: String
  )(using SqliteDriver
  ): Connection = {
    require(name.nonEmpty && !name.contains('/'), s"a database name, not a path: '$name'")
    open(dirs.data.resolve(name))
  }

  /** Opens, creating if needed, the database in `file`. */
  def open(file: Path)(using driver: SqliteDriver): Connection = driver.connect(s"jdbc:sqlite:$file")

  /** The database `name` in the app's data directory, as a `javax.sql.DataSource`: what libraries such as Magnum take.
    */
  def dataSource(
    dirs:         AppDirs,
    name:         String
  )(using driver: SqliteDriver
  ): javax.sql.DataSource = {
    require(name.nonEmpty && !name.contains('/'), s"a database name, not a path: '$name'")
    SqliteDataSource(s"jdbc:sqlite:${dirs.data.resolve(name)}", driver)
  }

  /** A database that lives as long as its connection: for tests and scratch work. */
  def memory()(using driver: SqliteDriver): Connection = driver.connect("jdbc:sqlite::memory:")

}
