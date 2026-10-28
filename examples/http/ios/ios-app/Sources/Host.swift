import UIKit

// The HTTP self-test's iOS host (#90). Scene-based because iOS 27 traps at launch without a
// UIScene (S3); it shows a plain window and hands control to Scala. No renderer: the point is
// that thicket-http-apple works without one.

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
  /// Scala Native is initialised at the top of `main`, the shallowest frame the host owns, so
  /// the collector's stack range covers every later entry from the run loop (#22).
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
    let label = UILabel()
    label.text = "thicket-http-apple self-test"
    label.translatesAutoresizingMaskIntoConstraints = false
    vc.view.addSubview(label)
    NSLayoutConstraint.activate([
      label.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
      label.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor),
    ])
    w.rootViewController = vc
    w.makeKeyAndVisible()
    window = w

    // A run-loop turn later, on the main thread: Scala checks the backend refuses this thread,
    // then runs the requests on a worker of its own.
    DispatchQueue.main.async { thicket_http_start() }
  }
}
