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

package thicket.signals

import scala.util.control.NonFatal

/** Where an exception goes when there is no caller left to throw it to.
  *
  * App code runs inside callbacks the toolkit makes: a click handler, an effect re-run by a write elsewhere, a fiber's
  * result arriving on the UI thread. Throwing out of one of those does not reach the app. On GTK and Apple it unwinds
  * through C frames, which is undefined behaviour and in practice an abort with no stack trace; on Android it escapes
  * the listener and kills the process; inside the signal runtime it used to poison every later, unrelated write (review
  * 2026-10-20, P0-3). Every such boundary catches instead and reports here.
  *
  * The default prints the stack trace to stderr. An app installs its own once at start-up — to log, to show an alert,
  * or, in a test, to collect and fail.
  */
object ErrorSink {

  /** Prints to stderr, prefixed so it can be told apart from the app's own output. */
  val printing: Throwable => Unit = { e =>
    System.err.println(s"thicket: uncaught exception in a callback: $e")
    e.printStackTrace()
  }

  @volatile private var current: Throwable => Unit = printing

  def install(handler: Throwable => Unit): Unit = current = handler
  def installed:                           Throwable => Unit = current

  /** Hands `e` to the installed handler. A handler that itself throws is not allowed to undo the point of having one,
    * so that is printed and dropped.
    */
  def report(e: Throwable): Unit =
    try current(e)
    catch { case NonFatal(inner) => printing(inner) }

  /** Runs `body`; a non-fatal exception is reported and `fallback` returned in its place. Fatal ones (out of memory,
    * stack overflow on the JVM, interrupts) still propagate: there is nothing sensible to carry on with.
    */
  inline def catching[A](inline fallback: A)(inline body: A): A =
    try body
    catch {
      case NonFatal(e) =>
        report(e)
        fallback
    }

}
