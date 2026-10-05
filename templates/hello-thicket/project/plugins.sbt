// One plugin. It brings sbt-scala-native with it, and the Thicket dependencies and GTK link
// settings come with `enablePlugins(ThicketGtkPlugin)` in build.sbt.
//
// `THICKET_VERSION` is what `bin/verify-getting-started.sh` sets, so this template is checked
// against the working tree rather than going stale next to it. A plugin version cannot be read
// from build.sbt, so it is named here as well as there.
addSbtPlugin("dev.thicket" % "sbt-thicket" % sys.env.getOrElse("THICKET_VERSION", "0.1.0"))

// Only needed while the artefacts come from `publishLocal`. Drop it once you depend on a release.
resolvers += Resolver.defaultLocal
