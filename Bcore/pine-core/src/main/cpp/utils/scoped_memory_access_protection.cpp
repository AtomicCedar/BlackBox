//
// Created by canyie on 2020/5/26.
//
#include "scoped_memory_access_protection.h"

using namespace pine;

#if defined(__aarch64__) || defined(__arm__)

thread_local ScopedMemoryAccessProtection* ScopedMemoryAccessProtection::current = nullptr;

void ScopedMemoryAccessProtection::HandleSignal(int signal, siginfo_t* info, void* reserved) {
    assert(signal == SIGSEGV);
    // 本线程可能不在任何保护窗口内(进程级 handler 被其它线程安装,或窗口已退出)。
    // 此时不能解引用 current,否则会在已崩溃的线程上二次崩溃、掩盖真实故障点:
    // 恢复默认 SIGSEGV 处理并重新触发,交给系统生成 tombstone。
    auto* protection = current;
    if (UNLIKELY(protection == nullptr)) {
        struct sigaction default_action {};
        default_action.sa_handler = SIG_DFL;
        sigaction(SIGSEGV, &default_action, nullptr);
        raise(SIGSEGV);
        return; // Unreachable
    }

    ucontext_t* context = static_cast<ucontext_t*>(reserved);
    uintptr_t fault_addr = context->uc_mcontext.fault_address;

    if (LIKELY(info->si_code == SEGV_ACCERR)) {
        if (LIKELY(fault_addr >= protection->addr && fault_addr <= (protection->addr + protection->size))) {
            if (LIKELY(protection->max_retries-- > 0)) {
                LOGW("Segmentation fault when trying access %p, unprotect it and try again", (void*) fault_addr);
                if (LIKELY(Memory::Unprotect(reinterpret_cast<void*>(fault_addr))))
                    return;
                LOGE("Failed to unprotect fault address…");
            } else {
                LOGE("Retried too many times to access %p", (void*) fault_addr);
            }
        }
    }

    if (protection->def.sa_sigaction == nullptr) {
        FATAL("No default signal handler to dispatch SIGSEGV (fault addr %p)", (void*) fault_addr);
    } else {
        protection->def.sa_sigaction(signal, info, reserved);
    }
}
#endif
