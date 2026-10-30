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

package thicket.device

import thicket.device.testing.{FakeLifecycle, FakePermissions}
import thicket.signals.{Owner, Signal}
import zio.test.*

import scala.collection.mutable

object DeviceSpec extends ZIOSpecDefault {

  import PermissionStatus.*

  def spec: Spec[Any, Nothing] =
    suite("Device")(
      suite("PermissionQueue")(
        test("one dialog at a time: the second request is asked only once the first is answered") {
          val asked = mutable.ArrayBuffer.empty[Permission]
          val got = mutable.ArrayBuffer.empty[(Permission, PermissionStatus)]
          val q = PermissionQueue(asked += _)
          q.request(Permission.Camera, s => got += Permission.Camera -> s)
          q.request(Permission.Location, s => got += Permission.Location -> s)
          val afterTwo = asked.toList
          q.answer(Denied)
          val afterOne = asked.toList
          q.answer(Granted)
          assertTrue(
            afterTwo == List(Permission.Camera),
            afterOne == List(Permission.Camera, Permission.Location),
            got.toList == List(Permission.Camera -> Denied, Permission.Location -> Granted),
            q.pending.isEmpty
          )
        },
        test("an answer given while asking, and a request made from inside an answer, both arrive in order") {
          val got = mutable.ArrayBuffer.empty[String]
          lazy val q: PermissionQueue = PermissionQueue(p => if p == Permission.Notifications then q.answer(Granted))
          q.request(
            Permission.Notifications,
            s => {
              got += s"notifications $s"
              q.request(Permission.Microphone, s2 => got += s"microphone $s2")
            }
          )
          val pending = q.pending
          q.answer(Restricted)
          assertTrue(
            got.toList == List("notifications Granted", "microphone Restricted"),
            pending.contains(Permission.Microphone)
          )
        },
        test("an answer with nothing pending is ignored") {
          val q = PermissionQueue(_ => ())
          q.answer(Granted)
          assertTrue(q.pending.isEmpty)
        },
        test("a request whose asking throws reaches its caller, and does not block the next") {
          val q = PermissionQueue(p => if p == Permission.Camera then throw IllegalStateException("not declared"))
          val thrown = scala.util.Try(q.request(Permission.Camera, _ => ())).failed.toOption.map(_.getMessage)
          q.request(Permission.Location, _ => ())
          assertTrue(thrown.contains("not declared"), q.pending.contains(Permission.Location))
        }
      ),
      test("Hooks run once, in order, without the removed one, and one that throws does not stop the rest") {
        val ran = mutable.ArrayBuffer.empty[Int]
        val reported = mutable.ArrayBuffer.empty[Throwable]
        val previous = thicket.signals.ErrorSink.installed
        thicket.signals.ErrorSink.install(reported += _)
        val hooks = Hooks()
        hooks.add(() => ran += 1)
        val second = hooks.add(() => ran += 2)
        hooks.add(() => throw RuntimeException("boom"))
        hooks.add(() => ran += 4)
        second.dispose()
        hooks.run()
        hooks.run()
        thicket.signals.ErrorSink.install(previous)
        assertTrue(ran.toList == List(1, 4), reported.map(_.getMessage).toList == List("boom"))
      },
      test("FakeLifecycle: an effect follows the state, and terminate runs the hooks") {
        val owner = Owner()
        given Owner = owner
        val life = FakeLifecycle()
        val seen = mutable.ArrayBuffer.empty[AppState]
        Signal.effect(seen += life.state())
        var closed = 0
        life.onTerminate(() => closed += 1)
        life.set(AppState.Background)
        life.set(AppState.Foreground)
        life.terminate()
        owner.dispose()
        assertTrue(
          seen.toList == List(AppState.Foreground, AppState.Background, AppState.Foreground),
          closed == 1
        )
      },
      test("FakePermissions: the user is asked once; a granted permission is not asked again") {
        val perms = FakePermissions(_ => Granted)
        val got = mutable.ArrayBuffer.empty[PermissionStatus]
        val before = perms.status(Permission.Location)
        perms.request(Permission.Location)(got += _)
        perms.request(Permission.Location)(got += _)
        assertTrue(
          before == Denied,
          got.toList == List(Granted, Granted),
          perms.asked.toList == List(Permission.Location)
        )
      }
    ) @@ TestAspect.sequential

}
