# S6 — Calibration baselines — BRIEF (optional)

**Machine:** macOS (+ Linux for the RN part). **Budget:** 1 week total, time-boxed hard.

Purpose: numbers, not opinions, for the comparison tables in `docs/06`. Two hello
apps (a label + button + counter), each measured for: build effort (hours),
toolchain steps, `.app`/APK size, cold start, RSS.

1. **Option D** — ScalaFX + GraalVM native-image + Gluon Substrate → iOS simulator
   (device if available) and Android. Follow Gluon's docs; use the Gluon GraalVM build.
2. **Option A** — Scala.js + React Native via Expo. Use the latest maintained facade
   you can find (Slinky-native 0.7.x, or scalajs-react with hand-written RN facades if
   Slinky fails to build on current Scala.js). Android emulator on Linux is fine;
   iOS simulator on the Mac.

Write `REPORT.md` with a side-by-side table and a short "what it felt like" for each.
Stop at the time box even if unfinished; partial numbers are still useful.
