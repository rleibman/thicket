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

package thicket.clipboard

/** The system clipboard, for text (`docs/16-device-services.md` §16.3).
  *
  * Call from the UI thread. Reading is asynchronous: on GTK the text may belong to another app, which is asked for it.
  * `onResult` runs on the UI thread with `None` when the clipboard holds no text.
  */
trait Clipboard {

  /** Puts `text` on the clipboard, for this app and every other. */
  def set(text: String): Unit

  /** The clipboard's text, to `onResult`, later, on the UI thread. */
  def get(onResult: Option[String] => Unit): Unit

}
