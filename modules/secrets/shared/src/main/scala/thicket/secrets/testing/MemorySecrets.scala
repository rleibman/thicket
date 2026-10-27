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

package thicket.secrets.testing

import thicket.secrets.Secrets

/** Secrets held in memory, for tests: the same rules as the platform stores, nothing kept. Instances that share `store`
  * see each other's secrets, as two instances of a platform store do.
  */
final class MemorySecrets(store: collection.mutable.Map[String, String] = collection.mutable.Map.empty)
    extends Secrets {

  def get(key: String): Option[String] = {
    Secrets.requireKey(key)
    store.synchronized(store.get(key))
  }

  def set(
    key:   String,
    value: String
  ): Unit = {
    Secrets.requireKey(key)
    store.synchronized(store(key) = value)
  }

  def delete(key: String): Unit = {
    Secrets.requireKey(key)
    store.synchronized(store.remove(key))
  }

}
