// One plugin. It brings sbt-scala-native with it; the Thicket dependencies, the iOS link
// settings and the Swift shim come with `enablePlugins(ThicketIosPlugin)` in build.sbt.
//
// `THICKET_VERSION` is what `bin/verify-getting-started-apple.sh` sets, so this template is
// checked against the working tree rather than going stale next to it.
addSbtPlugin("dev.thicket" % "sbt-thicket" % sys.env.getOrElse("THICKET_VERSION", "0.1.0"))

// Only needed while the artefacts come from `publishLocal`. Drop it once you depend on a release.
resolvers += Resolver.defaultLocal
