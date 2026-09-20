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

Of the mockup, roughly **15%** is real. Concretely, after M0:

| Real now | Invented in the mockup |
|---|---|
| `Signal`, `Var`, `computed`, `effect`, `Owner` lifetimes | everything ZIO-facing (`asSignal`, `launch`, `Async`) |
| `Element`, `Attr`, the reconciler, one effect per reactive attribute | structural reconciliation (`ForEach`, `Async` branches) |
| `Column`, `Row`, `Label`, `Button` | every other widget in the mockup |
| The renderer contract + GTK4 renderer + a `TestRenderer` | `Screen`, `Nav`, `Route`, tabs, toolbars |
| A counter app that builds and runs | forms, refinements, `.platform`, swipe actions |
| Owner never appears in app code | `TestApp`, `UiSpec` |

The counter in `examples/counter-gtk` is 14 lines and looks like the mockup's inner blocks
already. The gap is not in the *shape* of the API — it is in breadth and in everything above
the element tree.

## 11.5 The order to build it in

Each step is chosen to make the next one cheap, and to keep something runnable at every point.

1. **Structural reconciliation** — `Show`, `ForEach` with keys, subtree replace. Without it
   nothing above can exist, and it is the last piece of the core that is genuinely hard.
2. **A second renderer** — Android, reusing S2's build shape. This is where the contract gets
   its real test, and it puts the framework on the user's own phone.
3. **`Screen` and `Nav`** — the route ADT, a native navigation container per platform. This is
   what turns "widgets" into "an app".
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
