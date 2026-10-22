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

import thicket.sqlite.testing.JdbcConformance
import zio.test.*

import java.nio.file.Files

/** The conformance checks against xerial's `sqlite-jdbc`, the reference: every one must pass. */
object JvmConformanceSpec extends ZIOSpecDefault {

  def spec =
    suite("JDBC conformance, xerial sqlite-jdbc (JVM)")(
      test("every check passes") {
        val driver = SqliteDriver.fromJdbc(new org.sqlite.JDBC())
        val results = JdbcConformance.run(driver, Files.createTempDirectory("thicket-conformance"))
        val failed = results.filterNot(_.passed)
        assertTrue(results.size >= 15, failed.isEmpty) ?? failed.map(r => s"${r.name}: ${r.detail}").mkString("\n")
      },
      test("Sqlite.open puts the database in the app's data directory") {
        given SqliteDriver = SqliteDriver.fromJdbc(new org.sqlite.JDBC())
        val dirs = thicket.storage.testing.MemoryStorage().dirs
        val c = Sqlite.open(dirs, "app.db")
        c.createStatement().executeUpdate("create table t(x)")
        c.close()
        assertTrue(Files.exists(dirs.data.resolve("app.db")))
      }
    )

}
