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
import example.gallery.{Gallery, StorageDemo}
import scala.collection.mutable
import thicket.core.Reconciler
import thicket.core.dsl.*
import thicket.signals.{ErrorSink, Owner}
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

  /** Runs the steps in order, one main-loop turn apart. */
  private def sequence(steps: List[() => Unit]): Unit =
    steps match {
      case Nil          => ()
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
          Log.i(Tag, if failures == 0 then "[gallery] ALL CHECKS PASSED" else s"[gallery] $failures CHECK(S) FAILED")
        }
      )
    )
  }

}
