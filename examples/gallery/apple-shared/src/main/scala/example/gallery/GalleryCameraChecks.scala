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

package example.gallery

import thicket.device.Availability
import thicket.renderer.apple.AppleApp

/** The camera in the Pickers section on AppKit and UIKit (#129).
  *
  *   - **macOS** has no camera screen (decision 7): the host passes `Camera.unavailable(`[[noDesktopCamera]]`)`, as GTK
  *     does, and the section must say so: the GTK host's check.
  *   - **iOS** passes `AppleCamera`, and the host gives this a [[Probe]] of it. The simulator has no camera, so
  *     `UIImagePickerController.isSourceTypeAvailable(.camera)` is false: the section must say the camera is
  *     unavailable with that reason, and "Take a photo" must present nothing and answer no photo. On a device with a
  *     camera the section must show what `availability` says; taking a photo there needs a person, and is not driven.
  *
  * The probe is a parameter rather than imported here because this file is shared with macOS, whose link has no
  * `libthicketcamera.a`.
  */
object GalleryCameraChecks {

  type Check = (String, Boolean, String) => Unit

  /** Why the Mac has no camera capture (docs/16 §16.3, decision 7), the GTK host's words. */
  val noDesktopCamera: String = "no camera screen on the desktop"

  /** What the iOS host's camera says of itself: `availability`, `isSourceTypeAvailable(.camera)`, and whether its
    * picker is up.
    */
  final case class Probe(
    availability:    () => Availability,
    sourceAvailable: () => Boolean,
    presented:       () => Boolean
  )

  /** Re-checks `cond` every 16 ms until it holds or `timeoutMs` passes, then continues with the time it took. */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  def run(
    check: Check,
    click: String => Boolean,
    texts: () => List[String],
    probe: Option[Probe]
  )(
    next: () => Unit
  ): Unit =
    (Gallery.currentPickers, probe) match {
      case (None, _) =>
        check("the Apple host provides a camera", false, "Gallery.currentPickers is None")
        next()
      case (Some(demo), None) =>
        check(
          "the camera says the desktop has none to take a photo with",
          texts().contains(PickersDemo.cameraLabel(Availability.Unavailable(noDesktopCamera))),
          demo.taken.now
        )
        next()
      case (Some(demo), Some(camera)) =>
        val source = camera.sourceAvailable()
        val availability = camera.availability()
        println(s"[gallery] camera: isSourceTypeAvailable(.camera) $source, availability $availability")
        if !source then {
          check(
            "the camera says it is unavailable, with UIKit's reason: the simulator has no camera",
            availability == Availability.Unavailable("This device has no camera") &&
              texts().contains(PickersDemo.cameraLabel(availability)),
            s"$availability; ${demo.taken.now}"
          )
          val clicked = click("Take a photo")
          eventually(if clicked then 5000 else 0)(demo.lastTaken.isDefined) { ms =>
            println(s"[gallery] camera: \"Take a photo\" answered in $ms ms")
            check(
              "\"Take a photo\" with no camera presents nothing and answers no photo",
              clicked && demo.lastTaken.contains(None) && !camera.presented() &&
                texts().contains(PickersDemo.takenLabel(None)),
              s"clicked $clicked; ${demo.lastTaken}; presented ${camera.presented()}; ${demo.taken.now}"
            )
            next()
          }
        } else {
          check(
            "the camera section shows the camera's availability",
            texts().contains(PickersDemo.cameraLabel(availability)),
            s"$availability; ${demo.taken.now}"
          )
          println("[gallery] SKIP taking a photo needs a person at the camera; it is not driven here (#129)")
          next()
        }
    }

}
