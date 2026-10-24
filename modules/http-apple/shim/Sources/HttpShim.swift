// thicket-http-apple: HTTP over URLSession, for sttp's SyncBackend on iOS and macOS (#90).
//
// The iOS SDK has no libcurl — no header, no .tbd, not in the simulator runtime (measured,
// docs/decisions.md 2026-10-22) — so sttp's only Scala Native backend, CurlBackend, cannot
// link there. This is the transport that replaces it: Foundation only, so the one file
// compiles for macOS and for iOS unchanged.
//
// PULL, NEVER PUSH. URLSession completes on its own delegate queue, a thread Scala Native's
// GC does not know about, so nothing here ever calls into Scala. Scala starts a request with
// thk_http_send, blocks in thk_http_wait (marked @blocking on the Scala side, so the GC does
// not wait for it), and reads the results out. A request is an Int64 id into `requests`, not
// a pointer, so a stale id is an empty answer rather than a dereference.
//
// The declarations are checked against tools/shim-gen's HttpAbi by HttpConsistencySpec.

import Foundation

/// One request, and once it has finished, what came back. The result fields are written once,
/// before `finished` is set under `done`'s lock, and read only after a wait has seen it set.
private final class Exchange {
  let done = NSCondition()
  var finished = false
  var task: URLSessionDataTask?
  var status: Int32 = 0
  var headers: UnsafeMutablePointer<CChar>? = strdup("")
  var body: UnsafeMutablePointer<UInt8>?
  var bodyLength: Int64 = 0
  var error: Int32 = 0
  var message: UnsafeMutablePointer<CChar>? = strdup("")

  deinit {
    free(headers)
    free(body)
    free(message)
  }

  func finish(status: Int32, headers: String, body: Data?, error: Int32, message: String) {
    self.status = status
    free(self.headers)
    self.headers = strdup(headers)
    if let body, !body.isEmpty {
      let p = UnsafeMutablePointer<UInt8>.allocate(capacity: body.count)
      body.copyBytes(to: p, count: body.count)
      self.body = p
      self.bodyLength = Int64(body.count)
    }
    self.error = error
    free(self.message)
    self.message = strdup(message)
    done.lock()
    finished = true
    done.broadcast()
    done.unlock()
  }
}

private let lock = NSLock()
nonisolated(unsafe) private var requests: [Int64: Exchange] = [:]
nonisolated(unsafe) private var nextId: Int64 = 1

private func lookup(_ id: Int64) -> Exchange? {
  lock.lock()
  defer { lock.unlock() }
  return requests[id]
}

/// Redirects come back to sttp as 3xx responses, as they do from CurlBackend: sttp's
/// FollowRedirectsBackend follows them and keeps the history, which URLSession would not.
private final class SessionDelegate: NSObject, URLSessionTaskDelegate {
  func urlSession(
    _ session: URLSession, task: URLSessionTask,
    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
    completionHandler: @escaping (URLRequest?) -> Void
  ) {
    completionHandler(nil)
  }
}

/// No cookie jar and no cache: what goes out is what sttp put in, and every `Set-Cookie` comes
/// back to the caller, as with curl. URLSession still decodes gzip/deflate/br bodies itself.
private let session: URLSession = {
  let c = URLSessionConfiguration.ephemeral
  c.httpCookieStorage = nil
  c.httpShouldSetCookies = false
  c.httpCookieAcceptPolicy = .never
  c.urlCache = nil
  c.requestCachePolicy = .reloadIgnoringLocalCacheData
  c.waitsForConnectivity = false
  return URLSession(configuration: c, delegate: SessionDelegate(), delegateQueue: nil)
}()

/// Error codes, as HttpAbi documents them.
private enum Failure: Int32 {
  case connect = 1, read = 2, timeout = 3, cancelled = 4, badRequest = 5
}

private func classify(_ error: Error) -> Failure {
  guard let e = error as? URLError else { return .read }
  switch e.code {
  case .timedOut:
    return .timeout
  case .cancelled:
    return .cancelled
  case .badURL, .unsupportedURL:
    return .badRequest
  // The request was not sent: sttp's ConnectException. App Transport Security's refusal of
  // plain http:// to a host without an Info.plist exception is one of these.
  case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed, .notConnectedToInternet,
    .secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
    .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid, .clientCertificateRejected,
    .clientCertificateRequired, .appTransportSecurityRequiresSecureConnection,
    .internationalRoamingOff, .dataNotAllowed, .callIsActive, .cannotLoadFromNetwork:
    return .connect
  default:
    return .read
  }
}

/// Foundation joins a repeated response header into one value with ", " — fine for every header
/// RFC 9110 lets a recipient combine, and wrong for Set-Cookie, whose Expires date has a comma
/// of its own. A comma starts a new cookie only when what follows it is `token=`; in
/// "Expires=Wed, 21 Oct 2015" what follows is "21 Oct", which is not.
func splitSetCookie(_ joined: String) -> [String] {
  let chars = Array(joined)
  let tokenChars = Set("!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
  var out: [String] = []
  var start = 0
  var i = 0
  while i < chars.count {
    if chars[i] == "," {
      var j = i + 1
      while j < chars.count && chars[j] == " " { j += 1 }
      var k = j
      while k < chars.count && tokenChars.contains(chars[k]) { k += 1 }
      if k > j && k < chars.count && chars[k] == "=" {
        out.append(String(chars[start..<i]).trimmingCharacters(in: .whitespaces))
        start = j
        i = j
        continue
      }
    }
    i += 1
  }
  out.append(String(chars[start...]).trimmingCharacters(in: .whitespaces))
  return out.filter { !$0.isEmpty }
}

private func headerLines(_ response: HTTPURLResponse) -> String {
  var lines: [String] = []
  for (key, value) in response.allHeaderFields {
    let name = "\(key)"
    let v = "\(value)"
    if name.caseInsensitiveCompare("Set-Cookie") == .orderedSame {
      for cookie in splitSetCookie(v) { lines.append("\(name): \(cookie)") }
    } else {
      lines.append("\(name): \(v)")
    }
  }
  return lines.joined(separator: "\n")
}

@_cdecl("thk_http_send")
public func thk_http_send(_ method: UnsafePointer<CChar>, _ url: UnsafePointer<CChar>, _ headers: UnsafePointer<CChar>, _ body: UnsafePointer<UInt8>?, _ bodyLength: Int64, _ timeoutMs: Int32) -> Int64 {
  let ex = Exchange()
  lock.lock()
  let id = nextId
  nextId += 1
  requests[id] = ex
  lock.unlock()

  let urlString = String(cString: url)
  guard let target = URL(string: urlString), target.scheme != nil else {
    ex.finish(status: 0, headers: "", body: nil, error: Failure.badRequest.rawValue, message: "malformed URL: \(urlString)")
    return id
  }
  var request = URLRequest(url: target)
  request.httpMethod = String(cString: method)
  if timeoutMs > 0 { request.timeoutInterval = Double(timeoutMs) / 1000 }
  for line in String(cString: headers).split(separator: "\n", omittingEmptySubsequences: true) {
    guard let colon = line.firstIndex(of: ":") else { continue }
    let name = line[..<colon].trimmingCharacters(in: .whitespaces)
    let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
    // addValue, not setValue: a repeated request header is kept (URLRequest joins it with ",").
    request.addValue(value, forHTTPHeaderField: name)
  }
  if let body, bodyLength > 0 { request.httpBody = Data(bytes: body, count: Int(bodyLength)) }

  let task = session.dataTask(with: request) { data, response, error in
    if let error {
      ex.finish(status: 0, headers: "", body: nil, error: classify(error).rawValue, message: error.localizedDescription)
    } else if let http = response as? HTTPURLResponse {
      ex.finish(status: Int32(http.statusCode), headers: headerLines(http), body: data, error: 0, message: "")
    } else {
      ex.finish(status: 0, headers: "", body: nil, error: Failure.read.rawValue, message: "not an HTTP response")
    }
  }
  ex.task = task
  task.resume()
  return id
}

@_cdecl("thk_http_wait")
public func thk_http_wait(_ request: Int64, _ waitMs: Int32) -> Int32 {
  guard let ex = lookup(request) else { return -1 }
  let deadline = Date(timeIntervalSinceNow: Double(max(waitMs, 0)) / 1000)
  ex.done.lock()
  defer { ex.done.unlock() }
  while !ex.finished {
    if !ex.done.wait(until: deadline) { break }
  }
  return ex.finished ? 1 : 0
}

@_cdecl("thk_http_cancel")
public func thk_http_cancel(_ request: Int64) {
  lookup(request)?.task?.cancel()
}

@_cdecl("thk_http_free")
public func thk_http_free(_ request: Int64) {
  lock.lock()
  let ex = requests.removeValue(forKey: request)
  lock.unlock()
  // Freeing one still in flight stops it; its completion handler keeps the Exchange alive
  // until it has run.
  ex?.task?.cancel()
}

@_cdecl("thk_http_status")
public func thk_http_status(_ request: Int64) -> Int32 {
  lookup(request)?.status ?? 0
}

@_cdecl("thk_http_headers")
public func thk_http_headers(_ request: Int64) -> UnsafePointer<CChar>? {
  lookup(request)?.headers.map { UnsafePointer($0) }
}

@_cdecl("thk_http_body_length")
public func thk_http_body_length(_ request: Int64) -> Int64 {
  lookup(request)?.bodyLength ?? 0
}

@_cdecl("thk_http_body")
public func thk_http_body(_ request: Int64) -> UnsafePointer<UInt8>? {
  lookup(request)?.body.map { UnsafePointer($0) }
}

@_cdecl("thk_http_error")
public func thk_http_error(_ request: Int64) -> Int32 {
  lookup(request)?.error ?? 0
}

@_cdecl("thk_http_error_message")
public func thk_http_error_message(_ request: Int64) -> UnsafePointer<CChar>? {
  lookup(request)?.message.map { UnsafePointer($0) }
}

@_cdecl("thk_http_on_main_thread")
public func thk_http_on_main_thread() -> Int32 {
  Thread.isMainThread ? 1 : 0
}

@_cdecl("thk_http_live")
public func thk_http_live() -> Int32 {
  lock.lock()
  defer { lock.unlock() }
  return Int32(requests.count)
}
