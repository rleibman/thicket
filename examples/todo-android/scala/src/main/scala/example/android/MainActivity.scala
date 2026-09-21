package example.android

import android.app.Activity
import android.os.{Build, Bundle}
import android.view.MenuItem
import android.window.{OnBackInvokedCallback, OnBackInvokedDispatcher}
import scala.annotation.nowarn
import android.widget.ScrollView
import example.TodoApp
import scalaui.core.Reconciler
import scalaui.renderer.android.AndroidRenderer
import scalaui.signals.{Owner, Signal, ThreadGuard}

/** The Android host for [[TodoApp]] — the same app the GTK host mounts.
  *
  * Chrome comes from `AppRoot`, applied natively: the action bar title follows the top
  * screen, Up appears when the stack is deep, and the system Back button pops. When the
  * stack is at its root `back()` returns false and the Activity finishes, which is the
  * behaviour Android users expect.
  */
class MainActivity extends Activity:

  private val owner = Owner()
  private val model = TodoApp.Model()
  private val app   = TodoApp(model)

  override def onCreate(saved: Bundle): Unit =
    super.onCreate(saved)

    // Android's main thread owns the signal graph; a stray write from elsewhere should
    // fail loudly rather than corrupt it (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)

    val renderer = AndroidRenderer(this)
    given Owner  = owner

    // Restore the back stack across process death. It is a List of a route ADT, so this is
    // ordinary serialisation rather than a framework-specific save/restore protocol.
    Option(saved).map(_.getStringArray(StackKey)).foreach: saved =>
      app.navigator.restore(saved.toList.flatMap(TodoApp.parseRoute))

    val mounted = Reconciler.mount(renderer, app.element)
    val scroll  = ScrollView(this)
    scroll.addView(mounted.handle)
    setContentView(scroll)

    Signal.effect(setTitle(app.title()))
    Signal.effect:
      val up = app.canGoBack()
      Option(getActionBar).foreach(_.setDisplayHomeAsUpEnabled(up))

    registerBackHandler()

    if getIntent != null && getIntent.getBooleanExtra("selftest", false) then
      SelfTest.run(model, app, mounted.handle)

  /** Android 13+ replaced `onBackPressed` with a dispatcher that also drives the predictive
    * back gesture — the animated peek at the previous screen. Registering here is what makes
    * the app behave like a platform app rather than one that merely intercepts a key.
    */
  private def registerBackHandler(): Unit =
    if Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU then
      getOnBackInvokedDispatcher.registerOnBackInvokedCallback(
        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
        new OnBackInvokedCallback:
          def onBackInvoked(): Unit = if !app.back() then finish()
      )

  override def onSaveInstanceState(out: Bundle): Unit =
    super.onSaveInstanceState(out)
    out.putStringArray(StackKey, app.navigator.routes.now.map(TodoApp.showRoute).toArray)

  /** Pre-Android-13 fallback; the dispatcher above supersedes it where available. */
  @nowarn("cat=deprecation")
  override def onBackPressed(): Unit =
    if !app.back() then super.onBackPressed()

  /** The action bar's Up arrow. */
  override def onOptionsItemSelected(item: MenuItem): Boolean =
    if item.getItemId == android.R.id.home then
      val _ = app.back()
      true
    else super.onOptionsItemSelected(item)

  override def onDestroy(): Unit =
    owner.dispose()
    super.onDestroy()

  private val StackKey = "scalaui.backstack"
