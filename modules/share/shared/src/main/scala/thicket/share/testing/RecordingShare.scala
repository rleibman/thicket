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

package thicket.share.testing

import java.nio.file.Path
import thicket.device.Availability
import thicket.share.Share

import scala.collection.mutable

/** A [[Share]] that records what was shared, for tests: `Left(text)` for text, `Right(path -> mimeType)` for a file. */
final class RecordingShare extends Share {

  val shared: mutable.ArrayBuffer[Either[String, (Path, String)]] = mutable.ArrayBuffer.empty

  def availability: Availability = Availability.Available

  def text(
    text:    String,
    subject: Option[String]
  ): Unit = shared += Left(text)

  def file(
    path:     Path,
    mimeType: String
  ): Unit = shared += Right(path -> mimeType)

}
