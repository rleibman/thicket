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

package thicket.renderer.android

import _root_.android.graphics.{Canvas, ColorFilter, Matrix, Paint, Path, PixelFormat}
import _root_.android.graphics.drawable.Drawable
import thicket.renderer.{MaterialSymbols, SvgPath}

/** A curated icon drawn from its Material Symbols path data (#61), filled in one colour.
  *
  * A drawable rather than a resource because the renderer ships as a plain jar, which cannot carry Android resources.
  * The path is parsed once; drawing scales it from the symbols' `0 -960 960 960` view box into whatever bounds the
  * view gives it, so it is sharp at every density. 24dp intrinsic, the size Material uses for an icon.
  */
final class IconDrawable(
  pathData: String,
  sizePx:   Int,
  colour:   Int
) extends Drawable {

  private val source: Path = {
    val p = Path()
    SvgPath.parse(pathData).foreach {
      case SvgPath.Cmd.MoveTo(x, y)           => p.moveTo(x, y)
      case SvgPath.Cmd.LineTo(x, y)           => p.lineTo(x, y)
      case SvgPath.Cmd.QuadTo(cx, cy, x, y)   => p.quadTo(cx, cy, x, y)
      case SvgPath.Cmd.Close                  => p.close()
    }
    p
  }

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  paint.setStyle(Paint.Style.FILL)
  paint.setColor(colour)

  private val scaled = Path()

  override def draw(canvas: Canvas): Unit = {
    val b = getBounds
    val m = Matrix()
    // The view box's top edge is at y = -960: move it to 0, then scale 960 units to the bounds.
    m.setTranslate(0f, MaterialSymbols.viewBoxSize)
    m.postScale(b.width / MaterialSymbols.viewBoxSize, b.height / MaterialSymbols.viewBoxSize)
    m.postTranslate(b.left.toFloat, b.top.toFloat)
    source.transform(m, scaled)
    canvas.drawPath(scaled, paint)
  }

  override def getIntrinsicWidth: Int  = sizePx
  override def getIntrinsicHeight: Int = sizePx

  override def setAlpha(alpha: Int): Unit = { paint.setAlpha(alpha); invalidateSelf() }

  override def setColorFilter(filter: ColorFilter): Unit = { paint.setColorFilter(filter); invalidateSelf() }

  @SuppressWarnings(Array("deprecation"))
  override def getOpacity: Int = PixelFormat.TRANSLUCENT

}
