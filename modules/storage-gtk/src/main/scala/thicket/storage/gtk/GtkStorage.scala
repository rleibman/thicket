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

package thicket.storage.gtk

import thicket.storage.*

import java.nio.file.{Path, Paths}

/** Storage for a GTK app: the XDG base directories, and preferences in a file under the data directory.
  *
  * @param appId
  *   the application id given to `GtkApp.run`, e.g. `"dev.thicket.gallery"`. It names the app's directories, as GNOME
  *   apps' are named, so two apps never share one.
  */
final class GtkStorage(appId: String) extends Storage {

  val dirs: AppDirs = XdgDirs.resolve(sys.env, sys.props.getOrElse("user.home", "/tmp"), appId).ensure()

  lazy val preferences: Preferences = FilePreferences(dirs.data.resolve("preferences.properties"))

}

/** The XDG Base Directory rules (https://specifications.freedesktop.org/basedir-spec/latest/), as a function of the
  * environment so they can be tested without changing it.
  */
object XdgDirs {

  def resolve(
    env:   Map[String, String],
    home:  String,
    appId: String
  ): AppDirs = {
    require(appId.nonEmpty && !appId.contains('/') && appId != "." && appId != "..", s"not a usable app id: '$appId'")
    // The spec: a relative path in one of these variables is invalid and is ignored.
    def base(
      variable: String,
      fallback: String
    ): Path =
      env.get(variable).filter(_.startsWith("/")).map(Paths.get(_)).getOrElse(Paths.get(home, fallback))
    val tmp = env.get("TMPDIR").filter(_.startsWith("/")).getOrElse("/tmp")
    AppDirs(
      data = base("XDG_DATA_HOME", ".local/share").resolve(appId),
      cache = base("XDG_CACHE_HOME", ".cache").resolve(appId),
      // Not XDG_RUNTIME_DIR: that is for sockets and is often a small tmpfs.
      temp = Paths.get(tmp, s"$appId-${sys.props.getOrElse("user.name", "user")}")
    )
  }

}
