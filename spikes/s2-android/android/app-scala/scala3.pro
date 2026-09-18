# R8 keep rules for Scala 3 on Android.
# Each rule was added only because R8 or the ART runtime demanded it. Do not add
# speculative rules: every one of these costs APK size.

# --- 1. `lazy val` ---------------------------------------------------------
# Scala 3 compiles `lazy val x` to a field `x$lzy1` plus a static VarHandle
# obtained in <clinit> via MethodHandles.Lookup.findVarHandle(cls, "x$lzy1", ...).
# That is a reflective lookup *by name*, so R8's renaming breaks it at class-init
# time with:
#   java.lang.NoSuchFieldException: No field moodNames$lzy1 in class Lscalaui/s2/MainActivity;
#   at java.lang.invoke.MethodHandles$Lookup.findVarHandle
#   at scalaui.s2.MainActivity.<clinit>
# `-keepclassmembernames` is NOT enough: it only stops renaming, so R8 still removes
# a field nothing references statically, and the same exception reappears from inside
# the Scala library itself ("No field cache$lzy1 in class Ly0"). `-keepclassmembers`
# stops both removal and renaming. Class names may still be obfuscated: findVarHandle
# takes a Class object, not a class name.
-keepclassmembers class ** {
    *** *$lzy*;
}
