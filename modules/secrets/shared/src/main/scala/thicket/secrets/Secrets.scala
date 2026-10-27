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

/** Small secrets that outlive the app — a token, a password — kept by the platform's secure store: the Android
  * Keystore, the Apple Keychain, the Secret Service on GTK (`docs/16-device-services.md` §16.3).
  *
  * Strings only, and small ones: a secret store is for credentials, not data. Each app's secrets are its own.
  *
  * **Off the UI thread.** Every call may block — on GTK it is a D-Bus round trip to the keyring daemon, which may be
  * waiting for the user to unlock it — so call it from a worker, as with a database.
  *
  * A failure of the store itself — the keyring locked, the daemon gone — throws [[SecretsException]]. A store that is
  * not there at all is reported when it is opened, as `Left(reason)`, not by each call.
  */
trait Secrets {

  /** The secret kept under `key`, if there is one. */
  def get(key: String): Option[String]

  /** Keeps `value` under `key`, replacing what was there. */
  def set(
    key:   String,
    value: String
  ): Unit

  /** Forgets `key`. Forgetting a key that has no secret is not an error. */
  def delete(key: String): Unit

}

/** The secure store failed: what it said is the message. */
final class SecretsException(
  message: String,
  cause:   Throwable = null
) extends RuntimeException(message, cause)

object Secrets {

  /** What every platform accepts as a key: not empty. Each implementation calls it, so they agree. */
  def requireKey(key: String): Unit = require(key.nonEmpty, "a secret's key must not be empty")

}
