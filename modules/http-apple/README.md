# thicket-http-apple

HTTP for Apple platforms: an sttp client4 `SyncBackend` over `URLSession` (#90,
[docs/16](../../docs/16-device-services.md) §16.4).

sttp's only Scala Native backend is `CurlBackend`, and **the iOS SDK has no libcurl** — no
header, no `.tbd` stub, not even a private copy in the simulator runtime (measured
2026-10-22, Xcode 27.0). Without this module caliban-client, which sends through sttp, has no
transport on iOS.

```scala
libraryDependencies += "net.leibman" % "thicket-http-apple_native0.5_3" % thicketVersion

import sttp.client4.*
import thicket.http.apple.URLSessionBackend

val backend: SyncBackend = URLSessionBackend()      // same type as CurlBackend()
basicRequest.get(uri"https://example.com/").send(backend)
myCalibanQuery.toRequest(uri"https://example.com/graphql").send(backend)
```

It is its own module, not part of `renderer-apple`: an app that does not depend on it links
none of it, neither the Scala nor `libthickethttp.a`. It needs no renderer either — the
self-test app has none.

## Rules for callers

- **Never on the UI thread.** `send` blocks until the response is in. Under a self-test
  (`THICKET_SELFTEST`, `THICKET_GALLERY_SELFTEST`, `THICKET_LAZYTEST`, `THICKET_HTTPTEST`) a
  call from the main thread throws `IllegalStateException`; otherwise it prints one warning
  and carries on.
- **From ZIO, use `ZIO.attemptBlockingInterrupt`.** It runs on the blocking pool, and
  interrupting the fiber interrupts the thread, which cancels the `URLSession` task within
  one poll (`pollMs`, 50 by default). Plain `attemptBlocking` cannot be interrupted.
- **Timeouts:** the request's `readTimeout` bounds the whole exchange, as with `CurlBackend`;
  past it the task is cancelled and sttp's `TimeoutException` is thrown.
- **Errors are sttp's:** not sent (refused, no such host, TLS, App Transport Security) →
  `SttpClientException.ConnectException`; timed out → `TimeoutException`; anything after the
  request went out → `ReadException`.
- **App Transport Security:** plain `http://` to anything but loopback (`127.0.0.1`,
  `localhost`) needs an `NSAppTransportSecurity` exception in the app's `Info.plist`; without
  one the request fails with a `ConnectException`. The self-test reaches `127.0.0.1` and
  `localhost` with no exception in its `Info.plist`.

## Differences from `CurlBackend`

All of them come from `URLSession`:

- the body is always decompressed (gzip, deflate, br), whatever `decompressResponseBody` says;
- Foundation joins a repeated **response** header into one `", "`-separated value (RFC 9110
  §5.3 allows it); `Set-Cookie` is split back into one header per cookie, so
  `response.unsafeCookies` sees every cookie. A repeated **request** header is sent as one
  comma-joined line;
- multipart and streaming request bodies, and streaming or WebSocket responses, are not
  supported (they throw `UnsupportedOperationException`);
- no cookie jar and no cache, as with curl; redirects come back as 3xx and sttp's
  `FollowRedirectsBackend` follows them, as `CurlBackend()` does.

## libidn2

sttp-model's Native build passes **every** host that is not an IPv6 literal — `127.0.0.1` and
`localhost` included — through libidn2's `idn2_to_ascii_8z` (`@link("idn2")`). Neither the
iOS SDK nor the macOS SDK has libidn2, so the Swift library also defines the three functions
sttp calls (`Idn2.swift`): ASCII labels unchanged, others NFC-normalised, lower-cased and
Punycode-encoded behind `xn--`. On iOS this is what makes the app link at all (without it:
`idn2_free`, `idn2_strerror`, `idn2_to_ascii_8z` undefined). On macOS Scala Native still
passes `-lidn2`, so a macOS link needs a libidn2 on the search path: here the self-test
binary picked up Homebrew's (`otool -L` shows `/opt/homebrew/opt/libidn2/lib/libidn2.0.dylib`),
and would not start on a Mac without it. That is true of `CurlBackend` on macOS too.

## How it works

```
Scala (any thread but main)                 Swift (HttpShim.swift)          URLSession queue
send ──── thk_http_send(method, url, …) ──▶  start a URLSessionDataTask ──▶  …network…
     ◀─── id                                                                completion: store
loop: thk_http_wait(id, pollMs)  @blocking   NSCondition wait          ◀──  status, headers,
      Thread.interrupted? → thk_http_cancel                                  body; broadcast
      past readTimeout?   → thk_http_cancel
read  thk_http_status / headers / body / error
free  thk_http_free(id)
```

Nothing calls back into Scala: `URLSession` completes on a thread the GC does not know.
`thk_http_wait` is `@blocking`, which marks the thread Unmanaged for the call, so a
collection started elsewhere does not wait for the network — measured: `System.gc()` takes
1–2 ms while a request is parked, and **1 711 ms** with `@blocking` removed.

The C ABI is described in `tools/shim-gen` (`HttpAbi.scala`); `shim/include/thicket_http.h`
and `HttpShim.scala` are generated from it (`sbt shimGen/run`) and checked byte for byte, and
`HttpConsistencySpec` checks the Swift `@_cdecl` signatures against it on any machine.

## Building

| | |
|---|---|
| The Swift library | `shim/build-shim.sh macos` / `ios-sim` → `shim/build*/libthickethttp.a` (sbt runs it when packaging) |
| macOS app in this repo | `nativeConfig ~= ThicketNativeFlags.appleHttp(<dir of libthickethttp.a>)` |
| iOS app in this repo | the Swift host's link takes `shim/build-ios/libthickethttp.a` |
| An app outside the repo | `ThicketMacPlugin` / `ThicketIosPlugin` unpack the library from this module's jar when it is a dependency (`print thicketHttpApple`); the macOS plugin links it |

## Tests

`examples/http-apple/selftest.sh macos` and `examples/http-apple/selftest.sh ios "<device>"`
run 13 checks against a local server on `127.0.0.1` (`examples/http-apple/server.py`):
main-thread refusal, GET, IDN host names, POST (text and binary), 404, repeated headers,
redirect, timeout, connect refused, cancellation by interrupt, the GC check above, one
caliban-client query, and no request left behind. Numbers in the decision log, 2026-10-22.
