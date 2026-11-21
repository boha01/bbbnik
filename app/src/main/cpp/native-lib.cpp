// ============================================================
// native-lib.cpp - BOHA XVIP / ModMenuPro (Stealth Edition)
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
// HOOKS
// ============================================================
struct PositionComponent { float x, y, z; };
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
    }
    if (orig_PositionUpdate) orig_PositionUpdate(self, method);
}

using TakeDamage_t = void (*)(void* self, float amount, const MethodInfo* method);
static TakeDamage_t orig_TakeDamage = nullptr;
static void hook_TakeDamage(void* self, float amount, const MethodInfo* method) {
    if (F.godMode) return;
    if (orig_TakeDamage) orig_TakeDamage(self, amount, method);
}

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

static void* waitForIl2Cpp(int timeoutSeconds = 90) {
    const int attempts = timeoutSeconds * 2;
    for (int i = 0; i < attempts; i++) {
        uintptr_t base = KittyMemory::getBaseAddress("libil2cpp.so");
        if (base != 0) {
            void* handle = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
            if (handle) {
                LOGI("[init] libil2cpp.so found (base=0x%" PRIxPTR ", attempt %d)", base, i + 1);
                return handle;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    LOGE("[init] libil2cpp.so not loaded after %ds", timeoutSeconds);
    return nullptr;
}

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
    LOGE("[init] il2cpp_domain_get() stayed null for %ds", timeoutSeconds);
    return nullptr;
}

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

    void* ptr = Il2CppApi::methodPointer(m);
    if (!ptr) { LOGE("[hook] %s: '%s' has a null method pointer, skipped", label, methodName); return false; }
    if ((uintptr_t)ptr < libBase || (uintptr_t)ptr >= libEnd) {
        LOGE("[hook] %s: '%s' pointer %p is OUTSIDE libil2cpp.so range, refused", label, methodName, ptr);
        return false;
    }
    int rc = DobbyHook(ptr, replaceFn, outOrig);
    if (rc != 0) {
        LOGE("[hook] %s: DobbyHook('%s') failed rc=%d", label, methodName, rc);
        *outOrig = nullptr;
        return false;
    }
    LOGI("[hook] %s: hooked '%s' @ %p", label, methodName, ptr);
    return true;
}

static bool installHooksSafely() {
    try {
        void* handle = waitForIl2Cpp();
        if (!handle) return false;

        LOGI("[init] waiting 3s for Unity/Il2Cpp runtime to settle...");
        std::this_thread::sleep_for(std::chrono::seconds(3));

        if (!il2cpp.init(handle)) {
            LOGE("[init] il2cpp.init FAILED - aborting");
            return false;
        }
        if (!il2cpp.domain_get || !il2cpp.domain_get_assemblies ||
            !il2cpp.assembly_get_image || !il2cpp.class_from_name || !il2cpp.class_get_method) {
            LOGE("[init] a required il2cpp symbol resolved to null - aborting");
            return false;
        }

        Il2CppDomain* domain = waitForDomain();
        if (!domain) return false;

        if (il2cpp.thread_attach) {
            void* attached = il2cpp.thread_attach(domain);
            if (!attached) {
                LOGE("[init] il2cpp_thread_attach returned null - waiting 2s");
                std::this_thread::sleep_for(std::chrono::seconds(2));
            }
        }

        uintptr_t libBase = 0, libEnd = 0;
        if (!getLibRange("libil2cpp.so", libBase, libEnd)) {
            LOGE("[init] could not read libil2cpp.so range - aborting hooks");
            return false;
        }

        Il2CppClass* posClass = nullptr;
        for (int i = 0; i < 30 && !posClass; i++) {
            posClass = safeFindClass(domain, TARGET_CLASS);
            if (!posClass) std::this_thread::sleep_for(std::chrono::seconds(1));
        }
        if (!posClass) {
            LOGE("[init] class not found after 30 retries: %s", TARGET_CLASS);
            return false;
        }
        LOGI("[init] found class: %s", TARGET_CLASS);

        const char* candidates[] = {"Update", "OnUpdate", "Execute", "UpdatePosition", "SetPosition"};
        for (const char* name : candidates) {
            if (safeHook(posClass, name, -1, (void*)hook_PositionUpdate,
                         (void**)&orig_PositionUpdate, libBase, libEnd, "Position")) {
                F.hooked = true;
                break;
            }
        }
        if (!F.hooked) LOGE("[init] no Position update method could be hooked");

        Il2CppClass* health = safeFindClass(domain, "Health");
        if (health) {
            safeHook(health, "TakeDamage", 1, (void*)hook_TakeDamage,
                     (void**)&orig_TakeDamage, libBase, libEnd, "Health");
        } else {
            LOGI("[init] 'Health' class not found - God Mode hook skipped");
        }
        return F.hooked.load();
    } catch (const std::exception& e) {
        LOGE("[init] exception during init: %s - hooks disabled", e.what());
        return false;
    } catch (...) {
        LOGE("[init] unknown exception during init - hooks disabled");
        return false;
    }
}

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
    patchLoop();
}

// ============================================================
// JNI - NativeBridge
// ============================================================
#define JNI_FN(name) Java_com_bonika_modmenu_NativeBridge_##name
extern "C" {

// دالة التهيئة الجديدة التي يستدعيها libloader
JNIEXPORT void JNICALL JNI_FN(initNative)(JNIEnv*, jclass) {
    bool expected = false;
    if (g_initStarted.compare_exchange_strong(expected, true)) {
        try {
            std::thread(hackThread).detach();
            LOGI("[init] initNative: background thread spawned");
        } catch (...) {
            LOGE("[init] initNative: failed to spawn thread");
            g_initStarted = false;
        }
    } else {
        LOGI("[init] initNative called again - already started");
    }
}

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

// JNI_OnLoad لم يعد يستخدم للتهيئة - libloader يستدعي initNative بدلاً منه
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    LOGI("[init] libmodmenu JNI_OnLoad (no init here - loader calls initNative)");
    return JNI_VERSION_1_6;
}
} // extern "C"
