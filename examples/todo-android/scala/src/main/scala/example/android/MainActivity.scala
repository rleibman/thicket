package example.android

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import example.TodoUi
import scalaui.core.Reconciler
import scalaui.renderer.android.AndroidRenderer
import scalaui.signals.{Owner, ThreadGuard}

/** The Android host for [[TodoUi]] — the same element tree the GTK app mounts.
  *
  * The Activity owns the tree's lifetime: `onDestroy` disposes the `Owner`, which stops
  * every effect the tree created. No cleanup callbacks, no leak.
  */
class MainActivity extends Activity:

  private val owner = Owner()
  private val model = TodoUi.Model()

  override def onCreate(saved: Bundle): Unit =
    super.onCreate(saved)

    // Android's main thread owns the signal graph; a stray write from elsewhere should
    // fail loudly rather than corrupt it (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)

    val renderer = AndroidRenderer(this)
    given Owner  = owner
    val mounted  = Reconciler.mount(renderer, TodoUi(model))

    val scroll = ScrollView(this)
    scroll.addView(mounted.handle)
    setContentView(scroll)

    // An Intent extra rather than an env var: a process launched by `am start` inherits
    // zygote's environment, so sys.env is not a usable channel on Android.
    //   adb shell am start -n <pkg>/<activity> --ez selftest true
    if getIntent != null && getIntent.getBooleanExtra("selftest", false) then
      SelfTest.run(model, mounted.handle)

  override def onDestroy(): Unit =
    owner.dispose()
    super.onDestroy()
