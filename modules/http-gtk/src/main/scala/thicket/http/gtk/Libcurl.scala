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

package thicket.http.gtk

import scala.scalanative.posix.dlfcn
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
private object GcThreadState {

  def scalanative_GC_set_mutator_thread_state(state: CInt): Unit = extern

}

/** `thicket_curl.c`: libcurl's variadic `curl_easy_setopt`, called through the pointer `dlsym` found. */
@extern
private object CurlVariadic {

  def thk_curl_setopt_long(
    fn:     Ptr[Byte],
    curl:   Ptr[Byte],
    option: CInt,
    value:  CLong
  ): CInt = extern

  def thk_curl_setopt_off(
    fn:     Ptr[Byte],
    curl:   Ptr[Byte],
    option: CInt,
    value:  CLongLong
  ): CInt = extern

  def thk_curl_setopt_ptr(
    fn:     Ptr[Byte],
    curl:   Ptr[Byte],
    option: CInt,
    value:  Ptr[Byte]
  ): CInt = extern

}

@extern
private object MemStream {

  /** POSIX 2008: a `FILE*` whose writes grow a buffer that `fclose` hands back in `*ptr`, `*size` bytes long. */
  def open_memstream(
    ptr:  Ptr[Ptr[Byte]],
    size: Ptr[CSize]
  ): Ptr[Byte] = extern

}

/** The libcurl functions `LibcurlBackend` uses, found at run time with `dlopen`, so an app that uses `thicket-http-gtk`
  * still starts on a system without libcurl, and building it needs no `-dev` package.
  *
  * A request runs on curl's multi interface, which nothing here gives a callback: the body and the headers go to
  * `open_memstream` buffers through curl's default writer, `fwrite`. So no Scala runs inside curl, and the two calls
  * that wait — `curl_multi_perform` and `curl_multi_poll` — run with the thread Unmanaged, as an `@blocking` extern
  * would: a collection started by another thread meanwhile does not wait for the network.
  */
final private[gtk] class Libcurl private (
  setoptFn:      Ptr[Byte],
  easyInit:      CFuncPtr0[Ptr[Byte]],
  easyCleanup:   CFuncPtr1[Ptr[Byte], Unit],
  easyStrerror:  CFuncPtr1[CInt, CString],
  slistAppend:   CFuncPtr2[Ptr[Byte], CString, Ptr[Byte]],
  slistFreeAll:  CFuncPtr1[Ptr[Byte], Unit],
  multiInit:     CFuncPtr0[Ptr[Byte]],
  multiAdd:      CFuncPtr2[Ptr[Byte], Ptr[Byte], CInt],
  multiRemove:   CFuncPtr2[Ptr[Byte], Ptr[Byte], CInt],
  multiCleanup:  CFuncPtr1[Ptr[Byte], CInt],
  multiPerform:  CFuncPtr2[Ptr[Byte], Ptr[CInt], CInt],
  multiPoll:     CFuncPtr5[Ptr[Byte], Ptr[Byte], CUnsignedInt, CInt, Ptr[CInt], CInt],
  multiInfoRead: CFuncPtr2[Ptr[Byte], Ptr[CInt], Ptr[Byte]],
  multiStrerror: CFuncPtr1[CInt, CString]
) {

  import Libcurl.*

  def easy():                   Ptr[Byte] = easyInit()
  def cleanup(easy: Ptr[Byte]): Unit = easyCleanup(easy)

  def setLong(
    easy:   Ptr[Byte],
    option: CInt,
    value:  Long
  ): Unit = ok(CurlVariadic.thk_curl_setopt_long(setoptFn, easy, option, value.toSize), option)

  def setOff(
    easy:   Ptr[Byte],
    option: CInt,
    value:  Long
  ): Unit = ok(CurlVariadic.thk_curl_setopt_off(setoptFn, easy, option, value), option)

  def setPtr(
    easy:   Ptr[Byte],
    option: CInt,
    value:  Ptr[Byte]
  ): Unit = ok(CurlVariadic.thk_curl_setopt_ptr(setoptFn, easy, option, value), option)

  def strerror(code: Int): String = fromCString(easyStrerror(code))

  def append(
    list: Ptr[Byte],
    line: CString
  ): Ptr[Byte] = {
    val l = slistAppend(list, line)
    if l == null then throw new OutOfMemoryError("curl_slist_append")
    l
  }

  def freeList(list: Ptr[Byte]): Unit = if list != null then slistFreeAll(list)

  def multi(): Ptr[Byte] = multiInit()

  def add(
    multi: Ptr[Byte],
    easy:  Ptr[Byte]
  ): Unit = multiOk(multiAdd(multi, easy), "curl_multi_add_handle")

  def remove(
    multi: Ptr[Byte],
    easy:  Ptr[Byte]
  ): Unit = { multiRemove(multi, easy); () }

  def cleanupMulti(multi: Ptr[Byte]): Unit = { multiCleanup(multi); () }

  /** `curl_multi_perform`, Unmanaged: it may resolve, connect and write the buffers, but runs no Scala. */
  def perform(
    multi:   Ptr[Byte],
    running: Ptr[CInt]
  ): Unit = multiOk(foreign(multiPerform(multi, running)), "curl_multi_perform")

  /** `curl_multi_poll`, Unmanaged: waits up to `ms` for activity on the transfer's sockets. */
  def poll(
    multi: Ptr[Byte],
    ms:    Int,
    fds:   Ptr[CInt]
  ): Unit = multiOk(foreign(multiPoll(multi, null, 0.toUInt, ms, fds)), "curl_multi_poll")

  /** The finished transfer's `CURLcode`, or `None` while it runs. */
  def done(
    multi: Ptr[Byte],
    queue: Ptr[CInt]
  ): Option[Int] = {
    // CURLMsg: { CURLMSG msg; CURL *easy_handle; union { void *whatever; CURLcode result; } data } — the union at 16
    // on a 64-bit platform. CURLMSG_DONE is 1. One transfer per multi handle, so the first DONE is ours.
    var result: Option[Int] = None
    var msg = multiInfoRead(multi, queue)
    while msg != null do {
      if !msg.asInstanceOf[Ptr[CInt]] == 1 then result = Some(!(msg + 16).asInstanceOf[Ptr[CInt]])
      msg = multiInfoRead(multi, queue)
    }
    result
  }

  private def ok(
    code:   CInt,
    option: CInt
  ): Unit =
    if code != 0 then throw new java.io.IOException(s"curl_easy_setopt($option): ${strerror(code)}")

  private def multiOk(
    code: CInt,
    what: String
  ): Unit =
    if code != 0 then throw new java.io.IOException(s"$what: ${fromCString(multiStrerror(code))}")

}

private[gtk] object Libcurl {

  /** libcurl's soname: the runtime library, installed wherever anything uses it, unlike the `-dev` link. */
  val soname: String = "libcurl.so.4"

  inline private val Managed = 0
  inline private val Unmanaged = 1

  /** Runs a foreign call with the thread Unmanaged. Its arguments are evaluated first, while still Managed. */
  inline private def foreign[A](inline call: A): A = {
    GcThreadState.scalanative_GC_set_mutator_thread_state(Unmanaged)
    try call
    finally GcThreadState.scalanative_GC_set_mutator_thread_state(Managed)
  }

  private var loaded: Option[Either[String, Libcurl]] = None

  /** libcurl, loaded and initialised once per process; `Left` says why it is not there. */
  def load(): Either[String, Libcurl] =
    synchronized {
      loaded.getOrElse {
        val l = open(soname)
        loaded = Some(l)
        l
      }
    }

  private def open(library: String): Either[String, Libcurl] =
    if sizeOf[Ptr[Byte]] != 8.toUSize then Left("libcurl's CURLMsg is read as laid out on a 64-bit platform")
    else
      Zone {
        val handle = dlfcn.dlopen(toCString(library), dlfcn.RTLD_NOW | dlfcn.RTLD_LOCAL)
        if handle == null then Left(s"no $library: ${fromCString(dlfcn.dlerror())}")
        else {
          val names = List(
            "curl_global_init",
            "curl_easy_setopt",
            "curl_easy_init",
            "curl_easy_cleanup",
            "curl_easy_strerror",
            "curl_slist_append",
            "curl_slist_free_all",
            "curl_multi_init",
            "curl_multi_add_handle",
            "curl_multi_remove_handle",
            "curl_multi_cleanup",
            "curl_multi_perform",
            "curl_multi_poll", // 7.66, 2019
            "curl_multi_info_read",
            "curl_multi_strerror"
          )
          val found = names.map(n => n -> dlfcn.dlsym(handle, toCString(n)).asInstanceOf[Ptr[Byte]]).toMap
          val missing = names.filter(found(_) == null)
          if missing.nonEmpty then Left(s"$library lacks ${missing.mkString(", ")}")
          else {
            // curl_global_init is not thread-safe; this runs once, under the lock. CURL_GLOBAL_ALL is 3.
            val globalInit = CFuncPtr.fromPtr[CFuncPtr1[CLong, CInt]](found("curl_global_init"))
            val init = globalInit(3.toSize)
            if init != 0 then Left(s"curl_global_init failed with $init")
            else
              Right(
                new Libcurl(
                  found("curl_easy_setopt"),
                  CFuncPtr.fromPtr(found("curl_easy_init")),
                  CFuncPtr.fromPtr(found("curl_easy_cleanup")),
                  CFuncPtr.fromPtr(found("curl_easy_strerror")),
                  CFuncPtr.fromPtr(found("curl_slist_append")),
                  CFuncPtr.fromPtr(found("curl_slist_free_all")),
                  CFuncPtr.fromPtr(found("curl_multi_init")),
                  CFuncPtr.fromPtr(found("curl_multi_add_handle")),
                  CFuncPtr.fromPtr(found("curl_multi_remove_handle")),
                  CFuncPtr.fromPtr(found("curl_multi_cleanup")),
                  CFuncPtr.fromPtr(found("curl_multi_perform")),
                  CFuncPtr.fromPtr(found("curl_multi_poll")),
                  CFuncPtr.fromPtr(found("curl_multi_info_read")),
                  CFuncPtr.fromPtr(found("curl_multi_strerror"))
                )
              )
          }
        }
      }

  // The options used, from curl.h. The type is in the id: LONG + 0, OBJECTPOINT + 10000, OFF_T + 30000.
  val WriteData = 10001
  val Url = 10002
  val PostFieldsSize = 30120 // CURLOPT_POSTFIELDSIZE_LARGE
  val CopyPostFields = 10165
  val HttpHeader = 10023
  val HeaderData = 10029
  val CustomRequest = 10036
  val NoBody = 44
  val HttpGet = 80
  val NoSignal = 99
  val ConnectTimeoutMs = 156
  val AcceptEncoding = 10102

}
