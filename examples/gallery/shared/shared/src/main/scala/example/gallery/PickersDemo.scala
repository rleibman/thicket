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
import thicket.camera.Camera
import thicket.device.Availability
import thicket.pickers.{Documents, Photos}
import thicket.signals.Var

/** The Pickers section's state: the last photo and document picked, by name and size, and the last document saved. */
final class PickersDemo(
  photos:    Photos,
  documents: Documents,
  camera:    Option[Camera]
) {

  val photo:    Var[String] = Var(PickersDemo.nothing("Photo"))
  val document: Var[String] = Var(PickersDemo.nothing("Document"))
  val saved:    Var[String] = Var(PickersDemo.notSaved)
  val taken:    Var[String] =
    Var(camera.fold(PickersDemo.noCamera)(c => PickersDemo.cameraLabel(c.availability)))

  /** What the latest photo taken answered, for a self-test. */
  var lastTaken: Option[Option[Path]] = None

  /** What the latest picks answered, for a self-test: the copies' paths. */
  var lastPhotos:    Seq[Path] = Nil
  var lastDocuments: Seq[Path] = Nil

  def pickPhoto(): Unit =
    photos.pick(1) { paths =>
      lastPhotos = paths
      photo.set(PickersDemo.pickedLabel("Photo", paths))
    }

  def openDocument(): Unit =
    documents.open(List("text/plain")) { paths =>
      lastDocuments = paths
      document.set(PickersDemo.pickedLabel("Document", paths))
    }

  def takePhoto(): Unit =
    camera.foreach(_.capturePhoto { photo =>
      lastTaken = Some(photo)
      taken.set(PickersDemo.takenLabel(photo))
    })

  def saveDocument(): Unit =
    documents.save(PickersDemo.savedName, "text/plain", PickersDemo.savedText.getBytes("UTF-8")) { name =>
      saved.set(PickersDemo.savedLabel(name))
    }

}

object PickersDemo {

  def nothing(what: String): String = s"$what: nothing picked yet"

  def pickedLabel(
    what:  String,
    paths: Seq[Path]
  ): String =
    paths.headOption.fold(s"$what: cancelled")(p => s"$what: ${p.getFileName} (${Files.size(p)} bytes)")

  val notSaved: String = "Saved: nothing yet"

  val noCamera: String = "Camera: not on this platform yet"

  def cameraLabel(a: Availability): String =
    a match {
      case Availability.Available          => "Camera: ready"
      case Availability.NeedsPermission(_) => "Camera: needs permission"
      case Availability.Unavailable(why)   => s"Camera: unavailable ($why)"
    }

  def takenLabel(photo: Option[Path]): String =
    photo.fold("Camera: no photo taken")(p => s"Camera: took ${Files.size(p)} bytes")

  val savedName: String = "thicket-gallery.txt"

  val savedText: String = "Saved from the Thicket gallery\n"

  def savedLabel(name: Option[String]): String = name.fold("Saved: cancelled")(n => s"Saved: $n")

}
