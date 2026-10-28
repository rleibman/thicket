# http — the HTTP self-test

The same checks, `checks/` (`HttpChecks`), against a local server for every platform's sttp
backend: GET, a binary and a text POST, 404, repeated headers both ways, a redirect, a timeout,
a refused and a reset connection, cancellation by interrupt, the GC not waiting for a request,
and a caliban-client query. Each host adds what only its platform has.

| Host | Backend | Run |
|---|---|---|
| `linux/` | `LibcurlBackend` (`thicket-http-gtk`) | `sbt httpLinux/nativeLink && examples/http/selftest.sh linux` (CI) |
| `macos/` | `URLSessionBackend` (`thicket-http-apple`, #90) | `sbt httpMacos/nativeLink && examples/http/selftest.sh macos` |
| `ios/` | the same, on the simulator; `ios/ios-app/` is the Swift host | `sbt httpIos/nativeLink && examples/http/selftest.sh ios "iPhone 18 Pro"` |
| Android | `AndroidHttpBackend` (`thicket-http-android`) | in the gallery's self-test: `SELFTEST=1 examples/gallery/android/build.sh` |

`selftest.sh` starts `server.py` on `127.0.0.1` and a free port (the iOS simulator shares the
Mac's loopback, and the Android build `adb reverse`s it; nothing leaves the machine), passes the
port in `THICKET_HTTPTEST`, and exits 0 only on `ALL CHECKS PASSED`. `apple/` holds the Apple
hosts' own checks: the main thread refused, no request left in the Swift side. The GC check
uses a backend that waits for the whole response in one native call (`pollMs` 5000), or it
could not tell an Unmanaged wait from a short one.
