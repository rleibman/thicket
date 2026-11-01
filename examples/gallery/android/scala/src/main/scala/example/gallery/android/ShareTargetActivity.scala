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

package example.gallery.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** A share target inside the gallery, so its self-test can be the app on the other end of a share: it takes what the
  * chooser hands it (`ACTION_SEND`), reads a shared file through the URI it was granted, as another app would, and
  * finishes. The self-test reads [[ShareInbox]].
  */
final class ShareTargetActivity extends Activity {

  override def onCreate(saved: Bundle): Unit = {
    super.onCreate(saved)
    val intent = getIntent
    if intent != null && intent.getAction == Intent.ACTION_SEND then {
      val text = Option(intent.getStringExtra(Intent.EXTRA_TEXT))
      // The typed form is Android 13+; the self-test runs on 13+, and an older device just skips the file.
      val stream =
        if android.os.Build.VERSION.SDK_INT >= 33 then
          Option(intent.getParcelableExtra[Uri](Intent.EXTRA_STREAM, classOf[Uri]))
        else None
      val file = stream.flatMap { uri =>
        scala.util.Try {
          val in = getContentResolver.openInputStream(uri)
          try (uri.getLastPathSegment, Option(getContentResolver.getType(uri)).getOrElse("?"), in.readAllBytes().toList)
          finally in.close()
        }.toOption
      }
      ShareInbox.received = Some(ShareInbox.Received(text, file))
    }
    finish()
  }

}

/** What [[ShareTargetActivity]] last received: the text, or the file's name, type and bytes. */
object ShareInbox {

  final case class Received(
    text: Option[String],
    file: Option[(String, String, List[Byte])]
  )

  @volatile var received: Option[Received] = None

}
