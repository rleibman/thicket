# shim-gen

The Apple ABI, described once, plus the check that says the four hand-written copies of it
still agree.

Plain JVM. No Scala Native, no Xcode, no Swift toolchain — `sbt shimGen/testOnly *` runs
anywhere, which is the point: the failure it catches is an Apple failure that only a Mac can
*suffer*, and this is what makes it visible on the machine where the change is written.

## What problem this solves

Every function crossing the Scala ⇄ Apple boundary is declared **four** times:

| | |
|---|---|
| `shim/include/scalaui_apple.h` | what sn-bindgen reads |
| `shim/Sources/Shim+AppKit.swift` | the `@_cdecl` signature |
| `shim/Sources/Shim+UIKit.swift` | the `@_cdecl` signature again |
| `src/main/scala/.../Shim.scala` | the `@extern` binding |

At 34 functions that is **136 declarations** to keep in step by hand. And nothing checks
them: Swift compiles against its own signature, Scala Native compiles against its own
`extern`, and the linker matches the two **by name only**. A parameter that is `Int32` on
one side and `Int64` on the other links cleanly, passes CI, and reads a garbage register at
runtime — in a callback, on a phone.

That is the failure this module exists to make impossible. Not the line count.

## What it does not do

**It does not generate function bodies**, and that is a measurement rather than a
limitation. On the two shims that exist:

| | AppKit | UIKit |
|---|---|---|
| Exported functions | 34 | 35 |
| Signature lines (generatable) | 80 | 82 |
| Body lines (hand-written) | 211 | 212 |

Of the 34 functions in both shims, **7** have byte-identical bodies, 13 differ only in a
type or property name, and **14 are genuinely different code**. `NSImageView.imageScaling`
is not `UIView.contentMode`; `placeholderString` is not `placeholder`; AppKit's coordinate
system is flipped relative to UIKit's. A generator that emitted bodies would be emitting
guesses, and a wrong body is worse than no body.

So: the declaration layer is generated, the bodies stay hand-written, and
`Emit.swiftSignatures` scaffolds a new shim with `fatalError` placeholders rather than
inventing something.

## Usage

```bash
sbt "shimGen/testOnly *"          # the consistency and round-trip checks — run this
sbt shimGen/run                   # dry run: what would be written
sbt "shimGen/run /tmp/out"        # write scalaui_apple.h, Shim.scala, Shim+New.swift
```

## The files

| | |
|---|---|
| `Abi.scala` | **The source of truth.** 34 functions, in a type vocabulary with no struct in it |
| `Parse.scala` | Reads declarations back out of C, Swift and Scala — regex parsers, deliberately |
| `Emit.scala` | Emits the C header, the Scala externs, and Swift signature scaffolding |
| `ConsistencySuite` | The four artefacts agree, and `Abi` describes them |
| `GenerateSuite` | Generating from `Abi` reproduces what is checked in |

## Two things the checks had to be taught

**`sui_set_root_view` is exported by the UIKit shim and declared in neither the header nor
`Shim.scala`, and that is correct.** On iOS the Swift side owns `@main`, because a
`UIScene` delegate cannot live in the static archive Scala Native produces, so it hands its
root view *in* rather than being asked for one. Scala never calls it. `Abi.swiftOnly`
records that, so it is not reported as drift.

**Scala Native cannot distinguish `sui_handle` from `const uint8_t *`** — both are
`Ptr[Byte]`, and there is no variant that would land in a different register. So the
Scala-side comparison runs at `Abi.Repr` (i32 / i64 / f64 / pointer / function pointer)
rather than at the named type. Demanding a distinction the language does not have would be
a false positive forever; what survives is every difference that costs a wrong register.

## Adopting the generated files

Not done yet, on purpose. `GenerateSuite` proves the generated header and externs declare
exactly what the checked-in ones declare, so the swap is safe — but it should be made on a
machine that can compile the Swift and run the 23-check self-test afterwards. Forgejo #7.
