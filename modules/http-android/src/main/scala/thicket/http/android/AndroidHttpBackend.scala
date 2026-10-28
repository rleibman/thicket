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

import java.net.HttpURLConnection
import java.util.concurrent.{ExecutionException, Executors, FutureTask, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger
import sttp.capabilities.Effect
import sttp.client4.*
import sttp.client4.httpurlconnection.HttpURLConnectionBackend
import sttp.client4.wrappers.FollowRedirectsBackend
import sttp.model.HeaderNames
import sttp.shared.Identity

/** sttp's `HttpURLConnectionBackend` — Android's own HTTP stack, so it adds nothing to the APK — with the two things it
  * lacks on sttp 4.1.0 (docs/16 §16.4):
  *   - **a header that repeats is sent each time.** sttp sets each header with `setRequestProperty`, which replaces the
  *     one before, so of `X-A: 1` and `X-A: 2` only the second was sent. They are added back with `addRequestProperty`
  *     once sttp has set its own.
  *   - **an interrupt cancels the request.** A thread blocked reading a socket does not notice `Thread.interrupt()`, so
  *     the exchange runs on a worker while the calling thread waits for it, interruptibly. Interrupted, it throws
  *     `InterruptedException` at once and disconnects the connection, which on Android closes the socket under the
  *     worker. (The JDK's `HttpURLConnection` defers the disconnect until a read in progress ends.)
  *
  * {{{
  * val backend: SyncBackend = AndroidHttpBackend()
  * basicRequest.get(uri"https://example.com").send(backend)
  * }}}
  *
  * **It blocks the calling thread, so never call it from the UI thread.** There it throws Android's
  * `NetworkOnMainThreadException`, as `HttpURLConnection` itself would, before anything starts. From ZIO, run it with
  * `ZIO.attemptBlockingInterrupt`.
  *
  * **What the app needs:** the `INTERNET` permission, and, for plain `http://`, a network security config that allows
  * cleartext to that host (Android refuses it since API 28). With R8, sttp's exception mapping refers to
  * `java.net.http.HttpTimeoutException`, which Android lacks: `-dontwarn java.net.http.HttpTimeoutException`.
  * Everything else is sttp's: errors are its `SttpClientException`s, and redirects are followed by its
  * `FollowRedirectsBackend`, outside this one, so each hop is one exchange with its own headers.
  */
final class AndroidHttpBackend private (delegate: SyncBackend) extends SyncBackend {

  import AndroidHttpBackend.*

  type R = Any & Effect[Identity]

  override def close(): Unit = delegate.close()

  override def send[T](request: GenericRequest[T, R]): Response[T] = {
    refuseMainThread()
    val exchange = Exchange(repeatedHeaders(request))
    // Redirects are this backend's caller's: the delegate's own FollowRedirectsBackend must pass each one back.
    val task = new FutureTask[Response[T]](() => {
      current.set(exchange)
      try delegate.send(request.followRedirects(false))
      finally current.remove()
    })
    workers.execute(task)
    try task.get()
    catch {
      case _: InterruptedException =>
        task.cancel(false) // not yet started: never start
        exchange.cancel()
        throw new InterruptedException(s"${request.method} ${request.uri} was interrupted, and its connection closed")
      case e: ExecutionException => throw e.getCause
    }
  }

}

object AndroidHttpBackend {

  /** A backend that follows redirects through sttp, as `HttpURLConnectionBackend()` does. */
  def apply(options: BackendOptions = BackendOptions.Default): SyncBackend =
    FollowRedirectsBackend(new AndroidHttpBackend(HttpURLConnectionBackend(options, customizeConnection = customize)))

  /** One exchange: the headers to add back, and its connection once there is one. */
  final private class Exchange(repeated: List[(String, List[String])]) {

    private var connection: Option[HttpURLConnection] = None
    private var cancelled = false

    /** Called by sttp on the worker, after it set the headers and before it connects. */
    def opened(c: HttpURLConnection): Unit = {
      synchronized {
        if cancelled then throw new java.io.InterruptedIOException("cancelled before it was sent")
        connection = Some(c)
      }
      repeated.foreach { case (name, values) =>
        c.setRequestProperty(name, values.head)
        values.tail.foreach(c.addRequestProperty(name, _))
      }
    }

    /** On another thread: Android's `disconnect` cancels at once, but the JDK's waits for a read in progress, and the
      * interrupted caller must not.
      */
    def cancel(): Unit =
      synchronized {
        cancelled = true
        connection.foreach(c => workers.execute(() => c.disconnect()))
      }

  }

  /** The headers given more than once, by name, in order. `Content-Length` is sttp's to set. */
  private[android] def repeatedHeaders(request: GenericRequest[?, ?]): List[(String, List[String])] =
    request.headers
      .filterNot(_.is(HeaderNames.ContentLength))
      .groupBy(_.name.toLowerCase)
      .values
      .collect { case hs if hs.size > 1 => hs.head.name -> hs.map(_.value).toList }
      .toList

  /** Android's own refusal, which the worker would otherwise hide: the exchange runs there, and the calling thread
    * would only wait — on the main thread, a frozen UI. Found by reflection, so that on the JDK it is never true.
    */
  private val onMainThread: () => Boolean =
    try {
      val looper = Class.forName("android.os.Looper")
      val main = looper.getMethod("getMainLooper").invoke(null)
      val isCurrent = looper.getMethod("isCurrentThread")
      () => isCurrent.invoke(main) == java.lang.Boolean.TRUE
    } catch { case _: ReflectiveOperationException => () => false }

  private def refuseMainThread(): Unit =
    if onMainThread() then
      throw Class
        .forName("android.os.NetworkOnMainThreadException")
        .getConstructor()
        .newInstance()
        .asInstanceOf[RuntimeException]

  private val current = new ThreadLocal[Exchange]

  private val customize: HttpURLConnection => Unit = c => Option(current.get()).foreach(_.opened(c))

  private val workers = Executors.newCachedThreadPool(new ThreadFactory {
    private val n = new AtomicInteger()
    def newThread(r: Runnable): Thread = {
      val t = new Thread(r, s"thicket-http-${n.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
  })

}
