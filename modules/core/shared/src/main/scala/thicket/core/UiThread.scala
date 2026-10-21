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

package thicket.core

/** The seam through which anything off the UI thread gets back onto it.
  *
  * A library cannot know which thread is "the" UI thread, so the host installs this during start-up, exactly as it
  * installs `ThreadGuard`. Effect bridges post through it rather than holding a renderer, which keeps them independent
  * of any particular one.
  *
  * On Apple targets "any thread" means the main thread or a Scala-created thread — never a GCD queue, which segfaults
  * in the GC allocator (S1).
  */
object UiThread {

  /** The default, until a host installs one: fail, loudly and on the calling thread.
    *
    * It used to be `f => f()`, run where it was posted. On every real host that meant a ZIO fibre wrote the signal
    * graph from a worker thread, `ThreadGuard` threw there, the fibre swallowed it, and the screen sat at `Loading`
    * with nothing logged — and no host installed one (review 2026-10-20, P0-5). A host that forgets now finds out at
    * the first post, with a message that says what to do.
    */
  val notInstalled: (() => Unit) => Unit = _ =>
    throw new IllegalStateException(
      "UiThread.run was called but no host installed a UI-thread poster. Hosts call Host.install at start-up; " +
        "a test calls UiThread.install with its own (see effect-zio's TestUiThread)."
    )

  // Written once on the UI thread and read from every fibre that posts, hence volatile.
  @volatile private var poster: (() => Unit) => Unit = notInstalled

  /** Installed by the host, through [[Host.install]]. `GtkApp` uses `g_idle_add`; an Android Activity uses a `Handler`
    * on the main `Looper`; an Apple host uses `dispatch_async(main)`.
    */
  def install(post: (() => Unit) => Unit): Unit = poster = post

  def installed: (() => Unit) => Unit = poster

  def run(f: () => Unit): Unit = poster(f)

}

/** What every host does once, on the UI thread, before it mounts anything. */
object Host {

  /** Binds the signal graph to the calling thread — a stray cross-thread write then fails loudly instead of corrupting
    * the graph (docs/05 A-06) — and routes everything posted through [[UiThread]] onto it with `post`.
    *
    * One call rather than two, because the two are one decision: the thread `ThreadGuard` accepts must be the thread
    * `post` delivers to. Hosts used to install only the guard.
    */
  def install(post: (() => Unit) => Unit): Unit = {
    thicket.signals.ThreadGuard.install(thicket.signals.ThreadGuard.owningThread)
    UiThread.install(post)
  }

}
