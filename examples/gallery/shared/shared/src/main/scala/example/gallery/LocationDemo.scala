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

import thicket.device.{Availability, Device, PermissionStatus}
import thicket.location.{Accuracy, Fix, Location}
import thicket.signals.{Owner, Signal, Var}

import scala.util.Try

/** The Location section's state: whether positions can be had, and the latest one, followed while the app is in front.
  *
  * Following starts at once when location is available, and otherwise when "Use my location" gets the permission.
  */
final class LocationDemo(
  location: Location,
  device:   Option[Device]
)(using Owner
) {

  val availability: Var[String] = Var(LocationDemo.availabilityLabel(location.availability))

  val position: Var[String] = Var(LocationDemo.waiting)

  private var following = false

  /** Follows positions from now on, if it can and is not already. */
  private def follow(): Unit =
    if !following && location.availability == Availability.Available then {
      following = true
      val fixes = location.updates(Accuracy.Fine)
      val _ = Signal.effect(position.set(fixes().fold(LocationDemo.waiting)(LocationDemo.positionLabel)))
    }

  /** "Use my location": asks for the permission if it is needed, then follows. */
  def use(): Unit =
    location.availability match {
      case Availability.NeedsPermission(p) =>
        device.foreach { d =>
          Try(d.permissions.request(p) { s =>
            if s == PermissionStatus.Granted then follow()
            availability.set(LocationDemo.availabilityLabel(location.availability))
          }).failed.foreach(e => availability.set(s"Location: ${e.getMessage}"))
        }
      case a =>
        availability.set(LocationDemo.availabilityLabel(a))
        follow()
    }

  follow()

}

object LocationDemo {

  val waiting: String = "Position: waiting for a fix"

  def availabilityLabel(a: Availability): String =
    a match {
      case Availability.Available          => "Location: available"
      case Availability.NeedsPermission(_) => "Location: needs permission"
      case Availability.Unavailable(why)   => s"Location: unavailable ($why)"
    }

  /** Four decimal places: about 11 m, finer than a phone's fix and enough to tell two test positions apart. */
  def positionLabel(f: Fix): String = f"Position: ${f.latitude}%.4f, ${f.longitude}%.4f"

}
