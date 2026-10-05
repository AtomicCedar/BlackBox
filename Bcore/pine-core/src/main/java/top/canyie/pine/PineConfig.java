package top.canyie.pine;

import android.os.Build;

import java.util.Locale;

/**
 * A class to stores some configures.
 * @author canyie
 */
@SuppressWarnings("WeakerAccess") public final class PineConfig {
    public static int sdkLevel;
    /**
     * Whether we need to print more detailed logs.
     */
    public static boolean debug = false;

    /**
     * Whether the current process is debuggable.
     */
    public static boolean debuggable;

    /**
     * Whether all Pine hooks won't take effect.
     */
    public static boolean disableHooks;

    /**
     * Internal API. Whether we should use fast-native to speedup jni method calling.
     */
    public static boolean useFastNative;
    /** Set to true will try to hide certain features. Some information used for debugging may be erased.  */
    public static boolean antiChecks = true;
    /**
     * Set to true will disable the hidden api policy.
     * <p>
     * Implemented with the official data interface {@code VMRuntime.setHiddenApiExemptions()}
     * (via {@code top.canyie.pine.utils.HiddenApiBypass}): it only writes runtime data
     * and does NOT modify libart.so's .text section, so integrity checks (CRC of .text etc.)
     * will not be triggered. The exemption is applied once at {@link Pine#ensureInitialized()}
     * and cannot be revoked afterwards (ART caches access flags).
     * <p>
     * NOTE: {@code setHiddenApiExemptions} is process-wide and does not distinguish domains,
     * so this flag and {@link #disableHiddenApiPolicyForPlatformDomain} are equivalent —
     * if either is {@code true}, a full (all-prefix) exemption is applied.
     * <p>
     * If the exemption cannot be applied (library unavailable), Pine logs an error and hidden
     * APIs remain restricted; use {@link top.canyie.pine.utils.HiddenApiCompat} /
     * {@link top.canyie.pine.utils.PinePass} to access hidden members on demand.
     */
    public static boolean disableHiddenApiPolicy = true;
    /** Set to true will disable the hidden api policy. Equivalent to {@link #disableHiddenApiPolicy} (see its note). */
    public static boolean disableHiddenApiPolicyForPlatformDomain = true;

    /**
     * A function to load our native library (libpine.so)
     * @see Pine.LibLoader
     */
    public static Pine.LibLoader libLoader = new Pine.LibLoader() {
        @Override public void loadLib() {
            System.loadLibrary("pine");
        }
    };

    static {
        sdkLevel = Build.VERSION.SDK_INT;
        if (sdkLevel == Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (isAtLeastPreReleaseCodename("VanillaIceCream")) {
                // Android 15 (VanillaIceCream) Preview
                sdkLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE + 1;
            }
        }
    }

    // https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:core/core/src/main/java/androidx/core/os/BuildCompat.java;l=49;drc=f8ab4c3030c3fbadca32a9593c522c89a9f2cadf
    private static boolean isAtLeastPreReleaseCodename(String codename) {
        final String buildCodename = Build.VERSION.CODENAME.toUpperCase(Locale.ROOT);

        // Special case "REL", which means the build is not a pre-release build.
        if ("REL".equals(buildCodename)) {
            return false;
        }

        return buildCodename.compareTo(codename.toUpperCase(Locale.ROOT)) >= 0;
    }

    private PineConfig() {
        throw new RuntimeException();
    }
}
