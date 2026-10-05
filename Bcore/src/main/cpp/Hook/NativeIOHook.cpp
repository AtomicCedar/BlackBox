//
// NativeIOHook 实现。
//
// 方案：通过 dl_iterate_phdr 枚举本进程加载的 ELF（应用自身的库 + Xposed
// 模块的库 + art apex 的 libart/libnativeloader），遍历其 .dynamic ->
// .rela.plt / .rela.dyn，命中导入符号表后改写 GOT 表项。相比 inline hook
// libc 无需做指令重定位，安全性高得多。
//
// 并发说明：dl_iterate_phdr 的回调全程持有 linker 锁，期间不可能有并发的
// dlopen/dlclose 改动映射，库列表与内存内容都是稳定快照；且 linker 只会
// 列出已完成加载的库，不会撞上加载到一半的半映射状态。
//

#include "NativeIOHook.h"

#include <cctype>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <android/dlext.h>
#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <limits.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "../IO.h"
#include "../Log.h"
#include "LibXposedNative.h"

#include <errno.h>

#if defined(__aarch64__)

namespace {

// 诊断：被补丁库的路径类调用拿到 EACCES 时打印路径，便于定位漏挂/漏重定向
inline void logDenied(const char *symbol, const char *path) {
    if (path != nullptr) {
        ALOGD("NativeIOHook: %s EACCES for %s", symbol, path);
    }
}

// ---------------------------------------------------------------------------
// /proc/*/maps 行伪装：引擎 so（libpine/libblackbox）始终删行；扩展规则表
// 支持删行/改行（Java 侧按应用配置，进程级）。检测方读 maps 找 hook 引擎时
// 命中名单的行直接丢弃或改写，其余原样输出到匿名内存 fd（memfd）。原理参考
// RatelModule Foundation/MapsRedirector 与 FakeXposed IoRedirect。
// ---------------------------------------------------------------------------
// real_* 指针定义在下方"真实函数指针与包装函数"区，此处 extern 前向声明
extern int (*real_open)(const char *, int, ...);
extern int (*real_openat)(int, const char *, int, ...);

static const char *const kHiddenMapsSo[] = {
    "libpine.so",
    "libblackbox.so",
};

static bool isHiddenMapsLine(const char *line) {
    for (const char *const name : kHiddenMapsSo) {
        if (strstr(line, name) != nullptr) {
            return true;
        }
    }
    return false;
}

// 扩展 maps 规则：mode 0=删行（行内含 key 则丢弃），1=改行（行内 key 替换为 value）
struct MapsRule {
    int mode;
    std::string key;
    std::string value;
};
static std::vector<MapsRule> g_mapsRules;
static std::mutex g_mapsRulesMutex;

// ---------------------------------------------------------------------------
// 设备伪装属性表：key -> value，Java 侧 DeviceSpoofConfig 通过 JNI 注册，
// __system_property_get / __system_property_find 命中时返回伪装值。
// 覆盖 Java 层 SystemProperties.get hook 没生效的通路（native 直读属性）。
// ---------------------------------------------------------------------------
struct PropRule {
    std::string key;
    std::string value;
};
static std::vector<PropRule> g_propRules;
static std::mutex g_propRulesMutex;

static const char *lookupProp(const char *name) {
    if (name == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(g_propRulesMutex);
    for (const auto &rule : g_propRules) {
        if (rule.key == name) return rule.value.c_str();
    }
    return nullptr;
}

// 处理一行 maps：返回 true=保留（out 为处理后行内容），false=整行丢弃。
// 删行规则匹配原始行；改行规则在 out 上按规则顺序累积替换。
static bool applyMapsRules(const char *line, std::string &out) {
    out = line;
    std::lock_guard<std::mutex> lock(g_mapsRulesMutex);
    for (const auto &rule : g_mapsRules) {
        if (rule.mode == 0) {
            if (strstr(line, rule.key.c_str()) != nullptr) {
                return false;
            }
        } else if (rule.mode == 1 && !rule.key.empty()) {
            size_t pos = out.find(rule.key);
            if (pos != std::string::npos) {
                out.replace(pos, rule.key.size(), rule.value);
            }
        }
    }
    return true;
}

static bool isProcMapsPath(const char *path) {
    if (path == nullptr || strncmp(path, "/proc/", 6) != 0) {
        return false;
    }
    size_t len = strlen(path);
    return (len >= 5 && strcmp(path + len - 5, "/maps") == 0) ||
           (len >= 6 && strcmp(path + len - 6, "/smaps") == 0);
}

// 读真实 maps 生成过滤副本（memfd）。返回 fake fd（>=0）；memfd 不可用时返回原 fd
static int createFakeMaps(int realFd) {
#ifdef __NR_memfd_create
    int fakeFd = static_cast<int>(syscall(__NR_memfd_create, "blackbox_maps", 1 /*MFD_CLOEXEC*/));
#else
    int fakeFd = -1;
#endif
    if (fakeFd < 0) {
        return realFd; // memfd 不可用：返回原样，宁可不藏也不破坏调用
    }
    char line[PATH_MAX];
    char *p = line;
    size_t n = PATH_MAX - 1;
    ssize_t r;
    while ((r = TEMP_FAILURE_RETRY(read(realFd, p, n))) > 0) {
        p[r] = '\0';
        p = line;
        char *e;
        while ((e = strchr(p, '\n')) != nullptr) {
            e[0] = '\0';
            if (isHiddenMapsLine(p)) {
                p = e + 1; // 引擎 so 行：丢弃
                continue;
            }
            std::string out;
            if (applyMapsRules(p, out)) {
                e[0] = '\n';
                write(fakeFd, out.data(), out.size());
                write(fakeFd, "\n", 1);
            }
            p = e + 1;
        }
        const size_t remain = strlen(p);
        if (remain <= PATH_MAX / 2) {
            memcpy(line, p, remain);
        } else {
            memmove(line, p, remain);
        }
        p = line + remain;
        n = PATH_MAX - 1 - remain;
    }
    lseek(fakeFd, 0, SEEK_SET);
    return fakeFd;
}

// 返回值：0=非 maps 路径（走正常逻辑）；>0=替换后的 fd；<0=打开失败
static int redirectProcMaps(const char *path, int flags, int mode) {
    if (!isProcMapsPath(path) || (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND))) {
        return 0;
    }
    int realFd = real_open(path, flags, mode);
    if (realFd < 0) {
        return -1;
    }
    int fakeFd = createFakeMaps(realFd);
    if (fakeFd != realFd) {
        close(realFd); // 使用副本，释放真实 fd
    }
    return fakeFd;
}

static int redirectProcMapsAt(int dirfd, const char *path, int flags, int mode) {
    if (dirfd != AT_FDCWD || !isProcMapsPath(path) ||
        (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND))) {
        return 0;
    }
    int realFd = real_openat(dirfd, path, flags, mode);
    if (realFd < 0) {
        return -1;
    }
    int fakeFd = createFakeMaps(realFd);
    if (fakeFd != realFd) {
        close(realFd);
    }
    return fakeFd;
}

// ---------------------------------------------------------------------------
// 容器数据目录隐藏：分身体直接访问宿主数据目录时把 blackbox/（容器数据根，
// 含分身/模块 APK）伪装成不存在。检测方扫宿主数据目录找容器痕迹时全为 ENOENT。
// 分身体正常 IO 走虚拟路径重定向（原始路径是 /data/data/<分身pkg> 等，不含这些
// 前缀），不受影响；黑盒内部加载 APK/模块走 libjavacore（不经 GOT），同样不受影响。
// ---------------------------------------------------------------------------
static bool isContainerDataPath(const char *path) {
    if (path == nullptr) {
        return false;
    }
    static const char *const kHostDataRoots[] = {
        "/data/data/top.niunaijun.blackbox/",
        "/data/user/0/top.niunaijun.blackbox/",
        "/data/user_de/0/top.niunaijun.blackbox/",
    };
    for (const char *root : kHostDataRoots) {
        const size_t len = strlen(root);
        if (strncmp(path, root, len) == 0) {
            return strncmp(path + len, "blackbox", 8) == 0;
        }
    }
    return false;
}

// 返回 0=放行；-1=命中容器数据目录（调用方直接返回 -1, errno 已置 ENOENT）
static int denyIfContainerDataPath(const char *path) {
    if (isContainerDataPath(path)) {
        errno = ENOENT;
        return -1;
    }
    return 0;
}

// ---------------------------------------------------------------------------
// 真实函数指针与包装函数
// ---------------------------------------------------------------------------

int (*real_open)(const char *, int, ...);
int (*real_openat)(int, const char *, int, ...);
int (*real___open_2)(const char *, int);
int (*real___openat_2)(int, const char *, int);
FILE *(*real_fopen)(const char *, const char *);
int (*real_mkdir)(const char *, mode_t);
int (*real_mkdirat)(int, const char *, mode_t);
int (*real_access)(const char *, int);
int (*real_faccessat)(int, const char *, int, int);
int (*real_fstatat)(int, const char *, struct stat *, int);
int (*real_fstatat64)(int, const char *, struct stat64 *, int);
int (*real_unlink)(const char *);
int (*real_unlinkat)(int, const char *, int);
int (*real_rename)(const char *, const char *);
int (*real_renameat)(int, const char *, int, const char *);
int (*real_rmdir)(const char *);
int (*real_remove)(const char *);
int (*real_chmod)(const char *, mode_t);
int (*real_truncate)(const char *, off_t);
DIR *(*real_opendir)(const char *);
void *(*real_dlopen)(const char *, int);
void *(*real_android_dlopen_ext)(const char *, int, const android_dlextinfo *);
// linker 导出的显式 caller 版本。libdl 的 dlopen/android_dlopen_ext 用返回地址
// 当调用方来选 linker 命名空间；GOT 包装把这一跳变成 libblackbox（宿主命名
// 空间），应用 so 里发起的 dlopen 就会在错误的命名空间里解析（表现为
// dlopen failed: library "xxx.so" not found）。这里直接把真实调用方传回去。
void *(*real_loader_dlopen)(const char *, int, const void *);
void *(*real_loader_android_dlopen_ext)(const char *, int, const android_dlextinfo *, const void *);
int (*real_open64)(const char *, int, ...);
int (*real_openat64)(int, const char *, int, ...);
int (*real_stat)(const char *, struct stat *);
int (*real_lstat)(const char *, struct stat *);
int (*real_stat64)(const char *, struct stat64 *);
int (*real_fstat)(int, struct stat *);
int (*real_fstat64)(int, struct stat64 *);
int (*real_faccessat2)(int, const char *, int, int);

// 系统属性读取：__system_property_get 是 native 侧 SystemProperties.get 的底层实现，
// Java 层 hook 挂不上时用 GOT 包装兜底。__system_property_find 返回的 prop_info
// 里名字和值都带偏移，找到后直接按伪装值覆写。
int (*real___system_property_get)(const char *, char *);

int my___system_property_get(const char *name, char *value) {
    const char *spoof = lookupProp(name);
    if (spoof != nullptr) {
        int len = static_cast<int>(strlen(spoof));
        if (len >= PROP_VALUE_MAX) len = PROP_VALUE_MAX - 1;
        memcpy(value, spoof, len + 1);
        return len;
    }
    return real___system_property_get(name, value);
}

// ---------------------------------------------------------------------------
// dl_iterate_phdr 模块路径伪装：多开检测常用 dl_iterate_phdr 遍历已加载 so，
// 容器分身体的模块路径形如 /data/user/0/<宿主>/blackbox/data/app/<pkg>/lib/...，
// "/blackbox/" 是容器 virtual 目录的暴露特征。包装回调里把模块名还原成
// /data/app/<pkg>/lib/... 的正常安装形态，隐藏容器目录特征。
// 容器自身 install 扫库走 libblackbox 自身 GOT（未被改写），不经过本包装。
// ---------------------------------------------------------------------------
int (*real_dl_iterate_phdr)(int (*callback)(struct dl_phdr_info *, size_t, void *),
                            void *data);

struct DlIterateContext {
    int (*userCallback)(struct dl_phdr_info *, size_t, void *);
    void *userData;
};

// ---------------------------------------------------------------------------
// 虚拟路径双向映射：容器分身真实路径（/data/user/0/黑盒/blackbox/...）与其
// 伪装路径（应用视角 / 正常安装形态）的登记表。dl_iterate_phdr / readlink
// 返回伪装路径隐藏特征；拿伪装路径再 open 时反查还原成真实路径，
// 消除"拿伪装路径打开失败"的副作用（正常引擎/框架拿路径定位文件不受影响）。
// 登记来源：1) 模块路径（install 扫描 dlpi_name 含 /blackbox/）
//           2) open 重定向命中（数据文件真实↔应用视角）
// ---------------------------------------------------------------------------
static std::unordered_map<std::string, std::string> g_hiddenPathMap;  // 真实 -> 伪装
static std::mutex g_hiddenPathMutex;

static void registerHiddenPath(const char *real, const char *masked) {
    if (real == nullptr || masked == nullptr || real[0] == '\0' || masked[0] == '\0') {
        return;
    }
    std::lock_guard<std::mutex> lock(g_hiddenPathMutex);
    g_hiddenPathMap[real] = masked;
}

// 真实 -> 伪装（返回 map 内引用，仅插入不删除，指针稳定）
static const char *lookupMasked(const char *real) {
    if (real == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_hiddenPathMutex);
    auto it = g_hiddenPathMap.find(std::string(real));
    return it == g_hiddenPathMap.end() ? nullptr : it->second.c_str();
}

// 伪装 -> 真实（反查，条目少直接线性找）
static const char *lookupReal(const char *masked) {
    if (masked == nullptr) {
        return nullptr;
    }
    std::string m(masked);
    std::lock_guard<std::mutex> lock(g_hiddenPathMutex);
    for (const auto &kv : g_hiddenPathMap) {
        if (kv.second == m) {
            return kv.first.c_str();
        }
    }
    return nullptr;
}

// 返回原始路径的子串指针（指向 /data/app/<pkg>/... 段），无需拷贝——
// dlpi_name 指向 soinfo 内只读内存，整个进程生命周期有效
static const char *maskModulePath(const char *path) {
    if (path == nullptr) {
        return path;
    }
    const char *blackbox = strstr(path, "/blackbox/");
    if (blackbox == nullptr) {
        return path;
    }
    const char *dataApp = strstr(blackbox, "/data/app/");
    if (dataApp != nullptr) {
        return dataApp;
    }
    return blackbox;
}

// 伪装 + 登记：含 /blackbox/ 的路径返回伪装形式并登记双向映射
static const char *maskAndRegister(const char *path) {
    const char *masked = maskModulePath(path);
    if (masked != path) {
        registerHiddenPath(path, masked);
    }
    return masked;
}

static int dlIterateCallback(struct dl_phdr_info *info, size_t size, void *data) {
    auto *ctx = static_cast<DlIterateContext *>(data);
    if (ctx->userCallback == nullptr) {
        return 0;
    }
    struct dl_phdr_info masked = *info;
    if (info->dlpi_name != nullptr) {
        const char *m = lookupMasked(info->dlpi_name);
        if (m == nullptr && strstr(info->dlpi_name, "/blackbox/") != nullptr) {
            m = maskAndRegister(info->dlpi_name);
        }
        masked.dlpi_name = (m != nullptr) ? m : info->dlpi_name;
    }
    return ctx->userCallback(&masked, size, ctx->userData);
}

int my_dl_iterate_phdr(int (*callback)(struct dl_phdr_info *, size_t, void *),
                       void *data) {
    DlIterateContext ctx{callback, data};
    return real_dl_iterate_phdr(dlIterateCallback, &ctx);
}

// ---------------------------------------------------------------------------
// readlink / readlinkat 路径反查伪装：多开检测遍历 /proc/self/fd/N 拿实际
// 打开路径（重定向后是黑盒目录）。真实 readlink 拿路径后按 IO 规则反向
// 替换成应用视角路径（/data/user/0/黑盒/blackbox/... → /data/user/0/包名/...）。
// readlink 返回不带 \0 的原始字节，先按长度拷进 std::string 再加 \0 处理。
// ---------------------------------------------------------------------------
ssize_t (*real_readlink)(const char *, char *, size_t);
ssize_t (*real_readlinkat)(int, const char *, char *, size_t);

static ssize_t rewriteFdPath(char *buf, ssize_t len, size_t bufsiz) {
    if (len <= 0 || bufsiz == 0) {
        return len;
    }
    std::string actual(buf, static_cast<size_t>(len));
    const char *m = lookupMasked(actual.c_str());
    if (m == nullptr) {
        if (actual.find("/blackbox/") != std::string::npos) {
            m = maskAndRegister(actual.c_str());
        }
    }
    if (m == nullptr) {
        return len;
    }
    size_t rl = strlen(m);
    if (rl > bufsiz) {
        rl = bufsiz;
    }
    memcpy(buf, m, rl);
    return static_cast<ssize_t>(rl);
}

ssize_t my_readlink(const char *path, char *buf, size_t bufsiz) {
    return rewriteFdPath(buf, real_readlink(path, buf, bufsiz), bufsiz);
}

ssize_t my_readlinkat(int dirfd, const char *path, char *buf, size_t bufsiz) {
    return rewriteFdPath(buf, real_readlinkat(dirfd, path, buf, bufsiz), bufsiz);
}

// redirectPath 命中规则时返回 malloc 的新串，否则原样返回入参
inline const char *tryRedirect(const char *path) {
    if (path == nullptr) return nullptr;
    return IO::redirectPath(path);
}

inline void releaseRedirect(const char *orig, const char *redirected) {
    if (redirected != nullptr && redirected != orig) {
        free(const_cast<char *>(redirected));
    }
}

int my_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    // 伪装路径反查：readlink / dl_iterate_phdr 伪装过的路径，还原成真实路径
    // 打开——消除"拿伪装路径 open 失败"的副作用
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        int r = real_open(unmasked, flags, mode);
        if (r != 0 && errno == EACCES) logDenied("open", path);
        return r;
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    // /proc/*/maps 行伪装：隐藏引擎 so
    int mapsFd = redirectProcMaps(path, flags, mode);
    if (mapsFd != 0) {
        return mapsFd;
    }
    const char *redir = tryRedirect(path);
    // 重定向命中：登记"实际路径 ↔ 应用视角路径"，供 readlink 反查伪装
    if (redir != path && redir != nullptr) {
        registerHiddenPath(redir, path);
    }
    int r = real_open(redir, flags, mode);
    if (r != 0 && errno == EACCES) logDenied("open", path);
    releaseRedirect(path, redir);
    return r;
}

int my_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_openat(dirfd, path, flags, mode);
    }
    // 伪装路径反查（绝对路径形态）
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        int r = real_openat(dirfd, unmasked, flags, mode);
        if (r != 0 && errno == EACCES) logDenied("openat", path);
        return r;
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    // /proc/*/maps 行伪装
    int mapsFd = redirectProcMapsAt(dirfd, path, flags, mode);
    if (mapsFd != 0) {
        return mapsFd;
    }
    const char *redir = tryRedirect(path);
    // 重定向命中：登记"实际路径 ↔ 应用视角路径"，供 readlink 反查伪装
    if (redir != path && redir != nullptr) {
        registerHiddenPath(redir, path);
    }
    int r = real_openat(dirfd, redir, flags, mode);
    if (r != 0 && errno == EACCES) logDenied("openat", path);
    releaseRedirect(path, redir);
    return r;
}

int my___open_2(const char *path, int flags) {
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    // /proc/*/maps 行伪装
    int mapsFd = redirectProcMaps(path, flags, 0);
    if (mapsFd != 0) {
        return mapsFd;
    }
    const char *redir = tryRedirect(path);
    int r = real___open_2(redir, flags);
    if (r != 0 && errno == EACCES) logDenied("__open_2", path);
    releaseRedirect(path, redir);
    return r;
}

int my___openat_2(int dirfd, const char *path, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real___openat_2(dirfd, path, flags);
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    // /proc/*/maps 行伪装
    int mapsFd = redirectProcMapsAt(dirfd, path, flags, 0);
    if (mapsFd != 0) {
        return mapsFd;
    }
    const char *redir = tryRedirect(path);
    int r = real___openat_2(dirfd, redir, flags);
    if (r != 0 && errno == EACCES) logDenied("__openat_2", path);
    releaseRedirect(path, redir);
    return r;
}

FILE *my_fopen(const char *path, const char *mode) {
    if (denyIfContainerDataPath(path) != 0) {
        return nullptr;
    }
    if (mode != nullptr && mode[0] == 'r' &&
        (mode[1] == '\0' || (mode[1] == 'b' && mode[2] == '\0'))) {
        // /proc/*/maps 行伪装（只处理读模式）
        int mapsFd = redirectProcMaps(path, O_RDONLY, 0);
        if (mapsFd != 0) {
            return mapsFd < 0 ? nullptr : fdopen(mapsFd, mode);
        }
    }
    const char *redir = tryRedirect(path);
    FILE *f = real_fopen(redir, mode);
    if (f == nullptr && errno == EACCES) logDenied("fopen", path);
    releaseRedirect(path, redir);
    return f;
}

int my_mkdir(const char *path, mode_t mode) {
    const char *redir = tryRedirect(path);
    int r = real_mkdir(redir, mode);
    if (r != 0 && errno == EACCES) logDenied("mkdir", path);
    releaseRedirect(path, redir);
    return r;
}

int my_mkdirat(int dirfd, const char *path, mode_t mode) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_mkdirat(dirfd, path, mode);
    }
    const char *redir = tryRedirect(path);
    int r = real_mkdirat(dirfd, redir, mode);
    if (r != 0 && errno == EACCES) logDenied("mkdirat", path);
    releaseRedirect(path, redir);
    return r;
}

// ---------------------------------------------------------------------------
// 黑盒目录树的"祖先目录"权限伪装：多开检测常沿 dataDir 逐级 access 父目录，
// 黑盒祖先真实可读（宿主 uid 拥有整棵 blackbox 树），伪装成 EACCES 模拟正常
// 应用的权限隔离。判据：路径停在 /blackbox/ 后的中间层（其后无 '/'，未落到
// 具体 data/user/<uid>/<pkg> 或 data/app/<pkg> 层）且是权限检查（非 F_OK）。
// 还要拦"宿主 data 目录本身"（/data/user/0/<宿主>）——检测方一路剥到最后
// 一级暴露它（分身体 uid == 宿主 uid，真实可读），这是 GOT 无法覆盖的物理
// 事实，只能靠显式拦截。
// ---------------------------------------------------------------------------
// 宿主 data 目录（/data/user/0/<宿主>），首次遇到含 /blackbox 的路径时提取
static char g_host_data_dir[128];

static void extractHostDataDir(const char *path) {
    if (g_host_data_dir[0] != '\0' || path == nullptr) {
        return;
    }
    const char *bb = strstr(path, "/blackbox");
    if (bb == nullptr || bb == path) {
        return;
    }
    size_t len = static_cast<size_t>(bb - path);
    if (len >= sizeof(g_host_data_dir)) {
        len = sizeof(g_host_data_dir) - 1;
    }
    memcpy(g_host_data_dir, path, len);
    g_host_data_dir[len] = '\0';
}

static bool isBlackboxAncestorDir(const char *path) {
    if (path == nullptr) {
        return false;
    }
    // 宿主 data 目录本身（/data/user/0/<宿主>）——检测方剥到最深层的主命中点
    if (g_host_data_dir[0] != '\0' && strcmp(path, g_host_data_dir) == 0) {
        return true;
    }
    const char *blackbox = strstr(path, "/blackbox");
    if (blackbox == nullptr) {
        return false;
    }
    extractHostDataDir(path);
    const char *tail = blackbox + strlen("/blackbox");
    if (*tail == '\0') {
        return true;   // 路径恰好是 .../blackbox（无尾斜杠）→ 黑盒根 → 拦
    }
    if (*tail != '/') {
        return false;  // 非目录边界（blackbox_xxx）→ 放行
    }
    // .../blackbox/xxx：中间层（其后无 '/'）→ 拦；具体数据层 → 放行
    return strchr(tail + 1, '/') == nullptr;
}

// 黑盒祖先目录的 access 权限检查一律 EACCES（模拟正常权限隔离）
static bool denyBlackboxAncestorAccess(const char *path, int mode) {
    if ((mode & (R_OK | W_OK | X_OK)) == 0) {
        return false;  // F_OK 存在性检查放行
    }
    return isBlackboxAncestorDir(path);
}

int my_access(const char *path, int mode) {
    if (denyBlackboxAncestorAccess(path, mode)) {
        errno = EACCES;
        return -1;
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_access(redir, mode);
    if (r != 0 && errno == EACCES) logDenied("access", path);
    releaseRedirect(path, redir);
    return r;
}

int my_faccessat(int dirfd, const char *path, int mode, int flag) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_faccessat(dirfd, path, mode, flag);
    }
    if (denyBlackboxAncestorAccess(path, mode)) {
        errno = EACCES;
        return -1;
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_faccessat(dirfd, redir, mode, flag);
    if (r != 0 && errno == EACCES) logDenied("faccessat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_fstatat(dirfd, path, buf, flags);
    }
    // 伪装路径反查
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        return real_fstatat(dirfd, unmasked, buf, flags);
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_fstatat(dirfd, redir, buf, flags);
    if (r != 0 && errno == EACCES) logDenied("fstatat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_fstatat64(int dirfd, const char *path, struct stat64 *buf, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_fstatat64(dirfd, path, buf, flags);
    }
    // 伪装路径反查
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        return real_fstatat64(dirfd, unmasked, buf, flags);
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_fstatat64(dirfd, redir, buf, flags);
    if (r != 0 && errno == EACCES) logDenied("fstatat64", path);
    releaseRedirect(path, redir);
    return r;
}

int my_unlink(const char *path) {
    const char *redir = tryRedirect(path);
    int r = real_unlink(redir);
    releaseRedirect(path, redir);
    return r;
}

int my_unlinkat(int dirfd, const char *path, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_unlinkat(dirfd, path, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real_unlinkat(dirfd, redir, flags);
    releaseRedirect(path, redir);
    return r;
}

int my_rename(const char *oldp, const char *newp) {
    const char *ro = tryRedirect(oldp);
    const char *rn = tryRedirect(newp);
    int r = real_rename(ro, rn);
    releaseRedirect(oldp, ro);
    releaseRedirect(newp, rn);
    return r;
}

int my_renameat(int olddirfd, const char *oldp, int newdirfd, const char *newp) {
    if (olddirfd == AT_FDCWD || newdirfd == AT_FDCWD) {
        const char *ro = olddirfd == AT_FDCWD ? tryRedirect(oldp) : oldp;
        const char *rn = newdirfd == AT_FDCWD ? tryRedirect(newp) : newp;
        int r = real_renameat(olddirfd, ro, newdirfd, rn);
        if (ro != oldp) releaseRedirect(oldp, ro);
        if (rn != newp) releaseRedirect(newp, rn);
        return r;
    }
    return real_renameat(olddirfd, oldp, newdirfd, newp);
}

int my_rmdir(const char *path) {
    const char *redir = tryRedirect(path);
    int r = real_rmdir(redir);
    releaseRedirect(path, redir);
    return r;
}

int my_remove(const char *path) {
    const char *redir = tryRedirect(path);
    int r = real_remove(redir);
    releaseRedirect(path, redir);
    return r;
}

int my_chmod(const char *path, mode_t mode) {
    const char *redir = tryRedirect(path);
    int r = real_chmod(redir, mode);
    releaseRedirect(path, redir);
    return r;
}

int my_truncate(const char *path, off_t length) {
    const char *redir = tryRedirect(path);
    int r = real_truncate(redir, length);
    releaseRedirect(path, redir);
    return r;
}

DIR *my_opendir(const char *path) {
    // 伪装路径反查：readlink / dl_iterate_phdr 伪装过的目录路径还原成真实路径
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        DIR *du = real_opendir(unmasked);
        if (du == nullptr && errno == EACCES) logDenied("opendir", path);
        return du;
    }
    const char *redir = tryRedirect(path);
    DIR *d = real_opendir(redir);
    if (d == nullptr && errno == EACCES) logDenied("opendir", path);
    releaseRedirect(path, redir);
    return d;
}

int my_stat(const char *path, struct stat *buf) {
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        return real_stat(unmasked, buf);
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_stat(redir, buf);
    if (r != 0 && errno == EACCES) logDenied("stat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_lstat(const char *path, struct stat *buf) {
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        return real_lstat(unmasked, buf);
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_lstat(redir, buf);
    if (r != 0 && errno == EACCES) logDenied("lstat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_stat64(const char *path, struct stat64 *buf) {
    const char *unmasked = lookupReal(path);
    if (unmasked != nullptr) {
        return real_stat64(unmasked, buf);
    }
    if (denyIfContainerDataPath(path) != 0) {
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_stat64(redir, buf);
    if (r != 0 && errno == EACCES) logDenied("stat64", path);
    releaseRedirect(path, redir);
    return r;
}

// fd 版本无路径可重定向，仅做 EACCES 观测
int my_fstat(int fd, struct stat *buf) {
    int r = real_fstat(fd, buf);
    if (r != 0 && errno == EACCES) ALOGD("NativeIOHook: fstat EACCES for fd=%d", fd);
    return r;
}

int my_fstat64(int fd, struct stat64 *buf) {
    int r = real_fstat64(fd, buf);
    if (r != 0 && errno == EACCES) ALOGD("NativeIOHook: fstat64 EACCES for fd=%d", fd);
    return r;
}

int my_faccessat2(int dirfd, const char *path, int mode, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_faccessat2(dirfd, path, mode, flags);
    }
    if (denyBlackboxAncestorAccess(path, mode)) {
        errno = EACCES;
        return -1;
    }
    const char *redir = tryRedirect(path);
    int r = real_faccessat2(dirfd, redir, mode, flags);
    if (r != 0 && errno == EACCES) logDenied("faccessat2", path);
    releaseRedirect(path, redir);
    return r;
}

// 运行期动态加载的库（QQ 的插件 so、librealm-jni 等）在加载完成那一刻还
// 没有被补丁；已被补丁的库再 dlopen 新库时会经过这里，立即对新库补丁。
// 包装必须等 real 返回后才触发重扫，否则会在 linker 加载中途扫描半成品映射
void *my_dlopen(const char *filename, int flags) {
    void *handle;
    if (real_loader_dlopen != nullptr) {
        handle = real_loader_dlopen(filename, flags, __builtin_return_address(0));
    } else {
        handle = real_dlopen(filename, flags);
    }
    if (handle != nullptr) {
        // 先做 102 native 模块的注册/回调（模块 native_init 里可能经 hook_func
        // 请求补丁，自己会触发全量重扫），再常规刷新 IO 补丁
        LibXposedNative::onDlopen(filename, handle);
        NativeIOHook::install();
    }
    return handle;
}

void *my_android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *info) {
    void *handle;
    if (real_loader_android_dlopen_ext != nullptr) {
        handle = real_loader_android_dlopen_ext(filename, flags, info, __builtin_return_address(0));
    } else {
        handle = real_android_dlopen_ext(filename, flags, info);
    }
    if (handle != nullptr) {
        LibXposedNative::onDlopen(filename, handle);
        NativeIOHook::install();
    }
    return handle;
}

struct HookEntry {
    const char *name;
    void *hook;
    void **real;
};

HookEntry g_entries[] = {
        {"open",         (void *) my_open,         (void **) &real_open},
        {"openat",       (void *) my_openat,       (void **) &real_openat},
        {"__open_2",     (void *) my___open_2,     (void **) &real___open_2},
        {"__openat_2",   (void *) my___openat_2,   (void **) &real___openat_2},
        {"fopen",        (void *) my_fopen,        (void **) &real_fopen},
        {"mkdir",        (void *) my_mkdir,        (void **) &real_mkdir},
        {"mkdirat",      (void *) my_mkdirat,      (void **) &real_mkdirat},
        {"access",       (void *) my_access,       (void **) &real_access},
        {"faccessat",    (void *) my_faccessat,    (void **) &real_faccessat},
        {"fstatat",      (void *) my_fstatat,      (void **) &real_fstatat},
        {"fstatat64",    (void *) my_fstatat64,    (void **) &real_fstatat64},
        {"unlink",       (void *) my_unlink,       (void **) &real_unlink},
        {"unlinkat",     (void *) my_unlinkat,     (void **) &real_unlinkat},
        {"rename",       (void *) my_rename,       (void **) &real_rename},
        {"renameat",     (void *) my_renameat,     (void **) &real_renameat},
        {"rmdir",        (void *) my_rmdir,        (void **) &real_rmdir},
        {"remove",       (void *) my_remove,       (void **) &real_remove},
        {"chmod",        (void *) my_chmod,        (void **) &real_chmod},
        {"truncate",     (void *) my_truncate,     (void **) &real_truncate},
        {"opendir",      (void *) my_opendir,      (void **) &real_opendir},
        {"dlopen",           (void *) my_dlopen,             (void **) &real_dlopen},
        {"android_dlopen_ext", (void *) my_android_dlopen_ext, (void **) &real_android_dlopen_ext},
        {"open64",           (void *) my_open,               (void **) &real_open64},
        {"openat64",         (void *) my_openat,             (void **) &real_openat64},
        {"stat",             (void *) my_stat,               (void **) &real_stat},
        {"lstat",            (void *) my_lstat,              (void **) &real_lstat},
        {"stat64",           (void *) my_stat64,             (void **) &real_stat64},
        {"fstat",            (void *) my_fstat,              (void **) &real_fstat},
        {"fstat64",          (void *) my_fstat64,            (void **) &real_fstat64},
        {"faccessat2",       (void *) my_faccessat2,         (void **) &real_faccessat2},
        {"access",           (void *) my_access,            (void **) &real_access},
        {"faccessat",        (void *) my_faccessat,          (void **) &real_faccessat},
        {"__system_property_get", (void *) my___system_property_get, (void **) &real___system_property_get},
        {"dl_iterate_phdr",  (void *) my_dl_iterate_phdr,   (void **) &real_dl_iterate_phdr},
        {"readlink",         (void *) my_readlink,          (void **) &real_readlink},
        {"readlinkat",       (void *) my_readlinkat,        (void **) &real_readlinkat},
};

// resolveRemaining: 通过 dlsym 兜底填充仍未解析的 real 指针
static void *g_foundLoaderDlopen;
static void *g_foundLoaderDlopenExt;

// __loader_* 不在应用可见的符号搜索组里（dlsym 拿不到），但 libdl 自身是
// BIND_NOW：它的重定位表里就存着这两个函数的最终地址。用 dladdr 拿到 libdl
// 的加载基址后解析内存中的 ELF，把 GOT 槽里的地址读出来。
void scanLoaderSymbolsInLibdl(uintptr_t base) {
    auto *ehdr = reinterpret_cast<const ElfW(Ehdr) *>(base);
    if (ehdr->e_phoff == 0 || ehdr->e_phnum == 0 || ehdr->e_phnum > 128) return;
    auto *phdr = reinterpret_cast<const ElfW(Phdr) *>(base + ehdr->e_phoff);
    ElfW(Dyn) *dyn = nullptr;
    size_t dynMaxEntries = 0;
    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<ElfW(Dyn) *>(base + phdr[i].p_vaddr);
            dynMaxEntries = phdr[i].p_memsz / sizeof(ElfW(Dyn));
            break;
        }
    }
    if (dyn == nullptr || dynMaxEntries == 0 || dynMaxEntries > 65536) return;

    ElfW(Addr) jmprel = 0, rela = 0, symtab = 0, strtab = 0;
    size_t pltrelsz = 0, relasz = 0, strsz = 0;
    size_t scanned = 0;
    for (ElfW(Dyn) *d = dyn; scanned < dynMaxEntries; ++d, ++scanned) {
        ElfW(Sxword) tag = d->d_tag;
        if (tag == DT_NULL) break;
        switch (tag) {
            case DT_JMPREL: jmprel = d->d_un.d_ptr; break;
            case DT_PLTRELSZ: pltrelsz = d->d_un.d_val; break;
            case DT_RELA: rela = d->d_un.d_ptr; break;
            case DT_RELASZ: relasz = d->d_un.d_val; break;
            case DT_SYMTAB: symtab = d->d_un.d_ptr; break;
            case DT_STRTAB: strtab = d->d_un.d_ptr; break;
            case DT_STRSZ: strsz = d->d_un.d_val; break;
            default: break;
        }
    }
    if ((jmprel == 0 && rela == 0) || symtab == 0 || strtab == 0) return;

    auto abs = [&](ElfW(Addr) v) -> uintptr_t {
        return v >= base ? static_cast<uintptr_t>(v) : base + static_cast<uintptr_t>(v);
    };
    auto *syms = reinterpret_cast<ElfW(Sym) *>(abs(symtab));
    auto *strs = reinterpret_cast<const char *>(abs(strtab));

    auto scan = [&](uintptr_t relAddr, size_t totalBytes) {
        if (totalBytes == 0 || totalBytes > 4 * 1024 * 1024) return;
        size_t count = totalBytes / sizeof(ElfW(Rela));
        auto *entries = reinterpret_cast<ElfW(Rela) *>(relAddr);
        for (size_t i = 0; i < count; ++i) {
            uint32_t type = ELF64_R_TYPE(entries[i].r_info);
            if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
            uint32_t symIdx = ELF64_R_SYM(entries[i].r_info);
            uint64_t symOff = static_cast<uint64_t>(symIdx) * sizeof(ElfW(Sym));
            if (strsz == 0 || symOff >= strsz) continue;
            if (syms[symIdx].st_name >= strsz) continue;
            const char *nm = strs + syms[symIdx].st_name;
            void **slot = reinterpret_cast<void **>(base + entries[i].r_offset);
            if (*slot == nullptr) continue;
            if (g_foundLoaderDlopen == nullptr && strcmp(nm, "__loader_dlopen") == 0) {
                g_foundLoaderDlopen = *slot;
            } else if (g_foundLoaderDlopenExt == nullptr && strcmp(nm, "__loader_android_dlopen_ext") == 0) {
                g_foundLoaderDlopenExt = *slot;
            }
        }
    };
    if (jmprel != 0 && pltrelsz > 0) scan(abs(jmprel), pltrelsz);
    if (rela != 0 && relasz > 0) scan(abs(rela), relasz);
}

void *resolveLoaderSymbol(const char *name) {
    void *fn = dlsym(RTLD_DEFAULT, name);
    if (fn != nullptr) return fn;
    if (g_foundLoaderDlopen == nullptr && g_foundLoaderDlopenExt == nullptr) {
        void *anchor = dlsym(RTLD_DEFAULT, "dlopen");
        Dl_info di{};
        if (anchor != nullptr && dladdr(anchor, &di) != 0 && di.dli_fbase != nullptr) {
            scanLoaderSymbolsInLibdl(reinterpret_cast<uintptr_t>(di.dli_fbase));
        }
        ALOGD("NativeIOHook: libdl scan dlopen=%p ext=%p", g_foundLoaderDlopen, g_foundLoaderDlopenExt);
    }
    if (strcmp(name, "__loader_dlopen") == 0) return g_foundLoaderDlopen;
    return g_foundLoaderDlopenExt;
}

void resolveRealsViaDlsym() {
    for (auto &e: g_entries) {
        if (*e.real == nullptr) {
            *e.real = dlsym(RTLD_DEFAULT, e.name);
        }
    }
    if (real_loader_dlopen == nullptr) {
        real_loader_dlopen = reinterpret_cast<void *(*)(const char *, int, const void *)>(
                resolveLoaderSymbol("__loader_dlopen"));
    }
    if (real_loader_android_dlopen_ext == nullptr) {
        real_loader_android_dlopen_ext =
                reinterpret_cast<void *(*)(const char *, int, const android_dlextinfo *, const void *)>(
                        resolveLoaderSymbol("__loader_android_dlopen_ext"));
    }
    ALOGD("NativeIOHook: loader dlopen resolver %s, ext resolver %s",
          real_loader_dlopen != nullptr ? "ok" : "missing",
          real_loader_android_dlopen_ext != nullptr ? "ok" : "missing");
}

// ---------------------------------------------------------------------------
// 已处理库缓存。重扫（每次 dlopen 后触发）必须只解析新出现的库，否则大应用
// 启动期会成百次重复解析全部库。base+path 命中且哨兵 GOT 槽仍指向包装函数
// 时跳过；库被 dlclose 后重定位会把 GOT 还原，此时哨兵失效，自然触发重补。
// ---------------------------------------------------------------------------

struct PatchedLib {
    uintptr_t base;
    std::string path;
    uintptr_t sentinelGot;    // 第一个被改写的 GOT 槽地址；0 表示该库无命中符号
    void *sentinelValue;      // 写入哨兵槽的包装函数指针
};

std::vector<PatchedLib> g_patchedLibs;

PatchedLib *findPatched(uintptr_t base) {
    for (auto &p: g_patchedLibs) {
        if (p.base == base) return &p;
    }
    return nullptr;
}

void upsertPatched(uintptr_t base, const std::string &path, uintptr_t sentinelGot, void *sentinelValue) {
    PatchedLib *p = findPatched(base);
    if (p == nullptr) {
        PatchedLib n{};
        n.base = base;
        n.path = path;
        n.sentinelGot = sentinelGot;
        n.sentinelValue = sentinelValue;
        g_patchedLibs.push_back(n);
        return;
    }
    p->path = path;
    p->sentinelGot = sentinelGot;
    p->sentinelValue = sentinelValue;
}

// ---------------------------------------------------------------------------
// 目标库筛选
// ---------------------------------------------------------------------------

bool isDataLibPath(const char *path) {
    // 只补丁应用/模块自己的库（都在 /data 下）；系统库保持原行为
    if (path == nullptr || path[0] == '\0') return false;
    if (strncmp(path, "/data/", 6) != 0) return false;
    if (strstr(path, "libblackbox.so") != nullptr) return false;
    return true;
}

// System.loadLibrary 的实际 dlopen 发生在 libart/libnativeloader 里（不在
// /data 下，上面的全量补丁不会碰它们）。对这两个库只补 dlopen 家族符号，
// 让每次库加载完成都触发一次对新库的补丁。注意不能在 Java 层挂
// System.loadLibrary：Runtime.loadLibrary0 按调用者类解析库名空间，挂掉
// 之后库会被放进 BOOT 名空间导致 dlopen failed。
bool isDlopenOnlyLibPath(const char *path) {
    if (path == nullptr || path[0] == '\0') return false;
    if (strncmp(path, "/apex/com.android.art/", 22) != 0) return false;
    return strstr(path, "/libart.so") != nullptr ||
           strstr(path, "/libnativeloader.so") != nullptr;
}

// ---------------------------------------------------------------------------
// dl_iterate_phdr 遍历补丁
// ---------------------------------------------------------------------------

// GOT 页改写后不再恢复 RELRO 只读：恢复纯属加固，而判断原页属性需要解析
// maps；保持可写对功能无影响（bionic 默认 BIND_NOW，没有延迟绑定回写）
void writeGotEntry(void **got, void *hook) {
    long pageSize = sysconf(_SC_PAGESIZE);
    uintptr_t page = reinterpret_cast<uintptr_t>(got) & ~static_cast<uintptr_t>(pageSize - 1);
    mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE);
    *got = hook;
}

struct ScanCounters {
    int processed;
    int newLibs;
};

// ---------------------------------------------------------------------------
// libxposed 102 native 模块的命名 hook 表。模块经 hook_func(func, replace,
// backup) 请求 hook 任意导出函数（典型如 kill/_exit/exit/raise/abort），
// func 是模块 so 解析到的函数地址，dladdr 反查导出符号名后对 /data 下所有
// 库（含后续加载）改写 GOT。与 inline hook 不同，只影响经各自 PLT/GOT 的
// 调用方——游戏 so 内的调用均可覆盖，libc 内部调用不受影响。
// 加锁顺序恒为 installMutex -> 命名表锁；扫描路径只拿命名表锁。
// ---------------------------------------------------------------------------
struct NamedHook {
    std::string name;
    void *replace;
    void *real;
    bool active;
};

std::vector<NamedHook> g_namedHooks;
std::mutex g_namedHooksMutex;

NamedHook *findNamedHookLocked(const char *name) {
    for (auto &mh: g_namedHooks) {
        if (mh.name == name) return &mh;
    }
    return nullptr;
}

void applyNamedHooks(const char *nm, uintptr_t base, ElfW(Addr) gotOffset) {
    std::lock_guard<std::mutex> lock(g_namedHooksMutex);
    for (auto &mh: g_namedHooks) {
        if (mh.name != nm) continue;
        void **got = reinterpret_cast<void **>(base + gotOffset);
        if (!mh.active) {
            // 已 unhook：把此前指向 replace 的槽恢复为真实函数
            if (*got == mh.replace) writeGotEntry(got, mh.real);
        } else if (*got != mh.replace) {
            writeGotEntry(got, mh.replace);
        }
        break;
    }
}

// dl_iterate_phdr 回调，全程持有 linker 锁，可安全读写目标库内存
int hookCallback(struct dl_phdr_info *info, size_t, void *data) {
    const char *path = info->dlpi_name;
    bool dlopenOnly = isDlopenOnlyLibPath(path);
    if (!dlopenOnly && !isDataLibPath(path)) return 0;

    auto *counters = static_cast<ScanCounters *>(data);
    counters->processed++;
    uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);

    PatchedLib *cached = findPatched(base);
    if (cached != nullptr && cached->path == path) {
        bool stillHooked;
        if (cached->sentinelGot == 0) {
            stillHooked = true; // 库里本来就没有目标符号，重扫不会有效果
        } else {
            stillHooked = *reinterpret_cast<void **>(cached->sentinelGot) == cached->sentinelValue;
        }
        if (stillHooked) return 0;
    }
    counters->newLibs++;

    ElfW(Dyn) *dyn = nullptr;
    size_t dynMaxEntries = 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &ph = info->dlpi_phdr[i];
        if (ph.p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<ElfW(Dyn) *>(base + ph.p_vaddr);
            dynMaxEntries = ph.p_memsz / sizeof(ElfW(Dyn));
            break;
        }
    }
    if (dyn == nullptr || dynMaxEntries == 0 || dynMaxEntries > 65536) return 0;

    ElfW(Addr) jmprel = 0, rela = 0, symtab = 0, strtab = 0;
    size_t pltrelsz = 0, relasz = 0, strsz = 0;
    size_t scanned = 0;
    for (ElfW(Dyn) *d = dyn; scanned < dynMaxEntries; ++d, ++scanned) {
        ElfW(Sxword) tag = d->d_tag;
        if (tag == DT_NULL) break;
        switch (tag) {
            case DT_JMPREL:
                jmprel = d->d_un.d_ptr;
                break;
            case DT_PLTRELSZ:
                pltrelsz = d->d_un.d_val;
                break;
            case DT_RELA:
                rela = d->d_un.d_ptr;
                break;
            case DT_RELASZ:
                relasz = d->d_un.d_val;
                break;
            case DT_SYMTAB:
                symtab = d->d_un.d_ptr;
                break;
            case DT_STRTAB:
                strtab = d->d_un.d_ptr;
                break;
            case DT_STRSZ:
                strsz = d->d_un.d_val;
                break;
            default:
                break;
        }
    }
    if ((jmprel == 0 && rela == 0) || symtab == 0 || strtab == 0) return 0;

    // bionic 已把 .dynamic 的 d_ptr 重定位为绝对地址；兜底按"小于 base 加 bias"
    auto abs = [&](ElfW(Addr) v) -> uintptr_t {
        return v >= base ? static_cast<uintptr_t>(v) : base + static_cast<uintptr_t>(v);
    };
    auto *syms = reinterpret_cast<ElfW(Sym) *>(abs(symtab));
    auto *strs = reinterpret_cast<const char *>(abs(strtab));

    uintptr_t firstGot = 0;
    void *firstVal = nullptr;
    auto scan = [&](uintptr_t relAddr, size_t totalBytes) -> bool {
        if (totalBytes == 0 || totalBytes > 4 * 1024 * 1024) return true;
        size_t count = totalBytes / sizeof(ElfW(Rela));
        auto *entries = reinterpret_cast<ElfW(Rela) *>(relAddr);
        for (size_t i = 0; i < count; ++i) {
            uint32_t type = ELF64_R_TYPE(entries[i].r_info);
            if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
            uint32_t symIdx = ELF64_R_SYM(entries[i].r_info);
            // symtab 无独立长度，用 strsz 界定合理范围（每个符号至少对应一个
            // 名字字节），防止损坏的符号索引越界读
            uint64_t symOff = static_cast<uint64_t>(symIdx) * sizeof(ElfW(Sym));
            if (strsz == 0 || symOff >= strsz) continue;
            if (syms[symIdx].st_name >= strsz) continue;
            const char *nm = strs + syms[symIdx].st_name;
                for (auto &e: g_entries) {
                    if (dlopenOnly && strcmp(e.name, "dlopen") != 0 &&
                        strcmp(e.name, "android_dlopen_ext") != 0) {
                        continue;
                    }
                    if (strcmp(nm, e.name) != 0) continue;
                    void **got = reinterpret_cast<void **>(base + entries[i].r_offset);
                    if (*got == e.hook) break; // 已打过
                    if (*e.real == nullptr && *got != nullptr) {
                        *e.real = *got;
                    }
                    writeGotEntry(got, e.hook);
                    if (firstGot == 0) {
                        firstGot = reinterpret_cast<uintptr_t>(got);
                        firstVal = e.hook;
                    }
                    break;
                }
                if (!dlopenOnly) {
                    applyNamedHooks(nm, base, entries[i].r_offset);
                }
        }
        return true;
    };

    bool ok = true;
    if (jmprel != 0 && pltrelsz > 0) ok = scan(abs(jmprel), pltrelsz);
    if (ok && rela != 0 && relasz > 0) scan(abs(rela), relasz);
    if (ok) {
        // 重定位表不完整时（异常库）不入缓存，下次重扫补全
        upsertPatched(base, path, firstGot, firstVal);
    }
    return 0;
}

std::mutex g_installMutex;

// 调用方必须已持有 g_installMutex
void doInstallLocked() {
    if (real_fopen == nullptr) {
        resolveRealsViaDlsym();
        if (real_fopen == nullptr || real_open == nullptr) {
            ALOGE("NativeIOHook: resolve real libc functions failed");
            return;
        }
    }

    ScanCounters counters{0, 0};
    dl_iterate_phdr(hookCallback, &counters);
    ALOGD("NativeIOHook: scanned %d libraries (%d new)", counters.processed, counters.newLibs);
}

} // namespace

void NativeIOHook::install() {
    std::lock_guard<std::mutex> lock(g_installMutex);
    doInstallLocked();
}

void NativeIOHook::addMapsRule(int mode, const char *key, const char *value) {
    if (key == nullptr || key[0] == '\0') {
        return;
    }
    std::lock_guard<std::mutex> lock(g_mapsRulesMutex);
    g_mapsRules.push_back(MapsRule{mode, key, value == nullptr ? "" : value});
}

void NativeIOHook::clearMapsRules() {
    std::lock_guard<std::mutex> lock(g_mapsRulesMutex);
    g_mapsRules.clear();
}

void NativeIOHook::addPropRule(const char *key, const char *value) {
    if (key == nullptr || key[0] == '\0' || value == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_propRulesMutex);
    g_propRules.push_back(PropRule{key, value});
}

void NativeIOHook::clearPropRules() {
    std::lock_guard<std::mutex> lock(g_propRulesMutex);
    g_propRules.clear();
}

bool NativeIOHook::addNamedHook(const void *func, void *replace, void **backup) {
    if (func == nullptr || replace == nullptr || backup == nullptr) return false;
    Dl_info di{};
    if (dladdr(const_cast<void *>(func), &di) == 0 || di.dli_sname == nullptr ||
        di.dli_saddr == nullptr) {
        ALOGD("NativeIOHook: hook_func: cannot resolve symbol for %p", func);
        return false;
    }
    {
        std::lock_guard<std::mutex> installLock(g_installMutex);
        {
            std::lock_guard<std::mutex> lock(g_namedHooksMutex);
            NamedHook *existing = findNamedHookLocked(di.dli_sname);
            if (existing != nullptr) {
                existing->replace = replace;
                existing->real = di.dli_saddr;
                existing->active = true;
            } else {
                NamedHook mh{};
                mh.name = di.dli_sname;
                mh.replace = replace;
                mh.real = di.dli_saddr;
                mh.active = true;
                g_namedHooks.push_back(mh);
            }
        }
        // 新 hook 要对已加载库生效：清缓存强制全量重扫（缓存命中会早退跳过）
        g_patchedLibs.clear();
        doInstallLocked();
    }
    *backup = di.dli_saddr;
    ALOGD("NativeIOHook: named hook %s -> %p (real %p)", di.dli_sname, replace, di.dli_saddr);
    return true;
}

bool NativeIOHook::removeNamedHook(const void *func) {
    if (func == nullptr) return false;
    Dl_info di{};
    if (dladdr(const_cast<void *>(func), &di) == 0 || di.dli_sname == nullptr) return false;
    std::lock_guard<std::mutex> installLock(g_installMutex);
    {
        std::lock_guard<std::mutex> lock(g_namedHooksMutex);
        NamedHook *existing = findNamedHookLocked(di.dli_sname);
        if (existing == nullptr) return false;
        existing->active = false;
    }
    g_patchedLibs.clear();
    doInstallLocked();
    ALOGD("NativeIOHook: named hook %s removed", di.dli_sname);
    return true;
}

#else // !__aarch64__

void NativeIOHook::install() {
    // 仅实现了 arm64 的 GOT 补丁，其他架构保持原行为
    ALOGD("NativeIOHook: not supported on this arch, skipped");
}

bool NativeIOHook::addNamedHook(const void *, void *, void **) {
    // 无 GOT 补丁通路，102 native 模块的 hook_func 不可用（模块会跳过自身 hook）
    return false;
}

bool NativeIOHook::removeNamedHook(const void *) {
    return false;
}

#endif // __aarch64__
