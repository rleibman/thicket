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

import caliban.client.{Argument, CalibanClientError, SelectionBuilder}
import caliban.client.FieldBuilder.Scalar
import caliban.client.Operations.RootQuery
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import sttp.client4.*
import sttp.model.{StatusCode, Uri}

/** An sttp `SyncBackend` against the local server in `examples/http/server.py`: the same checks for every platform's
  * backend — `URLSessionBackend` on macOS and iOS (#90), `CurlBackend` on Linux, `HttpURLConnectionBackend` on Android.
  * The host starts the server, passes its port, and adds the checks only its platform has.
  *
  * Every check reports to `report` as it finishes; [[summary]] is the closing lines the scripts wait for.
  */
final class HttpChecks(report: HttpChecks.Result => Unit) {

  private val results = ListBuffer.empty[HttpChecks.Result]

  def all: List[HttpChecks.Result] = synchronized(results.toList)

  def check(
    name: String
  )(
    body: => (Boolean, String)
  ): Unit = {
    val (ok, detail) =
      try body
      catch { case e: Throwable => (false, s"threw ${e.getClass.getName}: ${e.getMessage}") }
    val r = HttpChecks.Result(name, ok, detail)
    synchronized(results += r)
    report(r)
  }

  /** `N checks: …`, then `ALL CHECKS PASSED` or `N CHECK(S) FAILED`. */
  def summary: List[String] = {
    val rs = all
    val failed = rs.count(!_.passed)
    List(
      s"${rs.size} checks: ${rs.size - failed} passed, $failed failed",
      if failed == 0 then "ALL CHECKS PASSED" else s"$failed CHECK(S) FAILED"
    )
  }

  private def millis[A](body: => A): (A, Long) = {
    val t0 = System.nanoTime()
    val a = body
    (a, (System.nanoTime() - t0) / 1000000L)
  }

  /** The one query this test makes through caliban-client: `greeting(name: String!): String!`. */
  private def greeting(name: String): SelectionBuilder[RootQuery, String] =
    SelectionBuilder.Field("greeting", Scalar[String](), arguments = List(Argument("name", name, "String!")))

  /** Every portable check, on the calling thread, which must not be a UI thread.
    *
    * `parked` is the backend for the GC check: one that waits for the whole of a slow response in a single native call.
    * It is `backend` itself except where the backend polls, as `URLSessionBackend` does.
    */
  def run(
    port:    Int,
    backend: SyncBackend,
    parked:  SyncBackend
  ): Unit = {
    val base = s"http://127.0.0.1:$port"

    check("GET") {
      val (r, ms) = millis(basicRequest.get(uri"$base/hello").response(asStringAlways).send(backend))
      (
        r.code == StatusCode.Ok && r.body == "hello, thicket" && r.contentType.exists(_.startsWith("text/plain")),
        s"${r.code} \"${r.body}\" ${r.contentType.getOrElse("-")} in $ms ms"
      )
    }

    check("host names through idn2") {
      // On Native, sttp-model passes every DNS host name through libidn2's idn2_to_ascii_8z: iOS has no libidn2, so
      // thicket-http-apple supplies it (Idn2.swift); Linux and macOS have the system's. On the JVM it is java.net.IDN.
      // No request is made for the two IDN names: only the URI is rendered.
      val idn = List("http://bücher.example/a", "http://MÜNCHEN.example/").map(Uri.unsafeParse(_).toString)
      val r = basicRequest.get(uri"http://localhost:$port/hello").response(asStringAlways).send(backend)
      (
        idn == List("http://xn--bcher-kva.example/a", "http://xn--mnchen-3ya.example/") && r.body == "hello, thicket",
        s"rendered $idn; GET via localhost ${r.code} \"${r.body}\""
      )
    }

    check("POST with a body") {
      val text = "ünïcödé body ✓ " * 100
      val r = basicRequest.post(uri"$base/echo").body(text).response(asStringAlways).send(backend)
      val bytes = Array.tabulate[Byte](256)(_.toByte)
      val b = basicRequest.post(uri"$base/echo").body(bytes).response(asByteArrayAlways).send(backend)
      (
        r.code == StatusCode.Ok && r.body == text && r.header("X-Method").contains("POST") &&
          b.body.sameElements(bytes),
        s"${r.code}, ${r.body.length} chars echoed ${if r.body == text then "intact" else "CHANGED"}, " +
          s"256 bytes ${if b.body.sameElements(bytes) then "intact" else s"CHANGED (${b.body.length})"}, " +
          s"X-Method ${r.header("X-Method").getOrElse("-")}"
      )
    }

    check("404") {
      val r = basicRequest.get(uri"$base/missing").send(backend)
      (r.code == StatusCode.NotFound && r.body == Left("no such thing"), s"${r.code} ${r.body}")
    }

    check("repeated headers") {
      val r = basicRequest.get(uri"$base/cookies").send(backend)
      val cookies = r.headers("Set-Cookie").toList
      val multi = r.headers("X-Multi").flatMap(_.split(",")).map(_.trim).toList
      val parsed = r.unsafeCookies.map(_.name).toList.sorted
      val sent = basicRequest
        .post(uri"$base/echo")
        .header("X-Req", "one")
        .header("X-Req", "two", DuplicateHeaderBehavior.Add)
        .send(backend)
      val received = sent.header("X-Req-Values").toList.flatMap(_.split("[|,]")).map(_.trim)
      (
        cookies == List("a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/", "b=2; Path=/") &&
          multi == List("one", "two") && parsed == List("a", "b") && received == List("one", "two"),
        s"Set-Cookie ×${cookies.size} ${cookies.mkString("[", " | ", "]")}, X-Multi $multi, cookies $parsed; " +
          s"sent X-Req twice, server got $received"
      )
    }

    check("redirect followed by sttp") {
      val r = basicRequest.get(uri"$base/redirect").response(asStringAlways).send(backend)
      (
        r.code == StatusCode.Ok && r.body == "hello, thicket" && r.history.size == 1,
        s"${r.code}, history ${r.history.map(_.code)}"
      )
    }

    check("timeout") {
      val (outcome, ms) = millis {
        try {
          val r = basicRequest.get(uri"$base/slow?ms=3000").readTimeout(500.millis).send(backend)
          s"no timeout: ${r.code}"
        } catch { case _: SttpClientException.TimeoutException => "TimeoutException" }
      }
      (outcome == "TimeoutException" && ms < 1500, s"$outcome after $ms ms (readTimeout 500 ms, server waits 3000)")
    }

    check("connect refused") {
      // Port 1 on loopback: nothing listens there, so the connection is refused without leaving the machine.
      try {
        val r = basicRequest.get(uri"http://127.0.0.1:1/").send(backend)
        (false, s"got ${r.code}")
      } catch {
        case e: SttpClientException.ConnectException => (true, s"ConnectException: ${e.getCause.getMessage}")
        case e: SttpClientException                  => (false, s"${e.getClass.getName}: ${e.getCause}")
      }
    }

    check("connection reset") {
      // A reset reaches sttp's exception mapping past the cases for connecting and timing out, so this is the check
      // that every case of that mapping can run on the platform (on Android, java.net.http is not there).
      try {
        val r = basicRequest.get(uri"$base/reset").send(backend)
        (false, s"got ${r.code}")
      } catch {
        case e: SttpClientException.ReadException => (true, s"ReadException: ${e.getCause}")
        case e: Throwable                         => (false, s"${e.getClass.getName}: ${e.getMessage}")
      }
    }

    check("cancellation by interrupt") {
      val before = stats(base, backend)
      @volatile var outcome = "nothing"
      @volatile var ms = -1L
      val t = new Thread(() => {
        val t0 = System.nanoTime()
        outcome =
          try s"no cancel: ${basicRequest.get(uri"$base/slow?ms=4000").send(backend).code}"
          catch {
            case _: InterruptedException => "InterruptedException"
            case e: Throwable            => s"${e.getClass.getName}: ${e.getMessage}"
          }
        ms = (System.nanoTime() - t0) / 1000000L
      })
      t.start()
      Thread.sleep(300)
      t.interrupt()
      t.join()
      // The server notices the closed socket within its 20 ms poll; give it up to a second.
      var after = stats(base, backend)
      var waited = 0
      while after("aborted") == before("aborted") && waited < 1000 do {
        Thread.sleep(50); waited += 50; after = stats(base, backend)
      }
      val aborted = after("aborted") - before("aborted")
      (
        outcome == "InterruptedException" && ms < 1000 && aborted == 1,
        s"$outcome after $ms ms (interrupted at 300, server would answer at 4000); the server saw $aborted hang-up"
      )
    }

    check("the GC does not wait for a blocked request") {
      // A collection stops every thread that runs managed code. A thread inside a native call the runtime was not told
      // blocks (@blocking) is still counted as running it, so the collection waits for that call to come back.
      @volatile var code = "nothing"
      val t = new Thread(() =>
        code =
          try basicRequest.get(uri"$base/slow?ms=2000").send(parked).code.toString
          catch { case e: Throwable => s"${e.getClass.getName}: ${e.getMessage}" }
      )
      t.start()
      Thread.sleep(300)
      val (_, gcMs) = millis(System.gc())
      t.join()
      (
        gcMs < 500 && code == "200",
        s"System.gc() took $gcMs ms while another thread waited 2000 ms for a response; it then got $code"
      )
    }

    check("caliban-client query") {
      val (r, ms) = millis(greeting("thicket").toRequest(uri"$base/graphql").send(backend))
      val body: Either[CalibanClientError, String] = r.body
      (body == Right("hello, thicket"), s"${r.code} $body in $ms ms")
    }
  }

  /** The server's /stats counters. */
  private def stats(
    base:    String,
    backend: SyncBackend
  ): Map[String, Int] = {
    val json = basicRequest.get(Uri.unsafeParse(s"$base/stats")).response(asStringAlways).send(backend).body
    Seq("started", "completed", "aborted").map { k =>
      k -> s""""$k": (\\d+)""".r.findFirstMatchIn(json).map(_.group(1).toInt).getOrElse(-1)
    }.toMap
  }

}

object HttpChecks {

  final case class Result(
    name:   String,
    passed: Boolean,
    detail: String
  )

  /** `PASS name — detail` or `FAIL …`: the line the hosts print. */
  def line(r: Result): String = s"${if r.passed then "PASS" else "FAIL"} ${r.name} — ${r.detail}"

}
