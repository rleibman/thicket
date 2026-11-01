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

package thicket.pickers.testing

import java.nio.file.Path
import thicket.device.Availability
import thicket.pickers.{Documents, Photos}

import scala.collection.mutable

/** [[Photos]] whose user picks `answer` at once, for tests: at most `limit` of them. [[asked]] records each limit. */
final class FakePhotos(answer: Seq[Path]) extends Photos {

  val asked: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty

  def availability: Availability = Availability.Available

  def pick(limit: Int)(onResult: Seq[Path] => Unit): Unit = {
    asked += limit
    onResult(answer.take(limit))
  }

}

/** [[Documents]] whose user picks `answer` and saves under the suggested name, at once, for tests. [[saved]] keeps what
  * was saved.
  */
final class FakeDocuments(answer: Seq[Path]) extends Documents {

  val saved: mutable.LinkedHashMap[String, Array[Byte]] = mutable.LinkedHashMap.empty

  def availability: Availability = Availability.Available

  def open(
    mimeTypes: Seq[String],
    multiple:  Boolean
  )(
    onResult: Seq[Path] => Unit
  ): Unit = onResult(if multiple then answer else answer.take(1))

  def save(
    name:     String,
    mimeType: String,
    bytes:    Array[Byte]
  )(
    onResult: Option[String] => Unit
  ): Unit = {
    saved(name) = bytes
    onResult(Some(name))
  }

}
