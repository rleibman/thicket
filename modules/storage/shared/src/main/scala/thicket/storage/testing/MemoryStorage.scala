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

package thicket.storage.testing

import thicket.storage.*

import java.nio.file.{Files, Path}

/** Preferences held in memory, for tests: the same typing rules as the platform implementations, nothing on disk. */
final class MemoryPreferences extends StringPreferences {

  private val values = collection.mutable.LinkedHashMap.empty[String, String]

  protected def read(key: String): Option[String] = synchronized(values.get(key))

  protected def write(
    key:    String,
    tagged: String
  ): Unit = synchronized(values(key) = tagged)

  def remove(key: String): Unit = synchronized(values.remove(key))

  def keys: Set[String] = synchronized(values.keySet.toSet)

}

/** A [[Storage]] for tests: memory preferences, and directories under a fresh temporary directory. */
final class MemoryStorage(root: Path = Files.createTempDirectory("thicket-storage")) extends Storage {

  val dirs: AppDirs = AppDirs(root.resolve("data"), root.resolve("cache"), root.resolve("temp")).ensure()

  val preferences: Preferences = MemoryPreferences()

}
