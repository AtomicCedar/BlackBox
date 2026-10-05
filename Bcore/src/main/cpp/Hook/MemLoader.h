//
// 注入 so 内存加载器：绕过系统 linker，把磁盘上的 so 加载进匿名内存执行。
// 主 so 由本加载器处理（mmap 数据源 → 段加载 → 重定位 → .init/.init_array/
// JNI_OnLoad），DT_NEEDED 依赖库走系统 dlopen（复杂依赖交给 linker）。
// 加载完成后磁盘文件可删、maps 无文件路径（匿名映射），注入痕迹少一层。
//
// 参考：OrientalGlass/CustomLinker（加载流程九步），符号解析保持 dlsym 遍历
// 依赖库（对自写 hook 库足够；需要从任意已加载库解析符号时可用 xdl 增强）。
//

#ifndef BLACKBOX_MEMLOADER_H
#define BLACKBOX_MEMLOADER_H

#include <jni.h>
#include <elf.h>
#include <string>
#include <vector>

class MemLoader {
public:
    // 加载指定 so 文件到匿名内存并调用其 JNI_OnLoad。加载器实例驻留内存
    // （不可析构——so 的代码/数据在 mmap 映像里，析构 munmap 后已注册的
    // JNI 方法再被调用会 SIGSEGV）。成功返回 true。
    static bool load(const char *soPath, JavaVM *vm);

private:
    explicit MemLoader(JavaVM *vm);
    ~MemLoader() = default;

    bool loadInternal(const char *soPath);

    // 九步加载流程
    bool mapFile();
    bool checkElfHeader();
    bool allocImage();
    bool loadSegments();
    bool parseDynamic();
    bool loadDeps();
    bool relocate();
    bool setProtection();
    bool callInit();
    void wipeElfHeaders();

    Elf64_Sym *findSymbol(const char *name);
    Elf64_Sym *findSymbolGnu(const char *name);
    Elf64_Sym *findSymbolSysv(const char *name);
    Elf64_Addr resolveSymbol(const char *name);
    void processRelocs(Elf64_Rela *table, size_t count);
    Elf64_Addr getSymbol(const char *name);

    static uint32_t gnuHash(const uint8_t *name);
    static uint32_t elfHash(const uint8_t *name);

private:
    JavaVM *jvm;
    std::string soPath;

    // 文件映射（数据源，加载完成后 munmap）
    int fd = -1;
    uint8_t *pFileMap = nullptr;
    size_t fileSize = 0;

    // ELF 解析
    Elf64_Ehdr *pElfHeader = nullptr;
    Elf64_Phdr *pProgramHeader = nullptr;
    size_t programHeaderNum = 0;

    // 内存映像
    uint8_t *pImageBase = nullptr;
    size_t imageSize = 0;

    // 动态段
    Elf64_Dyn *pDynamicTable = nullptr;
    size_t dynamicItemNum = 0;
    Elf64_Rela *pRelaDyn = nullptr;
    size_t relaDynNum = 0;
    Elf64_Rela *pRelaPlt = nullptr;
    size_t relaPltNum = 0;
    Elf64_Sym *pDynSym = nullptr;
    char *pDynStr = nullptr;

    // GNU Hash
    uint32_t *pGnuHash = nullptr;
    size_t gnuBucketNum = 0, gnuSymOffset = 0, gnuMaskWords = 0, gnuShift2 = 0;
    Elf64_Xword *gnuBloomFilter = nullptr;
    uint32_t *gnuBuckets = nullptr, *gnuChains = nullptr;

    // SysV Hash
    uint32_t *pSysvHash = nullptr;
    size_t sysvBucketNum = 0, sysvChainNum = 0;

    // 依赖库句柄（析构时 dlclose）
    std::vector<void *> depHandles;
};

#endif // BLACKBOX_MEMLOADER_H
