import UIKit

// Minimal UIKit host. Deliberately not SwiftUI: S3 is about Scala driving raw UIViews
// through the shim, so the host's only jobs are to own a root view and hand it to Scala.
//
// The scene lifecycle is not optional. iOS 27 traps at launch
// (__UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption, EXC_BREAKPOINT) for an
// app that only implements the old window-on-AppDelegate pattern, so any app template the
// framework ships has to be scene-based.

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
  func application(
    _ application: UIApplication,
    configurationForConnecting connectingSceneSession: UISceneSession,
    options: UIScene.ConnectionOptions
  ) -> UISceneConfiguration {
    let config = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
    // Set here rather than via UISceneDelegateClassName in Info.plist: that key needs the
    // Swift module name, which swiftc picks on its own outside an Xcode target.
    config.delegateClass = SceneDelegate.self
    return config
  }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
  var window: UIWindow?

  func scene(
    _ scene: UIScene,
    willConnectTo session: UISceneSession,
    options connectionOptions: UIScene.ConnectionOptions
  ) {
    guard let windowScene = scene as? UIWindowScene else { return }

    let w = UIWindow(windowScene: windowScene)
    let vc = UIViewController()
    vc.view.backgroundColor = .white
    w.rootViewController = vc
    w.makeKeyAndVisible()
    window = w

    let root = Unmanaged.passUnretained(vc.view!).toOpaque()
    sui_set_root_view(root)

    // Order matters: the Scala runtime has to be up before any exported function, and it
    // must start on the main thread, because the main thread is thereafter the only
    // non-Scala thread allowed to re-enter Scala (S1).
    ScalaNativeInit()

    // Deferred one turn of the run loop so the 100k-iteration benchmark inside
    // scalaui_main does not run inside scene setup, where iOS's launch watchdog is
    // counting. This still lands on the main thread — the same path sui_run_on_main uses.
    DispatchQueue.main.async { scalaui_main(root) }
  }
}
