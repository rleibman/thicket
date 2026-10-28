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

package thicket.http.android

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import sttp.client4.*
import zio.test.*

/** `AndroidHttpBackend` on the desktop JDK, whose `HttpURLConnection` replaces a header and ignores an interrupt as
  * Android's does, against the JDK's own server. The gallery's self-test runs the same backend on Android, where the
  * cancelled connection's closing can be seen too.
  */
object AndroidHttpBackendSpec extends ZIOSpecDefault {

  /** A server on a free loopback port for `body`, with `handler` at `/`. */
  private def withServer[A](handler: HttpExchange => Unit)(body: Int => A): A = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      e =>
        try handler(e)
        finally e.close()
    )
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
    server.start()
    try body(server.getAddress.getPort)
    finally server.stop(0)
  }

  private def reply(
    e:    HttpExchange,
    code: Int,
    text: String
  ): Unit = {
    val bytes = text.getBytes("UTF-8")
    e.sendResponseHeaders(code, bytes.length.toLong)
    e.getResponseBody.write(bytes)
  }

  def spec =
    suite("AndroidHttpBackend")(
      test("a header given twice reaches the server twice; one given once, once") {
        val seen = new AtomicReference[List[String]](Nil)
        val once = new AtomicReference[List[String]](Nil)
        withServer { e =>
          seen.set(Option(e.getRequestHeaders.get("X-Req")).map(_.asScala.toList).getOrElse(Nil))
          once.set(Option(e.getRequestHeaders.get("X-One")).map(_.asScala.toList).getOrElse(Nil))
          reply(e, 200, "ok")
        } { port =>
          val r = basicRequest
            .get(uri"http://127.0.0.1:$port/")
            .header("X-Req", "one")
            .header("X-Req", "two", DuplicateHeaderBehavior.Add)
            .header("X-One", "only")
            .send(AndroidHttpBackend())
          assertTrue(r.code.code == 200, seen.get == List("one", "two"), once.get == List("only"))
        }
      },
      test("a redirect is followed by sttp, and the next hop gets the repeated header too") {
        val hops = new AtomicReference[List[(String, List[String])]](Nil)
        withServer { e =>
          val path = e.getRequestURI.getPath
          hops.updateAndGet(h => h :+ (path -> e.getRequestHeaders.get("X-Req").asScala.toList))
          if path == "/from" then {
            e.getResponseHeaders.add("Location", "/to")
            e.sendResponseHeaders(302, -1)
          } else reply(e, 200, "arrived")
        } { port =>
          val r = basicRequest
            .get(uri"http://127.0.0.1:$port/from")
            .header("X-Req", "a")
            .header("X-Req", "b", DuplicateHeaderBehavior.Add)
            .response(asStringAlways)
            .send(AndroidHttpBackend())
          assertTrue(
            r.body == "arrived",
            r.history.map(_.code.code) == List(302),
            hops.get == List("/from" -> List("a", "b"), "/to" -> List("a", "b"))
          )
        }
      },
      test("an interrupt returns at once with InterruptedException, the request still running") {
        // Only the caller's side can be seen here: the JDK's HttpURLConnection defers a disconnect until the read in
        // progress ends, where Android's closes the socket at once. That half is the gallery's check, on Android,
        // where the server counts the hang-up.
        withServer { e =>
          Thread.sleep(4000)
          reply(e, 200, "late")
        } { port =>
          val outcome = new AtomicReference[String]("nothing")
          val t = new Thread(() =>
            outcome.set(
              try s"no cancel: ${basicRequest.get(uri"http://127.0.0.1:$port/").send(AndroidHttpBackend()).code}"
              catch {
                case _: InterruptedException => "InterruptedException"
                case e: Throwable            => e.toString
              }
            )
          )
          val t0 = System.nanoTime()
          t.start()
          Thread.sleep(300)
          t.interrupt()
          t.join()
          val ms = (System.nanoTime() - t0) / 1000000L
          assertTrue(outcome.get == "InterruptedException", ms < 1500)
        }
      },
      test("errors are still sttp's: a refused connection is a ConnectException") {
        val r = scala.util.Try(basicRequest.get(uri"http://127.0.0.1:1/").send(AndroidHttpBackend()))
        assertTrue(r.failed.toOption.exists(_.isInstanceOf[SttpClientException.ConnectException]))
      },
      test("repeatedHeaders: names compared without case, Content-Length left to sttp") {
        val req = basicRequest
          .header("x-a", "1")
          .header("X-A", "2", DuplicateHeaderBehavior.Add)
          .header("Content-Length", "1")
          .header("Content-Length", "2", DuplicateHeaderBehavior.Add)
          .get(uri"http://example.com/")
        assertTrue(AndroidHttpBackend.repeatedHeaders(req) == List("x-a" -> List("1", "2")))
      }
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

}
