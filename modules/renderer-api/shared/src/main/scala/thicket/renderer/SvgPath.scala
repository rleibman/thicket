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

package thicket.renderer

/** The subset of SVG path data the bundled icons use, turned into absolute drawing commands.
  *
  * Android has no system icon set an app should show and the renderer ships as a plain jar, which cannot carry Android
  * resources, so the curated [[Icon]]s are drawn on Android from Material Symbols path data (#61). This is the parser
  * for that data. It lives here, not in the Android renderer, so it is tested on every backend rather than only on a
  * device.
  *
  * Supported: `M L H V Q T Z`, absolute and relative, with implicit repeats and SVG's compact number syntax (`57-57`,
  * `.5.5`). That is every command in the bundled set; `C`, `S` and `A` are refused rather than guessed at, so an icon
  * added later that needs them fails loudly in a test.
  */
object SvgPath {

  enum Cmd {

    case MoveTo(
      x: Float,
      y: Float
    )
    case LineTo(
      x: Float,
      y: Float
    )
    case QuadTo(
      cx: Float,
      cy: Float,
      x:  Float,
      y:  Float
    )
    case Close

  }

  def parse(d: String): Vector[Cmd] = {
    val out = Vector.newBuilder[Cmd]
    val tokens = tokenize(d)
    var i = 0
    var cmd = ' '
    // Current point, the start of the current subpath, and the last quadratic control point
    // (for T, which reflects it).
    var x, y, sx, sy = 0f
    var qx, qy = 0f
    var lastWasQuad = false

    def num(): Float = {
      tokens(i) match {
        case Right(v) => i += 1; v
        case Left(c)  => throw IllegalArgumentException(s"expected a number in path data, found '$c'")
      }
    }

    while i < tokens.length do {
      tokens(i) match {
        case Left(c) =>
          cmd = c
          i += 1
        case Right(_) =>
          // A number where a command letter would be: the previous command repeats, except
          // that a repeated moveto is a lineto.
          if cmd == 'M' then cmd = 'L' else if cmd == 'm' then cmd = 'l'
      }
      val rel = cmd.isLower
      cmd.toUpper match {
        case 'M' =>
          val nx = num() + (if rel then x else 0f)
          val ny = num() + (if rel then y else 0f)
          x = nx; y = ny; sx = nx; sy = ny
          out += Cmd.MoveTo(x, y)
          lastWasQuad = false
        case 'L' =>
          x = num() + (if rel then x else 0f)
          y = num() + (if rel then y else 0f)
          out += Cmd.LineTo(x, y)
          lastWasQuad = false
        case 'H' =>
          x = num() + (if rel then x else 0f)
          out += Cmd.LineTo(x, y)
          lastWasQuad = false
        case 'V' =>
          y = num() + (if rel then y else 0f)
          out += Cmd.LineTo(x, y)
          lastWasQuad = false
        case 'Q' =>
          val cx = num() + (if rel then x else 0f)
          val cy = num() + (if rel then y else 0f)
          val nx = num() + (if rel then x else 0f)
          val ny = num() + (if rel then y else 0f)
          out += Cmd.QuadTo(cx, cy, nx, ny)
          qx = cx; qy = cy; x = nx; y = ny
          lastWasQuad = true
        case 'T' =>
          // The control point is the previous one reflected through the current point, or the
          // current point itself when the previous command was not a quadratic.
          val cx = if lastWasQuad then 2 * x - qx else x
          val cy = if lastWasQuad then 2 * y - qy else y
          val nx = num() + (if rel then x else 0f)
          val ny = num() + (if rel then y else 0f)
          out += Cmd.QuadTo(cx, cy, nx, ny)
          qx = cx; qy = cy; x = nx; y = ny
          lastWasQuad = true
        case 'Z' =>
          out += Cmd.Close
          x = sx; y = sy
          lastWasQuad = false
        case other =>
          throw IllegalArgumentException(s"unsupported path command '$other' (supported: M L H V Q T Z)")
      }
    }
    out.result()
  }

  /** Letters and numbers. SVG numbers need no separators: `57-57` is two numbers, and so is `.5.5`. */
  private def tokenize(d: String): Vector[Either[Char, Float]] = {
    val out = Vector.newBuilder[Either[Char, Float]]
    var i = 0
    while i < d.length do {
      val c = d.charAt(i)
      if c.isLetter && c != 'e' && c != 'E' then { out += Left(c); i += 1 }
      else if c == '-' || c == '+' || c == '.' || c.isDigit then {
        val start = i
        var seenDot = c == '.'
        i += 1
        while i < d.length && (d.charAt(i).isDigit || (d.charAt(i) == '.' && !seenDot)) do {
          if d.charAt(i) == '.' then seenDot = true
          i += 1
        }
        out += Right(d.substring(start, i).toFloat)
      } else i += 1 // separators: spaces and commas
    }
    out.result()
  }

}
