# 12. Component status

**The live answer to "what is done and what is left."** Updated whenever a widget, prop or
renderer lands — unlike `docs/01`–`docs/09`, which are frozen. The target is the v1
catalogue in `docs/07` §7.10.

**Last updated:** 2026-09-28 (phase 1 in progress).

## 12.1 Scoreboard

| | Count |
|---|---|
| Widgets in the v1 catalogue (`docs/07` §7.10) | 32 |
| Widgets implemented on at least one renderer | **9** |
| Widgets implemented on **every** renderer that exists | **9** |
| Renderers | **4** (GTK4, Android, AppKit, UIKit) |
| Props in the contract | **18** |
| Props implemented on every renderer | **17** — `Axis` is missing on Apple |

Nine of thirty-two is the honest headline. The nine are, deliberately, the ones that forced
the contract to be right: a tappable container, a two-way-bound field, a recycling list and
a viewport between them exercise nearly every hard part of a renderer. What is left is
mostly *breadth* — and breadth is the work, exactly as `docs/11` §11.6 predicted.

## 12.2 Widgets

Legend: **done** · **partial** (works, with a stated gap) · *blank* = not started.

| Widget | Contract | GTK4 | Android | AppKit | UIKit | Notes |
|---|---|---|---|---|---|---|
| `Column` | `WidgetKind.Column` | done | done | done | done | `GtkBox` / `LinearLayout` / `NSStackView` / `UIStackView` |
| `Row` | `WidgetKind.Row` | done | done | done | done | Clips on overflow; the answer is a horizontal `Scroll`, not a wrapping row |
| `Label` | `WidgetKind.Label` | done | done | done | done | `Text` in §7.10's naming |
| `Button` | `WidgetKind.Button` | done | done | done | done | |
| `TextField` | `WidgetKind.TextField` | done | done | done | done | Two-way bound; renderers must not disturb a widget showing the value already written |
| `Checkbox` | `WidgetKind.Checkbox` | done | done | done | done | |
| `Scroll` | `WidgetKind.Scroll` | done | done | partial | partial | Vertical everywhere; horizontal (`Prop.Axis`) on GTK and Android only — Forgejo **#4** |
| `Divider` | `WidgetKind.Divider` | done | done | done | done | Platform's own weight and colour, never a drawn line |
| `Image` | `WidgetKind.Image` | done | done | partial | partial | Decoding is on the UI thread everywhere; `ContentFit.Cover` distorts on AppKit — Forgejo **#6** |

### Not started

| Group | Left to do |
|---|---|
| Layout | `Stack`/`ZStack`, `Spacer`, `SafeArea`, `Grid` |
| Controls | `SecureField`, `IconButton`, `Toggle`/`Switch`, `Radio`, `Slider`, `Stepper`, `SegmentedControl`, `Picker`, `DatePicker`, `ProgressBar`, `ActivityIndicator`, `Link` |
| Containers | `TabView`, `Sheet`/`Modal`, `Alert`, `Menu`/`ContextMenu`, `Toolbar` |

`Spacer` and `Toggle` are the two most conspicuous absences in the demo app: the first is
faked with `Prop.Grow`, the second with a `Checkbox`.

## 12.3 Structure and behaviour (not widgets, but shipped)

| Feature | Where | Status |
|---|---|---|
| Fine-grained signals | `modules/signals` | done — glitch-free, property-tested on JVM/JS/Native |
| Keyed reconciliation | `Reconciler` | done — `Show`, `Switch`, `ForEach`, `Fragment`, in-place `moveAfter` |
| Virtualised list | `LazyColumn` + `RowSource` | **partial** — GTK and Android recycle; **Apple does not** and silently mounts every row — Forgejo **#5** |
| Navigation | `Nav`, `NavHost`, `AppRoot` | partial — stack, title and Up chrome work; no *native* navigation container |
| Theming | `Theme`, `ColorRole` | partial — role → platform token, one accent role; no per-subtree `Provide` |
| ZIO bridge | `modules/effect-zio` | done — `asSignal`, `launch`, `RemoteData`, `ErrorPresenter`; runs on iOS |
| UI-thread seam | `UiThread` | done |
| Apple ABI description + consistency check | `tools/shim-gen` | done — 34 functions described; the four hand-written declarations per function are checked to agree, on any machine |

## 12.4 Props

All four renderers handle all 18 props, because `Prop` is an `enum` and the match is
exhaustive under `-Werror` — a new prop breaks every renderer until it is handled, which is
the point of doing this in Scala.

`Text`, `OnTap`, `Spacing`, `Padding`, `Enabled`, `Placeholder`, `OnTextChange`, `Checked`,
`OnCheckedChange`, `Style`, `Grow`, `Align`, `Tint`, `Fill`, `Picture`, `Fit`,
`TextEmphasis`, `Axis`.

"Handled" is not "honoured": Apple's `Axis` case is an explicit documented no-op, which is
why 12.1 counts it as 17.

## 12.5 Renderers

| Renderer | Module | Layout mode | Virtual rows | Runs |
|---|---|---|---|---|
| GTK4 | `renderer-gtk` | ToolkitManaged | yes (`GtkListView`) | Linux, Scala Native |
| Android | `renderer-android` | ToolkitManaged | yes (`ListView`) | Android 26+, Scala on ART |
| AppKit | `renderer-apple` | ToolkitManaged | **no** | macOS, Scala Native + Swift shim |
| UIKit | `renderer-apple` | ToolkitManaged | **no** | iOS simulator, Scala Native + Swift shim |
| Win32/WinUI | — | — | — | not started |
| DOM (dev canvas) | — | — | — | not started |

No renderer uses `LayoutMode.FrameBased` yet, which means **Yoga is not wired in**: every
container lays out its own children the platform way. S4 proved Yoga is reachable; nothing
depends on it until `Grid` or absolute positioning does.

## 12.6 What this list is for

**Division of labour.** Apple work is done on the macOS laptop, so anything AppKit/UIKit is
raised as a Forgejo issue rather than attempted here — currently **#4** (horizontal
`Scroll`), **#5** (virtualised rows), **#6** (`ContentFit.Cover`) and **#7** (shim
generation). Everything else is built and measured on the Linux box.

Two rules, so it stays true:

1. A widget is **done** only when it is implemented on every renderer that exists and
   exercised by a self-test or a unit test. "Compiles" is not done.
2. A gap is recorded here the day it is found, with the workaround if there is one. A gap
   that only lives in a commit message is a gap nobody can plan around.

## 12.7 Phase 0 is closed; the MVP, proposed

**Phase 0 is done.** `docs/10` declared **GO** on 2026-09-19 against the gate in `docs/08`
§8.1 (S1 ∧ S2 ∧ S3). Everything since — four renderers, keyed reconciliation, navigation,
theming, virtualisation, the ZIO bridge — is already MVP work that was never named as such.
This section names it, so there is a finish line to steer at.

**Proposed MVP (0.1): a developer outside this repository can build and ship a real app for
Android, iOS and one desktop, without reading the framework's source.**

That phrasing, rather than a feature count, because it is falsifiable: hand the repo to
someone and watch. It implies five things, roughly in dependency order.

| # | Work | Why it is on the critical path |
|---|---|---|
| 1 | **Shim generation** (Forgejo **#7**, phase 1 — *code done, not yet adopted*) — Swift, C header and Scala externs from one widget description | The Mac measured **11.0 non-comment Swift lines per exported function**, projecting ~240 functions for the v1 catalogue and roughly **5 200 lines of Swift maintained in duplicate** across the two shims (`docs/09`). Hand-writing the remaining 23 widgets four times over is the single largest cost in the project, and generation removes most of it. A prerequisite, not an optimisation. |
| 2 | **Widget breadth** — ~20 of the 32, chosen by what a real app cannot do without | `Toggle`, `Spacer`, `Slider`, `Picker`, `ProgressBar`, `ActivityIndicator`, `Alert`, `Sheet`, `TabView`. The demo currently fakes two of these. |
| 3 | **Apple parity** — virtualised rows, horizontal `Scroll`, `ContentFit.Cover` | `LazyColumn` silently mounting 10 000 rows on iOS is the worst kind of gap: it works in the demo and dies in an app. |
| 4 | **Native navigation containers** and per-subtree theming | The two places the framework currently asks the app to accept something non-native. |
| 5 | **Published artefacts and a getting-started** | Without these, "an outside developer" is not a thing that can be tested. |

**Explicitly out of the MVP:** Windows, Yoga/`FrameBased` layout, the Scala.js dev canvas,
the CLI, the inspector, and the cats-effect bridge. Each is real work with no dependent
above it.

**The test for done** is the one `docs/08` M4 already proposed and this repo can now
actually run: a real client app — the meal-planner against its Caliban server — built by
someone who did not write the framework, running on all three targets.
