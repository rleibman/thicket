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

package thicket.clipboard.android

import _root_.android.content.{ClipData, ClipboardManager, Context}
import _root_.android.os.{Handler, Looper}
import thicket.clipboard.Clipboard

/** The system clipboard, through `ClipboardManager`, as plain text.
  *
  * From Android 10, only the app with focus, or the keyboard, may read the clipboard: `get` from an app in the
  * background answers `None`. From Android 13 the system shows its own "copied" confirmation when `set` is called.
  * `get` answers on the next turn of the main loop, as GTK's does.
  */
final class AndroidClipboard(context: Context) extends Clipboard {

  private val manager =
    Option(context.getApplicationContext)
      .getOrElse(context).getSystemService(Context.CLIPBOARD_SERVICE)
      .asInstanceOf[ClipboardManager]

  private val main = Handler(Looper.getMainLooper)

  def set(text: String): Unit = manager.setPrimaryClip(ClipData.newPlainText("text", text))

  def get(onResult: Option[String] => Unit): Unit = {
    val text =
      Option(manager.getPrimaryClip)
        .filter(_.getItemCount > 0)
        .flatMap(clip => Option(clip.getItemAt(0).coerceToText(context)))
        .map(_.toString)
    val _ = main.post(() => onResult(text))
  }

}
