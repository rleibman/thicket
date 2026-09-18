# S4 — Yoga layout from Scala — how to run

Prerequisites on this Linux box: cmake, g++, clang, and a libclang shim because
sn-bindgen 0.4.5 is linked against `libclang-17.so.17` while Ubuntu ships 20/21:

    mkdir -p ~/.local/lib/sn-bindgen-compat
    ln -sf /usr/lib/x86_64-linux-gnu/libclang-20.so.20 \
           ~/.local/lib/sn-bindgen-compat/libclang-17.so.17

Then:

    # 1. vendored Yoga (see third-party/README.md)
    git clone --depth 1 --branch v3.2.1 https://github.com/facebook/yoga.git third-party/yoga
    cmake -S third-party/yoga -B build/yoga -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_TESTING=OFF
    cmake --build build/yoga --target yogacore --parallel 4

    # 2. tests (note: `sbt test` is incremental on sbt 2 — use testOnly)
    export LD_LIBRARY_PATH=~/.local/lib/sn-bindgen-compat
    sbt "native/testOnly *"
