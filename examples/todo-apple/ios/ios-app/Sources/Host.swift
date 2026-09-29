import UIKit

// Scene-based by necessity: iOS 27 traps at launch on apps that only implement the old
// window-on-AppDelegate lifecycle (S3). A scene delegate cannot live inside the Scala
// Native static archive, so the host owns `@main` and the renderer is handed a root view
// that already exists — the mirror image of macOS, where `AppleApp.run` never returns.

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
    vc.view.backgroundColor = .systemBackground
    // A navigation controller so the title the renderer sets has somewhere to appear.
    // Navigation *within* the app is the framework's own NavHost, not UIKit's stack: the
    // element tree is what changes, exactly as it does on macOS.
    let nav = UINavigationController(rootViewController: vc)
    w.rootViewController = nav
    w.makeKeyAndVisible()
    window = w

    sui_set_root_view(Unmanaged.passUnretained(vc.view!).toOpaque())
    ScalaNativeInit()

    // Deferred a run-loop turn so mounting does not happen inside scene setup, where the
    // launch watchdog is counting. Still the main thread, which is the only non-Scala
    // thread allowed to enter Scala (S1).
    DispatchQueue.main.async { thicket_todo_start() }
  }
}
