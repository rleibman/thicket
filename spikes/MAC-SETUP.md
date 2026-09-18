# macOS setup for the Apple spikes (S1, S3, S8, S6)

Run this once on the Mac, then start Claude Code in the repo root and give it a
one-line prompt naming the spike (see "Handoff" below).

## 1. Xcode

From the App Store (not just the Command Line Tools — the spikes need the iOS SDK,
a simulator runtime and `xcodebuild`).

    xcode-select --install                 # if not already
    sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
    xcodebuild -runFirstLaunch
    xcodebuild -version
    xcrun --sdk iphonesimulator --show-sdk-path     # must print a path
    xcrun simctl list runtimes | grep iOS           # need at least one iOS 17+ runtime

If no runtime is listed: Xcode → Settings → Components → install an iOS runtime.

## 2. Scala toolchain

    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    brew install coursier/formulas/coursier && cs setup      # installs JDK, sbt, scala-cli
    brew install sbt                                          # ensure sbt 2.x launcher
    sbt --version                                             # expect 2.0.9+ (see docs/decisions.md)

Scala Native uses Apple clang from Xcode; no separate LLVM install is needed.
Record which clang is picked up (`clang --version`) in the spike REPORT.

## 3. Claude Code

    curl -fsSL https://claude.ai/install.sh | bash
    # or: npm install -g @anthropic-ai/claude-code

## 4. The repository

Whatever transport we agree on (private GitHub repo, or rsync from the Linux box):

    git clone <url> scala-ui && cd scala-ui
    git checkout plan/phase-0-spikes

## 5. Sanity check before starting a spike

    xcrun simctl list devices available | head       # a bootable device exists
    cs java-home --jvm temurin:21                    # a real JDK, not an EA build
    ls ~/Library/Developer/Xcode/DerivedData         # Xcode has run at least once

## Handoff — what to tell the agent

Start Claude in the repo root and say, verbatim:

    Do spike S1. Read CLAUDE.md first, then docs/decisions.md, then
    spikes/s1-native-ios/BRIEF.md. One spike only.

Then S3 and S8 in separate sessions, in that order, **only if S1 passes**.
S6 (calibration baselines) is optional and time-boxed.

## Carry these findings from the Linux spikes — they matter on Apple

1. **Scala Native cannot return a small struct by value from a callback** (S4). It
   type-checks, runs, and silently delivers shifted fields. `CGSize`, `CGRect` and
   `CGPoint` are returned by value all over UIKit/AppKit, so **S3 must test this
   explicitly**, and the Swift shim should use out-parameters as a blanket rule.
   Working C trampoline: `spikes/s4-yoga/native/src/main/resources/scala-native/measure_shim.c`.
2. **`CFuncPtr` cannot close over local state** (compile error). Every callback is a
   static function plus an explicit context — the handle table is mandatory.
   Reference implementation: `spikes/s7-gtk4/src/main/scala/scalaui/gtk/Handles.scala`.
3. **`Long` ⇄ `Ptr` needs `Intrinsics.castLongToRawPtr`** (S7). `id.asInstanceOf[Ptr[Byte]]`
   compiles and throws at runtime.
4. **sbt 2 gotchas** (docs/decisions.md): no `%%%`, `test` is incremental so use
   `testOnly *`, `-Werror` not `-Xfatal-warnings`, artifacts are symlinks into a CAS.
5. **Scala 3.9's minimum `-java-output-version` is 17.**
