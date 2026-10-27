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

package thicket.secrets.gtk

import thicket.secrets.testing.SecretsConformance
import zio.test.*

/** The Secret Service is a desktop session's: this box has one, CI's `checkLinux` does not. So conformance runs when
  * the service is there, and must be there when `THICKET_REQUIRE_SECRET_SERVICE=1`.
  */
object GtkSecretsSpec extends ZIOSpecDefault {

  private val appId = "dev.thicket.secrets-test"

  def spec: Spec[Any, Nothing] =
    suite("GtkSecrets")(
      test("without libsecret, open says so instead of failing to start") {
        val r = GtkSecrets.open(appId, "libsecret-thicket-absent.so.0")
        assertTrue(r.left.exists(_.contains("libsecret-thicket-absent.so.0")))
      },
      test("with the Secret Service: every conformance check, and apps do not see each other's secrets") {
        GtkSecrets.open(appId) match {
          case Left(reason) =>
            // A plain Boolean: an assertion that mentions `sys.env` prints the whole environment when it fails.
            val required = sys.env.get("THICKET_REQUIRE_SECRET_SERVICE").contains("1")
            assertTrue(!required) ?? s"unavailable: $reason"
          case Right(s) =>
            val results = SecretsConformance.run(() => GtkSecrets.open(appId).toOption.get, "conformance.")
            val other = GtkSecrets.open(s"$appId.other").toOption.get
            s.set("isolation", "mine")
            val seen = other.get("isolation")
            s.delete("isolation")
            assertTrue(results.size == 11, results.forall(_.passed), seen.isEmpty) ??
              results.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
        }
      }
    ) @@ TestAspect.sequential

}
