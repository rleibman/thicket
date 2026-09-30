# 4. Scala toolchain reality, per platform

The framework is only as feasible as the compilers underneath it. This section is
deliberately blunt about what is proven, what is plausible, and what is unknown.

## 4.1 The three backends

| Backend          | Output                      | Concurrency                                                                                                | Startup                                                                                 | Interop                                                                                                 | Link/compile speed                                                                                                                |
|------------------|-----------------------------|------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| **JVM**          | bytecode                    | full threads                                                                                               | JVM warm-up (irrelevant on Android — ART is AOT/JIT hybrid and is the platform runtime) | Java/Kotlin libraries directly                                                                          | fast incremental (Zinc), JVM hot-swap possible                                                                                    |
| **Scala.js**     | JS / ES modules             | single-threaded                                                                                            | instant in a webview                                                                    | JS libraries via facades                                                                                | fastest incremental linking (sub-second with `fastLinkJS` + Vite)                                                                 |
| **Scala Native** | native object code via LLVM | real threads since 0.5.0 (Apr 2024); Java memory model for shared vars; `java.util.concurrent` thread-safe | native, milliseconds                                                                    | C ABI both directions: `extern` calls out, `@exported` functions in; can build static/dynamic libraries | **slow**: whole-program linking + LLVM; tens of seconds to minutes for release, still many seconds for debug. This is the DX risk |

Scala Native details that matter here (0.5.x, latest snapshots 0.5.12/0.5.13 in 2026):

- `nativeConfig.withTargetTriple(...)` enables cross-compilation and has existed
  since 0.4.0. Officially supported hosts/targets are Linux, macOS, Windows on
  x86_64/arm64. **iOS and Android are not documented targets** — nothing forbids
  `arm64-apple-ios`, but the runtime (GC, threading, `posix` shims, `javalib`)
  has never been validated there by the project. **[unverified — Spike S1]**
- Build target `libraryStatic` / `libraryDynamic` + `@exported("c_name")` produce
  a `.a`/`.dylib` that Xcode or Gradle/NDK can link. This is the exact shape needed
  for "Scala core, Swift shim".
- GC choices: Immix (default), Commix (parallel), Boehm, none. Behaviour under iOS
  memory pressure and with foreign threads calling in (the UI main thread is
  created by UIKit, not by Scala) must be tested. Blocking extern functions are
  supported so the GC can cooperate with long-running foreign calls.
- `sn-bindgen` generates Scala 3 bindings from **C** headers via libclang. It does
  not understand Objective-C or C++; ObjC/WinRT surfaces need a C shim (see §4.3).

## 4.2 Android

Natural path: **Scala 3 on the JVM**, compiled to bytecode, dexed by AGP/R8.

Known state:

- Works in principle; community guides (ScalaOnAndroid, scalac blog) did it with
  Scala 2.13/3 via Gradle's `scala` plugin producing a JAR consumed by an Android
  library module, plus R8 shrinking and keep rules. Gradle 8.13 improved Scala
  toolchain declaration.
- **Pain points to verify [Spike S2]:** (1) Scala 3 stdlib size after R8 and APK
  delta; (2) Scala 3 runtime features that touch `sun.misc.Unsafe` /
  `VarHandle`/`MethodHandle` (lazy vals, `scala.runtime.LazyVals`) under ART and
  min-SDK 26 desugaring; (3) AGP 9 dropping legacy ProGuard files; (4) IDE story (IntelliJ + Scala plugin *and* Android
  plugin in one project); (5) Android's
  `Looper`/main-thread rule; (6) sbt vs Gradle — the realistic model is *sbt/mill
  builds the Scala AAR, Gradle builds the app*, or a Gradle plugin that invokes Zinc.
- Alternative: Scala Native via the NDK, driving `android.view.*` over JNI. Possible
  but the UI is Java; every widget call would cross JNI. Rejected except as a
  fallback if the JVM path fails.
- Hot reload: Android Studio "Apply Changes" works on dex; Scala classes are just
  classes. Plausible but unproven [Spike S2].

## 4.3 iOS and macOS

Only Scala Native can run here (no JVM on iOS; Scala.js only inside a webview or
JS engine, i.e. React Native / JavaScriptCore).

Plan: **Scala Native static library + Swift shim with a C ABI**.

- Xcode project (generated by our tooling) contains: `App.swift` entry, the Scala
  `.a` (built per arch/SDK: `arm64-apple-ios`, `arm64-apple-ios-simulator`,
  `x86_64-apple-ios-simulator`, `arm64-apple-macos`, `x86_64-apple-macos`), and a
  small Swift package `ThicketShim` exposing `@_cdecl` functions such as
  `sui_button_new`, `sui_view_set_frame`, `sui_button_on_tap(fnptr, ctx)`.
- Scala Native side: `extern` declarations for the shim (generated by sn-bindgen
  from the shim's C header), callbacks via `CFuncPtr`.
- Why a Swift shim rather than binding UIKit directly via the ObjC runtime (`objc_msgSend`)? Direct binding is possible
  but variadic `objc_msgSend`, ObjC
  blocks, ARC semantics and header generation for ObjC are a research project of
  their own. The shim confines platform knowledge to ~2–4k lines of Swift per
  Apple platform and is what any Swift developer can maintain. AppKit shares most
  of it (macOS is a cheap second target because of this).
- Threading: UIKit owns the main thread. Scala code runs on it when called from the
  shim; background work uses Scala Native threads; results are marshalled back via
  `DispatchQueue.main.async` exposed as a shim function.
- App Store: static native code is fine. No JIT, no dynamic code loading — Scala
  Native is AOT, so compliant. Bitcode is no longer required.
- Debugging: LLDB works on Scala Native binaries with DWARF; symbol names are
  mangled but readable. Xcode's debugger will show Scala frames [to verify in S1].
- Link time and the dev loop: linking a Scala Native library for iOS on every
  edit is minutes, not seconds. This is why the plan includes a Scala.js dev canvas
  and JVM-hosted desktop runs for iteration, with device builds as the check step.

Unknowns to settle in **Spike S1**: does the Scala Native runtime (Immix GC,
threads, `javalib` time/locale/IO) start and run on an iOS device and simulator?
Binary size of a hello-world `.a` after dead-code elimination? Does the GC cope
with the main thread being foreign? Does `arm64-apple-ios-simulator` triple work (simulator slices use a different
platform tag from device arm64)?

## 4.4 Linux

Scala Native executables are first-class. `com.indoorvivants.gnome::gtk4` bindings
exist and are published. GTK4 gives native-looking widgets on GNOME and acceptable
ones elsewhere; Wayland and X11 both supported by GTK. GMainLoop drives the UI
thread. Lowest-risk native target; good place to prove the renderer contract.

## 4.5 Windows

Scala Native supports Windows x86_64 (MSVC toolchain / clang). UI options:

- **WinUI 3** (modern look) is a WinRT/COM API — no plain C headers; needs a C++/WinRT
  or C# shim exposing a C ABI, same pattern as the Swift shim.
- **Win32 common controls** are pure C and bindable directly with sn-bindgen but look
  dated (Windows 7 era) unless themed.
- Realistic plan: Win32 first for proof, WinUI 3 shim as the P2 deliverable.

## 4.6 Browser (dev canvas only)

Scala.js + a DOM renderer for the same element tree, with CSS themes approximating
iOS/Android/macOS looks. Purpose: sub-second reload during development, component
gallery, docs, and CI screenshot tests. Not a production target (Laminar exists).

## 4.7 Cross-building the core

`sbt-crossproject` (JVM/JS/Native) is mature. The core, the signals library, layout
adapter and the element tree must be `%%%` cross-published with zero
platform-specific code; renderers are separate modules per platform. Third-party
dependencies the core may use must themselves be cross-published for all three
backends — this rules out Airstream (JS only) and most JVM-only libraries.

## 4.8 Risk register (toolchain)

| Risk                                                               | Likelihood | Impact                                       | Mitigation                                                                   |
|--------------------------------------------------------------------|------------|----------------------------------------------|------------------------------------------------------------------------------|
| Scala Native runtime does not work on iOS                          | Medium     | Fatal for Option B; falls back to Option A/D | Spike S1 first, before anything else                                         |
| Scala Native link times make device iteration miserable            | High       | DX                                           | Dev canvas (JS) + desktop JVM/Native runs; incremental linking work upstream |
| Scala 3 stdlib on Android bloats APK or hits ART incompatibilities | Medium     | Adoption                                     | Spike S2 measures; R8 rules; `-release 8`; avoid reflection                  |
| Yoga C API changes                                                 | Low        | Contained                                    | Pin version; own adapter                                                     |
| Single-maintainer upstreams (sn-bindgen, gtk bindings)             | Medium     | Bus factor                                   | Vendor generated bindings into this repo; contribute upstream                |
| Apple/Google policy changes on foreign toolchains                  | Low        | Fatal per platform                           | AOT native code is the most policy-safe form there is                        |

## Sources

- https://www.scala-native.org/en/stable/user/sbt.html
- https://www.scala-native.org/en/stable/user/interop.html
- https://scala-native.org/en/stable/changelog/0.5.x/0.5.0.html
- https://github.com/indoorvivants/sn-bindgen
- https://github.com/indoorvivants/scala-native-gtk-bindings
- https://mateuszkubuszok.github.io/ScalaOnAndroid/
- https://scalac.io/blog/android-project-with-scala-reflections-on-starting/
- https://docs.gradle.org/8.13/release-notes.html
- https://android-developers.googleblog.com/2025/11/use-r8-to-shrink-optimize-and-fast.html
