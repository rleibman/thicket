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

package thicket.storage.apple

import thicket.storage.*

import java.nio.file.Paths
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Storage for a macOS or iOS app: Application Support, Caches and the temporary directory, each under the bundle id,
  * and preferences in a file in the first (`docs/16-device-services.md` §16.5–16.6).
  *
  * No Swift and no Foundation. The three places are the ones `FileManager.urls(for:in:)` and `NSTemporaryDirectory()`
  * report — `$HOME/Library/Application Support`, `$HOME/Library/Caches`, and Darwin's per-user temporary directory —
  * and the gallery's self-test holds them to Foundation's answer on both platforms. On iOS, and in a sandboxed Mac app,
  * `$HOME` is the app's container, so they are the container's.
  *
  * Preferences are a [[FilePreferences]], as on GTK, not `UserDefaults`: the same file, format and typing on every
  * desktop and phone, and no Swift library for an app to link (`docs/decisions.md`, 2026-10-22).
  *
  * @param bundleId
  *   the app's bundle identifier, e.g. `"dev.thicket.gallery"`. It names the app's directory inside each place, as
  *   Apple asks of a Mac app; on iOS the container is the app's already, and the name costs nothing. Passed in, not
  *   read from the bundle: a macOS executable run outside an `.app` has none.
  */
final class AppleStorage(bundleId: String) extends Storage {

  val dirs: AppDirs = AppleDirs.resolve(AppleDirs.home(), AppleDirs.userTemp(), bundleId).ensure()

  lazy val preferences: Preferences = FilePreferences(dirs.data.resolve("preferences.properties"))

}

/** Apple's per-user directories, as a function of the home and temporary directories so they can be tested anywhere. */
object AppleDirs {

  def resolve(
    home:     String,
    temp:     String,
    bundleId: String
  ): AppDirs = {
    require(
      bundleId.nonEmpty && !bundleId.contains('/') && bundleId != "." && bundleId != "..",
      s"not a usable bundle id: '$bundleId'"
    )
    require(home.startsWith("/") && temp.startsWith("/"), s"not absolute: home '$home', temp '$temp'")
    val library = Paths.get(home, "Library")
    AppDirs(
      data = library.resolve("Application Support").resolve(bundleId),
      cache = library.resolve("Caches").resolve(bundleId),
      temp = Paths.get(temp).resolve(bundleId)
    )
  }

  /** `$HOME`, which Foundation's user domain is under: on iOS and in a sandboxed Mac app, the container. Then
    * `user.home`, which comes from the password database — on the iOS simulator that is the Mac user's own home, which
    * is why it is second.
    */
  def home(): String =
    sys.env
      .get("HOME").filter(_.startsWith("/")).orElse(sys.props.get("user.home").filter(_.startsWith("/"))).getOrElse {
        throw new IllegalStateException("no home directory (HOME is unset): nowhere private to store files")
      }

  /** Darwin's per-user temporary directory, `confstr(_CS_DARWIN_USER_TEMP_DIR)`, which is what `NSTemporaryDirectory()`
    * reports: private to the user (under `/var/folders`) on macOS, the container's `tmp` on iOS. Then `$TMPDIR`. Never
    * a shared directory such as `/tmp`.
    */
  def userTemp(): String =
    fromConfstr.orElse(sys.env.get("TMPDIR")).filter(_.startsWith("/")).getOrElse {
      throw new IllegalStateException("no per-user temporary directory (confstr and TMPDIR gave none)")
    }

  /** `_CS_DARWIN_USER_TEMP_DIR`, from Darwin's `<unistd.h>`. Another libc means something else by it, or nothing. */
  private val CsDarwinUserTempDir = 65537

  private def fromConfstr: Option[String] =
    Zone {
      val size = 1024
      val buf = alloc[CChar](size)
      val n = DarwinLibc.confstr(CsDarwinUserTempDir, buf, size.toCSize).toLong
      if n <= 0 || n > size then None else Some(fromCString(buf))
    }

}

@extern
private object DarwinLibc {

  def confstr(
    name: CInt,
    buf:  CString,
    len:  CSize
  ): CSize = extern

}
