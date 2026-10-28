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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration.Duration
import scala.scalanative.libc.{stdio, stdlib}
import scala.scalanative.unsafe.*
import sttp.capabilities.Effect
import sttp.client4.*
import sttp.client4.internal.{BodyFromResponseAs, SttpFile}
import sttp.client4.wrappers.FollowRedirectsBackend
import sttp.client4.ws.{GotAWebSocketException, NotAWebSocketException}
import sttp.model.{Header, Method, ResponseMetadata, StatusCode}
import sttp.shared.Identity

/** An sttp [[SyncBackend]] over libcurl for GTK apps, in place of sttp's own `CurlBackend` (docs/16 §16.4), which on
  * sttp 4.1.0 corrupts a binary request body, makes every collection wait for a request in flight, cannot be cancelled,
  * and throws plain `RuntimeException`s. The same type, so an app, or caliban-client, changes nothing else:
  * {{{
  * val backend: SyncBackend = LibcurlBackend()
  * basicRequest.get(uri"https://example.com").send(backend)
  * }}}
  *
  * **It blocks the calling thread, so never call it from the UI thread.** From ZIO, run it with
  * `ZIO.attemptBlockingInterrupt`: interrupting the fiber interrupts the thread, which cancels the request (below).
  *
  * **How a request runs.** libcurl is loaded with `dlopen` (`libcurl.so.4`), so an app starts without it and the build
  * needs no `-dev` package; if it is missing, `LibcurlBackend()` throws `IllegalStateException` saying so. Each request
  * gets its own easy and multi handle. This thread drives the transfer and waits in `curl_multi_poll`, a slice of
  * `pollMs` at a time, with the thread Unmanaged, so a collection started by another thread does not wait for the
  * network. Nothing calls back into Scala: curl writes the body and headers into memory itself. Between slices it
  * checks:
  *   - **interruption** — `Thread.interrupt()` drops the transfer, which closes its connection, and throws
  *     `InterruptedException`;
  *   - **the deadline** — `readTimeout` from the request's options, for the whole exchange, as `URLSessionBackend`
  *     treats it; past it the transfer is dropped and sttp's `TimeoutException` is thrown.
  *
  * **Errors** are sttp's: a request that was not sent (refused, no such host, TLS) is a
  * `SttpClientException.ConnectException`, a timeout a `TimeoutException`, anything after that a `ReadException`.
  *
  * **Limits:**
  *   - A connection is not reused between requests: each has its own multi handle, so that two threads never share one.
  *   - The body is decompressed by curl (whatever encodings the system's libcurl supports), whatever
  *     `decompressResponseBody` says.
  *   - Multipart and streaming request bodies, and streaming or WebSocket responses, are not supported.
  *   - There is no cookie jar and no cache. Redirects come back to sttp, whose `FollowRedirectsBackend` follows them.
  */
final class LibcurlBackend private (
  curl:   Libcurl,
  pollMs: Int
) extends SyncBackend {

  import Libcurl.*

  type R = Any & Effect[Identity]

  override def close(): Unit = ()

  override def send[T](request: GenericRequest[T, R]): Response[T] =
    SttpClientException.adjustExceptions(monad)(exchange(request))(
      SttpClientException.defaultExceptionToSttpClientException(request, _)
    )

  private def exchange[T](request: GenericRequest[T, R]): Response[T] = {
    val timeoutMs: Long = request.options.readTimeout match {
      case d: Duration if d.isFinite && d.toMillis > 0 => d.toMillis
      case _ => 0L
    }
    val deadline = if timeoutMs > 0 then System.nanoTime() + timeoutMs * 1000000L else Long.MaxValue
    val body = LibcurlBackend.bodyBytes(request.body)
    Zone.acquire { implicit z =>
      val easy = curl.easy()
      if easy == null then throw new java.io.IOException("curl_easy_init failed")
      val multi = curl.multi()
      if multi == null then { curl.cleanup(easy); throw new java.io.IOException("curl_multi_init failed") }
      val bodyOut = MemoryStream()
      val headersOut = MemoryStream()
      var headers: Ptr[Byte] = null
      var added = false
      try {
        curl.setLong(easy, NoSignal, 1)
        curl.setPtr(easy, Url, toCString(request.uri.toString).asInstanceOf[Ptr[Byte]])
        curl.setLong(easy, ConnectTimeoutMs, LibcurlBackend.ConnectTimeoutMs)
        curl.setPtr(easy, AcceptEncoding, c"".asInstanceOf[Ptr[Byte]]) // decode whatever encodings curl has
        request.method match {
          case Method.GET if body.isEmpty => curl.setLong(easy, HttpGet, 1)
          case Method.HEAD                => curl.setLong(easy, NoBody, 1)
          case m                          =>
            curl.setPtr(easy, CustomRequest, toCString(m.method).asInstanceOf[Ptr[Byte]])
            if body.nonEmpty || m == Method.POST then {
              // Size first, then the bytes, which curl copies: binary-safe, NULs included.
              val buf = alloc[Byte](math.max(1, body.length))
              var i = 0
              while i < body.length do { buf(i) = body(i); i += 1 }
              curl.setOff(easy, PostFieldsSize, body.length.toLong)
              curl.setPtr(easy, CopyPostFields, buf)
            }
        }
        // "Expect:" with no value stops curl from asking for 100-continue before a large body, a round trip that a
        // server which does not answer it turns into a one-second wait.
        val lines = request.headers.map(h => s"${h.name}: ${h.value}") ++
          (if request.headers.exists(_.is("Expect")) then Nil else List("Expect:"))
        lines.foreach(l => headers = curl.append(headers, toCString(l, StandardCharsets.UTF_8)))
        curl.setPtr(easy, HttpHeader, headers)
        curl.setPtr(easy, WriteData, bodyOut.file)
        curl.setPtr(easy, HeaderData, headersOut.file)
        curl.add(multi, easy)
        added = true
        awaitDone(multi, request, deadline, timeoutMs) match {
          case 0    => response(request, headersOut.bytes(), bodyOut.bytes())
          case code => throw LibcurlBackend.failure(code, s"${request.method} ${request.uri}: ${curl.strerror(code)}")
        }
      } finally {
        if added then curl.remove(multi, easy)
        curl.cleanup(easy) // closes the connection: nothing else holds it
        curl.cleanupMulti(multi)
        curl.freeList(headers)
        bodyOut.release()
        headersOut.release()
      }
    }
  }

  /** Drives the transfer until it is done; its `CURLcode`. */
  private def awaitDone(
    multi:     Ptr[Byte],
    request:   GenericRequest[?, ?],
    deadline:  Long,
    timeoutMs: Long
  )(using Zone
  ): Int = {
    val running = alloc[CInt]()
    val queue = alloc[CInt]()
    val fds = alloc[CInt]()
    var result = Option.empty[Int]
    while result.isEmpty do {
      if Thread.interrupted() then
        throw new InterruptedException(s"${request.method} ${request.uri} was interrupted, and its transfer dropped")
      val left = deadline - System.nanoTime()
      if left <= 0 then
        throw new java.net.SocketTimeoutException(s"${request.method} ${request.uri}: no response in $timeoutMs ms")
      curl.perform(multi, running)
      result = curl.done(multi, queue)
      if result.isEmpty then {
        if !running == 0 then throw new java.io.IOException("curl finished the transfer without reporting it")
        curl.poll(multi, math.min(pollMs.toLong, left / 1000000L + 1).toInt, fds)
      }
    }
    result.get
  }

  private def response[T](
    request: GenericRequest[T, R],
    head:    Array[Byte],
    bytes:   Array[Byte]
  ): Response[T] = {
    val (code, statusText, headers) = LibcurlBackend.parseHead(new String(head, StandardCharsets.ISO_8859_1))
    val meta = ResponseMetadata(code, statusText, headers)
    request.options.onBodyReceived(meta)
    val body = LibcurlBackend.fromBytes(request.response, meta, Left(bytes))
    sttp.client4.Response(body, code, statusText, headers, Nil, request.onlyMetadata)
  }

  /** A `FILE*` that curl writes into, its buffer read back once, and freed either way. */
  final private class MemoryStream(using Zone) {

    private val ptr = alloc[Ptr[Byte]]()
    private val length = alloc[CSize]()
    val file: Ptr[Byte] = MemStream.open_memstream(ptr, length)
    if file == null then throw new OutOfMemoryError("open_memstream")
    private var open = true

    /** Closes the stream and copies its bytes out. */
    def bytes(): Array[Byte] = {
      close()
      val out = new Array[Byte]((!length).toInt)
      var i = 0
      val buf = !ptr
      while i < out.length do { out(i) = buf(i); i += 1 }
      out
    }

    def release(): Unit = { close(); if !ptr != null then { stdlib.free(!ptr); !ptr = null } }

    private def close(): Unit = if open then { open = false; stdio.fclose(file.asInstanceOf[Ptr[stdio.FILE]]) }

  }

}

object LibcurlBackend {

  /** How long a connection may take, as sttp's `BackendOptions.Default`. */
  private val ConnectTimeoutMs = 30000L

  /** A backend that follows redirects through sttp, as `CurlBackend()` does.
    *
    * @param pollMs
    *   how long each wait in `curl_multi_poll` lasts before the thread looks for an interrupt or a passed deadline. The
    *   wait is Unmanaged, so a long one costs the GC nothing; a short one is how quickly an interrupt cancels.
    * @throws IllegalStateException
    *   if libcurl cannot be loaded, saying why.
    */
  def apply(pollMs: Int = 50): SyncBackend =
    Libcurl.load() match {
      case Right(curl)  => FollowRedirectsBackend(new LibcurlBackend(curl, math.max(1, pollMs)))
      case Left(reason) => throw new IllegalStateException(s"thicket-http-gtk: $reason")
    }

  /** The `CURLcode`s, as the `java.net` exceptions sttp classifies. */
  private[gtk] def failure(
    code:    Int,
    message: String
  ): Exception =
    code match {
      case 1 | 3   => new java.net.MalformedURLException(message) // UNSUPPORTED_PROTOCOL, URL_MALFORMAT
      case 5 | 6   => new java.net.UnknownHostException(message) // COULDNT_RESOLVE_PROXY, COULDNT_RESOLVE_HOST
      case 7       => new java.net.ConnectException(message) // COULDNT_CONNECT
      case 28      => new java.net.SocketTimeoutException(message) // OPERATION_TIMEDOUT
      case 35 | 60 => new java.net.ConnectException(message) // SSL_CONNECT_ERROR, PEER_FAILED_VERIFICATION
      case _       => new java.io.IOException(message)
    }

  /** The status, its text and the headers of the last response in curl's header stream: an interim `1xx` comes first
    * when there is one.
    */
  private[gtk] def parseHead(raw: String): (StatusCode, String, List[Header]) = {
    val lines = raw.split("\r?\n").toList
    val block = lines.drop(lines.lastIndexWhere(_.startsWith("HTTP/")))
    val status = block.headOption.getOrElse(throw new java.io.IOException("no status line in the response"))
    val parts = status.split(" ", 3)
    val code =
      parts.lift(1).flatMap(_.toIntOption).getOrElse(throw new java.io.IOException(s"bad status line: $status"))
    val headers = block.tail.takeWhile(_.nonEmpty).map { line =>
      val colon = line.indexOf(':')
      if colon < 0 then Header(line.trim, "") else Header(line.take(colon).trim, line.drop(colon + 1).trim)
    }
    (StatusCode(code), parts.lift(2).getOrElse(""), headers)
  }

  private def bodyBytes(body: GenericRequestBody[?]): Array[Byte] =
    body match {
      case NoBody                 => Array.emptyByteArray
      case StringBody(s, enc, _)  => s.getBytes(enc)
      case ByteArrayBody(b, _)    => b
      case ByteBufferBody(b, _)   => val d = b.duplicate(); val a = new Array[Byte](d.remaining()); d.get(a); a
      case InputStreamBody(in, _) => readAll(in)
      case FileBody(f, _)         => Files.readAllBytes(f.toPath)
      case _: MultipartBody[?] => throw new UnsupportedOperationException("LibcurlBackend: multipart bodies")
      case _: StreamBody[?, ?] => throw new UnsupportedOperationException("LibcurlBackend: streaming bodies")
    }

  private def readAll(in: InputStream): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val chunk = new Array[Byte](8192)
    var n = in.read(chunk)
    while n != -1 do { out.write(chunk, 0, n); n = in.read(chunk) }
    out.toByteArray
  }

  /** The response is read into memory, so every way of consuming it starts from the bytes. */
  private val fromBytes =
    new BodyFromResponseAs[Identity, Array[Byte], Nothing, Nothing]()(using sttp.monad.IdentityMonad) {
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
      override protected def regularAsInputStream(response: Array[Byte]): InputStream =
        new ByteArrayInputStream(response)
      override protected def regularAsStream(response: Array[Byte]): (Nothing, () => Unit) =
        throw new UnsupportedOperationException("LibcurlBackend: streaming responses")
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
