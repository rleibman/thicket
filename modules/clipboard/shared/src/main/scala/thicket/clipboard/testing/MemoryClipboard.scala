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

package thicket.clipboard.testing

import thicket.clipboard.Clipboard

/** A [[Clipboard]] in memory, for tests: `get` answers at once. */
final class MemoryClipboard(initial: Option[String] = None) extends Clipboard {

  private var current = initial

  def set(text: String): Unit = current = Some(text)

  def get(onResult: Option[String] => Unit): Unit = onResult(current)

}
