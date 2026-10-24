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

package thicket.tools.shim

import thicket.tools.shim.Abi.{Fn, Group}
import thicket.tools.shim.Abi.CType.*

/** The `thk_http_*` ABI: `thicket-http-apple`'s Swift library over `URLSession` (#90, docs/16 §16.4).
  *
  * A separate surface from [[Abi]], with its own header, its own Scala externs and its own Swift library, because it is
  * a separate module: an app that does not use HTTP must not link it, so none of it is in `renderer-apple`.
  *
  * **The shape is pull, never push.** `URLSession` completes on its own delegate queue, a thread Scala Native's GC does
  * not know about, so nothing here calls into Scala. Scala starts a request, blocks in [[wait]] until it is done (or a
  * slice of time passes), and then reads the results out. There is no callback type in this surface, and no struct.
  *
  * A request is an `int64_t` id into a table the Swift side keeps, not a pointer: an id that was freed, or never
  * existed, is answered with an empty result rather than a dereference.
  */
object HttpAbi {

  private val req = Abi.p("request", I64)

  val requests: List[Fn] = List(
    Fn(
      "thk_http_send",
      I64,
      List(
        Abi.p("method", Str),
        Abi.p("url", Str),
        Abi.p("headers", Str),
        Abi.p("body", Bytes),
        Abi.p("bodyLength", I64),
        Abi.p("timeoutMs", I32)
      ),
      doc = "Starts a request and returns its id at once. `headers` is \"Name: value\" lines separated by\n" +
        "   \\n; a name may repeat. `body` may be NULL when `bodyLength` is 0, and is copied before this\n" +
        "   returns. `timeoutMs` is URLSession's idle timeout, 0 for its default. Every id must be freed."
    ),
    Fn(
      "thk_http_wait",
      I32,
      List(req, Abi.p("waitMs", I32)),
      doc = "Blocks up to `waitMs` for the request to finish: 1 finished (or failed), 0 still running,\n" +
        "   -1 no such request. Scala marks the call blocking for the GC, so it may park for as long as it likes.",
      blocking = true
    ),
    Fn(
      "thk_http_cancel",
      Void,
      List(req),
      doc = "Cancels the URLSession task. The request then finishes with error 4 (cancelled)."
    ),
    Fn(
      "thk_http_free",
      Void,
      List(req),
      doc = "Forgets the request and everything it holds; its pointers die with it."
    )
  )

  val results: List[Fn] = List(
    Fn("thk_http_status", I32, List(req), doc = "The HTTP status code, or 0 if no response arrived."),
    Fn(
      "thk_http_headers",
      Str,
      List(req),
      doc = "The response's headers as \"Name: value\" lines separated by \\n. Foundation joins a repeated\n" +
        "   header into one line with \", \"; Set-Cookie is split back apart here. Owned by the request."
    ),
    Fn("thk_http_body_length", I64, List(req)),
    Fn(
      "thk_http_body",
      Bytes,
      List(req),
      doc = "The response body, owned by the request. NULL when it is empty."
    ),
    Fn(
      "thk_http_error",
      I32,
      List(req),
      doc = "0 none, 1 connect (the request was not sent), 2 read, 3 timeout, 4 cancelled, 5 the request\n" +
        "   could not be built (a malformed URL)."
    ),
    Fn("thk_http_error_message", Str, List(req), doc = "The platform's description of the error, or \"\".")
  )

  val inspection: List[Fn] = List(
    Fn(
      "thk_http_on_main_thread",
      I32,
      Nil,
      doc = "1 on the process's main thread. The backend refuses to block it under a self-test."
    ),
    Fn(
      "thk_http_live",
      I32,
      Nil,
      doc = "How many requests have not been freed, for a self-test's leak check."
    )
  )

  val groups: List[Group] = List(
    Group("requests", requests),
    Group("results", results, doc = "Valid once thk_http_wait has returned 1, until thk_http_free."),
    Group("inspection", inspection)
  )

  val all: List[Fn] = groups.flatMap(_.fns)

}
