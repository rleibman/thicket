# thicket-clipboard

Text on the system clipboard, shared with every other app. The plan is in
`docs/16-device-services.md`, §16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-clipboard` | JVM, Native | the API: `Clipboard` (`set`, `get`); `testing.MemoryClipboard` |
| `thicket-clipboard-gtk` | Scala Native | `GdkClipboard`, the display's clipboard |
| `thicket-clipboard-android` | Android | `ClipboardManager` |
| `thicket-clipboard-apple` | Scala Native, macOS and iOS | `NSPasteboard` / `UIPasteboard`, the general pasteboard (#123) |

```scala
val clipboard = GtkClipboard()          // inside GtkApp.run; AndroidClipboard(this) in createApp;
                                        // AppleClipboard() inside AppleApp.run
Button("Copy link")(clipboard.set(url))
Button("Paste")(clipboard.get(_.foreach(field.set)))
```

- **Reading is a callback**, on the UI thread, with `None` when the clipboard holds no text
  (empty, or an image). On GTK the text may belong to another app, which is asked for it
  (`gdk_clipboard_read_text_async`). On Android the answer comes on the next turn of the main
  loop, to match.
- **Android 10+** lets only the app with focus, or the keyboard, read the clipboard: `get`
  from the background answers `None`. Android 13+ shows its own "copied" confirmation on
  `set`.
- **Apple** reads the pasteboard synchronously when `get` is called, and answers on the next
  turn of the run loop (`AppleApp.postToUi`), as the others do. macOS: `clearContents` then
  `setString(_, forType: .string)`; iOS: `UIPasteboard.general.string`.
- **iOS's "Allow Paste" prompt** (iOS 16+), measured on the iOS 27 simulator (Xcode 27.0,
  "iPhone Air", 2026-10-30):
  - **Text this process copied** reads with no prompt: the gallery's own copies came back in
    25–77 ms.
  - **Anything else prompts**: text from `xcrun simctl pbcopy` ("GalleryIos would like to paste
    from CoreSimulatorBridge"), and — not obvious — **the same app's copy from an earlier
    launch** ("… would like to paste from GalleryIos").
  - While the prompt is up the read **blocks the main thread** until the user answers; the
    gallery waited on it for the whole 120 s run. `get` answers once it returns. The prompt
    itself is modal, so the user sees nothing frozen behind it.
  - Which choice the user made could not be driven from the command line; Apple documents a
    refusal as no text, so `None`.
- **Apple's helper** is C, not Swift (`resources/scala-native/thicket_pasteboard.c`): four
  Objective-C messages sent through the runtime (`objc_msgSend`), compiled by Scala Native's own
  clang, with UIKit or AppKit named in the object file (`LC_LINKER_OPTION`), so an app needs no
  flags and no extra archive — as `thicket-secrets-apple`'s Keychain helper (#108). A Swift
  library like `thicket-http-apple`'s (#90) would need its own build script, an archive carried
  in the jar, the plugins to unpack it, and a shim-gen ABI, for four calls.
- **Text only.** Images and files are for the share sheet, still to come.

## How it is checked

The galleries copy a numbered text and paste it back, twice. Each paste must show that round's
copy, so a clipboard that never took the text, or kept an older one, fails. With `set` doing
nothing, both rounds fail on GTK and on Android, and each paste shows a previous run's text,
which also shows the read reaches the system clipboard. On GTK, `wl-paste` in another process
read the gallery's copy (measured once on the desktop; CI's Xvfb has no `wl-paste`).

On Apple (#123) each copy must also raise the pasteboard's `changeCount`, and the round pastes
only when it did: on iOS a paste of any text this process did not write waits on the "Allow
Paste" prompt, which would hang the run instead of failing it. After the self-test,
`bin/verify-apple.sh` reads the pasteboard from outside the app — `pbpaste` on macOS (putting
the user's own text back afterwards), `xcrun simctl pbpaste` on iOS — and it must hold the
gallery's last copy. With `set` doing nothing, both rounds fail on macOS and on the simulator,
and the outside reads show the older text.
