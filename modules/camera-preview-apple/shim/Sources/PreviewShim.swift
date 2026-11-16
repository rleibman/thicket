// thicket-camera-preview-apple: a live camera preview over AVCaptureSession, in a UIView on iOS
// and an NSView on macOS, and a still photo from it (#148, docs/17-camera-preview.md §17.4).
//
// ON THE MAIN THREAD ONLY, like thicket-camera-apple: Scala calls in on the main thread, and
// every answer is delivered there, the one non-Scala thread allowed to enter Scala (S1); the
// Scala side wraps each answer in GcState.guarded (S3). The session is configured, started and
// stopped on a serial queue of this library's own, as AVFoundation asks: startRunning blocks.
//
// Swift rather than Scala alone: AVCapturePhotoCaptureDelegate and
// AVCaptureVideoDataOutputSampleBufferDelegate are Objective-C protocols, the preview layer is
// a view's backing layer, and the rotation coordinator is observed through KVO.
//
// One session at a time in the process: thk_prev_start stops the one running first. The photo is
// handed over as a file, written into the directory Scala names. The camera permission
// (AVCaptureDevice's authorisation) is thicket-device-apple's.
//
// The declarations are checked against tools/shim-gen's PreviewAbi by PreviewConsistencySpec.

import AVFoundation
import Foundation

#if os(iOS)
  import UIKit
  public typealias PreviewBase = UIView
#else
  import AppKit
  public typealias PreviewBase = NSView
#endif

public typealias thk_prev_int_cb = @convention(c) (Int64, Int32) -> Void
public typealias thk_prev_text_cb = @convention(c) (Int64, UnsafePointer<CChar>) -> Void

private func gravity(_ fit: Int32) -> AVLayerVideoGravity {
  switch fit {
  case 0: return .resizeAspect
  case 2: return .resize
  default: return .resizeAspectFill
  }
}

private func fitCode(_ g: AVLayerVideoGravity) -> Int32 {
  switch g {
  case .resizeAspect: return 0
  case .resizeAspectFill: return 1
  case .resize: return 2
  default: return -1
  }
}

/// The preview's view: its layer is the AVCaptureVideoPreviewLayer. It has no size of its own,
/// so it gives itself an aspect, height over width, as a required-but-one constraint: the frame's
/// once a session runs in it.
final class PreviewView: PreviewBase {
  private var aspectConstraint: NSLayoutConstraint!
  private(set) var aspect: CGFloat

  #if os(iOS)
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
  #else
    let previewLayer = AVCaptureVideoPreviewLayer()
    override func makeBackingLayer() -> CALayer { previewLayer }
    override var isFlipped: Bool { true }
  #endif

  init(fit: Int32) {
    #if os(iOS)
      aspect = 4.0 / 3.0
    #else
      aspect = 3.0 / 4.0
    #endif
    super.init(frame: CGRect(x: 0, y: 0, width: 320, height: 320 * aspect))
    #if os(macOS)
      wantsLayer = true
    #endif
    previewLayer.videoGravity = gravity(fit)
    #if os(iOS)
      backgroundColor = .black
      clipsToBounds = true
    #else
      previewLayer.backgroundColor = CGColor(gray: 0, alpha: 1)
      previewLayer.masksToBounds = true
    #endif
    aspectConstraint = makeAspect(aspect)
    aspectConstraint.isActive = true
    setContentHuggingPriority(.defaultLow, for: .horizontal)
    setContentHuggingPriority(.defaultLow, for: .vertical)
  }

  required init?(coder: NSCoder) { fatalError("not used") }

  private func makeAspect(_ a: CGFloat) -> NSLayoutConstraint {
    let c = heightAnchor.constraint(equalTo: widthAnchor, multiplier: a)
    // Below required, so a host that fixes both sides wins over it.
    #if os(iOS)
      c.priority = UILayoutPriority(rawValue: 999)
    #else
      c.priority = NSLayoutConstraint.Priority(rawValue: 999)
    #endif
    return c
  }

  /// A width a layout that leaves it open can start from; the height follows the aspect.
  override var intrinsicContentSize: CGSize { CGSize(width: 320, height: 320 * aspect) }

  func setAspect(_ a: CGFloat) {
    guard a > 0, abs(a - aspect) > 0.001 else { return }
    aspect = a
    aspectConstraint.isActive = false
    aspectConstraint = makeAspect(a)
    aspectConstraint.isActive = true
    invalidateIntrinsicContentSize()
  }
}

/// Counts the frames a running session delivers, on the queue they arrive on.
private final class FrameCounter: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
  private let lock = NSLock()
  private var n: Int64 = 0

  var count: Int64 {
    lock.lock()
    defer { lock.unlock() }
    return n
  }

  func captureOutput(
    _ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection
  ) {
    lock.lock()
    n += 1
    lock.unlock()
  }
}

/// The delegate of the one capture under way. The photo output holds it weakly, so the session
/// holds it until the answer.
private final class PhotoAnswer: NSObject, AVCapturePhotoCaptureDelegate {
  let dir: String
  let done: thk_prev_text_cb
  let ctx: Int64
  let finished: (PhotoAnswer, String) -> Void

  init(
    _ dir: String, _ done: @escaping thk_prev_text_cb, _ ctx: Int64,
    _ finished: @escaping (PhotoAnswer, String) -> Void
  ) {
    self.dir = dir
    self.done = done
    self.ctx = ctx
    self.finished = finished
  }

  func photoOutput(
    _ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?
  ) {
    var path = ""
    if error == nil, let data = photo.fileDataRepresentation() {
      do {
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let ms = Int64(Date().timeIntervalSince1970 * 1000)
        let file = (dir as NSString).appendingPathComponent("preview-\(ms).jpg")
        try data.write(to: URL(fileURLWithPath: file), options: .atomic)
        path = file
      } catch {
        NSLog("thicket camera preview: the photo could not be written: \(error)")
      }
    } else if let error {
      NSLog("thicket camera preview: the capture failed: \(error)")
    }
    DispatchQueue.main.async { self.finished(self, path) }
  }
}

/// One running session: the camera, its outputs, and the view it shows in.
private final class Running {
  let session = AVCaptureSession()
  let device: AVCaptureDevice
  let facing: Int32
  weak var view: PreviewView?
  let photo = AVCapturePhotoOutput()
  let frames = AVCaptureVideoDataOutput()
  var capture: PhotoAnswer?
  var stopped = false
  /// Counted in runningCount: set on the main thread once the session runs.
  var counted = false
  #if os(iOS)
    var rotation: AVCaptureDevice.RotationCoordinator?
    var observation: NSKeyValueObservation?
  #endif

  init(_ device: AVCaptureDevice, _ facing: Int32, _ view: PreviewView) {
    self.device = device
    self.facing = facing
    self.view = view
  }

  /// On the session queue: inputs, outputs, preset. False if the camera cannot be used.
  func configure(_ counter: FrameCounter, _ frameQueue: DispatchQueue) -> Bool {
    session.beginConfiguration()
    defer { session.commitConfiguration() }
    if session.canSetSessionPreset(.photo) { session.sessionPreset = .photo }
    guard let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
      return false
    }
    session.addInput(input)
    guard session.canAddOutput(photo) else { return false }
    session.addOutput(photo)
    frames.alwaysDiscardsLateVideoFrames = true
    frames.setSampleBufferDelegate(counter, queue: frameQueue)
    if session.canAddOutput(frames) { session.addOutput(frames) }
    return true
  }

  var size: CMVideoDimensions { CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription) }

  /// On the main thread, once running: the preview upright, and the view's aspect the frame's as shown.
  func showUpright() {
    guard let view else { return }
    let dims = size
    guard dims.width > 0, dims.height > 0 else { return }
    var turned = false
    #if os(iOS)
      let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: view.previewLayer)
      rotation = coordinator
      let apply: () -> Void = { [weak self, weak coordinator] in
        guard let self, let coordinator, let view = self.view, !self.stopped else { return }
        let angle = coordinator.videoRotationAngleForHorizonLevelPreview
        if let c = view.previewLayer.connection, c.isVideoRotationAngleSupported(angle) {
          c.videoRotationAngle = angle
        }
        let quarter = Int((angle / 90).rounded()) % 2 != 0
        let d = self.size
        view.setAspect(quarter ? CGFloat(d.width) / CGFloat(d.height) : CGFloat(d.height) / CGFloat(d.width))
      }
      observation = coordinator.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.new]) { _, _ in
        DispatchQueue.main.async { apply() }
      }
      apply()
      turned = Int((coordinator.videoRotationAngleForHorizonLevelPreview / 90).rounded()) % 2 != 0
    #endif
    if !turned { view.setAspect(CGFloat(dims.height) / CGFloat(dims.width)) }
  }

  /// The angle a photo taken now should be turned by to be upright, where the platform says.
  func photoAngle() -> CGFloat? {
    #if os(iOS)
      return rotation?.videoRotationAngleForHorizonLevelCapture
    #else
      return nil
    #endif
  }
}

private let sessionQueue = DispatchQueue(label: "dev.thicket.camera-preview.session")
private let frameQueue = DispatchQueue(label: "dev.thicket.camera-preview.frames")
private let counter = FrameCounter()
private var current: Running?
/// Sessions started and running, not yet stopped: updated on the main thread.
private var runningCount: Int32 = 0

private func camera(_ facing: Int32) -> AVCaptureDevice? {
  #if os(iOS)
    return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: facing == 0 ? .front : .back)
  #else
    var types: [AVCaptureDevice.DeviceType] = [.builtInWideAngleCamera]
    if #available(macOS 14.0, *) {
      types.append(.external)
    } else {
      types.append(.externalUnknown)
    }
    let all = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .unspecified).devices
    // docs/17 §17.4: on the desktop front is the first camera, back is none unless one says so.
    return facing == 0 ? all.first { $0.position != .back } : all.first { $0.position == .back }
  #endif
}

@_cdecl("thk_prev_has_camera")
public func thk_prev_has_camera(_ facing: Int32) -> Int32 {
  camera(facing) == nil ? 0 : 1
}

@_cdecl("thk_prev_view_new")
public func thk_prev_view_new(_ fit: Int32) -> UnsafeMutableRawPointer {
  Unmanaged.passRetained(PreviewView(fit: fit)).toOpaque()
}

@_cdecl("thk_prev_start")
public func thk_prev_start(
  _ view: UnsafeMutableRawPointer, _ facing: Int32, _ done: @escaping thk_prev_int_cb, _ ctx: Int64
) -> Int32 {
  _ = thk_prev_stop()
  guard let device = camera(facing) else { return 0 }
  let v = Unmanaged<PreviewView>.fromOpaque(view).takeUnretainedValue()
  let r = Running(device, facing, v)
  current = r
  v.previewLayer.session = r.session
  sessionQueue.async {
    let ok = r.configure(counter, frameQueue)
    if ok { r.session.startRunning() }
    let running = ok && r.session.isRunning
    DispatchQueue.main.async {
      // Stopped while it started: the stop's own work on the queue stops the session, and the
      // answer is dropped.
      guard !r.stopped else { return }
      if running {
        r.counted = true
        runningCount += 1
        r.showUpright()
      }
      done(ctx, running ? 1 : 0)
    }
  }
  return 1
}

@_cdecl("thk_prev_stop")
public func thk_prev_stop() -> Int32 {
  guard let r = current else { return 0 }
  current = nil
  r.stopped = true
  r.capture = nil
  #if os(iOS)
    r.observation?.invalidate()
    r.observation = nil
    r.rotation = nil
  #endif
  r.view?.previewLayer.session = nil
  if r.counted {
    r.counted = false
    runningCount -= 1
  }
  sessionQueue.async {
    if r.session.isRunning { r.session.stopRunning() }
    r.frames.setSampleBufferDelegate(nil, queue: nil)
  }
  return 1
}

@_cdecl("thk_prev_capture")
public func thk_prev_capture(_ dir: UnsafePointer<CChar>, _ done: @escaping thk_prev_text_cb, _ ctx: Int64) -> Int32 {
  guard let r = current, !r.stopped, r.capture == nil, r.session.isRunning else { return 0 }
  let answer = PhotoAnswer(String(cString: dir), done, ctx) { [weak r] a, path in
    // Answered only while this capture is still the session's: a stop drops it.
    guard let r, r.capture === a else { return }
    r.capture = nil
    path.withCString { a.done(a.ctx, $0) }
  }
  r.capture = answer
  let angle = r.photoAngle()
  sessionQueue.async {
    let settings: AVCapturePhotoSettings =
      r.photo.availablePhotoCodecTypes.contains(.jpeg)
      ? AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg]) : AVCapturePhotoSettings()
    #if os(iOS)
      if let angle, let c = r.photo.connection(with: .video), c.isVideoRotationAngleSupported(angle) {
        c.videoRotationAngle = angle
      }
    #else
      _ = angle
    #endif
    r.photo.capturePhoto(with: settings, delegate: answer)
  }
  return 1
}

@_cdecl("thk_prev_test_running")
public func thk_prev_test_running() -> Int32 {
  runningCount
}

@_cdecl("thk_prev_test_frames")
public func thk_prev_test_frames() -> Int64 {
  counter.count
}

@_cdecl("thk_prev_test_size")
public func thk_prev_test_size(_ width: UnsafeMutablePointer<Double>, _ height: UnsafeMutablePointer<Double>) -> Int32 {
  guard let r = current, !r.stopped else { return 0 }
  let d = r.size
  width.pointee = Double(d.width)
  height.pointee = Double(d.height)
  return 1
}

@_cdecl("thk_prev_test_facing")
public func thk_prev_test_facing() -> Int32 {
  guard let r = current, !r.stopped, r.session.isRunning else { return -1 }
  return r.facing
}

@_cdecl("thk_prev_test_gravity")
public func thk_prev_test_gravity(_ view: UnsafeMutableRawPointer) -> Int32 {
  let v = Unmanaged<PreviewBase>.fromOpaque(view).takeUnretainedValue()
  // The view's own layer, not the property: on macOS this also asks that the backing layer is the preview's.
  guard v is PreviewView, let layer = v.layer as? AVCaptureVideoPreviewLayer else { return -1 }
  return fitCode(layer.videoGravity)
}

@_cdecl("thk_prev_test_fit")
public func thk_prev_test_fit(_ fit: Int32) -> Int32 {
  fitCode(PreviewView(fit: fit).previewLayer.videoGravity)
}

@_cdecl("thk_prev_test_aspect")
public func thk_prev_test_aspect(_ view: UnsafeMutableRawPointer) -> Double {
  let v = Unmanaged<PreviewBase>.fromOpaque(view).takeUnretainedValue()
  return Double((v as? PreviewView)?.aspect ?? 0)
}
