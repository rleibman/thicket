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

  private var poster: (() => Unit) => Unit = f => f()

  /** Installed by the host. `GtkApp` uses `g_idle_add`; an Android Activity uses a `Handler` on the main `Looper`; an
    * Apple host uses `dispatch_async(main)`.
    */
  def install(post: (() => Unit) => Unit): Unit = poster = post

  def run(f: () => Unit): Unit = poster(f)

}
