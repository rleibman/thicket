package scalaui.s8.generated

// This file was generated using sn-bindgen 0.4.5: https://sn-bindgen.indoorvivants.com/

import _root_.scala.scalanative.unsafe.*
import _root_.scala.scalanative.unsigned.*
import _root_.scala.scalanative.libc.*
import _root_.scala.scalanative.*

object structs:
  import _root_.scalaui.s8.generated.aliases.*
  import _root_.scalaui.s8.generated.structs.*

  /**
   * [bindgen] header: shim/include/scalaui_shim_types.h
  */
  opaque type sui_size = CStruct2[Double, Double]
  
  object sui_size:
    given _tag: Tag[sui_size] = Tag.materializeCStruct2Tag[Double, Double]
    
    export fields.*
    private[generated] object fields:
      extension (struct: sui_size)
        inline def width : Double = struct._1
        inline def width_=(value: Double): Unit = (!struct.at1 = value)
        inline def height : Double = struct._2
        inline def height_=(value: Double): Unit = (!struct.at2 = value)
      end extension
    
    // Allocates sui_size on the heap – fields are not initalised or zeroed out
    def apply()(using Zone): Ptr[sui_size] = scala.scalanative.unsafe.alloc[sui_size](1)
    def apply(width : Double, height : Double)(using Zone): Ptr[sui_size] =
      val ____ptr = apply()
      (!____ptr).width = width
      (!____ptr).height = height
      ____ptr
    
    

object aliases:
  import _root_.scalaui.s8.generated.aliases.*
  import _root_.scalaui.s8.generated.structs.*
  type int32_t = scala.scalanative.unsafe.CInt
  object int32_t:
    val _tag: Tag[int32_t] = summon[Tag[scala.scalanative.unsafe.CInt]]
    inline def apply(inline o: scala.scalanative.unsafe.CInt): int32_t = o
    extension (v: int32_t)
      inline def value: scala.scalanative.unsafe.CInt = v

  type int64_t = scala.Long
  object int64_t:
    val _tag: Tag[int64_t] = summon[Tag[scala.Long]]
    inline def apply(inline o: scala.Long): int64_t = o
    extension (v: int64_t)
      inline def value: scala.Long = v

  /**
   * [bindgen] header: shim/include/scalaui_shim_types.h
  */
  opaque type sui_handle = Ptr[Byte]
  object sui_handle:
    given _tag: Tag[sui_handle] = Tag.Ptr(Tag.Byte)
    inline def apply(inline o: Ptr[Byte]): sui_handle = o
    extension (v: sui_handle)
      inline def value: Ptr[Byte] = v

  /**
   * [bindgen] header: shim/include/scalaui_shim_types.h
  */
  opaque type sui_main_cb = CFuncPtr1[int64_t, Unit]
  object sui_main_cb:
    given _tag: Tag[sui_main_cb] = Tag.materializeCFuncPtr1[int64_t, Unit]
    inline def fromPtr(ptr: Ptr[Byte] | CVoidPtr): sui_main_cb = CFuncPtr.fromPtr(ptr.asInstanceOf[Ptr[Byte]])
    inline def apply(inline o: CFuncPtr1[int64_t, Unit]): sui_main_cb = o
    extension (v: sui_main_cb)
      inline def value: CFuncPtr1[int64_t, Unit] = v
      inline def toPtr: CVoidPtr = CFuncPtr.toPtr(v)

  /**
   * [bindgen] header: shim/include/scalaui_shim_types.h
  */
  opaque type sui_measure_byvalue_cb = CFuncPtr1[int64_t, sui_size]
  object sui_measure_byvalue_cb:
    given _tag: Tag[sui_measure_byvalue_cb] = Tag.materializeCFuncPtr1[int64_t, sui_size]
    inline def fromPtr(ptr: Ptr[Byte] | CVoidPtr): sui_measure_byvalue_cb = CFuncPtr.fromPtr(ptr.asInstanceOf[Ptr[Byte]])
    inline def apply(inline o: CFuncPtr1[int64_t, sui_size]): sui_measure_byvalue_cb = o
    extension (v: sui_measure_byvalue_cb)
      inline def value: CFuncPtr1[int64_t, sui_size] = v
      inline def toPtr: CVoidPtr = CFuncPtr.toPtr(v)

  /**
   * [bindgen] header: shim/include/scalaui_shim_types.h
  */
  opaque type sui_measure_outparam_cb = CFuncPtr2[int64_t, Ptr[sui_size], Unit]
  object sui_measure_outparam_cb:
    given _tag: Tag[sui_measure_outparam_cb] = Tag.materializeCFuncPtr2[int64_t, Ptr[sui_size], Unit]
    inline def fromPtr(ptr: Ptr[Byte] | CVoidPtr): sui_measure_outparam_cb = CFuncPtr.fromPtr(ptr.asInstanceOf[Ptr[Byte]])
    inline def apply(inline o: CFuncPtr2[int64_t, Ptr[sui_size], Unit]): sui_measure_outparam_cb = o
    extension (v: sui_measure_outparam_cb)
      inline def value: CFuncPtr2[int64_t, Ptr[sui_size], Unit] = v
      inline def toPtr: CVoidPtr = CFuncPtr.toPtr(v)

  /**
   * [bindgen] header: shim/include/scalaui_shim_types.h
  */
  opaque type sui_tap_cb = CFuncPtr1[int64_t, Unit]
  object sui_tap_cb:
    given _tag: Tag[sui_tap_cb] = Tag.materializeCFuncPtr1[int64_t, Unit]
    inline def fromPtr(ptr: Ptr[Byte] | CVoidPtr): sui_tap_cb = CFuncPtr.fromPtr(ptr.asInstanceOf[Ptr[Byte]])
    inline def apply(inline o: CFuncPtr1[int64_t, Unit]): sui_tap_cb = o
    extension (v: sui_tap_cb)
      inline def value: CFuncPtr1[int64_t, Unit] = v
      inline def toPtr: CVoidPtr = CFuncPtr.toPtr(v)


@extern
private[generated] object extern_functions:
  import _root_.scalaui.s8.generated.aliases.*
  import _root_.scalaui.s8.generated.structs.*
  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_button_new(): sui_handle = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_button_on_tap(h : sui_handle, cb : sui_tap_cb, ctx : int64_t): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_button_send_ui_action(button : sui_handle): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_button_set_title(h : sui_handle, title : CString): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_cpu_seconds(): Double = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_destroy(h : sui_handle): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_label_get_text(h : sui_handle): CString = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_label_new(): sui_handle = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_label_set_text(h : sui_handle, text : CString): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_live_handle_count(): int32_t = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_probe_struct_byvalue(cb : sui_measure_byvalue_cb, ctx : int64_t, result : Ptr[sui_size]): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_probe_struct_outparam(cb : sui_measure_outparam_cb, ctx : int64_t, result : Ptr[sui_size]): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_root_view(): sui_handle = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_rss_mb(): Double = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_run_on_main(cb : sui_main_cb, ctx : int64_t): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_simulate_taps(button : sui_handle, n : int32_t): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_view_add_child(parent : sui_handle, child : sui_handle): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_view_new(): sui_handle = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_view_remove(h : sui_handle): Unit = extern

  /**
   * [bindgen] header: shim/include/scalaui_shim.h
  */
  def sui_view_set_frame(h : sui_handle, x : Double, y : Double, w : Double, height : Double): Unit = extern

object functions:
  import _root_.scalaui.s8.generated.aliases.*
  import _root_.scalaui.s8.generated.structs.*
  import extern_functions.*
  export extern_functions.*


object types:
    export _root_.scalaui.s8.generated.structs.*
    export _root_.scalaui.s8.generated.aliases.*

object all:
  export _root_.scalaui.s8.generated.structs.sui_size
  export _root_.scalaui.s8.generated.aliases.int32_t
  export _root_.scalaui.s8.generated.aliases.int64_t
  export _root_.scalaui.s8.generated.aliases.sui_handle
  export _root_.scalaui.s8.generated.aliases.sui_main_cb
  export _root_.scalaui.s8.generated.aliases.sui_measure_byvalue_cb
  export _root_.scalaui.s8.generated.aliases.sui_measure_outparam_cb
  export _root_.scalaui.s8.generated.aliases.sui_tap_cb
  export _root_.scalaui.s8.generated.functions.sui_button_new
  export _root_.scalaui.s8.generated.functions.sui_button_on_tap
  export _root_.scalaui.s8.generated.functions.sui_button_send_ui_action
  export _root_.scalaui.s8.generated.functions.sui_button_set_title
  export _root_.scalaui.s8.generated.functions.sui_cpu_seconds
  export _root_.scalaui.s8.generated.functions.sui_destroy
  export _root_.scalaui.s8.generated.functions.sui_label_get_text
  export _root_.scalaui.s8.generated.functions.sui_label_new
  export _root_.scalaui.s8.generated.functions.sui_label_set_text
  export _root_.scalaui.s8.generated.functions.sui_live_handle_count
  export _root_.scalaui.s8.generated.functions.sui_probe_struct_byvalue
  export _root_.scalaui.s8.generated.functions.sui_probe_struct_outparam
  export _root_.scalaui.s8.generated.functions.sui_root_view
  export _root_.scalaui.s8.generated.functions.sui_rss_mb
  export _root_.scalaui.s8.generated.functions.sui_run_on_main
  export _root_.scalaui.s8.generated.functions.sui_simulate_taps
  export _root_.scalaui.s8.generated.functions.sui_view_add_child
  export _root_.scalaui.s8.generated.functions.sui_view_new
  export _root_.scalaui.s8.generated.functions.sui_view_remove
  export _root_.scalaui.s8.generated.functions.sui_view_set_frame

