# R8 keep rules for Scala 3 on Android (S2).
# Only rules the runtime actually demanded. Each costs APK size.

# Scala 3 compiles `lazy val x` to a field `x$lzy1` plus a VarHandle obtained in <clinit>
# via MethodHandles.Lookup.findVarHandle(cls, "x$lzy1", ...) — a reflective lookup *by
# name*. R8 renames or removes the field and the class dies at init:
#   NoSuchFieldException: No field moodNames$lzy1 in class L...;
# `-keepclassmembernames` is NOT enough: it stops renaming but not removal.
-keepclassmembers class ** {
    *** *$lzy*;
}
