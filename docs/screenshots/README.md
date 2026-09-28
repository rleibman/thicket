# Screenshots

One small, deliberately-updated set — refreshed at milestones rather than on every change.

| File | Platform | What it shows |
|---|---|---|
| `android-todo.png` | Android 36 emulator, release APK (154 KB) | The list screen of `examples/shared/TodoApp.scala` — the same element tree the GTK host renders. Native action bar, `EditText` with hint, Material `CheckBox`, and list rows that are tappable *containers* rather than buttons. `2 of 3 done` is a reactive caption. The action row below the list is a horizontal `Scroll`: a fifth button sits off the right edge — see `android-row-overflow.png`. |
| `android-lazy.png` | same | `LazyColumn` with **10 000 rows**, scrolled to row 1 081. Android reports **66 views** — the list materialises only what is on screen. Without virtualisation this would be 30 000+ views. |
| `android-row-overflow.png` | same | The same action row **scrolled left**: `Add` has gone off the edge and `10 000 rows` — the fifth button, which used to be silently clipped — is reachable. Five buttons do not fit 1080 px, and `Row` does not wrap, so the app wraps it in `Scroll(axis = Orientation.Horizontal)`. Android renders that as a `HorizontalScrollView`; GTK as a scrolled window with `GTK_POLICY_AUTOMATIC` horizontally and `NEVER` vertically. |
| `android-detail.png` | same | After a **real touch** on a row. The action bar title follows the top of the nav stack, and the Up arrow appears because `canGoBack` is true — neither is drawn by the framework; both come from `AppRoot` chrome applied natively. |

Regenerate while the demo is running (`examples/todo-android/build.sh`):

```
adb exec-out screencap -p > docs/screenshots/android-todo.png
```

**Read them critically.** What is still missing is as visible as what works: no images, no
branding beyond the one accent role, the buttons are still the platform default rather than
anything designed, and a horizontally scrolling button row is a workaround for the absence of
a wrapping container, not a design. Tracked in `docs/11`.
