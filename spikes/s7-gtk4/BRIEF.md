# S7 — GTK4 renderer smoke test — BRIEF

**Machine:** Linux (a desktop session with Wayland/X11 is needed to show a window;
headless via `xvfb-run` for CI-style checks). **Budget:** 3 days. **Depends on:** nothing.
**Gate:** no — this validates the renderer contract shape cheaply.

## Prerequisites (ask before installing)
- `libgtk-4-dev`, `pkg-config`, Scala Native's usual deps (`libunwind-dev` etc.).

## Deliverables
1. `sbt` Scala Native project depending on `com.indoorvivants.gnome::gtk4`
   (find the latest version and the matching Scala Native/Scala 3 versions on
   Scaladex; if it has not been published for the chosen Scala Native 0.5.x,
   regenerate with sn-bindgen following its README — report the effort).
2. A counter app: window with header bar, a label and two buttons, using GTK's
   `GtkBox` for layout (no Yoga here).
3. **Draft the renderer contract** from `07` §7.4 as a Scala trait in this spike
   (`scalaui.renderer.Renderer`) and implement it for GTK for *just* Box/Label/Button.
   Build the counter through the contract, not directly — the point is to see
   whether the contract's `create/update/insertChild/setFrame/measure/runOnUiThread`
   shape fits a real toolkit. `setFrame` on GTK: use `GtkFixed` for absolute
   positioning so Yoga-style layout is possible later; note how it feels.
4. Main loop: `g_idle_add` for `runOnUiThread` from a background Scala thread updating the label.
5. `REPORT.md`: link time, binary size, startup time, contract friction points
   (each with a proposed change to §7.4), and whether GTK4's accessibility (Orca)
   reads the label/buttons out of the box.
