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

import java.io.PrintWriter
import java.sql.{Connection, SQLFeatureNotSupportedException}

/** A `javax.sql.DataSource` for SQLite, for libraries that take one — Magnum's `Transactor`, `connect` and `transact`
  * among them. Each `getConnection` opens a new connection to the same URL; for `:memory:` that is a new, empty
  * database every time, so use a file.
  *
  * On Scala Native, call `unwrap` and `isWrapperFor` on this class, not through `javax.sql.DataSource`: there they bind
  * to the JDK's `java.sql.Wrapper`, which Scala Native does not have, and the program does not link.
  */
final class SqliteDataSource(
  url:    String,
  driver: SqliteDriver
) extends javax.sql.DataSource {

  private var loginTimeout = 0
  private var logWriter: PrintWriter = null

  def getConnection(): Connection = driver.connect(url)

  // SQLite has no users; the credentials are ignored, as xerial's driver ignores them.
  def getConnection(
    username: String,
    password: String
  ): Connection = driver.connect(url)

  def getLogWriter():                        PrintWriter = logWriter
  def setLogWriter(out:        PrintWriter): Unit = logWriter = out
  def getLoginTimeout():                     Int = loginTimeout
  def setLoginTimeout(seconds: Int):         Unit = loginTimeout = seconds

  def getParentLogger(): java.util.logging.Logger = throw new SQLFeatureNotSupportedException("getParentLogger")

  def unwrap[T](iface: Class[T]): T =
    if iface.isInstance(this) then iface.cast(this) else throw new java.sql.SQLException(s"not a $iface")

  def isWrapperFor(iface: Class[?]): Boolean = iface.isInstance(this)

}
