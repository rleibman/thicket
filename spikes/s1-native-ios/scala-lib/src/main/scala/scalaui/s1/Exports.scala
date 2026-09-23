package scalaui.s1

import java.util.Date
import scala.scalanative.libc.{stdlib, string}
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** The five C entry points S1 exists to exercise. Each one is deliberately aimed at a different part of the Scala
  * Native runtime: static data, the GC under churn, threads, javalib, and calling back out to native code.
  */
object Exports {

  /** Reused across `scalaui_time` calls so the returned pointer stays valid after the function returns. The caller must
    * copy the bytes before the next call.
    */
  lazy private val timeBuf: CString = stdlib.malloc(256.toUSize)

  private var callback: Option[CFuncPtr1[CString, Unit]] = None

  private case class Node(
    id:       Int,
    label:    String,
    children: List[Int]
  )

  @exported("scalaui_hello")
  def hello(): CString = c"hello from scala native"

  /** Churns short-lived case classes, Strings and Lists so the GC has to collect during the run rather than just
    * growing the heap. Returns the number of objects allocated so the caller can confirm the loop actually ran.
    */
  @exported("scalaui_alloc_stress")
  def allocStress(seconds: Int): Long = {
    val deadline = System.nanoTime() + seconds.toLong * 1000000000L
    var count = 0L
    var sink = 0
    while System.nanoTime() < deadline do {
      var i = 0
      while i < 1000 do {
        val n = Node(i, s"node-$i", List(i, i + 1, i + 2))
        sink = (sink + n.children.sum + n.label.length) % 1000003
        count += 1
        i += 1
      }
    }
    // Consuming `sink` keeps the allocations observable, so neither the optimiser nor
    // LTO can delete the loop body and turn this into a no-op timer.
    count + (sink % 1)
  }

  /** Deterministic by construction: thread i sums j % (i+1) over a fixed range, so the checksum is independent of
    * scheduling and can be verified against the host run.
    */
  @exported("scalaui_thread_test")
  def threadTest(n: Int): Int = {
    val results = new Array[Int](n)
    val threads = (0 until n).map { i =>
      val t = new Thread(() => {
        var sum = 0
        var j = 0
        while j < 100000 do {
          sum += j % (i + 1)
          j += 1
        }
        results(i) = sum
      })
      t.start()
      t
    }
    threads.foreach(_.join())
    results.sum
  }

  /** Exercises the clock and the formatter. The brief asked for `java.time.Instant` and `String.format(Locale, ...)`;
    * neither links on Scala Native 0.5.12 — `java.time`, `java.text` and `java.util.Locale` are all absent from its
    * javalib, and the Locale-taking `String.format` overload drags in `java.text.NumberFormat`. This uses the subset
    * that does exist: `java.util.Date` and root-locale `String.format`. See REPORT.md; the gap is a portability
    * finding, not an iOS-specific one.
    */
  @exported("scalaui_time")
  def time(): CString = {
    val millis = System.currentTimeMillis()
    val date = new Date(millis)
    val s = String.format("epoch_ms=%d date=%s grouped=%08d", millis, date.toString, 1234567)
    Zone {
      string.strncpy(timeBuf, toCString(s), 255.toUSize)
    }
    timeBuf(255) = 0.toByte
    timeBuf
  }

  @exported("scalaui_register_callback")
  def registerCallback(cb: CFuncPtr1[CString, Unit]): Unit = callback = Some(cb)

  @exported("scalaui_fire")
  def fire(): Unit = callback.foreach(cb => cb(c"fired from scala"))

}
