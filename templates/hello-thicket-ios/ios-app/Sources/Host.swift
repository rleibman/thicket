import UIKit

// The iOS host. UIKit apps must adopt the scene lifecycle, and a scene delegate cannot live
// in the static library Scala Native produces, so the host is Swift and owns `main`. It
// creates the window, hands its root view to Thicket's shim, and calls the Scala entry point.
// Change the UI in src/main/scala; this file only changes if you rename the entry symbol.

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
  /// Scala Native is initialised at the top of `main`, before UIKit runs, and never in
  /// scene setup.
  ///
  /// Its collector records the main thread's stack base as the address of a local inside
  /// its own initialisation and scans only from there down. Called from scene setup, that
  /// base sat deep inside UIKit's launch, so every later entry into Scala ran *nearer* the
  /// true base — outside the scanned range. Objects referenced only from the main thread's
  /// stack were invisible to the collector and freed while live (#22).
  static func main() {
    ScalaNativeInit()
    UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
  }

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
    let nav = UINavigationController(rootViewController: vc)
    w.rootViewController = nav
    w.makeKeyAndVisible()
    window = w

    sui_set_root_view(Unmanaged.passUnretained(vc.view!).toOpaque())

    // Deferred a run-loop turn so mounting does not happen inside scene setup, where the
    // launch watchdog is counting. Still the main thread, the only non-Scala thread allowed
    // to enter Scala.
    DispatchQueue.main.async { thicket_hello_start() }
  }
}
