# 1. Vision and goals

## 1.1 The problem

Scala has excellent coverage of three deployment surfaces:

| Surface     | Backend           | Mature UI story                                  |
|-------------|-------------------|--------------------------------------------------|
| Server      | JVM               | n/a                                              |
| Browser     | Scala.js          | Laminar, scalajs-react, Slinky, Tyrian, Outwatch |
| CLI / tools | Scala Native, JVM | n/a                                              |

It has **no** story for the fourth surface, the *thick client*: an installed
application on a phone, tablet or desktop that uses the platform's own windowing,
input, accessibility and look. The historical attempts (Scala on Android via
sbt-android, RoboVM-era iOS demos, Scala Swing, ScalaFX) are either dead, desktop-only,
or do not look native. A Scala team that needs a mobile app today rewrites the client
in Kotlin/Swift/Dart/TypeScript and keeps Scala only on the server.

## 1.2 Vision

> One Scala 3 codebase, compiled per platform, producing applications that users
> cannot distinguish from ones written in the platform's first-party toolkit —
> with a developer experience good enough that people *choose* it, not merely
> tolerate it.

## 1.3 Goals

G1. **Platform fidelity.** Controls, navigation, typography, scrolling physics,
dark mode, dynamic type, accessibility and system gestures come from the
platform, not from a re-implementation.

G2. **One language, one mental model.** UI, state and business logic are Scala 3.
Platform code is confined to renderers the app author never touches.

G3. **Developer experience competitive with Flutter/RN.** Declarative API, a
sub-second edit→see loop during development, ordinary sbt/mill/scala-cli
builds, IDE support via Metals/IntelliJ, and one command to produce a
signed artefact per platform.

G4. **Reactive, and at home with effect systems.** The UI core is reactive (signals), and effects are first-class: the
core stays effect-system agnostic ("F at the edges", not `F[_]` threaded through every widget), with **ZIO as the
reference bridge shipped in the first milestone** and a cats-effect bridge
following the same contract. JSON, HTTP, GraphQL (Caliban) clients and domain
code shared with a ZIO server compile unchanged.

G5. **Stability as a feature.** Semantic versioning, a documented renderer contract
so third parties can add platforms, and no dependency on a JS engine, a browser,
or a commercial licence at runtime.

## 1.4 Non-goals (for the first two years)

- Web as a *production* target. Scala.js already has good web UI libraries; the
  web backend in this plan exists for the dev loop and for previews, not to compete
  with Laminar.
- Games / custom-drawn canvases as the primary use case (Indigo covers games).
- Pixel-identical UI across platforms. We deliberately want a button on iOS to
  look like an iOS button and on Android like an Android button.
- A visual designer / WYSIWYG tool.
- Supporting Scala 2.

## 1.5 Target platforms and priority

| Priority | Platform                      | Scala backend         | Rationale                                                                      |
|----------|-------------------------------|-----------------------|--------------------------------------------------------------------------------|
| P0       | iOS (17+)                     | Scala Native          | Hardest, highest-value, most-uncertain toolchain; if it fails the plan changes |
| P0       | Android (API 26+)             | Scala on JVM (dex/R8) | Largest install base; JVM path is the natural fit                              |
| P1       | macOS (13+)                   | Scala Native          | Shares ObjC runtime & most of the iOS shim → cheap second target               |
| P1       | Linux (GTK4)                  | Scala Native          | Existing community bindings; developers' own machines                          |
| P2       | Windows (10/11)               | Scala Native          | Largest desktop base but most work (WinUI 3 / Win32 shim)                      |
| Dev only | Browser (HTML/CSS dev canvas) | Scala.js              | Fast reload during development; previews and docs                              |

## 1.6 Who is this for

1. Scala teams that already run Scala servers and want a client without a second
   language.
2. Scala developers building tools for themselves (desktop utilities, internal apps).
3. Longer term: developers who value Scala 3's type system for UI code the way
   Elm/Purescript users do, and are currently forced into TypeScript.

## 1.7 Success criteria for the plan itself

The plan succeeds if, after the spikes in `08-roadmap-and-spikes.md`, we can answer
with evidence — not opinion — whether a Scala thick-client framework is feasible at
the quality bar in G1–G5, and which of the architecture options in
`06-architecture-options.md` to commit to.
