package top.niunaijun.blackbox.core;

import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * 按应用设备伪装（机器模拟）：把分身应用看到的设备参数 / SIM 信息 / 唯一标识
 * 换成配置值。与反检测层（hideRoot / maps 隐藏）相互独立——这里改的是应用
 * 进程内 Java 层的读数，不涉及任何 native 痕迹。
 * <p>
 * 配置是一个 key=value 文件：blackbox/hotfix/device_spoof/u&lt;userId&gt;/&lt;packageName&gt;，
 * 存在 = 启用，与热修复/so 注入同思路——同 uid 直接读文件，无需跨进程调用。
 * 空值字段不伪装（应用读到真实值）；随机值由 UI 侧生成后落盘，启动时只按值应用。
 * <p>
 * 应用时机：分身体 bindApplication 的 makeApplication 之后（与 SoInject 同点），
 * 此时 Build 类已加载、应用 Application 尚未 onCreate，改写字段与方法 hook
 * 都能在业务代码首次读取前生效。hook 走容器内 pine-xposed（XposedBridge /
 * XposedHelpers），与 Xposed 模块同一引擎，无额外依赖。
 */
public class DeviceSpoofConfig {

    private static final String TAG = "DeviceSpoof";

    public static boolean isEnabled(int userId, String packageName) {
        try {
            return BEnvironment.getDeviceSpoofFile(userId, packageName).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(int userId, String packageName, boolean enabled) {
        File file = BEnvironment.getDeviceSpoofFile(userId, packageName);
        try {
            if (enabled) {
                FileUtils.mkdirs(file.getParentFile().getAbsolutePath());
                if (!file.exists()) {
                    file.createNewFile();
                }
            } else if (file.exists()) {
                file.delete();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * 保存配置：启用 = 写入非默认字段，禁用 = 删文件。
     */
    public static void save(int userId, String packageName, boolean enabled, Data data) {
        File file = BEnvironment.getDeviceSpoofFile(userId, packageName);
        try {
            if (enabled) {
                FileUtils.mkdirs(file.getParentFile().getAbsolutePath());
                FileUtils.writeToFile(data.serialize().getBytes(StandardCharsets.UTF_8), file);
            } else if (file.exists()) {
                file.delete();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static Data getConfig(int userId, String packageName) {
        Data data = new Data();
        try {
            File file = BEnvironment.getDeviceSpoofFile(userId, packageName);
            if (!file.exists()) {
                return data;
            }
            for (String line : FileUtils.readToString(file.getAbsolutePath()).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                data.applyKey(key, value);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return data;
    }

    /**
     * 反多开检测：Context.getPackageCodePath() 返回的 APK 路径伪装成正常安装形态
     * （/data/app/<pkg>/base.apk）。真实 APK 在黑盒目录下，IO 规则已把
     * /data/app/<pkg> 重定向回真实路径。只 hook 读取 API——绝不改 LoadedApk
     * 的 sourceDir 字段（dex/内嵌 lib 路径与它强耦合，ART/linker 绕过 GOT
     * 直接 open 伪装路径会 ClassNotFoundException）。
     * 独立于设备伪装开关，分身体启动时无条件调用。
     */
    public static void hookPackageCodePath(final String packageName) {
        try {
            Class<?> clazz = XposedHelpers.findClass("android.app.ContextImpl", ClassLoader.getSystemClassLoader());
            XposedBridge.hookAllMethods(clazz, "getPackageCodePath", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult("/data/app/" + packageName + "/base.apk");
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 分身体进程启动时调用：读配置并按值改写 Build 字段、hook Settings /
     * TelephonyManager / CellIdentity 等读数。仅配置过的字段生效。
     */
    public static void apply(int userId, String packageName) {
        if (!isEnabled(userId, packageName)) {
            return;
        }
        Data cfg = getConfig(userId, packageName);
        if (cfg.fieldCount() == 0) {
            return;
        }
        try {
            applyBuildFields(cfg);
            applyUserAgent(cfg);
            applyIdentifiers(cfg);
            applySim(cfg);
            Slog.i(TAG, "applied for " + packageName + ", fields=" + cfg.fieldCount()
                    + " manufacturer=" + cfg.manufacturer + " marketName=" + cfg.marketName
                    + " model=" + cfg.model + " sdk=" + cfg.sdkInt + " buildId=" + cfg.buildId
                    + " imei=" + cfg.imei + " deviceId=" + cfg.deviceId);
        } catch (Throwable t) {
            Slog.e(TAG, "apply failed for " + packageName, t);
        }
    }

    // ---------------------------------------------------------------- Build 字段

    private static void applyBuildFields(Data cfg) {
        // MANUFACTURER 同时写 BRAND 与 MANUFACTURER，保持交叉一致
        setBuildField("MANUFACTURER", cfg.manufacturer);
        setBuildField("BRAND", cfg.manufacturer);
        setBuildField("MODEL", cfg.model);
        setBuildField("PRODUCT", cfg.product);
        setBuildField("DEVICE", cfg.device);
        setBuildField("BOARD", cfg.board);
        setBuildField("HARDWARE", cfg.board);
        // 指纹同时写 Build.FINGERPRINT 与 Build.VERSION.BASE_OS（检测方常交叉验证）
        setBuildField("FINGERPRINT", cfg.fingerprint);
        setBuildVersionField("BASE_OS", cfg.fingerprint);
        // 编译版本号：与指纹中间段、http.agent 的 Build/ 段保持一致。
        // Build.DISPLAY（= ro.build.display.id 字段版）也要改——开发助手这类工具
        // 的"编译版本号"行读的是字段而非属性，属性双通道覆盖不到。
        setBuildField("ID", cfg.buildId);
        setBuildField("DISPLAY", cfg.buildId);
        // SDK_INT 不能伪装到低于真机——应用会走旧 API 分支（如跳过 RECEIVER_EXPORTED），
        // 框架仍按新规则校验，导致启动崩溃。低于真机时跳过伪装。
        if (cfg.sdkInt > 0 && cfg.sdkInt >= Build.VERSION.SDK_INT) {
            setStaticInt("SDK_INT", cfg.sdkInt);
        }
        // RELEASE 是 String 字段（Guise 用 Int 配置会反射类型不匹配静默失败，这里修正）
        setBuildVersionField("RELEASE", cfg.release);
        if (!cfg.radioVersion.isEmpty()) {
            hookReturn(Build.class, "getRadioVersion", cfg.radioVersion);
        }
        applySystemProperties(cfg);
    }

    /**
     * Java 层 SystemProperties.get 全量伪装：Build 字段改写的只是运行时字段，但应用
     * 常直接读 ro.product.marketname（小米营销名）/ ro.build.id（编译版本号）等原始
     * 属性——开发助手这类工具就是从属性读的，不改这里就会出现"型号变了但名称/版本号
     * 还是真机"。只伪装配置过的 key，其他属性放行。
     */
    private static void applySystemProperties(Data cfg) {
        final Map<String, String> props = new HashMap<>();
        putProp(props, "ro.product.marketname", cfg.marketName);
        putProp(props, "ro.product.model", cfg.model);
        putProp(props, "ro.product.brand", cfg.manufacturer);
        putProp(props, "ro.product.manufacturer", cfg.manufacturer);
        putProp(props, "ro.product.name", cfg.product);
        putProp(props, "ro.product.device", cfg.device);
        putProp(props, "ro.product.board", cfg.board);
        putProp(props, "ro.hardware", cfg.board);
        putProp(props, "ro.build.id", cfg.buildId);
        putProp(props, "ro.build.display.id", cfg.buildId);
        if (!cfg.fingerprint.isEmpty()) {
            props.put("ro.build.tags", "release-keys");
        }
        putProp(props, "ro.build.version.release", cfg.release);
        if (cfg.sdkInt > 0) {
            props.put("ro.build.version.sdk", String.valueOf(cfg.sdkInt));
        }
        putProp(props, "ro.build.characteristics", cfg.characteristics);
        // 同一份属性表同步注册到 native 层（__system_property_get GOT hook）：
        // Java 层 hook 在分身体里可能因类加载器隔离挂不上，native 层兜底覆盖
        // getprop 命令、libcore syscall 等所有底层属性读取通路。
        try {
            NativeCore.clearPropRules();
            for (Map.Entry<String, String> entry : props.entrySet()) {
                NativeCore.addPropRule(entry.getKey(), entry.getValue());
            }
        } catch (Throwable ignored) {
        }
        if (props.isEmpty()) {
            return;
        }
        try {
            // 不用 ClassLoader.getSystemClassLoader()——分身体的 boot class loader
            // 和应用实际加载系统类的 class loader 可能不是同一个实例，hook 不到点子上。
            // Class.forName 走调用方（本类）的 class loader，与应用看到的类是同一个。
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            XposedBridge.hookAllMethods(clazz, "get", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 0) {
                        String value = props.get(param.args[0]);
                        if (value != null) {
                            param.setResult(value);
                        }
                    }
                }
            });
            // ro.build.version.sdk 是 int 属性，单独 hook getInt（Integer 不是 String）
            if (cfg.sdkInt > 0) {
                XposedBridge.hookAllMethods(clazz, "getInt", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length > 0 && "ro.build.version.sdk".equals(param.args[0])) {
                            param.setResult(cfg.sdkInt);
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putProp(Map<String, String> props, String key, String value) {
        if (value != null && !value.isEmpty()) {
            props.put(key, value);
        }
    }

    /**
     * Http User Agent 是进程启动时（RuntimeInit）用真实 Build 值拼好、缓存在
     * http.agent 系统属性里的字符串，改 Build 字段不会重算它，这里直接覆写。
     * 缺配置的段回退到真实值。
     */
    private static void applyUserAgent(Data cfg) {
        if (cfg.model.isEmpty() && cfg.release.isEmpty() && cfg.buildId.isEmpty()) {
            return;
        }
        String release = cfg.release.isEmpty() ? Build.VERSION.RELEASE : cfg.release;
        String model = cfg.model.isEmpty() ? Build.MODEL : cfg.model;
        String buildId = cfg.buildId.isEmpty() ? Build.ID : cfg.buildId;
        try {
            System.setProperty("http.agent",
                    "Dalvik/2.1.0 (Linux; U; Android " + release + "; " + model + " Build/" + buildId + ")");
        } catch (Throwable ignored) {
        }
    }

    private static void setBuildField(String field, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            XposedHelpers.setStaticObjectField(Build.class, field, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setBuildVersionField(String field, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            XposedHelpers.setStaticObjectField(Build.VERSION.class, field, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setStaticInt(String field, int value) {
        try {
            XposedHelpers.setStaticIntField(Build.VERSION.class, field, value);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 唯一标识

    private static void applyIdentifiers(Data cfg) {
        if (!cfg.deviceId.isEmpty()) {
            // Settings.Secure/System.getStringForUser(ContentResolver, String, int)，args[1] 即 name
            hookAndroidId(Settings.Secure.class, Settings.Secure.ANDROID_ID, cfg.deviceId);
            hookAndroidId(Settings.System.class, Settings.System.ANDROID_ID, cfg.deviceId);
        }
        if (!cfg.imei.isEmpty()) {
            hookReturn(TelephonyManager.class, "getImei", cfg.imei);
            hookReturn(TelephonyManager.class, "getDeviceId", cfg.imei);
        }
        if (!cfg.phoneNumber.isEmpty()) {
            hookReturn(TelephonyManager.class, "getLine1Number", cfg.phoneNumber);
        }
    }

    private static void hookAndroidId(Class<?> clazz, final String name, final String value) {
        try {
            XposedBridge.hookAllMethods(clazz, "getStringForUser", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length > 1 && name.equals(param.args[1])) {
                        param.setResult(value);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- SIM

    private static void applySim(Data cfg) {
        if (!cfg.simOperator.isEmpty()) {
            for (String method : new String[]{"getSimOperatorNumericForPhone",
                    "getNetworkOperatorForPhone", "getSimOperator", "getNetworkOperator"}) {
                hookReturn(TelephonyManager.class, method, cfg.simOperator);
            }
            hookCellIdentityMccMnc(cfg.simOperator);
        }
        if (!cfg.simOperatorName.isEmpty()) {
            for (String method : new String[]{"getSimOperatorName", "getSimOperatorNameForPhone",
                    "getNetworkOperatorName", "getNetworkOperatorNameForPhone"}) {
                hookReturn(TelephonyManager.class, method, cfg.simOperatorName);
            }
            hookReturn(SubscriptionInfo.class, "getCarrierName", cfg.simOperatorName);
            hookReturn(SubscriptionInfo.class, "getDisplayName", cfg.simOperatorName);
        }
        if (!cfg.simCountryIso.isEmpty()) {
            for (String method : new String[]{"getSimCountryIso", "getSimCountryIsoForPhone",
                    "getNetworkCountryIso", "getNetworkCountryIsoForPhone"}) {
                hookReturn(TelephonyManager.class, method, cfg.simCountryIso);
            }
            hookReturn(SubscriptionInfo.class, "getCountryIso", cfg.simCountryIso);
        }
    }

    /** SIM_OPERATOR 形如 46011：前 3 位 MCC、后 2~3 位 MNC，同步替换 CellIdentity* 的取数 */
    private static void hookCellIdentityMccMnc(String operator) {
        String mcc = operator.substring(0, 3);
        String mnc = operator.substring(3);
        final int mccInt = parseIntSafe(mcc);
        final int mncInt = parseIntSafe(mnc);
        for (String className : new String[]{"android.telephony.CellIdentity",
                "android.telephony.CellIdentityCdma",
                "android.telephony.CellIdentityGsm", "android.telephony.CellIdentityLte",
                "android.telephony.CellIdentityWcdma", "android.telephony.CellIdentityTdscdma",
                "android.telephony.CellIdentityNr"}) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, ClassLoader.getSystemClassLoader());
            if (clazz == null) {
                continue;
            }
            try {
                XposedBridge.hookAllMethods(clazz, "getMcc", XC_MethodReplacement.returnConstant(mccInt));
                XposedBridge.hookAllMethods(clazz, "getMnc", XC_MethodReplacement.returnConstant(mncInt));
                XposedBridge.hookAllMethods(clazz, "getMccString", XC_MethodReplacement.returnConstant(mcc));
                XposedBridge.hookAllMethods(clazz, "getMncString", XC_MethodReplacement.returnConstant(mnc));
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 通用 hook 工具

    /** 替换类上所有同名方法的返回值（静态/实例、所有重载） */
    private static void hookReturn(Class<?> clazz, String methodName, Object value) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, XC_MethodReplacement.returnConstant(value));
        } catch (Throwable ignored) {
        }
    }

    /** 按类名（可能不存在）hook 单个返回值方法 */
    private static void hookMethodReturn(String className, String methodName, Object value) {
        try {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, ClassLoader.getSystemClassLoader());
            if (clazz != null) {
                XposedBridge.hookAllMethods(clazz, methodName, XC_MethodReplacement.returnConstant(value));
            }
        } catch (Throwable ignored) {
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 配置数据

    /**
     * 设备伪装配置值。空串 = 不伪装该字段。
     */
    public static class Data {
        public String manufacturer = "";
        public String marketName = "";
        public String model = "";
        public String product = "";
        public String device = "";
        public String board = "";
        public String fingerprint = "";
        public String buildId = "";
        public int sdkInt = 0;
        public String release = "";
        public String characteristics = "";
        public String radioVersion = "";
        public String simOperator = "";
        public String simOperatorName = "";
        public String simCountryIso = "";
        public String imei = "";
        public String deviceId = "";
        public String phoneNumber = "";

        /** 已配置（非默认）的字段数，用于 UI 状态显示 */
        public int fieldCount() {
            int count = 0;
            if (!manufacturer.isEmpty()) count++;
            if (!marketName.isEmpty()) count++;
            if (!model.isEmpty()) count++;
            if (!product.isEmpty()) count++;
            if (!device.isEmpty()) count++;
            if (!board.isEmpty()) count++;
            if (!fingerprint.isEmpty()) count++;
            if (!buildId.isEmpty()) count++;
            if (sdkInt > 0) count++;
            if (!release.isEmpty()) count++;
            if (!characteristics.isEmpty()) count++;
            if (!radioVersion.isEmpty()) count++;
            if (!simOperator.isEmpty()) count++;
            if (!simOperatorName.isEmpty()) count++;
            if (!simCountryIso.isEmpty()) count++;
            if (!imei.isEmpty()) count++;
            if (!deviceId.isEmpty()) count++;
            if (!phoneNumber.isEmpty()) count++;
            return count;
        }

        /** 序列化为 key=value 行，只写非默认字段 */
        public String serialize() {
            StringBuilder sb = new StringBuilder();
            append(sb, "manufacturer", manufacturer);
            append(sb, "market_name", marketName);
            append(sb, "model", model);
            append(sb, "product", product);
            append(sb, "device", device);
            append(sb, "board", board);
            append(sb, "fingerprint", fingerprint);
            append(sb, "build_id", buildId);
            if (sdkInt > 0) sb.append("sdk_int=").append(sdkInt).append('\n');
            append(sb, "release", release);
            append(sb, "characteristics", characteristics);
            append(sb, "radio_version", radioVersion);
            append(sb, "sim_operator", simOperator);
            append(sb, "sim_operator_name", simOperatorName);
            append(sb, "sim_country_iso", simCountryIso);
            append(sb, "imei", imei);
            append(sb, "device_id", deviceId);
            append(sb, "phone_number", phoneNumber);
            return sb.toString();
        }

        private static void append(StringBuilder sb, String key, String value) {
            if (value != null && !value.isEmpty()) {
                sb.append(key).append('=').append(value).append('\n');
            }
        }

        /** 按 key 回填一个字段 */
        public void applyKey(String key, String value) {
            switch (key) {
                case "manufacturer":
                    manufacturer = value;
                    break;
                case "market_name":
                    marketName = value;
                    break;
                case "model":
                    model = value;
                    break;
                case "product":
                    product = value;
                    break;
                case "device":
                    device = value;
                    break;
                case "board":
                    board = value;
                    break;
                case "fingerprint":
                    fingerprint = value;
                    break;
                case "build_id":
                    buildId = value;
                    break;
                case "sdk_int":
                    sdkInt = parseIntSafe(value);
                    break;
                case "release":
                    release = value;
                    break;
                case "characteristics":
                    characteristics = value;
                    break;
                case "radio_version":
                    radioVersion = value;
                    break;
                case "sim_operator":
                    simOperator = value;
                    break;
                case "sim_operator_name":
                    simOperatorName = value;
                    break;
                case "sim_country_iso":
                    simCountryIso = value;
                    break;
                case "imei":
                    imei = value;
                    break;
                case "device_id":
                    deviceId = value;
                    break;
                case "phone_number":
                    phoneNumber = value;
                    break;
                default:
                    break;
            }
        }
    }
}
