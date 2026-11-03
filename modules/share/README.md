# thicket-share

Handing text or a file to another app through the platform's share sheet. The plan is in
`docs/16-device-services.md`, §16.3 and §16.10 item 5.

| Module | Platform | From |
|---|---|---|
| `thicket-share` | JVM, Native | the API: `Share`; `Share.unavailable(reason)`; `SharedFiles`; `testing.RecordingShare` |
| `thicket-share-android` | Android | `ACTION_SEND` through the chooser; `ThicketFileProvider` serves files |
| `thicket-share-apple` | Scala Native, macOS and iOS | `NSSharingServicePicker`, `UIActivityViewController`; a C helper, no Swift (#134) |

GTK has no share sheet: use `Share.unavailable(reason)`.

```scala
val share = AndroidShare(this)                          // in createApp; on GTK, Share.unavailable("…")
// Apple, inside AppleApp.run:  AppleShare(storage.dirs.cache)
Button("Share link")(share.text(url, subject = Some("A recipe")))
Button("Export")(share.file(exported, "application/pdf"))
```

- **Fire and forget**, from the UI thread: the platform doesn't say what the user picked.
- **A file is shared as a copy**, so the app may change or delete the original at once.
- **Android: declare the provider.** Inside `<application>` in `AndroidManifest.xml`:
  ```xml
  <provider android:name="thicket.share.android.ThicketFileProvider"
            android:authorities="${applicationId}.thicket.files"
            android:exported="false" android:grantUriPermissions="true" />
  ```
  It is Thicket's own, against the framework, so the app needs no AndroidX. It serves only
  the copies under `<cache>/thicket-shared`, read-only, to the app the user picks, through the
  read grant that comes with the share. Not exported: nothing else can read it. A file shared
  without the element throws `IllegalStateException` naming it. Copies older than a day are
  deleted at each share.
- **Text** needs nothing.
- **Apple.** iOS presents a `UIActivityViewController` over whatever is on top of the key
  window; macOS shows an `NSSharingServicePicker` popover below the key window's content. A
  file is copied into `<cache>/thicket-shared` on the calling thread, and the copy's URL is
  shared: no grant is needed, the system hands a file in the app's sandbox to the chosen app.
  Its type is its extension's. The `subject` reaches iOS apps that take one (Mail) through a
  `UIActivityItemSource`; macOS drops it. With no window, a call throws `IllegalStateException`.

## How it is checked

- `ShareSpec` (JVM and Native, 5 tests): `SharedFiles` serves a copy under its own name and
  type, serves nothing outside its directory (`..`, the type file), prunes old copies.
- The Android gallery is its own share target. `build.sh` taps it in the real chooser; the
  text arrives, and the file arrives with its name, type and bytes. Because the target is the
  gallery itself, which needs no grant, the self-test also checks that the share sheet, a
  different app, read the file through the grant. Without the grant, that check fails.
- The Apple galleries (`GalleryShareChecks`, #134). **macOS** (8 checks): the picker's popover
  window appears, its delegate was offered services (5 for the text, 9 for the file, measured),
  it closes with no choice; the file is a copy with its bytes and type. No service is driven:
  there is no "Copy", the list is drawn by another process, and reaching it from outside needs
  the Accessibility permission. **iOS** (9 checks): the `UIActivityViewController` is on top;
  its item answers the text, and the subject for Mail; `DRIVE share-copy` has an XCUITest tap
  "Copy" in the sheet, the sheet goes and the pasteboard changes, and `simctl pbpaste` outside
  the app must read the shared text. Mail, Messages and AirDrop themselves are not driven.
  With the presentation call removed, macOS fails 6 checks and iOS 7, every "appears" among them.
