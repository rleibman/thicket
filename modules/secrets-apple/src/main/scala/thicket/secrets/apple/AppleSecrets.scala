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

import thicket.secrets.{Secrets, SecretsException}

import java.nio.charset.StandardCharsets.UTF_8
import scala.scalanative.unsafe.*

/** Secrets on macOS and iOS: the Keychain (`docs/16-device-services.md` §16.3). Each secret is a generic password
  * (`kSecClassGenericPassword`) with `kSecAttrService` = the service you open it with (the bundle id) and
  * `kSecAttrAccount` = the key, accessible after the first unlock, on this device only, never synced.
  *
  * Which keychain, measured on macOS 27 and the iOS 27 simulator (`docs/decisions.md`, #108):
  *   - **iOS**: the app's own keychain, the only one there is.
  *   - **macOS, an app with a keychain entitlement** (signed with a team and a provisioning profile): the
  *     data-protection keychain, per app, as on iOS.
  *   - **macOS, anything else** — an unsigned or ad-hoc-signed executable, which is every `sbt nativeLink` and the
  *     gallery — gets `errSecMissingEntitlement` there, so this uses the user's login keychain instead. Its items are
  *     bound to the build that made them: a rebuilt binary cannot read or delete them, and `get` throws a
  *     [[SecretsException]] that says so. Its dialogs are turned off for the whole process, so a call fails rather than
  *     waiting on one.
  *
  * Every call blocks, so call it from a worker, as with GTK's.
  */
final class AppleSecrets private (
  service:        String,
  dataProtection: Boolean
) extends Secrets {

  /** `"data protection"` or `"login"`: which keychain this one is, for a log line or a test. */
  val keychain: String = if dataProtection then "data protection" else "login"

  private val dp = if dataProtection then 1 else 0

  def get(key: String): Option[String] = {
    Secrets.requireKey(key)
    AppleSecrets.withBytes(service) {
      (
        s,
        sl
      ) =>
        AppleSecrets.withBytes(key) {
          (
            k,
            kl
          ) =>
            val out = stackalloc[Ptr[Byte]]()
            val outLen = stackalloc[CLongLong]()
            val status = Keychain.thk_keychain_get(dp, s, sl, k, kl, out, outLen)
            status match {
              case KeychainStatus.errSecSuccess =>
                val n = (!outLen).toInt
                val p = !out
                val bytes = new Array[Byte](n)
                var i = 0
                while i < n do {
                  bytes(i) = p(i)
                  i += 1
                }
                Keychain.thk_keychain_free(!out)
                Some(new String(bytes, UTF_8))
              case KeychainStatus.errSecItemNotFound                  => None
              case KeychainStatus.errSecAuthFailed if !dataProtection =>
                throw new SecretsException(
                  s"the Keychain item '$key' of '$service' was made by another build of this app " +
                    "(an unsigned or ad-hoc-signed Mac binary's login-keychain items are bound to the build that made " +
                    s"them); remove it with: security delete-generic-password -s '$service' -a '$key'"
                )
              case other => AppleSecrets.fail("read", key, other)
            }
        }
    }
  }

  def set(
    key:   String,
    value: String
  ): Unit = {
    Secrets.requireKey(key)
    AppleSecrets.withBytes(service) {
      (
        s,
        sl
      ) =>
        AppleSecrets.withBytes(key) {
          (
            k,
            kl
          ) =>
            AppleSecrets.withBytes(value) {
              (
                v,
                vl
              ) =>
                val status = Keychain.thk_keychain_set(dp, s, sl, k, kl, v, vl)
                if status != KeychainStatus.errSecSuccess then AppleSecrets.fail("write", key, status)
            }
        }
    }
  }

  def delete(key: String): Unit = {
    Secrets.requireKey(key)
    AppleSecrets.withBytes(service) {
      (
        s,
        sl
      ) =>
        AppleSecrets.withBytes(key) {
          (
            k,
            kl
          ) =>
            val status = Keychain.thk_keychain_delete(dp, s, sl, k, kl)
            if status != KeychainStatus.errSecSuccess && status != KeychainStatus.errSecItemNotFound then
              AppleSecrets.fail("delete", key, status)
        }
    }
  }

}

object AppleSecrets {

  /** The app's secrets under `service` (its bundle id), or why there are none. Asks the Keychain once — a delete of a
    * key nobody writes — so it blocks like any other call.
    *
    * `Left` when the Keychain refuses this process altogether: an iOS app without the entitlement its signature should
    * carry, or a Mac whose login keychain cannot be reached.
    *
    * @param service
    *   `kSecAttrService` for every secret, e.g. `"dev.example.app"`. Passed in, not read from the bundle: a macOS
    *   executable run outside an `.app` has none.
    */
  def open(service: String): Either[String, AppleSecrets] = {
    require(service.nonEmpty, "a service, such as the bundle id dev.example.app")
    val probe = "thicket.probe"
    probeStatus(1, service, probe) match {
      case ok if ok == KeychainStatus.errSecSuccess || ok == KeychainStatus.errSecItemNotFound =>
        Right(AppleSecrets(service, dataProtection = true))
      case KeychainStatus.errSecMissingEntitlement if Keychain.thk_keychain_is_macos() == 1 =>
        // No keychain entitlement: an unsigned or ad-hoc-signed Mac binary. The login keychain, without its dialogs.
        Keychain.thk_keychain_disallow_interaction()
        probeStatus(0, service, probe) match {
          case ok if ok == KeychainStatus.errSecSuccess || ok == KeychainStatus.errSecItemNotFound =>
            Right(AppleSecrets(service, dataProtection = false))
          case other => Left(s"the login keychain refused: ${describe(other)}")
        }
      case KeychainStatus.errSecMissingEntitlement =>
        Left(s"the Keychain refused this app: ${describe(KeychainStatus.errSecMissingEntitlement)} (is it signed?)")
      case other => Left(s"the Keychain refused: ${describe(other)}")
    }
  }

  /** A delete of a key nobody writes. A read is no probe: without the entitlement, a read of the data-protection
    * keychain answers `errSecItemNotFound` like any other, and only a write or a delete says `errSecMissingEntitlement`
    * (measured on macOS 27).
    */
  private def probeStatus(
    dp:      Int,
    service: String,
    key:     String
  ): Int =
    withBytes(service)(
      (
        s,
        sl
      ) =>
        withBytes(key)(
          (
            k,
            kl
          ) => Keychain.thk_keychain_delete(dp, s, sl, k, kl)
        )
    )

  /** Security's own words for a status, and the number. */
  private[apple] def describe(status: Int): String = {
    val size = 512
    val buf = stackalloc[Byte](size)
    Keychain.thk_keychain_message(status, buf, size.toLong)
    val text = fromCString(buf)
    if text.isEmpty then s"OSStatus $status" else s"$text (OSStatus $status)"
  }

  private def fail(
    op:     String,
    key:    String,
    status: Int
  ): Nothing = throw new SecretsException(s"the Keychain could not $op '$key': ${describe(status)}")

  /** `s` as UTF-8 bytes with their length, in memory that lives for the call. Never NUL-terminated: a key or a secret
    * may hold any character, and an empty one is still a pointer.
    */
  private def withBytes[A](s: String)(f: (Ptr[Byte], CLongLong) => A): A =
    Zone {
      val b = s.getBytes(UTF_8)
      val p = alloc[Byte](math.max(b.length, 1))
      var i = 0
      while i < b.length do {
        p(i) = b(i)
        i += 1
      }
      f(p, b.length.toLong)
    }

}
