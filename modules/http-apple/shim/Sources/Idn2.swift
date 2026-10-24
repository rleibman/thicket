// The three libidn2 functions sttp-model calls, for platforms that have no libidn2 (#90).
//
// sttp-model's Scala Native build turns every DNS host name into ASCII through libidn2
// (`sttp.model.internal.idn.CIdn`, `@link("idn2")`), on every request, ASCII names included.
// Neither the iOS SDK nor the macOS SDK ships libidn2, so an iOS app using sttp does not link
// without these: measured, `idn2_free`, `idn2_strerror` and `idn2_to_ascii_8z` undefined.
//
// What this does: an ASCII label is passed through unchanged; a label with anything else is
// NFC-normalised, lower-cased and Punycode-encoded (RFC 3492) behind "xn--". That is IDNA's
// ToASCII without UTS #46's full mapping table and without its validity checks — enough for
// real host names, and it never contacts anything.
//
// On macOS, Scala Native still passes -lidn2 (the annotation is sttp's), so a macOS link needs
// a libidn2 to be found; this copy then goes unused or wins, either way with the same answer
// for a valid name.

import Foundation

private let base = 36, tMin = 1, tMax = 26, skew = 38, damp = 700

private func adapt(_ delta0: Int, _ points: Int, _ first: Bool) -> Int {
  var delta = first ? delta0 / damp : delta0 / 2
  delta += delta / points
  var k = 0
  while delta > ((base - tMin) * tMax) / 2 {
    delta /= base - tMin
    k += base
  }
  return k + (base - tMin + 1) * delta / (delta + skew)
}

private func digit(_ d: Int) -> Character {
  Character(Unicode.Scalar(UInt8(d < 26 ? 97 + d : 22 + d)))
}

/// RFC 3492 §6.3, the encoder. nil on overflow, which no real label reaches.
func punycode(_ label: String) -> String? {
  let input = label.unicodeScalars.map { Int($0.value) }
  var output = String(label.unicodeScalars.filter { $0.isASCII }.map(Character.init))
  let basic = output.count
  var handled = basic
  if basic > 0 { output.append("-") }
  var n = 128, delta = 0, bias = 72
  while handled < input.count {
    guard let m = input.filter({ $0 >= n }).min() else { return nil }
    let (step, overflow) = (m - n).multipliedReportingOverflow(by: handled + 1)
    if overflow { return nil }
    delta += step
    n = m
    for c in input {
      if c < n { delta += 1 }
      if c == n {
        var q = delta
        var k = base
        while true {
          let t = k <= bias ? tMin : (k >= bias + tMax ? tMax : k - bias)
          if q < t { break }
          output.append(digit(t + (q - t) % (base - t)))
          q = (q - t) / (base - t)
          k += base
        }
        output.append(digit(q))
        bias = adapt(delta, handled + 1, handled == basic)
        delta = 0
        handled += 1
      }
    }
    delta += 1
    n += 1
  }
  return output
}

private func toAscii(_ host: String) -> String? {
  var labels: [String] = []
  for label in host.split(separator: ".", omittingEmptySubsequences: false) {
    if label.unicodeScalars.allSatisfy({ $0.isASCII }) {
      labels.append(String(label))
    } else {
      guard let encoded = punycode(String(label).precomposedStringWithCanonicalMapping.lowercased()) else {
        return nil
      }
      labels.append("xn--" + encoded)
    }
  }
  return labels.joined(separator: ".")
}

@_cdecl("idn2_to_ascii_8z")
public func idn2_to_ascii_8z(
  _ input: UnsafePointer<CChar>?, _ output: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?,
  _ flags: Int32
) -> Int32 {
  guard let input, let output else { return -1 }
  guard let ascii = toAscii(String(cString: input)) else { return -2 }
  output.pointee = strdup(ascii)
  return 0
}

private let messages: [Int32: UnsafeMutablePointer<CChar>] = [
  -1: strdup("idn2: no input or no output pointer")!,
  -2: strdup("idn2: the label could not be Punycode-encoded")!,
]
private let unknown = strdup("idn2: unknown error")!

@_cdecl("idn2_strerror")
public func idn2_strerror(_ rc: Int32) -> UnsafePointer<CChar> {
  UnsafePointer(messages[rc] ?? unknown)
}

@_cdecl("idn2_free")
public func idn2_free(_ ptr: UnsafeMutableRawPointer?) {
  free(ptr)
}
