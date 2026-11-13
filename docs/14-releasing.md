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
| `net.leibman:thicket-device-apple_native0.5_3` | the app's lifecycle and permissions on macOS and iOS (`thicket-device` comes with it), with its own Swift library inside the jar; an iOS app adds the usage strings for what it asks |
| `net.leibman:thicket-location_3` / `_native0.5_3`, `thicket-location-android_3` | the device's position; on GTK and macOS, `Location.unavailable` from `thicket-location` |
| `net.leibman:thicket-location-apple_native0.5_3` | the device's position on iOS, from `CLLocationManager` (Scala only: it uses `thicket-device-apple`'s Swift library); the app's `Info.plist` needs `NSLocationWhenInUseUsageDescription` |
| `net.leibman:thicket-motion_3` / `_native0.5_3`, `thicket-motion-android_3` | acceleration, rotation rate and attitude; on GTK, `Motion.unavailable` from `thicket-motion` |
| `net.leibman:thicket-motion-ios_native0.5_3` | motion on iOS through `CMMotionManager`, with its own Swift library inside the jar (`ios-sim/libthicketmotion.a`); on macOS, `Motion.unavailable` from `thicket-motion` |
| `net.leibman:thicket-clipboard-gtk_native0.5_3` / `thicket-clipboard-android_3` | text on the system clipboard (`thicket-clipboard` comes with them) |
| `net.leibman:thicket-haptics_3` / `_native0.5_3`, `thicket-haptics-android_3` | haptic taps; on GTK, `Haptics.none` from `thicket-haptics` |
| `net.leibman:thicket-pickers-gtk_native0.5_3` / `thicket-pickers-android_3` | the photo picker, the document picker and save dialog (`thicket-pickers` comes with them) |
| `net.leibman:thicket-pickers-apple_native0.5_3` | the same on macOS and iOS: `PHPickerViewController` and `UIDocumentPickerViewController`, `NSOpenPanel` and `NSSavePanel` sheets, with its own Swift library inside the jar (`macos/` and `ios-sim/libthicketpickers.a`); the Apple renderer comes with it |
| `net.leibman:thicket-camera_3` / `_native0.5_3`, `thicket-camera-android_3` | taking a photo with the camera app; on GTK, `Camera.unavailable` from `thicket-camera` |
| `net.leibman:thicket-camera-apple_native0.5_3` | taking a photo on iOS with `UIImagePickerController`, with its own Swift library inside the jar (`ios-sim/libthicketcamera.a`); the app's `Info.plist` needs `NSCameraUsageDescription`; on macOS, `Camera.unavailable` from `thicket-camera` |
| `net.leibman:thicket-share_3` / `_native0.5_3`, `thicket-share-android_3` | the share sheet; on Android the app declares `ThicketFileProvider`; on GTK, `Share.unavailable` |
| `net.leibman:thicket-camera-preview_3` / `_native0.5_3`, `thicket-camera-preview-android_3` | a live camera preview in the app's layout (`docs/17`): Camera2 on Android, the app declaring `CAMERA`; elsewhere, `CameraPreview.unavailable` from `thicket-camera-preview` |
| `net.leibman:thicket-renderer-android_3` | the Android renderer (plain JVM bytecode, `-release 17`; R8 dexes it) |
| `net.leibman:thicket-renderer-apple_native0.5_3` | the AppKit and UIKit renderers, with the Swift shim inside the jar |
| `net.leibman:thicket-storage-apple_native0.5_3` | app directories and preferences on macOS and iOS (`thicket-storage` comes with it); no Swift |
| `net.leibman:thicket-secrets-apple_native0.5_3` | tokens and passwords in the Keychain on macOS and iOS (`thicket-secrets` comes with it); no Swift, and it links Security itself. An iOS app needs keychain entitlements, on the simulator too (`modules/secrets/README.md`) |
| `net.leibman:thicket-clipboard-apple_native0.5_3` | text on the general pasteboard, `NSPasteboard` / `UIPasteboard` (`thicket-clipboard` and the Apple renderer come with it); no Swift, and it links UIKit or AppKit itself |
| `net.leibman:thicket-share-apple_native0.5_3` | the share sheet on macOS and iOS, `NSSharingServicePicker` / `UIActivityViewController` (`thicket-share` comes with it); no Swift, and it links UIKit or AppKit itself |
| `net.leibman:thicket-haptics-apple_native0.5_3` | haptic taps: iOS's feedback generators, macOS's `NSHapticFeedbackManager` (`thicket-haptics` comes with it); no Swift |
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
bin/verify-getting-started.sh                          # this checkout, published locally
THICKET_VERSION=0.2.0 bin/verify-getting-started.sh    # a release, from Maven Central alone
```

The first form publishes the framework **and `sbt-thicket`** locally, copies
`templates/hello-thicket` **outside** the repo, and builds and runs it against the published
artefacts. The plugin is resolved by the template's meta-build, so a plugin that does not
publish or does not load fails before anything is compiled. Outside is the point: building the
template in place would resolve the modules as project dependencies and prove nothing about
what a stranger gets from a jar.

The second form checks a release. Nothing is published; sbt gets an empty coursier cache, Ivy
home and global base, so a jar left in `~/.ivy2/local` cannot stand in for Central; and the
script fails unless every thicket jar the build used was downloaded from `repo1.maven.org`.
Until 2026-11-12 the script ignored `THICKET_VERSION` and always published locally, so "run it
against the release" checked the local jars. Measured: `0.2.0` passes; `0.2.0-SNAPSHOT`, which
exists only in `~/.ivy2/local`, fails to resolve.

`bin/verify-getting-started-apple.sh` takes the same two forms on the Mac, for
`templates/hello-thicket-macos` and `templates/hello-thicket-ios` (the simulator). Against
`0.2.0` from Central, 2026-11-12: the macOS app showed its 320×192 window and the iOS app came
up in the simulator.

A GUI app does not exit on its own, so staying up is the pass — the script treats a 10s
timeout as success and any other exit, or a `signal 11`, `CRITICAL` or theme-parser line, as
failure.

Phase 5's exit criterion is a person getting an app running in under thirty minutes. Only a
person can measure that; this checks the half that does not need one.

## 14.5 Cutting a release

The group id is `net.leibman` (decision log, 2026-10-20), on the Central Portal.

**The Mac builds; Linux signs and uploads** (decision log, 2026-11-12). Only a Mac can build
every artefact: seven Apple modules carry Swift libraries that need Xcode, and off a Mac their
`publish` is skipped rather than shipping a jar with no library in it. The signing key and the
Central token stay on Linux, so the Mac needs neither gpg nor credentials.
`bin/release.sh` does the whole of it.

Prerequisites, once, on Linux:
- a GPG key whose public half is on a keyserver (`keyserver.ubuntu.com` is enough);
- `SONATYPE_USERNAME` / `SONATYPE_PASSWORD` in the environment: a Central Portal **user
  token**, not the login, generated in the account that owns `net.leibman`;
- ssh to the Mac without a password, and the Android SDK's `android.jar` (the Mac has no
  Android SDK; the script copies it over).

None of it goes in the repository; `.gitignore` refuses `*.asc`, `*.gpg` and `.env*`.

1. On a `main` that CI has passed: `git tag -a v0.3.0 -m "…"` and push the tag.
2. Rehearse, which uploads nothing:
   ```bash
   THICKET_MAC_HOST=<mac> THICKET_GPG_KEY=<fingerprint> RELEASE_UPLOAD=0 bin/release.sh 0.3.0
   ```
3. The same without `RELEASE_UPLOAD=0`. The script:
   - checks that the tag is on `origin/main`, and refuses anything but `X.Y.Z`, `-M<n>` or `-RC<n>`;
   - has the Mac clone the tag into a temporary directory and run `publish; platforms/publish`,
     **unsigned**, into `target/sona-staging`;
   - copies the staging back **without** macOS's `._*` files (`COPYFILE_DISABLE=1 tar
     --no-xattrs`; a plain `tar` adds one beside every file);
   - checks every module has a jar, sources, javadoc and a POM with the fields Central
     requires, that every checksum matches and nothing depends on a SNAPSHOT;
   - signs every artefact here and verifies every signature;
   - uploads the bundle as `USER_MANAGED` and waits until Central reports `VALIDATED`.

   **It does not publish.**
4. In the Portal, View Deployments, check the component list, then **Publish** — the only
   permanent step: a Central release can never be withdrawn or replaced — or Drop.
5. Once `repo1.maven.org` has it (10–30 minutes), run both verify scripts with
   `THICKET_VERSION` (§14.4). The search sites lag by hours, and mvnrepository.com, which is
   not Sonatype's, by days or weeks; dependency resolution uses neither.
6. Move the templates' default to the new version, in a PR: the `getOrElse("THICKET_VERSION",
   …)` in each `templates/*/project/plugins.sbt`. A template that defaults to an unpublished
   version fails for anyone who copies it — measured: until 2026-11-12 all three defaulted to
   `0.1.0`, which was never published, and the GTK template failed to resolve
   `sbt-thicket_sbt2_3:0.1.0` with empty caches.

### 0.2.0, the first release (2026-11-09)

Tag `v0.2.0` on `3b37d27`. 69 modules (jar, sources, javadoc, POM, each signed; 52 MB zipped),
deployment `8fe68b2e-b442-43c9-a690-ce6673702adf`, validated, then published by hand. All 69
POMs on `repo1.maven.org` the same day; the jars downloaded from there match the ones uploaded
(checked for four, the Apple renderer among them).

Released knowing two Apple faults that #159 fixes: a `Platform` host shows "not available"
instead of its view, and the Stepper's label drops trailing zeros.

**Rebuilds are not byte-identical.** `bin/release.sh` rehearsed against `v0.2.0` on
2026-11-12: the 44 JVM and Scala.js jars matched Central's exactly, the 25 Scala Native jars
did not. The cause is not established; the build directory (a different temporary clone) is
the likely difference. It does not affect users, but a release cannot be verified by
rebuilding it.

### What the practice run found (2026-11-09)

A full rehearsal ran from Linux on a clean clone tagged `v0.2.0` locally: `publishSigned`
staged 62 modules (jar, sources, javadoc, POM, each signed; 56 MB), every external
dependency resolved on Central, and `sonaUpload` reached `VALIDATED`. The deployment was
then dropped in the Portal; nothing was published. What it settled:

- **The token belongs to the account that owns `net.leibman`**, which is an older Sonatype
  login, not necessarily the one you would sign in with today. A "namespace exists" error
  when adding it means you are in the wrong account.
- **Credentials:** the environment variables work. sbt 2's global directory here is
  `~/.config/sbt/2/`; `~/.sbt/1.0/` is sbt 1's and sbt 2 does not read it.
- **Build on one machine, the Mac.** On non-Mac hosts the seven Apple modules gated by
  `publish / skip := !isMac` are skipped (they require Xcode); this includes `thicket-location-apple`
  even though its Swift code lives in `thicket-device-apple`. The five without (`clipboard-apple`,
  `haptics-apple`, `secrets-apple`, `share-apple`, `storage-apple`) are staged anyway. A Linux upload
  plus a Mac upload of the same version would therefore collide on those five.
- **Rehearse, then Drop** in the Portal (Deployments → the deployment → Drop). Only
  Publish is permanent. `bin/release.sh` now rehearses without uploading (`RELEASE_UPLOAD=0`).
- **Every published module sets `description`**, next to its `name` in `build.sbt`. The
  POM's description is what the Central and Maven search pages show; without one it is the
  module name. A new module adds one.

The examples have `publish / skip := true`; the `platforms` aggregate publishes the three
renderers and the platform modules that are not in `root`: `thicket-http-apple`,
`thicket-storage-android`, `thicket-sqlite-android`, `thicket-secrets-android`,
`thicket-secrets-apple`, `thicket-device-gtk`, `thicket-device-android`, `thicket-device-apple`,
`thicket-location-android`, `thicket-location-apple`, `thicket-motion-android`, `thicket-motion-ios`, `thicket-clipboard-gtk`,
`thicket-clipboard-android`, `thicket-clipboard-apple`, `thicket-haptics-android`,
`thicket-haptics-apple`, `thicket-pickers-gtk`, `thicket-pickers-android`, `thicket-pickers-apple`,
`thicket-camera-android`, `thicket-camera-apple`, `thicket-share-android`, `thicket-share-apple` and
`thicket-camera-preview-android`.
