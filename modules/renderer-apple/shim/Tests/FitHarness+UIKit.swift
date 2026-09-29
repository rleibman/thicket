import Foundation
import UIKit

// The UIKit half of the ContentFit harness (Forgejo #6), mirroring FitHarness+AppKit.swift.
//
// UIKit is expected to be correct already — `.scaleAspectFill` is exactly Cover — but the
// issue asks for that to be confirmed rather than assumed, so it is measured the same way:
// drive the real `@_cdecl` entry points, render the view, count pixels.
//
// Runs headless in the simulator via `simctl spawn`, so it needs no app bundle and no
// UIScene. `layer.render(in:)` is used rather than `drawHierarchy`, which would require a
// window; it also honours `masksToBounds`, and that matters here — without clipping,
// scaleAspectFill overflows its frame instead of cropping, which is a different bug with
// the same aspect ratio.

private let side = 200.0

private func writeTestImage() -> String {
  let format = UIGraphicsImageRendererFormat()
  format.scale = 1
  let img = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 100), format: format).image { ctx in
    UIColor.black.setFill()
    ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 100))
    UIColor.white.setFill()
    // Centred, so it survives Cover's crop.
    ctx.fill(CGRect(x: 175, y: 25, width: 50, height: 50))
  }
  let path = NSTemporaryDirectory() + "scalaui-fit-source.png"
  try! img.pngData()!.write(to: URL(fileURLWithPath: path))
  return path
}

/// The white marker's bounding box in a rendered view, as (width, height) in pixels.
private func markerSize(_ image: UIImage, writeTo outPath: String) -> (Int, Int) {
  try? image.pngData()?.write(to: URL(fileURLWithPath: outPath))
  guard let cg = image.cgImage else { return (0, 0) }
  let w = cg.width, h = cg.height
  var bytes = [UInt8](repeating: 0, count: w * h * 4)
  guard let ctx = CGContext(
    data: &bytes, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
    space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
  ) else { return (0, 0) }
  ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))

  var minX = Int.max, maxX = Int.min, minY = Int.max, maxY = Int.min
  for y in 0..<h {
    for x in 0..<w {
      let i = (y * w + x) * 4
      if bytes[i + 3] > 128, bytes[i] > 200, bytes[i + 1] > 200, bytes[i + 2] > 200 {
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

  let v = Unmanaged<UIView>.fromOpaque(h).takeUnretainedValue()
  v.layoutIfNeeded()
  let format = UIGraphicsImageRendererFormat()
  format.scale = 1
  let shot = UIGraphicsImageRenderer(bounds: v.bounds, format: format).image { ctx in
    v.layer.render(in: ctx.cgContext)
  }
  return markerSize(shot, writeTo: outPath)
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
    print("[fit] source image 400x100, 50x50 centred marker, rendered into \(Int(side))x\(Int(side))")
    for (name, code, expected) in cases {
      let out = "\(outDir)/fit-ios-\(name).png"
      let (w, h) = render(fit: code, imagePath: src, to: out)
      guard w > 0, h > 0 else {
        print("[fit] FAIL \(name): marker not found in the rendered view")
        failures += 1
        continue
      }
      let ratio = Double(w) / Double(h)
      let ok = abs(ratio - expected) < 0.06
      print(
        "[fit] \(ok ? "ok  " : "FAIL") \(name): marker \(w)x\(h), aspect \(String(format: "%.2f", ratio))"
          + " (expected \(String(format: "%.2f", expected)))"
      )
      if !ok { failures += 1 }
    }

    print(failures == 0 ? "[fit] ALL CHECKS PASSED" : "[fit] \(failures) CHECK(S) FAILED")
    exit(failures == 0 ? 0 : 1)
  }
}
