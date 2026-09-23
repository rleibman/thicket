package scalaui.renderer.appkit

import scala.scalanative.unsafe.*
import scalaui.core.{AppRoot, Reconciler}
import scalaui.signals.{Owner, Signal, ThreadGuard}

/** Hosts an element tree in an AppKit window.
  *
  * The whole entry point for a macOS target: `AppKitApp.run("Title")(ui)`.
  */
object AppKitApp {

  // A CFuncPtr cannot close over local state, so the mount parameters live here (S4).
  private var build: Owner ?=> AppRoot =
    (_: Owner) ?=> throw IllegalStateException("AppKitApp.run was not given a UI")

  private val rootOwner = Owner()

  /** The mounted root view, available once the window has been built. The demo reads the tree back out of it with
    * [[AppKitInspect]].
    */
  var rootHandle: Shim.Handle = null

  /** Runs `f` on the main thread. Safe from any thread the platform permits — which on Apple means the main thread or a
    * Scala-created thread, never a GCD worker (S1).
    */
  def postToUi(f: () => Unit): Unit = Shim.sui_run_on_main(Handles.tapTrampoline, Handles.registerOneShot(f))

  /** Fired by the shim from `applicationDidFinishLaunching`, so the window and its root view exist by the time the tree
    * is mounted.
    */
  private val onReady: Shim.VoidCb =
    CFuncPtr1.fromScalaFunction((_: Long) =>
      GcState.guarded {
        val renderer = AppKitRenderer()
        given Owner = rootOwner
        val root = build

        val mounted = Reconciler.mount(renderer, root.element)
        rootHandle = mounted.handle
        Shim.sui_insert_after(Shim.sui_root_view(), mounted.handle, null.asInstanceOf[Shim.Handle])

        // Native chrome, driven from AppRoot rather than drawn into the element tree: the
        // window title follows the top screen.
        Signal.effect(Zone(Shim.sui_window_set_title(toCString(root.title()))))
      }
    )

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
