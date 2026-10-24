# thicket-storage

Where an app keeps its files, and its preferences. The plan and the decisions are in
`docs/16-device-services.md`.

| Module | Platform | Directories | Preferences |
|---|---|---|---|
| `thicket-storage` | JVM, Native | the API: `AppDirs`, `Preferences`, `Storage`; `FilePreferences`; `testing.MemoryStorage` | |
| `thicket-storage-gtk` | Scala Native | XDG: `$XDG_DATA_HOME/<appId>`, `$XDG_CACHE_HOME/<appId>`, and `tmp` inside that cache directory | `FilePreferences` in the data directory |
| `thicket-storage-android` | Android | `getFilesDir`, `getCacheDir`, `getCacheDir/tmp` | `SharedPreferences` |
| `thicket-storage-apple` | Scala Native (macOS, iOS) | `~/Library/Application Support/<bundleId>`, `~/Library/Caches/<bundleId>`, Darwin's per-user temporary directory`/<bundleId>`; on iOS `~` is the container. Checked against `FileManager.urls(for:in:)` and `NSTemporaryDirectory()` | `FilePreferences` in the data directory |

```scala
val storage: Storage = GtkStorage("dev.example.app")     // or AndroidStorage(activity), AppleStorage(bundleId)
val prefs = storage.preferences
prefs.putLong("opens", prefs.getLong("opens").getOrElse(0L) + 1)
java.nio.file.Files.writeString(storage.dirs.data.resolve("notes.txt"), "…")
```

- **Files:** read and write them with `java.nio.file`, which works on every platform.
  Thicket only says *where*.
- **Types:** a preference reads back as the type it was written as, and as `None` when read
  as another type, on every platform. Each value is stored as a tagged string
  (`StringPreferences`), so Android keeps a `Double` at full precision.
- **Size:** preferences are for small values. Anything structured belongs in a file or in
  SQLite (`modules/sqlite`).
- **Tests:** `testing.MemoryStorage` is a `Storage` with memory preferences and fresh
  temporary directories.
