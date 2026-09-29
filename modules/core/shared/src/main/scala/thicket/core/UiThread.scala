package thicket.core

/** The seam through which anything off the UI thread gets back onto it.
  *
  * A library cannot know which thread is "the" UI thread, so the host installs this during
  * start-up, exactly as it installs `ThreadGuard`. Effect bridges post through it rather
  * than holding a renderer, which keeps them independent of any particular one.
  *
  * On Apple targets "any thread" means the main thread or a Scala-created thread — never a
  * GCD queue, which segfaults in the GC allocator (S1).
  */
object UiThread {

  private var poster: (() => Unit) => Unit = f => f()

  /** Installed by the host. `GtkApp` uses `g_idle_add`; an Android Activity uses a
    * `Handler` on the main `Looper`; an Apple host uses `dispatch_async(main)`.
    */
  def install(post: (() => Unit) => Unit): Unit = poster = post

  def run(f: () => Unit): Unit = poster(f)
}
