# Vendored third-party sources

`yoga/` is a shallow clone of https://github.com/facebook/yoga pinned to **v3.2.1**
(commit `042f501`). It is gitignored: re-create with

    git clone --depth 1 --branch v3.2.1 https://github.com/facebook/yoga.git yoga

Build the static library (from the spike root):

    cmake -S third-party/yoga -B build/yoga -DCMAKE_BUILD_TYPE=Release \
          -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_TESTING=OFF
    cmake --build build/yoga --target yogacore --parallel 4

`-DBUILD_TESTING=OFF` is required: Yoga's own gtest suite does not compile under
gcc 15 (warnings-as-errors). The library itself builds cleanly.
