/*
 * Copyright 2026 Roberto Leibman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example.gallery.android

import _root_.android.os.{Handler, Looper, SystemClock}
import _root_.android.util.Log
import _root_.android.view.{View, ViewGroup}
import _root_.android.widget.{Button, EditText, ImageView, Spinner, TextView}
import example.gallery.{DeviceDemo, Gallery, LocationDemo, StorageDemo}
import example.http.HttpChecks
import scala.collection.mutable
import sttp.client4.*
import thicket.http.android.AndroidHttpBackend
import thicket.core.Reconciler
import thicket.core.dsl.*
import thicket.device.{AppState, Availability, Permission}
import thicket.device.android.{AndroidLifecycle, AndroidPermissions}
import thicket.renderer.android.ThicketActivity
import thicket.secrets.android.AndroidSecrets
import thicket.secrets.testing.SecretsConformance
import thicket.signals.{Disposable, ErrorSink, Owner, Signal}
import thicket.location.{Accuracy, Fix, Location, LocationProvider}
import thicket.location.android.AndroidLocationProvider
import thicket.sqlite.Sqlite
import thicket.sqlite.android.AndroidSqlite
import thicket.sqlite.testing.JdbcConformance
import thicket.storage.android.AndroidStorage

/** The gallery's checks on Android, read back out of the real view tree.
  *
  * Not the GTK self-test translated line by line — the toolkits differ — but the same claims where they apply, and in
  * particular every Android fix from the 2026-10-20 review that a unit test cannot see: the Spinner's selection
  * (P1-11), Grow along a Column (P1-4), the LazyColumn re-binding its rows, an effect's result arriving through
  * `UiThread` (P0-5), a throwing handler (P0-3), and spacing that mounts in linear time (P2-7).
  *
  * Steps run one per main-loop turn, `Delay` ms apart, because a view is measured and laid out — and a Spinner reports
  * its selection — on a later turn than the one that changed it.
  */
object GallerySelfTest {

  private val Tag = "thicket"
  private val Delay = 150L
  private var failures = 0
  private val main = Handler(Looper.getMainLooper)

  private def check(
    name:   String,
    cond:   Boolean,
    detail: => String = ""
  ): Unit = {
    if !cond then failures += 1
    Log.i(Tag, s"[gallery] ${if cond then "ok  " else "FAIL"} $name")
    if !cond && detail.nonEmpty then Log.i(Tag, s"[gallery]      $detail")
  }

  private def allTexts(v: View): List[String] =
    v match {
      case e: EditText  => List(e.getText.toString)
      case g: ViewGroup => (0 until g.getChildCount).toList.flatMap(i => allTexts(g.getChildAt(i)))
      case t: TextView  => List(t.getText.toString)
      case _ => Nil
    }

  private def findAll(v: View)(p: View => Boolean): List[View] = {
    val here = if p(v) then List(v) else Nil
    v match {
      case g: ViewGroup => here ++ (0 until g.getChildCount).toList.flatMap(i => findAll(g.getChildAt(i))(p))
      case _ => here
    }
  }

  /** A step that waits, a main-loop turn at a time, until `cond` holds or `ms` have passed; the next step checks. */
  final private class Await(
    val ms:   Long,
    val cond: () => Boolean
  ) extends (() => Unit) {

    def apply(): Unit = ()

  }

  /** Asks `build.sh` to do what only the system or the user can — press Home, bring the app back, answer the permission
    * dialog — by a line in the log it is reading.
    */
  private def drive(action: String): Unit = Log.i(Tag, s"[gallery] DRIVE $action")

  /** Runs the steps in order, one main-loop turn apart. */
  private def sequence(steps: List[() => Unit]): Unit =
    steps match {
      case Nil                => ()
      case (a: Await) :: rest =>
        val deadline = SystemClock.uptimeMillis() + a.ms
        def poll(): Unit =
          if a.cond() || SystemClock.uptimeMillis() >= deadline then sequence(rest)
          else main.postDelayed(() => poll(), 50)
        main.postDelayed(() => poll(), Delay)
      case step :: rest =>
        main.postDelayed(
          () => {
            try step()
            catch { case e: Throwable => check(s"step threw $e", false) }
            sequence(rest)
          },
          Delay
        )
    }

  def run(activity: GalleryActivity): Unit = {
    def screen: View = activity.topPage.getOrElse(activity.getWindow.getDecorView)
    def texts = allTexts(screen)
    def text(prefix: String) = texts.find(_.startsWith(prefix)).getOrElse("")
    def button(label: String) =
      findAll(screen) { case b: Button => b.getText.toString == label; case _ => false }.headOption
    def click(label: String) = button(label).foreach(_.performClick())
    def label(t:     String) = findAll(screen) { case tv: TextView => tv.getText.toString == t; case _ => false }
    def spinner = findAll(screen) { case _: Spinner => true; case _ => false }.headOption.map(_.asInstanceOf[Spinner])
    var reportsBefore = ""
    var entriesBefore = 0
    var notesBefore = -1

    // HTTP: the checks every platform's sttp backend runs (examples/http), here over AndroidHttpBackend against
    // build.sh's server, reached through `adb reverse`. On a worker after the other steps, so its load and its
    // System.gc() cannot disturb their timing; the main thread polls for it rather than blocking.
    val httpPort = activity.getIntent.getIntExtra("httptest", -1)
    val http = HttpChecks(_ => ())
    val httpWorker = new Thread(
      () => {
        val backend = AndroidHttpBackend()
        http.run(httpPort, backend, parked = backend)
      },
      "http-selftest"
    )
    if httpPort > 0 then {
      // Android refuses network on the main thread (StrictMode's NetworkOnMainThreadException), as URLSessionBackend
      // refuses it on Apple: an app that tries finds out at once, not when the network is slow.
      val refused =
        try {
          basicRequest.get(uri"http://127.0.0.1:$httpPort/hello").send(AndroidHttpBackend())
          "sent"
        } catch {
          case e: Throwable =>
            Iterator
              .iterate[Throwable](e)(_.getCause).takeWhile(_ != null).map(_.getClass.getSimpleName).mkString(" < ")
        }
      check(
        "http: a request from the main thread is refused",
        refused.contains("NetworkOnMainThreadException"),
        refused
      )
    }
    def finish(): Unit =
      Log.i(Tag, if failures == 0 then "[gallery] ALL CHECKS PASSED" else s"[gallery] $failures CHECK(S) FAILED")
    def awaitHttp(deadline: Long): Unit =
      if httpWorker.isAlive && SystemClock.uptimeMillis() < deadline then
        main.postDelayed(() => awaitHttp(deadline), 200)
      else {
        check("http: the checks finished within 30 s", !httpWorker.isAlive)
        http.all.foreach { r =>
          check(s"http: ${r.name}", r.passed, r.detail)
          if r.passed then Log.i(Tag, s"[gallery]      ${r.detail}")
        }
        finish()
      }
    Log.i(Tag, "[gallery] reading the catalogue back out of Android")
    sequence(
      List(
        () => {
          List(
            "Label — every text role",
            "Picker — a choice from a fixed list",
            "Layout — Spacer, Grow, Divider, nested Row and Column",
            "Image — the three content fits",
            "Errors — a handler that throws is reported, and the app carries on",
            "Effects — a ZIO effect, its result posted back to the UI thread"
          ).foreach(h => check(s"section is on screen: $h", texts.contains(h)))
          check(
            "a ZIO effect's result reached the screen through UiThread",
            texts.contains(Gallery.effectResult),
            texts.filter(_.startsWith("Load")).toString
          )
        },
        // Device services (docs/16): SharedPreferences, the app's internal directories, and SQLite through
        // Thicket's own JDBC driver over android.database.sqlite, held to the same conformance checks as the
        // JVM's reference driver, all of which it must pass.
        () => {
          Gallery.currentStorage match {
            case None       => check("the Android host provides storage", false)
            case Some(demo) =>
              val storage = AndroidStorage(activity)
              check(
                "the launch counter is on screen and stored in SharedPreferences",
                texts.contains(s"Opened ${demo.opens} times") &&
                  storage.preferences.getLong(StorageDemo.opensKey).contains(demo.opens),
                texts.filter(_.startsWith("Opened")).toString
              )
              check(
                "the data directory is the app's private files directory",
                storage.dirs.data == activity.getFilesDir.toPath,
                storage.dirs.toString
              )
              val results = JdbcConformance.run(AndroidSqlite.driver, storage.dirs.temp.resolve("conformance"))
              check(
                s"JDBC conformance on Thicket's Android driver: all ${results.size} checks pass",
                results.size >= 17 && results.forall(_.passed),
                results.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
              )
              // getObject / setObject: what a library mapping `Any` uses. Android-only, because Scala Native's
              // java.sql has neither, so they cannot be in the shared conformance checks.
              val objects = {
                val c = Sqlite.memory()(using AndroidSqlite.driver)
                try {
                  c.createStatement().executeUpdate("create table t(i integer, r real, s text, b blob, n text)")
                  val p = c.prepareStatement("insert into t values (?, ?, ?, ?, ?)")
                  p.setObject(1, java.lang.Integer.valueOf(7))
                  p.setObject(2, java.lang.Double.valueOf(2.5))
                  p.setObject(3, "text")
                  p.setObject(4, Array[Byte](1, 2))
                  p.setObject(5, null)
                  p.executeUpdate()
                  val rs = c.createStatement().executeQuery("select * from t")
                  rs.next()
                  (1 to 5)
                    .map(rs.getObject(_)).map {
                      case b: Array[Byte] => b.toList
                      case other => other
                    }.toList
                } finally c.close()
              }
              check(
                "getObject and setObject round-trip SQLite's types on Thicket's Android driver",
                objects == List(7L, 2.5, "text", List[Byte](1, 2), null),
                objects.toString
              )
              notesBefore = demo.notes.now.toOption.getOrElse(-1)
              click("Add a note")
          }
        },
        () => (), // the insert runs on the database worker; give it a turn
        () => {
          check(
            "\"Add a note\" writes a row on the worker and the count reaches the screen",
            notesBefore >= 0 && texts.contains(StorageDemo.notesLabel(notesBefore + 1)) &&
              java.nio.file.Files.exists(activity.getFilesDir.toPath.resolve("gallery.db")),
            s"before $notesBefore; " + texts.filter(_.contains("SQLite")).toString
          )
          // Secrets: the Keystore-backed store, the same conformance checks as every other store, and the gallery's
          // token kept between runs — encrypted on disk, so the preferences file never holds it.
          val secrets = AndroidSecrets(activity)
          Gallery.currentStorage.foreach(d => Log.i(Tag, s"[gallery]      this run: ${d.secret.now}"))
          val token = secrets.get(StorageDemo.secretKey)
          val onDisk = activity
            .getSharedPreferences("thicket.secrets", android.content.Context.MODE_PRIVATE)
            .getString(StorageDemo.secretKey, "")
          check(
            "the gallery's secret is on screen as kept or stored, and reads back from the Keystore store",
            (texts.contains(StorageDemo.secretKept) || texts.contains(StorageDemo.secretStored)) &&
              token.exists(_.startsWith(StorageDemo.secretPrefix)),
            texts
              .filter(
                _.contains("ecret")
              ).toString + s"; read back ${token.map(_.take(StorageDemo.secretPrefix.length))}"
          )
          check(
            "the stored secret is ciphertext: the preferences file does not contain it",
            onDisk.nonEmpty && token.exists(t => !onDisk.contains(t)),
            s"${onDisk.length} characters on disk"
          )
          // Each ciphertext is bound to its key (GCM's associated data): copied under another key, it must not
          // decrypt as that key's secret.
          val moved = {
            val raw = activity.getSharedPreferences("thicket.secrets", android.content.Context.MODE_PRIVATE)
            raw.edit().putString("selftest.moved", onDisk).commit()
            try {
              val got = secrets.get("selftest.moved")
              s"read back ${got.map(_.take(StorageDemo.secretPrefix.length))}"
            } catch { case _: thicket.secrets.SecretsException => "refused" }
            finally secrets.delete("selftest.moved")
          }
          check("a stored secret copied under another key does not decrypt", moved == "refused", moved)
          val secretResults = SecretsConformance.run(() => AndroidSecrets(activity), "selftest.")
          check(
            s"secrets conformance on the Android Keystore store: all ${secretResults.size} checks pass",
            secretResults.size == 11 && secretResults.forall(_.passed),
            secretResults.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
          )
        },
        () => {
          // Images: the file paths resolve to the APK's assets, and the bytes decode.
          val images = findAll(screen) { case i: ImageView => i.getDrawable != null; case _ => false }
          check(
            "every image has something to draw, the asset-backed ones included",
            images.size >= 4,
            images.size.toString
          )
        },
        () => {
          // A throwing handler is reported, and the app carries on.
          val seen = mutable.ArrayBuffer.empty[Throwable]
          val previous = ErrorSink.installed
          ErrorSink.install(e => seen += e)
          click("Throw from a handler")
          ErrorSink.install(previous)
          check(
            "a handler's exception reaches ErrorSink",
            seen.map(_.getMessage).toList == List(Gallery.deliberate),
            seen.toString
          )
        },
        () => {
          // Grow along a Column's axis: a grown label has width, and a Spacer in a Column
          // has height. Android used to zero the width of every grown child.
          val grown = label("grown").headOption
          check(
            "a grown label in a Row is laid out wider than zero",
            grown.exists(_.getWidth > 0),
            grown.map(_.getWidth).toString
          )
          val top = label("top").headOption
          val bottom = label("bottom").headOption
          val four = label("four").headOption
          check(
            "a Spacer in a Column pushes the last label down to line up with its taller neighbour",
            (for t <- top; b <- bottom; f <- four yield b.getTop - t.getTop > f.getHeight).contains(true),
            s"top ${top.map(_.getTop)}, bottom ${bottom.map(_.getTop)}"
          )
          reportsBefore = text("The picker reported")
          click("Clear")
        },
        () => {
          check("Clear shows nothing selected", texts.contains("(nothing selected)"), text("Chose"))
          check(
            "and the Spinner shows the blank placeholder, not the first option",
            spinner.exists(s => s.getSelectedItemPosition == 0 && s.getSelectedItem == ""),
            spinner.map(s => s"${s.getSelectedItemPosition}: '${s.getSelectedItem}'").toString
          )
          click("Select last")
        },
        () => {
          check("an app write selects the option", texts.contains("Chose Cherry (index 2)"), text("Chose"))
          check(
            "and the placeholder has gone",
            spinner.exists(s => s.getCount == 3 && s.getSelectedItem == "Cherry"),
            spinner.map(s => s"${s.getCount} items, '${s.getSelectedItem}'").toString
          )
          click("Swap the options")
        },
        () => {
          check("swapping the options keeps the selection", texts.contains("Chose Pea (index 2)"), text("Chose"))
          check(
            "and none of the app's writes came back as a user choice",
            text("The picker reported") == reportsBefore,
            s"$reportsBefore -> ${text("The picker reported")}"
          )
          click("Swap the options")
        },
        () => {
          // Spacing mounts in linear time: twice the children, about twice the time. It was
          // quadratic, every insert rewriting every sibling's params.
          def mountMs(n: Int): Double = {
            val o = Owner()
            given Owner = o
            val t0 = SystemClock.elapsedRealtimeNanos()
            val m = Reconciler.mount(activity.renderer0, Column(spacing = 4)(Seq.tabulate(n)(i => Label(s"r$i"))*))
            val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            m.dispose()
            o.dispose()
            ms
          }
          val _ = mountMs(500) // warm-up
          val small = mountMs(1000)
          val large = mountMs(2000)
          Log.i(Tag, f"[gallery] info mounting 1000 spaced rows: $small%.1f ms, 2000: $large%.1f ms")
          check(
            "mounting spaced rows scales linearly (2000 rows < 3x 1000)",
            large < small * 3,
            f"$small%.1f -> $large%.1f ms"
          )
          entriesBefore = activity.renderer0.tableEntries
          Gallery.currentNav.foreach(_.push(Gallery.Route.Big))
        },
        () => {
          check("the big list is on top", activity.pageCount == 2, activity.pageCount.toString)
          check(
            "its first row is Row 0",
            texts.contains("Row 0"),
            texts.filter(_.startsWith("Row")).take(3).toString
          )
          click("Delete the first row")
        },
        // The list re-binds on a later layout pass, which on a slow emulator can be more than one turn away.
        Await(3000, () => texts.find(t => t.startsWith("Row ") || t.startsWith("Renamed ")).contains("Row 1")),
        () => {
          val rows = texts.filter(t => t.startsWith("Row ") || t.startsWith("Renamed "))
          check(
            "deleting row 0 re-binds the rows on screen",
            rows.headOption.contains("Row 1"),
            rows.take(3).toString
          )
          click("Rename the first row")
        },
        () => {
          val rows = texts.filter(t => t.startsWith("Row ") || t.startsWith("Renamed "))
          check(
            "an edit that keeps the count reaches the row",
            rows.headOption.contains("Renamed 1"),
            rows.take(3).toString
          )
          Gallery.currentNav.foreach(_.pop())
        },
        () => {
          check("back pops to the catalogue", activity.pageCount == 1, activity.pageCount.toString)
          check(
            "and the popped page's widgets leave nothing in the renderer's tables",
            activity.renderer0.tableEntries == entriesBefore,
            s"$entriesBefore before the push, ${activity.renderer0.tableEntries} after the pop"
          )
        }
      ) ++ deviceSteps(activity, () => texts, click) ++ locationSteps(activity, () => texts) ++ List(() => {
        if httpPort > 0 then {
          httpWorker.start()
          awaitHttp(SystemClock.uptimeMillis() + 30000)
        } else {
          check("http: build.sh passed the test server's port (--ei httptest)", false)
          finish()
        }
      })
    )
  }

  /** Lifecycle and permissions (docs/16 §16.10 item 3), with `build.sh` as the user: Home sends the app to the
    * background and an `am start` brings the same Activity back; the system's permission dialog is answered "Don't
    * allow", then "Allow". `build.sh` revokes the permission before the run, so the dialog is shown each time, and
    * after the verdict it presses Back and looks for the terminate hook's line.
    */
  private def deviceSteps(
    activity: GalleryActivity,
    texts:    () => List[String],
    click:    String => Unit
  ): List[() => Unit] = {
    val notifications = "android.permission.POST_NOTIFICATIONS"
    def granted = activity.checkSelfPermission(notifications) == android.content.pm.PackageManager.PERMISSION_GRANTED
    def demo = Gallery.currentDevice
    def answer = demo.map(_.notifications.now).getOrElse("")
    var askedAt = ""
    var sawBackground = false
    val watch = Owner()
    List(
      () => {
        check("the Android host provides lifecycle and permissions", demo.isDefined)
        check(
          "the app is in the foreground, and the section says so",
          AndroidLifecycle.state.now == AppState.Foreground &&
            texts().contains(DeviceDemo.stateLabel(AppState.Foreground)) &&
            texts().contains(DeviceDemo.returnsLabel(0)),
          s"${AndroidLifecycle.state.now}; " + texts().filter(t => t.startsWith("The app") || t.startsWith("Back"))
        )
        check(
          "notifications start Denied: build.sh revoked them",
          !granted && answer == "Notifications: Denied",
          s"granted $granted; $answer"
        )
        val undeclared = scala.util.Try(AndroidPermissions(activity).status(Permission.Camera))
        check(
          "a permission the manifest does not declare throws, naming it, instead of answering Denied",
          undeclared.failed.toOption.exists(e =>
            e.isInstanceOf[IllegalStateException] && e.getMessage.contains("android.permission.CAMERA")
          ),
          undeclared.toString
        )
        val _ = AndroidLifecycle.onTerminate(() => Log.i(Tag, "[gallery] TERMINATED: the terminate hook ran"))
        drive("home")
      },
      Await(10000, () => AndroidLifecycle.state.now == AppState.Background),
      () => {
        check(
          "Home puts the app in the background, the Activity stopped but not destroyed",
          AndroidLifecycle.state.now == AppState.Background && !activity.isDestroyed &&
            texts().contains(DeviceDemo.stateLabel(AppState.Background)),
          s"${AndroidLifecycle.state.now}, destroyed ${activity.isDestroyed}"
        )
        drive("front")
      },
      Await(10000, () => AndroidLifecycle.state.now == AppState.Foreground),
      () => {
        check(
          "bringing it back puts the same Activity in the foreground, counted once",
          AndroidLifecycle.state.now == AppState.Foreground && ThicketActivity.current.contains(activity) &&
            texts().contains(DeviceDemo.returnsLabel(1)),
          s"${AndroidLifecycle.state.now}, same Activity ${ThicketActivity.current.contains(activity)}; " +
            texts().filter(_.startsWith("Back"))
        )
        // A dialog over the app pauses the Activity but does not stop it: the app stays in the foreground.
        Signal.effect { if AndroidLifecycle.state() == AppState.Background then sawBackground = true }(using watch)
        click("Ask for notifications")
        askedAt = answer
        drive("deny")
      },
      Await(15000, () => answer != DeviceDemo.asking),
      () => {
        check(
          "\"Don't allow\" in the system's dialog answers Denied, on screen",
          askedAt == DeviceDemo.asking && answer == "Notifications: Denied" &&
            texts().contains("Notifications: Denied") && !granted,
          s"while asking: $askedAt; then: $answer; granted $granted"
        )
        click("Ask for notifications")
        drive("allow")
      },
      Await(15000, () => answer != DeviceDemo.asking),
      () => {
        check(
          "asking again shows the dialog again, and \"Allow\" answers Granted",
          answer == "Notifications: Granted" && texts().contains("Notifications: Granted") && granted,
          s"$answer; granted $granted"
        )
        check("the app stayed in the foreground under the dialogs", !sawBackground)
        watch.dispose()
      }
    )
  }

  /** Location (docs/16 §16.10 item 4), with `build.sh` moving the emulator: `DRIVE geo <lon> <lat>` is
    * `adb emu geo fix`, which the GPS provider reports. `build.sh` installs with every permission granted, location
    * among them.
    *
    * Android throttles location for an app in the background on its own, so a section that stops changing there would
    * pass even if Thicket kept listening. The listening itself is counted instead, through a provider that wraps the
    * real one under the real lifecycle.
    */
  private def locationSteps(
    activity: GalleryActivity,
    texts:    () => List[String]
  ): List[() => Unit] = {
    def demo = Gallery.currentLocation
    def position = demo.map(_.position.now).getOrElse("")
    val paris = LocationDemo.positionLabel(Fix(48.8584, 2.2945, None, None, 0L))
    val sydney = LocationDemo.positionLabel(Fix(-33.8568, 151.2153, None, None, 0L))
    var listening = 0
    val real = AndroidLocationProvider(activity, AndroidPermissions(activity))
    val counted = new LocationProvider {
      def availability: Availability = real.availability
      def start(
        accuracy: Accuracy,
        onFix:    Fix => Unit
      ): Disposable = {
        listening += 1
        val d = real.start(accuracy, onFix)
        var stopped = false
        () =>
          if !stopped then {
            stopped = true
            listening -= 1
            d.dispose()
          }
      }
      def last(accuracy: Accuracy): Option[Fix] = real.last(accuracy)
    }
    val probe = Owner()
    List(
      () => {
        check(
          "the Android host provides a Location, available: build.sh installs with location granted",
          demo.isDefined && texts().contains(LocationDemo.availabilityLabel(Availability.Available)),
          texts().filter(_.startsWith("Location")).toString
        )
        val _ = Location(counted, AndroidLifecycle).updates(Accuracy.Fine)(using probe)
        drive("geo 2.2945 48.8584")
      },
      Await(20000, () => position == paris),
      () => {
        check(
          "a position sent to the emulator's GPS reaches the section",
          position == paris && texts().contains(paris),
          position
        )
        check("in the foreground, Thicket listens to the provider", listening == 1, s"$listening listening")
        drive("home")
      },
      Await(10000, () => AndroidLifecycle.state.now == AppState.Background),
      () => {
        check("in the background, Thicket stops listening", listening == 0, s"$listening listening")
        drive("geo 151.2153 -33.8568")
      },
      // Time for a report to arrive, were anyone listening.
      Await(3000, () => false),
      () => {
        check("and a position sent there does not reach the section", position == paris, position)
        drive("front")
      },
      Await(10000, () => AndroidLifecycle.state.now == AppState.Foreground),
      Await(20000, () => position == sydney),
      () => {
        check(
          "back in front, it listens again and shows where the device went meanwhile",
          listening == 1 && position == sydney && texts().contains(sydney),
          s"$listening listening; $position"
        )
        probe.dispose()
        check("disposing the owner stops it", listening == 0, s"$listening listening")
      }
    )
  }

}
