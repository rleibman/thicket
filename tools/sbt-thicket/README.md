# sbt-thicket

The link settings a Thicket app needs, published instead of copied.

```scala
// project/plugins.sbt
addSbtPlugin("dev.thicket" % "sbt-thicket" % "<version>")

// build.sbt
lazy val app = project.in(file(".")).enablePlugins(ThicketGtkPlugin)
```

`ScalaNativePlugin` is a dependency of this plugin, so an app no longer adds it itself, and
`enablePlugins(ThicketGtkPlugin)` enables it too. Before this existed, a GTK app copied
twelve lines of `nativeConfig` out of Thicket's own `build.sbt` — the `pkg-config` flags,
the GC, the LTO and the mode — and got a link failure with nothing pointing at the cause if
it got the GC wrong (#40).

## One definition, compiled twice

`shared/src/main/scala/thicket/sbt/ThicketNativeFlags.scala` is the only copy of the flags.
It is compiled into this plugin, and **also** into Thicket's own meta-build, which adds that
directory to `Compile / unmanagedSourceDirectories` in `project/build.sbt`. So
`build.sbt`'s `gtkNativeSettings` and this plugin's `thicketGtkSettings` are the same bytes
calling the same method; there is no second copy to keep in step.

The cost: the meta-build will not load if that path goes stale, and anything the shared file
references must be on both compile classpaths — sbt and Scala Native's `tools` are the whole
of it. The circular alternative, Thicket's build depending on the published plugin, would
need a plugin release before the renderer could build at all.

`project/build.sbt` refuses to load the build if `build.sbt` grows its own `pkg-config` or
`xcrun` command line again, or stops calling `ThicketNativeFlags`. That check started as a
zio-test suite reading `build.sbt`, and it did not work: sbt reported "No tests to run" for
every subsequent edit, because a build file is not one of a test's inputs — the guard ran
once and then silently stopped guarding. At build load it runs every time, and a failure
stops the load. `SharedFlagsSpec` keeps the part a test is good at: what the flags are.

## No Apple plugin yet

`ThicketNativeFlags.apple` and `ThicketNativeFlags.ios` are here, and they are the
definitions `build.sbt` links the macOS and iOS targets with. There is no
`ThicketApplePlugin`, because flags are not what is missing: an Apple consumer needs
`libthicketapple.a`, a Swift static library this repository builds with a shell script and
does not publish, and on iOS there is no `main` at all — the Swift host owns it. A plugin
pointing `-L` at a directory nobody filled would be worse than none. Issue #41 is the one
that makes an Apple getting-started real.

Neither Apple function has been run since being moved here. They compile, and they are
byte-for-byte what worked in `build.sbt`; there was no Mac available, which is a weaker
claim than verified.
