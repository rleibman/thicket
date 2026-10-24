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

package thicket.http.apple

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.file.Files
import scala.concurrent.duration.Duration
import scala.scalanative.unsafe.*
import sttp.capabilities.Effect
import sttp.client4.*
import sttp.client4.internal.{BodyFromResponseAs, SttpFile}
import sttp.client4.wrappers.FollowRedirectsBackend
import sttp.client4.ws.{GotAWebSocketException, NotAWebSocketException}
import sttp.model.{Header, ResponseMetadata, StatusCode, StatusText}
import sttp.monad.MonadError
import sttp.shared.Identity

/** An sttp [[SyncBackend]] over Apple's `URLSession`, for iOS — whose SDK has no libcurl, so sttp's own Scala Native
  * backend, `CurlBackend`, cannot link there — and for macOS, where either works (#90, docs/16 §16.4).
  *
  * The same type as `CurlBackend`, so an app, or caliban-client, picks a backend per platform and nothing else changes:
  * {{{
  * val backend: SyncBackend = URLSessionBackend()
  * basicRequest.get(uri"https://example.com").send(backend)
  * }}}
  *
  * **It blocks the calling thread, so never call it from the UI thread.** Under a self-test (`THICKET_SELFTEST`,
  * `THICKET_GALLERY_SELFTEST`, `THICKET_LAZYTEST`, `THICKET_HTTPTEST`) a call from the main thread throws
  * `IllegalStateException`; otherwise it prints one warning and carries on. From ZIO, run it with
  * `ZIO.attemptBlockingInterrupt`: that runs it on the blocking pool, and interrupting the fiber interrupts the thread,
  * which cancels the `URLSession` task (below).
  *
  * **How a request runs.** The Swift side starts a `URLSessionDataTask` and returns an id. This thread then waits in
  * `thk_http_wait`, a slice of `pollMs` at a time, and reads the status, headers and body out once it is done. Nothing
  * calls back into Scala: `URLSession` completes on its own queue, a thread the GC does not know. The wait is
  * `@blocking`, so a collection started by another thread while this one is parked does not wait for the network.
  * Between slices it checks:
  *   - **interruption** — `Thread.interrupt()` cancels the task and throws `InterruptedException`;
  *   - **the deadline** — `readTimeout` from the request's options, for the whole exchange, as `CurlBackend` treats it;
  *     past it the task is cancelled and sttp's `TimeoutException` is thrown. `URLSession`'s own idle timeout is set to
  *     the same value.
  *
  * **Errors** are sttp's: a request that was not sent (refused, no such host, TLS, App Transport Security) is a
  * `SttpClientException.ConnectException`, a timeout a `TimeoutException`, anything after that a `ReadException`.
  *
  * **Differences from `CurlBackend`, all from `URLSession`:**
  *   - Plain `http://` to anything but `localhost` needs an App Transport Security exception in the app's `Info.plist`
  *     (`NSAppTransportSecurity`); without one the request fails with a `ConnectException`.
  *   - The body is always decompressed by `URLSession` (gzip, deflate, br), whatever `decompressResponseBody` says.
  *   - A response header that repeats is joined by Foundation into one, `", "`-separated, as RFC 9110 §5.3 allows;
  *     `Set-Cookie` is split back into one header per cookie.
  *   - Multipart and streaming request bodies, and streaming or WebSocket responses, are not supported.
  *   - There is no cookie jar and no cache, as with curl. Redirects come back to sttp, whose `FollowRedirectsBackend`
  *     follows them.
  */
final class URLSessionBackend private (pollMs: Int) extends SyncBackend {

  type R = Any & Effect[Identity]

  override def close(): Unit = ()

  override def send[T](request: GenericRequest[T, R]): Response[T] =
    SttpClientException.adjustExceptions(monad)(exchange(request))(
      SttpClientException.defaultExceptionToSttpClientException(request, _)
    )

  private def exchange[T](request: GenericRequest[T, R]): Response[T] = {
    URLSessionBackend.refuseMainThread()
    val timeoutMs: Long = request.options.readTimeout match {
      case d: Duration if d.isFinite && d.toMillis > 0 => d.toMillis
      case _ => 0L
    }
    val body = URLSessionBackend.bodyBytes(request.body)
    val headers = request.headers.map(h => s"${h.name}: ${h.value}").mkString("\n")
    val id = Zone.acquire { implicit z =>
      val buf = if body.isEmpty then null else alloc[Byte](body.length)
      var i = 0
      while i < body.length do { buf(i) = body(i); i += 1 }
      HttpShim.thk_http_send(
        toCString(request.method.method),
        toCString(request.uri.toString),
        toCString(headers),
        buf,
        body.length.toLong,
        math.min(timeoutMs, Int.MaxValue.toLong).toInt
      )
    }
    try {
      awaitDone(id, request, timeoutMs)
      HttpShim.thk_http_error(id) match {
        case 0    => response(id, request)
        case code =>
          val message = s"${request.method} ${request.uri}: ${string(HttpShim.thk_http_error_message(id))}"
          throw URLSessionBackend.failure(code, message)
      }
    } finally HttpShim.thk_http_free(id)
  }

  private def awaitDone(
    id:        Long,
    request:   GenericRequest[?, ?],
    timeoutMs: Long
  ): Unit = {
    val deadline = if timeoutMs > 0 then System.nanoTime() + timeoutMs * 1000000L else Long.MaxValue
    var done = 0
    while done == 0 do {
      if Thread.interrupted() then {
        HttpShim.thk_http_cancel(id)
        throw new InterruptedException(s"${request.method} ${request.uri} was interrupted, and its task cancelled")
      }
      val left = deadline - System.nanoTime()
      if left <= 0 then {
        HttpShim.thk_http_cancel(id)
        throw new java.net.SocketTimeoutException(s"${request.method} ${request.uri}: no response in $timeoutMs ms")
      }
      done = HttpShim.thk_http_wait(id, math.min(pollMs.toLong, left / 1000000L + 1).toInt)
    }
    if done < 0 then throw new IllegalStateException(s"thk_http_wait: request $id is unknown to the Swift side")
  }

  private def response[T](
    id:      Long,
    request: GenericRequest[T, R]
  ): Response[T] = {
    val code = StatusCode(HttpShim.thk_http_status(id))
    val headers = URLSessionBackend.parseHeaders(string(HttpShim.thk_http_headers(id)))
    val length = HttpShim.thk_http_body_length(id)
    val ptr = HttpShim.thk_http_body(id)
    val bytes = new Array[Byte](length.toInt)
    if ptr != null then {
      var i = 0
      while i < bytes.length do { bytes(i) = ptr(i); i += 1 }
    }
    val statusText = StatusText.default(code).getOrElse("")
    val meta = ResponseMetadata(code, statusText, headers)
    request.options.onBodyReceived(meta)
    val body = URLSessionBackend.fromBytes(request.response, meta, Left(bytes))
    Response(body, code, statusText, headers, Nil, request.onlyMetadata)
  }

  private def string(p: CString): String = if p == null then "" else fromCString(p)

}

object URLSessionBackend {

  /** A backend that follows redirects through sttp, as `CurlBackend()` does.
    *
    * @param pollMs
    *   how long each native wait lasts before the thread looks for an interrupt or a passed deadline. The wait is
    *   `@blocking`, so a long one costs the GC nothing; a short one is how quickly an interrupt cancels the request.
    */
  def apply(pollMs: Int = 50): SyncBackend = FollowRedirectsBackend(new URLSessionBackend(math.max(1, pollMs)))

  /** How many requests the Swift side still holds. Every `send` frees its own, whatever happens, so this is 0 whenever
    * no request is in flight; a self-test checks it.
    */
  def liveRequests: Int = HttpShim.thk_http_live()

  /** The environment variables that mean a self-test is running, as `AppleRenderer` reads them. */
  private val selfTestVars = Seq("THICKET_SELFTEST", "THICKET_GALLERY_SELFTEST", "THICKET_LAZYTEST", "THICKET_HTTPTEST")

  @volatile private var warned = false

  private def refuseMainThread(): Unit =
    if HttpShim.thk_http_on_main_thread() == 1 then {
      val message = "URLSessionBackend blocks its thread, and this is the main thread: the UI would freeze until the " +
        "response arrives. Call it from a worker — from ZIO, with ZIO.attemptBlockingInterrupt."
      if selfTestVars.exists(sys.env.contains) then throw new IllegalStateException(message)
      else if !warned then {
        warned = true
        System.err.println(s"thicket-http-apple: $message")
      }
    }

  /** The error codes `thk_http_error` returns, as the `java.net` exceptions sttp classifies. */
  private def failure(
    code:    Int,
    message: String
  ): Exception =
    code match {
      case 1 => new java.net.ConnectException(message)
      case 3 => new java.net.SocketTimeoutException(message)
      case 4 => new java.io.InterruptedIOException(s"cancelled: $message")
      case 5 => new java.net.MalformedURLException(message)
      case _ => new java.io.IOException(message)
    }

  /** "Name: value" lines, split at the first colon. */
  private[apple] def parseHeaders(lines: String): List[Header] =
    lines
      .split("\n").iterator
      .filter(_.nonEmpty)
      .map { line =>
        val colon = line.indexOf(':')
        if colon < 0 then Header(line.trim, "") else Header(line.take(colon).trim, line.drop(colon + 1).trim)
      }
      .toList

  private def bodyBytes(body: GenericRequestBody[?]): Array[Byte] =
    body match {
      case NoBody                 => Array.emptyByteArray
      case StringBody(s, enc, _)  => s.getBytes(enc)
      case ByteArrayBody(b, _)    => b
      case ByteBufferBody(b, _)   => val d = b.duplicate(); val a = new Array[Byte](d.remaining()); d.get(a); a
      case InputStreamBody(in, _) => readAll(in)
      case FileBody(f, _)         => Files.readAllBytes(f.toPath)
      case _: MultipartBody[?] => throw new UnsupportedOperationException("URLSessionBackend: multipart bodies")
      case _: StreamBody[?, ?] => throw new UnsupportedOperationException("URLSessionBackend: streaming bodies")
    }

  private def readAll(in: InputStream): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val chunk = new Array[Byte](8192)
    var n = in.read(chunk)
    while n != -1 do { out.write(chunk, 0, n); n = in.read(chunk) }
    out.toByteArray
  }

  private val monad: MonadError[Identity] = sttp.monad.IdentityMonad

  /** The response is read into memory, so every way of consuming it starts from the bytes. */
  private val fromBytes = new BodyFromResponseAs[Identity, Array[Byte], Nothing, Nothing]()(using monad) {
    override protected def withReplayableBody(
      response:       Array[Byte],
      replayableBody: Either[Array[Byte], SttpFile]
    ):                                                                Array[Byte] = response
    override protected def regularIgnore(response:      Array[Byte]): Unit = ()
    override protected def regularAsByteArray(response: Array[Byte]): Array[Byte] = response
    override protected def regularAsFile(
      response: Array[Byte],
      file:     SttpFile
    ): SttpFile = { Files.write(file.toPath, response); file }
    override protected def regularAsInputStream(response: Array[Byte]): InputStream = new ByteArrayInputStream(response)
    override protected def regularAsStream(response: Array[Byte]):      (Nothing, () => Unit) =
      throw new UnsupportedOperationException("URLSessionBackend: streaming responses")
    override protected def handleWS[T](
      responseAs: GenericWebSocketResponseAs[T, ?],
      meta:       ResponseMetadata,
      ws:         Nothing
    ): T = ws
    override protected def cleanupWhenNotAWebSocket(
      response: Array[Byte],
      e:        NotAWebSocketException
    ): Unit = ()
    override protected def cleanupWhenGotWebSocket(
      response: Nothing,
      e:        GotAWebSocketException
    ): Unit = response
  }

}
