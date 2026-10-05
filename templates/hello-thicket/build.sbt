ThisBuild / scalaVersion := "3.9.0"

lazy val app = project
  .in(file("."))
  // GTK link flags, the Scala Native mode and GC, and thicket-core + thicket-renderer-gtk at
  // the plugin's own version. Nothing to copy out of Thicket's build.
  .enablePlugins(ThicketGtkPlugin)
  .settings(
    name := "hello-thicket",
    // Only needed while the artefacts come from `publishLocal`.
    resolvers += Resolver.defaultLocal,
    Compile / mainClass := Some("hello.Main")
  )
