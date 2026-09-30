package example.android

import android.app.Activity
import android.os.{Build, Bundle}
import android.view.{Menu, MenuItem, View, WindowInsets}
import android.window.{OnBackInvokedCallback, OnBackInvokedDispatcher}
import scala.annotation.nowarn
import example.TodoApp
import thicket.core.{Action, ColorRole, NavHost, Reconciler, Rgb, Theme}
import thicket.renderer.ImageSource
import thicket.renderer.android.AndroidRenderer
import thicket.signals.{Owner, Signal, ThreadGuard}

/** The Android host for [[TodoApp]] — the same app the GTK host mounts.
  *
  * Chrome comes from `AppRoot`, applied natively: the action bar title follows the top
  * screen, Up appears when the stack is deep, and the system Back button pops. When the
  * stack is at its root `back()` returns false and the Activity finishes, which is the
  * behaviour Android users expect.
  */
class MainActivity extends Activity {

  private val owner = Owner()
  private val model = TodoApp.Model()
  private var app: NavHost[TodoApp.Route] = null

  override def onCreate(saved: Bundle): Unit = {
    super.onCreate(saved)

    // Android's main thread owns the signal graph; a stray write from elsewhere should
    // fail loudly rather than corrupt it (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)

    // Brand exactly one role and leave the rest to the platform. Buttons pick up the
    // accent; text, dividers, the check box and the action bar keep following the user's
    // Android theme, including dark mode.
    Theme.install(Theme.platform.withColor(ColorRole.Accent, Rgb(0x2E, 0x6F, 0x40)))

    val renderer = AndroidRenderer(this)
    given Owner  = owner

    // The logo lives in the APK's assets, which has no filesystem path —
    // `BitmapFactory.decodeFile` cannot see inside an APK. So the host reads the bytes and
    // hands the app an ImageSource, which is the split TodoApp expects.
    app = TodoApp(model, logo = logoBytes.map(ImageSource.FromBytes(_)))

    // Restore the back stack across process death. It is a List of a route ADT, so this is
    // ordinary serialisation rather than a framework-specific save/restore protocol.
    Option(saved).map(_.getStringArray(StackKey)).foreach { saved =>
      app.navigator.restore(saved.toList.flatMap(TodoApp.parseRoute))

    }
    // No hand-rolled ScrollView any more: the app declares its own `Scroll`.
    val mounted = Reconciler.mount(renderer, app.element)
    setContentView(mounted.handle)
    applySystemInsets(mounted.handle)

    Signal.effect(setTitle(app.title()))
    Signal.effect {
      val up = app.canGoBack()
      Option(getActionBar).foreach(_.setDisplayHomeAsUpEnabled(up))
    }

    // Toolbar actions in the action bar's options menu, which is Android's own place for
    // them. `invalidateOptionsMenu` asks the platform to rebuild it; the actual building
    // happens in onCreateOptionsMenu below, because Android owns that lifecycle rather
    // than letting a caller push items in.
    Signal.effect {
      currentActions = app.actions()
      invalidateOptionsMenu()
    }

    registerBackHandler()

    if getIntent != null && getIntent.getBooleanExtra("selftest", false) then
      SelfTest.run(model, app, mounted.handle)
  }

  /** The actions the current screen declares, read by [[onCreateOptionsMenu]].
    *
    * Held in a field because Android drives menu construction on its own schedule: the app
    * says "the menu changed", and the platform asks for it back when it is ready.
    */
  private var currentActions: Seq[Action] = Nil

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    menu.clear()
    currentActions.zipWithIndex.foreach { (a, i) =>
      val item = menu.add(Menu.NONE, i, i, a.label)
      item.setEnabled(a.enabled)
      // SHOW_AS_ACTION_IF_ROOM, not ALWAYS: Android decides whether an action fits on the
      // bar or belongs in the overflow, and overriding that is how a toolbar ends up
      // clipped on a narrow phone.
      item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    true
  }

  /** Keep content out from under the system bars.
    *
    * `targetSdk` 35+ forces edge-to-edge, so an app draws behind the status bar and the
    * action bar unless it says otherwise — which is why the first rows of this screen were
    * hidden. Padding by the system-bar insets is the platform-correct fix.
    *
    * This belongs in the framework, not in every app: docs/05 F-02 lists safe areas among
    * the behaviours a native renderer is supposed to give for free, and there is no
    * `SafeArea` element yet. Doing it here keeps the demo honest until there is.
    */
  private def applySystemInsets(root: View): Unit =
    if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R then
      root.setOnApplyWindowInsetsListener { (v: View, insets: WindowInsets) =>
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
      }

  /** Android 13+ replaced `onBackPressed` with a dispatcher that also drives the predictive
    * back gesture — the animated peek at the previous screen. Registering here is what makes
    * the app behave like a platform app rather than one that merely intercepts a key.
    */
  private def registerBackHandler(): Unit =
    if Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU then
      getOnBackInvokedDispatcher.registerOnBackInvokedCallback(
        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
        new OnBackInvokedCallback {
          def onBackInvoked(): Unit = if !app.back() then finish()
        }
      )

  override def onSaveInstanceState(out: Bundle): Unit = {
    super.onSaveInstanceState(out)
    out.putStringArray(StackKey, app.navigator.routes.now.map(TodoApp.showRoute).toArray)
  }

  /** Pre-Android-13 fallback; the dispatcher above supersedes it where available. */
  @nowarn("cat=deprecation")
  override def onBackPressed(): Unit =
    if !app.back() then super.onBackPressed()

  /** One handler for both: Android routes the Up arrow and the screen's own actions
    * through the same callback, distinguished by item id. Up is checked first because
    * `android.R.id.home` is a platform id and could otherwise collide with an action
    * index.
    */
  override def onOptionsItemSelected(item: MenuItem): Boolean =
    if item.getItemId == android.R.id.home then {
      val _ = app.back()
      true
    } else
      currentActions.lift(item.getItemId) match {
        case Some(a) => a.onTap(); true
        case None    => super.onOptionsItemSelected(item)
      }

  override def onDestroy(): Unit = {
    owner.dispose()
    super.onDestroy()
  }

  /** The bundled logo, as bytes. `None` rather than a crash if it is missing: a demo that
    * will not start because an image is absent is worse than a demo without a logo.
    */
  private def logoBytes: Option[Array[Byte]] =
    try {
      val in = getAssets.open("thicket-logo.png")
      try Some(in.readAllBytes())
      finally in.close()
    } catch { case _: java.io.IOException => None }

  private val StackKey = "thicket.backstack"
}
