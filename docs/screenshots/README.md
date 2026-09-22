# Screenshots

One small, deliberately-updated set — refreshed at milestones rather than on every change.

| File | Platform | What it shows |
|---|---|---|
| `android-todo.png` | Android 36 emulator, release APK (144 KB) | The list screen of `examples/shared/TodoApp.scala` — the same element tree the GTK host renders. Native action bar, `EditText` with hint, Material `CheckBox`, and list rows that are tappable *containers* rather than buttons. `2 of 3 done` is a reactive caption. |
| `android-detail.png` | same | After a **real touch** on a row. The action bar title follows the top of the nav stack, and the Up arrow appears because `canGoBack` is true — neither is drawn by the framework; both come from `AppRoot` chrome applied natively. |

Regenerate while the demo is running (`examples/todo-android/build.sh`):

```
adb exec-out screencap -p > docs/screenshots/android-todo.png
```

**Read them critically.** What is still missing is as visible as what works: there is no
theming (everything is the platform default), no press feedback or separators on rows, and
no images. Tracked in `docs/11`.
