import UIKit

// Scene-based by necessity: iOS 27 traps at launch on apps that only implement the old
// window-on-AppDelegate lifecycle (S3).

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
  func application(
    _ application: UIApplication,
    configurationForConnecting connectingSceneSession: UISceneSession,
    options: UIScene.ConnectionOptions
  ) -> UISceneConfiguration {
    let config = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
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
    ScalaNativeInit()


    // Deferred a run-loop turn so the ZIO runtime does not start inside scene setup, where
    // the launch watchdog is counting. Still the main thread, which is the only non-Scala
    // thread allowed to enter Scala (S1).
    DispatchQueue.main.async {
      scalaui_hello_main(root)
      // Printed after the first UI update is committed, not merely scheduled: the S6
      // harness pairs this against a host timestamp taken just before simctl launch.
      CATransaction.flush()
      print("S6_FIRST_RENDER \(Date().timeIntervalSince1970)")
    }
  }
}
