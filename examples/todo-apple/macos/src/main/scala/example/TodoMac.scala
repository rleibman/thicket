package example

import thicket.renderer.apple.AppleApp

/** The macOS (AppKit) host for [[TodoApp]].
  *
  * AppKit apps own their own process: `main` starts, `AppleApp.run` hands the thread to
  * `NSApp.run()`, and never returns. Compare [[TodoIos]], where the process is already
  * running by the time Scala is reached.
  */
object TodoMac {

  def main(args: Array[String]): Unit = AppleApp.run("Todo", 480, 460)(AppleSelfTest.build())

}
