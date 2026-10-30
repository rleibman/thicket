# 14. Releasing: versions, compatibility and what a consumer needs

## 14.1 Versions come from git tags

`GitVersioning` owns `version`. Nothing in `build.sbt` sets it, and an explicit
`ThisBuild / version := ...` is **silently overridden** — a hard-coded `"0.1.0-SNAPSHOT"` sat
on that line for a while and never reached a single artefact.

| What git says | What the artefact is called | Publishable? |
|---|---|---|
| on tag `v0.2.0` | `0.2.0` | yes: staged for Central |
| three commits past `v0.2.0` | `0.2.0-3-gdeadbee` | **no**: `publishTo` is `None`, so `publish` fails |
| no tag reachable (a shallow clone) | `0.1.0-<sha>` (`git.baseVersion` + sha) | no |
| uncommitted changes | the above plus `-SNAPSHOT` | to the Central snapshot repository |

Tags are `v`-prefixed; `gitTagToVersionNumber` strips the `v` and ignores anything that is
not a version, so a tag like `demo-recording` does not become one.

`v0.1.0` is tagged. A release version is `X.Y.Z`, optionally `-M<n>` or `-RC<n>`; anything
else between tags is for `publishLocal` only. Until 2026-10-20 every non-snapshot version
was staged as a release, which would have made `0.1.0-3-gdeadbee` a permanent artefact on
Central (review 2026-10-20, P1-2).

## 14.2 `early-semver`

`versionScheme := Some("early-semver")`, declared so coursier and sbt can tell an eviction
from a breaking change rather than inferring it from the number.

Before 1.0, that means: **`0.x.y` → `0.x.z` is binary compatible, `0.x` → `0.y` need not be.**
After 1.0 it means the usual thing. The scheme is a promise to tooling, so it is worth being
honest about which line is which rather than picking the number that sounds better.

What counts as breaking here is wider than it looks, because of two deliberate design
choices:

- **`Prop` is an exhaustive `enum` under `-Werror`.** Adding a case breaks every renderer
  that has not handled it — including one outside this repo. A new prop is a **minor** bump
  before 1.0 and a **major** one after.
- **`Renderer` is a trait an app may implement.** Adding an abstract member to it is the
  same kind of break. `WidgetKind` is the same shape of hazard.

Neither is an accident — that pressure is what stops a widget silently doing nothing on one
platform — but it does mean "we only added something" is not automatically compatible.

## 14.3 What a consumer actually needs

The artefacts, with explicit suffixes, because sbt 2 has no `%%%`:

| Artefact | For |
|---|---|
| `net.leibman:thicket-core_3` / `_native0.5_3` | the element tree, signals, navigation |
| `net.leibman:thicket-renderer-gtk_native0.5_3` | the GTK4 renderer |
| `net.leibman:thicket-effect-zio_3` / `_native0.5_3` | the ZIO bridge, if wanted |
| `net.leibman:thicket-storage-gtk_native0.5_3` / `thicket-storage-android_3` | app directories and preferences (`thicket-storage` comes with them) |
| `net.leibman:thicket-sqlite_3` / `_native0.5_3`, `thicket-sqlite-android_3` | SQLite through JDBC; on Native the driver comes with it, on Android add `thicket-sqlite-android` |
| `net.leibman:thicket-secrets-gtk_native0.5_3` / `thicket-secrets-android_3` | tokens and passwords in the platform's secure store (`thicket-secrets` comes with them); libsecret is optional at run time on GTK |
| `net.leibman:thicket-device-gtk_native0.5_3` / `thicket-device-android_3` | the app's lifecycle and permissions (`thicket-device` comes with them) |
| `net.leibman:thicket-location_3` / `_native0.5_3`, `thicket-location-android_3` | the device's position; on GTK, `Location.unavailable` from `thicket-location` |
| `net.leibman:thicket-motion_3` / `_native0.5_3`, `thicket-motion-android_3` | acceleration, rotation rate and attitude; on GTK, `Motion.unavailable` from `thicket-motion` |
| `net.leibman:thicket-clipboard-gtk_native0.5_3` / `thicket-clipboard-android_3` | text on the system clipboard (`thicket-clipboard` comes with them) |
| `net.leibman:thicket-haptics_3` / `_native0.5_3`, `thicket-haptics-android_3` | haptic taps; on GTK, `Haptics.none` from `thicket-haptics` |
| `net.leibman:thicket-pickers-gtk_native0.5_3` / `thicket-pickers-android_3` | the photo picker, the document picker and save dialog (`thicket-pickers` comes with them) |
| `net.leibman:thicket-renderer-android_3` | the Android renderer (plain JVM bytecode, `-release 17`; R8 dexes it) |
| `net.leibman:thicket-renderer-apple_native0.5_3` | the AppKit and UIKit renderers, with the Swift shim inside the jar |
| `net.leibman:thicket-storage-apple_native0.5_3` | app directories and preferences on macOS and iOS (`thicket-storage` comes with it); no Swift |
| `net.leibman:thicket-secrets-apple_native0.5_3` | tokens and passwords in the Keychain on macOS and iOS (`thicket-secrets` comes with it); no Swift, and it links Security itself. An iOS app needs keychain entitlements, on the simulator too (`modules/secrets/README.md`) |
| `net.leibman:thicket-http-apple_native0.5_3` | an sttp `SyncBackend` over `URLSession`, for iOS (no libcurl there), with its own Swift library inside the jar |
| `net.leibman:thicket-http-gtk_native0.5_3` | an sttp `SyncBackend` over libcurl, in place of sttp's `CurlBackend`; libcurl is loaded at run time, so no `-dev` package |
| `net.leibman:thicket-http-android_3` | sttp's `HttpURLConnectionBackend` with repeated headers and cancellation by interrupt (plain JVM bytecode, `-release 17`) |

`thicket-signals` and `thicket-renderer-api` come in transitively; naming them is only
necessary to write a renderer.

And the plugin, which is what a consumer actually adds:

| Artefact | For |
|---|---|
| `net.leibman:sbt-thicket` (`_sbt2_3`) | the build: GTK or Apple link flags, the Scala Native mode and GC, and the dependencies above. `ThicketGtkPlugin`, `ThicketMacPlugin`, `ThicketIosPlugin` |

The plugin takes the group id from the build that published it (`BuildInfo.organization`),
as it takes the version, so it cannot ask for a group the framework was not published under.

```scala
// project/plugins.sbt
addSbtPlugin("net.leibman" % "sbt-thicket" % thicketVersion)

// build.sbt
lazy val app = project.in(file(".")).enablePlugins(ThicketGtkPlugin)
```

That is the whole of it. `ScalaNativePlugin` comes in as a dependency of the plugin, and
`thicket-core` and `thicket-renderer-gtk` are added at the plugin's **own** version — plugin
and framework come out of one build, so they cannot disagree, and
`bin/verify-getting-started.sh` fails if they ever do.

Until #40 this was the thing phase 5 left undone: a GTK app copied about a dozen lines of
`nativeConfig` out of `templates/hello-thicket/build.sbt`, which coupled every app to a build
detail of ours, and an app that got the GC wrong failed at link time with nothing pointing at
the cause.

The flags are not two copies kept in step. `ThicketNativeFlags` is one source file, compiled
into the plugin *and* into this repository's meta-build, which `project/build.sbt` wires up —
so `build.sbt` and `thicketGtkSettings` are the same method on the same bytes, and the build
refuses to load if anyone computes the flags in `build.sbt` again.
`tools/sbt-thicket/README.md` has the detail and what the arrangement costs.

### Apple (published since #41)

Apple. `ThicketNativeFlags.apple` and `.ios` are shared the same way, and there is no Apple
plugin, because flags are not what is missing: an Apple consumer needs `libthicketapple.a`,
a Swift static library this repository builds with a shell script and does not publish, and
on iOS the Swift host owns `main`. #41 is the issue that makes an Apple getting-started real.

*Update, 2026-10-05 (#41):* now published. `thicket-renderer-apple`'s jar carries the shim
for macOS and the iOS simulator, `ThicketMacPlugin` and `ThicketIosPlugin` unpack and link it,
and `bin/verify-getting-started-apple.sh` builds and runs `templates/hello-thicket-macos` and
`templates/hello-thicket-ios` outside the repository. See `tools/sbt-thicket/README.md`.

## 14.4 Checking the getting-started still works

```bash
./bin/verify-getting-started.sh
```

Publishes the framework **and `sbt-thicket`** locally, copies `templates/hello-thicket`
**outside** the repo, and builds and runs it against the published artefacts. The plugin is
resolved by the template's meta-build, so a plugin that does not publish or does not load
fails before anything is compiled. Outside is the point: building the
template in place would resolve the modules as project dependencies and prove nothing about
what a stranger gets from a jar.

A GUI app does not exit on its own, so staying up is the pass — the script treats a 10s
timeout as success and any other exit, or a `signal 11`, `CRITICAL` or theme-parser line, as
failure.

Phase 5's exit criterion is a person getting an app running in under thirty minutes. Only a
person can measure that; this checks the half that does not need one.

## 14.5 Cutting a release

The group id is `net.leibman` (decision log, 2026-10-20), on the Central Portal.

Prerequisites, once per machine: a GPG key whose public half is on a keyserver, and
`SONATYPE_USERNAME` / `SONATYPE_PASSWORD` in the environment (a Central Portal user token,
not the login), which sbt 2's built-in `sonaUpload` reads. Neither goes in the repository;
`.gitignore` refuses `*.asc`, `*.gpg` and `.env*`.

The jars come from two machines, because `thicket-renderer-apple` carries a Swift library
only Xcode can build — off a Mac its `publish` is skipped rather than shipping a jar with no
shim in it.

1. On a clean `main` that CI has passed: `git tag v0.2.0` and push the tag.
2. Check the version is the release and not a describe version:
   `sbt "show coreJVM/version"` must print `0.2.0`.
3. **On the Mac** (it can build every artefact, the Apple one included):
   ```bash
   modules/renderer-android/setup.sh                  # android.jar, for the Android renderer
   sbt "publishSigned; platforms/publishSigned"       # root + renderers, into localStaging
   sbt sonaUpload                                     # upload; release by hand in the Portal UI
   ```
   `sonaRelease` instead of `sonaUpload` publishes as soon as the bundle validates. Prefer
   `sonaUpload` until the procedure has run once end to end: a Central release cannot be
   withdrawn.
4. Run `bin/verify-getting-started.sh` and `bin/verify-getting-started-apple.sh` against the
   released version (`THICKET_VERSION=0.2.0`) once Central shows it.

The examples have `publish / skip := true`; the `platforms` aggregate publishes the three
renderers and the platform modules that are not in `root`: `thicket-http-apple`,
`thicket-storage-android`, `thicket-sqlite-android`, `thicket-secrets-android`,
`thicket-secrets-apple`, `thicket-device-gtk`, `thicket-device-android` and
`thicket-location-android`, `thicket-motion-android`, `thicket-clipboard-gtk`,
`thicket-clipboard-android`, `thicket-haptics-android`, `thicket-pickers-gtk` and
`thicket-pickers-android`.
