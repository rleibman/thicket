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

import thicket.storage.testing.MemoryStorage

import java.nio.file.Files

/** `Sqlite.dataSource`, the same on every platform: what each spec asks of its own driver. Returns what went wrong, so
  * an empty list is a pass and the spec can print the rest.
  */
object DataSourceRoundTrip {

  private def wrapsItself(ds: javax.sql.DataSource): Boolean =
    ds match {
      case s: SqliteDataSource =>
        s.isWrapperFor(classOf[SqliteDataSource]) && (s.unwrap(classOf[SqliteDataSource]) eq s)
      case _ => false
    }

  def problems(using SqliteDriver): List[String] = {
    val dirs = MemoryStorage().dirs
    val ds = Sqlite.dataSource(dirs, "ds.db")
    val first = ds.getConnection()
    try {
      first.createStatement().executeUpdate("create table t(name text)")
      val p = first.prepareStatement("insert into t(name) values (?)")
      p.setString(1, "written through one connection")
      p.executeUpdate()
      p.close()
    } finally first.close()
    // A second connection, through the credentials variant SQLite ignores, sees the first one's row: one file.
    val second = ds.getConnection("user", "ignored")
    val read =
      try {
        val rs = second.createStatement().executeQuery("select name from t")
        if rs.next() then Some(rs.getString(1)) else None
      } finally second.close()
    val rejectsPaths = scala.util.Try(Sqlite.dataSource(dirs, "nested/ds.db")).isFailure
    List(
      Option.when(read != Some("written through one connection"))(s"the second connection read $read"),
      Option.when(!Files.exists(dirs.data.resolve("ds.db")))(s"no ds.db under ${dirs.data}"),
      // On the concrete class: through `javax.sql.DataSource` these bind to the JDK's `java.sql.Wrapper`, which Scala
      // Native's library does not have, and the binary does not link.
      Option.when(!ds.isInstanceOf[SqliteDataSource])(s"a ${ds.getClass.getName}, not an SqliteDataSource"),
      Option.when(!wrapsItself(ds))("isWrapperFor / unwrap did not answer for itself"),
      Option.when(!rejectsPaths)("a name with a '/' was accepted")
    ).flatten
  }

}
