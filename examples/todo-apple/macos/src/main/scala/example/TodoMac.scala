package example

import thicket.renderer.apple.AppleApp

/** The macOS (AppKit) host for [[TodoApp]].
  *
  * AppKit apps own their own process: `main` starts, `AppleApp.run` hands the thread to
  * `NSApp.run()`, and never returns. Compare [[TodoIos]], where the process is already
  * running by the time Scala is reached.
  */
object TodoMac {

  def main(args: Array[String]): Unit =
    // `LazyProbe` is a 10 000-row screen and nothing else: the shared TodoApp cannot start
    // on Apple until Forgejo #9, and #5's measurement should not be hostage to that.
    if sys.env.contains("THICKET_LAZYTEST") then AppleApp.run("10 000 rows", 480, 640)(LazyProbe.build())
    else AppleApp.run("Todo", 480, 460)(AppleSelfTest.build())

}
