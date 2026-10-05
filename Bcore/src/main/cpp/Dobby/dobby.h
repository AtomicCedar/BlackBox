#ifndef dobby_h
#define dobby_h

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// ---- basic types ----------------------------------------------------------

typedef uintptr_t addr_t;
typedef uint32_t addr32_t;
typedef uint64_t addr64_t;

// opaque generic function pointer; cast to/from the real function type at the call site
typedef void *dobby_func_ptr_t;

// opaque handle identifying one chained-hook layer; returned by DobbyHookEx,
// consumed by DobbyUnhook. Invalidated once the layer is unhooked or the
// whole entry is fully restored (DobbyDestroy to the end); stale handles are
// validated via an internal registry and safely rejected with -1.
typedef void *dobby_hook_handle_t;

#define ARM64_TMP_REG_NDX_0 17

// ---- fine-grained error codes ---------------------------------------------
// APIs return 0 on success and a negative error code on failure. Codes are
// distinguished by cause so callers can react per cause instead of treating
// every failure alike; every code is still < 0, so an `rc != 0` check keeps
// working unchanged. DOBBY_ERR_BAD_ARG (-1) is the generic argument error
// (null address/handle); the rest are specific:
//   DOBBY_ERR_NOT_HOOKED  - Unhook/Destroy target is not hooked, stale handle
//   DOBBY_ERR_DUPLICATE   - same replace function already hooks this address
//   DOBBY_ERR_CONFLICT    - hook/instrument type clash on the same address
//   DOBBY_ERR_SHORT_FUNC  - function too short for the multi-instruction
//                           entry patch (enable near branch to hook short fns)
//   DOBBY_ERR_MEMORY      - exec / near memory allocation failed
//   DOBBY_ERR_PATCH       - code patch (write/mprotect/icache) failed
#define DOBBY_ERR_BAD_ARG    (-1)
#define DOBBY_ERR_NOT_HOOKED (-2)
#define DOBBY_ERR_DUPLICATE  (-3)
#define DOBBY_ERR_CONFLICT   (-4)
#define DOBBY_ERR_SHORT_FUNC (-5)
#define DOBBY_ERR_MEMORY     (-6)
#define DOBBY_ERR_PATCH      (-7)

// ---- function inline hook ------------------------------------------------
// chaining: hooking an already-hooked address chains instead of failing; the new
// replace_func becomes the head and origin_func receives the previous head, so
// calling it keeps the chain alive (new -> previous -> ... -> original).
// DobbyDestroy pops the head and restores the previous hook (LIFO).
// DobbyUnhook removes a specific layer by handle (any order within the chain).
//
// Limitations:
//  - DobbyDestroy is LIFO only: it pops the most recent replace on
//    that address; removing an earlier hook while keeping a later one
//    is not supported (use DobbyUnhook for that).
//  - Always call the previous function through origin_func; calling the hooked
//    function by name re-enters the head and recurses.
//  - DobbyHook/DobbyInstrument/DobbyDestroy/DobbyUnhook are mutually serialized, but
//    destroy does not wait for threads already executing inside a trampoline.
//    Quiesce callers before destroying a live hook.
int DobbyHook(void *address, dobby_func_ptr_t replace_func, dobby_func_ptr_t *origin_func);

int DobbyHookEx(void *address, dobby_func_ptr_t replace_func, dobby_func_ptr_t *origin_func, dobby_hook_handle_t *handle);

int DobbyUnhook(dobby_hook_handle_t handle);

// destroy and restore code patch
int DobbyDestroy(void *address);

// ---- hook state query -----------------------------------------------------
// 1 if `address` currently owns an Interceptor entry (DobbyHook/DobbyHookEx/
// DobbyInstrument), 0 otherwise. Raw code patches are untracked.
int DobbyIsHooked(void *address);

// number of live intercept entries (hook + instrument)
int DobbyHookCount(void);

// ---- hook operation audit record -----------------------------------------
// Every DobbyHook/DobbyHookEx/DobbyUnhook/DobbyDestroy/DobbyInstrument call
// against a valid address appends one record (success or failure) to an
// internal log. Records are retained even after the hook is fully restored,
// so the history of "who hooked this, how, and when" survives the entry.
//
// Record fields: operation type, target address, replace/origin function,
// associated handle, result code, timestamp (monotonic microseconds),
// thread id, and the owning module/symbol names resolved from the address.
//
// Thread safety: recording and querying are mutually serialized with each
// other and with hook mutations; records never alias an entry that was freed.
typedef enum {
  kDobbyOpHook = 1,        // first inline hook on the address
  kDobbyOpChainHook,       // chained hook stacked on an already-hooked address
  kDobbyOpUnhook,          // DobbyUnhook removed one layer by handle
  kDobbyOpDestroy,         // DobbyDestroy popped a layer or restored the entry
  kDobbyOpInstrument,      // DobbyInstrument/DobbyInstrumentEx
} dobby_operation_type_t;

typedef struct {
  int type;                        // dobby_operation_type_t
  void *address;                   // target address the record is about
  dobby_func_ptr_t replace_func;   // replace function (0 if not applicable)
  dobby_func_ptr_t origin_func;    // origin handed out (0 if not applicable)
  dobby_hook_handle_t handle;      // related handle (0 if not applicable)
  int result;                      // API return value (0 ok, negative failure)
  uint64_t timestamp_us;           // monotonic clock, microseconds
  uint64_t thread_id;              // pthread_self()
  char module[128];                // owning module name of `address`
  char symbol[128];                // nearest symbol name of `address`
} dobby_operation_record_t;

// Query the operation history of one address, oldest first. `records` receives
// up to *count entries and *count is updated with the number written. Returns 0
// on success (possibly 0 records), -1 on invalid arguments. Records of an
// address that was fully restored remain visible here.
int DobbyQueryOperations(void *address, dobby_operation_record_t *records, uint32_t *count);

// Current live state of one address: whether it owns an intercept entry, the
// replace-chain layout (layer count, head replace/handle) and the relocated
// origin. `hooked` is 0 when the address is not currently intercepted, in
// which case all other fields are zero. Returns 0 on success, -1 on invalid
// arguments.
typedef struct {
  int hooked;                // 1 if the address owns a live intercept entry
  int chain_mode;            // 1 if the entry is in pointer-chain mode
  uint32_t layer_count;      // replace-chain layers (0 when not hooked)
  dobby_func_ptr_t head_replace;  // current head replace (0 when not hooked)
  dobby_hook_handle_t head_handle; // head handle, usable with DobbyUnhook
  dobby_func_ptr_t origin_func;    // relocated original code (0 when not hooked)
} dobby_entry_state_t;

int DobbyQueryEntryState(void *address, dobby_entry_state_t *state);

// ---- dynamic binary instruction instrument --------------------------------
// full floating-point register pack (q8-q31) is enabled by default
typedef union _FPReg {
  __int128_t q;
  struct {
    double d1;
    double d2;
  } d;
  struct {
    float f1;
    float f2;
    float f3;
    float f4;
  } f;
} FPReg;

// register context
typedef struct {
  uint64_t reserved_0; // reserved padding
  uint64_t sp;

  uint64_t reserved_1; // reserved padding
  union {
    uint64_t x[29];
    struct {
      uint64_t x0, x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16, x17, x18, x19, x20, x21, x22,
          x23, x24, x25, x26, x27, x28;
    } regs;
  } general;

  uint64_t fp;
  uint64_t lr;

  union {
    FPReg q[32];
    struct {
      FPReg q0, q1, q2, q3, q4, q5, q6, q7;
      // [!!! READ ME !!!]
      // for Arm64, can't access q8 - q31, unless you enable full floating-point register pack
      FPReg q8, q9, q10, q11, q12, q13, q14, q15, q16, q17, q18, q19, q20, q21, q22, q23, q24, q25, q26, q27, q28, q29,
          q30, q31;
    } regs;
  } floating;
} DobbyRegisterContext;

typedef void (*dobby_instrument_callback_t)(void *address, DobbyRegisterContext *ctx);
int DobbyInstrument(void *address, dobby_instrument_callback_t pre_handler);
// Like DobbyInstrument, plus post_handler: invoked after the instrumented
// function returns, with the full register context of the return point. The
// callback receives the same DobbyRegisterContext as pre_handler: integer
// return value in x0 (rewriting it overrides the caller-visible result), SIMD
// return value in q0.d.d1 (rewritable the same way), and all of x0-x30 /
// q0-q31 (full floating-point pack, on by default) plus fp/lr/sp available;
// ctx->lr holds the real caller's return address. pre/post: at least one must
// be non-NULL.
// Note: onLeave redirects `ret` instructions inside the relocated prologue to a
// leave closure; the entry is scanned to the first `ret` (bounded 64 bytes) as
// the relocation window, so small functions without embedded data inside the
// window are supported. ctx->lr is read from the invoking thread's own stack
// frame: safe under concurrent calls.
int DobbyInstrumentEx(void *address, dobby_instrument_callback_t pre_handler, dobby_instrument_callback_t post_handler);

// ---- symbol resolver ------------------------------------------------------
void *DobbySymbolResolver(const char *image_name, const char *symbol_name);

// ---- xDL-backed low-level symbol APIs (DobbyXdl*) ----------------------
// Thin C wrappers over the vendored xDL (https://github.com/hexhacking/xDL,
// MIT, at source/plugin/SymbolResolver/xdl) exposing its full public surface to
// library users, with the same semantics as the xDL originals.
// Handles returned by DobbyXdlOpen/DobbyXdlOpen2 are opaque; release them with
// DobbyXdlClose, which returns the force-loaded linker handle (NULL unless the
// object was force-loaded) that the caller must dlclose().
// The addr/addr4 cache is caller-owned: init to NULL, pass &cache, and finish
// with DobbyXdlAddrClean.

#define DOBBY_XDL_DEFAULT            0x00 // default flags
#define DOBBY_XDL_TRY_FORCE_LOAD     0x01 // dlopen if not already loaded
#define DOBBY_XDL_ALWAYS_FORCE_LOAD  0x02 // always dlopen
#define DOBBY_XDL_NON_SYM            0x01 // addr4: skip nearest-symbol lookup
#define DOBBY_XDL_FULL_PATHNAME      0x01 // iterate: resolve full pathnames
#define DOBBY_XDL_DI_DLINFO          1    // info request: fill DobbyXdlInfo

struct dl_phdr_info; // from <link.h>; forward-declared, include <link.h> to read fields

// address -> object/symbol info, layout-identical to xDL's xdl_info_t
typedef struct {
  const char *dli_fname; // pathname of the containing object
  void *dli_fbase;       // load bias of the containing object
  const char *dli_sname; // nearest symbol at or below the queried address
  void *dli_saddr;       // exact address of dli_sname
  size_t dli_ssize;      // size of dli_sname (xDL extension)
  const void *dlpi_phdr; // ELF program headers of the object (ElfW(Phdr) *)
  size_t dlpi_phnum;     // number of program headers
} DobbyXdlInfo;

void *DobbyXdlOpen(const char *filename, int flags);
void *DobbyXdlOpen2(struct dl_phdr_info *info);
void *DobbyXdlClose(void *handle);
void *DobbyXdlSym(void *handle, const char *symbol, size_t *symbol_size);
void *DobbyXdlDsym(void *handle, const char *symbol, size_t *symbol_size);
int DobbyXdlAddr(void *addr, DobbyXdlInfo *info, void **cache);
int DobbyXdlAddr4(void *addr, DobbyXdlInfo *info, void **cache, int flags);
void DobbyXdlAddrClean(void **cache);
int DobbyXdlIteratePhdr(int (*callback)(struct dl_phdr_info *, size_t, void *), void *data, int flags);
int DobbyXdlDlinfo(void *handle, int request, void *info);

// ---- GOT/PLT hook (DobbyGotHook*) --------------------------------------
// Overwrite the GOT slot(s) of an imported symbol inside a target module so
// that every call to that import from within the module goes to replace_func.
// Unlike DobbyHook (inline), this never modifies function code — it only
// rewrites a function pointer in the module's .got.
//
// module: name or path of the loaded library (e.g. "libfoo.so").
// symbol: the imported symbol to hook (e.g. "malloc").
// origin_func: optional; receives the original callee of the first GOT slot.
// handle: optional; opaque handle for DobbyGotUnhook.
// Returns 0 on success, -1 if the module/symbol is not found or the write fails.
int DobbyGotHook(const char *module, const char *symbol, dobby_func_ptr_t replace_func, dobby_func_ptr_t *origin_func, void **handle);
// Restore the hooked GOT slot(s) identified by `handle`.
int DobbyGotUnhook(void *handle);
// Register a GOT/PLT hook request and keep it tracked: applied to every loaded
// module whose name matches now, and re-applied automatically whenever a
// matching module is dlopen'd later (dlopen monitor). Unlike DobbyGotHook
// (one-shot), the request survives for future loads.
// origin_func: optional; receives the original callee of the first hooked slot
// (NULL if no matching module is loaded yet).
// handle: opaque task handle; release with DobbyGotUnhook.
// Returns 0 on success (registration recorded even if the module is not loaded
// yet), -1 on bad arguments.
int DobbyGotRegister(const char *module, const char *symbol, dobby_func_ptr_t replace_func, dobby_func_ptr_t *origin_func, void **handle);
// Re-scan all loaded modules and apply pending DobbyGotRegister requests to any
// matching module not yet hooked; also (re)arms the dlopen monitor. Returns 0.
int DobbyGotRefresh();

// ---- trampoline control ---------------------------------------------------
// use near branch (b xxx) instead of absolute indirect jump
void dobby_enable_near_branch_trampoline();
void dobby_disable_near_branch_trampoline();

// ---- version --------------------------------------------------------------
const char *DobbyGetVersion();

// ---- C convenience macro (original upstream) ------------------------------
#define install_hook_name(func_name, return_type, param_list...)                                                       \
  static return_type fake_##func_name(param_list);                                                                     \
  static return_type (*orig_##func_name)(param_list);                                                                  \
  /* __attribute__((constructor)) */ static void install_hook_##func_name(void *sym_addr) {                            \
    DobbyHook(sym_addr, (dobby_func_ptr_t)fake_##func_name, (dobby_func_ptr_t *)&orig_##func_name);                    \
    return;                                                                                                            \
  }                                                                                                                    \
  return_type fake_##func_name(param_list)

#ifdef __cplusplus
}
#endif

#ifdef __cplusplus
// ---- C++ type-safe cast helpers (pure header, thin wrappers over the C API;
// add no runtime behavior and cannot change hook semantics) --------------

#include <type_traits>

namespace dobby {

// safe cast helpers: avoid manual (dobby_func_ptr_t) / (void*) casts
template <typename Fn, typename = std::enable_if_t<std::is_function_v<std::remove_pointer_t<Fn>>>>
dobby_func_ptr_t func_ptr(Fn fn) {
  return reinterpret_cast<dobby_func_ptr_t>(fn);
}

template <typename Fn, typename = std::enable_if_t<std::is_function_v<std::remove_pointer_t<Fn>>>>
Fn func_cast(void *p) {
  return reinterpret_cast<Fn>(p);
}

} // namespace dobby
#endif // __cplusplus

#endif
