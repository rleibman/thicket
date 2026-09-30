package thicket.renderer.apple

import scala.scalanative.unsafe.*

/** Bindings for `shim/include/thicket_apple.h`.
  *
  * Hand-written rather than generated. sn-bindgen handles this header fine (S3 proved it), but its prebuilt macOS
  * binary is linked against a specific Homebrew llvm@17, so generating would put a 1.5 GB toolchain dependency between
  * a contributor and a build of this module. Twenty-five flat signatures are cheaper to own than that.
  *
  * Every signature is scalars or out-parameters: no struct crosses this boundary by value, because Scala Native gets
  * that silently wrong on both x86-64 and arm64 (S4, S3).
  */
@extern
object Shim {

  type Handle = Ptr[Byte]
  type VoidCb = CFuncPtr1[Long, Unit]
  type TextCb = CFuncPtr2[Long, CString, Unit]
  type BoolCb = CFuncPtr2[Long, CInt, Unit]

  /** The only callback that returns a value. A returned pointer is one register; what S4 and S3 found silently broken
    * was a returned small struct.
    */
  type RowCb = CFuncPtr3[Long, CInt, Handle, Handle]

  def sui_app_start(
    width:  CInt,
    height: CInt,
    title:  CString,
    ready:  VoidCb,
    ctx:    Long
  ): Unit = extern
  def sui_root_view():                      Handle = extern
  def sui_window_set_title(title: CString): Unit = extern

  def sui_create(kind: CInt):   Handle = extern
  def sui_destroy(h:   Handle): Unit = extern

  def sui_set_text(
    h:    Handle,
    text: CString
  ): Unit = extern
  def sui_set_placeholder(
    h:    Handle,
    text: CString
  ): Unit = extern
  def sui_set_checked(
    h:  Handle,
    on: CInt
  ): Unit = extern
  def sui_get_checked(h: Handle): CInt = extern
  def sui_set_enabled(
    h:  Handle,
    on: CInt
  ): Unit = extern
  def sui_set_spacing(
    h:  Handle,
    dp: CInt
  ): Unit = extern
  def sui_set_padding(
    h:  Handle,
    dp: CInt
  ): Unit = extern
  def sui_set_text_role(
    h:    Handle,
    role: CInt
  ): Unit = extern
  def sui_set_text_emphasis(
    h: Handle,
    e: CInt
  ): Unit = extern
  def sui_set_grow(
    h:  Handle,
    on: CInt
  ): Unit = extern
  def sui_set_align(
    h:     Handle,
    align: CInt
  ): Unit = extern

  def sui_set_tint(
    h:   Handle,
    has: CInt,
    r:   CInt,
    g:   CInt,
    b:   CInt
  ): Unit = extern
  def sui_set_fill(
    h:   Handle,
    has: CInt,
    r:   CInt,
    g:   CInt,
    b:   CInt
  ): Unit = extern
  def sui_set_image_file(
    h:    Handle,
    path: CString
  ): Unit = extern
  def sui_set_image_bytes(
    h:      Handle,
    data:   Ptr[Byte],
    length: CInt
  ): Unit = extern
  def sui_clear_image(h: Handle): Unit = extern
  def sui_set_content_fit(
    h:   Handle,
    fit: CInt
  ): Unit = extern

  def sui_on_tap(
    h:   Handle,
    cb:  VoidCb,
    ctx: Long
  ): Unit = extern
  def sui_on_text_change(
    h:   Handle,
    cb:  TextCb,
    ctx: Long
  ): Unit = extern
  def sui_on_checked_change(
    h:   Handle,
    cb:  BoolCb,
    ctx: Long
  ): Unit = extern

  def sui_insert_after(
    parent: Handle,
    child:  Handle,
    after:  Handle
  ): Unit = extern
  def sui_remove_child(
    parent: Handle,
    child:  Handle
  ): Unit = extern

  def sui_measure(
    h:       Handle,
    maxW:    CDouble,
    maxH:    CDouble,
    outMinW: Ptr[CDouble],
    outMinH: Ptr[CDouble],
    outNatW: Ptr[CDouble],
    outNatH: Ptr[CDouble]
  ): Unit = extern

  def sui_set_frame(
    h:      Handle,
    x:      CDouble,
    y:      CDouble,
    w:      CDouble,
    height: CDouble
  ): Unit = extern

  def sui_run_on_main(
    cb:  VoidCb,
    ctx: Long
  ): Unit = extern

  def sui_create_table(
    cb:  RowCb,
    ctx: Long
  ): Handle = extern
  def sui_table_reload(
    h:     Handle,
    count: CInt
  ): Unit = extern
  def sui_table_materialised(h: Handle): CInt = extern
  def sui_table_live():                  CInt = extern

  def sui_child_count(h: Handle): CInt = extern
  def sui_child_at(
    h:     Handle,
    index: CInt
  ): Handle = extern
  def sui_get_text(h:        Handle): CString = extern
  def sui_is_text_bearing(h: Handle): CInt = extern

}
