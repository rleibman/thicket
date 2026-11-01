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

package thicket.pickers

import java.nio.file.Path
import thicket.device.Availability

/** The system's photo picker (`docs/16-device-services.md` §16.3, §16.10 item 5).
  *
  * Each picked photo is copied into the app's cache and handed back as a `Path` the app may read, move or delete
  * (owner's decision, 2026-10-30): on Android a picked photo is a `content://` URI that is only readable for a while,
  * so a copy is the one form that behaves the same everywhere. The cost is the copy: a large video is copied in full.
  */
trait Photos {

  def availability: Availability

  /** Shows the picker for up to `limit` photos, and calls `onResult` on the UI thread with the copies: empty if the
    * user cancelled, or if another picker is already up (one at a time).
    */
  def pick(limit: Int = 1)(onResult: Seq[Path] => Unit): Unit

}

/** The system's document picker and save dialog (`docs/16` §16.3). Named `Documents`, not `docs/16`'s `Files`, which
  * would collide with `java.nio.file.Files` in every app that imports both.
  */
trait Documents {

  def availability: Availability

  /** Shows the picker for files of `mimeTypes` (`"application/pdf"`, `"text/plain"`; empty for any), one or several,
    * and calls `onResult` on the UI thread with copies in the app's cache, as [[Photos.pick]] does.
    */
  def open(
    mimeTypes: Seq[String] = Nil,
    multiple:  Boolean = false
  )(
    onResult: Seq[Path] => Unit
  ): Unit

  /** Asks the user where to save `bytes`, suggesting `name`, writes them there, and calls `onResult` on the UI thread
    * with the name the file was saved under; `None` if the user cancelled or the write failed.
    */
  def save(
    name:     String,
    mimeType: String,
    bytes:    Array[Byte]
  )(
    onResult: Option[String] => Unit
  ): Unit

}
