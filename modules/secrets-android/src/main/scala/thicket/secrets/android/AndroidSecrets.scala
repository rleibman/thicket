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

package thicket.secrets.android

import android.content.{Context, SharedPreferences}
import android.security.keystore.{KeyGenParameterSpec, KeyProperties}
import thicket.secrets.{Secrets, SecretsException}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{GeneralSecurityException, KeyStore}
import java.util.Base64
import javax.crypto.{Cipher, KeyGenerator, SecretKey}
import javax.crypto.spec.GCMParameterSpec

/** Secrets on Android: each value encrypted with AES-256-GCM under a key that lives in the Android Keystore — in the
  * device's secure hardware where it has one — and never leaves it. The ciphertext is kept in a private
  * `SharedPreferences` file; without the Keystore key it is noise.
  *
  * The secret's key is authenticated with it (GCM's associated data), so a value moved to another key does not decrypt.
  *
  * **Backups:** Android's auto backup may restore the preferences file to a new device, but never the Keystore key, so
  * a restored secret cannot be read: [[get]] throws [[SecretsException]] for it, and the app should sign in again and
  * set it anew. Excluding the file from backup is the app's choice, in its manifest.
  *
  * @param name
  *   the `SharedPreferences` file, and the Keystore key's alias. One per app is usual.
  */
final class AndroidSecrets(
  context: Context,
  name:    String = "thicket.secrets"
) extends Secrets {

  private val prefs: SharedPreferences =
    Option(context.getApplicationContext).getOrElse(context).getSharedPreferences(name, Context.MODE_PRIVATE)

  def get(key: String): Option[String] = {
    Secrets.requireKey(key)
    Option(prefs.getString(key, null)).map { stored =>
      val bytes =
        try Base64.getDecoder.decode(stored)
        catch { case e: IllegalArgumentException => throw SecretsException(s"'$key' is not a stored secret", e) }
      if bytes.length < AndroidSecrets.IvBytes then throw SecretsException(s"'$key' is not a stored secret")
      crypto(s"read '$key'") {
        val c = Cipher.getInstance(AndroidSecrets.Transformation)
        c.init(Cipher.DECRYPT_MODE, AndroidSecrets.key(name), GCMParameterSpec(128, bytes, 0, AndroidSecrets.IvBytes))
        c.updateAAD(key.getBytes(UTF_8))
        String(c.doFinal(bytes, AndroidSecrets.IvBytes, bytes.length - AndroidSecrets.IvBytes), UTF_8)
      }
    }
  }

  def set(
    key:   String,
    value: String
  ): Unit = {
    Secrets.requireKey(key)
    val sealed_ = crypto(s"store '$key'") {
      // The Keystore picks the IV: a key made for GCM refuses one supplied by the caller.
      val c = Cipher.getInstance(AndroidSecrets.Transformation)
      c.init(Cipher.ENCRYPT_MODE, AndroidSecrets.key(name))
      c.updateAAD(key.getBytes(UTF_8))
      c.getIV ++ c.doFinal(value.getBytes(UTF_8))
    }
    // commit, not apply: the caller is off the UI thread, and a secret should be on disk when this returns.
    if !prefs.edit().putString(key, Base64.getEncoder.encodeToString(sealed_)).commit() then
      throw SecretsException(s"could not write '$key'")
  }

  def delete(key: String): Unit = {
    Secrets.requireKey(key)
    if !prefs.edit().remove(key).commit() then throw SecretsException(s"could not delete '$key'")
  }

  private def crypto[A](what: String)(body: => A): A =
    try body
    catch { case e: GeneralSecurityException => throw SecretsException(s"the Keystore could not $what: $e", e) }

}

object AndroidSecrets {

  private val Transformation = "AES/GCM/NoPadding"

  private val IvBytes = 12

  /** The Keystore key under `alias`, made on first use. */
  private def key(alias: String): SecretKey =
    synchronized {
      val store = KeyStore.getInstance("AndroidKeyStore")
      store.load(null)
      Option(store.getKey(alias, null)).collect { case k: SecretKey => k }.getOrElse {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
          KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        )
        gen.generateKey()
      }
    }

}
