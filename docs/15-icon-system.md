# 15 — Icons: a design for review (#61)

**Status: draft for the project owner's decisions.** Nothing here is built. #61 orders the
work as icons first, then `IconButton`, then `TabView`, and says the icon system needs a
design before code. This is that design. §15.9 lists the decisions it needs.

## 15.1 What it has to do

- **Name an icon once, in shared code, and get each platform's own glyph.** `Icon.Delete`
  is a trash can drawn the way GNOME, macOS, iOS and Android each draw one, not one picture
  everywhere. This is the same rule as type roles (`TextRole`) and colour tokens: the
  framework names the *meaning* and each platform supplies the look.
- **Follow tint and the platform's colour.** An icon is a mask, not a picture. It takes the
  foreground colour of whatever it is in, including dark mode, a disabled button, and
  `Prop.Tint`.
- **Let an app add its own icons** without writing platform code.
- **Stay framework-only on Android:** no AndroidX and no Material Components, as every Android
  widget so far (see the decision log's AndroidX entries).

Out of scope: multicolour icons, app icons (the launcher or dock icon), animated symbols,
and SF Symbols' weight and variable-value features.

## 15.2 Where the glyphs come from

| | Built-in icons | Licence | How they reach the screen |
|---|---|---|---|
| GTK | the icon theme's **symbolic** icons (Adwaita on GNOME) | ships with the desktop; nothing is redistributed | `gtk_image_new_from_icon_name` / `gtk_button_set_icon_name`. GTK recolours `-symbolic` icons to the foreground colour itself |
| AppKit | **SF Symbols** | the OS's; usable only on Apple platforms, so never shipped elsewhere | `NSImage(systemSymbolName:accessibilityDescription:)`, a template image |
| UIKit | **SF Symbols** | as above | `UIImage(systemName:)`, a template image |
| Android | **Material Symbols** (Outlined, weight 400, 24 px) | **Apache-2.0** (`google/material-design-icons`, checked 2026-10-07) | see §15.4 |

**Measured on this Mac (macOS 27.0.1):** all 50 SF Symbol names considered for §15.3 exist
(`NSImage(systemSymbolName:)` returned non-nil for each). The GTK names in §15.3 are
**not yet verified**, because this Mac has no Adwaita theme. Verifying them on the Linux box
is the first task of the build (§15.8), through `gtk_icon_theme_has_icon`.

## 15.3 The built-in set

The proposal is a deliberately small set of the icons apps actually reach for in a toolbar,
a list row or a tab bar. Each name is a meaning, and the mapping is per platform:

| `Icon.` | GTK (symbolic) | SF Symbol | Material Symbol |
|---|---|---|---|
| `Add` | `list-add-symbolic` | `plus` | `add` |
| `Remove` | `list-remove-symbolic` | `minus` | `remove` |
| `Delete` | `user-trash-symbolic` | `trash` | `delete` |
| `Edit` | `document-edit-symbolic` | `pencil` | `edit` |
| `Search` | `system-search-symbolic` | `magnifyingglass` | `search` |
| `Settings` | `emblem-system-symbolic` | `gearshape` | `settings` |
| `Share` | `send-to-symbolic` | `square.and.arrow.up` | `share` |
| `Back` | `go-previous-symbolic` | `chevron.backward` | `arrow_back` |
| `Forward` | `go-next-symbolic` | `chevron.forward` | `arrow_forward` |
| `Close` | `window-close-symbolic` | `xmark` | `close` |
| `Menu` | `open-menu-symbolic` | `line.3.horizontal` | `menu` |
| `More` | `view-more-symbolic` | `ellipsis.circle` | `more_vert` |
| `Done` | `object-select-symbolic` | `checkmark` | `check` |
| `Info` | `dialog-information-symbolic` | `info.circle` | `info` |
| `Warning` | `dialog-warning-symbolic` | `exclamationmark.triangle` | `warning` |
| `Error` | `dialog-error-symbolic` | `xmark.octagon` | `error` |
| `Home` | `go-home-symbolic` | `house` | `home` |
| `Favorite` / `FavoriteFilled` | `non-starred-symbolic` / `starred-symbolic` | `star` / `star.fill` | `star` / `star` (fill 1) |
| `Refresh` | `view-refresh-symbolic` | `arrow.clockwise` | `refresh` |
| `Download` | `folder-download-symbolic` | `square.and.arrow.down` | `download` |
| `Calendar` | `x-office-calendar-symbolic` | `calendar` | `calendar_today` |
| `Person` | `avatar-default-symbolic` | `person` | `person` |

Twenty-four glyphs (22 rows, with `Favorite` in two variants).

**`More` shows why the names are meanings.** Android's overflow is a vertical ellipsis
(`more_vert`), Apple's a horizontal one in a circle, and GNOME's is its own glyph. One
picture for all three would be wrong on two of them.

**Growing the set** is one row added to this table (one line of Scala per platform) plus
the gallery entry. Anything an app needs that isn't here is a custom icon (§15.5); the set
does not grow to cover one app's needs.

## 15.4 Android: no `res/`, so icons are path data

Android's usual route is a `VectorDrawable` in `res/drawable`. That doesn't work here:
`res/` has to be compiled into the APK by AAPT2, and `thicket-renderer-android` is a plain
jar, which can't carry Android resources. An AAR could, but it would tie the renderer to
Gradle-built AARs. `android.R.drawable.ic_menu_*` are Holo-era, and they vary between
OS versions and vendors, so they would look different on every phone.

**Proposal:** a Material Symbol is one SVG path. Fetched 2026-10-07, `delete` (Outlined,
24 px) is a single `<path d="M280-120q-33 0-56.5-23.5T200-200v-520h-40…Z"/>` in a
`0 -960 960 960` viewBox. So:

1. **A small tool**, `tools/icon-gen`, reads the pinned SVGs and writes a Scala object of
   path strings into `renderer-android`. The commit is pinned, so the glyphs change only when
   someone bumps it, and the Apache-2.0 notice goes into `NOTICE`. The generated file is
   checked in, as `Shim.scala` is, with a `--check` mode for CI.
2. **At runtime**, a ~150-line SVG path parser (`M L H V C S Q T A Z`, absolute and
   relative; arcs converted to cubics) builds an `android.graphics.Path`. A small `Drawable`
   subclass draws it scaled to its bounds and honours `setTint`. It uses only framework
   APIs: `Path`, `Paint`, `Drawable`.
3. **The parser lives in `renderer-api`** as plain Scala that produces a list of absolute
   commands, because §15.5 uses it on every platform.

A cost to check: 24 path strings are about 10–15 KB of class data in the APK, against the
~200 KB the todo APK is today. Measure it, don't assume it.

## 15.5 An app's own icons

**Proposal: an app supplies a single-colour SVG**, square (24×24 or Material's 960 box), with
only `<path>` elements. At build time it becomes path data, and every platform renders that
path itself. One mechanism serves all four platforms, and none of them reads a file at
runtime.

```scala
// The sbt plugin generates this from src/main/icons/*.svg:
object AppIcons {
  val Leaf: Icon = Icon.custom("leaf", "M12 2C6.5 2 2 6.5 2 12…Z")
}

IconButton(AppIcons.Leaf, label = "Plant a tree")(plant())
```

| | Renders a custom icon by |
|---|---|
| Android | the §15.4 `Drawable`, the same code as the built-ins |
| AppKit / UIKit | a new ABI call taking the commands as a flat `double` array (no struct crosses the boundary: `Abi.scala`'s rule), which builds a `CGPath`, fills it into an image at the requested size, and marks it as a template (`isTemplate` / `.alwaysTemplate`), so it tints like an SF Symbol |
| GTK | a `GskPath` built from the commands and drawn in a small `GdkPaintable`. `GskPathBuilder` is GTK 4.14+, and the Linux box has 4.22. The alternative, writing a `-symbolic.svg` into a cache directory added with `gtk_icon_theme_add_search_path`, has GTK do the recolouring but writes files at startup |

**Why not SVG files at runtime:** UIKit and AppKit don't load an SVG file outside an asset
catalogue, and an asset catalogue needs `actool` and a bundle, which the macOS examples
don't have. Android can't load one at all without AndroidX. Path data is the common
denominator, and it is also exactly what a single-colour icon is.

**What an app can't do:** use a multicolour SVG, gradients, strokes, `<circle>` and other
shapes, or transforms. The tool **rejects these with a message**, rather than drawing them
wrongly. Converting shapes to paths is a later convenience if someone needs it.

## 15.6 The API

```scala
enum Icon {
  case Add, Remove, Delete, Edit, Search, Settings, Share, Back, Forward, Close, Menu, More,
    Done, Info, Warning, Error, Home, Favorite, FavoriteFilled, Refresh, Download, Calendar,
    Person
  case Custom(name: String, path: String)
}

// Prop:
case Glyph(icon: Option[Icon])

// Element:
def IconButton(icon: Icon | Signal[Icon], label: String)(onTap: => Unit): Element
def Icon(icon: Icon | Signal[Icon]): Element              // an icon on its own, e.g. in a row
// later, #61 step 3: Tab(title, icon)(content) inside TabView
```

- **`label` is required on `IconButton`.** It is the accessibility label: VoiceOver,
  TalkBack and Orca read it, and it is the tooltip on GTK and macOS. A button with no words
  and no label is invisible to a screen reader. Making it a parameter, not an optional prop,
  makes that impossible to forget.
- **The size follows the platform:** GTK's 16 px symbolic size, SF Symbols scaled to the
  surrounding text style, and 24 dp on Android. No `size` parameter in v1, for the same
  reason there is no point size in `TextRole`.
- **`Prop.Glyph`** carries the icon to a renderer. The `Icon` widget and `IconButton` each
  use it, and so can `Button` later (an icon beside text), with no new prop.

## 15.7 How it is verified

The same rules as the gallery: read the answer back from the platform, and run a control that
breaks it.

| | Read back |
|---|---|
| GTK | `gtk_image_get_icon_name` on the button's image, plus `gtk_icon_theme_has_icon` for **every** built-in name. A missing name in Adwaita fails the test, rather than drawing GTK's "missing" glyph |
| AppKit / UIKit | each built-in resolved with `systemSymbolName` / `systemName` and checked non-nil, on each OS the CI runs; the button's image `isTemplate` / `renderingMode == .alwaysTemplate` |
| Android | the button's drawable is the path `Drawable`, with the expected path bounds; and **pixels**: rendered to a bitmap, the icon's pixels are the tint colour, as the ContentFit check measures its picture |
| Custom | the same, plus a deliberately wrong path (one flipped command) has to fail the pixel check |

Controls: map one built-in to a non-existent name, and drop the tint.

## 15.8 Order of work

1. **Verify the GTK names (Linux box).** Run `gtk_icon_theme_has_icon` over §15.3 and replace
   any that don't exist in the installed Adwaita. Record the result here or in the decision
   log.
2. **Contract and core:** `Icon`, `Prop.Glyph`, `IconButton`, the `Icon` element, and the
   path parser in `renderer-api` with its tests (round-tripping every built-in path, and
   arcs).
3. **GTK and Android (Linux box)**, including `tools/icon-gen` and the `NOTICE` entry.
4. **Apple (Mac):** SF Symbol names, template images, and the custom-path ABI call.
5. **Gallery:** every built-in icon in a row, plus an `IconButton` and a custom icon, on all
   four platforms (the rule in docs/12 §12.10).
6. Then `TabView` (#61 step 3) uses `Icon` for its tabs.

## 15.9 Decisions for the project owner

1. **The built-in set:** the 24 glyphs in §15.3, or a different list? Smaller is easier to
   keep right on four platforms.
2. **Android artwork: Material Symbols, Outlined, weight 400.** The alternatives are Rounded
   or Sharp, or commissioned artwork. Material Symbols are Android's own idiom and Apache-2.0
   licensed.
3. **Custom icons as single-colour SVG paths turned into path data at build time** (§15.5),
   with the tool rejecting anything else. The alternative is per-platform assets
   (`res/drawable`, asset catalogues, theme directories). That gives full fidelity, but means
   three asset pipelines and Android `res/` the renderer jar can't carry.
4. **Custom icons on GTK:** `GskPath` (native drawing, no files), or a generated `-symbolic.svg`
   in a theme search path (GTK recolours it, but files are written at startup)?
5. **`label` required on `IconButton`:** agreed?
