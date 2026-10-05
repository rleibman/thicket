package thicket.tools.shim

/** The Scala ⇄ Apple ABI, described once.
  *
  * Three artefacts have to agree on every one of these functions, exactly:
  *
  *   - `shim/include/thicket_apple.h` — what sn-bindgen reads,
  *   - `shim/Sources/Shim+AppKit.swift` and `Shim+UIKit.swift` — the `@_cdecl` signatures,
  *   - `src/main/scala/.../Shim.scala` — the `@extern` bindings.
  *
  * The header and the Scala externs are generated from this (`sbt shimGen/run`); the two Swift shims are hand-written
  * and checked against it. Before that, all four were hand-written: at 34 functions, 136 places to get right. A
  * disagreement between them is not a compile error on either side: it is a silent ABI mismatch that reads a garbage
  * register at runtime, on a phone. That — not the line count — is what this description exists to remove.
  *
  * **What this does not describe.** Function *bodies*. Measured on the two shims that exist: 80 generatable signature
  * lines against 211 hand-written body lines each, and of the 34 shared functions only 7 have byte-identical bodies.
  * The bodies are real AppKit and UIKit logic — `NSImageView.imageScaling` is not `UIView.contentMode`,
  * `placeholderString` is not `placeholder` — and pretending otherwise would produce a generator that emits code nobody
  * wants. See `docs/13-phases.md` §13.3.
  */
object Abi {

  /** A C type, as it appears at this boundary.
    *
    * Deliberately small: every type here is a scalar, a pointer to a scalar, or an opaque handle. **No struct crosses
    * this boundary by value** — Scala Native returns a small struct from a `CFuncPtr` in the wrong registers, silently,
    * on both x86-64 (S4) and arm64 with a *different* wrong answer (S3). `NSRect` is flattened to four doubles and
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

    /** The three callback shapes. The context is `int64_t`, never `void *`: callbacks cannot close over state, so each
      * carries a handle-table id, and a numeric context avoids the `Long`⇄`Ptr` laundering that S7 found throws at
      * runtime.
      */
    case VoidCb, TextCb, BoolCb

    /** The row-binding callback, and the only one that returns a value. A returned *pointer* is a single register; what
      * S4 and S3 found silently broken was a returned small struct.
      */
    case RowCb

    /** A slider's value, in the app's own units. */
    case ValueCb

    /** A small integer — the depth the platform popped a navigation stack to. */
    case IntCb

  }

  /** What a type is *at the ABI*, as opposed to what it is called.
    *
    * `sui_handle` and `const uint8_t *` are different types in C and in Swift, and the same machine word in Scala
    * Native — `Ptr[Byte]` is what both bind to, and there is no `Ptr[UByte]` variant that would land in a different
    * register. So a check that demanded the Scala side distinguish them would be demanding a distinction that does not
    * exist, and would report a false positive forever.
    *
    * This is the level at which a mismatch actually costs something: an `Int32` where the other side expects an
    * `Int64`, or a scalar where it expects a pointer, links cleanly and reads the wrong register at runtime.
    */
  enum Repr { case I32, I64, F64, Pointer, FnPointer, Void }

  extension (t: CType) {

    def repr: Repr =
      t match {
        case CType.Void => Repr.Void
        case CType.I32  => Repr.I32
        case CType.I64  => Repr.I64
        case CType.F64  => Repr.F64

        case CType.Str | CType.Bytes | CType.OutF64 | CType.Handle => Repr.Pointer
        case CType.VoidCb | CType.TextCb | CType.BoolCb | CType.RowCb | CType.ValueCb | CType.IntCb =>
          Repr.FnPointer
      }

  }

  import CType.*

  final case class Param(
    name: String,
    tpe:  CType
  )
  final case class Fn(
    name:   String,
    ret:    CType,
    params: List[Param],
    doc:    String = ""
  ) {

    def arity: Int = params.length

  }

  private def p(
    n: String,
    t: CType
  ) = Param(n, t)

  /** A handle-taking setter, which is most of the surface. */
  private def setter(
    name: String,
    rest: (String, CType)*
  ): Fn =
    Fn(
      name,
      Void,
      p("h", Handle) :: rest.toList.map(
        (
          n,
          t
        ) => p(n, t)
      )
    )

  /** Grouped exactly as the C header groups them, because the header is generated from this and a diff that reorders
    * everything is a diff nobody reads.
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

  /** One `sui_create` kind code, and the view each toolkit builds for it.
    *
    * This is where per-platform widget *choice* is expressed, not only per-platform naming: kind 5 is a checkbox on the
    * Mac and a switch on iOS, because UIKit has no checkbox. The strings are the Swift construction each shim's `case`
    * must contain — `NSButton( checkboxWithTitle:` and `UISwitch(` — and ConsistencySpec reads both `sui_create` bodies
    * to check it does. So a shim that quietly builds a different widget, or handles a code this table does not know,
    * fails on any machine.
    *
    * `None` is a code that is reserved and not built by that shim (write it as `Kind(code, name, None, None, why)`);
    * Scala refuses to create it rather than let it reach the shim's `default:` branch. Since #18 every code is built.
    */
  final case class Kind(
    code:    Int,
    name:    String,
    appKit:  Option[String],
    uiKit:   Option[String],
    comment: String = ""
  )

  private def built(
    code:    Int,
    name:    String,
    appKit:  String,
    uiKit:   String,
    comment: String = ""
  ) = Kind(code, name, Some(appKit), Some(uiKit), comment)

  val kinds: List[Kind] = List(
    built(0, "Column", "TapView(", "UIStackView("),
    built(1, "Row", "TapView(", "UIStackView("),
    built(2, "Label", "NSTextField(labelWithString:", "UILabel(", "AppKit has no label class"),
    built(3, "Button", "NSButton(title:", "UIButton(type:"),
    built(4, "TextField", "NSTextField(string:", "UITextField("),
    built(5, "Checkbox", "NSButton(checkboxWithTitle:", "UISwitch(", "UIKit has no checkbox"),
    built(6, "Scroll", "NSScrollView(", "UIScrollView(", "vertical"),
    built(7, "Divider", "NSBox(", "UIView(", "the shims' default: branch"),
    built(8, "Image", "ImageView(", "UIImageView("),
    built(9, "Toggle", "NSSwitch(", "UISwitch(", "no label: the caption is a sibling"),
    built(10, "Spacer", "NSView(", "UIView(", "grows through Prop.Grow"),
    built(11, "ProgressBar", "NSProgressIndicator(", "UIProgressView("),
    built(12, "ActivityIndicator", "NSProgressIndicator(", "UIActivityIndicatorView(", "spins while mounted"),
    built(13, "Slider", "NSSlider(", "UISlider("),
    built(14, "SecureField", "NSSecureTextField(", "UITextField(", "isSecureTextEntry on UIKit"),
    built(15, "ScrollHorizontal", "NSScrollView(", "UIScrollView(", "the axis is read at create"),
    built(16, "Alert", "AlertView(", "AlertView(", "a placeholder; NSAlert / UIAlertController at present"),
    built(17, "Sheet", "SheetView(", "SheetView(", "a container; a sheet window / a presented controller"),
    built(
      18,
      "Picker",
      "NSPopUpButton(",
      "UIButton(",
      "UIKit: a menu-backed button, not UIPickerView — that is a wheel, wrong for a short list"
    )
  )

  /** The `sui_create` comment in the header, generated from [[kinds]] so the header cannot describe a different set of
    * codes from the one Scala sends.
    */
  private def kindDoc: String = {
    def show(v: Option[String]) = v.fold("-")(c => if c.endsWith("(") then s"$c)" else s"$c)")
    val rows = kinds.map { k =>
      val note = if k.comment.isEmpty then "" else s"  ${k.comment}"
      f"     ${k.code}%2d ${k.name}%-18s ${show(k.appKit)}%-30s ${show(k.uiKit)}%-16s$note".stripTrailing
    }
    ("kind: the view each toolkit builds. A different *widget* per platform is chosen here," ::
      "   not only a different name. Generated from Abi.kinds; ConsistencySpec checks both" ::
      "   shims' sui_create build what this says." ::
      "" ::
      rows).mkString("\n")
  }

  val construction: List[Fn] = List(
    Fn("sui_create", Handle, List(p("kind", I32)), doc = kindDoc),
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
    setter("sui_set_grow", "on" -> I32),
    setter("sui_set_align", "align" -> I32).copy(doc = "align: 0 Start, 1 Center, 2 End"),
    setter("sui_set_tint", "has" -> I32, "r" -> I32, "g" -> I32, "b" -> I32).copy(
      doc = "Colour is passed as three components plus a \"set\" flag rather than a struct:\n" +
        "   `None` means \"leave it to the platform\", which is not the same as black."
    ),
    setter("sui_set_fill", "has"         -> I32, "r"        -> I32, "g" -> I32, "b" -> I32),
    setter("sui_set_image_file", "path"  -> Str),
    setter("sui_set_image_bytes", "data" -> Bytes, "length" -> I32),
    setter("sui_clear_image"),
    setter("sui_set_content_fit", "fit" -> I32).copy(doc = "fit: 0 Contain, 1 Cover, 2 Fill"),
    setter("sui_set_progress", "has" -> I32, "fraction" -> F64).copy(
      doc = "has 0 means indeterminate, which is deliberately not the same as a fraction of 0.0.\n" +
        "   The fraction is 0.0-1.0; the shim scales it for NSProgressIndicator's 0-100."
    ),
    setter("sui_set_range", "min" -> F64, "max" -> F64)
      .copy(doc = "A slider's bounds, in the app's own units. Always arrives before sui_set_value."),
    setter("sui_set_value", "value" -> F64).copy(
      doc = "A slider's value, in the app's own units. Written only when it differs, so an\n" +
        "   app writing back what the user dragged to does not fight the drag."
    )
  )

  val events: List[Fn] = List(
    setter("sui_on_tap", "cb"            -> VoidCb, "ctx"  -> I64),
    setter("sui_on_text_change", "cb"    -> TextCb, "ctx"  -> I64),
    setter("sui_on_checked_change", "cb" -> BoolCb, "ctx"  -> I64),
    setter("sui_on_value_change", "cb"   -> ValueCb, "ctx" -> I64)
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
    Fn("sui_run_on_main", Void, List(p("cb", VoidCb), p("ctx", I64))),
    Fn(
      "sui_run_on_main_after",
      Void,
      List(p("delay_ms", I32), p("cb", VoidCb), p("ctx", I64)),
      doc = "The same, after at least `delay_ms`. For waiting on the platform a frame at a time\n" +
        "   rather than spinning the run loop with sui_run_on_main."
    )
  )

  /** The one place control is inverted: everywhere else Scala builds a tree and the shim obeys, but a table asks for
    * the row it is about to show and recycles the ones it is not. Mirrors `GtkSignalListItemFactory`'s bind callback
    * and `BaseAdapter.getView`.
    */
  val virtualRows: List[Fn] = List(
    Fn(
      "sui_create_table",
      Handle,
      List(p("cb", RowCb), p("ctx", I64)),
      doc = "An NSTableView / UITableView inside its scroller. `cb` is called for each row" + "\n" +
        "   that becomes visible, with a recycled view or NULL."
    ),
    Fn(
      "sui_table_reload",
      Void,
      List(p("h", Handle), p("count", I32)),
      doc = "The new row count, from RowSource.onInvalidate."
    ),
    Fn(
      "sui_table_materialised",
      I32,
      List(p("h", Handle)),
      doc = "How many row views the table has actually created. The measurement that says" + "\n" +
        "   virtualisation is working, so it is part of the ABI rather than the self-test."
    ),
    Fn(
      "sui_table_live",
      I32,
      Nil,
      doc = "How many table sources the shim still owns. Falls back to zero once every" + "\n" +
        "   virtual list is destroyed; the measurement that says destroying one frees it."
    )
  )

  /** Widgets shown *over* the app rather than attached to it (`WidgetKind.presented`). The handle is created and
    * configured like any other, and its children mount into it by the ordinary path; only the attachment differs.
    */
  val presentation: List[Fn] = List(
    setter("sui_present").copy(doc = "Shows an Alert or a Sheet over the app. Called after its children are mounted."),
    setter("sui_dismiss").copy(
      doc = "Takes it down because the app asked. Never reported through sui_on_dismiss, which is\n" +
        "   for the platform closing it. Does not release the handle: sui_destroy does."
    ),
    setter("sui_set_message", "text" -> Str).copy(doc = "An alert's secondary text."),
    setter("sui_alert_clear_actions"),
    setter("sui_alert_add_action", "label" -> Str, "role" -> I32, "cb" -> VoidCb, "ctx" -> I64).copy(
      doc = "role: 0 plain, 1 destructive, 2 cancel. Roles, not styling: each platform decides what\n" +
        "   they look like and where they go."
    ),
    setter("sui_on_dismiss", "cb" -> VoidCb, "ctx" -> I64)
      .copy(doc = "The platform closed it without a choice: Escape, a swipe down.")
  )

  /** A `Picker`'s choices.
    *
    * Clear-then-add-each, which is how an alert's actions already cross, rather than a second convention for passing a
    * sequence over the boundary. The selection is an **index**: two options may share a label, and every toolkit's
    * selection API is index-based underneath.
    *
    * `sui_get_selected` exists so a self-test can read the selection back out of the real control instead of trusting
    * the setter — the same reason `sui_get_checked` does.
    */
  val picker: List[Fn] = List(
    setter("sui_picker_clear_options"),
    setter("sui_picker_add_option", "label" -> Str),
    setter("sui_set_selected", "index" -> I32).copy(doc = "-1 for no selection."),
    Fn("sui_get_selected", I32, List(p("h", Handle))),
    setter("sui_on_select", "cb" -> IntCb, "ctx" -> I64)
      .copy(doc = "The index the user chose.")
  )

  /** A context menu is something a view *has* (`NSView.menu`, `UIContextMenuInteraction`), so it is set on the view's
    * own handle and adds nothing to the tree. Items are data — a label, an enabled flag and a callback each — like an
    * alert's actions. Which gesture opens it is the platform's business, so nothing here names one.
    */
  val contextMenus: List[Fn] = List(
    setter("sui_menu_clear").copy(doc = "Removes the view's menu items. A view with none has no menu."),
    setter("sui_menu_add_item", "label" -> Str, "enabled" -> I32, "cb" -> VoidCb, "ctx" -> I64)
      .copy(doc = "Appends one item. Order is the order given.")
  )

  /** Navigation is host chrome, not a widget kind (`AppRoot.pages`): the shim is told the live stack, bottom first, and
    * each page's already-mounted content, and renders it the platform's way — a `UINavigationController` on iOS, a
    * sidebar on macOS. When the *platform* pops (a swipe, a back button, a sidebar selection) it reports the new depth,
    * so the app's `Nav` and the platform's stack never diverge.
    */
  val navigation: List[Fn] = List(
    Fn(
      "sui_pages_begin",
      Void,
      Nil,
      doc = "Starts describing the stack. Nothing changes on screen until sui_pages_commit."
    ),
    Fn(
      "sui_pages_add",
      Void,
      List(p("id", I64), p("content", Handle), p("title", Str)),
      doc = "The next page up. `id` is stable for as long as the page is on the stack, and so is its\n" +
        "   `content`, which stays mounted while it is covered."
    ),
    Fn("sui_pages_commit", Void, Nil, doc = "Makes the platform's stack match what was described."),
    Fn(
      "sui_on_pages_popped",
      Void,
      List(p("cb", IntCb), p("ctx", I64)),
      doc = "The platform popped the stack itself, to the depth given. Never called for a change\n" +
        "   the app made through sui_pages_commit."
    )
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
    Fn("sui_is_text_bearing", I32, List(p("h", Handle))),
    Fn(
      "sui_class_name",
      Str,
      List(p("h", Handle)),
      doc = "The platform class of the view, e.g. NSSwitch. What the platform built, not what\n" +
        "   the renderer asked for. Same buffer rule as sui_get_text."
    ),
    Fn(
      "sui_get_progress",
      F64,
      List(p("h", Handle)),
      doc = "A determinate fraction 0.0-1.0; -1 when indeterminate (a spinner, or a bar given\n" +
        "   no fraction); -2 when the view shows no progress at all."
    ),
    Fn("sui_get_value", F64, List(p("h", Handle)), doc = "A slider's value, in the app's own units."),
    Fn("sui_is_secure", I32, List(p("h", Handle)), doc = "1 when the field masks what is typed into it."),
    Fn(
      "sui_has_image",
      I32,
      List(p("h", Handle)),
      doc = "1 when an image view holds a decoded image, 0 when it shows nothing, -1 when `h` is not\n" +
        "   an image view. The difference between a picture and an empty frame of the right size."
    ),
    Fn(
      "sui_scroll_offset",
      F64,
      List(p("h", Handle)),
      doc = "How far a vertical scroller's visible area is from the top of its content, in points:\n" +
        "   0 when it shows the top. -1 when `h` is not a scroller."
    ),
    Fn(
      "sui_is_presented",
      I32,
      List(p("h", Handle)),
      doc = "1 while an Alert or Sheet is on screen, as the platform reports it."
    ),
    Fn(
      "sui_presented_title",
      Str,
      List(p("h", Handle)),
      doc = "The title the platform is *showing* for a presented widget, read from the alert or the\n" +
        "   sheet's own chrome rather than from what the renderer was told. NULL when not presented."
    ),
    Fn("sui_presented_message", Str, List(p("h", Handle)), doc = "An alert's secondary text, as shown."),
    Fn(
      "sui_menu_item_count",
      I32,
      List(p("h", Handle)),
      doc = "How many items the platform's menu for this view holds; 0 when it has none."
    ),
    Fn("sui_menu_item_label", Str, List(p("h", Handle), p("index", I32))),
    Fn("sui_pages_depth", I32, Nil, doc = "How many pages the platform's own stack holds."),
    Fn(
      "sui_page_title",
      Str,
      List(p("index", I32)),
      doc = "The title the platform shows for page `index`, bottom first: the navigation item's\n" +
        "   on iOS, the sidebar entry's on macOS."
    ),
    Fn("sui_pages_shown", I32, Nil, doc = "The index of the page on screen, bottom first."),
    Fn(
      "sui_pages_back_offered",
      I32,
      Nil,
      doc = "1 when the platform's own chrome offers going back: a back button, or a sidebar to choose from."
    ),
    Fn(
      "sui_pages_back",
      I32,
      Nil,
      doc = "Goes back one page through the platform's own path — popping the navigation controller,\n" +
        "   selecting the previous sidebar entry — exactly as a user would."
    ),
    Fn(
      "sui_menu_live",
      I32,
      Nil,
      doc = "How many menu items the shim still holds, counted as they are freed. Falls by a\n" +
        "   row's items when the row is destroyed, or the menu leaked with its view."
    ),
    Fn(
      "sui_menu_activate",
      I32,
      List(p("h", Handle), p("index", I32)),
      doc = "Chooses item `index` through the platform's own menu path. 0 where the platform offers\n" +
        "   no way to do that from code (UIKit), rather than a simulation that would pass."
    ),
    Fn(
      "sui_presented_count",
      I32,
      Nil,
      doc = "How many things the platform has presented over the app right now — sheets attached to\n" +
        "   the window, or the chain of presented view controllers. The platform's answer, so a\n" +
        "   widget the renderer believes it dismissed but UIKit is still showing counts."
    ),
    Fn("sui_alert_action_count", I32, List(p("h", Handle))),
    Fn(
      "sui_alert_action_label",
      Str,
      List(p("h", Handle), p("index", I32)),
      doc = "In the order the platform holds them, which need not be the order declared."
    ),
    Fn(
      "sui_perform_click",
      I32,
      List(p("h", Handle)),
      doc = "Clicks a button the way a user would, through the control's own action. 0 if not a button."
    ),
    Fn(
      "sui_alert_choose",
      I32,
      List(p("h", Handle), p("index", I32)),
      doc = "Chooses action `index` through the platform's own response path. 0 where the platform\n" +
        "   offers no way to do that from code (UIKit), rather than a simulation that would pass."
    )
  )

  /** `doc`, when set, is emitted as a comment under the group's rule in the header. */
  final case class Group(
    title: String,
    fns:   List[Fn],
    doc:   String = ""
  )

  val groups: List[Group] = List(
    Group("lifecycle", lifecycle),
    Group("widget construction", construction),
    Group("properties", properties),
    Group("events", events),
    Group("tree", tree),
    Group("layout", layout),
    Group("threading", threading),
    Group(
      "virtual rows",
      virtualRows,
      doc = "The one place control is inverted: everywhere else Scala builds a tree and the shim" + "\n" +
        "   obeys, but a table asks for the row it is about to show and recycles the ones it is not."
    ),
    Group("presentation", presentation),
    Group("context menus", contextMenus),
    Group("picker", picker),
    Group("navigation", navigation),
    Group("inspection, for the self-test", inspection)
  )

  val all: List[Fn] = groups.flatMap(_.fns)

  /** Exported by a shim but deliberately **not** in this ABI.
    *
    * `sui_set_root_view` is called by the iOS host's Swift, never by Scala: on iOS the Swift side owns `@main` because
    * a `UIScene` delegate cannot live in the static archive Scala Native produces, so it hands its root view *in*
    * rather than asking for one. It therefore belongs in neither the C header nor `Shim.scala`, and the consistency
    * check must not report it as drift.
    */
  val swiftOnly: Set[String] = Set("sui_set_root_view")

}
