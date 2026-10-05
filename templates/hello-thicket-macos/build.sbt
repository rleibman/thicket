ThisBuild / scalaVersion := "3.9.0"

lazy val app = project
  .in(file("."))
  // AppKit link flags and the Swift shim — unpacked from the published thicket-renderer-apple
  // jar — plus thicket-core and thicket-renderer-apple at the
  // plugin's own version. Needs Xcode's command-line tools; nothing to clone or build.
  .enablePlugins(ThicketMacPlugin)
  .settings(
    name := "hello-thicket",
    // Only needed while the artefacts come from `publishLocal`.
    resolvers += Resolver.defaultLocal,
    Compile / mainClass := Some("hello.Main")
  )
