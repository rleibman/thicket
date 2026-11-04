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

package thicket.renderer.android

import android.content.Context
import android.view.Gravity
import android.widget.{Button, LinearLayout, TextView}
import thicket.renderer.Steps

/** `WidgetKind.Stepper` on Android, which has no stepper (`docs/12` §12.2a; `NumberPicker` is a scrolling wheel, a
  * different control): a − button, the value and a + button, framework views in a row, as an Android app composes it.
  *
  * The value is kept here, clamped to the range; a press moves it a step and reports it. A value the app writes is
  * shown and not reported back.
  */
final private[android] class StepperView(context: Context) extends LinearLayout(context) {

  private var min = 0.0
  private var max = 100.0
  private var step = 1.0
  private var current = 0.0
  private var onChange: Double => Unit = _ => ()

  private val minus = Button(context)
  private val shown = TextView(context)
  private val plus = Button(context)

  setOrientation(LinearLayout.HORIZONTAL)
  setGravity(Gravity.CENTER_VERTICAL)
  // U+2212, the minus sign, not a hyphen: the label TalkBack reads is the content description.
  minus.setText("−")
  minus.setContentDescription("Decrease")
  plus.setText("+")
  plus.setContentDescription("Increase")
  shown.setGravity(Gravity.CENTER)
  shown.setMinEms(3)
  addView(minus)
  addView(shown)
  addView(plus)
  minus.setOnClickListener(_ => press(-1))
  plus.setOnClickListener(_ => press(1))
  render()

  def setRange(
    lo: Double,
    hi: Double
  ): Unit = {
    min = lo
    max = hi
    current = clamp(current)
    render()
  }

  def setStep(by: Double): Unit = {
    step = if by.isNaN || by.isInfinite || by <= 0.0 then 1.0 else by
    render()
  }

  /** The app's write: shown, not reported. */
  def setValue(v: Double): Unit = {
    current = clamp(v)
    render()
  }

  def value: Double = current

  def setOnValueChange(f: Double => Unit): Unit = onChange = f

  override def setEnabled(enabled: Boolean): Unit = {
    super.setEnabled(enabled)
    render()
  }

  private def press(direction: Int): Unit = {
    val next = clamp(current + direction * step)
    if next != current then {
      current = next
      render()
      onChange(current)
    }
  }

  private def clamp(v: Double): Double = math.max(min, math.min(max, v))

  private def render(): Unit = {
    shown.setText(Steps.format(current, step))
    minus.setEnabled(isEnabled && current > min)
    plus.setEnabled(isEnabled && current < max)
  }

}
