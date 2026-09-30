# Screenshots

One small, deliberately-updated set — refreshed at milestones rather than on every change.

| File | Platform | What it shows |
|---|---|---|
| `android-todo.png` | Android 36 emulator, release APK (165 KB) | The list screen of `examples/shared/TodoApp.scala` — the same element tree the GTK host renders. Native action bar, `EditText` with hint, Material `CheckBox`, and list rows that are tappable *containers* rather than buttons. `2 of 3 done` is a reactive caption. The action row below the list is a horizontal `Scroll`: a fifth button sits off the right edge — see `android-row-overflow.png`. |
| `android-lazy.png` | same | `LazyColumn` with **10 000 rows**, scrolled to row 1 081. Android reports **66 views** — the list materialises only what is on screen. Without virtualisation this would be 30 000+ views. |
| `android-row-overflow.png` | same | The same action row **scrolled left**: `Add` has gone off the edge and `10 000 rows` — the fifth button, which used to be silently clipped — is reachable. Five buttons do not fit 1080 px, and `Row` does not wrap, so the app wraps it in `Scroll(axis = Orientation.Horizontal)`. Android renders that as a `HorizontalScrollView`; GTK as a scrolled window with `GTK_POLICY_AUTOMATIC` horizontally and `NEVER` vertically. |
| `android-catalogue.png` | same | The widgets phase 2 added: a Material `Switch` in a settings row pushed to the trailing edge by a `Spacer`, a determinate `ProgressBar` at 2 of 3 done, a `SeekBar` sitting at **7.0 of 0–11** — the app's own units, converted by the renderer — and a masked `SecureField`. Note the bar is Android's **default blue** while the buttons are the app's branded green — `ColorRole.Accent` does not reach it yet, which is a real gap and is recorded in `docs/12`. |
| `android-toolbar-actions.png` | same | `Screen.actions` in Android's own action bar — **ADD ABOUT**, uppercased by the platform though the app wrote "Add" and "About". The same two labels appear as ordinary green buttons in the content below, which is the contrast worth looking at: chrome is styled by the platform, the tree is styled by the app. |
| `android-sheet.png` | same | A `Sheet` — the app's own content (a bound `TextField`, Save/Close) presented over the dimmed screen as a native modal, with its title rendered by the platform. Built on `AlertDialog.setView`, because a plain `Dialog` reserves a title band and draws nothing in it. |
| `android-alert.png` | same | A native Material alert, presented by a signal rather than by a call — `Show(confirming)(Alert(...))`. Note **Cancel is left of Drop**, the reverse of the order the app declared them: Android has three fixed button *slots* and places by its own convention, so the app states roles (`cancel`, `destructive`) and each platform decides position. |
| `android-detail.png` | same | After a **real touch** on a row. The action bar title follows the top of the nav stack, and the Up arrow appears because `canGoBack` is true — neither is drawn by the framework; both come from `AppRoot` chrome applied natively. |
| `ios-phase2-widgets.png` | iOS 27.0 simulator (iPhone 17), UIKit | The same screen on iOS after the self-test: a `UISwitch` pushed to the trailing edge by a `Spacer`, a `UIProgressView` at **3 of 4 done**, a `UISlider` at **7.0 of 0–11** in the app's units, and a `UITextField` with `isSecureTextEntry` holding "hunter2". The draft form's checkbox is a `UISwitch` too, because UIKit has no checkbox. Unlike Android, there is no accent split: the bar, the slider and the buttons all take the system tint. |
| `content-fit-appkit.png` | macOS 13+, AppKit | The three `ContentFit` modes, a 400x100 image drawn into a 200x200 frame. `Contain` letterboxes; `Cover` fills and **crops** — the circle stays a circle and the gradient loses its outer thirds; `Fill` stretches and the circle becomes an ellipse. Before Forgejo **#6**, `Cover` rendered identically to the `Fill` panel: `NSImageView.imageScaling` has no cropping mode, so it was mapped to `.scaleAxesIndependently`. The captions are the measured marker bounding boxes, which is what the harness asserts on. |

Regenerate while the demo is running (`examples/todo-android/build.sh`):

```
adb exec-out screencap -p > docs/screenshots/android-todo.png
```

`content-fit-appkit.png` is generated rather than captured, so it can be reproduced exactly:

```
modules/renderer-apple/shim/run-fit-harness.sh
cp modules/renderer-apple/shim/build/fit/content-fit-appkit.png docs/screenshots/
```

Every screenshot of the items screen carries the logo, which the *host* supplies as an
`ImageSource` rather than the shared app baking one in: the framework renders bytes and does
not fetch, and where those bytes live is a platform question. GTK reads a file beside the
binary; Android reads an asset out of the APK, because `BitmapFactory.decodeFile` cannot see
inside one.

The in-app asset is **transparent**, not the white-background one the README uses — it has
to sit on whatever surface the platform theme provides, and a white card would look pasted
on.

**Read them critically.** What is still missing is as visible as what works: no images, no
branding beyond the one accent role, the buttons are still the platform default rather than
anything designed, and a horizontally scrolling button row is a workaround for the absence of
a wrapping container, not a design. Tracked in `docs/11`.
