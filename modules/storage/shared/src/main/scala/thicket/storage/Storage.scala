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

package thicket.storage

import java.nio.file.{Files, Path}

/** Where an app keeps its own files. Each is the platform's place for that kind of file, private to the app, and is
  * created on first use by [[AppDirs.ensure]].
  *
  * Reading and writing inside them is `java.nio.file`, which works on every platform Thicket runs on; this only answers
  * *where* (`docs/16-device-services.md` §16.1).
  *
  * @param data
  *   kept until the app is uninstalled: databases, preferences, the user's work
  * @param cache
  *   kept while there is room: the platform may delete it, so nothing here may be the only copy
  * @param temp
  *   for the life of an operation; deleting it at any time must be harmless
  */
final case class AppDirs(
  data:  Path,
  cache: Path,
  temp:  Path
) {

  /** Creates the three directories if they are missing, and returns them. */
  def ensure(): AppDirs = {
    Seq(data, cache, temp).foreach(Files.createDirectories(_))
    this
  }

}

/** Small typed values that outlive the app: settings, a last-opened tab, a counter. Not for anything large or
  * structured — that is a file or a database in [[AppDirs.data]] — and not for secrets.
  *
  * A value reads back as the type it was written as. Reading it as another type gives `None`, on every platform, rather
  * than a conversion on some and an exception on others.
  *
  * Writes are visible to the next read at once. How soon they reach the disk is the platform's: Android's
  * `SharedPreferences.apply` writes in the background, the file implementation writes before returning.
  */
trait Preferences {

  def getString(key:  String): Option[String]
  def getLong(key:    String): Option[Long]
  def getDouble(key:  String): Option[Double]
  def getBoolean(key: String): Option[Boolean]

  def putString(
    key:   String,
    value: String
  ): Unit
  def putLong(
    key:   String,
    value: Long
  ): Unit
  def putDouble(
    key:   String,
    value: Double
  ): Unit
  def putBoolean(
    key:   String,
    value: Boolean
  ): Unit

  def remove(key: String): Unit

  /** Every key that has a value. */
  def keys: Set[String]

}

/** One platform's storage: its directories and its preferences. A platform module builds it — `GtkStorage(appId)`,
  * `AndroidStorage(context)` — and the app passes it to the code that needs it.
  */
trait Storage {

  def dirs: AppDirs

  def preferences: Preferences

}
