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

## Apple: `ThicketMacPlugin` and `ThicketIosPlugin`

An Apple app needs more than flags: the renderer's `sui_*` functions are Swift, in
`libthicketapple.a`, which Thicket builds with a shell script. So Thicket's build puts the
shim in the published `thicket-renderer-apple` jar — both slices (`macos`, `ios-sim`) and
the headers, under `thicket-apple/`, built at package time — and these plugins unpack it
from the resolved jar (#41). Nothing has to be cloned or built.

```scala
lazy val app = project.in(file(".")).enablePlugins(ThicketMacPlugin)   // macOS: a binary
lazy val app = project.in(file(".")).enablePlugins(ThicketIosPlugin)   // iOS: a static library
```

- **`ThicketMacPlugin`** adds `thicket-core` and `thicket-renderer-apple` and links the
  unpacked shim with `ThicketNativeFlags.apple`.
- **`ThicketIosPlugin`** links a static library with `ThicketNativeFlags.ios`. iOS needs a
  Swift host that owns `main`, which links that library, the shim and itself; the template
  in `templates/hello-thicket-ios` carries one, and `ios-app/build-app.sh` does the link.
- **`thicketAppleShim`** unpacks the shim and returns where it went. In sbt 2 a project's
  `target` is below `target/out/`, so ask (`sbt 'print thicketAppleShim'`) rather than assume.

Both were first run by `bin/verify-getting-started-apple.sh`, which publishes locally, copies
the two templates outside the repository and builds and runs them: the macOS app owns a window
titled "Hello Thicket" after 10 s, and the iOS app is still running in the simulator after 10 s.
