# thicket-pickers

The system's photo picker, document picker and save dialog. The plan is in
`docs/16-device-services.md`, §16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-pickers` | JVM, Native | the API: `Photos`, `Documents`; `PickedFiles`; `testing.FakePhotos`, `FakeDocuments` |
| `thicket-pickers-gtk` | Scala Native | `GtkFileDialog`, over `GtkApp`'s window |
| `thicket-pickers-android` | Android | the photo picker and the Storage Access Framework, from `ThicketActivity` |
| `thicket-pickers-apple` | Scala Native, macOS and iOS | `PHPickerViewController` and `UIDocumentPickerViewController` on iOS, `NSOpenPanel` and `NSSavePanel` sheets on macOS, through its own Swift library (`libthicketpickers.a`, #125) |

```scala
val pickers = GtkPickers(storage.dirs.cache)      // AndroidPickers(this) in createApp; ApplePickers(storage.dirs.cache)
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
- **iOS:** `PHPickerViewController` (images, `selectionLimit = limit`, no permission needed).
  Each result's `loadFileRepresentation` gives a temporary file that is deleted when its
  callback returns, so the callback waits until Scala has copied it (`thk_pick_release`).
  Documents: `UIDocumentPickerViewController(forOpeningContentTypes:asCopy: true)`, whose copy in
  the app's tmp is deleted once Thicket's is made. `save` writes the bytes to a file named
  `name` in the cache and exports it (`forExporting:asCopy: true`); the staged file is deleted
  after.
- **macOS:** `NSOpenPanel` (images for `Photos`; the MIME types as `allowedContentTypes`;
  `allowsMultipleSelection`) and `NSSavePanel` (`nameFieldStringValue = name`), as sheets on the
  app's window. `save` copies the staged file to where the panel answered.
- **Apple's library:** Swift, not a C helper like `thicket-clipboard-apple`'s: the pickers
  answer through delegate objects and completion blocks, which C cannot write without an
  Objective-C file, and Scala Native compiles `.c` but not `.m`. Its ABI is `PickersAbi` in
  `tools/shim-gen` (11 functions, one callback type), checked by `PickersConsistencySpec`.

## How it is checked

- `PickersSpec` (JVM and Native, 4 tests): `PickedFiles` copies byte for byte, closes the
  stream when the copy fails, and keeps a picked name inside its directory; the fakes.
- The galleries answer the real pickers:
  - **Android:** `build.sh` taps the photo picker and the documents UI, and reads the saved
    file back from Downloads.
  - **GTK:** the self-test turns the portal off, so the chooser is in-process, then answers it.
  - **iOS (simulator):** `build-app.sh` adds the logo to the photo library and a text file to
    Files' "On My iPhone"; the XCUITest tapper (`examples/ios-host/tapper`) taps the photo,
    Cancel, the file, and Save in "On My iPhone", then reads the saved file back on the host.
  - **macOS 27:** the panels run in `com.apple.appkit.xpc.openAndSavePanelService` even for an
    unsandboxed app (`-[NSSavePanel ok:]` raises "not implemented"; neither key events nor
    accessibility reach its buttons from the app). The self-test checks the real sheet, its
    types and suggested name, and its own `cancel(_:)`; an accept is
    `ApplePickers.testing.choose`, which answers through the panel's own completion with a
    known file. The panel's OK button is not exercised.
  - Both check that each pick comes back as a copy in the app's cache, byte for byte, and that
    cancelling answers nothing.
