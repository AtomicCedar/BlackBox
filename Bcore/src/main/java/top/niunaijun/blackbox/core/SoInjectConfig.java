package top.niunaijun.blackbox.core;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * 按应用注入 so：把用户选择的 so 复制进分身体的 lib 目录（应用 ClassLoader
 * 命名空间可达），分身进程启动时逐个按名加载，so 的构造函数与 JNI_OnLoad
 * 随加载自动执行。启用标记 blackbox/hotfix/so_inject/u&lt;userId&gt;/&lt;packageName&gt;
 * 存在 = 启用；so 列表在同目录 &lt;packageName&gt;.list（每行一个 so 文件名），
 * 禁用只删启用标记、保留列表，重新启用列表还在。加载不依赖 Xposed 模块框架。
 */
public class SoInjectConfig {

    /** 注入 so 名后缀：libxxxx.so → libxxxx_inject.so（.so 前加 _inject） */
    private static final String INJECT_SUFFIX = "_inject";

    /** maps 隐藏规则关键字：含该关键字的行整行删除（注入 so 统一 *_inject.so 命名） */
    private static final String INJECTED_SO_MAPS_KEY = "_inject.so";

    public static boolean isEnabled(int userId, String packageName) {
        try {
            return BEnvironment.getSoInjectFile(userId, packageName).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 该应用配置的注入 so 文件名列表（*_inject.so 后缀命名，含 .so 后缀）。
     */
    public static List<String> getSoFiles(int userId, String packageName) {
        List<String> soFiles = new ArrayList<>();
        try {
            File file = BEnvironment.getSoInjectListFile(userId, packageName);
            if (!file.exists()) {
                return soFiles;
            }
            for (String line : FileUtils.readToString(file.getAbsolutePath()).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                soFiles.add(trimmed);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return soFiles;
    }

    /**
     * 设置启用状态：启用 = 建标记（列表保留），禁用 = 删标记（不删列表与已复制的 so）。
     */
    public static void setEnabled(int userId, String packageName, boolean enabled) {
        try {
            File file = BEnvironment.getSoInjectFile(userId, packageName);
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
     * 添加一个 so：从 uri 复制到分身体 lib 目录（*_inject.so 命名），列表追加一行，
     * 并确保启用标记存在。返回 null 表示成功，否则返回错误消息。
     */
    public static String addSoFile(int userId, String packageName, Uri uri) {
        try {
            // loadLibrary 按 lib<name>.so 约定搜索：目标名 = lib + <原名去 lib 前缀与
            // .so> + _inject.so（libhook.so → libhook_inject.so；不带 lib 的 hook.so
            // → libhook_inject.so，保证 lib 开头才能被 loadLibrary 找到）
            String displayName = queryDisplayName(uri);
            if (displayName == null || !displayName.endsWith(".so")) {
                return "not a .so file";
            }
            String stem = displayName.substring(0, displayName.length() - 3);
            if (stem.startsWith("lib")) {
                stem = stem.substring(3);
            }
            String targetName = "lib" + stem + INJECT_SUFFIX + ".so";
            File libDir = BEnvironment.getAppLibDir(packageName);
            FileUtils.mkdirs(libDir.getAbsolutePath());
            File target = new File(libDir, targetName);
            // 覆盖写前恢复写位（上一次落盘时已置只读），否则 FileOutputStream 打不开
            if (target.exists()) {
                target.setWritable(true);
            }
            InputStream input = BlackBoxCore.getContext().getContentResolver().openInputStream(uri);
            if (input == null) {
                return "cannot open file";
            }
            try {
                OutputStream output = new FileOutputStream(target);
                try {
                    copyTo(input, output);
                } finally {
                    output.close();
                }
            } finally {
                input.close();
            }
            // Android 16/17 拒绝从可写路径加载动态库（Writable dex / writable file 校验），
            // 与 CopyExecutor 对 base.apk 置只读同一处理：注入 so 必须去掉写位才能通过加载校验
            target.setReadOnly();
            File listFile = BEnvironment.getSoInjectListFile(userId, packageName);
            FileUtils.mkdirs(listFile.getParentFile().getAbsolutePath());
            FileUtils.writeToFile(appendLine(loadList(userId, packageName), targetName), listFile);
            setEnabled(userId, packageName, true);
            return null;
        } catch (Throwable t) {
            return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        }
    }

    /**
     * 移除一个注入的 so：删列表行 + 删分身体 lib 目录里的文件。
     */
    public static void removeSoFile(int userId, String packageName, String soName) {
        try {
            List<String> rest = new ArrayList<>();
            for (String line : getSoFiles(userId, packageName)) {
                if (!line.equals(soName)) {
                    rest.add(line);
                }
            }
            File listFile = BEnvironment.getSoInjectListFile(userId, packageName);
            FileUtils.mkdirs(listFile.getParentFile().getAbsolutePath());
            FileUtils.writeToFile(joinLines(rest), listFile);
            new File(BEnvironment.getAppLibDir(packageName), soName).delete();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * 分身进程启动时调用：按配置逐个把注入的 so 内存加载（MemLoader 绕过系统
     * linker，匿名内存映射，maps 无文件路径），so 的 .init_array 构造与
     * JNI_OnLoad 随加载自动执行。DT_NEEDED 依赖库由加载器走系统 dlopen。
     * 内存加载失败时兜底走应用 ClassLoader 加载（磁盘可见但功能可用）。
     */
    public static void inject(ClassLoader appClassLoader, int userId, String packageName) {
        for (String soName : getSoFiles(userId, packageName)) {
            try {
                // 文件名为 lib*_inject.so，loadLibrary 入参 = 去掉 lib 前缀与 .so 后缀
                String libName = soName.startsWith("lib")
                        ? soName.substring(3, soName.length() - 3)
                        : soName.substring(0, soName.length() - 3);
                String soPath = new File(BEnvironment.getAppLibDir(packageName), soName).getAbsolutePath();
                boolean memOk = false;
                try {
                    memOk = NativeCore.memLoadSo(soPath);
                } catch (Throwable ignored) {
                }
                if (memOk) {
                    Slog.i("SoInject", "mem-injected " + soName + " for " + packageName);
                } else {
                    // 兜底：MemLoader 失败（so 结构/符号过于复杂）退回系统加载，
                    // 至少功能可用；磁盘与 maps 仍按旧路径处理
                    loadLibraryVia(appClassLoader, libName);
                    Slog.i("SoInject", "fallback loaded " + soName + " for " + packageName);
                }
                // 注入 so 的 maps 隐藏：注册删行规则（含 _inject.so 的行整行隐藏）+ 重扫 GOT
                // 把注入 so 自身纳入容器 IO 伪装（否则它调 open 读 maps 是真实内容，
                // 既是检测口也是自检盲区）。内存映像的匿名 r-xp 页由 maps 行伪装
                // 兜底补回路径（liblog.so），不暴露。
                NativeCore.addMapsRule(0, INJECTED_SO_MAPS_KEY, null);
                NativeCore.rescanIOHook();
            } catch (Throwable t) {
                Slog.e("SoInject", "inject failed " + soName + " for " + packageName, t);
            }
        }
    }

    /**
     * 兜底加载：ClassLoader.loadLibrary(String) 是 protected 公开 API，但实测
     * Android 15/16 的 ClassLoader 上不存在（各版本对 so 加载的封装方法名/宿主
     * 类都在变）。枚举适配：先试 ClassLoader.loadLibrary(String)，再扫 Runtime 的
     * loadLibrary*(ClassLoader, String)（loadLibrary0/1 签名随 AOSP 版本变化）。
     * 目的都是让加载走"应用 ClassLoader 的命名空间"——容器数据目录的 so 只有
     * 应用命名空间可达，与 Xposed 模块经 ModuleClassLoader 加载同一原理。
     */
    private static void loadLibraryVia(ClassLoader loader, String libName) throws Exception {
        try {
            Method loadLibrary = ClassLoader.class.getDeclaredMethod("loadLibrary", String.class);
            loadLibrary.setAccessible(true);
            loadLibrary.invoke(loader, libName);
            return;
        } catch (NoSuchMethodException ignored) {
        }
        for (Method m : Runtime.class.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (m.getName().startsWith("loadLibrary") && params.length == 2
                    && params[0] == ClassLoader.class && params[1] == String.class) {
                m.setAccessible(true);
                m.invoke(Runtime.getRuntime(), loader, libName);
                return;
            }
        }
        throw new NoSuchMethodException("no so loader found for this Android version");
    }

    private static String loadList(int userId, String packageName) {
        try {
            File file = BEnvironment.getSoInjectListFile(userId, packageName);
            return file.exists() ? FileUtils.readToString(file.getAbsolutePath()) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] appendLine(String content, String line) {
        String merged = content.trim().isEmpty() ? line : content.trim() + "\n" + line;
        return merged.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void copyTo(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
    }

    private static String queryDisplayName(Uri uri) {
        try {
            Cursor cursor = BlackBoxCore.getContext().getContentResolver()
                    .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        String name = cursor.getString(0);
                        if (name != null) {
                            int slash = name.lastIndexOf('/');
                            return slash >= 0 ? name.substring(slash + 1) : name;
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
