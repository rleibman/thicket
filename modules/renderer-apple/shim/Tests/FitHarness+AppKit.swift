import AppKit
import Foundation

// A measuring harness for ContentFit on AppKit (Forgejo #6).
//
// It is not a unit test of Swift code: it calls the shim's real `@_cdecl` entry points —
// sui_create, sui_set_image_file, sui_set_content_fit, sui_set_frame — compiled from the
// same source the app links, then renders the resulting view and counts pixels. A fit that
// distorts is invisible in a log and obvious in a bitmap, which is why this measures the
// drawn result rather than asserting on the property that was set.
//
// The test image is 400x100 — deliberately far from square — with a 50x50 white marker at
// its centre. The marker is what gets measured, because its aspect ratio in the output says
// exactly what the fit did:
//
//   Contain  image letterboxed to 200x50, marker scaled 0.5      -> 25x25   ratio 1.00
//   Cover    image scaled x2 and cropped, marker scaled 2.0      -> 100x100 ratio 1.00
//   Fill     axes scaled independently, x0.5 and x2.0            -> 25x100  ratio 0.25
//
// So Contain and Cover preserve the marker's aspect and Fill does not. A Cover that
// distorts is indistinguishable from Fill, which is precisely the defect.

private let side = 200.0

private func bitmap(_ w: Int, _ h: Int, _ draw: () -> Void) -> NSBitmapImageRep {
  let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
  )!
  NSGraphicsContext.saveGraphicsState()
  NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
  draw()
  NSGraphicsContext.restoreGraphicsState()
  return rep
}

/// The marker is a *circle*, so the same drawing serves both jobs: its bounding box gives
/// the aspect ratio the assertions use, and a circle drawn as an ellipse is the thing a
/// reader can see instantly in the screenshot. The gradient behind it makes cropping
/// visible too — Cover loses the outer thirds, Contain keeps them.
private func writeTestImage() -> String {
  let rep = bitmap(400, 100) {
    NSGradient(starting: NSColor.systemTeal, ending: NSColor.systemOrange)?
      .draw(in: NSRect(x: 0, y: 0, width: 400, height: 100), angle: 0)
    NSColor.white.setFill()
    // Centred, so it survives Cover's crop: at x2 the visible window is the middle 200px.
    NSBezierPath(ovalIn: NSRect(x: 175, y: 25, width: 50, height: 50)).fill()
  }
  let path = NSTemporaryDirectory() + "thicket-fit-source.png"
  try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
  return path
}

/// The three fits side by side, captioned, as one PNG for `docs/screenshots/`.
private func writeComposite(panels: [(String, String)], to outPath: String) {
  let pad = 20.0, caption = 28.0
  let w = Int(pad + (side + pad) * Double(panels.count))
  let h = Int(pad + side + caption + pad)
  let rep = bitmap(w, h) {
    NSColor.white.setFill()
    NSRect(x: 0, y: 0, width: Double(w), height: Double(h)).fill()
    for (i, panel) in panels.enumerated() {
      let x = pad + (side + pad) * Double(i)
      let frame = NSRect(x: x, y: pad + caption, width: side, height: side)
      if let img = NSImage(contentsOfFile: panel.1) { img.draw(in: frame) }
      NSColor.lightGray.setStroke()
      NSBezierPath(rect: frame).stroke()
      let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: 13),
        .foregroundColor: NSColor.black
      ]
      let label = NSAttributedString(string: panel.0, attributes: attrs)
      label.draw(at: NSPoint(x: x, y: pad / 2))
    }
  }
  try? rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: outPath))
}

/// The white marker's bounding box in a rendered view, as (width, height) in pixels.
private func markerSize(_ rep: NSBitmapImageRep) -> (Int, Int) {
  var minX = Int.max, maxX = Int.min, minY = Int.max, maxY = Int.min
  for y in 0..<rep.pixelsHigh {
    for x in 0..<rep.pixelsWide {
      guard let c = rep.colorAt(x: x, y: y) else { continue }
      // The marker is the only white thing; the image's own background is black and the
      // view's is transparent.
      if c.alphaComponent > 0.5, c.redComponent > 0.8, c.greenComponent > 0.8, c.blueComponent > 0.8 {
        minX = min(minX, x); maxX = max(maxX, x)
        minY = min(minY, y); maxY = max(maxY, y)
      }
    }
  }
  guard maxX >= minX, maxY >= minY else { return (0, 0) }
  return (maxX - minX + 1, maxY - minY + 1)
}

private func render(fit: Int32, imagePath: String, to outPath: String) -> (Int, Int) {
  let h = sui_create(8)
  imagePath.withCString { sui_set_image_file(h, $0) }
  sui_set_content_fit(h, fit)
  sui_set_frame(h, 0, 0, side, side)

  let v = Unmanaged<NSView>.fromOpaque(h).takeUnretainedValue()
  v.layoutSubtreeIfNeeded()
  let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds)!
  v.cacheDisplay(in: v.bounds, to: rep)
  try? rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: outPath))
  return markerSize(rep)
}

@main
struct FitHarness {
  static func main() {
    let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : NSTemporaryDirectory()
    let src = writeTestImage()

    let cases: [(String, Int32, Double)] = [
      ("contain", 0, 1.0),
      ("cover", 1, 1.0),
      ("fill", 2, 0.25)
    ]

    var failures = 0
    var panels: [(String, String)] = []
    print("[fit] source image 400x100, 50x50 centred marker, rendered into \(Int(side))x\(Int(side))")
    for (name, code, expected) in cases {
      let out = "\(outDir)/fit-\(name).png"
      let (w, h) = render(fit: code, imagePath: src, to: out)
      panels.append(("ContentFit.\(name.capitalized)  marker \(w)x\(h)", out))
      guard w > 0, h > 0 else {
        print("[fit] FAIL \(name): marker not found in the rendered view")
        failures += 1
        continue
      }
      let ratio = Double(w) / Double(h)
      // A pixel of slop: the marker's edges land on fractional device pixels.
      let ok = abs(ratio - expected) < 0.06
      print(
        "[fit] \(ok ? "ok  " : "FAIL") \(name): marker \(w)x\(h), aspect \(String(format: "%.2f", ratio))"
          + " (expected \(String(format: "%.2f", expected)))"
      )
      if !ok { failures += 1 }
    }

    let composite = "\(outDir)/content-fit-appkit.png"
    writeComposite(panels: panels, to: composite)
    print("[fit] wrote \(composite)")

    // The property that names the defect, stated directly: Cover must not look like Fill.
    print(failures == 0 ? "[fit] ALL CHECKS PASSED" : "[fit] \(failures) CHECK(S) FAILED")
    exit(failures == 0 ? 0 : 1)
  }
}
