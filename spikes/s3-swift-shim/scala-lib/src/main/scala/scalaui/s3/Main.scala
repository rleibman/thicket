package scalaui.s3

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scalaui.s3.generated.aliases.*
import scalaui.s3.generated.functions.*
import scalaui.s3.generated.structs.*

/** The Scala half of S3: builds real UIKit widgets through the Swift shim, wires taps back into Scala closures, and
  * measures the round trip.
  */
object Main {

  private var label:    sui_handle = null.asInstanceOf[sui_handle]
  private var tapCount: Int = 0
  private val report = scala.collection.mutable.ArrayBuffer.empty[String]

  private def setLabel(text: String): Unit = Zone(sui_label_set_text(label, toCString(text)))

  // A rolling window sized to what the label's fixed frame can actually show. Too large a
  // window is indistinguishable from a hang: UILabel truncates at the bottom, so new lines
  // are appended off-screen and the display stops changing.
  private def render(): Unit = setLabel(report.takeRight(13).mkString("\n"))

  private def say(line: String): Unit = {
    // Also to stdout: the label is the demo, but a screenshot is a poor way to read
    // numbers back, and simctl --console-pty gives the exact text.
    println(s"[S3] $line")
    report += line
    render()
  }

  // ---------------------------------------------------------------- UI construction

  @exported("scalaui_main")
  def scalaui_main(rootPtr: Ptr[Byte]): Unit = GcState.guarded(buildUi(rootPtr))

  private def buildUi(rootPtr: Ptr[Byte]): Unit = {
    val root = rootPtr.asInstanceOf[sui_handle]

    label = sui_label_new()
    sui_view_set_frame(label, 16.0, 80.0, 360.0, 460.0)
    sui_view_add_child(root, label)

    val countButton = sui_button_new()
    Zone(sui_button_set_title(countButton, toCString("tap me (Scala closure)")))
    sui_view_set_frame(countButton, 16.0, 560.0, 360.0, 44.0)
    sui_view_add_child(root, countButton)

    val threadButton = sui_button_new()
    Zone(sui_button_set_title(threadButton, toCString("update from background thread")))
    sui_view_set_frame(threadButton, 16.0, 610.0, 360.0, 44.0)
    sui_view_add_child(root, threadButton)

    // The closures below are the whole point: C cannot hold them, so each is registered in
    // the handle table and reached through one static trampoline keyed by id.
    val countId = Handles.register { () =>
      tapCount += 1
      say(s"tap #$tapCount handled in Scala")
    }
    sui_button_on_tap(countButton, sui_tap_cb(Handles.tapTrampoline), countId)

    val threadId = Handles.register(() => startBackgroundUpdate())
    sui_button_on_tap(threadButton, sui_tap_cb(Handles.tapTrampoline), threadId)

    say("S3 — Scala drives UIKit through a Swift shim")
    say(s"handles live in shim: ${sui_live_handle_count()}")
    probeStructReturn()
    runBenchmarks(countButton)

    // simctl cannot tap, so drive the real UIKit event path programmatically: this goes
    // touch-event -> UIControl -> ObjC target/action -> C fn ptr -> trampoline -> closure,
    // which is strictly more than sui_simulate_taps proves.
    sui_button_send_ui_action(countButton)
    sui_button_send_ui_action(countButton)

    // And the reverse direction, auto-started for the same reason.
    startBackgroundUpdate()
    startSoak()
  }

  // ------------------------------------------------------- reverse direction: threads

  /** The brief's reverse test, and the one place S1's threading rule really bites: the work happens on a Scala-created
    * thread (never a GCD queue, which would segfault in the GC allocator), and the UI touch is posted back to the main
    * thread through the shim.
    */
  private def startBackgroundUpdate(): Unit = {
    val t = new Thread(() => {
      val computed = (1 to 200000).map(i => i % 7).sum
      val postId = Handles.register { () =>
        say(s"background thread → main thread OK (sum=$computed)")
      }
      sui_run_on_main(sui_main_cb(Handles.mainTrampoline), postId)
    })
    t.setName("s3-background")
    t.start()
    say("background Scala thread started…")
  }

  /** The stop-the-world deadlock only shows up when a background Scala thread triggers a collection while the main
    * thread is parked in CFRunLoop, so one successful post proves nothing. This forces many collections from a
    * background thread across a period where the main thread is mostly idle, and reports progress through the shim so a
    * stall is visible on screen rather than merely absent from a log.
    */
  private def startSoak(): Unit = {
    val rounds = 400
    val t = new Thread(() => {
      var delivered = 0
      var round = 0
      while round < rounds do {
        // Enough churn per round to force the GC to run repeatedly.
        val garbage = (1 to 20000).map(i => s"g$i").filter(_.length > 3).size
        round += 1
        if round % 50 == 0 then {
          val r = round
          val postId = Handles.register { () =>
            delivered += 1
            say(
              s"soak round $r/$rounds delivered (garbage=$garbage, RSS=${f"${sui_rss_mb()}%.1f"} MB)"
            )
          }
          sui_run_on_main(sui_main_cb(Handles.mainTrampoline), postId)
        }
      }
      val donePost = Handles.register(() => say(s"SOAK COMPLETE: $rounds rounds, no GC stall"))
      sui_run_on_main(sui_main_cb(Handles.mainTrampoline), donePost)
    })
    t.setName("s3-soak")
    t.start()
  }

  // ------------------------------------------------------------- the S4 struct probe

  /** S4 measured this on x86-64 SysV and found the fields came back shifted. arm64 AAPCS64 returns a two-double struct
    * in v0/v1 rather than packed, so it has to be re-measured here before the shim's "no structs by value" rule can be
    * called justified or paranoid.
    */
  private def probeStructReturn(): Unit = {
    val byValue: CFuncPtr1[Long, sui_size] = CFuncPtr1.fromScalaFunction { (_: Long) =>
      val s = stackalloc[sui_size]()
      (!s).width = 111.0
      (!s).height = 222.0
      !s
    }
    val outParam: CFuncPtr2[Long, Ptr[sui_size], Unit] =
      CFuncPtr2.fromScalaFunction {
        (
          _:   Long,
          out: Ptr[sui_size]
        ) =>
          (!out).width = 111.0
          (!out).height = 222.0
      }

    Zone {
      val r1 = sui_size()
      sui_probe_struct_byvalue(sui_measure_byvalue_cb(byValue), 0L, r1)
      val ok1 = (!r1).width == 111.0 && (!r1).height == 222.0
      say(f"struct by value : ${(!r1).width}%.1f x ${(!r1).height}%.1f ${if ok1 then "OK" else "WRONG (S4 bug)"}")

      val r2 = sui_size()
      sui_probe_struct_outparam(sui_measure_outparam_cb(outParam), 0L, r2)
      val ok2 = (!r2).width == 111.0 && (!r2).height == 222.0
      say(f"struct out-param: ${(!r2).width}%.1f x ${(!r2).height}%.1f ${if ok2 then "OK" else "WRONG"}")
    }
  }

  // ------------------------------------------------------------------- benchmarks

  private def runBenchmarks(button: sui_handle): Unit = {
    val n = 100000

    // Two variants, because the difference is the actionable number: the first is the raw
    // C-ABI + UIKit cost, the second adds the Scala String -> CString encoding a real
    // framework would pay on every set.
    val pre = Zone {
      val cs = toCString("benchmark")
      val t0 = System.nanoTime()
      var i = 0
      while i < n do {
        sui_label_set_text(label, cs)
        i += 1
      }
      System.nanoTime() - t0
    }
    say(f"set_text x$n (pre-encoded): ${pre.toDouble / n}%.0f ns/call")

    val encoded = {
      val t0 = System.nanoTime()
      var i = 0
      while i < n do {
        Zone(sui_label_set_text(label, toCString(s"row $i")))
        i += 1
      }
      System.nanoTime() - t0
    }
    say(f"set_text x$n (+Zone encode): ${encoded.toDouble / n}%.0f ns/call")

    // Round trip: Swift invokes the stored callback, which lands in the trampoline, which
    // looks the closure up in the handle table and runs it. That is the full N-05 path.
    val before = sui_rss_mb()
    tapCount = 0
    val benchId = Handles.register(() => tapCount += 1)
    sui_button_on_tap(button, sui_tap_cb(Handles.tapTrampoline), benchId)
    val t0 = System.nanoTime()
    sui_simulate_taps(button, n)
    val elapsed = System.nanoTime() - t0
    val after = sui_rss_mb()
    say(f"tap round-trip x$n: ${elapsed.toDouble / n}%.0f ns/tap (target <= 5000)")
    say(f"taps delivered: $tapCount${if tapCount == n then " OK" else " MISMATCH"}")
    say(f"RSS $before%.2f -> $after%.2f MB (delta ${after - before}%+.2f)")

    // Restore the interactive handler the benchmark displaced.
    Handles.release(benchId)
    tapCount = 0
    val countId = Handles.register { () =>
      tapCount += 1
      say(s"tap #$tapCount handled in Scala")
    }
    sui_button_on_tap(button, sui_tap_cb(Handles.tapTrampoline), countId)
    say(s"handle table entries: ${Handles.count}")
  }

}
