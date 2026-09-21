# 11. What the framework looks like

The companion to this document is [`examples/mockup/MealPlanner.scala.mockup`](../examples/mockup/MealPlanner.scala.mockup)
— a complete meal-o-rama mobile client written as if the framework were finished. It does
not compile and is not on the build path. Read the two together.

## 11.1 The thesis

Porting Flutter to Scala would be a waste of everyone's time. Flutter is good, Compose is
good, and "the same thing but in a language with fewer packages" is not a reason for anyone
to switch. If this framework is worth building, it is because **Scala 3's type system lets a
UI make guarantees the others cannot**, and because a ZIO shop can share its domain with the
server rather than re-describing it.

So the design goal is not parity. It is: *the compiler should reject the bugs that dominate
real UI work.* Blank screens from unhandled loading states. Navigating to a screen without
its arguments. Submitting a form the server will reject. Leaked subscriptions. A view calling
the network because nothing stopped it.

## 11.2 Seven ideas

**1. Navigation is a value.** `Route` is an ADT, so a screen cannot be reached without its
arguments, the back stack is a `List[Route]`, state restoration is serialising a list, deep
links parse into one, and a test asserts on it directly. No string paths, no route registry.

**2. The app is a total function `Route => Screen`.** Add a route, and the compiler tells you
which match is missing. There is no runtime "unknown route" branch because there is no way to
have one.

**3. Async is an exhaustive match.** Every ZIO reaching the UI arrives as
`Async[E, A] = Loading | Failed(e) | Done(a)`. The single most common UI bug — a blank screen
because loading or failure was not handled — becomes a non-exhaustive-match error. Flutter's
`FutureBuilder` and Compose's `collectAsState` both let you skip these; this does not.

**4. Capabilities are declared per screen.** `def PantryScreen()(using Nav, Pantry)` can
navigate and read the pantry, and *cannot* make an HTTP call, because it never asked for one.
This is ZIO's `R` channel pushed down to the view layer and checked at compile time, rather
than a service locator that fails at runtime.

**5. Validation lives in the type, and is the server's type.** `RecipeDraft` carries
refinements (`String :| NonEmpty`, `Int :| Between[1, 24]`) and is the *same class the
Caliban server validates against*. The Done button's `enabled` is derived from it, so you
cannot forget to disable it, and you cannot submit something the server will reject.

**6. Effects are owned by the component.** `launch` forks into the component's ZIO `Scope`.
Navigate away and the fiber is interrupted and the socket closed — not because someone wrote
a cleanup callback, but because the scope closed. This is the one idea taken straight from
ZIO rather than from Scala's type system, and it is why the ZIO bridge is first-class
(§7.13) rather than an adapter.

**7. Platform fidelity by refinement, not abstraction.** The default widget is already the
platform's own. `.platform(ios = …, desktop = …)` adds something on one platform, locally,
without forking the screen. Compare with the usual cross-platform bargain, where you get the
intersection of all platforms and bolt on escape hatches.

## 11.3 What this buys that the incumbents do not

| | Flutter | Compose MP | React Native | scala-ui (intended) |
|---|---|---|---|---|
| Unhandled loading state | runtime blank | runtime blank | runtime blank | **compile error** |
| Navigate without args | runtime | runtime | runtime | **compile error** |
| Screen performs unauthorised effect | possible | possible | possible | **compile error** |
| Submit server-invalid form | runtime | runtime | runtime | **compile error** |
| Domain shared with server | no | JVM only | no | **yes, all targets** |
| Native widgets | mimicked | mimicked | yes (desktop lags) | **yes, all five** |
| Leaked subscription | manual | manual | manual | **scope-closed** |

The last column is the pitch. If it does not hold, there is no reason to build this.

## 11.4 What exists today, honestly

Of the mockup, roughly **30%** is real. Concretely, after M0 + structural reconciliation:

| Real now | Invented in the mockup |
|---|---|
| `Signal`, `Var`, `computed`, `effect`, `Owner` lifetimes | everything ZIO-facing (`asSignal`, `launch`, `Async`) |
| `Element`, `Attr`, the reconciler, one effect per reactive attribute | `Screen`, `Nav`, `Route`, tabs, toolbars |
| **`Show` and `ForEach` with keyed diffing and in-place moves** | virtualisation (`LazyColumn`) — `ForEach` mounts every row |
| **`Route` ADT, `Nav` back stack, `Screen`, `NavHost`** | tabs, deep-link parsing (`derives Routable`) |
| **Native chrome via `AppRoot`** — title, Up, predictive back | toolbar actions (the type exists; no renderer applies them yet) |
| **`Fragment`** — several children in one slot | forms, refinements, `.platform`, swipe actions |
| `Column`, `Row`, `Label`, `Button` | every other widget in the mockup |
| The renderer contract + **GTK4 and Android renderers** + a `TestRenderer` | `TestApp`, `UiSpec` |
| **One shared UI mounting on Linux and Android unchanged** | `Async` as an exhaustive match |
| Owner never appears in app code | |

`ForEach` already gives the mockup's central promise: a row whose item keeps its key but
changes its data is **patched, not rebuilt** — measured at exactly one renderer call for one
changed item in a three-item list. What it does not yet do is *virtualise*, so a thousand
rows really are a thousand widgets. That is the difference between `ForEach` and the
mockup's `LazyColumn`, and it is a renderer-level concern (`UITableView`, `RecyclerView`,
`GtkListView`) rather than a reconciler one.

The counter in `examples/counter-gtk` is 14 lines and looks like the mockup's inner blocks
already. The gap is not in the *shape* of the API — it is in breadth and in everything above
the element tree.

## 11.5 The order to build it in

Each step is chosen to make the next one cheap, and to keep something runnable at every point.

1. ~~**Structural reconciliation**~~ — **done.** `Show`, `ForEach` with keyed diffing,
   `Fragment`, in-place reordering via a `moveAfter` contract primitive, and regions that
   nest and sit transparently between static siblings.
2. ~~**A second renderer**~~ — **done.** Android, via S2's sbt→JAR→Gradle shape. The
   contract survived a structurally different toolkit unchanged; see §11.7.
3. ~~**`Screen` and `Nav`**~~ — **done**, except for native navigation *containers*: see §11.8.
4. **The ZIO bridge** — `asSignal`, `Async`, `launch`, component scopes. S8 proved the runtime
   works; this is the ergonomics layer over it.
5. **Widen the catalogue** — text input, scroll, images, lists. Mechanical, and the right
   moment to start generating the Apple shim rather than hand-writing it (S3).
6. **The Apple renderer** — last among the four, because by then the contract is settled and
   the shim can be generated. S1/S3/S8 already de-risked it.

Forms, refinements and `.platform` come after that. They are the most distinctive ideas and
the least urgent: nothing else depends on them.

## 11.6 The honest risk

Ideas 1–5 are cheap to state and expensive to make good. `Async` as an exhaustive match is
maybe fifty lines; making `Skeleton.list` look right on four platforms is not. The pattern in
this whole project so far has been that **the type-level ideas are easy and the platform
breadth is the work** — phase 0 measured exactly that, and the mockup should be read with it
in mind.

The other risk is that idea 7 is a promise about taste. "One declaration, three idioms" only
holds if someone keeps making judgement calls about what a swipe action *means* on GTK. That
is not a type system problem and it does not get easier with scale.

## 11.7 What the second renderer proved (and cost)

`examples/shared/.../TodoUi.scala` is mounted unchanged by the GTK renderer on Linux and by
the Android renderer on a phone. The same self-test — drive the model, read the widget order
back out of the *toolkit* — passes on both. The contract needed **no changes** to accommodate
Android, which is the first real evidence that it is an abstraction rather than a description
of GTK.

Where the two renderers genuinely differ is instructive:

| | GTK4 | Android |
|---|---|---|
| Child insertion | no insert-at-index; `prepend` / `insert_child_after` | `addView(child, index)` — index is native |
| Reorder | `gtk_box_reorder_child_after`, so `moveAfter` is overridden | no primitive; the contract's remove+insert default is used |
| Spacing | a box property | no such property — recomputed as child margins on every structural change |
| Threading | `g_idle_add` | `Handler(Looper.getMainLooper)` |

The `insertAfter`-by-sibling decision (from S7, because GTK has no index) turned out to cost
Android nothing: `indexOfChild(after) + 1` recovers the index. Had the contract been written
against Android first, it would have specified an index and GTK would have had to emulate it.

**What it cost: nothing measurable at startup, and 9–22 ms of real work.** An earlier draft
of this section claimed the framework added ~90 ms and had pushed the app over N-01. That was
wrong: it compared figures from two different emulator sessions. Measured properly — all three
APKs installed together, launched round-robin, 10 rounds each — the bare Kotlin activity, the
bare Scala activity and the full scala-ui todo app are **418 / 386 / 388 ms**, i.e.
indistinguishable, with Kotlin nominally slowest.

Instrumenting `onCreate` shows where the framework's time actually goes:

| phase | ms |
|---|---|
| process start → `onCreate` (class loading, ART) | 18–38 |
| build the element tree | 0–2 |
| **`Reconciler.mount`** | **9–20** |
| `setContentView` (Android's own measure/layout) | 11–38 |

So mounting a ~15-widget tree costs **9–20 ms**, and that is the number that will scale with
the catalogue. It is worth watching, but it is not a startup regression — the ~350 ms floor is
Android's process creation and first frame, which every app pays.

Release APK: **136 KB**, framework and app together.

## 11.8 Navigation: what landed, and the one thing that did not

Two of the mockup's seven ideas are now real:

**Navigation is a value.** `Route` is an app-defined ADT; the back stack is `List[Entry[R]]`;
`nav.routes.now` is what a test asserts on and what a host persists. The Android example
round-trips its stack through `onSaveInstanceState` as plain strings — no framework save/restore
protocol, because there is nothing framework-specific to save.

**The app is a total function `Route => Screen`.** `NavHost(nav) { case … }` takes a plain
function over the ADT, so adding a route breaks the match at compile time. There is no route
registry to forget to update.

Chrome travels beside the tree in `AppRoot`, never inside it, so each host applies it natively:
GTK sets the window title and packs a back arrow into the header bar; Android sets the action bar
title, shows Up, and registers an `OnBackInvokedCallback` so the **predictive back gesture** works.
`back()` returning `false` at the root is what lets the Activity finish normally — verified with a
real `KEYCODE_BACK`.

**What did not land: native navigation containers.** `NavHost` mounts only the top screen, so
pushing unmounts the screen beneath and popping rebuilds it. A `UINavigationController`, a
fragment back stack or a `GtkStack` keeps the whole stack alive, which is what preserves scroll
position and in-flight state across a push — and what provides the slide transition. That is a
renderer change behind the same API; the app-facing shape does not move. Until then, back
navigation is correct but forgetful.

### A renderer bug this exposed

Navigation was the first thing to unmount a whole subtree, and GTK objected immediately:

```
GtkButton 0x… has a parent GtkBox 0x… during dispose. Parents hold a reference,
so this should not happen. Did you call g_object_unref() instead of gtk_widget_unparent()?
```

The reconciler was detaching a region's top handles and *then* destroying the subtree
depth-first. On GTK, `gtk_box_remove` frees the removed widget's entire subtree, so the
depth-first destroy was touching freed memory. The contract now states that **`destroy`
detaches as well as releases**, and disposal is depth-first with no separate removal step. All
three renderers implement it; the `TestRenderer` models it too, or it would stop being a
reference implementation.

Worth noting what caught this: not the 32 unit tests, which passed throughout, but running the
app against a real toolkit that checks its own invariants.
