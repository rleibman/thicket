package thicket.tools.shim

/** The Scala ⇄ Apple ABI, described once.
  *
  * Three artefacts have to agree on every one of these functions, exactly:
  *
  *   - `shim/include/thicket_apple.h` — what sn-bindgen reads,
  *   - `shim/Sources/Shim+AppKit.swift` and `Shim+UIKit.swift` — the `@_cdecl` signatures,
  *   - `src/main/scala/.../Shim.scala` — the `@extern` bindings.
  *
  * Today all three are hand-written, which is 4 declarations per function across two shims
  * and, at 34 functions, 136 places to get right. A disagreement between them is not a
  * compile error on either side: it is a silent ABI mismatch that reads a garbage register
  * at runtime, on a phone. That — not the line count — is what this description exists to
  * remove.
  *
  * **What this does not describe.** Function *bodies*. Measured on the two shims that
  * exist: 80 generatable signature lines against 211 hand-written body lines each, and of
  * the 34 shared functions only 7 have byte-identical bodies. The bodies are real AppKit
  * and UIKit logic — `NSImageView.imageScaling` is not `UIView.contentMode`,
  * `placeholderString` is not `placeholder` — and pretending otherwise would produce a
  * generator that emits code nobody wants. See `docs/13-phases.md` §13.3.
  */
object Abi {

  /** A C type, as it appears at this boundary.
    *
    * Deliberately small: every type here is a scalar, a pointer to a scalar, or an opaque
    * handle. **No struct crosses this boundary by value** — Scala Native returns a small
    * struct from a `CFuncPtr` in the wrong registers, silently, on both x86-64 (S4) and
    * arm64 with a *different* wrong answer (S3). `NSRect` is flattened to four doubles and
    * sizes come back through out-parameters.
    */
  enum CType {
    case Void
    case I32
    case I64
    case F64

    /** `const char *` — a NUL-terminated string owned by the caller. */
    case Str

    /** `const uint8_t *` — always paired with a length parameter. */
    case Bytes

    /** `double *` — an out-parameter, because a returned struct is not safe here. */
    case OutF64

    /** `sui_handle`: an opaque retained view pointer. */
    case Handle

    /** The three callback shapes. The context is `int64_t`, never `void *`: callbacks
      * cannot close over state, so each carries a handle-table id, and a numeric context
      * avoids the `Long`⇄`Ptr` laundering that S7 found throws at runtime.
      */
    case VoidCb, TextCb, BoolCb
  }

  /** What a type is *at the ABI*, as opposed to what it is called.
    *
    * `sui_handle` and `const uint8_t *` are different types in C and in Swift, and the same
    * machine word in Scala Native — `Ptr[Byte]` is what both bind to, and there is no
    * `Ptr[UByte]` variant that would land in a different register. So a check that demanded
    * the Scala side distinguish them would be demanding a distinction that does not exist,
    * and would report a false positive forever.
    *
    * This is the level at which a mismatch actually costs something: an `Int32` where the
    * other side expects an `Int64`, or a scalar where it expects a pointer, links cleanly
    * and reads the wrong register at runtime.
    */
  enum Repr { case I32, I64, F64, Pointer, FnPointer, Void }

  extension (t: CType) {
    def repr: Repr = t match {
      case CType.Void => Repr.Void
      case CType.I32  => Repr.I32
      case CType.I64  => Repr.I64
      case CType.F64  => Repr.F64

      case CType.Str | CType.Bytes | CType.OutF64 | CType.Handle => Repr.Pointer
      case CType.VoidCb | CType.TextCb | CType.BoolCb            => Repr.FnPointer
    }
  }

  import CType.*

  final case class Param(name: String, tpe: CType)
  final case class Fn(name: String, ret: CType, params: List[Param], doc: String = "") {
    def arity: Int = params.length
  }

  private def p(n: String, t: CType) = Param(n, t)

  /** A handle-taking setter, which is most of the surface. */
  private def setter(name: String, rest: (String, CType)*): Fn =
    Fn(name, Void, p("h", Handle) :: rest.toList.map((n, t) => p(n, t)))

  /** Grouped exactly as the C header groups them, because the header is generated from
    * this and a diff that reorders everything is a diff nobody reads.
    */
  val lifecycle: List[Fn] = List(
    Fn(
      "sui_app_start",
      Void,
      List(p("width", I32), p("height", I32), p("title", Str), p("ready", VoidCb), p("ctx", I64))
    ),
    Fn("sui_root_view", Handle, Nil),
    Fn("sui_window_set_title", Void, List(p("title", Str)))
  )

  val construction: List[Fn] = List(
    Fn(
      "sui_create",
      Handle,
      List(p("kind", I32)),
      doc = "kind: 0 Column, 1 Row, 2 Label, 3 Button, 4 TextField, 5 Checkbox, 6 Scroll,\n" +
        "        7 Divider, 8 Image"
    ),
    Fn("sui_destroy", Void, List(p("h", Handle)))
  )

  val properties: List[Fn] = List(
    setter("sui_set_text", "text"        -> Str),
    setter("sui_set_placeholder", "text" -> Str),
    setter("sui_set_checked", "on"       -> I32),
    Fn("sui_get_checked", I32, List(p("h", Handle))),
    setter("sui_set_enabled", "on" -> I32),
    setter("sui_set_spacing", "dp" -> I32),
    setter("sui_set_padding", "dp" -> I32),
    setter("sui_set_text_role", "role" -> I32).copy(doc = "role: 0 Title, 1 Body, 2 Caption"),
    setter("sui_set_text_emphasis", "emphasis" -> I32)
      .copy(doc = "emphasis: 0 Normal, 1 Secondary"),
    setter("sui_set_grow", "on"     -> I32),
    setter("sui_set_align", "align" -> I32).copy(doc = "align: 0 Start, 1 Center, 2 End"),
    setter("sui_set_tint", "has" -> I32, "r" -> I32, "g" -> I32, "b" -> I32).copy(
      doc = "Colour is passed as three components plus a \"set\" flag rather than a struct:\n" +
        "   `None` means \"leave it to the platform\", which is not the same as black."
    ),
    setter("sui_set_fill", "has"      -> I32, "r" -> I32, "g" -> I32, "b" -> I32),
    setter("sui_set_image_file", "path" -> Str),
    setter("sui_set_image_bytes", "data" -> Bytes, "length" -> I32),
    setter("sui_clear_image"),
    setter("sui_set_content_fit", "fit" -> I32).copy(doc = "fit: 0 Contain, 1 Cover, 2 Fill")
  )

  val events: List[Fn] = List(
    setter("sui_on_tap", "cb"            -> VoidCb, "ctx" -> I64),
    setter("sui_on_text_change", "cb"    -> TextCb, "ctx" -> I64),
    setter("sui_on_checked_change", "cb" -> BoolCb, "ctx" -> I64)
  )

  val tree: List[Fn] = List(
    Fn("sui_insert_after", Void, List(p("parent", Handle), p("child", Handle), p("after", Handle))),
    Fn("sui_remove_child", Void, List(p("parent", Handle), p("child", Handle)))
  )

  val layout: List[Fn] = List(
    Fn(
      "sui_measure",
      Void,
      List(
        p("h", Handle),
        p("maxW", F64),
        p("maxH", F64),
        p("outMinW", OutF64),
        p("outMinH", OutF64),
        p("outNatW", OutF64),
        p("outNatH", OutF64)
      ),
      doc = "Out-parameters, not a returned NSSize: see the header comment. maxW/maxH are\n" +
        "   NaN for \"unconstrained\", matching Constraints."
    ),
    Fn(
      "sui_set_frame",
      Void,
      List(p("h", Handle), p("x", F64), p("y", F64), p("w", F64), p("height", F64))
    )
  )

  val threading: List[Fn] = List(
    Fn("sui_run_on_main", Void, List(p("cb", VoidCb), p("ctx", I64)))
  )

  val inspection: List[Fn] = List(
    Fn("sui_child_count", I32, List(p("h", Handle))),
    Fn("sui_child_at", Handle, List(p("h", Handle), p("index", I32))),
    Fn(
      "sui_get_text",
      Str,
      List(p("h", Handle)),
      doc = "Returns NULL when the view carries no text. The buffer is owned by the shim and\n" +
        "   valid until the next call, so Scala must copy before calling again."
    ),
    Fn("sui_is_text_bearing", I32, List(p("h", Handle)))
  )

  final case class Group(title: String, fns: List[Fn])

  val groups: List[Group] = List(
    Group("lifecycle", lifecycle),
    Group("widget construction", construction),
    Group("properties", properties),
    Group("events", events),
    Group("tree", tree),
    Group("layout", layout),
    Group("threading", threading),
    Group("inspection, for the self-test", inspection)
  )

  val all: List[Fn] = groups.flatMap(_.fns)

  /** Exported by a shim but deliberately **not** in this ABI.
    *
    * `sui_set_root_view` is called by the iOS host's Swift, never by Scala: on iOS the
    * Swift side owns `@main` because a `UIScene` delegate cannot live in the static archive
    * Scala Native produces, so it hands its root view *in* rather than asking for one. It
    * therefore belongs in neither the C header nor `Shim.scala`, and the consistency check
    * must not report it as drift.
    */
  val swiftOnly: Set[String] = Set("sui_set_root_view")
}
