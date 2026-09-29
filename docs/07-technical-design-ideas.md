# 7. Technical design ideas (Option B)

Sketches, not decisions. Code is illustrative Scala 3 and has not been compiled.

## 7.1 Module layout

```
Thicket/
  core/            %%% JVM/JS/Native  element tree, reconciler, theme, units, events
  signals/         %%% JVM/JS/Native  reactive primitives (Var, Signal, computed, effect)
  layout-yoga/     %%% JVM/Native     Yoga bindings (sn-bindgen on Native; JNI/Java lib on JVM)
  layout-web/      JS                 CSS flexbox passthrough for the dev canvas
  renderer-api/    %%% all            the renderer contract (versioned, S-01)
  renderer-android/ JVM               android.view.* backend
  renderer-apple/   Native            calls ThicketShim (Swift, C ABI); UIKit + AppKit
  renderer-gtk/     Native            GTK4 via com.indoorvivants.gnome
  renderer-win/     Native            Win32 first, WinUI3 shim later
  renderer-dom/     JS                dev canvas (HTML/CSS, platform-mimicking themes)
  effect-api/      %%% all            the effect bridge contract (versioned like renderer-api)
  effect-zio/      %%% all            ZIO 2 bridge — reference implementation, built with the core
  effect-cats/     %%% all            cats-effect 3 / fs2 bridge, same contract, later
  tooling/sbt-plugin, tooling/mill-plugin, tooling/cli
  shims/apple/ThicketShim (SwiftPM), shims/win/ (C++/WinRT)
  gallery/          %%% all            component catalogue & perf test-bed
```

## 7.2 Reactive core: explicit, fine-grained signals

Why not Airstream: JS-only by design. Why not a compiler plugin: brittle, IDE
hostile. Signals in the SolidJS / Preact-Signals / Vue-3 style are ~1 000 lines,
cross-compile trivially, and are glitch-free with synchronous propagation.

```scala
trait Signal[+A]:
  def now: A

  def map[B](f: A => B): Signal[B]

  def zip[B](that: Signal[B]): Signal[(A, B)]

final class Var[A] private(init: A) extends Signal[A]:
  def set(a: A): Unit // must be on UI thread (A-06); see 7.7

  def update(f: A => A): Unit

object Signal:
  def computed[A](f: Tracking ?=> A): Signal[A] // auto-tracked dependencies

  def effect(f: Tracking ?=> Unit): Disposable // runs when deps change

val count = Var(0)
val label = Signal.computed(s"Clicked ${count()} times") // count() reads & tracks
```

Ownership: effects and computed signals are owned by the component scope that
created them and disposed when the element is unmounted — Laminar's ownership idea,
without its JS assumptions. Batching: `Signal.batch { ... }` coalesces multiple
`set`s into one propagation. Async never enters the signal graph directly: signals are
synchronous and UI-thread-only by design; effects reach them only through the bridge
in §7.13, which is where ZIO lives.

## 7.3 Element DSL: context functions, no plugin

```scala
import thicket.*

def Counter(): Element =
  val count = Var(0)
  Column(gap = 12.dp, padding = 16.dp) {
    Text(count.map(n => s"Count: $n"), style = Typography.title)
    Row(gap = 8.dp) {
      Button("−") {
        count.update(_ - 1)
      }
      Button("+") {
        count.update(_ + 1)
      }
    }
    if count.now > 10 then Text("That's a lot") // static branch: use Signal.when for reactive
    Show(count.map(_ > 10)) {
      Text("That's a lot")
    } // reactive branch
  }
```

Mechanics:

- `Column(...) { body }` has type `(children: Children ?=> Unit) => Element`; each
  child constructor appends to the implicit `Children` builder. This is what gives
  the SwiftUI/Compose look with zero macros.
- Props that accept `A | Signal[A]` (union types) so the same call works with static
  and reactive values; `given Conversion[A, Signal[A]]` keeps call sites terse.
- Units are opaque types: `opaque type Dp = Float; extension (x: Int) def dp: Dp`.
- Modifiers as extension methods returning a new Element (`Text("x").padding(8.dp).onTap(...)`),
  or as named parameters — decide by ergonomics testing in the dev canvas (M1).
- Lists: `ForEach(items: Signal[Seq[A]], key: A => K) { a => ... }` produces keyed
  children; the reconciler maps to `UITableView`/`RecyclerView`/`GtkListView`
  virtualised containers.
- Theming/environment through `given`s: `given Theme = Theme.system` resolved at
  construction, overridable per subtree with `Provide(theme) { ... }`.

## 7.4 Reconciliation model

Retained native widgets; the Scala side keeps a *shadow tree* of `Node`s (element
type, props, Yoga node, children, native handle). On each signal propagation only
the affected props update — there is no whole-tree diff on every change because
reactivity is fine-grained (this is the SolidJS insight; React/RN diff everything).
Structural changes (`Show`, `ForEach`, `Switch`) reconcile their own subtree only.

Renderer contract (simplified, versioned per S-01):

```scala
trait Renderer:
  type Handle

  def create(kind: WidgetKind, props: Props): Handle

  def update(h: Handle, patch: PropsPatch): Unit

  def insertChild(parent: Handle, child: Handle, index: Int): Unit

  def removeChild(parent: Handle, child: Handle): Unit

  def measure(h: Handle, constraints: Constraints): Size // intrinsic size of leaves

  def setFrame(h: Handle, frame: Rect): Unit // from Yoga

  def destroy(h: Handle): Unit

  def runOnUiThread(f: () => Unit): Unit

  def platform: Platform
```

Whether a container is a "Yoga box" (plain view positioned by us) or a *native
container* (nav controller, tab bar, list, scroll view) is per `WidgetKind`; the
latter own their children's layout and report a single box to Yoga.

## 7.5 Layout: Yoga everywhere (with native containers opting out)

Yoga (Meta) is C++ with a stable public C API, implements CSS Flexbox, is what React
Native uses, and has Java/ObjC/C# bindings.

- Native: bind the C API with sn-bindgen; vendor the generated Scala.
- Android (JVM): `com.facebook.yoga` Java artefact from the RN ecosystem, or JNI to
  the same C build — decide in Spike S4 (the RN artefact drags SoLoader).
- JS dev canvas: no Yoga; the DOM's flexbox is close enough for previews, and
  divergences become test cases.
- Leaves report intrinsic size via a Yoga measure callback that calls
  `Renderer.measure` (e.g. `sizeThatFits` on UIKit, `measure()` on Android).

## 7.6 The Apple shim (Swift, C ABI)

```swift
// ThicketShim/Sources/ThicketShim/Button.swift
@_cdecl("sui_button_new")   public func buttonNew() -> UnsafeMutableRawPointer
@_cdecl("sui_button_set_title") public func buttonSetTitle(_ h: UnsafeMutableRawPointer, _ utf8: UnsafePointer<CChar>)
@_cdecl("sui_button_on_tap") public func buttonOnTap(_ h: UnsafeMutableRawPointer,
                                                     _ cb: @convention(c) (UnsafeMutableRawPointer?) -> Void,
                                                     _ ctx: UnsafeMutableRawPointer?)
@_cdecl("sui_run_on_main")   public func runOnMain(_ cb: @convention(c) (UnsafeMutableRawPointer?) -> Void, _ ctx: UnsafeMutableRawPointer?)
```

A single C header `thicket_shim.h` declares these; sn-bindgen turns it into the
Scala `extern` object. Handles are `Unmanaged<UIView>` pointers retained by the shim
and released on `sui_destroy`. Callbacks pass a `ctx` pointer that Scala Native maps
back to the closure (via a handle table, since GC'd objects cannot be pinned across
C — verify pinning story in S3). AppKit variants live behind `#if canImport(UIKit)`.

App start-up: the Xcode project's `@main` Swift entry calls `thicket_main()` — an
`@exported` Scala Native function — after UIKit finishes launching, hands it the
root view controller handle, and Scala mounts the tree.

## 7.7 Threading model

- Exactly one UI thread per platform, owned by the platform (UIKit main, Android
  main Looper, GMainLoop, Win32 message loop). The framework never creates it.
- `Var.set` off the UI thread: **fails fast** with a descriptive exception in dev
  builds; adapters (`zio`, `cats-effect`) always hop via `Renderer.runOnUiThread`.
  Decided over "auto-marshal everything" because implicit thread hops hide ordering
  bugs; revisit after M1 feedback.
- Background work: Scala Native 0.5 threads / Android threads / ZIO fibers; results
  come back as signal updates on the UI thread.
- GC: foreign main thread must be registered with the Scala Native GC (attach
  thread) — S1 checks whether calling into Scala from UIKit's thread "just works".

## 7.8 Dev loop (D-01)

Three tiers, in order of speed:

1. **Dev canvas** (Scala.js + Vite): `fastLinkJS` incremental → browser reload in
   under a second; CSS themes mimic each platform; also the docs/gallery renderer.
2. **Desktop host**: run the same app natively on the developer's desktop (macOS
   renderer on a Mac, GTK on Linux) — Scala Native debug link ~10–30 s. On the JVM
   the Android renderer cannot run, but a JVM+JavaFX *preview* renderer could be a
   later addition if link times stay painful.
3. **Device/emulator**: full build; target ≤ 30 s incremental Android (Zinc + Apply
   Changes), minutes for iOS (Scala Native link + Xcode). Acceptable as the
   "check on device" step, not the inner loop.

Longer-term idea: an *interpreted element tree* — ship the reconciler + renderers
as a stable binary and hot-swap only app code — is how Flutter's hot reload works (Dart VM) and is not available to us
with AOT; the tiered approach is the realistic
substitute.

## 7.9 Build tooling

- `sbt-thicket` / `mill-thicket`: tasks `iosXcodeProject`, `androidGradleProject`,
  `desktopPackage`. Xcode/Gradle shells are *generated once, then owned by the app*
  (P-04); regeneration writes only framework-owned files.
- iOS: the plugin builds the Scala Native `.a` for each (arch, sdk) and assembles an
  XCFramework so Xcode picks the right slice for device vs simulator.
- Android: plugin compiles Scala via Zinc into a JAR, wraps as an AAR with R8 keep
  rules for Scala 3 (vendored `scala3.pro`), Gradle app consumes it. Alternative
  investigated in S2: a Gradle plugin that runs Zinc so Android Studio's normal
  build works end-to-end.
- `thicket` CLI: thin wrapper over the plugins + `xcodebuild`/`gradlew` + `doctor`.

## 7.10 Component catalogue (v1 scope)

Layout: Column, Row, Stack/ZStack, Spacer, ScrollView, SafeArea, Grid (Yoga wrap).
Controls: Text, TextField, SecureField, Button, IconButton, Toggle/Switch,
Checkbox, Radio, Slider, Stepper, SegmentedControl, Picker, DatePicker,
ProgressBar, ActivityIndicator, Image (async, cached), Link.
Containers: List (virtualised, sections, swipe actions), NavigationStack,
TabView, Sheet/Modal, Alert, Menu/ContextMenu, Toolbar, Divider.
Platform-only via `Platform.ios {}` etc.: e.g. `UIMenu` inline styles, Android FAB.

## 7.11 Error handling & diagnostics

- Native errors (`NSError`, Android exceptions, GTK criticals) are captured in the
  shim and re-raised as `PlatformError(platform, code, message, nativeStack)`.
- Dev mode logs every reconciliation with element path; inspector (D-05) later.
- Crash reports: symbolication guide for Scala Native mangled names; ship a
  `thicket symbolicate` helper.

## 7.12 Where Scala 3 specifically helps

- `enum` for exhaustive UI state machines; the compiler flags unhandled states.
- Opaque types for units and identifiers (no Dp/Px mix-ups).
- Union types for `A | Signal[A]` props.
- Context functions for builder scopes and for `Tracking ?=>` dependency tracking.
- `inline` and `transparent inline` to make the DSL zero-cost in hot paths.
- Match types / `Tuple` ops for typed resource accessors (A-09) without macros
  where possible; macros only in the code generator for resources.
- Cross-building via `%%%`: the same domain model, Caliban client and validation
  logic run on the server and in the app (A-10) — the concrete payoff for
  ZIO/Caliban shops.

## 7.13 Effect bridge: "F at the edges", ZIO first

### Why the core has no `F[_]`

Two designs were considered:

| | Tagless core (`Element[F]`, `Cmd[F]`, Tyrian-style) | Synchronous core + per-effect bridge (chosen) |
|---|---|---|
| Widget API | Every constructor/handler carries `F`; `Button[F]("x")(fa: F[Unit])` | No effect type in sight; `Button("x") { … }` |
| Signals | Would have to be `F`-aware or duplicated | One implementation, glitch-free, sync, cross-platform |
| Streams | Cannot be abstracted with one typeclass (ZStream ≠ fs2.Stream ≠ Akka) — Tyrian and Laminar both end up special-casing | Each bridge adapts its own stream type with extension methods |
| Cost for a ZIO user | Reads `F` everywhere, writes `IO`/`RIO` aliases to hide it | Sees `ZIO` only where they hand one over |
| Precedent | Tyrian (`Cmd[F]`, works, noisy) | SolidJS/Vue (sync signals) + Compose (`LaunchedEffect`/`collectAsState` at the edge) |

Conclusion: the *renderer* contract and the *effect* contract are the two seams of
the framework. Both are small, versioned interfaces; both have one reference
implementation built with the core (Apple renderer, ZIO bridge).

### The bridge contract (`effect-api`)

What any bridge must provide, in effect-system-neutral terms:

```scala
trait EffectBridge[F[_]]:
  /** Runs fa on the component's scope; cancelled/interrupted when the component unmounts. */
  def launch(fa: F[Unit])(using ComponentScope): Unit

  /** Runs fa's continuation on the UI thread (for signal writes). */
  def onUi[A](fa: F[A]): F[A]

  /** Lifts a UI-side signal write into F, already on the UI thread. */
  def set[A](v: Var[A], a: A): F[Unit]

  /** Resource tied to component lifetime (acquire on mount, release on unmount). */
  def resource[A](acquire: F[A])(release: A => F[Unit])(using ComponentScope): Signal[Option[A]]

  /** Error channel: what to do with a failure the component did not handle. */
  def onUnhandled(e: Throwable | Any): Unit // bridge-specific typed variants in each module
```

Streams are intentionally *not* in the contract; each bridge adds them for its own
stream type. This keeps the contract implementable by any effect system in ~200
lines and lets each bridge feel idiomatic.

### The ZIO bridge (`effect-zio`)

Concepts and how they map:

| ZIO concept | UI mapping |
|---|---|
| `Runtime[R]` | One per app, created by `App.run(layer)`; held in a `given UiRuntime[R]`. Components declare `type AppTask[A] = RIO[AppEnv, A]` or use the given. Swappable for tests (headless renderer + `TestClock`). |
| `ZLayer` | The app's services (HTTP/GraphQL clients, DB, auth) are a layer supplied once at the root; components access them through `R`. This is the server-client code-sharing story: the same `ZLayer` shape as the Caliban server. |
| `Scope` | Every mounted component owns a ZIO `Scope`; `launch(effect)` forks into it; unmount closes the scope → all fibers interrupted, all finalizers run. Compose's coroutine scope, but with ZIO's structured concurrency and typed errors. |
| `Executor` | `UiExecutor` posts to the platform main thread (`sui_run_on_main`, `Handler(Looper.getMainLooper)`, `g_idle_add`). `zio.ui.onUi` = `.onExecutor(UiExecutor)`. `Var.set` from a fiber not on `UiExecutor` fails fast (A-06), so the compiler cannot save you but the runtime tells you immediately. |
| `ZIO[R,E,A]` → state | `effect.toSignal: Signal[AsyncValue[E, A]]` with `AsyncValue = Loading | Failed(e) | Done(a)` — an exhaustive `enum` the UI pattern-matches on. |
| `ZStream[R,E,A]` → state | `stream.toSignal(initial)`; each element is a `Var.set` on the UI thread; stream fiber lives in the component scope. |
| `SubscriptionRef[A]` | The natural shared-state primitive: `ref.changes.toSignal(ref.get)`; writes from any fiber, UI reads via the signal. Recommended over `Ref` for app-level state. |
| `Hub`/`Queue` | UI events (taps, text changes) can be published to a `Hub` when a fiber wants to consume them as a stream: `button.taps: ZStream[Any, Nothing, Unit]`. |
| Typed errors `E` | `launch` requires either `E = Nothing` or a `given ErrorPresenter[E]` in scope (shows an alert / toast / logs); an unhandled typed error is a compile error, not a runtime surprise. |
| `Schedule` | Retry/polling of UI data sources; `ZStream.repeatZIOWithSchedule(fetch, Schedule.spaced(30.seconds)).toSignal(...)` is the idiomatic "auto-refresh". |
| `Clock`/`TestClock`, `TestRandom` | Component tests run the headless renderer under a `TestEnvironment`; time-driven UI (debounce, polling) is deterministic. |

Illustrative use:

```scala
def RecipeList()(using UiRuntime[AppEnv]): Element =
  val query = Var("")
  val recipes = query.debounce(300.millis) // debounce is bridge-provided, runs on TestClock in tests
    .toZStream
    .mapZIO(q => RecipeService.search(q)) // ZIO[AppEnv, RecipeError, List[Recipe]]
    .toSignal(initial = Nil) // fiber owned by this component

  Column {
    TextField(query, placeholder = "Search…")
    ForEach(recipes, key = _.id) { r => Text(r.name) }
    Button("Add") {
      launch(RecipeService.add(Recipe.draft).onUi *> ZIO.succeed(query.set("")))
    }
  }

given ErrorPresenter[RecipeError] = ErrorPresenter.alert(_.userMessage)
```

`launch`, `toSignal`, `toZStream`, `debounce`, `onUi` are extension methods from
`effect-zio`; nothing in `core` knows they exist.

### Platform notes for ZIO

- ZIO 2 is cross-published for JVM, JS and Native; the default Native executor is
  multi-threaded since Scala Native 0.5. **Behaviour on iOS is unverified → Spike S8**
  (runtime start-up cost, thread creation from a static library, `Clock` on
  `javalib`, fiber scheduler interaction with the UIKit main thread).
- On Android, ZIO's runtime is ordinary JVM code; `Runtime` creation at app start
  must be measured against N-01 (cold-start budget). Bootstrapping a `ZLayer` lazily
  after first frame is the mitigation.
- On the dev canvas (Scala.js) ZIO is single-threaded; `UiExecutor` is a no-op —
  which is also why the dev canvas can't catch threading mistakes; the desktop host
  can.
- Ecosystem reach on Native is uneven (zio-http client, zio-json are fine; some
  libraries are JVM-only). The sample app should exercise a Caliban/GraphQL client
  from Scala Native early (M1).

### Cats-effect bridge (later, same shape)

`Dispatcher[F]` for `launch`, `Resource` for component-scoped resources, fs2 for
streams, `Async[F].evalOn(uiExecutionContext)` for `onUi`. Writing it validates that
the contract is genuinely agnostic; until then the contract is provisional (S-01
does not apply to `effect-api` before a second implementation exists).
