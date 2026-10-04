import UIKit

// The gallery's UIKit host. Structurally identical to the todo example's — see that one for
// the full reasoning — because the differences between the two apps are all above this
// layer. Only the exported entry symbol and the bundle identity differ.

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
    // Navigation *within* the gallery is the framework's own NavHost, not UIKit's stack.
    let nav = UINavigationController(rootViewController: vc)
    w.rootViewController = nav
    w.makeKeyAndVisible()
    window = w

    sui_set_root_view(Unmanaged.passUnretained(vc.view!).toOpaque())

    // Deferred a run-loop turn so mounting does not happen inside scene setup, where the
    // launch watchdog is counting. The gallery is a big tree — every widget in the
    // catalogue on one screen — so this matters more here than for the todo example.
    // Still the main thread, the only non-Scala thread allowed to enter Scala (S1).
    DispatchQueue.main.async { thicket_gallery_start() }
  }
}
