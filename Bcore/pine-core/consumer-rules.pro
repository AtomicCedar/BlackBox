# Pine
-keep class top.canyie.pine.Pine {
    public static long openElf;
    public static long findElfSymbol;
    public static long closeElf;
    public static long getMethodDeclaringClass;
    public static long syncMethodEntry;
    public static long suspendVM;
    public static long resumeVM;
    private static int arch;
}
-keep class top.canyie.pine.Pine$HookRecord {
    public long trampoline;
}
-keep class top.canyie.pine.Ruler { *; }
-keep class top.canyie.pine.Ruler$I { *; }
-keep class top.canyie.pine.entry.**Entry {
    static *** **Bridge(...);
}

# Keep JNI names for classes that still contain native methods without pinning every class.
-keepclasseswithmembernames class top.canyie.pine.** {
    native <methods>;
}

# HiddenApiBypass (synced from org.lsposed.hiddenapibypass upstream main, post-v6.1:
# DexFieldLayout dex-layout probing + fallback, offset cache helpers):
# Helper's inner classes are accessed via Unsafe/reflection (offsets, NeverCall methods/fields),
# so they must not be renamed or removed when the host app minifies.
-dontwarn dalvik.system.VMRuntime

-if class top.canyie.pine.utils.HiddenApiBypass
-keepclassmembers class top.canyie.pine.utils.Helper$* { *; }

# DexFieldLayout is package-private, instantiated directly by HiddenApiBypass, and its
# Layout entries carry ART-version-dependent dex offsets by name; keep it (and its inner
# Layout class) whenever HiddenApiBypass is kept, so name-based lookups survive minification.
-if class top.canyie.pine.utils.HiddenApiBypass
-keep class top.canyie.pine.utils.DexFieldLayout { *; }

# PinePass uses android.util.Property.of(...) (the "Property route" hidden-API accessor);
# allow R8 to strip it like the official library does.
-assumenosideeffects class android.util.Property {
    public static *** of(...);
}
