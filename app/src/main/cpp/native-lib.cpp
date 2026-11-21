// ============================================================
// native-lib.cpp - BOHA XVIP / ModMenuPro  (CRASH FIX v2)
// ------------------------------------------------------------
// Bug fixed: SIGSEGV, fault address 0x135, inside libmodmenu.so
// while calling into libil2cpp.so at game startup.
//
// Root cause in the old file (NOT just "called too early"):
//   1. il2cpp.domain_get() result was passed straight into
//      il2cpp_thread_attach() with NO null check. Before Unity
//      finishes runtime init, domain_get() returns nullptr and
//      thread_attach(nullptr) dereferences it inside libil2cpp.
//   2. Il2CppApi::findClass() called domain_get_assemblies()
//      and class_from_name() with no null checks on the domain,
//      the assemblies array, the image, or the count - a null
//      base + a struct offset is exactly a 0x135-style fault.
//   3. "Library mapped" was treated as "runtime ready". There
//      was no settle wait and no retry: classes/metadata are
//      registered AFTER libil2cpp.so appears in memory.
//
// Fix in this file:
//   * JNI_OnLoad does NOTHING except spawn a detached thread.
//   * The thread waits for libil2cpp.so in /proc/self/maps AND
//     via dlopen(RTLD_NOLOAD), then sleeps 3 more seconds.
//   * domain_get() is POLLED until it returns non-null.
//   * Every Il2Cpp pointer is null-checked before every call.
//     Class lookup is a local safe implementation with retries,
//     it does NOT use the unchecked Il2CppApi::findClass().
//   * A method pointer is validated (non-null AND inside the
//     libil2cpp.so mapping) before it is handed to DobbyHook,
//     and DobbyHook's return code is checked.
//   * Whole init is wrapped in try/catch, and every failure
//     path only logs (LOGE) and exits the thread - it never
//     dereferences a bad pointer. NOTE: try/catch cannot catch
//     a SIGSEGV; the null/range checks are what prevent it.
// ============================================================
#include <jni.h>
#include <thread>
#include <chrono>
#include <atomic>
#include <cinttypes>
#include <cstring>
#include <cstdio>
#include <dlfcn.h>
#include <unistd.h>
#include <android/log.h>
#include "Features.h"
#include "il2cpp/Il2CppApi.h"
#include "kitty/KittyMemory.h"
#include "dobby/dobby.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "ModMenu", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ModMenu", __VA_ARGS__)
#define TARGET_CLASS "dev.Scripts.ECS.Morpeh.Features.UnityViewFeature.Components.Position"

// ============================================================
// HOOKS (unchanged behaviour - only called after safe init)
// ============================================================
struct PositionComponent { float x, y, z; }; // match field order with dump.cs
using PositionUpdate_t = void (*)(void* self, const MethodInfo* method);
static PositionUpdate_t orig_PositionUpdate = nullptr;

static void hook_PositionUpdate(void* self, const MethodInfo* method) {
    if (self) {
        auto* pos = reinterpret_cast<PositionComponent*>(self);
        F.playerPos = {pos->x, pos->y, pos->z};

        if (F.teleportRequested && F.hasWaypoint) {
            pos->x = F.waypoint.x; pos->y = F.waypoint.y; pos->z = F.waypoint.z;
            F.teleportRequested = false;
        }
        if (F.freeFly) {
            // TODO: move pos from overlay input (unchanged from v1)
        }
    }
    if (orig_PositionUpdate) orig_PositionUpdate(self, method);
}

using TakeDamage_t = void (*)(void* self, float amount, const MethodInfo* method);
static TakeDamage_t orig_TakeDamage = nullptr;
static void hook_TakeDamage(void* self, float amount, const MethodInfo* method) {
    if (F.godMode) return; // no damage at all
    if (orig_TakeDamage) orig_TakeDamage(self, amount, method);
}

// Memory-patch offsets from dump.cs. 0 = not set yet, skipped safely.
namespace Offsets {
    constexpr uintptr_t InfiniteAmmo = 0x0;
    constexpr uintptr_t RapidFire    = 0x0;
    constexpr uintptr_t NoRecoil     = 0x0;
    constexpr uintptr_t VehicleSpeed = 0x0;
    constexpr uintptr_t CameraFov    = 0x0;
}

// ============================================================
// SAFE IL2CPP STARTUP
// ============================================================
static std::atomic<bool> g_initStarted{false};

// Range of libil2cpp.so in this process, from /proc/self/maps.
// Used to reject garbage method pointers before DobbyHook.
static bool getLibRange(const char* lib, uintptr_t& outBase, uintptr_t& outEnd) {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[512];
    bool found = false;
    outBase = 0; outEnd = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, lib)) continue;
        uintptr_t start = 0, end = 0;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &start, &end) == 2) {
            if (!found || start < outBase) outBase = start;
            if (!found || end > outEnd)   outEnd = end;
            found = true;
        }
    }
    fclose(f);
    return found && outEnd > outBase;
}

// Wait until libil2cpp.so is BOTH mapped (maps) AND openable
// (dlopen RTLD_NOLOAD). Returns the handle, or nullptr on timeout.
// Never calls any il2cpp_* function.
static void* waitForIl2Cpp(int timeoutSeconds = 90) {
    const int attempts = timeoutSeconds * 2; // every 500 ms
    for (int i = 0; i < attempts; i++) {
        uintptr_t base = KittyMemory::getBaseAddress("libil2cpp.so");
        if (base != 0) {
            void* handle = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
            if (handle) {
                LOGI("[init] libil2cpp.so found (base=0x%" PRIxPTR ", attempt %d)", base, i + 1);
                return handle; // do NOT dlclose - dlsym results must stay valid
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    LOGE("[init] libil2cpp.so not loaded after %ds - aborting init, game continues without hooks", timeoutSeconds);
    return nullptr;
}

// Poll domain_get() until the Il2Cpp runtime is actually up.
// Calling ANY other il2cpp_* function before this returns
// non-null is what produced the startup SIGSEGV.
static Il2CppDomain* waitForDomain(int timeoutSeconds = 30) {
    if (!il2cpp.domain_get) { LOGE("[init] il2cpp_domain_get symbol is null"); return nullptr; }
    const int attempts = timeoutSeconds * 2;
    for (int i = 0; i < attempts; i++) {
        Il2CppDomain* domain = il2cpp.domain_get();
        if (domain) {
            LOGI("[init] il2cpp domain ready (attempt %d)", i + 1);
            return domain;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    LOGE("[init] il2cpp_domain_get() stayed null for %ds - aborting, no hooks installed", timeoutSeconds);
    return nullptr;
}

// Safe replacement for Il2CppApi::findClass(): same search
// (split full name at the last '.'), but EVERY step is checked.
static Il2CppClass* safeFindClass(Il2CppDomain* domain, const char* fullName) {
    if (!domain || !fullName) return nullptr;
    if (!il2cpp.domain_get_assemblies || !il2cpp.assembly_get_image || !il2cpp.class_from_name) {
        LOGE("[init] safeFindClass: a required il2cpp symbol is null");
        return nullptr;
    }
    size_t count = 0;
    const Il2CppAssembly** assemblies = il2cpp.domain_get_assemblies(domain, &count);
    if (!assemblies || count == 0 || count > 2048) {
        LOGE("[init] safeFindClass: bad assemblies (ptr=%p count=%zu)", (void*)assemblies, count);
        return nullptr;
    }
    const char* dot = strrchr(fullName, '.');
    if (!dot || dot == fullName || !*(dot + 1)) {
        LOGE("[init] safeFindClass: bad class name '%s'", fullName);
        return nullptr;
    }
    char ns[256], name[128];
    size_t nsLen = (size_t)(dot - fullName);
    if (nsLen >= sizeof(ns)) nsLen = sizeof(ns) - 1;
    memcpy(ns, fullName, nsLen); ns[nsLen] = '\0';
    strncpy(name, dot + 1, sizeof(name) - 1); name[sizeof(name) - 1] = '\0';

    for (size_t i = 0; i < count; i++) {
        if (!assemblies[i]) continue;
        const Il2CppImage* image = il2cpp.assembly_get_image(assemblies[i]);
        if (!image) continue;
        Il2CppClass* klass = il2cpp.class_from_name(image, ns, name);
        if (klass) return klass;
    }
    return nullptr;
}

// Validate + install one hook. Returns true only on Dobby success.
static bool safeHook(Il2CppClass* klass, const char* methodName, int args,
                     void* replaceFn, void** outOrig,
                     uintptr_t libBase, uintptr_t libEnd, const char* label) {
    if (!klass || !methodName || !replaceFn || !outOrig) {
        LOGE("[hook] %s: null argument, skipped", label);
        return false;
    }
    if (!il2cpp.class_get_method) { LOGE("[hook] %s: class_get_method symbol is null", label); return false; }

    const Il2CppMethod* m = il2cpp.class_get_method(klass, methodName, args);
    if (!m) { LOGI("[hook] %s: method '%s' not found", label, methodName); return false; }

    void* ptr = Il2CppApi::methodPointer(m); // checks m != nullptr internally
    if (!ptr) { LOGE("[hook] %s: '%s' has a null method pointer, skipped", label, methodName); return false; }
    if ((uintptr_t)ptr < libBase || (uintptr_t)ptr >= libEnd) {
        LOGE("[hook] %s: '%s' pointer %p is OUTSIDE libil2cpp.so range, refused (stale metadata?)", label, methodName, ptr);
        return false;
    }
    int rc = DobbyHook(ptr, replaceFn, outOrig);
    if (rc != 0) {
        LOGE("[hook] %s: DobbyHook('%s') failed rc=%d (is the real libdobby linked, not the stub?)", label, methodName, rc);
        *outOrig = nullptr;
        return false;
    }
    LOGI("[hook] %s: hooked '%s' @ %p", label, methodName, ptr);
    return true;
}

static bool installHooksSafely() {
    try {
        // 1) Wait for the library itself - no il2cpp calls yet.
        void* handle = waitForIl2Cpp();
        if (!handle) return false;

        // 2) Settle wait: library mapped != Unity runtime ready.
        LOGI("[init] waiting 3s for Unity/Il2Cpp runtime to settle...");
        std::this_thread::sleep_for(std::chrono::seconds(3));

        // 3) Resolve symbols, verify ALL of them before calling any.
        if (!il2cpp.init(handle)) {
            LOGE("[init] il2cpp.init FAILED (missing symbols) - aborting, game continues without hooks");
            return false;
        }
        if (!il2cpp.domain_get || !il2cpp.domain_get_assemblies ||
            !il2cpp.assembly_get_image || !il2cpp.class_from_name || !il2cpp.class_get_method) {
            LOGE("[init] a required il2cpp symbol resolved to null - aborting");
            return false;
        }

        // 4) Poll for a live domain. Nothing else runs before this.
        Il2CppDomain* domain = waitForDomain();
        if (!domain) return false;

        // 5) Attach THIS background thread to the Il2Cpp runtime.
        //    (Old code called this with an unchecked domain.)
        if (il2cpp.thread_attach) {
            void* attached = il2cpp.thread_attach(domain);
            if (!attached) {
                LOGE("[init] il2cpp_thread_attach returned null - waiting 2s and continuing cautiously");
                std::this_thread::sleep_for(std::chrono::seconds(2));
            }
        } else {
            LOGI("[init] il2cpp_thread_attach not exported, continuing without it");
        }

        // 6) Method-pointer validation range.
        uintptr_t libBase = 0, libEnd = 0;
        if (!getLibRange("libil2cpp.so", libBase, libEnd)) {
            LOGE("[init] could not read libil2cpp.so range from maps - aborting hooks");
            return false;
        }

        // 7) Class lookup with RETRY - metadata registers late.
        //    A null here is logged, never dereferenced.
        Il2CppClass* posClass = nullptr;
        for (int i = 0; i < 30 && !posClass; i++) {
            posClass = safeFindClass(domain, TARGET_CLASS);
            if (!posClass) std::this_thread::sleep_for(std::chrono::seconds(1));
        }
        if (!posClass) {
            LOGE("[init] class not found after 30 retries: %s (check dump.cs name/namespace) - no Position hook", TARGET_CLASS);
            return false;
        }
        LOGI("[init] found class: %s", TARGET_CLASS);

        // 8) Position hook - first candidate name that really hooks.
        const char* candidates[] = {"Update", "OnUpdate", "Execute", "UpdatePosition", "SetPosition"};
        for (const char* name : candidates) {
            if (safeHook(posClass, name, -1, (void*)hook_PositionUpdate,
                         (void**)&orig_PositionUpdate, libBase, libEnd, "Position")) {
                F.hooked = true;
                break;
            }
        }
        if (!F.hooked) LOGE("[init] no Position update method could be hooked (names in dump.cs differ?)");

        // 9) God Mode hook - fully optional, failure only logs.
        Il2CppClass* health = safeFindClass(domain, "Health");
        if (health) {
            safeHook(health, "TakeDamage", 1, (void*)hook_TakeDamage,
                     (void**)&orig_TakeDamage, libBase, libEnd, "Health");
        } else {
            LOGI("[init] 'Health' class not found - set the real name from dump.cs; God Mode hook skipped");
        }
        return F.hooked.load();
    } catch (const std::exception& e) {
        LOGE("[init] exception during init: %s - hooks disabled, game continues", e.what());
        return false;
    } catch (...) {
        LOGE("[init] unknown exception during init - hooks disabled, game continues");
        return false;
    }
}

// Periodic memory patches - unchanged, but each write is
// already gated on a non-zero base AND a non-zero offset.
static void patchLoop() {
    while (true) {
        try {
            uintptr_t base = KittyMemory::getBaseAddress("libil2cpp.so");
            if (base) {
                if (F.infiniteAmmo && Offsets::InfiniteAmmo) KittyMemory::writeValue<int>(base + Offsets::InfiniteAmmo, 9999);
                if (F.noRecoil && Offsets::NoRecoil)         KittyMemory::writeValue<float>(base + Offsets::NoRecoil, 0.f);
                if (F.vehicleSpeedOn && Offsets::VehicleSpeed) KittyMemory::writeValue<float>(base + Offsets::VehicleSpeed, F.vehicleSpeed.load());
                if (Offsets::CameraFov) KittyMemory::writeValue<float>(base + Offsets::CameraFov, F.fov.load());
            }
        } catch (...) {
            LOGE("[patch] exception in patch loop iteration, continuing");
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

static void hackThread() {
    LOGI("[init] background init thread started (pid=%d)", getpid());
    installHooksSafely();
    patchLoop(); // never returns
}

// ============================================================
// JNI - identical signatures to NativeBridge.java (19 methods)
// ============================================================
#define JNI_FN(name) Java_com_bonika_modmenu_NativeBridge_##name
extern "C" {
JNIEXPORT void JNICALL JNI_FN(setEspEnabled)(JNIEnv*, jclass, jboolean v){ F.esp=v; }
JNIEXPORT void JNICALL JNI_FN(setEspDistance)(JNIEnv*, jclass, jfloat v){ F.espDistance=v; }
JNIEXPORT void JNICALL JNI_FN(setSkeletonEsp)(JNIEnv*, jclass, jboolean v){ F.skeleton=v; }
JNIEXPORT void JNICALL JNI_FN(setShowNpcs)(JNIEnv*, jclass, jboolean v){ F.showNpcs=v; }
JNIEXPORT void JNICALL JNI_FN(setShowVehicles)(JNIEnv*, jclass, jboolean v){ F.showVehicles=v; }
JNIEXPORT void JNICALL JNI_FN(setEspBoxColor)(JNIEnv*, jclass, jint v){ F.espBoxColor=v; }
JNIEXPORT void JNICALL JNI_FN(setGodMode)(JNIEnv*, jclass, jboolean v){ F.godMode=v; }
JNIEXPORT void JNICALL JNI_FN(setInfiniteAmmo)(JNIEnv*, jclass, jboolean v){ F.infiniteAmmo=v; }
JNIEXPORT void JNICALL JNI_FN(setRapidFire)(JNIEnv*, jclass, jboolean v){ F.rapidFire=v; }
JNIEXPORT void JNICALL JNI_FN(setNoRecoil)(JNIEnv*, jclass, jboolean v){ F.noRecoil=v; }
JNIEXPORT void JNICALL JNI_FN(setVehicleSpeedEnabled)(JNIEnv*, jclass, jboolean v){ F.vehicleSpeedOn=v; }
JNIEXPORT void JNICALL JNI_FN(setVehicleSpeedMultiplier)(JNIEnv*, jclass, jfloat v){ F.vehicleSpeed=v; }
JNIEXPORT void JNICALL JNI_FN(teleportToWaypoint)(JNIEnv*, jclass){ F.teleportRequested=true; }
JNIEXPORT void JNICALL JNI_FN(setInstantBrake)(JNIEnv*, jclass, jboolean v){ F.instantBrake=v; }
JNIEXPORT void JNICALL JNI_FN(setFov)(JNIEnv*, jclass, jfloat v){ F.fov=v; }
JNIEXPORT void JNICALL JNI_FN(setNightVision)(JNIEnv*, jclass, jboolean v){ F.nightVision=v; }
JNIEXPORT void JNICALL JNI_FN(setFreeFly)(JNIEnv*, jclass, jboolean v){ F.freeFly=v; }
JNIEXPORT jboolean JNICALL JNI_FN(isHooked)(JNIEnv*, jclass){ return F.hooked; }
JNIEXPORT jfloatArray JNICALL JNI_FN(getPlayerPosition)(JNIEnv* env, jclass){
    if (!env) return nullptr;
    jfloatArray a = env->NewFloatArray(3);
    if (!a) return nullptr;
    float xyz[3]={F.playerPos.x,F.playerPos.y,F.playerPos.z};
    env->SetFloatArrayRegion(a,0,3,xyz); return a;
}

// JNI_OnLoad: spawn the worker and return IMMEDIATELY.
// No dlopen, no dlsym, no il2cpp_* call happens here - the
// game's libil2cpp.so may not even be mapped at this point.
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    bool expected = false;
    if (g_initStarted.compare_exchange_strong(expected, true)) {
        try {
            std::thread(hackThread).detach();
            LOGI("[init] JNI_OnLoad: background thread spawned, returning immediately");
        } catch (...) {
            LOGE("[init] JNI_OnLoad: failed to spawn thread - menu toggles will work, hooks will not");
            g_initStarted = false;
        }
    } else {
        LOGI("[init] JNI_OnLoad called again - init already started, ignoring");
    }
    return JNI_VERSION_1_6;
}
} // extern "C"
