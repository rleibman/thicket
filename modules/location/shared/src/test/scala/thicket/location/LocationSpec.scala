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

package thicket.location

import thicket.device.{AppState, Availability, Permission}
import thicket.device.testing.FakeLifecycle
import thicket.location.testing.FakeLocationProvider
import thicket.location.testing.FakeLocationProvider.fix
import thicket.signals.Owner
import zio.test.*

import scala.collection.mutable

/** `sequential` is mandatory: these tests track signals, and the tracking context is process-global. See `SignalSpec`.
  */
object LocationSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("Location")(
      test("updates: the last known position first, then each report, while in the foreground") {
        given Owner = Owner()
        val provider = FakeLocationProvider(lastKnown = Some(fix(1, 1)))
        val fixes = Location(provider, FakeLifecycle()).updates(Accuracy.Fine)
        val first = fixes.now
        provider.report(fix(2, 2))
        assertTrue(first.contains(fix(1, 1)), fixes.now.contains(fix(2, 2)), provider.listening == 1)
      },
      test("updates stop in the background, ignore what is reported there, and start again in front") {
        given Owner = Owner()
        val life = FakeLifecycle()
        val provider = FakeLocationProvider()
        val fixes = Location(provider, life).updates(Accuracy.Fine)
        provider.report(fix(1, 1))
        life.set(AppState.Background)
        val listeningAway = provider.listening
        provider.report(fix(2, 2))
        val away = fixes.now
        life.set(AppState.Foreground)
        val back = fixes.now
        provider.report(fix(3, 3))
        assertTrue(
          listeningAway == 0,
          away.contains(fix(1, 1)),
          // Back in front, it starts with where the platform last saw the device, then follows again.
          back.contains(fix(2, 2)),
          fixes.now.contains(fix(3, 3)),
          provider.starts == 2,
          provider.listening == 1
        )
      },
      test("updates stop when their owner is disposed") {
        val owner = Owner()
        given Owner = owner
        val provider = FakeLocationProvider()
        val _ = Location(provider, FakeLifecycle()).updates(Accuracy.Coarse)
        val before = provider.listening
        owner.dispose()
        assertTrue(before == 1, provider.listening == 0)
      },
      test("updates do not start while unavailable, and start at the next return to the front once available") {
        given Owner = Owner()
        val life = FakeLifecycle()
        val provider = FakeLocationProvider(availability = Availability.NeedsPermission(Permission.Location))
        val fixes = Location(provider, life).updates(Accuracy.Fine)
        val startsBefore = provider.starts
        provider.availability = Availability.Available
        life.set(AppState.Background)
        life.set(AppState.Foreground)
        provider.report(fix(4, 4))
        assertTrue(startsBefore == 0, provider.starts == 1, fixes.now.contains(fix(4, 4)))
      },
      test("current: the next report, once, and then it stops listening") {
        val provider = FakeLocationProvider()
        val got = mutable.ArrayBuffer.empty[Option[Fix]]
        val _ = Location(provider, FakeLifecycle()).current(Accuracy.Fine)(got += _)
        val listeningWhileWaiting = provider.listening
        provider.report(fix(5, 5))
        provider.report(fix(6, 6))
        assertTrue(listeningWhileWaiting == 1, got.toList == List(Some(fix(5, 5))), provider.listening == 0)
      },
      test("current: disposing it stops waiting; unavailable answers None at once without starting") {
        val provider = FakeLocationProvider()
        val got = mutable.ArrayBuffer.empty[Option[Fix]]
        val loc = Location(provider, FakeLifecycle())
        loc.current(Accuracy.Fine)(got += _).dispose()
        provider.report(fix(7, 7))
        provider.availability = Availability.Unavailable("off")
        val _ = loc.current(Accuracy.Fine)(got += _)
        assertTrue(got.toList == List(None), provider.listening == 0, provider.starts == 1)
      },
      test("Location.unavailable says why, and never has a position") {
        given Owner = Owner()
        val loc = Location.unavailable("no location service")
        var answer: Option[Option[Fix]] = None
        val _ = loc.current(Accuracy.Fine)(f => answer = Some(f))
        assertTrue(
          loc.availability == Availability.Unavailable("no location service"),
          loc.updates(Accuracy.Fine).now.isEmpty,
          answer.contains(None)
        )
      }
    ) @@ TestAspect.sequential

}
