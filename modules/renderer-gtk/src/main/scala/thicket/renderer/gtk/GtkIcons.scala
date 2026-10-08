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

package thicket.renderer.gtk

import thicket.renderer.Icon

/** The curated [[Icon]]s as GTK icon-theme names (#61).
  *
  * Symbolic names from the freedesktop naming spec, so the user's theme draws them and recolours them with the text.
  * Every one was checked to exist in Adwaita (`/usr/share/icons/Adwaita/symbolic`), and the gallery self-test asks the
  * theme for each at runtime. Two needed a choice: Adwaita has no `share` icon, so Share is `send-to`; and it has no
  * heart, which is why the curated set has no Favorite — GNOME uses a star for that.
  */
object GtkIcons {

  def name(icon: Icon): String =
    icon match {
      case Icon.Add                 => "list-add-symbolic"
      case Icon.Remove              => "list-remove-symbolic"
      case Icon.Delete              => "user-trash-symbolic"
      case Icon.Edit                => "document-edit-symbolic"
      case Icon.Search              => "system-search-symbolic"
      case Icon.Settings            => "emblem-system-symbolic"
      case Icon.Share               => "send-to-symbolic"
      case Icon.Close               => "window-close-symbolic"
      case Icon.Back                => "go-previous-symbolic"
      case Icon.Forward             => "go-next-symbolic"
      case Icon.Menu                => "open-menu-symbolic"
      case Icon.More                => "view-more-symbolic"
      case Icon.Home                => "go-home-symbolic"
      case Icon.Person              => "avatar-default-symbolic"
      case Icon.Star                => "starred-symbolic"
      case Icon.Check               => "object-select-symbolic"
      case Icon.Refresh             => "view-refresh-symbolic"
      case Icon.Info                => "dialog-information-symbolic"
      case Icon.Warning             => "dialog-warning-symbolic"
      case Icon.Copy                => "edit-copy-symbolic"
      case Icon.Calendar            => "x-office-calendar-symbolic"
      case Icon.Mail                => "mail-unread-symbolic"
      case Icon.Send                => "mail-send-symbolic"
      case Icon.Download            => "folder-download-symbolic"
      case Icon.Platform(gtk, _, _) => gtk
    }

}
