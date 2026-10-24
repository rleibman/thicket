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

package thicket.storage

/** [[Preferences]] over a store that only holds strings: each value is kept with its type, as `"l:42"`, so it reads
  * back as the type it was written as on every platform. The file implementation and Android's both use it, so a value
  * means the same thing on each.
  */
abstract class StringPreferences extends Preferences {

  /** The raw, tagged value. */
  protected def read(key: String): Option[String]

  protected def write(
    key:    String,
    tagged: String
  ): Unit

  def getString(key:  String): Option[String] = typed(key, 's')(Some(_))
  def getLong(key:    String): Option[Long] = typed(key, 'l')(_.toLongOption)
  def getDouble(key:  String): Option[Double] = typed(key, 'd')(_.toDoubleOption)
  def getBoolean(key: String): Option[Boolean] = typed(key, 'b')(_.toBooleanOption)

  def putString(
    key:   String,
    value: String
  ): Unit = write(key, s"s:$value")
  def putLong(
    key:   String,
    value: Long
  ): Unit = write(key, s"l:$value")
  // `toString` round-trips a Double exactly, NaN and the infinities included.
  def putDouble(
    key:   String,
    value: Double
  ): Unit = write(key, s"d:$value")
  def putBoolean(
    key:   String,
    value: Boolean
  ): Unit = write(key, s"b:$value")

  private def typed[A](
    key: String,
    tag: Char
  )(
    parse: String => Option[A]
  ): Option[A] =
    read(key)
      .collect { case v if v.length >= 2 && v.charAt(0) == tag && v.charAt(1) == ':' => v.substring(2) }
      .flatMap(parse)

}
