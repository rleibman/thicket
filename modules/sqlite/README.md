# thicket-sqlite

SQLite through plain JDBC: `java.sql` is the API, the same on every platform, and Thicket
adds no query layer (`docs/16-device-services.md` §16.5).

| Platform | Driver |
|---|---|
| GTK (and Apple, #98) | `NativeSqlite.driver`, over `com.github.lolgab::scala-native-jdbc-sqlite` 0.0.7 |
| Android | `AndroidSqlite.driver`, in `thicket-sqlite-android`: Thicket's JDBC driver over `android.database.sqlite` |
| JVM tests | `SqliteDriver.fromJdbc(new org.sqlite.JDBC())`, xerial's `sqlite-jdbc` |

```scala
import thicket.sqlite.NativeSqlite.given            // or AndroidSqlite.given
val c = Sqlite.open(storage.dirs, "app.db")         // or Sqlite.memory()
val p = c.prepareStatement("insert into note(text) values (?)")
p.setString(1, "hello"); p.executeUpdate(); p.close()
```

- **The subset that works everywhere** is what `testing.JdbcConformance` checks, 17 checks
  in all: statements, prepared statements with typed parameters and `setNull`, typed getters
  by index and label, `wasNull`, generated keys, transactions, batches and `setFetchSize`.
  Scala Native's `java.sql` has no `getObject` and no metadata; the Android driver has
  `getObject` and `setObject`.
- **Known gaps on Native,** upstream in 0.0.7: batches and `setFetchSize` are unimplemented.
  `JdbcConformance.knownNativeFailures` lists the two checks it fails, and
  `NativeConformanceSpec` fails if that set changes. (0.0.6 also swallowed errors from a plain
  `executeUpdate` and a bad bind; 0.0.7 throws them, lolgab/scala-native-jdbc#17.)
- **A typed layer without a framework DSL: Magnum,** given `Sqlite.dataSource(dirs, name)`.
  - **On Native:** `com.github.lolgab::magnum` 1.3.1 works. Five of seven checks pass; the
    two failures are the driver's missing batches and `setFetchSize`.
  - **On Android:** the official `com.augustnagro::magnum` 1.3.1 does not run, because it
    calls `System.getLogger`, which Android lacks. With that one line made lazy, it passes
    all seven (`docs/16` §16.5).
- **Threads:** keep database work off the UI thread, and use a connection from one thread at
  a time. Android ties a transaction to the thread that began it.
- **Android driver stubs:** regenerate them with
  `modules/sqlite-android/gen-unsupported.py "$(cs java-home --jvm temurin:21)"`, then
  `sbt scalafmtAll`, since the checked-in file is the formatted one.
