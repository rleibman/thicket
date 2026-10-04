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

package thicket.core

import zio.test.*
import thicket.signals.Checks

import thicket.core.dsl.*
import thicket.renderer.{ContentFit, ImageSource}
import thicket.signals.{Owner, Var}

/** Images render *local* data. Fetching is the app's job, which is what lets loading and failure be the same exhaustive
  * match as every other async value.
  */

object ImageSpec extends ZIOSpecDefault {

  def spec =
    suite("Image")(
      test("a picture from bytes") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(r, Column()(Image(ImageSource.FromBytes(Array[Byte](1, 2, 3)))))
        val img = r.childrenOf(m.handle).head
        chk.eq(r.nodes(img).props("picture"), "bytes:3")
        chk.eq(r.nodes(img).props("fit"), "Contain")
        o.dispose()
        chk.result
      },
      test("a picture from a file, with an explicit fit") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val m = Reconciler.mount(
          r,
          Column()(Image(ImageSource.FromFile("/tmp/a.png"), fit = ContentFit.Cover))
        )
        val img = r.childrenOf(m.handle).head
        chk.eq(r.nodes(img).props("picture"), "file:/tmp/a.png")
        chk.eq(r.nodes(img).props("fit"), "Cover")
        o.dispose()
        chk.result
      },
      test("a signal-driven picture swaps in place") {
        val chk = Checks()
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val src = Var[ImageSource](ImageSource.FromFile("/tmp/a.png"))
        val m = Reconciler.mount(r, Column()(Image(src)))
        val img = r.childrenOf(m.handle).head

        chk.eq(r.nodes(img).props("picture"), "file:/tmp/a.png")
        val created = r.createCount
        src.set(ImageSource.FromFile("/tmp/b.png"))
        chk.eq(r.nodes(img).props("picture"), "file:/tmp/b.png")
        chk.eq(r.createCount, created, "the widget was reused, not rebuilt")
        o.dispose()
        chk.result
      },
      test("it composes with RemoteData: loading, failure and the picture are one match") {
        val chk = Checks()
        // This is the whole argument for not putting a fetcher in the framework — an image
        // that arrives over the network is just another async value.
        val o = Owner(); given Owner = o
        val r = TestRenderer()
        val state = Var[RemoteData[String, ImageSource]](RemoteData.Loading)

        val m = Reconciler.mount(
          r,
          Column()(
            RemoteData(state) {
              case RemoteData.Loading   => Label("Loading…")
              case RemoteData.Failed(e) => Label(s"No image: $e")
              case RemoteData.Done(src) => Image(src)
            }
          )
        )

        def only: Int = r.childrenOf(m.handle).head
        chk.eq(r.text(only), "Loading…")

        state.set(RemoteData.Failed("offline"))
        chk.eq(r.text(only), "No image: offline")

        state.set(RemoteData.Done(ImageSource.FromBytes(Array[Byte](9, 9))))
        chk.eq(r.nodes(only).props("picture"), "bytes:2")
        chk.eq(r.kind(only), thicket.renderer.WidgetKind.Image)
        o.dispose()
        chk.result
      }
    ) @@ TestAspect.sequential

}
