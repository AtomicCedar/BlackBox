//
// Created by canyie on 2020/3/11.
//

#include <sys/user.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <bits/sysconf.h>
#include "memory.h"
#include "lock.h"
#include "../pine_config.h"

using namespace pine;

const size_t Memory::page_size = static_cast<const size_t>(sysconf(_SC_PAGESIZE));

uintptr_t Memory::address = 0;
size_t Memory::offset = 0;
std::mutex Memory::mutex;

void* Memory::AllocUnprotected(size_t size) {
    if (UNLIKELY(size > page_size)) {
        LOGE("Attempting to allocate too much memory space (%zx bytes)", size);
        errno = ENOMEM;
        return nullptr;
    }

    ScopedLock lock(mutex);

    if (LIKELY(address)) {
        size_t next_offset = offset + size;
        if (LIKELY(next_offset <= page_size)) {
            void* ptr = reinterpret_cast<void*>(address + offset);
            offset = next_offset;
            return ptr;
        }
        // 当前页已写满,换新页前收回旧页的写权限(保留读+执行),避免长期暴露 RWX 匿名段。
        // 所有 trampoline 的写入都发生在 ScopedSuspendVM 挂起其它线程期间,
        // 因此换页时旧页内已分配的槽位必然已写完,可安全 mprotect。
        int result = mprotect(reinterpret_cast<void*>(address), page_size, PROT_READ | PROT_EXEC);
        if (UNLIKELY(result == -1))
            LOGE("Failed to remove write permission of trampoline page %p: %s (%d)",
                    reinterpret_cast<void*>(address), strerror(errno), errno);
    }

    void* mapped = mmap(nullptr, page_size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);

    if (UNLIKELY(mapped == MAP_FAILED)) {
        LOGE("Unable to allocate executable memory: %s (%d)", strerror(errno), errno);
        return nullptr;
    }
    if (PineConfig::debug && PineConfig::debuggable)
        LOGD("Mapped new memory %p (size %zu)", mapped, page_size);

    if (!PineConfig::anti_checks)
        prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, mapped, size, "pine codes");

    memset(mapped, 0, page_size);
    address = reinterpret_cast<uintptr_t>(mapped);
    offset = size;
    return mapped;
}
