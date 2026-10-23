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

import com.augustnagro.magnum.*
import thicket.sqlite.testing.JdbcConformance.Result

import javax.sql.DataSource

/** A meal, as Magnum maps it: the table, its codec and its repository are all derived from the case class. */
@Table(SqliteDbType, SqlNameMapper.CamelToSnakeCase)
case class MagnumMeal(
  @Id id:  Long,
  name:    String,
  kcal:    Double,
  serves:  Int,
  veg:     Boolean,
  comment: Option[String]
) derives DbCodec

/** What an app writes to insert one: everything but the id, which SQLite assigns. */
case class MagnumMealCreator(
  name:    String,
  kcal:    Double,
  serves:  Int,
  veg:     Boolean,
  comment: Option[String]
) derives DbCodec

/** Magnum over the Scala Native SQLite driver (`docs/16-device-services.md` §16.5): the typed layer an app can use
  * without Thicket inventing one, here as `com.github.lolgab::magnum` 1.3.1 on GTK (and Apple, #98).
  *
  * Native only for now. The official Magnum 1.3.1 does not run on Android: it creates a `System.Logger` at the top
  * level of the file that holds `connect`, and Android has no `System.getLogger`. R8 refuses to build the APK, and past
  * that every call fails with `NoSuchMethodError`. Measured 2026-10-21 on the `s2test` emulator, with one line changed
  * in Magnum — that logger made a `lazy val`, as the Native build already has it — and `SqlLogger.NoOp`: all seven of
  * these pass over Thicket's Android driver. They move to shared code when Magnum has that change.
  */
object MagnumChecks {

  private val repo = Repo[MagnumMealCreator, MagnumMeal, Long]

  /** Every check, each on a freshly created table, through a [[Transactor]] that does not log every query. */
  def run(ds: DataSource): List[Result] = {
    val xa = Transactor(ds, SqlLogger.NoOp)
    def fresh(): Unit =
      connect(xa) {
        sql"drop table if exists magnum_meal".update.run()
        sql"""create table magnum_meal(id integer primary key, name text not null unique, kcal real not null,
              serves integer not null, veg integer not null, comment text)""".update.run()
      }
    def leek = MagnumMealCreator("Leek soup — ünïcödé, 'quoted'", 180.5, 4, veg = true, comment = None)
    def pie = MagnumMealCreator("Bob's pie", 640.0, 6, veg = false, comment = Some("with mash"))
    def insert(m: MagnumMealCreator)(using DbCon) =
      sql"""insert into magnum_meal(name, kcal, serves, veg, comment)
            values (${m.name}, ${m.kcal}, ${m.serves}, ${m.veg}, ${m.comment})""".update.run()

    val checks: List[(String, () => Either[String, String])] = List(
      "sql\"…\" binds its parameters and query[A] decodes a derived case class, Option included" -> { () =>
        fresh()
        connect(xa) {
          insert(leek)
          insert(pie)
          val min = 100.0
          val rows = sql"select * from magnum_meal where kcal > $min order by id".query[MagnumMeal].run()
          val want = Vector(
            MagnumMeal(1, leek.name, leek.kcal, leek.serves, leek.veg, None),
            MagnumMeal(2, pie.name, pie.kcal, pie.serves, pie.veg, Some("with mash"))
          )
          if rows == want then Right("ok") else Left(rows.toString)
        }
      },
      "Repo: insert, count, findById, update, deleteById, findAll" -> { () =>
        fresh()
        connect(xa) {
          repo.insert(leek)
          repo.insert(pie)
          val counted = repo.count
          val found = repo.findById(2L)
          found.foreach(m => repo.update(m.copy(kcal = 700.0)))
          repo.deleteById(1L)
          val all = repo.findAll
          if counted == 2 && found.exists(_.name == pie.name) && all.map(m => (m.id, m.kcal)) == Vector(2L -> 700.0)
          then Right("ok")
          else Left(s"count $counted, found $found, then $all")
        }
      },
      "Repo.insertReturning gives the new row, with the id SQLite assigned" -> { () =>
        fresh()
        connect(xa) {
          repo.insert(leek)
          val back = repo.insertReturning(pie)
          if back == MagnumMeal(2, pie.name, pie.kcal, pie.serves, pie.veg, pie.comment) then Right("ok")
          else Left(back.toString)
        }
      },
      "transact commits, and an exception inside it rolls back" -> { () =>
        fresh()
        transact(xa)(repo.insert(leek))
        val rolledBack =
          try {
            transact(xa) {
              repo.insert(pie)
              throw new IllegalStateException("deliberate")
            }
            false
          } catch { case _: IllegalStateException => true }
        val n = connect(xa)(repo.count)
        if rolledBack && n == 1 then Right("ok") else Left(s"rolled back $rolledBack, $n rows")
      },
      "a violated constraint surfaces as Magnum's SqlException" -> { () =>
        fresh()
        try {
          connect(xa) {
            repo.insert(leek)
            repo.insert(leek)
          }
          Left("no exception")
        } catch { case e: SqlException => Right(e.getMessage.linesIterator.next()) }
      },
      "Repo.insertAll inserts every row (a JDBC batch)" -> { () =>
        fresh()
        connect(xa) {
          repo.insertAll(List(leek, pie))
          val n = repo.count
          if n == 2 then Right("ok") else Left(s"$n rows")
        }
      },
      "query.iterator streams the rows (setFetchSize)" -> { () =>
        fresh()
        connect(xa) {
          insert(leek)
          insert(pie)
          val names = scala.util.Using.Manager { use =>
            given scala.util.Using.Manager = use
            sql"select * from magnum_meal order by id".query[MagnumMeal].iterator().map(_.name).toList
          }.get
          if names == List(leek.name, pie.name) then Right("ok") else Left(names.toString)
        }
      }
    )
    checks.map {
      (
        name,
        body
      ) =>
        val outcome =
          try body()
          catch {
            case e: Throwable => Left(s"${e.getClass.getName}: ${Option(e.getMessage).map(_.linesIterator.next())}")
          }
        Result(name, outcome.isRight, outcome.fold(identity, identity))
    }
  }

  /** What the Scala Native build fails, 2026-10-22: `scala-native-jdbc-sqlite` 0.0.7 leaves JDBC batches and
    * `setFetchSize` unimplemented (`???`).
    */
  val knownNativeFailures: Set[String] = Set(
    "Repo.insertAll inserts every row (a JDBC batch)",
    "query.iterator streams the rows (setFetchSize)"
  )

}
