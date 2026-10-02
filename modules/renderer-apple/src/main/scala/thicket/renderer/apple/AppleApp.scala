package thicket.renderer.apple

import scala.scalanative.unsafe.*
import thicket.core.{AppRoot, Mounted, NavPage, Reconciler}
import thicket.signals.{Owner, Signal, ThreadGuard}

/** Hosts an element tree in an AppKit window.
  *
  * The whole entry point for a macOS target: `AppleApp.run("Title")(ui)`.
  */
object AppleApp {

  // A CFuncPtr cannot close over local state, so the mount parameters live here (S4).
  private var build: Owner ?=> AppRoot =
    (_: Owner) ?=> throw IllegalStateException("AppleApp.run was not given a UI")

  private val rootOwner = Owner()

  /** The mounted root view, available once the window has been built. The demo reads the tree back out of it with
    * [[AppleInspect]].
    */
  var rootHandle: Shim.Handle = null

  /** The renderer that mounted the tree. The demo's self-test asks it what a handle was created as; nothing in the
    * framework needs it.
    */
  var renderer: AppleRenderer = null

  /** Runs `f` on the main thread. Safe from any thread the platform permits — which on Apple means the main thread or a
    * Scala-created thread, never a GCD worker (S1).
    */
  def postToUi(f: () => Unit): Unit = Shim.sui_run_on_main(Handles.tapTrampoline, Handles.registerOneShot(f))

  /** [[postToUi]], after at least `delayMs`. */
  def postToUiAfter(delayMs: Int)(f: () => Unit): Unit =
    Shim.sui_run_on_main_after(delayMs, Handles.tapTrampoline, Handles.registerOneShot(f))

  /** Fired by the shim from `applicationDidFinishLaunching`, so the window and its root view exist by the time the tree
    * is mounted.
    */
  private val onReady: Shim.VoidCb =
    CFuncPtr1.fromScalaFunction((_: Long) =>
      GcState.guarded {
        renderer = AppleRenderer()
        given Owner = rootOwner
        val root = build

        // The stack is rendered the platform's way (`AppRoot.pages`): a navigation
        // controller on iOS, a sidebar on macOS. Each page is mounted once and stays mounted
        // while it is covered, which is what keeps the screen below a push alive.
        Signal.effect {
          val ps = root.pages()
          Signal.untracked(showPages(ps))
        }

        // The platform popped its own stack — a swipe, a back button, a sidebar choice.
        // `Nav` follows, or the two stacks diverge and the chrome starts lying.
        Shim.sui_on_pages_popped(
          Handles.intTrampoline,
          Handles.registerInt { depth =>
            while root.pages.now.size > depth && root.back() do ()
          }
        )

        // Native chrome, driven from AppRoot rather than drawn into the element tree: the
        // window title follows the top screen.
        Signal.effect(Zone(Shim.sui_window_set_title(toCString(root.title()))))
      }
    )

  /** The mounted content of each live page, by entry id, with the owner its effects belong to. */
  private val pageMounts = scala.collection.mutable.LinkedHashMap.empty[Long, (Owner, Mounted[Shim.Handle])]

  /** The mounted root of each page on the stack, bottom first. */
  def pageHandles: List[Shim.Handle] = pageMounts.values.map(_._2.handle).toList

  private def showPages(ps: Seq[NavPage]): Unit = {
    ps.foreach { p =>
      if !pageMounts.contains(p.id) then {
        // Owned by the root, not by the effect that noticed the page: re-running the effect on
        // the next push must not dispose the pages it already mounted.
        val o = Owner.child(using rootOwner)
        pageMounts(p.id) = (o, Reconciler.mount(renderer, p.content)(using o))
      }
    }
    Shim.sui_pages_begin()
    ps.foreach(p => Zone(Shim.sui_pages_add(p.id, pageMounts(p.id)._2.handle, toCString(p.screen.title))))
    Shim.sui_pages_commit()

    // Only after the platform has let go of them.
    val live = ps.map(_.id).toSet
    pageMounts.keys.filterNot(live).toList.foreach { id =>
      pageMounts.remove(id).foreach {
        (
          o,
          m
        ) =>
          m.dispose()
          o.dispose()
      }
    }
    ps.lastOption.foreach(top => rootHandle = pageMounts(top.id)._2.handle)
  }

  def run(
    title:  String,
    width:  Int = 480,
    height: Int = 460
  )(
    ui: Owner ?=> AppRoot
  ): Unit = {
    // The signal graph belongs to whichever thread reaches it first; here that is the main
    // thread. Installing the guard makes a stray cross-thread write fail loudly instead of
    // corrupting the graph (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)
    build = ui

    // From here AppKit's run loop owns this thread, so Scala must not look Managed to the
    // GC while it is parked there (S3). `sui_app_start` does not return.
    GcState.releaseMainThread()
    Zone(Shim.sui_app_start(width, height, toCString(title), onReady, 0L))
  }

}
