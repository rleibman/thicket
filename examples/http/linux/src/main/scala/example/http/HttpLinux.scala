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

import thicket.http.gtk.LibcurlBackend

/** The HTTP self-test on Linux: [[HttpChecks]] over `thicket-http-gtk`'s `LibcurlBackend`, which is what a GTK app uses
  * (docs/16 §16.3). `THICKET_HTTPTEST=<port> http-linux`; no window, so the checks run on a worker as they would in an
  * app.
  */
object HttpLinux {

  def main(args: Array[String]): Unit = {
    val port =
      sys.env.get("THICKET_HTTPTEST").map(_.toInt).getOrElse(sys.error("set THICKET_HTTPTEST to the server's port"))
    val checks = HttpChecks(r => println(HttpChecks.line(r)))
    val worker = new Thread(
      () =>
        // For the GC check, one wait for the whole response: pollMs longer than it takes. With the default 50 ms the
        // thread would pass a safepoint between slices anyway, and the check could not tell the wait was Unmanaged.
        checks.run(port, LibcurlBackend(), parked = LibcurlBackend(pollMs = 5000)),
      "http-selftest"
    )
    worker.start()
    worker.join()
    checks.summary.foreach(println)
  }

}
