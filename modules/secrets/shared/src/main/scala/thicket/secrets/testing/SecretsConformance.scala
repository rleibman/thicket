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

package thicket.secrets.testing

import thicket.secrets.Secrets

/** What "secrets behave the same on every platform" means, as checks: run against [[MemorySecrets]] in the unit tests,
  * the Secret Service in the GTK gallery, and the Keystore in the Android one.
  *
  * Plain code rather than a test framework's, so a gallery self-test can run it. The checks use a real store, so every
  * key they write starts with `prefix`, and each check deletes what it wrote.
  */
object SecretsConformance {

  final case class Result(
    name:   String,
    passed: Boolean,
    detail: String
  )

  /** Every check. `open` gives a new instance of the store under test, the same one each time as far as its contents
    * go: one check writes through one instance and reads through another.
    */
  def run(
    open:   () => Secrets,
    prefix: String
  ): List[Result] = {
    val s = open()
    checks.zipWithIndex.map { case ((name, body), i) =>
      val key = s"$prefix$i"
      val outcome =
        try body(s, open, key)
        catch { case e: Throwable => Left(s"${e.getClass.getName}: ${e.getMessage}") }
        finally
          try Seq(key, s"$key.other").foreach(s.delete)
          catch { case _: Throwable => () }
      Result(name, outcome.isRight, outcome.fold(identity, identity))
    }
  }

  private def expect(
    cond:   Boolean,
    detail: => String
  ): Either[String, String] =
    if cond then Right("ok") else Left(detail)

  private def roundTrip(
    s:     Secrets,
    key:   String,
    value: String
  ): Either[String, String] = {
    s.set(key, value)
    val back = s.get(key)
    expect(
      back.contains(value),
      s"wrote ${value.length} characters, read back ${back.map(_.length)}: ${back.map(_.take(40))}"
    )
  }

  /** A check gets the store, a way to open another instance of it, and the key it may use (and `key.other`). */
  private type Check = (Secrets, () => Secrets, String) => Either[String, String]

  private val checks: List[(String, Check)] = List(
    "a key that was never set has no secret" -> {
      (
        s,
        _,
        key
      ) =>
        expect(s.get(key).isEmpty, s"got ${s.get(key)}")
    },
    "a secret reads back as it was written" -> {
      (
        s,
        _,
        key
      ) =>
        roundTrip(s, key, "hunter2")
    },
    "setting a key again replaces its secret" -> {
      (
        s,
        _,
        key
      ) =>
        s.set(key, "first")
        s.set(key, "second")
        expect(s.get(key).contains("second"), s"got ${s.get(key)}")
    },
    "delete forgets the secret" -> {
      (
        s,
        _,
        key
      ) =>
        s.set(key, "gone soon")
        s.delete(key)
        expect(s.get(key).isEmpty, s"still ${s.get(key)}")
    },
    "deleting a key that has no secret is not an error" -> {
      (
        s,
        _,
        key
      ) =>
        s.delete(key)
        expect(s.get(key).isEmpty, s"got ${s.get(key)}")
    },
    "keys are separate: setting and deleting one leaves another alone" -> {
      (
        s,
        _,
        key
      ) =>
        s.set(key, "one")
        s.set(s"$key.other", "two")
        s.delete(key)
        expect(
          s.get(key).isEmpty && s.get(s"$key.other").contains("two"),
          s"${s.get(key)}, ${s.get(s"$key.other")}"
        )
    },
    "Unicode, newlines and tabs round-trip" -> {
      (
        s,
        _,
        key
      ) =>
        roundTrip(s, key, "pässwörd ✓ 鍵 🔑\nline two\tafter a tab")
    },
    "an empty secret is a secret, not a missing one" -> {
      (
        s,
        _,
        key
      ) =>
        s.set(key, "")
        expect(s.get(key).contains(""), s"got ${s.get(key)}")
    },
    "a 16 KiB secret round-trips (a long token)" -> {
      (
        s,
        _,
        key
      ) =>
        roundTrip(s, key, "0123456789abcdef" * 1024)
    },
    "another instance of the store sees the secret, and its delete" -> {
      (
        s,
        open,
        key
      ) =>
        s.set(key, "shared")
        val other = open()
        val seen = other.get(key)
        other.delete(key)
        expect(seen.contains("shared") && s.get(key).isEmpty, s"other saw $seen; after its delete, ${s.get(key)}")
    },
    "an empty key is refused" -> {
      (
        s,
        _,
        _
      ) =>
        try {
          s.set("", "x")
          Left("no exception")
        } catch { case e: IllegalArgumentException => Right(e.getMessage) }
    }
  )

}
