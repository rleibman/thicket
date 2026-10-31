# thicket-clipboard

Text on the system clipboard, shared with every other app. The plan is in
`docs/16-device-services.md`, §16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-clipboard` | JVM, Native | the API: `Clipboard` (`set`, `get`); `testing.MemoryClipboard` |
| `thicket-clipboard-gtk` | Scala Native | `GdkClipboard`, the display's clipboard |
| `thicket-clipboard-android` | Android | `ClipboardManager` |

Apple's half is a Mac issue.

```scala
val clipboard = GtkClipboard()          // inside GtkApp.run; AndroidClipboard(this) in createApp
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
- **Text only.** Images and files are for the share sheet, still to come.

## How it is checked

The galleries copy a numbered text and paste it back, twice. Each paste must show that round's
copy, so a clipboard that never took the text, or kept an older one, fails. With `set` doing
nothing, both rounds fail on GTK and on Android, and each paste shows a previous run's text,
which also shows the read reaches the system clipboard. On GTK, `wl-paste` in another process
read the gallery's copy (measured once on the desktop; CI's Xvfb has no `wl-paste`).
