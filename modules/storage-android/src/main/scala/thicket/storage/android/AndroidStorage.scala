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

package thicket.storage.android

import android.content.{Context, SharedPreferences}
import thicket.storage.*

import scala.jdk.CollectionConverters.*

/** Storage for an Android app: its private internal directories, and `SharedPreferences`.
  *
  * Pass the `Activity` (a `ThicketActivity` is one) or the application context; the application context is taken from
  * it, so holding this does not hold the `Activity`.
  *
  * @param name
  *   the `SharedPreferences` file, private to the app. One per app is usual.
  */
final class AndroidStorage(
  context: Context,
  name:    String = "thicket"
) extends Storage {

  private val app: Context = Option(context.getApplicationContext).getOrElse(context)

  // Internal storage, not external: private to the app, needs no permission, and removed with it.
  val dirs: AppDirs =
    AppDirs(
      data = app.getFilesDir.toPath,
      cache = app.getCacheDir.toPath,
      temp = app.getCacheDir.toPath.resolve("tmp")
    )
      .ensure()

  val preferences: Preferences = AndroidPreferences(app.getSharedPreferences(name, Context.MODE_PRIVATE))

}

/** `SharedPreferences` through [[StringPreferences]]: every value is stored as a tagged string, so a `Double` keeps its
  * full precision (`SharedPreferences` has only `Float`) and every type reads back the same way it does on the other
  * platforms.
  *
  * Writes use `apply()`: visible to the next read at once, written to disk in the background, as Android recommends for
  * the UI thread.
  */
final class AndroidPreferences(prefs: SharedPreferences) extends StringPreferences {

  protected def read(key: String): Option[String] =
    // A value some other code stored as another type is absent here rather than a ClassCastException.
    prefs.getAll.asScala.get(key).collect { case s: String => s }

  protected def write(
    key:    String,
    tagged: String
  ): Unit = prefs.edit().putString(key, tagged).apply()

  def remove(key: String): Unit = prefs.edit().remove(key).apply()

  def keys: Set[String] = prefs.getAll.asScala.collect { case (k, _: String) => k }.toSet

}
