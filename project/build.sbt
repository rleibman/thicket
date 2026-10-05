// The meta-build compiles Thicket's own copy of the sbt-thicket plugin's flag definitions,
// straight out of the plugin's source tree. One file, two compilations: `build.sbt`'s
// `gtkNativeSettings` and the published plugin's `thicketGtkSettings` call the same method on
// the same bytes, so they cannot drift (#40).
//
// Only `shared/` is added, not the whole plugin. The AutoPlugin itself must stay out: sbt
// discovers AutoPlugins in the meta-build as well as in plugin jars, and compiling
// `ThicketGtkPlugin` here would make it a plugin of *this* build by accident.
//
// Everything it needs is already on this classpath — sbt itself, and Scala Native's `tools`
// via `addSbtPlugin("org.scala-native" % "sbt-scala-native")` in plugins.sbt. If this path
// ever goes stale the meta-build fails to compile, which is the loud failure we want: there
// is no local copy left for it to fall back to.
//
// The guard is inside the setting on purpose. Sharing one definition makes drift impossible,
// so the only way back to two copies is for someone to paste a `pkg-config` or `xcrun` command
// line into `build.sbt` again — and that is not something a *test* can be relied on to catch:
// as a zio-test suite reading build.sbt it ran once and sbt then reported "No tests to run"
// for every later edit, because build.sbt is not one of a test's inputs. Measured, after
// sabotaging build.sbt and watching the suite not run at all. Here it is evaluated on every
// load of the build, and a failure stops the load rather than being skipped.
Compile / unmanagedSourceDirectories += {
  val repo = baseDirectory.value.getParentFile
  val shared = repo / "tools" / "sbt-thicket" / "shared" / "src" / "main" / "scala"

  val b = IO.read(repo / "build.sbt")
  val copied = Seq(
    Option.when(b.contains("\"pkg-config"))("a pkg-config command line"),
    Option.when(b.contains("\"xcrun\""))("an xcrun command line"),
    Option.when(!b.contains("ThicketNativeFlags.gtk"))("no call to ThicketNativeFlags.gtk"),
    Option.when(!b.contains("ThicketNativeFlags.apple"))("no call to ThicketNativeFlags.apple")
  ).flatten
  if (copied.nonEmpty) {
    sys.error(
      s"""|build.sbt has ${copied.mkString(" and ")}.
          |
          |The Scala Native link flags live in exactly one place — ThicketNativeFlags, in
          |  tools/sbt-thicket/shared/src/main/scala/thicket/sbt/ThicketNativeFlags.scala
          |— which this meta-build and the published sbt-thicket plugin both compile. Computing
          |them in build.sbt as well puts back the second copy issue #40 exists to remove: the
          |two can then disagree, and a disagreement about the GC shows up as a link failure in
          |somebody else's project with nothing pointing at the cause.
          |
          |Put the change in ThicketNativeFlags and call it from build.sbt.
          |""".stripMargin
    )
  }
  shared
}
