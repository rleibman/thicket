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

package thicket.secrets.apple

import thicket.secrets.testing.SecretsConformance
import zio.test.*

/** The real Keychain, on a Mac: this test binary is unsigned, so it gets the login keychain. Its own service, not an
  * app's, and every check deletes what it wrote, so the developer's keychain is left as it was.
  */
object AppleSecretsSpec extends ZIOSpecDefault {

  private val service = "dev.thicket.secrets-test"

  private def open(s: String) = AppleSecrets.open(s).fold(r => throw new AssertionError(r), identity)

  def spec: Spec[Any, Nothing] =
    suite("AppleSecrets")(
      test("an unsigned binary on macOS gets the login keychain, not a Left") {
        val r = AppleSecrets.open(service)
        assertTrue(r.map(_.keychain) == Right("login")) ?? r.toString
      },
      test("every conformance check") {
        val results = SecretsConformance.run(() => open(service), "conformance.")
        assertTrue(results.size == 11, results.forall(_.passed)) ??
          results.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
      },
      test("services are separate: another service does not see the secret, nor delete it") {
        val mine = open(service)
        val other = open(s"$service.other")
        try {
          mine.set("isolation", "mine")
          val seen = other.get("isolation")
          other.delete("isolation")
          val kept = mine.get("isolation")
          mine.delete("isolation")
          assertTrue(seen.isEmpty, kept.contains("mine"), mine.get("isolation").isEmpty)
        } finally
          // Whatever failed above, the developer's keychain keeps nothing of this test's.
          try mine.delete("isolation")
          catch { case _: Throwable => () }
      },
      test("a status is described in Security's words") {
        assertTrue(AppleSecrets.describe(KeychainStatus.errSecItemNotFound).contains("-25300"))
      }
    ) @@ TestAspect.sequential

}
