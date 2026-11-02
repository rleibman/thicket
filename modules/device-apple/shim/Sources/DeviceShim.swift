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
// NotificationCenter registrations, the permission APIs take completion blocks, and location is
// a CLLocationManagerDelegate, an Objective-C class (#118). Location is here, not in a library of
// thicket-location-apple's own, because the permission needs the same manager and delegate: the
// answer to requestWhenInUseAuthorization arrives only as the delegate's authorisation change.
//
// The declarations are checked against tools/shim-gen's DeviceAbi by DeviceConsistencySpec.

import AVFoundation
import CoreLocation
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
#if os(iOS)
  private let locationKeyName = "NSLocationWhenInUseUsageDescription"
#else
  private let locationKeyName = "NSLocationUsageDescription"
#endif
private let locationKey = cString(locationKeyName)
private let appBundle = cString("an app bundle: notifications need the app to run from a .app with a CFBundleIdentifier")

private func declares(_ key: String) -> Bool {
  (Bundle.main.object(forInfoDictionaryKey: key) as? String).map { !$0.isEmpty } ?? false
}

@_cdecl("thk_dev_permission_missing")
public func thk_dev_permission_missing(_ permission: Int32) -> UnsafePointer<CChar> {
  switch permission {
  // iOS ignores requestWhenInUseAuthorization without it, and terminates an app that then
  // uses location.
  case 0: return declares(locationKeyName) ? nothingMissing : locationKey
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
  case 0: return locationStatus(locationManager.authorizationStatus)
  case 1: return capture(AVCaptureDevice.authorizationStatus(for: .video))
  case 2: return capture(AVCaptureDevice.authorizationStatus(for: .audio))
  case 3: return notificationStatus()
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
  case 0:
    // No completion block: the answer is the delegate's next authorisation change. Already
    // answered, iOS shows nothing and sends no change, so the status is delivered as it stands.
    if locationManager.authorizationStatus == .notDetermined {
      locationAnswers.append(deliver)
      locationManager.requestWhenInUseAuthorization()
    } else {
      deliver()
    }
  case 1: AVCaptureDevice.requestAccess(for: .video) { _ in deliver() }
  case 2: AVCaptureDevice.requestAccess(for: .audio) { _ in deliver() }
  case 3: UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in deliver() }
  default: DispatchQueue.main.async { answered(ctx, unsupported) }
  }
}

// MARK: - location

private func locationStatus(_ s: CLAuthorizationStatus) -> Int32 {
  switch s {
  case .authorizedAlways, .authorizedWhenInUse: return granted
  case .denied: return denied
  case .restricted: return restricted
  case .notDetermined: return notDetermined
  @unknown default: return denied
  }
}

private struct Listener {
  let fine: Bool
  let fixed: thk_dev_void_cb
  let ctx: Int64
}

private var listeners: [Int64: Listener] = [:]
private var nextListener: Int64 = 1
/// The position being delivered, read back by thk_dev_location_fix(0, ...) from inside the callback.
private var delivering: CLLocation?
/// Permission requests waiting for the user's answer.
private var locationAnswers: [() -> Void] = []

/// Delivers on the thread the manager was made on, which must have a run loop: the main thread,
/// the only one that calls in here.
private final class LocationDelegate: NSObject, CLLocationManagerDelegate {
  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    // Also called once when the delegate is set, with the status as it stands: an answer only
    // once it is no longer "not determined".
    guard manager.authorizationStatus != .notDetermined else { return }
    let waiting = locationAnswers
    locationAnswers = []
    waiting.forEach { $0() }
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    for location in locations {
      delivering = location
      // A listener may stop itself, or another, from inside its callback (Location.current).
      for id in listeners.keys.sorted() {
        if let l = listeners[id] { l.fixed(l.ctx) }
      }
    }
    delivering = nil
  }

  // kCLErrorLocationUnknown is transient and the manager keeps trying; a denial arrives as an
  // authorisation change. Nothing for the listeners either way.
  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

private let locationDelegate = LocationDelegate()

private let locationManager: CLLocationManager = {
  let m = CLLocationManager()
  m.delegate = locationDelegate
  return m
}()

private func updateListening() {
  if listeners.isEmpty {
    locationManager.stopUpdatingLocation()
  } else {
    locationManager.desiredAccuracy =
      listeners.values.contains { $0.fine } ? kCLLocationAccuracyBest : kCLLocationAccuracyKilometer
    locationManager.startUpdatingLocation()
  }
}

@_cdecl("thk_dev_location_availability")
public func thk_dev_location_availability() -> Int32 {
  switch locationManager.authorizationStatus {
  case .notDetermined, .denied: return 1
  case .restricted: return 3
  default:
    // Asked only once the app may use location: Apple warns that it can block the main thread.
    return CLLocationManager.locationServicesEnabled() ? 0 : 2
  }
}

@_cdecl("thk_dev_location_start")
public func thk_dev_location_start(_ fine: Int32, _ fixed: @escaping thk_dev_void_cb, _ ctx: Int64) -> Int64 {
  let id = nextListener
  nextListener += 1
  listeners[id] = Listener(fine: fine != 0, fixed: fixed, ctx: ctx)
  updateListening()
  return id
}

@_cdecl("thk_dev_location_stop")
public func thk_dev_location_stop(_ id: Int64) {
  if listeners.removeValue(forKey: id) != nil { updateListening() }
}

@_cdecl("thk_dev_location_fix")
public func thk_dev_location_fix(
  _ which: Int32, _ latitude: UnsafeMutablePointer<Double>, _ longitude: UnsafeMutablePointer<Double>,
  _ accuracy: UnsafeMutablePointer<Double>, _ altitude: UnsafeMutablePointer<Double>,
  _ timeMillis: UnsafeMutablePointer<Double>
) -> Int32 {
  guard let l = which == 0 ? delivering : locationManager.location else { return 0 }
  latitude.pointee = l.coordinate.latitude
  longitude.pointee = l.coordinate.longitude
  accuracy.pointee = l.horizontalAccuracy >= 0 ? l.horizontalAccuracy : Double.nan
  // Above the WGS 84 ellipsoid, as Fix says, not CLLocation.altitude's mean sea level.
  altitude.pointee = l.verticalAccuracy > 0 ? l.ellipsoidalAltitude : Double.nan
  timeMillis.pointee = (l.timestamp.timeIntervalSince1970 * 1000).rounded()
  return 1
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
      // Not `isVisible`: under a locked screen it was false for the gallery's own window.
      if miniaturized != 0, w.isMiniaturizable, w.canBecomeMain, !w.isMiniaturized {
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

@_cdecl("thk_dev_test_screen_locked")
public func thk_dev_test_screen_locked() -> Int32 {
  #if os(iOS)
    return 0
  #else
    // A locked Mac occludes every window, so every app is off screen: Background, correctly, and
    // nothing an app does brings it back (measured, #114).
    let session = CGSessionCopyCurrentDictionary() as? [String: Any]
    return (session?["CGSSessionScreenIsLocked"] as? Bool ?? false) ? 1 : 0
  #endif
}

@_cdecl("thk_dev_test_location_listeners")
public func thk_dev_test_location_listeners() -> Int32 {
  Int32(listeners.count)
}

@_cdecl("thk_dev_test_terminate")
public func thk_dev_test_terminate() {
  #if os(iOS)
    NotificationCenter.default.post(name: UIApplication.willTerminateNotification, object: UIApplication.shared)
  #else
    NSApp.terminate(nil)
  #endif
}
