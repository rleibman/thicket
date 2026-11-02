// thicket-motion-ios: the motion sensors on iOS, through CMMotionManager (#120,
// docs/16-device-services.md §16.3, §16.10 item 4).
//
// PUSH, ON THE MAIN THREAD ONLY, like thicket-device-apple: every update is delivered to
// OperationQueue.main, the one non-Scala thread allowed to enter Scala (S1), and the Scala side
// wraps each sample in GcState.guarded (S3).
//
// Swift rather than a C helper: CMMotionManager's start…Updates(to:withHandler:) take Swift
// (Objective-C) blocks, which C cannot write without the blocks extension and an Objective-C
// file, and the handler reads properties off CMAccelerometerData / CMGyroData / CMDeviceMotion
// objects. Swift, beside the other Apple libraries, keeps one language and one build shape.
//
// Samples are passed as Core Motion reports them, three doubles, never a struct: acceleration in
// g, rotation rate in rad/s, attitude as roll, pitch, yaw. Scala converts to Thicket's units.
//
// One CMMotionManager for the process, as Apple asks. A sensor has one handler: Scala shares each
// sensor among its listeners, and calls thk_mot_start again only to change the interval.
//
// The declarations are checked against tools/shim-gen's MotionAbi by MotionConsistencySpec.

import CoreMotion
import Foundation

public typealias thk_mot_vec3_cb = @convention(c) (Int64, Double, Double, Double) -> Void

private let manager = CMMotionManager()

@_cdecl("thk_mot_available")
public func thk_mot_available(_ sensor: Int32) -> Int32 {
  let has: Bool
  switch sensor {
  case 0: has = manager.isAccelerometerAvailable
  case 1: has = manager.isGyroAvailable
  case 2: has = manager.isDeviceMotionAvailable
  default: has = false
  }
  return has ? 1 : 0
}

@_cdecl("thk_mot_start")
public func thk_mot_start(_ sensor: Int32, _ interval: Double, _ sample: @escaping thk_mot_vec3_cb, _ ctx: Int64) {
  switch sensor {
  case 0:
    guard manager.isAccelerometerAvailable else { return }
    manager.accelerometerUpdateInterval = interval
    if manager.isAccelerometerActive { return }
    manager.startAccelerometerUpdates(to: .main) { data, _ in
      guard let a = data?.acceleration else { return }
      sample(ctx, a.x, a.y, a.z)
    }
  case 1:
    guard manager.isGyroAvailable else { return }
    manager.gyroUpdateInterval = interval
    if manager.isGyroActive { return }
    manager.startGyroUpdates(to: .main) { data, _ in
      guard let r = data?.rotationRate else { return }
      sample(ctx, r.x, r.y, r.z)
    }
  case 2:
    guard manager.isDeviceMotionAvailable else { return }
    manager.deviceMotionUpdateInterval = interval
    if manager.isDeviceMotionActive { return }
    manager.startDeviceMotionUpdates(to: .main) { motion, _ in
      guard let a = motion?.attitude else { return }
      sample(ctx, a.roll, a.pitch, a.yaw)
    }
  default:
    return
  }
}

@_cdecl("thk_mot_stop")
public func thk_mot_stop(_ sensor: Int32) {
  switch sensor {
  case 0: manager.stopAccelerometerUpdates()
  case 1: manager.stopGyroUpdates()
  case 2: manager.stopDeviceMotionUpdates()
  default: return
  }
}

// MARK: - testing

@_cdecl("thk_mot_test_active")
public func thk_mot_test_active(_ sensor: Int32) -> Int32 {
  let active: Bool
  switch sensor {
  case 0: active = manager.isAccelerometerActive
  case 1: active = manager.isGyroActive
  case 2: active = manager.isDeviceMotionActive
  default: active = false
  }
  return active ? 1 : 0
}
