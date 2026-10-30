// thicket-device-apple: the app's lifecycle and permissions on macOS and iOS (#114,
// docs/16-device-services.md §16.3).
//
// PUSH, ON THE MAIN THREAD ONLY. Unlike thicket-http-apple this library calls into Scala, and
// only ever from the main thread, the one non-Scala thread allowed to enter Scala (S1): the
// observers are registered on the main queue, and each permission answer, which the platform
// delivers on a queue of its own, is moved to the main queue before the callback is called.
// The Scala side wraps every callback in GcState.guarded (S3).
//
// Swift rather than a C helper like thicket_keychain.c: the observers are block-based
// NotificationCenter registrations and the permission APIs take completion blocks, and
// thicket-location-apple (#118) will add a CLLocationManagerDelegate here, an Objective-C class.
//
// The declarations are checked against tools/shim-gen's DeviceAbi by DeviceConsistencySpec.

import AVFoundation
import Foundation
import UserNotifications

#if os(iOS)
  import UIKit
#else
  import AppKit
#endif

public typealias thk_dev_void_cb = @convention(c) (Int64) -> Void
public typealias thk_dev_int_cb = @convention(c) (Int64, Int32) -> Void

// Statuses, as DeviceAbi numbers them.
private let granted: Int32 = 0
private let denied: Int32 = 1
private let restricted: Int32 = 2
private let notDetermined: Int32 = 3
private let unsupported: Int32 = -1

// MARK: - lifecycle

private var lifecycleStarted = false
private var onScreen = false
private var terminateObserved = false

/// Where the app is now. iOS: a scene in the foreground, active or not (a system alert over the
/// app leaves it there). macOS: any part of any of the app's windows visible.
private func appOnScreen() -> Bool {
  #if os(iOS)
    return UIApplication.shared.connectedScenes.contains {
      $0.activationState == .foregroundActive || $0.activationState == .foregroundInactive
    }
  #else
    return NSApp.occlusionState.contains(.visible)
  #endif
}

@_cdecl("thk_dev_lifecycle_start")
public func thk_dev_lifecycle_start(_ changed: @escaping thk_dev_int_cb, _ ctx: Int64) -> Int32 {
  if !lifecycleStarted {
    lifecycleStarted = true
    onScreen = appOnScreen()
    let report: (Bool) -> Void = { now in
      if now != onScreen {
        onScreen = now
        changed(ctx, now ? 1 : 0)
      }
    }
    let nc = NotificationCenter.default
    #if os(iOS)
      // The scene's foreground and background, not its activation: activation also comes and
      // goes under Control Centre and app switching, and a headless simulator never activated a
      // scene at all (measured, #114). A scene's state is not yet updated when these are posted,
      // so entering the foreground is taken at its word, and leaving it asks the other scenes.
      _ = nc.addObserver(forName: UIScene.willEnterForegroundNotification, object: nil, queue: .main) { _ in
        report(true)
      }
      _ = nc.addObserver(forName: UIScene.didEnterBackgroundNotification, object: nil, queue: .main) { note in
        let leaving = note.object as AnyObject?
        report(
          UIApplication.shared.connectedScenes.contains {
            $0 !== leaving && ($0.activationState == .foregroundActive || $0.activationState == .foregroundInactive)
          })
      }
    #else
      // One notification for hiding, minimising, ordering out and being covered (measured, #114).
      _ = nc.addObserver(forName: NSApplication.didChangeOcclusionStateNotification, object: nil, queue: .main) { _ in
        report(appOnScreen())
      }
    #endif
  }
  return onScreen ? 1 : 0
}

@_cdecl("thk_dev_on_terminate")
public func thk_dev_on_terminate(_ terminating: @escaping thk_dev_void_cb, _ ctx: Int64) {
  if terminateObserved { return }
  terminateObserved = true
  #if os(iOS)
    let name = UIApplication.willTerminateNotification
  #else
    let name = NSApplication.willTerminateNotification
  #endif
  _ = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in terminating(ctx) }
}

// MARK: - permissions

private func cString(_ s: String) -> UnsafePointer<CChar> { UnsafePointer(strdup(s)!) }

private let nothingMissing = cString("")
private let cameraKey = cString("NSCameraUsageDescription")
private let microphoneKey = cString("NSMicrophoneUsageDescription")
private let appBundle = cString("an app bundle: notifications need the app to run from a .app with a CFBundleIdentifier")

private func declares(_ key: String) -> Bool {
  (Bundle.main.object(forInfoDictionaryKey: key) as? String).map { !$0.isEmpty } ?? false
}

@_cdecl("thk_dev_permission_missing")
public func thk_dev_permission_missing(_ permission: Int32) -> UnsafePointer<CChar> {
  switch permission {
  case 1: return declares("NSCameraUsageDescription") ? nothingMissing : cameraKey
  case 2: return declares("NSMicrophoneUsageDescription") ? nothingMissing : microphoneKey
  // UNUserNotificationCenter.current() raises for a process with no bundle (an executable run
  // outside an .app, as the macOS gallery is) instead of answering.
  case 3: return Bundle.main.bundleIdentifier == nil ? appBundle : nothingMissing
  default: return nothingMissing
  }
}

private func capture(_ s: AVAuthorizationStatus) -> Int32 {
  switch s {
  case .authorized: return granted
  case .denied: return denied
  case .restricted: return restricted
  case .notDetermined: return notDetermined
  @unknown default: return denied
  }
}

/// UserNotifications answers only through a completion handler, on a queue of its own, so the
/// caller waits for it (a few ms; marked blocking on the Scala side). Never on that queue itself.
private func notificationStatus() -> Int32 {
  let done = DispatchSemaphore(value: 0)
  var status = UNAuthorizationStatus.notDetermined
  UNUserNotificationCenter.current().getNotificationSettings { s in
    status = s.authorizationStatus
    done.signal()
  }
  if done.wait(timeout: .now() + 5) == .timedOut { return denied }
  switch status {
  case .authorized, .provisional: return granted
  case .denied: return denied
  case .notDetermined: return notDetermined
  default:
    #if os(iOS)
      if status == .ephemeral { return granted }
    #endif
    return denied
  }
}

private func status(_ permission: Int32) -> Int32 {
  switch permission {
  case 1: return capture(AVCaptureDevice.authorizationStatus(for: .video))
  case 2: return capture(AVCaptureDevice.authorizationStatus(for: .audio))
  case 3: return notificationStatus()
  // Location comes with thicket-location-apple (#118), with the CLLocationManager it needs.
  default: return unsupported
  }
}

@_cdecl("thk_dev_permission_status")
public func thk_dev_permission_status(_ permission: Int32) -> Int32 {
  status(permission)
}

@_cdecl("thk_dev_permission_request")
public func thk_dev_permission_request(_ permission: Int32, _ answered: @escaping thk_dev_int_cb, _ ctx: Int64) {
  // The answer is read back from the platform rather than taken from the completion's Bool, so
  // that Restricted, and a refusal given earlier, are reported as such. On the main queue: the
  // status read may wait for UserNotifications, which answers on a queue of its own.
  let deliver: () -> Void = { DispatchQueue.main.async { answered(ctx, status(permission)) } }
  switch permission {
  case 1: AVCaptureDevice.requestAccess(for: .video) { _ in deliver() }
  case 2: AVCaptureDevice.requestAccess(for: .audio) { _ in deliver() }
  case 3: UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in deliver() }
  default: DispatchQueue.main.async { answered(ctx, unsupported) }
  }
}

// MARK: - testing

@_cdecl("thk_dev_test_hide")
public func thk_dev_test_hide(_ hidden: Int32) -> Int32 {
  #if os(iOS)
    return 0
  #else
    if hidden != 0 { NSApp.hide(nil) } else { NSApp.unhide(nil) }
    return 1
  #endif
}

@_cdecl("thk_dev_test_miniaturize")
public func thk_dev_test_miniaturize(_ miniaturized: Int32) -> Int32 {
  #if os(iOS)
    return 0
  #else
    var n: Int32 = 0
    for w in NSApp.windows {
      if miniaturized != 0, w.isVisible, w.isMiniaturizable {
        w.miniaturize(nil)
        n += 1
      } else if miniaturized == 0, w.isMiniaturized {
        w.deminiaturize(nil)
        n += 1
      }
    }
    return n
  #endif
}

@_cdecl("thk_dev_test_terminate")
public func thk_dev_test_terminate() {
  #if os(iOS)
    NotificationCenter.default.post(name: UIApplication.willTerminateNotification, object: UIApplication.shared)
  #else
    NSApp.terminate(nil)
  #endif
}
