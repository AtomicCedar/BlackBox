//
// MemLoader 实现：内存加载 so 的九步流程（参考 OrientalGlass/CustomLinker）。
// 日志走项目 Log.h（ALOGD/ALOGE），入口 MemLoader::load 由 BoxCore JNI 调用。
//

#include "MemLoader.h"

#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <elf.h>
#include <vector>
#include <cstring>
#include <cstdio>

#include "../Log.h"

#define PAGE_START(addr) ((uintptr_t)(addr) & ~(g_PageSize - 1))
#define PAGE_END(addr)   (((uintptr_t)(addr) + g_PageSize - 1) & ~(g_PageSize - 1))

static size_t g_PageSize = 4096;

MemLoader::MemLoader(JavaVM *vm) : jvm(vm) {
    if (g_PageSize == 4096) {
        g_PageSize = sysconf(_SC_PAGESIZE);  // Android 15+ 可能为 16KB
    }
}

bool MemLoader::load(const char *soPath, JavaVM *vm) {
    // 必须堆分配且驻留：so 的代码/数据在 mmap 映像里，析构 munmap 后
    // 已注册的 JNI 方法再被调用会 SIGSEGV。
    auto *loader = new MemLoader(vm);
    loader->soPath = soPath;
    return loader->loadInternal(soPath);
}

bool MemLoader::loadInternal(const char *soPath) {
    ALOGD("MemLoader start: %s", soPath);
    if (soPath == nullptr || soPath[0] == '\0') {
        ALOGE("MemLoader: empty so path");
        return false;
    }
    if (!mapFile()) {
        ALOGE("MemLoader step1 mapFile failed: %s", soPath);
        return false;
    }
    if (!checkElfHeader()) {
        ALOGE("MemLoader step2 checkElfHeader failed");
        return false;
    }
    if (!allocImage()) {
        ALOGE("MemLoader step3 allocImage failed");
        return false;
    }
    if (!loadSegments()) {
        ALOGE("MemLoader step4 loadSegments failed");
        return false;
    }
    if (!parseDynamic()) {
        ALOGE("MemLoader step5 parseDynamic failed");
        return false;
    }
    if (!loadDeps()) {
        ALOGE("MemLoader step6 loadDeps failed");
        return false;
    }
    if (!relocate()) {
        ALOGE("MemLoader step7 relocate failed");
        return false;
    }
    if (!setProtection()) {
        ALOGE("MemLoader step8 setProtection failed");
        return false;
    }
    if (!callInit()) {
        ALOGE("MemLoader step9 callInit failed");
        return false;
    }

    // 数据源映射用完即卸，maps 里只剩匿名映像
    if (pFileMap && fileSize > 0) {
        munmap(pFileMap, fileSize);
        pFileMap = nullptr;
    }
    if (fd > 0) {
        close(fd);
        fd = -1;
    }

    // 抹除内存中的 ELF 头部，防 /proc/pid/mem dump
    wipeElfHeaders();
    ALOGD("MemLoader complete: %s", soPath);
    return true;
}

bool MemLoader::mapFile() {
    fd = open(soPath.c_str(), O_RDONLY);
    if (fd < 0) {
        ALOGE("MemLoader open failed: %s", soPath.c_str());
        return false;
    }
    struct stat st;
    if (fstat(fd, &st) < 0) {
        ALOGE("MemLoader fstat failed");
        return false;
    }
    fileSize = st.st_size;
    pFileMap = (uint8_t *) mmap(nullptr, fileSize, PROT_READ, MAP_PRIVATE, fd, 0);
    if (pFileMap == MAP_FAILED) {
        ALOGE("MemLoader mmap failed");
        pFileMap = nullptr;
        return false;
    }
    ALOGD("MemLoader file mapped: size=0x%zx", fileSize);
    return true;
}

bool MemLoader::checkElfHeader() {
    if (!pFileMap || fileSize < sizeof(Elf64_Ehdr)) {
        return false;
    }
    pElfHeader = (Elf64_Ehdr *) pFileMap;
    if (memcmp(pElfHeader->e_ident, ELFMAG, SELFMAG) != 0) {
        ALOGE("MemLoader invalid ELF magic");
        return false;
    }
    if (pElfHeader->e_type != ET_DYN || pElfHeader->e_machine != EM_AARCH64) {
        ALOGE("MemLoader not AArch64 shared object");
        return false;
    }
    pProgramHeader = (Elf64_Phdr *) (pFileMap + pElfHeader->e_phoff);
    programHeaderNum = pElfHeader->e_phnum;
    ALOGD("MemLoader ELF valid: %zu program headers", programHeaderNum);
    return true;
}

bool MemLoader::allocImage() {
    Elf64_Addr minVaddr = (Elf64_Addr) -1;
    Elf64_Addr maxVaddr = 0;
    for (size_t i = 0; i < programHeaderNum; i++) {
        if (pProgramHeader[i].p_type != PT_LOAD) {
            continue;
        }
        if (pProgramHeader[i].p_vaddr < minVaddr) {
            minVaddr = pProgramHeader[i].p_vaddr;
        }
        Elf64_Addr segEnd = pProgramHeader[i].p_vaddr + pProgramHeader[i].p_memsz;
        if (segEnd > maxVaddr) {
            maxVaddr = segEnd;
        }
    }
    imageSize = PAGE_END(maxVaddr) - PAGE_START(minVaddr);
    pImageBase = (uint8_t *) mmap(nullptr, imageSize, PROT_READ | PROT_WRITE,
                                  MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (pImageBase == MAP_FAILED) {
        ALOGE("MemLoader image alloc failed");
        pImageBase = nullptr;
        return false;
    }
    ALOGD("MemLoader image: base=%p size=0x%zx", pImageBase, imageSize);
    return true;
}

bool MemLoader::loadSegments() {
    for (size_t i = 0; i < programHeaderNum; i++) {
        if (pProgramHeader[i].p_type != PT_LOAD) {
            continue;
        }
        if (pProgramHeader[i].p_offset + pProgramHeader[i].p_filesz > fileSize) {
            ALOGE("MemLoader segment %zu out of bounds", i);
            return false;
        }
        uint8_t *dest = pImageBase + pProgramHeader[i].p_vaddr;
        memcpy(dest, pFileMap + pProgramHeader[i].p_offset, pProgramHeader[i].p_filesz);
        if (pProgramHeader[i].p_memsz > pProgramHeader[i].p_filesz) {
            memset(dest + pProgramHeader[i].p_filesz, 0,
                   pProgramHeader[i].p_memsz - pProgramHeader[i].p_filesz);
        }
    }
    ALOGD("MemLoader segments loaded");
    return true;
}

bool MemLoader::parseDynamic() {
    for (size_t i = 0; i < programHeaderNum; i++) {
        if (pProgramHeader[i].p_type == PT_DYNAMIC) {
            pDynamicTable = (Elf64_Dyn *) (pImageBase + pProgramHeader[i].p_vaddr);
            dynamicItemNum = pProgramHeader[i].p_memsz / sizeof(Elf64_Dyn);
            break;
        }
    }
    if (!pDynamicTable) {
        ALOGE("MemLoader PT_DYNAMIC not found");
        return false;
    }
    for (size_t i = 0; i < dynamicItemNum; i++) {
        Elf64_Xword val = pDynamicTable[i].d_un.d_val;
        switch (pDynamicTable[i].d_tag) {
            case DT_RELA:
                pRelaDyn = (Elf64_Rela *) (pImageBase + val);
                break;
            case DT_RELASZ:
                relaDynNum = val / sizeof(Elf64_Rela);
                break;
            case DT_JMPREL:
                pRelaPlt = (Elf64_Rela *) (pImageBase + val);
                break;
            case DT_PLTRELSZ:
                relaPltNum = val / sizeof(Elf64_Rela);
                break;
            case DT_SYMTAB:
                pDynSym = (Elf64_Sym *) (pImageBase + val);
                break;
            case DT_STRTAB:
                pDynStr = (char *) (pImageBase + val);
                break;
            case DT_GNU_HASH:
                pGnuHash = (uint32_t *) (pImageBase + val);
                gnuBucketNum = pGnuHash[0];
                gnuSymOffset = pGnuHash[1];
                gnuMaskWords = pGnuHash[2];
                gnuShift2 = pGnuHash[3];
                gnuBloomFilter = (Elf64_Xword *) (pGnuHash + 4);
                gnuBuckets = (uint32_t *) (gnuBloomFilter + gnuMaskWords);
                gnuChains = gnuBuckets + gnuBucketNum;
                break;
            case DT_HASH:
                pSysvHash = (uint32_t *) (pImageBase + val);
                sysvBucketNum = pSysvHash[0];
                sysvChainNum = pSysvHash[1];
                break;
            default:
                break;
        }
    }
    ALOGD("MemLoader dynamic: relaDyn=%zu relaPlt=%zu gnuHash=%s",
          relaDynNum, relaPltNum, pGnuHash ? "yes" : "no");
    return true;
}

bool MemLoader::loadDeps() {
    for (size_t i = 0; i < dynamicItemNum; i++) {
        if (pDynamicTable[i].d_tag != DT_NEEDED) {
            continue;
        }
        const char *libName = pDynStr + pDynamicTable[i].d_un.d_val;
        void *handle = dlopen(libName, RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            ALOGD("MemLoader dlopen(%s) warn: %s", libName, dlerror());
            continue;
        }
        depHandles.push_back(handle);
        ALOGD("MemLoader dep loaded: %s", libName);
    }
    return true;
}

uint32_t MemLoader::gnuHash(const uint8_t *name) {
    uint32_t h = 5381;
    while (*name) {
        h = (h << 5) + h + *name++;
    }
    return h;
}

uint32_t MemLoader::elfHash(const uint8_t *name) {
    uint32_t h = 0, g;
    while (*name) {
        h = (h << 4) + *name++;
        g = h & 0xf0000000;
        if (g) h ^= g >> 24;
        h &= ~g;
    }
    return h;
}

Elf64_Sym *MemLoader::findSymbolGnu(const char *name) {
    if (!pGnuHash) return nullptr;
    uint32_t hash = gnuHash((const uint8_t *) name);
    Elf64_Xword bloomWord = gnuBloomFilter[(hash / 64) % gnuMaskWords];
    Elf64_Xword mask = (Elf64_Xword) 1 << (hash % 64)
                       | (Elf64_Xword) 1 << ((hash >> gnuShift2) % 64);
    if ((bloomWord & mask) != mask) return nullptr;
    uint32_t symIdx = gnuBuckets[hash % gnuBucketNum];
    if (symIdx < gnuSymOffset) return nullptr;
    uint32_t *chainPtr = gnuChains + (symIdx - gnuSymOffset);
    while (true) {
        uint32_t chainHash = *chainPtr;
        if ((chainHash | 1) == (hash | 1)) {
            const char *symName = pDynStr + pDynSym[symIdx].st_name;
            if (strcmp(symName, name) == 0) return &pDynSym[symIdx];
        }
        if (chainHash & 1) break;
        symIdx++;
        chainPtr++;
    }
    return nullptr;
}

Elf64_Sym *MemLoader::findSymbolSysv(const char *name) {
    if (!pSysvHash) return nullptr;
    uint32_t hash = elfHash((const uint8_t *) name);
    uint32_t idx = pSysvHash[2 + (hash % sysvBucketNum)];
    uint32_t *chains = pSysvHash + 2 + sysvBucketNum;
    while (idx != 0) {
        const char *symName = pDynStr + pDynSym[idx].st_name;
        if (strcmp(symName, name) == 0) return &pDynSym[idx];
        idx = chains[idx];
    }
    return nullptr;
}

Elf64_Sym *MemLoader::findSymbol(const char *name) {
    Elf64_Sym *sym = findSymbolGnu(name);
    if (sym) return sym;
    return findSymbolSysv(name);
}

Elf64_Addr MemLoader::resolveSymbol(const char *name) {
    // 先查 so 自身的符号表——C++ 编译的 so 里未内联的 JNIEnv 成员方法等
    // 符号定义在 so 内部但走 PLT（st_value 非 0），地址 = 基址 + st_value
    Elf64_Sym *local = findSymbol(name);
    if (local && local->st_value != 0) {
        return (Elf64_Addr) pImageBase + local->st_value;
    }
    // 再查 DT_NEEDED 依赖库
    for (void *h : depHandles) {
        void *addr = dlsym(h, name);
        if (addr) return (Elf64_Addr) addr;
    }
    // 兜底：全局已加载库（libc/liblog 等可能未出现在 DT_NEEDED）
    void *any = dlsym(RTLD_DEFAULT, name);
    if (any) return (Elf64_Addr) any;
    return 0;
}

void MemLoader::processRelocs(Elf64_Rela *table, size_t count) {
    if (!table) return;
    for (size_t i = 0; i < count; i++) {
        uint32_t type = ELF64_R_TYPE(table[i].r_info);
        uint32_t symIdx = ELF64_R_SYM(table[i].r_info);
        Elf64_Addr *target = (Elf64_Addr *) (pImageBase + table[i].r_offset);
        switch (type) {
            case R_AARCH64_RELATIVE:
                *target = (Elf64_Addr) pImageBase + table[i].r_addend;
                break;
            case R_AARCH64_ABS64:
            case R_AARCH64_GLOB_DAT:
            case R_AARCH64_JUMP_SLOT: {
                if (symIdx == 0) break;
                const char *symName = pDynStr + pDynSym[symIdx].st_name;
                Elf64_Addr symAddr = resolveSymbol(symName);
                if (symAddr) {
                    *target = symAddr + table[i].r_addend;
                }
                break;
            }
            default:
                break;
        }
    }
}

bool MemLoader::relocate() {
    processRelocs(pRelaDyn, relaDynNum);
    processRelocs(pRelaPlt, relaPltNum);
    ALOGD("MemLoader relocation complete");
    return true;
}

bool MemLoader::setProtection() {
    for (size_t i = 0; i < programHeaderNum; i++) {
        if (pProgramHeader[i].p_type != PT_LOAD) continue;
        int prot = 0;
        if (pProgramHeader[i].p_flags & PF_R) prot |= PROT_READ;
        if (pProgramHeader[i].p_flags & PF_W) prot |= PROT_WRITE;
        if (pProgramHeader[i].p_flags & PF_X) prot |= PROT_EXEC;
        uint8_t *start = pImageBase + PAGE_START(pProgramHeader[i].p_vaddr);
        uint8_t *end = pImageBase + PAGE_END(pProgramHeader[i].p_vaddr + pProgramHeader[i].p_memsz);
        mprotect(start, end - start, prot);
    }
    __builtin___clear_cache((char *) pImageBase, (char *) pImageBase + imageSize);
    ALOGD("MemLoader protection applied");
    return true;
}

bool MemLoader::callInit() {
    Elf64_Addr initFunc = 0;
    Elf64_Addr *initArray = nullptr;
    size_t initArraySize = 0;
    for (size_t i = 0; i < dynamicItemNum; i++) {
        switch (pDynamicTable[i].d_tag) {
            case DT_INIT:
                initFunc = pDynamicTable[i].d_un.d_val;
                break;
            case DT_INIT_ARRAY:
                initArray = (Elf64_Addr *) (pImageBase + pDynamicTable[i].d_un.d_val);
                break;
            case DT_INIT_ARRAYSZ:
                initArraySize = pDynamicTable[i].d_un.d_val / sizeof(Elf64_Addr);
                break;
            default:
                break;
        }
    }
    if (initFunc) {
        ((void (*)()) (pImageBase + initFunc))();
    }
    if (initArray) {
        for (size_t i = 0; i < initArraySize; i++) {
            ((void (*)()) (initArray[i]))();
        }
    }
    Elf64_Addr jniOnLoad = getSymbol("JNI_OnLoad");
    if (jniOnLoad) {
        typedef jint (*JNI_OnLoadFn)(JavaVM *, void *);
        jint ret = ((JNI_OnLoadFn) jniOnLoad)(jvm, nullptr);
        ALOGD("MemLoader JNI_OnLoad returned %d", ret);
    }
    return true;
}

Elf64_Addr MemLoader::getSymbol(const char *name) {
    Elf64_Sym *sym = findSymbol(name);
    if (sym) return (Elf64_Addr) (pImageBase + sym->st_value);
    return 0;
}

void MemLoader::wipeElfHeaders() {
    if (!pImageBase) return;
    void *headerPage = (void *) PAGE_START(pImageBase);

    mprotect(headerPage, g_PageSize, PROT_READ | PROT_WRITE);
    memset(pImageBase, 0, sizeof(Elf64_Ehdr));

    uint8_t *phdrStart = (uint8_t *) pProgramHeader;
    if (phdrStart >= pImageBase && phdrStart < pImageBase + imageSize) {
        uintptr_t phdrPageStart = PAGE_START(phdrStart);
        uintptr_t phdrPageEnd = PAGE_END(phdrStart + programHeaderNum * sizeof(Elf64_Phdr));
        size_t phdrLen = phdrPageEnd - phdrPageStart;
        mprotect((void *) phdrPageStart, phdrLen, PROT_READ | PROT_WRITE);
        memset(phdrStart, 0, programHeaderNum * sizeof(Elf64_Phdr));
        mprotect((void *) phdrPageStart, phdrLen, PROT_READ);
    }

    if (pDynamicTable) {
        uint8_t *dynStart = (uint8_t *) pDynamicTable;
        if (dynStart >= pImageBase && dynStart < pImageBase + imageSize) {
            size_t dynSize = dynamicItemNum * sizeof(Elf64_Dyn);
            uintptr_t dynPageStart = PAGE_START(dynStart);
            uintptr_t dynPageEnd = PAGE_END(dynStart + dynSize);
            size_t dynLen = dynPageEnd - dynPageStart;
            mprotect((void *) dynPageStart, dynLen, PROT_READ | PROT_WRITE);
            memset(dynStart, 0, dynSize);
            mprotect((void *) dynPageStart, dynLen, PROT_READ);
        }
    }

    mprotect(headerPage, g_PageSize, PROT_READ | PROT_EXEC);
    ALOGD("MemLoader ELF headers wiped");
}
