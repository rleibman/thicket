# thicket-http-android

sttp's `HttpURLConnectionBackend` — Android's own HTTP stack, so nothing added to the APK —
with the two things it lacks on sttp 4.1.0: `AndroidHttpBackend()`. Why, and the numbers, are
in `docs/16-device-services.md` §16.4.

```scala
// On a worker thread: it blocks. From ZIO, ZIO.attemptBlockingInterrupt.
val backend: SyncBackend = AndroidHttpBackend()
basicRequest.get(uri"https://example.com").send(backend)
```

- **A header given twice is sent twice.** sttp sets each with `setRequestProperty`, which
  replaces; the repeats are added back with `addRequestProperty`.
- **An interrupt cancels.** The exchange runs on a worker; the calling thread waits,
  interruptibly, and when interrupted throws `InterruptedException` at once and disconnects the
  connection, which on Android closes the socket.
- **Not on the main thread:** there it throws Android's `NetworkOnMainThreadException`, as
  `HttpURLConnection` would, before anything starts.
- **The app needs** the `INTERNET` permission; for plain `http://`, a network security config
  allowing cleartext to that host; and with R8, `-dontwarn java.net.http.HttpTimeoutException`
  (sttp's exception mapping names it; Android lacks it, and the mapping works without it).
- **Plain JVM**, no Android API at compile time, so its 5 tests run on the desktop JDK. There
  `disconnect` waits for a read in progress, so only the caller's side of a cancellation is
  checked; the Android gallery's self-test checks the server sees the hang-up.
