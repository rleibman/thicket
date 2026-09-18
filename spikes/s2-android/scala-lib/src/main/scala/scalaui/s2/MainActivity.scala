package scalaui.s2

import android.app.Activity
import android.os.Bundle
import android.view.{Gravity, View}
import android.widget.{Button, LinearLayout, TextView}

import scala.collection.immutable.TreeMap
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Failure, Success, Try}

/** Deliberately exercises the Scala 3 runtime features most likely to break on ART:
  * `lazy val` (scala.runtime.LazyVals uses Unsafe/VarHandle depending on version),
  * `enum`, `opaque type`, `given`, `LazyList`, `TreeMap`, `Future` on the global
  * ExecutionContext, `Ordering`, `Try`, and string interpolation. No reflection.
  */
object Counter:
  opaque type Count = Int
  object Count:
    def zero: Count = 0
    extension (c: Count)
      def inc: Count      = c + 1
      def value: Int      = c

enum Mood:
  case Calm, Curious, Excited

object Mood:
  def forCount(n: Int): Mood =
    if n < 3 then Calm else if n < 7 then Curious else Excited

given Ordering[Mood] = Ordering.by(_.ordinal)

class MainActivity extends Activity:
  import Counter.*

  private var count: Count = Count.zero

  // lazy val: the classic Scala-3-on-ART risk (LazyVals / Unsafe / VarHandle).
  private lazy val fibs: LazyList[BigInt] =
    BigInt(0) #:: BigInt(1) #:: fibs.zip(fibs.tail).map((a, b) => a + b)

  private lazy val moodNames: TreeMap[Mood, String] =
    TreeMap.from(Mood.values.map(m => m -> m.toString))

  private var label: TextView = scala.compiletime.uninitialized

  override def onCreate(saved: Bundle): Unit =
    super.onCreate(saved)

    val root = new LinearLayout(this)
    root.setOrientation(LinearLayout.VERTICAL)
    root.setGravity(Gravity.CENTER)
    root.setPadding(48, 48, 48, 48)

    label = new TextView(this)
    label.setTextSize(20f)
    label.setId(View.generateViewId())
    root.addView(label)

    val button = new Button(this)
    button.setText("Increment")
    button.setId(View.generateViewId())
    button.setOnClickListener { (_: View) =>
      count = count.inc
      render()
    }
    root.addView(button)

    setContentView(root)
    render()
    runAsyncProbe()

  private def render(): Unit =
    val n    = count.value
    val mood = Mood.forCount(n)
    val fib  = Try(fibs(n).toString).getOrElse("?")
    label.setText(
      s"count=$n mood=${moodNames(mood)} fib=$fib sorted=${Mood.values.sorted.mkString(",")}"
    )

  /** Does a Future on the global EC actually run under ART? */
  private def runAsyncProbe(): Unit =
    given ExecutionContext = ExecutionContext.global
    Future((1 to 1000).map(BigInt(_)).sum).onComplete {
      case Success(v) => android.util.Log.i("S2", s"Future completed: $v")
      case Failure(e) => android.util.Log.e("S2", "Future failed", e)
    }
