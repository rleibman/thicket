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

package thicket.renderer.apple

import thicket.renderer.Icon

/** The curated [[Icon]]s as SF Symbols (#66), the same symbols on AppKit and UIKit.
  *
  * Every name was checked against the SDKs (macOS 27.0.1 and the iOS 27 simulator, 24/24 on each), and the gallery
  * self-test asks the platform for each at runtime: `NSImage(systemSymbolName:)` and `UIImage(systemName:)` return nil
  * for a name they lack and draw nothing, so a wrong one has to be caught there rather than seen. Where GTK's mapping
  * had to choose, so did this one: Share is the system share symbol, `square.and.arrow.up`, which is what both Apple
  * platforms show for it.
  */
object AppleIcons {

  def name(icon: Icon): String =
    icon match {
      case Icon.Add                => "plus"
      case Icon.Remove             => "minus"
      case Icon.Delete             => "trash"
      case Icon.Edit               => "pencil"
      case Icon.Search             => "magnifyingglass"
      case Icon.Settings           => "gearshape"
      case Icon.Share              => "square.and.arrow.up"
      case Icon.Close              => "xmark"
      case Icon.Back               => "chevron.backward"
      case Icon.Forward            => "chevron.forward"
      case Icon.Menu               => "line.3.horizontal"
      case Icon.More               => "ellipsis"
      case Icon.Home               => "house"
      case Icon.Person             => "person"
      case Icon.Star               => "star"
      case Icon.Check              => "checkmark"
      case Icon.Refresh            => "arrow.clockwise"
      case Icon.Info               => "info.circle"
      case Icon.Warning            => "exclamationmark.triangle"
      case Icon.Copy               => "doc.on.doc"
      case Icon.Calendar           => "calendar"
      case Icon.Mail               => "envelope"
      case Icon.Send               => "paperplane"
      case Icon.Download           => "arrow.down.circle"
      case Icon.Platform(_, sf, _) => sf
    }

}
