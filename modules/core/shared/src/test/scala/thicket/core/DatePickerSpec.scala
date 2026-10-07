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

package thicket.core

import thicket.core.dsl.*
import thicket.renderer.{CalendarDate, WidgetKind}
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `DatePicker` and the `CalendarDate` it carries.
  *
  * Most of the risk is in the date itself, not the widget: it crosses the Apple boundary as an epoch day, so a wrong
  * conversion is a picker that shows the day before, and only in some years. Hence a round trip over every day of four
  * centuries rather than a handful of examples.
  */
object DatePickerSpec extends ZIOSpecDefault {

  def spec =
    suite("DatePicker")(
      test("the epoch is day zero, and known dates land where they should") {
        val chk = Checks()
        chk.eq(CalendarDate(1970, 1, 1).toEpochDay, 0)
        chk.eq(CalendarDate(1969, 12, 31).toEpochDay, -1)
        chk.eq(CalendarDate(2000, 3, 1).toEpochDay, 11017)
        // A leap day in a century year that *is* a leap year, the case a /4 /100 slip breaks.
        chk.eq(CalendarDate(2000, 2, 29).toEpochDay, 11016)
        chk.eq(CalendarDate(2026, 10, 6).toEpochDay, 20732)
        chk.result
      },
      test("every day from 1800 to 2200 survives the trip through an epoch day") {
        val chk = Checks()
        val from = CalendarDate(1800, 1, 1).toEpochDay
        val to = CalendarDate(2200, 12, 31).toEpochDay
        // Every day, consecutive: a conversion that skipped or repeated one would break the
        // second check even if the round trip held.
        var bad = List.empty[Int]
        var prev = CalendarDate.fromEpochDay(from - 1)
        var d = from
        while d <= to do {
          val c = CalendarDate.fromEpochDay(d)
          if c.toEpochDay != d || !follows(prev, c) then bad = d :: bad
          prev = c
          d += 1
        }
        chk.yes(bad.isEmpty, bad.take(5).map(CalendarDate.fromEpochDay).toString)
        chk.eq(to - from + 1, 146462, "days in 1800-2200")
        chk.result
      },
      test("an impossible date is refused, not rolled over") {
        val chk = Checks()
        // Rolling 2023-02-29 over to March 1 is what Java's lenient calendar did, and it is
        // how a picker ends up showing a date nobody chose.
        chk.yes(scala.util.Try(CalendarDate(2023, 2, 29)).isFailure)
        chk.yes(scala.util.Try(CalendarDate(2024, 2, 29)).isSuccess)
        chk.yes(scala.util.Try(CalendarDate(2024, 13, 1)).isFailure)
        chk.yes(scala.util.Try(CalendarDate(2024, 0, 1)).isFailure, "months are 1-12, not 0-11")
        chk.result
      },
      test("the widget carries its date, and a choice reaches the app") {
        val o = Owner(); given Owner = o
        val chk = Checks()
        val r = TestRenderer()
        val due = Var(CalendarDate(2026, 10, 6))
        val m = Reconciler.mount(r, DatePicker(due)(due.set))

        chk.eq(r.kind(m.handle), WidgetKind.DatePicker)
        chk.eq(r.nodes(m.handle).props("date"), "2026-10-06")
        r.nodes(m.handle).onDateChange.foreach(_(CalendarDate(2027, 1, 15)))
        chk.eq(due.now, CalendarDate(2027, 1, 15))
        chk.eq(r.nodes(m.handle).props("date"), "2027-01-15", "and the written-back value is shown")
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

  private def follows(
    a: CalendarDate,
    b: CalendarDate
  ): Boolean =
    if b.day > 1 then a.year == b.year && a.month == b.month && a.day == b.day - 1
    else if b.month > 1 then a.year == b.year && a.month == b.month - 1 && a.day == CalendarDate.daysIn(a.year, a.month)
    else a.year == b.year - 1 && a.month == 12 && a.day == 31

}
