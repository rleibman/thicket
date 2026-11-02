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

import thicket.device.{AppState, Availability, Permission, PermissionStatus}
import thicket.device.apple.{AppleDevice, AppleLifecycle, ApplePermissions}
import thicket.location.{Accuracy, Fix, Location, LocationProvider}
import thicket.location.apple.AppleLocationProvider
import thicket.renderer.apple.AppleApp
import thicket.signals.{Disposable, Owner, Signal}

/** The Location section on AppKit and UIKit (#118): the Android gallery's `locationSteps` on iOS, and the GTK host's
  * check on macOS, which has no location service yet.
  *
  * On iOS the build script is the user, as for the Device section: it grants location with `simctl privacy` before the
  * launch, and `[gallery] DRIVE geo <lat>,<lon>` is `simctl location set`. iOS stops delivering positions to an app in
  * the background without background modes, so a section that stops changing there would pass even if Thicket kept
  * listening. The listening is counted instead: through a provider wrapping the real one under the real lifecycle, as
  * on Android, and in `libthicketdevice.a`'s own listener table, which is what keeps `CLLocationManager` updating.
  */
object GalleryLocationChecks {

  type Check = (String, Boolean, String) => Unit

  /** Why the Mac has no location: CoreLocation on the desktop is not wired up yet (docs/16 §16.9, decision 7). The GTK
    * host's words, so the section reads the same.
    */
  val noLocation: String = "no location service on the desktop yet"

  private def ios: Boolean = AppleApp.renderer.platform == "uikit"

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

  private def lc: AppState = AppleLifecycle.state.now

  def run(
    check: Check,
    click: String => Boolean,
    texts: () => List[String]
  )(
    next: () => Unit
  ): Unit =
    Gallery.currentLocation match {
      case None =>
        check("the Apple host provides a Location", false, "Gallery.currentLocation is None")
        next()
      case Some(demo) =>
        if ios then iosSteps(check, texts, demo)(next)
        else {
          // As on GTK: the section says why, "Use my location" changes nothing, and no position arrives.
          val clicked = click("Use my location")
          val unavailable = LocationDemo.availabilityLabel(Availability.Unavailable(noLocation))
          val t = texts()
          check(
            "location says it is unavailable on the desktop, and asking for it changes nothing",
            clicked && t.contains(unavailable) && t.contains(LocationDemo.waiting) &&
              demo.position.now == LocationDemo.waiting,
            s"clicked $clicked; " + t.filter(s => s.startsWith("Location") || s.startsWith("Position"))
          )
          next()
        }
    }

  private def iosSteps(
    check: Check,
    texts: () => List[String],
    demo:  LocationDemo
  )(
    next: () => Unit
  ): Unit = {
    def position = demo.position.now
    def native = AppleDevice.testing.locationListeners
    val paris = LocationDemo.positionLabel(Fix(48.8584, 2.2945, None, None, 0L))
    val sydney = LocationDemo.positionLabel(Fix(-33.8568, 151.2153, None, None, 0L))
    var listening = 0
    val counted = new LocationProvider {
      def availability: Availability = AppleLocationProvider.availability
      def start(
        accuracy: Accuracy,
        onFix:    Fix => Unit
      ): Disposable = {
        listening += 1
        val d = AppleLocationProvider.start(accuracy, onFix)
        var stopped = false
        () =>
          if !stopped then {
            stopped = true
            listening -= 1
            d.dispose()
          }
      }
      def last(accuracy: Accuracy): Option[Fix] = AppleLocationProvider.last(accuracy)
    }
    check(
      "the iOS host provides a Location, available: the build script granted it with simctl privacy",
      ApplePermissions.status(Permission.Location) == PermissionStatus.Granted &&
        texts().contains(LocationDemo.availabilityLabel(Availability.Available)),
      s"${ApplePermissions.status(Permission.Location)}; " + texts().filter(_.startsWith("Location"))
    )
    // The request path, requestWhenInUseAuthorization: already answered, iOS shows nothing.
    var asked = Option.empty[PermissionStatus]
    ApplePermissions.request(Permission.Location)(s => asked = Some(s))
    val synchronous = asked
    eventually(5000)(asked.isDefined) { _ =>
      check(
        "asking for location, granted, answers Granted on a later turn, with no alert",
        synchronous.isEmpty && asked.contains(PermissionStatus.Granted),
        s"right after the request $synchronous; then $asked"
      )
      val probe = Owner()
      val _ = Location(counted, AppleLifecycle).updates(Accuracy.Fine)(using probe)
      val before = position
      drive("geo 48.8584,2.2945")
      eventually(30000)(position == paris) { ms =>
        println(s"[gallery] measured: simctl location set -> on screen in $ms ms after the DRIVE line")
        check(
          "a position set with simctl location reaches the section",
          before != paris && position == paris && texts().contains(paris),
          s"before: $before; then: $position"
        )
        val fix = AppleLocationProvider.last(Accuracy.Fine)
        println(s"[gallery] measured: the manager's last fix $fix")
        check(
          "in the foreground, Thicket listens: one through the counting provider, the gallery's and its own natively",
          listening == 1 && native == 2,
          s"$listening counted; $native in libthicketdevice"
        )
        drive("home")
        eventually(20000)(lc == AppState.Background) { _ =>
          check(
            "in the background, Thicket stops listening, and CLLocationManager stops updating",
            lc == AppState.Background && listening == 0 && native == 0,
            s"$lc; $listening counted; $native in libthicketdevice"
          )
          // What the section shows while the app is away. iOS suspends it seconds after it leaves, so it cannot wait
          // and look (measured: a 3 s timer set there never fired): the build script sets the position, waits and
          // brings the app back, and the positions the section showed in between are what is checked.
          val away = Owner()
          var shownAway = List.empty[String]
          Signal.effect {
            val p = demo.position()
            if AppleLifecycle.state.now == AppState.Background then shownAway = shownAway :+ p
          }(using away)
          drive("geo -33.8568,151.2153")
          // Time for a report to arrive, were anyone listening.
          drive("wait 3")
          drive("front")
          eventually(30000)(lc == AppState.Foreground && position == sydney) { ms =>
            println(s"[gallery] measured: relaunched -> the new position on screen in $ms ms after the DRIVE lines")
            away.dispose()
            check(
              "a position set in the background did not reach the section there",
              shownAway.nonEmpty && shownAway.forall(_ == paris),
              s"shown while away: $shownAway"
            )
            check(
              "back in front, it listens again and shows where the device went meanwhile",
              lc == AppState.Foreground && listening == 1 && native == 2 && position == sydney &&
                texts().contains(sydney),
              s"$lc; $listening counted; $native in libthicketdevice; $position"
            )
            probe.dispose()
            check(
              "disposing the owner stops it; the gallery's own listener is left",
              listening == 0 && native == 1,
              s"$listening counted; $native in libthicketdevice"
            )
            next()
          }
        }
      }
    }
  }

}
