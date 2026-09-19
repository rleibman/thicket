import SwiftUI

/// A C function pointer cannot capture Swift context, so the callback target has to be a
/// free function plus global state. S3 replaces this with a handle table; S1 only needs
/// to show that Scala can reach back into the host at all.
private var callbackLog: [String] = []
private var callbackHits = 0

private func onScalaFire(_ msg: UnsafeMutablePointer<CChar>?) {
  callbackHits += 1
  callbackLog.append(msg.map { String(cString: $0) } ?? "<null>")
}

struct ContentView: View {
  @State private var lines: [String] = ["Tap a button to call into Scala Native."]
  @State private var busy = false

  private func log(_ s: String) { lines.append(s) }

  private func timed(_ label: String, _ body: () -> String) {
    let t0 = CFAbsoluteTimeGetCurrent()
    let result = body()
    let ms = (CFAbsoluteTimeGetCurrent() - t0) * 1000
    log(String(format: "%@ → %@  (%.3f ms)", label, result, ms))
  }

  var body: some View {
    VStack(spacing: 12) {
      Text("S1 — Scala Native on iOS").font(.headline)

      ScrollView {
        VStack(alignment: .leading, spacing: 4) {
          ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
            Text(line).font(.system(.caption, design: .monospaced))
              .frame(maxWidth: .infinity, alignment: .leading)
          }
        }
        .padding(8)
      }
      .frame(maxHeight: .infinity)
      .background(Color(white: 0.95))

      HStack {
        Button("hello") {
          timed("scalaui_hello") { String(cString: scalaui_hello()) }
        }
        Button("time") {
          timed("scalaui_time") { String(cString: scalaui_time()) }
        }
        Button("threads(8)") {
          timed("scalaui_thread_test(8)") { "\(scalaui_thread_test(8)) (expect 1399990)" }
        }
      }
      HStack {
        Button("fire callback") {
          scalaui_register_callback(onScalaFire)
          scalaui_fire()
          log("callback hits=\(callbackHits) last=\(callbackLog.last ?? "-")")
        }
        // Main thread on purpose — it blocks the UI for the duration, but a GCD queue
        // would crash in the GC allocator (see selfTest).
        Button("stress 3s") {
          timed("scalaui_alloc_stress(3)") { "\(scalaui_alloc_stress(3)) objects" }
        }
      }
      .padding(.bottom, 8)
    }
    .padding()
    .onAppear(perform: selfTest)
  }

  /// simctl cannot tap buttons, so the app proves itself: every export is exercised on
  /// appear and the results are rendered, which makes a screenshot the evidence.
  private func selfTest() {
    lines = ["self-test on launch:"]
    timed("scalaui_hello") { String(cString: scalaui_hello()) }
    timed("scalaui_time") { String(cString: scalaui_time()) }
    let checksum = scalaui_thread_test(8)
    log("scalaui_thread_test(8) → \(checksum) \(checksum == 1399990 ? "OK" : "MISMATCH")")
    scalaui_register_callback(onScalaFire)
    scalaui_fire()
    log("callback → hits=\(callbackHits) payload=\(callbackLog.last ?? "-")")

    // Deliberately on the main thread. Running this on DispatchQueue.global() segfaults
    // in Allocator_Alloc: Scala Native's GC only knows threads it created itself, and
    // 0.5.12 has no API to attach an existing foreign thread. See REPORT.md.
    timed("scalaui_alloc_stress(3) [main thread]") { "\(scalaui_alloc_stress(3)) objects" }
    log("post-stress hello → \(String(cString: scalaui_hello()))")
    log("post-stress threads(8) → \(scalaui_thread_test(8))")
    log("--- all exports OK; runtime alive after GC churn ---")
  }
}

@main
struct S1App: App {
  init() {
    // Before any exported function, and on the main thread.
    ScalaNativeInit()
  }
  var body: some Scene {
    WindowGroup { ContentView() }
  }
}
