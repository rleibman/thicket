# thicket-http-gtk

An sttp `SyncBackend` over libcurl, for GTK apps: `LibcurlBackend()`, the same type as sttp's
`CurlBackend()`, so an app or caliban-client changes nothing else. Why it exists, and the
numbers, are in `docs/16-device-services.md` §16.4.

```scala
// On a worker thread: it blocks. From ZIO, ZIO.attemptBlockingInterrupt.
val backend: SyncBackend = LibcurlBackend()
basicRequest.get(uri"https://example.com").send(backend)
```

- **No `-dev` package.** libcurl is loaded with `dlopen` (`libcurl.so.4`). Without it,
  `LibcurlBackend()` throws `IllegalStateException` saying so. `curl_easy_setopt` and
  `curl_easy_getinfo` are variadic, so they are called through `thicket_curl.c`, which the jar
  carries and Scala Native compiles; it needs no curl header. sttp-model still links libidn2
  for host names, so building an app needs `libidn2-dev`.
- **The GC does not wait for the network.** curl writes the response into `open_memstream`
  buffers, so no Scala runs inside curl, and its waits run with the thread Unmanaged.
- **An interrupt cancels.** The thread waits in 50 ms slices (`pollMs`) and checks for one;
  the transfer is dropped, its connection closed, and `InterruptedException` thrown.
- **Errors are sttp's:** `ConnectException`, `TimeoutException` (`readTimeout`, for the whole
  exchange, as `URLSessionBackend`), `ReadException`.
- **Limits:** no connection reuse between requests (a multi handle each, so threads never
  share one); the body is always decompressed by curl; no multipart, streaming or WebSocket;
  no cookie jar or cache. Redirects are followed by sttp.
- **Checked by** `examples/http`'s 12 checks (`examples/http/selftest.sh linux`, in CI) and 5
  unit tests here.
