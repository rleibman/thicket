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

package thicket.pickers.apple

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.scalanative.unsafe.*
import thicket.core.GcState
import thicket.device.Availability
import thicket.pickers.{Documents, Photos, PickedFiles}
import thicket.renderer.apple.AppleApp

/** The pickers on macOS and iOS (#125), through `libthicketpickers.a`:
  *
  *   - **iOS:** `PHPickerViewController` for photos (no permission needed), and `UIDocumentPickerViewController` to
  *     open documents (`asCopy`) and to export one;
  *   - **macOS:** `NSOpenPanel` and `NSSavePanel`, as sheets on the app's window.
  *
  * Each picked file is copied into `cache` on a worker thread, through [[PickedFiles]], and the copies are handed back
  * on the main thread (owner's decision, 2026-10-30), as on GTK and Android. A cancel answers empty, or `None` for
  * `save`; so does a request while a picker is up (one at a time).
  *
  * `save` writes the bytes to a file of its own under `cache` first: iOS's exporter copies a file, not bytes. On macOS
  * the panel answers a destination, and the file is copied there.
  */
final class ApplePickers(cache: Path) {

  import ApplePickers.*

  private val picked = PickedFiles(cache)

  val photos: Photos = new Photos {

    def availability: Availability = Availability.Available

    def pick(limit: Int)(onResult: Seq[Path] => Unit): Unit = {
      val id = start(Pick(picked, onResult))
      if PickersShim.thk_pick_photos(math.max(limit, 1), file, done, id) == 0 then refused(id)
    }

  }

  val documents: Documents = new Documents {

    def availability: Availability = Availability.Available

    def open(
      mimeTypes: Seq[String],
      multiple:  Boolean
    )(
      onResult: Seq[Path] => Unit
    ): Unit = {
      val id = start(Pick(picked, onResult))
      val shown = Zone(
        PickersShim.thk_pick_documents(toCString(mimeTypes.mkString("\n")), if multiple then 1 else 0, file, done, id)
      )
      if shown == 0 then refused(id)
    }

    def save(
      name:     String,
      mimeType: String,
      bytes:    Array[Byte]
    )(
      onResult: Option[String] => Unit
    ): Unit = {
      def discard(): Unit = { scala.util.Try { Files.deleteIfExists(file); Files.deleteIfExists(dir) }; () }
        if !written then onResult(None)
        else {
          val id = nextId.getAndIncrement()
          saves.put(id, Save(file, () => discard(), onResult))
          val shown = Zone(PickersShim.thk_pick_save(toCString(file.toString), toCString(mimeType), answered, id))
          if shown == 0 then {
            saves.remove(id)
            discard()
            onResult(None)
          }
        }
      }
    }

  }

}

object ApplePickers {

  /** A pick waiting for its `done`: the copies made so far, by where they stand in the user's selection. */
  final private class Pick(
    val picked:   PickedFiles,
    val onResult: Seq[Path] => Unit
  ) {

    val copies = ConcurrentLinkedQueue[(Int, Path)]()

  }

  final private class Save(
    val file:     Path,
    val discard:  () => Unit,
    val onResult: Option[String] => Unit
  )

  // A CFuncPtr cannot close over state: the requests wait here under the id that travels as the callbacks' context.
  private val picks = ConcurrentHashMap[java.lang.Long, Pick]()
  private val saves = ConcurrentHashMap[java.lang.Long, Save]()
  private val nextId = AtomicLong(1L)

  private def start(p: Pick): Long = {
    val id = nextId.getAndIncrement()
    picks.put(id, p)
    id
  }

  /** Not shown (a picker is up, or there is no window): empty, on the next turn of the main loop. */
  private def refused(id: Long): Unit = {
    val p = picks.remove(id)
    if p != null then AppleApp.postToUi(() => p.onResult(Nil))
  }

  /** A picked file, on the main thread: copied on a worker thread, then released, which lets the platform delete it. */
  private val file: PickersShim.IntCb =
    CFuncPtr2.fromScalaFunction {
      (
        ctx:  Long,
        hold: CInt
      ) =>
        GcState.guarded {
          val p = picks.get(ctx)
          val c = PickersShim.thk_pick_path(hold)
          val source = if c == null then None else Some(Path.of(fromCString(c)))
          val index = PickersShim.thk_pick_index(hold)
          val t = Thread(() =>
            try
              for {
                pick <- Option(p)
                s    <- source
              } do
                scala.util
                  .Try(pick.picked.copy(s.getFileName.toString, Files.newInputStream(s)))
                  .foreach(copy => pick.copies.add(index -> copy))
            finally PickersShim.thk_pick_release(hold)
          )
          t.setName("thicket-pickers")
          t.start()
        }
    }

  /** Every file released: the copies, in the order the user's selection gave them. Empty for a cancel. */
  private val done: PickersShim.IntCb =
    CFuncPtr2.fromScalaFunction {
      (
        ctx: Long,
        _:   CInt
      ) =>
        GcState.guarded {
          val p = picks.remove(ctx)
          if p != null then p.onResult(p.copies.asScala.toList.sortBy(_._1).map(_._2))
        }
    }

  /** The save's answer: 0 cancelled, 1 saved by the system (iOS), 2 a destination to copy the file to (macOS). */
  private val answered: PickersShim.IntCb =
    CFuncPtr2.fromScalaFunction {
      (
        ctx:  Long,
        code: CInt
      ) =>
        GcState.guarded {
          val s = saves.remove(ctx)
          if s != null then
            code match {
              case 1 =>
                s.discard()
                s.onResult(Some(s.file.getFileName.toString))
              case 2 =>
                val c = PickersShim.thk_pick_save_path()
                val dest = if c == null then None else Some(Path.of(fromCString(c)))
                onWorker(dest.flatMap { d =>
                  scala.util.Try(Files.copy(s.file, d, StandardCopyOption.REPLACE_EXISTING)).toOption.map(_ => d)
                }) { saved =>
                  s.discard()
                  s.onResult(saved.map(_.getFileName.toString))
                }
              case _ =>
                s.discard()
                s.onResult(None)
            }
        }
    }

  /** Runs `work` on a thread of its own and hands its result to `deliver` on the main thread. */
  private def onWorker[A](work: => A)(deliver: A => Unit): Unit = {
    val t = Thread(() => {
      val a = work
      AppleApp.postToUi(() => deliver(a))
    })
    t.setName("thicket-pickers")
    t.start()
  }

  /** For self-tests: what the platform holds, and what a user would do (`thk_pick_test_*`). */
  object testing {

    /** How many pickers this process has shown. */
    def shown: Int = PickersShim.thk_pick_test_shown()

    /** The picker that is up, as the platform holds it (`"open types=public.image multiple=0 sheet=1"`), or `""`. */
    def describe: String = fromCString(PickersShim.thk_pick_test_describe())

    /** macOS: the panel's own `cancel(_:)`. False when no panel is up, and always on iOS. */
    def cancel(): Boolean = PickersShim.thk_pick_test_cancel() != 0

    /** macOS: closes the panel and answers it with `paths`, through its own completion. False when none is up. */
    def choose(paths: Seq[Path]): Boolean =
      Zone(PickersShim.thk_pick_test_choose(toCString(paths.map(_.toString).mkString("\n")))) != 0

  }

}
