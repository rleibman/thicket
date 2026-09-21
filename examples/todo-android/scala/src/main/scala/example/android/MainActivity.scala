package example.android

import android.app.Activity
import android.os.{Bundle, Process, SystemClock}
import android.util.Log
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
    val tEnter = SystemClock.uptimeMillis()
    super.onCreate(saved)
    val tSuper = SystemClock.uptimeMillis()

    // Android's main thread owns the signal graph; a stray write from elsewhere should
    // fail loudly rather than corrupt it (docs/05 A-06).
    ThreadGuard.install(ThreadGuard.owningThread)
    val tGuard = SystemClock.uptimeMillis()

    val renderer = AndroidRenderer(this)
    val tRenderer = SystemClock.uptimeMillis()

    given Owner = owner
    val tree    = TodoUi(model)
    val tTree   = SystemClock.uptimeMillis()

    val mounted = Reconciler.mount(renderer, tree)
    val tMount  = SystemClock.uptimeMillis()

    val scroll = ScrollView(this)
    scroll.addView(mounted.handle)
    setContentView(scroll)
    val tDone = SystemClock.uptimeMillis()

    // Where the cold-start budget actually goes. `Process.getStartUptimeMillis` is the
    // zygote fork, so "before onCreate" covers class loading and ART's own work.
    val start = Process.getStartUptimeMillis
    Log.i(
      "scalaui",
      f"[startup] process->onCreate ${tEnter - start}%4d ms | super ${tSuper - tEnter}%3d" +
        f" | guard ${tGuard - tSuper}%3d | renderer ${tRenderer - tGuard}%3d" +
        f" | buildTree ${tTree - tRenderer}%3d | mount ${tMount - tTree}%3d" +
        f" | setContentView ${tDone - tMount}%3d | onCreate total ${tDone - tEnter}%3d"
    )

    // An Intent extra rather than an env var: a process launched by `am start` inherits
    // zygote's environment, so sys.env is not a usable channel on Android.
    //   adb shell am start -n <pkg>/<activity> --ez selftest true
    if getIntent != null && getIntent.getBooleanExtra("selftest", false) then
      SelfTest.run(model, mounted.handle)

  override def onDestroy(): Unit =
    owner.dispose()
    super.onDestroy()
