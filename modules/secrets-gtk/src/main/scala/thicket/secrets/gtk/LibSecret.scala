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

package thicket.secrets.gtk

import scala.scalanative.libc.stdlib
import scala.scalanative.posix.dlfcn
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
private object GcThreadState {

  def scalanative_GC_set_mutator_thread_state(state: CInt): Unit = extern

}

/** libsecret's password functions, found at run time with `dlopen`, so an app that uses `thicket-secrets-gtk` still
  * starts on a system without libsecret — its secrets are `Unavailable` there — and building it needs no `-dev`
  * package.
  *
  * Only the non-variadic forms (`secret_password_storev_sync` and the rest), since a variadic C function cannot be
  * called through a function pointer. Each call blocks on D-Bus, so the thread is marked Unmanaged for its length, as
  * an `@blocking` extern would be: a collection started by another thread meanwhile must not wait for this one.
  */
final private[gtk] class LibSecret private (
  storeFn:   CFuncPtr7[Ptr[Byte], Ptr[Byte], CString, CString, CString, Ptr[Byte], Ptr[Ptr[Byte]], CInt],
  lookupFn:  CFuncPtr4[Ptr[Byte], Ptr[Byte], Ptr[Byte], Ptr[Ptr[Byte]], CString],
  clearFn:   CFuncPtr4[Ptr[Byte], Ptr[Byte], Ptr[Byte], Ptr[Ptr[Byte]], CInt],
  freeFn:    CFuncPtr1[CString, Unit],
  hashNew:   CFuncPtr2[Ptr[Byte], Ptr[Byte], Ptr[Byte]],
  hashPut:   CFuncPtr3[Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt],
  hashUnref: CFuncPtr1[Ptr[Byte], Unit],
  strHash:   Ptr[Byte],
  strEqual:  Ptr[Byte],
  errorFree: CFuncPtr1[Ptr[Byte], Unit]
) {

  import LibSecret.*

  /** The secret for (`app`, `key`), or `None`. */
  def lookup(
    app: String,
    key: String
  ): Option[String] =
    Zone {
      withAttributes(app, key) { attrs =>
        val err = stackalloc[Ptr[Byte]]()
        !err = null
        val found = foreign(lookupFn(schema, attrs, null, err))
        check(err, "look up")
        if found == null then None
        else
          try Some(fromCString(found, java.nio.charset.StandardCharsets.UTF_8))
          finally freeFn(found) // secret_password_free wipes the copy before freeing it
      }
    }

  def store(
    app:   String,
    key:   String,
    label: String,
    value: String
  ): Unit =
    Zone {
      withAttributes(app, key) { attrs =>
        val err = stackalloc[Ptr[Byte]]()
        !err = null
        val l = toCString(label, java.nio.charset.StandardCharsets.UTF_8)
        val v = toCString(value, java.nio.charset.StandardCharsets.UTF_8)
        // A null collection is the default one, the login keyring on GNOME.
        val ok = foreign(storeFn(schema, attrs, null, l, v, null, err))
        check(err, "store")
        if ok == 0 then throw new thicket.secrets.SecretsException(s"libsecret could not store '$key'")
      }
    }

  def clear(
    app: String,
    key: String
  ): Unit =
    Zone {
      withAttributes(app, key) { attrs =>
        val err = stackalloc[Ptr[Byte]]()
        !err = null
        // Returns false when nothing matched, which is not an error.
        foreign(clearFn(schema, attrs, null, err))
        check(err, "delete")
      }
    }

  /** A `GHashTable` of the two attributes, alive for `body`; the strings are the zone's. */
  private def withAttributes[A](
    app: String,
    key: String
  )(
    body: Ptr[Byte] => A
  )(using Zone
  ): A = {
    val table = hashNew(strHash, strEqual)
    try {
      hashPut(table, c"app".asInstanceOf[Ptr[Byte]], toCString(app).asInstanceOf[Ptr[Byte]])
      hashPut(
        table,
        c"key".asInstanceOf[Ptr[Byte]],
        toCString(key, java.nio.charset.StandardCharsets.UTF_8).asInstanceOf[Ptr[Byte]]
      )
      body(table)
    } finally hashUnref(table)
  }

  /** Throws the `GError` libsecret set, if it set one, and frees it. */
  private def check(
    err:  Ptr[Ptr[Byte]],
    what: String
  ): Unit =
    if !err != null then {
      // GError: { GQuark domain; gint code; gchar *message }
      val message = fromCString(!(!err + 8).asInstanceOf[Ptr[CString]])
      errorFree(!err)
      throw new thicket.secrets.SecretsException(s"libsecret could not $what: $message")
    }

}

private[gtk] object LibSecret {

  /** libsecret's soname: the runtime library, which is installed wherever anything uses it, unlike the `-dev` link. */
  val soname: String = "libsecret-1.so.0"

  inline private val Managed = 0
  inline private val Unmanaged = 1

  /** Runs a blocking foreign call with the thread Unmanaged. Its arguments are evaluated first, while still Managed. */
  inline private def foreign[A](inline call: A): A = {
    GcThreadState.scalanative_GC_set_mutator_thread_state(Unmanaged)
    try call
    finally GcThreadState.scalanative_GC_set_mutator_thread_state(Managed)
  }

  // `SecretSchema` "dev.thicket.Secret", with the attributes "app" and "key", laid out by hand: { const gchar *name; SecretSchemaFlags flags;
  // SecretSchemaAttribute attributes[32] (each { const gchar *name; int type }); gint reserved;
  // gpointer reserved1..7 } — 592 bytes on a 64-bit platform. Allocated once and never freed:
  // libsecret keeps no pointer to it, but it is the same for every call.
  lazy private val schema: Ptr[Byte] = {
    val s = stdlib.calloc(592.toUSize, 1.toUSize)
    !s.asInstanceOf[Ptr[CString]] = c"dev.thicket.Secret"
    !(s + 16).asInstanceOf[Ptr[CString]] = c"app" // SECRET_SCHEMA_ATTRIBUTE_STRING is 0, as calloc left it
    !(s + 32).asInstanceOf[Ptr[CString]] = c"key"
    s
  }

  private var loaded: Option[Either[String, LibSecret]] = None

  /** libsecret, loaded once per process; `Left` says why it is not there. */
  def load(library: String = soname): Either[String, LibSecret] =
    synchronized {
      if library != soname then open(library)
      else
        loaded.getOrElse {
          val l = open(library)
          loaded = Some(l)
          l
        }
    }

  private def open(library: String): Either[String, LibSecret] =
    if sizeOf[Ptr[Byte]] != 8.toUSize then Left("libsecret's schema is laid out for a 64-bit platform")
    else
      Zone {
        val handle = dlfcn.dlopen(toCString(library), dlfcn.RTLD_NOW | dlfcn.RTLD_LOCAL)
        if handle == null then Left(s"no $library: ${fromCString(dlfcn.dlerror())}")
        else {
          // dlsym on the handle searches libsecret's own dependencies too, so GLib's symbols come from here.
          val names = List(
            "secret_password_storev_sync",
            "secret_password_lookupv_sync",
            "secret_password_clearv_sync",
            "secret_password_free",
            "g_hash_table_new",
            "g_hash_table_insert",
            "g_hash_table_unref",
            "g_str_hash",
            "g_str_equal",
            "g_error_free"
          )
          val found = names.map(n => n -> dlfcn.dlsym(handle, toCString(n)).asInstanceOf[Ptr[Byte]]).toMap
          val missing = names.filter(found(_) == null)
          if missing.nonEmpty then Left(s"$library lacks ${missing.mkString(", ")}")
          else
            Right(
              new LibSecret(
                CFuncPtr.fromPtr(found("secret_password_storev_sync")),
                CFuncPtr.fromPtr(found("secret_password_lookupv_sync")),
                CFuncPtr.fromPtr(found("secret_password_clearv_sync")),
                CFuncPtr.fromPtr(found("secret_password_free")),
                CFuncPtr.fromPtr(found("g_hash_table_new")),
                CFuncPtr.fromPtr(found("g_hash_table_insert")),
                CFuncPtr.fromPtr(found("g_hash_table_unref")),
                found("g_str_hash"),
                found("g_str_equal"),
                CFuncPtr.fromPtr(found("g_error_free"))
              )
            )
        }
      }

}
