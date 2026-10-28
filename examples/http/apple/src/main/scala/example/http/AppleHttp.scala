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

import sttp.client4.*
import thicket.http.apple.URLSessionBackend

/** The HTTP self-test on macOS and iOS (#90): [[HttpChecks]] over `URLSessionBackend`, and the checks only it has. */
object AppleHttp {

  val checks = HttpChecks(r => println(HttpChecks.line(r)))

  /** Run on the main thread, before anything else: the backend must refuse to block it (under a self-test). */
  def mainThread(port: Int): Unit =
    checks.check("main thread refused") {
      val backend = URLSessionBackend()
      try {
        val r = basicRequest.get(uri"http://127.0.0.1:$port/hello").send(backend)
        (false, s"sent from the main thread and got ${r.code}")
      } catch {
        case e: IllegalStateException =>
          (e.getMessage.contains("main thread"), s"IllegalStateException: ${e.getMessage}")
      }
    }

  /** Every other check, on the calling thread, which must not be the main one. */
  def run(port: Int): Unit = {
    // For the GC check, one native wait for the whole request: pollMs longer than the response takes. Without
    // @blocking on thk_http_wait, the collection would wait for that thread to come back from Swift (~1.7 s).
    checks.run(port, URLSessionBackend(), parked = URLSessionBackend(pollMs = 5000))
    checks.check("no request left behind") {
      val live = URLSessionBackend.liveRequests
      (live == 0, s"$live request(s) still held by the Swift side")
    }
    checks.summary.foreach(println)
  }

}
