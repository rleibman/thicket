package thicket.core

import thicket.core.dsl.*
import thicket.signals.{Checks, Owner, Var}
import zio.test.*

/** `RemoteData` and `AppRoot` — the two smallest user-facing types in `core`, and the two
  * that coverage found were barely tested: `AppRoot` not at all, `RemoteData` only through
  * the screens that happened to use it.
  *
  * Both are the kind of thing that looks too simple to test right up until a renderer
  * depends on it. `AppRoot.apply` in particular is what every single-screen host mounts.
  */
object RemoteDataSpec extends ZIOSpecDefault {

  private type Rd = RemoteData[String, Int]

  def spec = suite("RemoteData and AppRoot")(
    test("toOption keeps only the loaded value") {
      val chk = Checks()
      chk.eq((RemoteData.Done(1): Rd).toOption, Some(1))
      chk.eq((RemoteData.Loading: Rd).toOption, None)
      chk.eq((RemoteData.Failed("nope"): Rd).toOption, None)
      chk.result
    },
    test("isLoading is true only in flight") {
      val chk = Checks()
      chk.yes((RemoteData.Loading: Rd).isLoading)
      chk.no((RemoteData.Done(1): Rd).isLoading)
      chk.no((RemoteData.Failed("nope"): Rd).isLoading)
      chk.result
    },
    test("map transforms the value and carries the other two cases through") {
      val chk = Checks()
      chk.eq((RemoteData.Done(2): Rd).map(_ * 21), RemoteData.Done(42))
      // The error type is preserved, not widened or swallowed — this is the case a naive
      // `map` implemented as `toOption.map` would silently lose.
      chk.eq((RemoteData.Failed("boom"): Rd).map(_ * 2), RemoteData.Failed("boom"))
      chk.eq((RemoteData.Loading: Rd).map(_ * 2), RemoteData.Loading)
      chk.result
    },
    test("RemoteData(signal) renders one branch per case and swaps as the state moves") {
      val o = Owner(); given Owner = o
      val chk   = Checks()
      val r     = TestRenderer()
      val state = Var[Rd](RemoteData.Loading)

      val m = Reconciler.mount(
        r,
        Column()(RemoteData(state) {
          case RemoteData.Loading   => Label("Loading…")
          case RemoteData.Failed(e) => Label(s"Failed: $e")
          case RemoteData.Done(v)   => Label(s"Got $v")
        })
      )

      def shown = r.childrenOf(m.handle).map(r.text)

      chk.eq(shown, Seq("Loading…"))
      state.set(RemoteData.Done(7))
      chk.eq(shown, Seq("Got 7"))
      // And back again: a retry returns to Loading rather than being a one-way trip.
      state.set(RemoteData.Loading)
      chk.eq(shown, Seq("Loading…"))
      state.set(RemoteData.Failed("offline"))
      chk.eq(shown, Seq("Failed: offline"))

      o.dispose()
      chk.result
    },
    test("AppRoot.apply is a single screen with no navigation") {
      val chk  = Checks()
      val root = AppRoot("Settings", Column()(Label("hello")))

      chk.eq(root.title.now, "Settings")
      // The three things a host asks of an app that has no nav stack. `back()` returning
      // false is what tells the host to defer to the platform — finish the activity, close
      // the window — rather than swallowing the gesture.
      chk.no(root.canGoBack.now, "a single screen has nowhere to go back to")
      chk.eq(root.actions.now, Nil)
      chk.no(root.back(), "back() must defer to the platform")
      chk.result
    },
    test("an AppRoot's element is the one it was given, and it mounts") {
      val o = Owner(); given Owner = o
      val chk  = Checks()
      val r    = TestRenderer()
      val root = AppRoot("Title", Column()(Label("a"), Label("b")))
      val m    = Reconciler.mount(r, root.element)

      chk.eq(r.childrenOf(m.handle).map(r.text), Seq("a", "b"))
      o.dispose()
      chk.result
    }
  ) @@ TestAspect.sequential
}
