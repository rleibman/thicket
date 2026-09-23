package scalaui.s6

import scala.scalanative.unsafe.*
import scalaui.s6.generated.aliases.*
import scalaui.s6.generated.functions.*

/** The S6 reference app for Option B: a label, a button, and a counter. Deliberately the smallest thing that exercises
  * the real path — Scala state, a Scala closure on a real UIKit control, and a UIKit property write — so the size and
  * startup numbers are comparable with the Option A and D hello apps rather than flattered by doing less.
  */
object Hello {

  private var label: sui_handle = null.asInstanceOf[sui_handle]
  private var count: Int = 0

  private def render(): Unit = Zone(sui_label_set_text(label, toCString(s"Count: $count")))

  @exported("scalaui_hello_main")
  def scalaui_hello_main(rootPtr: Ptr[Byte]): Unit =
    GcState.guarded {
      val root = rootPtr.asInstanceOf[sui_handle]
      label = sui_label_new()
      sui_view_set_frame(label, 20.0, 120.0, 320.0, 40.0)
      sui_view_add_child(root, label)

      val button = sui_button_new()
      Zone(sui_button_set_title(button, toCString("Increment")))
      sui_view_set_frame(button, 20.0, 180.0, 320.0, 44.0)
      sui_view_add_child(root, button)
      sui_button_on_tap(
        button,
        sui_tap_cb(Handles.tapTrampoline),
        Handles.register { () =>
          count += 1; render()
        }
      )
      render()
    }

}
