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

import com.github.lolgab.jdbc.sqlite.SQLiteDriver

/** SQLite on Scala Native (GTK, macOS, iOS), through `com.github.lolgab::scala-native-jdbc-sqlite`.
  *
  * **Known defect, upstream:** in 0.0.6 a failed plain `Statement.executeUpdate` and a failed parameter bind are not
  * reported — the library builds the `SQLException` and does not throw it — so invalid SQL or a violated constraint in
  * an `executeUpdate` reads as success. Errors while a statement runs (a constraint violated by a `PreparedStatement`)
  * and errors in a query (at the first `next()`) are reported. `JdbcConformance` lists the affected checks as known
  * failures on this driver, so they show up when it is fixed. Until then, check what you send: prefer prepared
  * statements, and test your SQL on the JVM.
  */
object NativeSqlite {

  /** The driver, registered with `DriverManager` as well: Scala Native has no `ServiceLoader` to find it. */
  given driver: SqliteDriver = {
    val jdbc = new SQLiteDriver()
    java.sql.DriverManager.registerDriver(jdbc)
    SqliteDriver.fromJdbc(jdbc)
  }

}
