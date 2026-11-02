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

import java.nio.file.{Files, Path}
import thicket.device.Availability
import thicket.renderer.apple.AppleApp

/** The camera in the Pickers section on AppKit and UIKit (#129).
  *
  *   - **macOS** has no camera screen (decision 7): the host passes `Camera.unavailable(`[[noDesktopCamera]]`)`, as GTK
  *     does, and the section must say so: the GTK host's check.
  *   - **iOS** passes `AppleCamera`, and the host gives this a [[Probe]] of it.
  *     - **With a camera** — and the iOS 27 simulator has one, a simulated grey frame, measured: the build script
  *       grants it with `simctl privacy`, so the section must say it is ready. "Take a photo" must put the camera
  *       screen up; a second request while it is up must answer no photo at once and leave it up; the shutter and
  *       "Done", tapped through XCUITest (`DRIVE photo`), must bring a JPEG (`FF D8 FF`) back in the app's cache,
  *       leaving nothing in the temporary directory; and the X (`DRIVE cancel`) must answer no photo.
  *     - **Without one** (`isSourceTypeAvailable(.camera)` false, as on older simulators): the section must say the
  *       camera is unavailable with that reason, and "Take a photo" must present nothing and answer no photo.
  *
  * The probe is a parameter rather than imported here because this file is shared with macOS, whose link has no
  * `libthicketcamera.a`.
  */
object GalleryCameraChecks {

  type Check = (String, Boolean, String) => Unit

  /** Why the Mac has no camera capture (docs/16 §16.3, decision 7), the GTK host's words. */
  val noDesktopCamera: String = "no camera screen on the desktop"

  /** What the iOS host's camera says of itself: `availability`, `isSourceTypeAvailable(.camera)`, whether its picker is
    * up, `capturePhoto` itself, the app's cache, and the JPEGs the library left in the temporary directory.
    */
  final case class Probe(
    availability:    () => Availability,
    sourceAvailable: () => Boolean,
    presented:       () => Boolean,
    capture:         (Option[Path] => Unit) => Unit,
    cache:           Path,
    leftovers:       () => List[Path]
  )

  private def drive(action: String): Unit = {
    println(s"[gallery] DRIVE $action")
    System.out.flush()
  }

  /** Re-checks `cond` every 16 ms until it holds or `timeoutMs` passes, then continues with the time it took. */
  private def eventually(timeoutMs: Long)(cond: => Boolean)(k: Long => Unit): Unit = {
    val start = System.nanoTime()
    val deadline = start + timeoutMs * 1000000L
    def loop(): Unit =
      if cond || System.nanoTime() > deadline then k((System.nanoTime() - start) / 1000000L)
      else AppleApp.postToUiAfter(16)(() => loop())
    loop()
  }

  /** How long a DRIVE through the XCUITest may take: it waits for the button, and the build script acts on DRIVE lines
    * one at a time, after the earlier ones' taps (each about 8 s, and much longer on a loaded machine).
    */
  private val driveMs = 300000L

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
        if source then withCamera(check, click, texts, demo, camera, availability)(next)
        else withoutCamera(check, click, texts, demo, camera, availability)(next)
    }

  private def withoutCamera(
    check:        Check,
    click:        String => Boolean,
    texts:        () => List[String],
    demo:         PickersDemo,
    camera:       Probe,
    availability: Availability
  )(
    next: () => Unit
  ): Unit = {
    check(
      "the camera says it is unavailable, with UIKit's reason: this device has no camera",
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
  }

  private def withCamera(
    check:        Check,
    click:        String => Boolean,
    texts:        () => List[String],
    demo:         PickersDemo,
    camera:       Probe,
    availability: Availability
  )(
    next: () => Unit
  ): Unit = {
    check(
      "the camera, granted by simctl privacy, is ready, and the section says so",
      availability == Availability.Available && texts().contains(PickersDemo.cameraLabel(availability)),
      s"$availability; ${demo.taken.now}"
    )
    val before = camera.leftovers()
    val clicked = click("Take a photo")
    eventually(if clicked then 10000 else 0)(camera.presented()) { ms =>
      println(s"[gallery] camera: \"Take a photo\" -> the camera screen up in $ms ms")
      check("\"Take a photo\" puts the camera screen up", clicked && camera.presented(), s"clicked $clicked")
      // One at a time: a second request while the first is up answers None, and the first stays up.
      var second: Option[Option[Path]] = None
      camera.capture(p => second = Some(p))
      eventually(5000)(second.isDefined) { _ =>
        check(
          "a second request while the camera is up answers no photo at once, and leaves the camera up",
          second.contains(None) && camera.presented() && demo.lastTaken.isEmpty,
          s"second $second; presented ${camera.presented()}; first ${demo.lastTaken}"
        )
        val started = System.nanoTime()
        drive("photo")
        eventually(driveMs)(demo.lastTaken.isDefined) { _ =>
          println(
            s"[gallery] measured: shutter and Done -> the photo back in ${(System.nanoTime() - started) / 1000000L} ms after the DRIVE line"
          )
          val photo = demo.lastTaken.flatten
          val head = photo.filter(Files.exists(_)).map(p => Files.readAllBytes(p)).getOrElse(Array.emptyByteArray)
          photo.foreach(p => println(s"[gallery] camera: photo $p, ${head.length} bytes"))
          check(
            "the shutter and Done bring a JPEG back, in the app's cache",
            photo.exists(_.startsWith(camera.cache)) && head.length > 3 &&
              (head(0) & 0xff) == 0xff && (head(1) & 0xff) == 0xd8 && (head(2) & 0xff) == 0xff &&
              texts().contains(PickersDemo.takenLabel(photo)) && !camera.presented(),
            s"${demo.lastTaken}; ${head.take(3).map(b => f"${b & 0xff}%02X").mkString(" ")}; ${demo.taken.now}"
          )
          val left = camera.leftovers().filterNot(before.contains)
          check(
            "the JPEG the library wrote to the temporary directory is gone once copied",
            left.isEmpty,
            left.toString
          )
          demo.lastTaken = None
          val again = click("Take a photo")
          eventually(if again then 10000 else 0)(camera.presented()) { _ =>
            val cancelled = System.nanoTime()
            drive("cancel")
            eventually(driveMs)(demo.lastTaken.isDefined) { _ =>
              println(
                s"[gallery] measured: the X -> answered in ${(System.nanoTime() - cancelled) / 1000000L} ms after the DRIVE line"
              )
              check(
                "closing the camera screen with its X answers no photo",
                again && demo.lastTaken.contains(None) && !camera.presented() &&
                  texts().contains(PickersDemo.takenLabel(None)),
                s"clicked $again; ${demo.lastTaken}; presented ${camera.presented()}; ${demo.taken.now}"
              )
              next()
            }
          }
        }
      }
    }
  }

}
