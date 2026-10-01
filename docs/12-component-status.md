# 12. Component status

**The live answer to "what is done and what is left."** Updated whenever a widget, prop or
renderer lands — unlike `docs/01`–`docs/09`, which are frozen. The target is the v1
catalogue in `docs/07` §7.10.

**Last updated:** 2026-09-30 (the phase 2 widgets on Apple, #4).

## 12.1 Scoreboard

| | Count |
|---|---|
| Widgets in the v1 catalogue (`docs/07` §7.10) | 32 |
| Widgets implemented on at least one renderer | **17** |
| Widgets implemented on **every** renderer that exists | **15** |
| Renderers | **4** (GTK4, Android, AppKit, UIKit) |
| Props in the contract | **25** |
| Props implemented on every renderer | **22** — `Message`, `Actions` and `OnDismiss` are no-ops on Apple, because they belong to `Alert`, which it cannot present yet |

**Fifteen of thirty-two**, and this time on every renderer: the six phase 2 widgets landed
on AppKit and UIKit (#4), measured by the same self-test checks GTK and Android run —
**43/43** on macOS and **42/42** on the iOS simulator, progress bar **0.750** on both.
`Alert` is the one widget still on two renderers only.

The first nine were, deliberately, the ones that forced the contract to be right: a
tappable container, a two-way-bound field, a recycling list and a viewport between them
exercise nearly every hard part of a renderer. What is left is mostly *breadth* — and
breadth is the work, exactly as `docs/11` §11.6 predicted.

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
| `Scroll` | `WidgetKind.Scroll` | done | done | done | done | Vertical and horizontal (`Prop.Axis`) on all four. The axis is read at `create`; Apple folds it into the kind code |
| `Divider` | `WidgetKind.Divider` | done | done | done | done | Platform's own weight and colour, never a drawn line |
| `Image` | `WidgetKind.Image` | done | done | done | done | Decoding is on the UI thread everywhere. All three `ContentFit` modes measured on both Apple toolkits — `docs/screenshots/content-fit-appkit.png` |

### Added in phase 3 — GTK and Android done, Apple pending

| Widget | Contract | GTK4 | Android | AppKit | UIKit | Notes |
|---|---|---|---|---|---|---|
| `Alert` | `WidgetKind.Alert` | done | done | — | — | `GtkAlertDialog` / `AlertDialog`. **Presented, not inserted** — see below |
| `Sheet` | `WidgetKind.Sheet` | done | done | — | — | A modal window / `AlertDialog` with a custom view. Presented **and** a container |

**The first presented widget, and it changed the contract.** An alert is not a child of
anything on any of the four toolkits, and GTK says so in its types: `GtkAlertDialog` is a
`GObject`, not a `GtkWidget`, so it could not be inserted even if we wanted to. So
`WidgetKind.presented` marks such kinds and the reconciler calls `Renderer.present` /
`Renderer.dismiss` instead of `insertAfter` / `removeChild`. Without it every renderer
would need the same special case in three methods — twelve places to keep in step.

Mounting presents, unmounting dismisses: `Show(confirming)(Alert(...))` is the whole API.
That is the same mechanism `Spinner` uses, rather than a second imperative way to say it.

**`Sheet` is what proved the seam generalises.** It is presented *and* a container, so its
children mount into its handle by the ordinary `insertAfter` path while only the attachment
differs — and it needed **no reconciler change**, which is the evidence that `presented` is
a real distinction rather than a special case shaped around `Alert`.

A presented widget lives in its **own window**, which has a testing consequence worth
stating: neither host's in-process view walk can see into it. The GTK self-test and the
Android one can observe that the screen *behind* is untouched and that the subtree's signals
still drive the model, but not the presented content itself. Anything about what the sheet
*shows* is asserted at the renderer boundary in `SheetSpec`. Both renderers routed a title
only for `Alert`, so a `Sheet`'s title went nowhere — and no automated check caught it,
because none of them could see it. A screenshot did.

Two things the platforms disagree about, both resolved in favour of stating *roles* and
letting each platform place them:

- **Button order.** GTK takes an array and answers with an index, so the declared order is
  the shown order. Android has three fixed *slots* and its own conventional positions —
  which is why `docs/screenshots/android-alert.png` shows Cancel left of Drop, the reverse
  of the order the app wrote. An app that assumed its own order would be right on GTK and
  wrong on Android.
- **What dismissal means.** `Prop.OnDismiss` is for the *platform* closing the alert —
  Escape, a tap outside, a back gesture — which is not the same as the user choosing a
  cancel button. Android distinguishes them natively (`setOnCancelListener` does not fire
  for a button). GTK does not: declaring a cancel button makes Escape *activate* it. So on
  GTK a cancel action absorbs Escape, and `OnDismiss` fires only when there is no cancel
  button. Both are the platform's own behaviour, which is the point.

### Added in phase 2 — done on all four (#4)

Slice 2:

| Widget | Contract | GTK4 | Android | AppKit | UIKit | Notes |
|---|---|---|---|---|---|---|
| `Slider` | `WidgetKind.Slider` | done | done | done | done | `GtkScale` / `SeekBar`. `Prop.Value` is in the **app's units**, not a fraction |
| `SecureField` | `WidgetKind.SecureField` | done | done | done | done | Entry visibility / password input type. A kind rather than a prop because `NSSecureTextField` is a separate class |

Slice 1:

| Widget | Contract | GTK4 | Android | AppKit | UIKit | Notes |
|---|---|---|---|---|---|---|
| `Toggle` | `WidgetKind.Toggle` | done | done | done | done | `GtkSwitch` / `Switch`. **No label**: only Android's has anywhere to put one, so the caption is a sibling |
| `Spacer` | `WidgetKind.Spacer` | done | done | done | done | Takes the room its siblings do not, through `Prop.Grow` |
| `ProgressBar` | `WidgetKind.ProgressBar` | done | done | done | done | `Prop.Progress`: `None` is indeterminate, which is not `Some(0.0)` |
| `Spinner` | `WidgetKind.ActivityIndicator` | done | done | done | done | No "running" prop; `Show` starts and stops it |

On Apple they are kind codes 9–14, each naming its control per toolkit in `Abi.kinds`:
`NSSwitch` / `UISwitch`, a plain view, `NSProgressIndicator` (`.bar`, `.spinning`) /
`UIProgressView` and `UIActivityIndicatorView`, `NSSlider` / `UISlider`, and
`NSSecureTextField` / a `UITextField` with `isSecureTextEntry`. **One platform gap:**
`UIProgressView` has no indeterminate mode, so `ProgressBar(None)` draws an empty bar on
UIKit. It is recorded as indeterminate and read back as such, rather than faked.

### Not started

| Group | Left to do |
|---|---|
| Layout | `Stack`/`ZStack`, `SafeArea`, `Grid` |
| Controls | `IconButton`, `Radio`, `Stepper`, `SegmentedControl`, `Picker`, `DatePicker`, `Link` |
| Containers | `TabView`, `Sheet`/`Modal`, `Menu`/`ContextMenu`, `Toolbar` |

`IconButton` and `Link` are held back on purpose: the first needs an icon/resource system
and the second needs platform URL opening, and neither should be improvised inside a widget.

### 12.2a Two entries in §7.10 are not cross-platform widgets

`docs/07` §7.10 listed the v1 catalogue **before any renderer existed**. Phase 2 is the
first time each entry has been checked against four real toolkits, and two of them do not
survive it. Verified against the installed GTK4 headers and `android-36/android.jar`:

| Widget | GTK4 | Android | AppKit | UIKit |
|---|---|---|---|---|
| `Radio` | `GtkCheckButton` + group | `RadioGroup` — a **container**, not a property | `NSButton` radio | **no radio control at all** |
| `Stepper` | `GtkSpinButton` | **none** (`NumberPicker` is a scrolling wheel, a different control) | `NSStepper` | `UIStepper` |

Each fails on exactly one platform, for a different reason.

- **`Radio` also needs grouping the contract does not have.** Mutual exclusion is a
  relationship between siblings, and Android expresses it *structurally* — the buttons must
  be children of a `RadioGroup` — while GTK expresses it as a pointer to a sibling. Neither
  is a prop. And iOS has no radio button, so the iOS idiom is a checkmark list or a
  segmented control, which is a different widget, not a styling of this one.
- **`Stepper` has no Android equivalent**, and the Android idiom is a row of buttons the
  app composes — which the framework can already express.

**Consequence for the target.** "32 widgets" is not the right denominator. A widget earns a
place in the catalogue by existing on every platform we render to; one that does not is
either an app-level composition or a platform-specific escape hatch (`.platform` in
`docs/11`), and forcing it into the contract would mean two renderers faking it. `Radio` and
`Stepper` are the first two entries to be reclassified, and the question should be asked of
every remaining entry before it is built, not after.

## 12.3 Structure and behaviour (not widgets, but shipped)

| Feature | Where | Status |
|---|---|---|
| Fine-grained signals | `modules/signals` | done — glitch-free, property-tested on JVM/JS/Native |
| Keyed reconciliation | `Reconciler` | done — `Show`, `Switch`, `ForEach`, `Fragment`, in-place `moveAfter` |
| Virtualised list | `LazyColumn` + `RowSource` | done — all four recycle. Materialised rows for a 10 000-row list: GTK **205**, Android **66**, AppKit **40**, UIKit **34** |
| Navigation | `Nav`, `NavHost`, `AppRoot` | partial — stack, title, Up and **toolbar actions** work as native chrome; no *native* navigation container |
| Theming | `Theme`, `ColorRole` | role → platform token, **per-subtree `Provide`**; **`Accent` reaches buttons but not `ProgressBar`**, so two accent-coloured controls render in different colours (visible in `docs/screenshots/android-catalogue.png`) |
| ZIO bridge | `modules/effect-zio` | done — `asSignal`, `launch`, `RemoteData`, `ErrorPresenter`; runs on iOS |
| UI-thread seam | `UiThread` | done |
| Apple ABI description + consistency check | `tools/shim-gen` | done — 38 functions and 17 kind codes described; `thicket_apple.h` and `Shim.scala` are generated from it (adopted 2026-09-29, #3) and checked byte-for-byte; the two Swift shims' signatures and per-platform widget choice are checked against it, on any machine |

## 12.4 Props

All four renderers handle all 22 props, because `Prop` is an `enum` and the match is
exhaustive under `-Werror` — a new prop breaks every renderer until it is handled, which is
the point of doing this in Scala.

`Text`, `OnTap`, `Spacing`, `Padding`, `Enabled`, `Placeholder`, `OnTextChange`, `Checked`,
`OnCheckedChange`, `Style`, `Grow`, `Align`, `Tint`, `Fill`, `Picture`, `Fit`,
`TextEmphasis`, `Axis`, `Progress`, `Value`, `Range`, `OnValueChange`.

**"Handled" is not "honoured."** Twenty-two are honoured everywhere. The remaining three —
`Message`, `Actions`, `OnDismiss` — are no-ops on Apple, but only because they belong to
`Alert`, which its shim cannot present yet; they are unreachable rather than ignored.
`Axis` was the last prop that was genuinely ignored on a renderer that *could* act on it,
until Forgejo **#4**.

## 12.5 Renderers

| Renderer | Module | Layout mode | Virtual rows | Runs |
|---|---|---|---|---|
| GTK4 | `renderer-gtk` | ToolkitManaged | yes (`GtkListView`) | Linux, Scala Native |
| Android | `renderer-android` | ToolkitManaged | yes (`ListView`) | Android 26+, Scala on ART |
| AppKit | `renderer-apple` | ToolkitManaged | yes (`NSTableView`) | macOS, Scala Native + Swift shim |
| UIKit | `renderer-apple` | ToolkitManaged | yes (`UITableView`) | iOS simulator, Scala Native + Swift shim |
| Win32/WinUI | — | — | — | not started |
| DOM (dev canvas) | — | — | — | not started |

No renderer uses `LayoutMode.FrameBased` yet, which means **Yoga is not wired in**: every
container lays out its own children the platform way. S4 proved Yoga is reachable; nothing
depends on it until `Grid` or absolute positioning does.

## 12.6 What this list is for

**Division of labour.** Apple work is done on the macOS laptop, so anything AppKit/UIKit is
raised as an issue rather than attempted here. Horizontal `Scroll`, **#1** (virtualised
rows), **#2** (`ContentFit.Cover`), **#3** (shim generation) and **#4** (the phase 2
widgets) are done; `Alert` on Apple is next. Everything else is built and measured on the
Linux box.

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
| 1 | **Shim generation** (Forgejo **#7**, phase 1 — *adopted 2026-09-29, GitHub #3; Swift bodies deliberately stay hand-written*) — Swift, C header and Scala externs from one widget description | The Mac measured **11.0 non-comment Swift lines per exported function**, projecting ~240 functions for the v1 catalogue and roughly **5 200 lines of Swift maintained in duplicate** across the two shims (`docs/09`). Hand-writing the remaining 23 widgets four times over is the single largest cost in the project, and generation removes most of it. A prerequisite, not an optimisation. |
| 2 | **Widget breadth** — ~20 of the 32, chosen by what a real app cannot do without | `Toggle`, `Spacer`, `Slider`, `Picker`, `ProgressBar`, `ActivityIndicator`, `Alert`, `Sheet`, `TabView`. The demo currently fakes two of these. |
| 3 | **Apple parity** — `Alert`; the phase 2 widgets, virtualised rows, horizontal `Scroll` and `ContentFit.Cover` are done | `LazyColumn` silently mounting 10 000 rows on iOS was the worst kind of gap: it worked in the demo and died in an app. Now 40 rows on AppKit and 34 on UIKit. |
| 4 | **Native navigation containers** and per-subtree theming | The two places the framework currently asks the app to accept something non-native. |
| 5 | **Published artefacts and a getting-started** | Without these, "an outside developer" is not a thing that can be tested. |

**Explicitly out of the MVP:** Windows, Yoga/`FrameBased` layout, the Scala.js dev canvas,
the CLI, the inspector, and the cats-effect bridge. Each is real work with no dependent
above it.

**The test for done** is the one `docs/08` M4 already proposed and this repo can now
actually run: a real client app — the meal-planner against its Caliban server — built by
someone who did not write the framework, running on all three targets.

## 12.8 How the tests are run

**zio-test, every module, every backend.** `sbt "<module><Platform>/testOnly *"` — and
`testOnly *` rather than `test`, because sbt 2's `test` is incremental and will happily run
**zero** tests and report success.

| Target | Tests |
|---|---|
| `signalsJVM` / `signalsNative` | 21 |
| `signalsJS` | 19 — no `ThreadGuardSpec`, JS has no threads |
| `coreJVM` / `coreJS` / `coreNative` | 73 |
| `effectZioJVM` | 13 |
| `shimGen` | 10 |

Four rules the migration established:

1. **Anything touching the signal graph needs `@@ TestAspect.sequential`.** The
   dependency-tracking context is two process-global `var`s, so parallel tests corrupt each
   other. zio-test runs a suite's tests in parallel by default.
2. **Suites sharing process-global state should be one spec.** `sequential` orders tests
   within a spec and promises nothing between specs, which zio-test runs concurrently, so
   two specs both installing `UiThread` and `ThreadGuard` can interleave. `EffectZioSpec`
   holds both the bridge and the remote-screen suites for this reason.

   *Weaker evidence than the others, and worth saying so.* This was adopted to fix a flaky
   test, and it did not: the flake was rule 3 below, and it kept failing for another two
   rounds. The rule stands on its own argument rather than on that failure, and merging the
   specs costs nothing — but it is a precaution, not a diagnosis, and it was presented as a
   diagnosis once already.
3. **A test's `UiThread` must marshal, not run inline.** `install(f => f())` runs the post
   on whatever thread called it — a ZIO fibre, typically — so signal writes land off the
   test thread and a spin-wait races on memory visibility. `TestUiThread` queues and drains.
4. **Never compare a stringified number.** Scala.js renders `7.0` as `"7"`. `TestRenderer`
   keeps numeric props in `nums: Map[String, Double]`, not in the string map.

And the process rule behind all four: **run the cross-built modules on all three
backends.** Phase 2 shipped a test that only ever passed on the JVM.

## 12.9 Coverage

```bash
./bin/coverage.sh
```

**91.10% statement, 87.64% branch**, 1596 of 1752 statements, measured 2026-09-29.

| Package | Statement |
|---|---|
| `thicket.tools.shim` | 94.31% |
| `thicket.signals` | 91.71% |
| `thicket.core` | 89.94% |
| `thicket.zio` | 69.44% |

`build.sbt` sets a ratchet just under the measured figure — 87% statement, 83% branch,
`coverageFailOnMinimum := true`. It exists to stop coverage sliding, not to be hit exactly.
Raise it when the real number moves up.

### What the number does *not* cover

**The three renderers.** GTK, Android and Apple are Scala Native and ART code, exercised by
self-tests that drive the real toolkit in a real process — 42 checks on GTK, 35 on Android,
27 and 26 on macOS and iOS. scoverage instruments none of that, so those modules contribute
nothing to the figure above and **their absence is not a gap in testing**. The number is
coverage of the effect-free core, and quoting it as "the project's coverage" would be
wrong in both directions at once.

`renderer-api` is also excluded, for a mechanical reason: instrumented code writes its
measurements into its *own* module's data directory, which only exists once that module's
tests run, and `renderer-api` is pure types with no tests. Its default methods are reached
through `core`'s tests either way.

### Two traps, both of which hand you a wrong answer silently

1. **sbt 2's disk cache restores compiled classes without running the compiler.**
   `scoverage.coverage` — the metadata mapping a measurement back to a statement — is a
   compile *side-effect*, not a tracked output, so a cache hit gives you instrumented
   classes with no metadata, and that module **disappears from the aggregate with no
   error**. This is how `signals` vanished while still producing measurement files.
   `bin/coverage.sh` clears `~/.cache/sbt/v2` for this reason; a coverage run that skips
   that step is not measuring what you think.
2. **A report with no metadata at all reads `100% of 0 statements`** — and passes
   `coverageFailOnMinimum` at any threshold. The script checks the statement count is
   non-zero before printing the percentage, because a coverage gate that cannot fail is
   worse than no gate.

Related: the same disk cache does not notice a *deleted* source file (`decisions.md`,
2026-09-29). It is worth suspecting early.
