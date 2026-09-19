import Foundation
import UIKit

// The whole shim. Every function is @_cdecl, so it is reachable from Scala Native as a
// plain C symbol with no Objective-C bridging header on the Scala side.
//
// Handle convention: Unmanaged<UIView>.passRetained(...).toOpaque(). The shim owns one
// +1 retain per handle and drops it in sui_destroy, so a view stays alive as long as
// Scala holds its handle even if it is not in the view hierarchy.

private func view(_ h: UnsafeMutableRawPointer) -> UIView {
  Unmanaged<UIView>.fromOpaque(h).takeUnretainedValue()
}

private func retainedHandle(_ v: UIView) -> UnsafeMutableRawPointer {
  liveHandles += 1
  return Unmanaged.passRetained(v).toOpaque()
}

private var liveHandles: Int32 = 0

/// Tap callbacks, keyed by the button's ObjectIdentifier. Holding the C function pointer
/// and the Scala-side context id together is all the state a tap needs.
private var tapTargets: [ObjectIdentifier: (cb: sui_tap_cb, ctx: Int64)] = [:]

/// UIControl needs an ObjC selector target, and a Swift closure cannot be one. This is the
/// Swift-side mirror of the same constraint Scala hits with CFuncPtr.
private final class TapProxy: NSObject {
  @objc func fire(_ sender: UIButton) {
    guard let t = tapTargets[ObjectIdentifier(sender)] else { return }
    t.cb(t.ctx)
  }
}
private let tapProxy = TapProxy()

/// Set by the host app before it calls scalaui_main.
private var rootView: UIView?

@_cdecl("sui_set_root_view")
public func sui_set_root_view(_ h: UnsafeMutableRawPointer) {
  rootView = view(h)
}

// MARK: - lifecycle

@_cdecl("sui_root_view")
public func sui_root_view() -> UnsafeMutableRawPointer? {
  guard let r = rootView else { return nil }
  return Unmanaged.passUnretained(r).toOpaque()
}

@_cdecl("sui_view_new")
public func sui_view_new() -> UnsafeMutableRawPointer {
  retainedHandle(UIView())
}

@_cdecl("sui_label_new")
public func sui_label_new() -> UnsafeMutableRawPointer {
  let l = UILabel()
  l.numberOfLines = 0
  l.font = .monospacedSystemFont(ofSize: 13, weight: .regular)
  return retainedHandle(l)
}

@_cdecl("sui_button_new")
public func sui_button_new() -> UnsafeMutableRawPointer {
  let b = UIButton(type: .system)
  b.addTarget(tapProxy, action: #selector(TapProxy.fire(_:)), for: .touchUpInside)
  return retainedHandle(b)
}

@_cdecl("sui_destroy")
public func sui_destroy(_ h: UnsafeMutableRawPointer) {
  let v = view(h)
  tapTargets.removeValue(forKey: ObjectIdentifier(v))
  liveHandles -= 1
  Unmanaged<UIView>.fromOpaque(h).release()
}

// MARK: - properties

// Scala hands over a NUL-terminated UTF-8 buffer it owns; String(cString:) copies, so the
// shim never retains Scala memory. That copy is the bulk of the per-call cost measured in
// the benchmark.
@_cdecl("sui_label_set_text")
public func sui_label_set_text(_ h: UnsafeMutableRawPointer, _ text: UnsafePointer<CChar>) {
  (view(h) as? UILabel)?.text = String(cString: text)
}

@_cdecl("sui_label_get_text")
public func sui_label_get_text(_ h: UnsafeMutableRawPointer) -> UnsafePointer<CChar>? {
  guard let t = (view(h) as? UILabel)?.text else { return nil }
  // Deliberately leaked one-shot copy: only the diagnostics path calls this, and giving
  // Scala a pointer into a Swift String's storage would be a use-after-free.
  return UnsafePointer(strdup(t))
}

@_cdecl("sui_button_set_title")
public func sui_button_set_title(_ h: UnsafeMutableRawPointer, _ title: UnsafePointer<CChar>) {
  (view(h) as? UIButton)?.setTitle(String(cString: title), for: .normal)
}

@_cdecl("sui_button_on_tap")
public func sui_button_on_tap(_ h: UnsafeMutableRawPointer, _ cb: @escaping sui_tap_cb, _ ctx: Int64) {
  tapTargets[ObjectIdentifier(view(h))] = (cb, ctx)
}

// MARK: - tree

@_cdecl("sui_view_add_child")
public func sui_view_add_child(_ parent: UnsafeMutableRawPointer, _ child: UnsafeMutableRawPointer) {
  view(parent).addSubview(view(child))
}

@_cdecl("sui_view_remove")
public func sui_view_remove(_ h: UnsafeMutableRawPointer) {
  view(h).removeFromSuperview()
}

// Four doubles rather than a CGRect: see the header's rule 1.
@_cdecl("sui_view_set_frame")
public func sui_view_set_frame(
  _ h: UnsafeMutableRawPointer, _ x: Double, _ y: Double, _ w: Double, _ height: Double
) {
  view(h).frame = CGRect(x: x, y: y, width: w, height: height)
}

// MARK: - threading

@_cdecl("sui_run_on_main")
public func sui_run_on_main(_ cb: @escaping sui_main_cb, _ ctx: Int64) {
  DispatchQueue.main.async { cb(ctx) }
}

// MARK: - benchmark + diagnostics

@_cdecl("sui_simulate_taps")
public func sui_simulate_taps(_ h: UnsafeMutableRawPointer, _ n: Int32) {
  guard let t = tapTargets[ObjectIdentifier(view(h))] else { return }
  for _ in 0..<n { t.cb(t.ctx) }
}

// Real UIKit event dispatch, as opposed to calling the stored pointer directly. This is
// the path an actual finger takes, minus the touch digitiser.
@_cdecl("sui_button_send_ui_action")
public func sui_button_send_ui_action(_ h: UnsafeMutableRawPointer) {
  (view(h) as? UIButton)?.sendActions(for: .touchUpInside)
}

@_cdecl("sui_rss_mb")
public func sui_rss_mb() -> Double {
  var info = mach_task_basic_info()
  var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
  let kr = withUnsafeMutablePointer(to: &info) {
    $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
      task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
    }
  }
  return kr == KERN_SUCCESS ? Double(info.resident_size) / (1024 * 1024) : -1
}

@_cdecl("sui_cpu_seconds")
public func sui_cpu_seconds() -> Double {
  var ru = rusage()
  guard getrusage(RUSAGE_SELF, &ru) == 0 else { return -1 }
  let user = Double(ru.ru_utime.tv_sec) + Double(ru.ru_utime.tv_usec) / 1_000_000
  let sys = Double(ru.ru_stime.tv_sec) + Double(ru.ru_stime.tv_usec) / 1_000_000
  return user + sys
}

@_cdecl("sui_live_handle_count")
public func sui_live_handle_count() -> Int32 { liveHandles }

// MARK: - struct-return probe

// S4 measured this on x86-64 SysV, where {float,float} packs into XMM0. arm64 AAPCS64
// returns a homogeneous float aggregate in v0/v1 instead, so the bug may or may not
// reproduce here. Both directions are probed so the report can state which is safe.
@_cdecl("sui_probe_struct_byvalue")
public func sui_probe_struct_byvalue(
  _ cb: @escaping sui_measure_byvalue_cb, _ ctx: Int64, _ result: UnsafeMutablePointer<sui_size>
) {
  result.pointee = cb(ctx)
}

@_cdecl("sui_probe_struct_outparam")
public func sui_probe_struct_outparam(
  _ cb: @escaping sui_measure_outparam_cb, _ ctx: Int64, _ result: UnsafeMutablePointer<sui_size>
) {
  cb(ctx, result)
}
