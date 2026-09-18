package scalaui.s2k

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.math.BigInteger

/** The Kotlin twin of the Scala activity: same widgets, same work, so the APK-size
 *  and cold-start deltas measure the Scala runtime and nothing else.
 */
enum class Mood { Calm, Curious, Excited }

fun moodFor(n: Int): Mood = if (n < 3) Mood.Calm else if (n < 7) Mood.Curious else Mood.Excited

class MainActivity : Activity() {
    private var count = 0
    private lateinit var label: TextView

    private val fibs: List<BigInteger> by lazy {
        val out = ArrayList<BigInteger>(64)
        var a = BigInteger.ZERO; var b = BigInteger.ONE
        repeat(64) { out.add(a); val t = a.add(b); a = b; b = t }
        out
    }

    private val moodNames: Map<Mood, String> by lazy {
        sortedMapOf<Mood, String>(compareBy { it.ordinal }).apply {
            Mood.entries.forEach { put(it, it.toString()) }
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(48, 48, 48, 48)

        label = TextView(this)
        label.textSize = 20f
        label.id = View.generateViewId()
        root.addView(label)

        val button = Button(this)
        button.text = "Increment"
        button.id = View.generateViewId()
        button.setOnClickListener { count += 1; render() }
        root.addView(button)

        setContentView(root)
        render()

        Thread {
            var sum = BigInteger.ZERO
            for (i in 1..1000) sum = sum.add(BigInteger.valueOf(i.toLong()))
            android.util.Log.i("S2", "Async completed: $sum")
        }.start()
    }

    private fun render() {
        val mood = moodFor(count)
        val fib = if (count < fibs.size) fibs[count].toString() else "?"
        label.text = "count=$count mood=${moodNames[mood]} fib=$fib " +
                "sorted=${Mood.entries.sortedBy { it.ordinal }.joinToString(",")}"
    }
}
