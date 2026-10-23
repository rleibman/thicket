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
  * **Known gaps, upstream:** in 0.0.7 JDBC batches (`addBatch` / `executeBatch`) and `setFetchSize` are not implemented
  * and throw `NotImplementedError`. `JdbcConformance.knownNativeFailures` lists the affected checks, so they show up
  * when upstream implements them. (0.0.6 also swallowed errors from a plain `executeUpdate` and a failed bind; 0.0.7
  * throws them.)
  */
object NativeSqlite {

  /** The driver, registered with `DriverManager` as well: Scala Native has no `ServiceLoader` to find it. */
  given driver: SqliteDriver = {
    val jdbc = new SQLiteDriver()
    java.sql.DriverManager.registerDriver(jdbc)
    SqliteDriver.fromJdbc(jdbc)
  }

}
