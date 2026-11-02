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

package thicket.share

import java.nio.file.Path
import thicket.device.Availability

/** Handing text or a file to another app through the platform's share sheet (`docs/16-device-services.md` §16.3, §16.10
  * item 5): the user picks the app there.
  *
  * Fire and forget, from the UI thread: no platform says reliably what the user chose, or whether the other app took
  * it, so there is nothing to answer.
  */
trait Share {

  def availability: Availability

  /** Shares `text`, with `subject` for apps that use one (mail). */
  def text(
    text:    String,
    subject: Option[String] = None
  ): Unit

  /** Shares a copy of the file at `path`, of `mimeType`. The original is not touched, and may change or go after the
    * call.
    */
  def file(
    path:     Path,
    mimeType: String
  ): Unit

}

object Share {

  /** A `Share` that is never available, for `reason`: GTK, which has no share sheet. The calls do nothing. */
  def unavailable(reason: String): Share =
    new Share {

      def availability: Availability = Availability.Unavailable(reason)

      def text(
        text:    String,
        subject: Option[String]
      ): Unit = ()

      def file(
        path:     Path,
        mimeType: String
      ): Unit = ()

    }

}
