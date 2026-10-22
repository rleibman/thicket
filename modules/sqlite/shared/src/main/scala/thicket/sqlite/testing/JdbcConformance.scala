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

package thicket.sqlite.testing

import thicket.sqlite.SqliteDriver

import java.nio.file.{Files, Path}
import java.sql.{Connection, SQLException, Statement}

/** What "the same JDBC code runs on every platform" means, as checks: run against xerial's driver on the JVM, the Scala
  * Native driver, and Thicket's Android driver on the emulator. A driver passes when every check passes, or when the
  * ones that fail are exactly the ones it is known to fail — so a fix upstream shows up as a change, not silently.
  *
  * Plain code rather than a test framework's, so the Android gallery can run it in its self-test, where no test runner
  * is. Uses only the JDBC subset `thicket.sqlite.Sqlite` documents; that is the point.
  */
object JdbcConformance {

  final case class Result(
    name:   String,
    passed: Boolean,
    detail: String
  )

  /** Every check, each on its own fresh database. `scratch` is a directory the checks may create files in. */
  def run(
    driver:  SqliteDriver,
    scratch: Path
  ): List[Result] = {
    Files.createDirectories(scratch)
    checks.zipWithIndex.map { case ((name, body), i) =>
      val f = scratch.resolve(s"conformance-$i.db")
      val url = s"jdbc:sqlite:$f"
      val outcome =
        try {
          Files.deleteIfExists(f)
          val c = driver.connect(url)
          try body(c, () => driver.connect(url))
          finally c.close()
        } catch { case e: Throwable => Left(s"${e.getClass.getName}: ${e.getMessage}") }
      Result(name, outcome.isRight, outcome.fold(identity, identity))
    }
  }

  /** The checks the Scala Native driver (`scala-native-jdbc-sqlite` 0.0.6) fails, measured 2026-10-21: it builds an
    * `SQLException` for a failed `Statement.executeUpdate` and a failed bind, and does not throw it. A failed prepare
    * is not thrown either, but surfaces as an error at the first `next()`, so that check passes.
    */
  val knownNativeFailures: Set[String] = Set(
    "invalid SQL in a plain executeUpdate throws",
    "a constraint violated by a plain executeUpdate throws",
    "binding a parameter index that does not exist throws",
    // Not implemented (`???`) in 0.0.6; Magnum's insertAll, updateAll, deleteAll and iterator use them.
    "a batch runs each set of parameters (addBatch, executeBatch)",
    "setFetchSize is accepted as a hint"
  )

  /** A check gets a connection to a new database, and a way to open a second connection to the same file. */
  private type Check = (Connection, () => Connection) => Either[String, String]

  private def expect(
    cond:   Boolean,
    detail: => String
  ): Either[String, String] =
    if cond then Right("ok") else Left(detail)

  private def throws(body: => Any): Either[String, String] =
    try {
      body
      Left("no exception")
    } catch {
      case e: SQLException => Right(e.getMessage)
    }

  /** Any exception: for a programming error, where what matters is that it is not silently accepted. xerial, the
    * reference, throws `ArrayIndexOutOfBoundsException` for a parameter index that does not exist.
    */
  private def throwsAnything(body: => Any): Either[String, String] =
    try {
      body
      Left("no exception")
    } catch {
      case e: Exception => Right(s"${e.getClass.getSimpleName}: ${e.getMessage}")
    }

  private def meals(c: Connection): Unit = {
    val s = c.createStatement()
    try
      s.executeUpdate(
        "create table meal(id integer primary key, name text not null unique, kcal real, servings integer, " +
          "big integer, veg integer, ratio real, photo blob)"
      )
    finally s.close()
  }

  private def count(
    c:     Connection,
    table: String
  ): Int = {
    val s = c.createStatement()
    try {
      val rs = s.executeQuery(s"select count(*) from $table")
      rs.next()
      rs.getInt(1)
    } finally s.close()
  }

  private val checks: List[(String, Check)] = List(
    "a plain Statement creates a table and inserts, reporting one row" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val s = c.createStatement()
        val n = s.executeUpdate("insert into meal(name, kcal) values ('Leek soup', 180.5)")
        s.close()
        expect(n == 1 && count(c, "meal") == 1, s"executeUpdate returned $n, count ${count(c, "meal")}")
    },
    "every supported type round-trips through a PreparedStatement, by index and by label" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val photo = Array[Byte](0, 1, -1, 127, -128, 0)
        val name = "Bob's \"quoted\" pie — ünïcödé 🌿"
        val p = c.prepareStatement(
          "insert into meal(name, kcal, servings, big, veg, ratio, photo) values (?, ?, ?, ?, ?, ?, ?)"
        )
        p.setString(1, name)
        p.setDouble(2, 0.1 + 0.2)
        p.setInt(3, Int.MinValue)
        p.setLong(4, Long.MaxValue)
        p.setBoolean(5, true)
        p.setFloat(6, 1.5f)
        p.setBytes(7, photo)
        p.executeUpdate()
        p.close()
        val q = c.prepareStatement("select name, kcal, servings, big, veg, ratio, photo from meal where name = ?")
        q.setString(1, name)
        val rs = q.executeQuery()
        val found = rs.next()
        val byIndex = (
          rs.getString(1),
          rs.getDouble(2),
          rs.getInt(3),
          rs.getLong(4),
          rs.getBoolean(5),
          rs.getFloat(6),
          rs.getBytes(7).toSeq
        )
        val byLabel = (
          rs.getString("name"),
          rs.getDouble("kcal"),
          rs.getInt("servings"),
          rs.getLong("big"),
          rs.getBoolean("veg"),
          rs.getFloat("ratio"),
          rs.getBytes("photo").toSeq
        )
        val want = (name, 0.1 + 0.2, Int.MinValue, Long.MaxValue, true, 1.5f, photo.toSeq)
        val more = rs.next()
        q.close()
        expect(found && !more && byIndex == want && byLabel == want, s"found=$found more=$more\n $byIndex\n $byLabel")
    },
    "setNull stores NULL; getters return null or 0 and wasNull says so" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val p = c.prepareStatement("insert into meal(name, kcal, photo) values (?, ?, ?)")
        p.setString(1, "nothing")
        p.setNull(2, 0) // java.sql.Types.NULL; Scala Native's java.sql has no Types
        p.setNull(3, 0)
        p.executeUpdate()
        p.close()
        val s = c.createStatement()
        val rs = s.executeQuery("select kcal, photo, name from meal")
        rs.next()
        val d = rs.getDouble(1)
        val dNull = rs.wasNull()
        val b = rs.getBytes(2)
        val bNull = rs.wasNull()
        rs.getString(3)
        val notNull = !rs.wasNull()
        s.close()
        expect(
          d == 0.0 && dNull && b == null && bNull && notNull,
          s"d=$d/$dNull bytes=$b/$bNull name-not-null=$notNull"
        )
    },
    "a large text value round-trips" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val text = "x" * 200000
        val p = c.prepareStatement("insert into meal(name) values (?)")
        p.setString(1, text)
        p.executeUpdate()
        p.close()
        val s = c.createStatement()
        val rs = s.executeQuery("select name from meal")
        rs.next()
        val back = rs.getString(1)
        s.close()
        expect(back == text, s"length ${Option(back).map(_.length)}")
    },
    "an update reports how many rows it changed" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val s = c.createStatement()
        s.executeUpdate("insert into meal(name, kcal) values ('a', 1), ('b', 2), ('c', 3)")
        s.close()
        val p = c.prepareStatement("update meal set kcal = kcal * 10 where kcal >= ?")
        p.setInt(1, 2)
        val n = p.executeUpdate()
        p.close()
        expect(n == 2, s"reported $n")
    },
    "a batch runs each set of parameters (addBatch, executeBatch)" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val p = c.prepareStatement("insert into meal(name, kcal) values (?, ?)")
        Seq("a" -> 1.0, "b" -> 2.0, "c" -> 3.0).foreach {
          (
            n,
            k
          ) =>
            p.setString(1, n)
            p.setDouble(2, k)
            p.addBatch()
        }
        val counts = p.executeBatch().toList
        p.close()
        expect(counts == List(1, 1, 1) && count(c, "meal") == 3, s"counts $counts, rows ${count(c, "meal")}")
    },
    "setFetchSize is accepted as a hint" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val p = c.prepareStatement("select * from meal")
        p.setFetchSize(100)
        val any = p.executeQuery().next()
        p.close()
        expect(!any, "a row in an empty table")
    },
    "an empty result has no rows" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val s = c.createStatement()
        val rs = s.executeQuery("select * from meal")
        val any = rs.next()
        s.close()
        expect(!any, "a row in an empty table")
    },
    "RETURN_GENERATED_KEYS gives the new row's id" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val s = c.createStatement()
        s.executeUpdate("insert into meal(name) values ('first'), ('second')")
        s.close()
        val p = c.prepareStatement("insert into meal(name) values (?)", Statement.RETURN_GENERATED_KEYS)
        p.setString(1, "third")
        p.executeUpdate()
        val keys = p.getGeneratedKeys
        val has = keys.next()
        val id = if has then keys.getLong(1) else -1L
        p.close()
        expect(has && id == 3L, s"has=$has id=$id")
    },
    "autoCommit is on by default; commit keeps, rollback discards" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val auto = c.getAutoCommit
        c.setAutoCommit(false)
        val p = c.prepareStatement("insert into meal(name) values (?)")
        p.setString(1, "kept")
        p.executeUpdate()
        c.commit()
        p.setString(1, "discarded")
        p.executeUpdate()
        c.rollback()
        p.close()
        c.setAutoCommit(true)
        val s = c.createStatement()
        val rs = s.executeQuery("select name from meal order by id")
        val names = Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
        s.close()
        expect(auto && names == List("kept"), s"autoCommit default $auto, rows $names")
    },
    "a committed write is seen by another connection to the same file" -> {
      (
        c,
        reopen
      ) =>
        meals(c)
        val p = c.prepareStatement("insert into meal(name) values (?)")
        p.setString(1, "shared")
        p.executeUpdate()
        p.close()
        val other = reopen()
        try {
          val n = count(other, "meal")
          expect(n == 1, s"the other connection sees $n rows")
        } finally other.close()
    },
    "a constraint violated by a PreparedStatement throws" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val p = c.prepareStatement("insert into meal(name) values (?)")
        p.setNull(1, 0)
        val r = throws(p.executeUpdate())
        p.close()
        r
    },
    "invalid SQL in a plain executeQuery throws by the first next()" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val s = c.createStatement()
        val r = throws(s.executeQuery("select nope from meal").next())
        s.close()
        r
    },
    "invalid SQL in a plain executeUpdate throws" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val s = c.createStatement()
        val r = throws(s.executeUpdate("insert into nowhere values (1)"))
        s.close()
        r
    },
    "a constraint violated by a plain executeUpdate throws" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val s = c.createStatement()
        s.executeUpdate("insert into meal(name) values ('dup')")
        val r = throws(s.executeUpdate("insert into meal(name) values ('dup')"))
        s.close()
        r
    },
    "invalid SQL in prepareStatement throws by the first execute" -> {
      (
        c,
        _
      ) =>
        meals(c)
        throws {
          val p = c.prepareStatement("selec nothing")
          try p.executeQuery().next()
          finally p.close()
        }
    },
    "binding a parameter index that does not exist throws" -> {
      (
        c,
        _
      ) =>
        meals(c)
        val p = c.prepareStatement("insert into meal(name) values (?)")
        val r = throwsAnything(p.setString(2, "no such parameter"))
        p.close()
        r
    }
  )

}
