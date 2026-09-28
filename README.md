# scala-ui — a thick-client UI framework for Scala

**Status:** phase 0 complete (**GO**); building towards an MVP. The same demo app —
one `Element` tree, no platform code — runs today on **Linux/GTK4, Android, macOS and the
iOS simulator**.

## Why

Scala covers the server (JVM), the browser (Scala.js — Laminar, scalajs-react, Tyrian) and
the command line (Scala Native). It has **no answer for the thick client**: no way to ship a
Scala app to a phone or a desktop that looks and feels like it belongs there. Kotlin has
Compose Multiplatform, Dart has Flutter, JS has React Native, C# has MAUI/Avalonia/Uno, Rust
has Tauri/Slint. Scala has nothing, and that gap keeps Scala out of an entire class of
products.

## What it is

A declarative, reactive UI core in pure Scala 3 (cross-compiled to JVM, JS and Native) that
describes the UI as a tree and hands each leaf to a **real native widget** through a thin
per-platform renderer: `android.view.*` on Android, AppKit/UIKit on macOS/iOS via a Swift
shim with a C ABI, GTK4 on Linux, WinUI/Win32 later. "Looks like the platform" is true *by
construction*, accessibility comes with the native controls, and the developer writes one
Scala codebase.

State is reactive (fine-grained signals, no `F[_]` in the core). Effects arrive through a
per-effect-system bridge — **ZIO first**, cats-effect next — so a ZIO service layer drives
the UI without the widget API knowing about it.

```scala
def itemsScreen(model: Model): Element =
  Scroll()(Column(spacing = 16, padding = 16)(
    Row(spacing = 8)(
      TextField(model.draft, placeholder = "What needs doing?")(model.draft.set),
      Checkbox(model.draftDone, "Done")(model.draftDone.set)
    ),
    Button("Add item")(model.addDraft()),
    ForEach(model.items, key = (i: Item) => i.id) { item =>
      Row(spacing = 12, padding = 12)(
        Label(item.map(_.title)).grow,
        Label(item.map(i => if i.done then "\u2713" else ""), style = TextRole.Caption)
      ).onTap(nav.push(Route.Detail(item.now.id)))
    }
  ))
```

That is the whole of it: no renderer, no platform, no effect type. The GTK, Android and
Apple hosts each mount the same function.

## Where it actually is

Phase 0 ran eight spikes and returned **GO** — see [docs/10](docs/10-phase-0-findings.md);
a ninth followed, on the ZIO bridge under iOS.
Not one of the toolchain risks the plan feared actually materialised; the risk inverted, and
the renderer contract became the thing to prove. It has now survived **four structurally
different toolkits with no changes**.

Measured, not asserted:

| | |
|---|---|
| App size (iOS, hello app) | **0.53 MB** vs React Native 26.0 MB, Gluon 60.1 MB |
| Cold start (iOS simulator) | **425 ms** vs React Native 702 ms |
| Android cold start | 388 ms, against hand-written Kotlin's 418 ms |
| Android release APK | 154 KB |
| Signal update | 77 / 219 / 419 ns per node (JVM / Native / JS) against a 1 000 ns budget |
| Mount 10 000 rows | ~56 ms; a single row update ~1.5 ms (JVM, `ScaleSuite`) |
| 10 000-row list | ~66 views on Android, ~205 on GTK — virtualised, not mounted |

What is **not** done is breadth: **9 of the 32 v1 widgets**, no Windows renderer, no Yoga
layout, no dev canvas, no CLI. The live list of what is done and what is left is
[docs/12-component-status.md](docs/12-component-status.md) — start there.

## Layout

```
modules/
  signals/          fine-grained reactivity (JVM / JS / Native)
  renderer-api/     the renderer contract — one small, versioned interface
  core/             element tree, reconciler, navigation, theming
  effect-zio/       the ZIO bridge
  renderer-gtk/     GTK4        (Scala Native)
  renderer-android/ android.view (Scala on ART)
  renderer-apple/   AppKit + UIKit (Scala Native + Swift shim)
examples/
  shared/           TodoApp — the platform-free demo every host mounts
  counter-gtk/  todo-android/  todo-apple/
spikes/             phase 0, one directory and REPORT.md per spike
docs/               the plan, the findings, and the live status
```

## Building

Scala **3.9.0**, sbt **2.0.9**, Scala Native **0.5.12**. Always the latest stable of each —
see [docs/decisions.md](docs/decisions.md).

```bash
sbt --error "signalsJVM/testOnly *; coreJVM/testOnly *; effectZioJVM/testOnly *"   # 98 tests

sbt --error counterGtk/nativeLink                                                   # Linux/GTK4
SCALAUI_SELFTEST=1 ./target/out/native0.5/scala-3.9.0/counter-gtk/counter-gtk

./examples/todo-android/build.sh                                                    # Android
```

`sbt test` is incremental on sbt 2 and will happily run **zero** tests and report success —
always `testOnly *`. The Apple example is built on macOS and is deliberately not in the root
aggregate, so Linux and Android builds are unaffected by it.

## Documents

Read [docs/12](docs/12-component-status.md) for current state and
[docs/11](docs/11-what-it-looks-like.md) for where it is going. The rest is the plan, frozen
except where noted.

| # | File | Contents |
|---|---|---|
| 1 | [docs/01-vision-and-goals.md](docs/01-vision-and-goals.md) | Problem, vision, goals, non-goals, target platforms |
| 2 | [docs/02-what-makes-ui-frameworks-popular.md](docs/02-what-makes-ui-frameworks-popular.md) | Usability, platform fidelity, stability, performance — what the winners did |
| 3 | [docs/03-landscape-and-alternatives.md](docs/03-landscape-and-alternatives.md) | Survey of existing frameworks and of what Scala can reach |
| 4 | [docs/04-scala-toolchain-reality.md](docs/04-scala-toolchain-reality.md) | JVM / Scala.js / Scala Native per platform |
| 5 | [docs/05-requirements.md](docs/05-requirements.md) | Functional & non-functional requirements (MoSCoW, numbered) |
| 6 | [docs/06-architecture-options.md](docs/06-architecture-options.md) | Options A–F, trade-offs, scoring, recommendation |
| 7 | [docs/07-technical-design-ideas.md](docs/07-technical-design-ideas.md) | Core API, reactivity, layout, renderer contract, threading, §7.10 v1 catalogue |
| 8 | [docs/08-roadmap-and-spikes.md](docs/08-roadmap-and-spikes.md) | Spikes, milestones, exit criteria — **and why its estimates are inflated** |
| 9 | [docs/09-open-questions.md](docs/09-open-questions.md) | What we do not know yet and how to find out |
| 10 | [docs/10-phase-0-findings.md](docs/10-phase-0-findings.md) | Every phase-0 measurement and the GO decision |
| 11 | [docs/11-what-it-looks-like.md](docs/11-what-it-looks-like.md) | The vision, the design ideas, and the gaps each one exposed — **living** |
| 12 | [docs/12-component-status.md](docs/12-component-status.md) | Done vs left, per widget and per renderer — **living** |
| — | [docs/decisions.md](docs/decisions.md) | Versions, tooling, and the dated decision log — **living** |
| — | [docs/screenshots/](docs/screenshots/) | What it looks like, read critically |

Facts marked **[unverified]** in docs 01–09 are claims a spike had not yet settled; docs 10
and 12 say which ones since have been.
