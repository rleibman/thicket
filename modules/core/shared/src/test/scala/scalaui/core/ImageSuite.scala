package scalaui.core

import scalaui.core.dsl.*
import scalaui.renderer.{ContentFit, ImageSource}
import scalaui.signals.{Owner, Var}

/** Images render *local* data. Fetching is the app's job, which is what lets loading and
  * failure be the same exhaustive match as every other async value.
  */
class ImageSuite extends munit.FunSuite {

  test("a picture from bytes") {
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(r, Column()(Image(ImageSource.FromBytes(Array[Byte](1, 2, 3)))))
    val img = r.childrenOf(m.handle).head
    assertEquals(r.nodes(img).props("picture"), "bytes:3")
    assertEquals(r.nodes(img).props("fit"), "Contain")
    o.dispose()
  }

  test("a picture from a file, with an explicit fit") {
    val o = Owner(); given Owner = o
    val r = TestRenderer()
    val m = Reconciler.mount(
      r,
      Column()(Image(ImageSource.FromFile("/tmp/a.png"), fit = ContentFit.Cover))
    )
    val img = r.childrenOf(m.handle).head
    assertEquals(r.nodes(img).props("picture"), "file:/tmp/a.png")
    assertEquals(r.nodes(img).props("fit"), "Cover")
    o.dispose()
  }

  test("a signal-driven picture swaps in place") {
    val o = Owner(); given Owner = o
    val r   = TestRenderer()
    val src = Var[ImageSource](ImageSource.FromFile("/tmp/a.png"))
    val m   = Reconciler.mount(r, Column()(Image(src)))
    val img = r.childrenOf(m.handle).head

    assertEquals(r.nodes(img).props("picture"), "file:/tmp/a.png")
    val created = r.createCount
    src.set(ImageSource.FromFile("/tmp/b.png"))
    assertEquals(r.nodes(img).props("picture"), "file:/tmp/b.png")
    assertEquals(r.createCount, created, "the widget was reused, not rebuilt")
    o.dispose()
  }

  test("it composes with RemoteData: loading, failure and the picture are one match") {
    // This is the whole argument for not putting a fetcher in the framework — an image
    // that arrives over the network is just another async value.
    val o = Owner(); given Owner = o
    val r     = TestRenderer()
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
    assertEquals(r.text(only), "Loading…")

    state.set(RemoteData.Failed("offline"))
    assertEquals(r.text(only), "No image: offline")

    state.set(RemoteData.Done(ImageSource.FromBytes(Array[Byte](9, 9))))
    assertEquals(r.nodes(only).props("picture"), "bytes:2")
    assertEquals(r.kind(only), scalaui.renderer.WidgetKind.Image)
    o.dispose()
  }
}
