# http-apple — the HTTP self-test

`thicket-http-apple`'s `URLSessionBackend`, and caliban-client over it, against a local
server, on macOS and on the iOS simulator (#90). No renderer and no UI beyond a label: the
module does not need one.

```bash
sbt httpMacos/nativeLink && examples/http-apple/selftest.sh macos
sbt httpIos/nativeLink   && examples/http-apple/selftest.sh ios "iPhone 18 Pro"
```

`selftest.sh` starts `server.py` on `127.0.0.1` and a free port (the simulator shares the
Mac's loopback; nothing leaves the machine), passes the port in `THICKET_HTTPTEST`, and exits 0
only on `ALL CHECKS PASSED`. The checks are in `shared/.../HttpSelfTest.scala`; what they
cover and why is in `modules/http-apple/README.md`.

| | |
|---|---|
| `shared/` | the checks, compiled into both hosts |
| `macos/` | a plain binary: the main thread checks the refusal, a worker runs the rest |
| `ios/` | the Scala half as a static library, `thicket_http_start` its entry |
| `ios/ios-app/` | the Swift host (`@main`, a `UIScene`) and `build-app.sh`, which links it with `libthickethttp.a` and runs it |
| `server.py` | the local server; its endpoints are listed at the top |
