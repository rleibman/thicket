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
  *       screen up; a second request while it is up must answer no photo at once and leave it up; its X, tapped through
  *       XCUITest (`DRIVE cancel`), must answer no photo and take it down. Then the shutter and "Use Photo" (`DRIVE
  *       photo`): a camera that captures must bring a JPEG (`FF D8 FF`) back in the app's cache, leaving nothing in the
  *       temporary directory. The simulator's shutter takes nothing (measured): the camera must then answer nothing and
  *       stay up until its X, and the run says the copy was not measured.
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

  /** How long the shutter is given to bring a photo back before it is taken to have taken none. */
  private val shutterMs = 60000L

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
    // Opens the camera screen and continues once it is up, or after 10 s.
    def open(k: Boolean => Unit): Unit = {
      demo.lastTaken = None
      val clicked = click("Take a photo")
      eventually(if clicked then 10000 else 0)(camera.presented()) { ms =>
        println(s"[gallery] camera: \"Take a photo\" -> the camera screen up in $ms ms")
        k(clicked && camera.presented())
      }
    }
    // Closes it with its X, through XCUITest, and checks the answer.
    def cancel(name: String)(k: () => Unit): Unit = {
      val started = System.nanoTime()
      drive("cancel")
      eventually(driveMs)(demo.lastTaken.isDefined && !camera.presented()) { _ =>
        println(
          s"[gallery] measured: the X -> answered in ${(System.nanoTime() - started) / 1000000L} ms after the DRIVE line"
        )
        check(
          name,
          demo.lastTaken.contains(None) && !camera.presented() && texts().contains(PickersDemo.takenLabel(None)),
          s"${demo.lastTaken}; presented ${camera.presented()}; ${demo.taken.now}"
        )
        k()
      }
    }
    val before = camera.leftovers()
    open { up =>
      check("\"Take a photo\" puts the camera screen up", up, s"presented ${camera.presented()}")
      // One at a time: a second request while the first is up answers None, and the first stays up.
      var second: Option[Option[Path]] = None
      camera.capture(p => second = Some(p))
      eventually(5000)(second.isDefined) { _ =>
        check(
          "a second request while the camera is up answers no photo at once, and leaves the camera up",
          second.contains(None) && camera.presented() && demo.lastTaken.isEmpty,
          s"second $second; presented ${camera.presented()}; first ${demo.lastTaken}"
        )
        cancel("closing the camera screen with its X answers no photo, and takes it down") { () =>
          open { again =>
            val started = System.nanoTime()
            // The shutter, then "Use Photo" on the review screen if one comes up.
            drive("photo")
            eventually(shutterMs)(demo.lastTaken.isDefined) { _ =>
              val ms = (System.nanoTime() - started) / 1000000L
              demo.lastTaken match {
                case Some(photo) =>
                  println(s"[gallery] measured: the shutter -> answered in $ms ms after the DRIVE line")
                  val bytes = photo.filter(Files.exists(_)).map(Files.readAllBytes(_)).getOrElse(Array.emptyByteArray)
                  photo.foreach(p => println(s"[gallery] camera: photo $p, ${bytes.length} bytes"))
                  check(
                    "the shutter brings a JPEG back, in the app's cache",
                    again && photo.exists(_.startsWith(camera.cache)) && bytes.length > 3 &&
                      (bytes(0) & 0xff) == 0xff && (bytes(1) & 0xff) == 0xd8 && (bytes(2) & 0xff) == 0xff &&
                      texts().contains(PickersDemo.takenLabel(photo)) && !camera.presented(),
                    s"$photo; ${bytes.take(3).map(b => f"${b & 0xff}%02X").mkString(" ")}; ${demo.taken.now}"
                  )
                  val left = camera.leftovers().filterNot(before.contains)
                  check(
                    "the JPEG the library wrote to the temporary directory is gone once copied",
                    left.isEmpty,
                    s"$left"
                  )
                  next()
                case None =>
                  // Measured on the iOS 27 simulator: its camera screen is a grey frame whose shutter takes nothing.
                  println(
                    s"[gallery] SKIP the shutter took no photo in $ms ms: this camera captures nothing (the simulator's " +
                      "does not). Copying the photo into the cache is not measured on this run."
                  )
                  check(
                    "a shutter that takes nothing answers nothing, and leaves the camera screen up",
                    again && demo.lastTaken.isEmpty && camera.presented(),
                    s"${demo.lastTaken}; presented ${camera.presented()}"
                  )
                  cancel("the X then answers no photo")(next)
              }
            }
          }
        }
      }
    }
  }

}
