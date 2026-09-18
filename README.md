# scala-ui — a thick-client UI framework for Scala

**Status:** research / planning. Nothing is built yet. This repository currently
holds the requirements, landscape analysis, architecture options and roadmap for
a Scala-native UI framework that targets **Android, iOS, macOS, Windows and Linux**.

## Why

Scala today covers the server (JVM), the browser (Scala.js — Laminar, scalajs-react,
Tyrian) and the command line (Scala Native). It has **no answer for the thick
client**: there is no way to ship a Scala app to a phone or a desktop that looks
and feels like it belongs there. Kotlin has Compose Multiplatform, Dart has Flutter,
JS has React Native, C# has MAUI/Avalonia/Uno, Rust has Tauri/Slint. Scala has
nothing, and that gap keeps Scala out of an entire class of products.

## What this plan proposes (one paragraph)

Build a **declarative, reactive UI core in pure Scala 3** (cross-compiled to JVM,
JS and Native) that describes the UI as a tree, lays it out with Flexbox (Yoga),
and hands each leaf to a **real native widget** via a thin per-platform renderer:
`android.view.*` on Android (Scala on the JVM), UIKit/AppKit on iOS/macOS (Scala
Native calling a small Swift shim with a C ABI), GTK4 on Linux (existing Scala
Native bindings), WinUI/Win32 on Windows (C shim). "Looks like the platform" is
therefore true *by construction*, accessibility comes with the native controls, and
the developer writes one Scala codebase. State is reactive (fine-grained signals);
effects are handled by a per-effect-system bridge, **ZIO first**, cats-effect next,
so a ZIO service layer drives the UI without the widget API knowing about `F[_]`.
A Scala.js/HTML "dev canvas" backend gives the sub-second reload loop that makes
Flutter and React Native popular.

The plan also weighs cheaper alternatives (Scala.js + React Native, Scala.js in a
Tauri/Capacitor webview, ScalaFX + Gluon/GraalVM, sharing logic only) and states
the go/no-go spikes that must pass before the ambitious path is committed to.

## Documents

| # | File                                                                                       | Contents                                                                           |
|---|--------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| 1 | [docs/01-vision-and-goals.md](docs/01-vision-and-goals.md)                                 | Problem, vision, goals, non-goals, target platforms & priority                     |
| 2 | [docs/02-what-makes-ui-frameworks-popular.md](docs/02-what-makes-ui-frameworks-popular.md) | Usability, platform fidelity, stability, performance — what the winners did        |
| 3 | [docs/03-landscape-and-alternatives.md](docs/03-landscape-and-alternatives.md)             | Survey of existing frameworks and of what Scala can reach today                    |
| 4 | [docs/04-scala-toolchain-reality.md](docs/04-scala-toolchain-reality.md)                   | JVM / Scala.js / Scala Native per platform: what works, what is unproven           |
| 5 | [docs/05-requirements.md](docs/05-requirements.md)                                         | Functional & non-functional requirements (MoSCoW, numbered)                        |
| 6 | [docs/06-architecture-options.md](docs/06-architecture-options.md)                         | Options A–F, trade-offs, scoring, recommendation                                   |
| 7 | [docs/07-technical-design-ideas.md](docs/07-technical-design-ideas.md)                     | Core API sketch, reactivity, layout, renderer contract, threading, build, dev loop |
| 8 | [docs/08-roadmap-and-spikes.md](docs/08-roadmap-and-spikes.md)                             | Go/no-go spikes, milestones, exit criteria                                         |
| 9 | [docs/09-open-questions.md](docs/09-open-questions.md)                                     | Things we do not know yet and how to find out                                      |

Sources consulted are listed at the end of each document. Facts marked **[unverified]** are claims we could not confirm
and that a spike must settle.
