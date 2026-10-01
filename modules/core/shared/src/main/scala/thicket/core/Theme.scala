package thicket.core

/** An 8-bit-per-channel colour. Only ever supplied by an *app*; the framework never has a default one.
  */
final case class Rgb(
  r: Int,
  g: Int,
  b: Int
) {

  def hex: String = f"#$r%02x$g%02x$b%02x"

  /** WCAG relative luminance. */
  def luminance: Double = {
    def channel(v: Int): Double = {
      val c = v / 255.0
      if c <= 0.03928 then c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
  }

  /** Black or white, whichever reads better on this colour.
    *
    * An app that overrides a *background* role and says nothing about the foreground has, in effect, broken the
    * platform's contrast guarantee — the platform's text colour was chosen for the platform's background. Deriving one
    * is the framework's job: it is the difference between branding an app and making it unreadable.
    */
  def contrasting: Rgb = if luminance > 0.179 then Rgb(0, 0, 0) else Rgb(255, 255, 255)

}

/** The roles an app may override.
  *
  * Roles, not widgets: an app says "my accent is this" once, rather than restyling every button. Anything not
  * overridden keeps the platform's own token, which is what preserves dark mode, contrast settings and the native look.
  */
enum ColorRole {

  case Accent
  case Surface
  case OnSurface
  case OnSurfaceSecondary
  case Danger

  /** Foreground on top of [[Accent]]. Left unset it is derived from the accent's luminance, so branding cannot silently
    * produce unreadable text.
    */
  case OnAccent

}

/** App-level overrides on top of the platform's own styling.
  *
  * The default is empty, and an empty theme is the *correct* theme for an app that wants to look native: every role
  * falls through to the platform. Overriding is a deliberate act of branding, and the app takes on what it implies —
  * notably that a hard-coded colour does not follow the user's light/dark preference unless the app supplies both.
  */
final case class Theme(colors: Map[ColorRole, Rgb] = Map.empty) {

  def withColor(
    role: ColorRole,
    rgb:  Rgb
  ): Theme = copy(colors = colors.updated(role, rgb))
  def get(role: ColorRole): Option[Rgb] =
    role match {
      case ColorRole.OnAccent =>
        // Explicit if given, otherwise derived from the accent — never simply absent, or a
        // branded button inherits the platform's text colour against a colour the platform
        // never saw.
        colors.get(ColorRole.OnAccent).orElse(colors.get(ColorRole.Accent).map(_.contrasting))
      case other => colors.get(other)
    }
  def isEmpty: Boolean = colors.isEmpty

}

object Theme {

  /** No overrides: every role comes from the platform. */
  val platform: Theme = Theme()

  private var current: Theme = platform

  /** Installed once by the host, before mounting. Per-subtree overrides (`Provide`) are a later addition; this is
    * deliberately the smallest thing that lets an app brand itself.
    */
  def install(theme: Theme): Unit = current = theme
  def active:                Theme = current

  /** Run `body` with `theme` as the active one, then restore whatever was active before.
    *
    * Roles are resolved to colours when an *element is built*, not when it is rendered, so this is the window that
    * matters. It is a stack rather than a set-and-forget because `Provide` nests: an app can brand a section and brand
    * a card inside it, and the card's siblings must not inherit the card's theme.
    *
    * Single-threaded like the rest of the graph — see `ThreadGuard`.
    */
  def withActive[A](theme: Theme)(body: => A): A = {
    val previous = current
    current = theme
    try body
    finally current = previous
  }

}
