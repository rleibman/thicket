package example

import scala.scalanative.unsafe.exported
import thicket.renderer.apple.{AppleApp, GcState}

/** The iOS (UIKit) host for [[TodoApp]] — the same tree, the same renderer, a different entry shape.
  *
  * There is no `main` here. iOS 27 refuses to launch an app that does not adopt the UIScene lifecycle (S3), and a scene
  * delegate cannot live in the static library Scala Native produces, so `@main` belongs to the Swift host in
  * `ios-app/`. The host creates the window, hands its root view to the shim, runs `ScalaNativeInit`, and then calls
  * this — on the main thread, which is the only non-Scala thread allowed to enter Scala (S1).
  */
object TodoIos {

  /** Called by the Swift host. `guarded` because this is a host -> Scala entry and the main thread is Unmanaged while
    * the run loop owns it (S3).
    */
  @exported("thicket_todo_start")
  def thicket_todo_start(): Unit =
    GcState.guarded {
      // The root view is not a parameter: the host already gave it to the shim with
      // `sui_set_root_view`, and the renderer reads it back through `sui_root_view()`, the
      // same call the AppKit shim answers. Width and height are ignored on iOS — the window
      // is the screen — but are passed anyway so the two hosts differ in nothing but shape.
      if sys.env.contains("THICKET_POSTTEST") then
        AppleApp.run("postToUi", 320, 120)(PostProbe.build(sys.env("THICKET_POSTTEST")))
      else if sys.env.contains("THICKET_LAZYTEST") then AppleApp.run("10 000 rows", 480, 640)(LazyProbe.build())
      else AppleApp.run("Todo", 480, 460)(AppleSelfTest.build())
    }

}
