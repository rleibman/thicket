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

package thicket.http.gtk

import sttp.client4.*
import sttp.model.{Header, StatusCode}
import zio.test.*

/** What can be checked without a server: the header block curl writes, the error codes, and that libcurl loads. The
  * exchanges themselves — bodies, timeouts, cancellation, the GC — are `examples/http`'s checks, which CI runs against
  * `LibcurlBackend` with `examples/http/selftest.sh linux`.
  */
object LibcurlBackendSpec extends ZIOSpecDefault {

  def spec =
    suite("LibcurlBackend")(
      test("the last response's status and headers, after an interim 100 Continue") {
        val raw =
          "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 Created\r\nX-A: 1\r\nX-A: 2\r\nSet-Cookie: a=1; Path=/\r\n\r\n"
        val (code, text, headers) = LibcurlBackend.parseHead(raw)
        assertTrue(
          code == StatusCode.Created,
          text == "Created",
          headers == List(Header("X-A", "1"), Header("X-A", "2"), Header("Set-Cookie", "a=1; Path=/"))
        )
      },
      test("an HTTP/2 status line has no text") {
        val (code, text, headers) = LibcurlBackend.parseHead("HTTP/2 404\r\ncontent-type: text/plain\r\n\r\n")
        assertTrue(code == StatusCode.NotFound, text == "", headers == List(Header("content-type", "text/plain")))
      },
      test("no status line is an IOException, which sttp reads as a ReadException") {
        assertTrue(
          scala.util.Try(LibcurlBackend.parseHead("")).failed.toOption.exists(_.isInstanceOf[java.io.IOException])
        )
      },
      test("each CURLcode becomes the java.net exception sttp classifies as connect, timeout or read") {
        val req = basicRequest.get(uri"http://example.com/")
        def kind(code: Int) =
          SttpClientException.defaultExceptionToSttpClientException(req, LibcurlBackend.failure(code, "m")) match {
            case Some(_: SttpClientException.ConnectException) => "connect"
            case Some(_: SttpClientException.TimeoutException) => "timeout"
            case Some(_: SttpClientException.ReadException)    => "read"
            case other                                         => other.toString
          }
        assertTrue(
          List(1, 3, 5, 6, 7, 35, 60).map(kind).forall(_ == "connect"),
          kind(28) == "timeout",
          List(18, 52, 55, 56).map(kind).forall(_ == "read")
        )
      },
      test("libcurl loads from its soname and initialises") {
        assertTrue(Libcurl.load().isRight)
      }
    )

}
