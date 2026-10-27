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

import thicket.secrets.Secrets

/** Secrets on GTK: the freedesktop Secret Service — GNOME Keyring, KeePassXC, KWallet's bridge — through libsecret.
  * Each secret is an item in the default collection (the login keyring on GNOME), with the attributes `app` = the app
  * id and `key`, under the schema `dev.thicket.Secret`; its label, which Seahorse shows, is `"<app id>: <key>"`.
  */
final class GtkSecrets private (
  appId: String,
  lib:   LibSecret
) extends Secrets {

  def get(key: String): Option[String] = {
    Secrets.requireKey(key)
    lib.lookup(appId, key)
  }

  def set(
    key:   String,
    value: String
  ): Unit = {
    Secrets.requireKey(key)
    lib.store(appId, key, s"$appId: $key", value)
  }

  def delete(key: String): Unit = {
    Secrets.requireKey(key)
    lib.clear(appId, key)
  }

}

object GtkSecrets {

  /** The app's secrets, or why there are none: no libsecret installed, or no Secret Service on the session bus (a
    * headless CI box, a desktop without a keyring). Asks the service once, so it blocks like any other call.
    */
  def open(appId: String): Either[String, Secrets] = open(appId, LibSecret.soname)

  /** With another library name, so a test can see what a system without libsecret gets. */
  private[gtk] def open(
    appId:   String,
    library: String
  ): Either[String, Secrets] = {
    require(appId.nonEmpty, "an app id, such as dev.example.app")
    LibSecret.load(library).flatMap { lib =>
      try {
        lib.lookup(appId, "thicket.probe")
        Right(GtkSecrets(appId, lib))
      } catch { case e: thicket.secrets.SecretsException => Left(e.getMessage) }
    }
  }

}
