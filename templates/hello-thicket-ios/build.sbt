ThisBuild / scalaVersion := "3.9.0"

lazy val app = project
  .in(file("."))
  // iOS is not a binary: the Swift host in ios-app/ owns `main`, so this links a static
  // library for the simulator, and `thicketAppleShim` unpacks the Swift shim the host links
  // against. ios-app/build-app.sh runs both and does the rest.
  .enablePlugins(ThicketIosPlugin)
  .settings(
    name := "hello-thicket-ios",
    // Only needed while the artefacts come from `publishLocal`.
    resolvers += Resolver.defaultLocal
  )
