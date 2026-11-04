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

import _root_.android.content.Context
import _root_.android.os.Bundle
import _root_.android.view.View
import _root_.android.widget.TextView
import example.gallery.{Gallery, Services}
import thicket.core.AppRoot
import thicket.device.android.AndroidDevice
import thicket.location.android.AndroidLocation
import thicket.motion.android.AndroidMotion
import thicket.clipboard.android.AndroidClipboard
import thicket.haptics.android.AndroidHaptics
import thicket.pickers.android.AndroidPickers
import thicket.camera.android.AndroidCamera
import thicket.camera.preview.android.AndroidCameraPreview
import thicket.share.android.AndroidShare
import thicket.renderer.android.{AndroidPlatformView, ThicketActivity}
import scala.collection.mutable
import thicket.signals.Owner
import thicket.sqlite.android.AndroidSqlite
import thicket.secrets.android.AndroidSecrets
import thicket.storage.android.AndroidStorage

/** The gallery on Android — the same screen the GTK, macOS and iOS hosts mount.
  *
  * Everything a host does is `ThicketActivity`'s, so this is only what is particular to the gallery: the logo comes
  * from the APK's assets, and `--ez selftest true` runs [[GallerySelfTest]].
  *
  * The theme is left entirely to the platform, on purpose: a branded accent would hide whether each widget is picking
  * up Android's own tokens, which is the one thing a cross-platform catalogue is for.
  */
class GalleryActivity extends ThicketActivity {

  protected def createApp(saved: Option[Bundle])(using Owner): AppRoot = {
    val device = AndroidDevice(this)
    Gallery.app(
      logo = assetBytes(Gallery.logo),
      services = Some(
        Services(
          AndroidStorage(this),
          AndroidSqlite.driver,
          () => Right(AndroidSecrets(this)),
          device = Some(device),
          location = Some(AndroidLocation(this)),
          motion = Some(AndroidMotion(this)),
          clipboard = Some(AndroidClipboard(this)),
          haptics = Some(AndroidHaptics),
          pickers = Some {
            val p = AndroidPickers(this)
            (p.photos, p.documents)
          },
          camera = Some(AndroidCamera(this)),
          share = Some(AndroidShare(this)),
          cameraPreview = Some(AndroidCameraPreview(this, device))
        )
      ),
      nativeView = NativeLabel(_)
    )
  }

  override protected def started(): Unit =
    if getIntent != null && getIntent.getBooleanExtra("selftest", false) then GallerySelfTest.run(this)

  /** An asset's bytes, or none: a gallery that will not start because an image is missing shows less than one without
    * it.
    */
  private def assetBytes(path: String): Array[Byte] =
    try {
      val in = getAssets.open(path)
      try in.readAllBytes()
      finally in.close()
    } catch { case _: java.io.IOException => Array.emptyByteArray }

}

/** The Platform section's view on Android: a plain `TextView`, made here, outside the renderer, as a camera preview's
  * module would make its view (docs/17 §17.3). It records what it made and what it was told to release, for
  * [[GallerySelfTest]].
  */
final class NativeLabel(text: String) extends AndroidPlatformView {

  val description = "A native label"

  def create(context: Context): View = {
    val v = TextView(context)
    v.setText(text)
    NativeLabel.made += v
    if NativeLabel.made.length > 2 then NativeLabel.made.remove(0, NativeLabel.made.length - 2)
    v
  }

  /** Records whether the view was still in the layout: the renderer must release it before removing it. */
  override def release(view: View): Unit = NativeLabel.released += (view -> (view.getParent != null))

}

object NativeLabel {

  val made = mutable.ArrayBuffer.empty[View]
  val released = mutable.ArrayBuffer.empty[(View, Boolean)]

}
