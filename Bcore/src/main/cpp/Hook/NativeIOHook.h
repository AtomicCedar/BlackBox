//
// Created for Android 16 compat.
// 容器的路径重定向原来只覆盖两层：Java 层 OsStub 代理（libcore.io.Os）和
// java.io.UnixFileSystem 的 JNI 方法替换。native 代码（模块 .so、加固壳等）
// 直接调用 libc 的 fopen/open/mkdir 等会完全绕过重定向，落到真实路径后被
// Android 11+ 的沙盒拒绝（尤其 /storage/emulated/0/Android/data/<其他包>）。
//
// 这里通过 GOT/PLT hook 解决：遍历本进程加载的 /data/ 下的 ELF（应用自身
// 的库 + Xposed 模块的库），把 fopen/open/openat/mkdir/fstatat/access 等
// 导入表项（JUMP_SLOT / GLOB_DAT）改写为本文件的包装函数。包装函数对路径
// 参数做 IO::redirectPath 后再调真实函数。libc 内部调用与系统库不受影响。
//

#ifndef BLACKBOX_NATIVEIOHOOK_H
#define BLACKBOX_NATIVEIOHOOK_H

class NativeIOHook {
public:
    // 扫描当前已加载库并打补丁。可重复调用（新库加载后刷新），幂等。
    static void install();

    // /proc/*/maps 扩展规则：mode 0=删行（行内含 key 则丢弃），1=改行（行内
    // key 替换为 value）。规则表为进程级，Java 侧按应用在启动时序中配置。
    // 引擎 so 名单（libpine/libblackbox）始终生效，不受此表影响。
    static void addMapsRule(int mode, const char *key, const char *value);

    // 清空扩展规则表（每个应用进程启动时调用，避免跨应用残留）
    static void clearMapsRules();

    // 设备伪装属性：key -> value，命中 __system_property_get 时返回伪装值。
    // 进程级，Java 侧按应用在启动时序中注册。空表不拦截任何属性。
    static void addPropRule(const char *key, const char *value);

    // 清空设备伪装属性表（每个应用进程启动时调用，避免跨应用残留）
    static void clearPropRules();

    // libxposed 102 native 模块的 hook_func：按 func 真实地址反查导出符号名，
    // 对 /data 下所有库（含后续加载的）改写 GOT 指向 replace，backup 带回真实
    // 函数地址。仅 arm64 实现（依赖 GOT 补丁通路），其他架构返回 false。
    static bool addNamedHook(const void *func, void *replace, void **backup);

    // hook_func 的逆操作：按 func 反查符号，恢复被改写的 GOT。仅 arm64 实现。
    static bool removeNamedHook(const void *func);
};

#endif //BLACKBOX_NATIVEIOHOOK_H
