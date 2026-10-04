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

package thicket.signals

import zio.test.*

/** The single-threaded contract (A-06). JVM and Native only; JS has no threads.
  *
  * `ThreadGuard.install` is process-global, so these must not overlap with anything else touching the graph — hence
  * `sequential`, and the reset after each test.
  */
object ThreadGuardSpec extends ZIOSpecDefault {

  def spec =
    suite("ThreadGuard")(
      test("writing from another thread is rejected") {
        val chk = Checks()
        ThreadGuard.install(ThreadGuard.owningThread)
        try {
          val a = Var(0)
          a.set(1) // binds the guard to this thread
          chk.eq(a.now, 1)

          @volatile var caught: Option[Throwable] = None
          val t = new Thread(() => caught = scala.util.Try(a.set(2)).failed.toOption)
          t.start()
          t.join()

          caught match {
            case Some(e: IllegalStateException) =>
              chk.yes(e.getMessage.contains("single-threaded"), e.getMessage)
            case other =>
              chk.yes(false, s"expected IllegalStateException, got $other")
          }
          chk.eq(a.now, 1, "the rejected write must not have taken effect")
        } finally ThreadGuard.install(ThreadGuard.off)
        chk.result
      },
      test("ThreadGuard.off allows cross-thread writes") {
        val chk = Checks()
        ThreadGuard.install(ThreadGuard.off)
        val a = Var(0)
        val t = new Thread(() => a.set(42))
        t.start()
        t.join()
        chk.eq(a.now, 42)
        chk.result
      }
    ) @@ TestAspect.sequential

}
