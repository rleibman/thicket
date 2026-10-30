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

import thicket.clipboard.testing.MemoryClipboard
import zio.test.*

object ClipboardSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("Clipboard")(
      test("MemoryClipboard: empty until set, then the latest text") {
        val c = MemoryClipboard()
        var first: Option[Option[String]] = None
        c.get(t => first = Some(t))
        c.set("one")
        c.set("two")
        var second: Option[Option[String]] = None
        c.get(t => second = Some(t))
        assertTrue(first.contains(None), second.contains(Some("two")))
      }
    )

}
