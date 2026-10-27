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

package thicket.secrets

import thicket.secrets.testing.{MemorySecrets, SecretsConformance}
import zio.test.*

object SecretsSpec extends ZIOSpecDefault {

  def spec: Spec[Any, Nothing] =
    suite("Secrets")(
      test("MemorySecrets passes every conformance check") {
        val store = collection.mutable.Map.empty[String, String]
        val results = SecretsConformance.run(() => MemorySecrets(store), "test.")
        assertTrue(results.size == 11, results.forall(_.passed), store.isEmpty) ??
          results.filterNot(_.passed).map(r => s"${r.name}: ${r.detail}").mkString("; ")
      },
      test("the checks catch a store that loses secrets between instances") {
        // Each instance with its own map: the persistence check must fail, and only it.
        val results = SecretsConformance.run(() => MemorySecrets(), "test.")
        assertTrue(
          results.filterNot(_.passed).map(_.name) == List(
            "another instance of the store sees the secret, and its delete"
          )
        )
      }
    )

}
