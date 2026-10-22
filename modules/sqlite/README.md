# thicket-sqlite

SQLite through plain JDBC: `java.sql` is the API, the same on every platform, and Thicket
adds no query layer (`docs/16-device-services.md` §16.5).

| Platform | Driver |
|---|---|
| GTK (and Apple, #98) | `NativeSqlite.driver`, over `com.github.lolgab::scala-native-jdbc-sqlite` 0.0.6 |
| Android | `AndroidSqlite.driver`, in `thicket-sqlite-android`: Thicket's JDBC driver over `android.database.sqlite` |
| JVM tests | `SqliteDriver.fromJdbc(new org.sqlite.JDBC())`, xerial's `sqlite-jdbc` |

```scala
import thicket.sqlite.NativeSqlite.given            // or AndroidSqlite.given
val c = Sqlite.open(storage.dirs, "app.db")         // or Sqlite.memory()
val p = c.prepareStatement("insert into note(text) values (?)")
p.setString(1, "hello"); p.executeUpdate(); p.close()
```

- **The subset that works everywhere** is what `testing.JdbcConformance` checks, 15 checks
  in all: statements, prepared statements with typed parameters and `setNull`, typed getters
  by index and label, `wasNull`, generated keys, and transactions. Scala Native's `java.sql`
  has no `getObject` and no metadata.
- **Known defect on Native,** upstream in 0.0.6: errors from a plain `Statement.executeUpdate`
  and from a bind to a parameter index that does not exist are not thrown.
  `JdbcConformance.knownNativeFailures` lists the three checks it fails, and
  `NativeConformanceSpec` fails if that set changes. Until it is fixed, prefer prepared
  statements.
- **A typed layer without a framework DSL:** Magnum has the same API on both runtimes. On
  Native it is `com.github.lolgab::magnum` 1.3.1, measured working with this driver
  (`docs/16` §16.5); on the JVM it is the official `com.augustnagro::magnum` 1.3.1, not yet
  measured on Android.
- **Threads:** keep database work off the UI thread, and use a connection from one thread at
  a time. Android ties a transaction to the thread that began it.
- **Android driver stubs:** regenerate them with
  `modules/sqlite-android/gen-unsupported.py "$(cs java-home --jvm temurin:21)"`, then
  `sbt scalafmtAll`, since the checked-in file is the formatted one.
