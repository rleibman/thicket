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

package example.http

import scala.scalanative.unsafe.exported
import thicket.core.GcState

/** The HTTP self-test on iOS. The Swift host in `ios-app/` owns `@main` and a plain window; it calls this once, on the
  * main thread, a run-loop turn after the scene connects.
  */
object HttpIos {

  /** The main thread checks the refusal, a worker runs the rest, and the main thread goes back to UIKit.
    *
    * `releaseMainThread` matters here even with no renderer: the main thread stays Managed for life after
    * `ScalaNativeInit`, and parked in UIKit's run loop it would never reach a safepoint, so the worker's first
    * collection would hang (S3). The renderer's `AppleApp.run` does this for a UI app.
    */
  @exported("thicket_http_start")
  def thicket_http_start(): Unit =
    GcState.guarded {
      sys.env.get("THICKET_HTTPTEST").map(_.toInt) match {
        case None =>
          println("THICKET_HTTPTEST is not set: launch with SIMCTL_CHILD_THICKET_HTTPTEST=<port>")
          println("1 CHECK(S) FAILED")
        case Some(port) =>
          HttpSelfTest.mainThread(port)
          new Thread(() => HttpSelfTest.run(port), "http-selftest").start()
      }
      GcState.releaseMainThread()
    }

}
