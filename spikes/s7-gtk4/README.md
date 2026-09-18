# S7 — GTK4 renderer smoke test — how to run

Needs `libgtk-4-dev` (installed 2026-09-18) and a display.

    export DISPLAY=:0 WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/$(id -u)
    sbt nativeLink
    ./target/out/native0.5/scala-3.9.0/s7-gtk4/s7-gtk4

The app opens a 360x200 window with a header bar, a label and -/+ buttons, then
drives itself: a background Scala thread posts a UI update via `g_idle_add`, and
two button activations are dispatched through GTK's own signal machinery.
