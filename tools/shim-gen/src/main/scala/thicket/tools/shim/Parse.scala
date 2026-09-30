package thicket.tools.shim

import thicket.tools.shim.Abi.CType

/** Reads the declarations back out of the three hand-written artefacts.
  *
  * This exists so that [[Abi]] can be checked against reality rather than asserted to match
  * it. Until the artefacts are actually generated, the check is the whole value: a
  * disagreement between the C header, a `@_cdecl` signature and a Scala `extern` is not a
  * compile error on either side — it is a silent ABI mismatch that reads the wrong register
  * at runtime.
  *
  * These are regex parsers, not C or Swift parsers, and they are allowed to be: the three
  * files are flat lists of declarations in a deliberately small type vocabulary, and a
  * declaration this cannot parse is reported as unparsed rather than silently skipped.
  */
object Parse {

  final case class Decl(name: String, ret: CType, params: List[CType])

  object Decl {
    def of(fn: Abi.Fn): Decl = Decl(fn.name, fn.ret, fn.params.map(_.tpe))
  }

  final case class Result(decls: List[Decl], unparsed: List[String])

  // -- C header ------------------------------------------------------------

  private val cTypes: Map[String, CType] = Map(
    "void"            -> CType.Void,
    "int32_t"         -> CType.I32,
    "int64_t"         -> CType.I64,
    "double"          -> CType.F64,
    "const char *"    -> CType.Str,
    "const uint8_t *" -> CType.Bytes,
    "double *"        -> CType.OutF64,
    "sui_handle"      -> CType.Handle,
    "sui_void_cb"     -> CType.VoidCb,
    "sui_text_cb"     -> CType.TextCb,
    "sui_bool_cb"     -> CType.BoolCb
  )

  /** Splits `const char *title` into its type and drops the parameter name. C puts the `*`
    * with the name, so the type is everything up to the last identifier, `*` included.
    */
  private def cParamType(decl: String): Option[CType] = {
    val s = decl.trim.replaceAll("\\s+", " ")
    if s == "void" then None
    else {
      val i    = s.lastIndexWhere(c => c == ' ' || c == '*')
      val tpe  = s.substring(0, i + 1).trim
      val norm = if s.contains('*') then s"${tpe.stripSuffix("*").trim} *" else tpe
      cTypes.get(norm)
    }
  }

  private val cFn = """^\s*(.+?)\s*\**\s*\b(sui_\w+)\s*\((.*)\)\s*;\s*$""".r

  def cHeader(source: String): Result = {
    val joined   = joinWrapped(source, ";", _ => true)
    val decls    = List.newBuilder[Decl]
    val unparsed = List.newBuilder[String]
    joined.foreach { line =>
      line match {
        case cFn(retRaw, name, argsRaw) =>
          val ret = cTypes.get(
            if line.contains(s"*$name") || line.contains(s"* $name") then s"${retRaw.trim} *"
            else retRaw.trim
          )
          val args = splitArgs(argsRaw).map(cParamType)
          if ret.isDefined && args.forall(_.isDefined) then
            decls += Decl(name, ret.get, args.flatten)
          else unparsed += line
        case l if l.contains("sui_") && l.contains("(") && l.endsWith(";") => unparsed += l
        case _                                                            => ()
      }
    }
    Result(decls.result(), unparsed.result())
  }

  // -- Swift @_cdecl -------------------------------------------------------

  private val swiftTypes: Map[String, CType] = Map(
    "Int32"                            -> CType.I32,
    "Int64"                            -> CType.I64,
    "Double"                           -> CType.F64,
    "UnsafePointer<CChar>"             -> CType.Str,
    "UnsafePointer<UInt8>"             -> CType.Bytes,
    "UnsafeMutablePointer<Double>"     -> CType.OutF64,
    "UnsafeMutableRawPointer"          -> CType.Handle,
    "sui_void_cb"                      -> CType.VoidCb,
    "sui_text_cb"                      -> CType.TextCb,
    "sui_bool_cb"                      -> CType.BoolCb
  )

  /** A trailing `?` is Swift's optional, which is the same C pointer — `sui_get_text`
    * returns `UnsafePointer<CChar>?` because it returns NULL for a view with no text. It
    * carries no ABI difference, so it is normalised away rather than given its own type.
    */
  private def swiftType(s: String): Option[CType] =
    swiftTypes.get(s.trim.stripPrefix("@escaping").trim.stripSuffix("?"))

  private val cdecl  = """^@_cdecl\("(\w+)"\)\s*$""".r
  private val swFn   = """^\s*public func \w+\s*\((.*)\)\s*(?:->\s*(\S+)\s*)?\{?\s*$""".r
  private val swArg  = """^\s*_\s+\w+:\s*(.+?)\s*$""".r

  def swiftShim(source: String): Result = {
    val lines    = source.split("\n", -1).toList
    val decls    = List.newBuilder[Decl]
    val unparsed = List.newBuilder[String]

    var i = 0
    while i < lines.length do {
      lines(i) match {
        case cdecl(name) =>
          // The signature may wrap over several lines; join to the opening brace.
          val start = i + 1
          var end   = start
          while end < lines.length && !lines(end).trim.endsWith("{") do end += 1
          val sig = lines.slice(start, math.min(end + 1, lines.length)).mkString(" ")
            .replaceAll("\\s+", " ").trim
          sig match {
            case swFn(argsRaw, retRaw) =>
              val ret  = Option(retRaw).fold(Option(CType.Void))(r => swiftType(r.stripSuffix("{")))
              val args = splitArgs(argsRaw).map {
                case swArg(t) => swiftType(t)
                case _        => None
              }
              if ret.isDefined && args.forall(_.isDefined) then
                decls += Decl(name, ret.get, args.flatten)
              else unparsed += s"$name: $sig"
            case _ => unparsed += s"$name: $sig"
          }
          i = end + 1
        case _ => i += 1
      }
    }
    Result(decls.result(), unparsed.result())
  }

  // -- Scala @extern -------------------------------------------------------

  private val scalaTypes: Map[String, CType] = Map(
    "Unit"        -> CType.Void,
    "CInt"        -> CType.I32,
    "Long"        -> CType.I64,
    "CDouble"     -> CType.F64,
    "Double"      -> CType.F64,
    "CString"     -> CType.Str,
    "Ptr[Byte]"   -> CType.Handle,
    "Handle"      -> CType.Handle,
    "Ptr[CDouble]" -> CType.OutF64,
    "Ptr[Double]"  -> CType.OutF64,
    "Ptr[UByte]"   -> CType.Bytes,
    "VoidCb"       -> CType.VoidCb,
    "TextCb"       -> CType.TextCb,
    "BoolCb"       -> CType.BoolCb
  )

  private val scFn = """^\s*def\s+(sui_\w+)\s*\((.*)\)\s*:\s*(\S+?)\s*=\s*extern\s*$""".r

  def scalaExterns(source: String): Result = {
    val joined   = joinWrapped(source, "= extern", _.startsWith("def "))
    val decls    = List.newBuilder[Decl]
    val unparsed = List.newBuilder[String]
    joined.foreach {
      case l @ scFn(name, argsRaw, retRaw) =>
        val ret = scalaTypes.get(retRaw.trim)
        val args = splitArgs(argsRaw).map { a =>
          val t = a.split(":", 2)
          if t.length == 2 then scalaTypes.get(t(1).trim) else None
        }
        if ret.isDefined && args.forall(_.isDefined) then decls += Decl(name, ret.get, args.flatten)
        else unparsed += l
      case l if l.contains("= extern") && l.contains("sui_") => unparsed += l
      case _                                                 => ()
    }
    Result(decls.result(), unparsed.result())
  }

  // -- Swift sui_create ----------------------------------------------------

  /** The `case` blocks of a shim's `sui_create` switch: each explicit code with the source
    * of its block, plus the `default:` block. Enough to check which view a kind builds
    * without parsing Swift — the switch is flat, one `case` or `default:` per block.
    */
  final case class Cases(explicit: Map[Int, String], default: Option[String]) {

    /** The block a code actually runs: its own case, or `default:`. */
    def blockFor(code: Int): Option[String] = explicit.get(code).orElse(default)
  }

  private val swCase    = """^\s*case\s+([\d,\s]+):\s*$""".r
  private val swDefault = """^\s*default:\s*$""".r

  def swiftCreateCases(source: String): Cases = {
    val lines = source.split("\n", -1).toList
      .dropWhile(l => !l.contains("func sui_create("))
      .drop(1)
      .takeWhile(_ != "}")
    val explicit = Map.newBuilder[Int, String]
    var default  = Option.empty[String]
    var codes    = Option.empty[List[Int]] // None: the current block is `default:`
    var inBlock  = false                   // false until the first case: the switch header
    val buf      = new StringBuilder

    def flush(): Unit = {
      if inBlock then codes match {
        case Some(cs) => cs.foreach(c => explicit += c -> buf.toString)
        case None     => default = Some(buf.toString)
      }
      buf.clear()
    }

    lines.foreach {
      case swCase(raw) =>
        flush()
        inBlock = true
        codes = Some(raw.split(",").toList.map(_.trim).filter(_.nonEmpty).map(_.toInt))
      case swDefault() =>
        flush()
        inBlock = true
        codes = None
      case l => buf.append(l).append('\n')
    }
    flush()
    Cases(explicit.result(), default)
  }

  // -- shared helpers ------------------------------------------------------

  /** Joins declarations that wrap over several lines into one line each, so the regexes
    * above can stay single-line. `terminator` is what ends a declaration in that language.
    */
  private def joinWrapped(
    source:     String,
    terminator: String,
    startsDecl: String => Boolean
  ): List[String] = {
    val out = List.newBuilder[String]
    val buf = new StringBuilder
    stripComments(source).split("\n", -1).foreach { raw =>
      val line = raw.trim
      // Preprocessor lines never continue a declaration, so they are dropped rather than
      // joined — otherwise `#include` swallows the first function in the file. `startsDecl`
      // does the same job for a language where a file has a preamble: without it the
      // package clause, the imports and the type aliases all end up glued to the first
      // `def`, and a real declaration is reported as unparsed.
      if line.isEmpty || line.startsWith("//") || line.startsWith("#") then ()
      else if buf.isEmpty && !startsDecl(line) then ()
      else {
        if buf.nonEmpty then buf.append(' ')
        buf.append(line)
        if line.endsWith(terminator) || line.endsWith(";") then {
          out += buf.toString.replaceAll("\\s+", " ")
          buf.clear()
        }
      }
    }
    if buf.nonEmpty then out += buf.toString.replaceAll("\\s+", " ")
    out.result()
  }

  /** Removes C block comments, including multi-line ones, and `//` tails.
    *
    * A block comment that *ends* on the same line as a declaration is what made the first
    * version of this parser fail: the tail of the `sui_create` kind-code comment stayed
    * glued to the declaration, and a real function was reported as unparsed.
    */
  private def stripComments(source: String): String = {
    val sb    = new StringBuilder
    var i     = 0
    var block = false
    while i < source.length do {
      if block then {
        if source.startsWith("*/", i) then { block = false; i += 2 }
        else {
          if source(i) == '\n' then sb.append('\n')
          i += 1
        }
      } else if source.startsWith("/*", i) then { block = true; i += 2 }
      else if source.startsWith("//", i) then {
        while i < source.length && source(i) != '\n' do i += 1
      } else { sb.append(source(i)); i += 1 }
    }
    sb.toString
  }

  /** Splits an argument list on top-level commas — `Ptr[CFuncPtr2[Long, CString, Unit]]`
    * contains commas that are not separators.
    */
  private def splitArgs(s: String): List[String] = {
    if s.trim.isEmpty || s.trim == "void" then Nil
    else {
      val out   = List.newBuilder[String]
      val buf   = new StringBuilder
      var depth = 0
      s.foreach { c =>
        if c == '[' || c == '<' || c == '(' then { depth += 1; buf.append(c) }
        else if c == ']' || c == '>' || c == ')' then { depth -= 1; buf.append(c) }
        else if c == ',' && depth == 0 then { out += buf.toString; buf.clear() }
        else buf.append(c)
      }
      if buf.toString.trim.nonEmpty then out += buf.toString
      out.result().map(_.trim).filter(_.nonEmpty)
    }
  }
}
