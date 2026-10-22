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

/** The conformance checks against the Scala Native driver. It passes when the checks that fail are exactly
  * [[JdbcConformance.knownNativeFailures]]: when upstream fixes them, this fails, and the known set shrinks.
  */
object NativeConformanceSpec extends ZIOSpecDefault {

  def spec =
    suite("JDBC conformance, scala-native-jdbc-sqlite (Scala Native)")(
      test("the failing checks are exactly the known upstream defects") {
        val results = JdbcConformance.run(NativeSqlite.driver, Files.createTempDirectory("thicket-conformance"))
        val failed = results.filterNot(_.passed).map(_.name).toSet
        assertTrue(results.size >= 17, failed == JdbcConformance.knownNativeFailures) ??
          results.map(r => s"${if r.passed then "ok  " else "FAIL"} ${r.name}: ${r.detail}").mkString("\n")
      },
      test("Sqlite.open and Sqlite.memory work with the given driver") {
        import NativeSqlite.given
        val dirs = thicket.storage.testing.MemoryStorage().dirs
        val c = Sqlite.open(dirs, "app.db")
        c.createStatement().executeUpdate("create table t(x)")
        c.close()
        val m = Sqlite.memory()
        m.createStatement().executeUpdate("create table t(x)")
        m.close()
        assertTrue(Files.exists(dirs.data.resolve("app.db")))
      }
    )

}
