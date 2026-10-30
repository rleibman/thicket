# thicket-pickers

The system's photo picker, document picker and save dialog. The plan is in
`docs/16-device-services.md`, §16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-pickers` | JVM, Native | the API: `Photos`, `Documents`; `PickedFiles`; `testing.FakePhotos`, `FakeDocuments` |
| `thicket-pickers-gtk` | Scala Native | `GtkFileDialog`, over `GtkApp`'s window |
| `thicket-pickers-android` | Android | the photo picker and the Storage Access Framework, from `ThicketActivity` |

Apple's half is a Mac issue.

```scala
val pickers = GtkPickers(storage.dirs.cache)      // AndroidPickers(this) in createApp
Button("Choose a photo")(pickers.photos.pick(1) { paths => paths.headOption.foreach(show) })
Button("Export")(pickers.documents.save("notes.txt", "text/plain", bytes)(_.foreach(n => status.set(s"Saved $n"))))
```

- **A copy, as a `Path`** (owner's decision, 2026-10-30). Each picked file is copied into the
  app's cache, in a directory of its own, and handed back as a path the app may read, move or
  delete. On Android a pick is a `content://` URI that stays readable only for a while, so a
  copy is the one form that behaves the same everywhere. The cost is the copy: a large video
  is copied in full, on a worker thread. The cache is the system's to clear, so move what you
  keep.
- **Callbacks**, on the UI thread: empty, or `None` for `save`, when the user cancels.
- **One at a time.** A request while a picker is up answers empty at once.
- **`Documents`, not `Files`**, which would collide with `java.nio.file.Files`.
- **Android:** the photo picker (`ACTION_PICK_IMAGES`, Android 13+, no permission needed)
  names its copies by media id (`36.png`), not the original name. Before Android 13,
  `ACTION_GET_CONTENT`. Documents use `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT`.
- **GTK:** `GtkFileDialog` (GTK 4.10+), through the desktop portal when the app is sandboxed.
  `save` writes to the file the user chose.

## How it is checked

- `PickersSpec` (JVM and Native, 4 tests): `PickedFiles` copies byte for byte, closes the
  stream when the copy fails, and keeps a picked name inside its directory; the fakes.
- The galleries answer the real pickers:
  - **Android:** `build.sh` taps the photo picker and the documents UI, and reads the saved
    file back from Downloads.
  - **GTK:** the self-test turns the portal off, so the chooser is in-process, then answers it.
  - Both check that each pick comes back as a copy in the app's cache, byte for byte, and that
    cancelling answers nothing.
