#include <jni.h>
#include <thread>
#include <chrono>
#include <dlfcn.h>
#include <android/log.h>
#include "Features.h"
#include "il2cpp/Il2CppApi.h"
#include "kitty/KittyMemory.h"
#include "dobby/dobby.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ModMenu", __VA_ARGS__)
#define TARGET_CLASS "dev.Scripts.ECS.Morpeh.Features.UnityViewFeature.Components.Position"

// ============================================================
// 04 - HOOKS
// أسماء الميثودات تحت (UpdatePosition / TakeDamage ...) يجب أن
// تطابق dump.cs لنسختك. إن اختلف الاسم غيّره هنا فقط.
// ============================================================

// ---- Position hook ----
// التوقيع الافتراضي لمكوّن Morpeh/ECS: الدالة تستلم pointer للمكوّن
// الذي حقول X/Y/Z في بدايته (struct blittable). نلتقطها كل frame.
struct PositionComponent { float x, y, z; }; // طابق ترتيب الحقول مع dump.cs
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
            // TODO: حرّك pos حسب إدخال WASD/اللمس - الإدخال يأتي من الـ Overlay
        }
    }
    if (orig_PositionUpdate) orig_PositionUpdate(self, method);
}

// ---- God Mode hook ----
// نفترض دالة ضرر ترجع float/int مقدار الضرر أو void تستلم مقداراً.
// النسخة تحت: TakeDamage(float amount) -> نصفّر المقدار عند التفعيل.
// إن كانت دالتك ترجع قيمة، غيّر التوقيع وأعد 0 بدل استدعاء الأصل.
using TakeDamage_t = void (*)(void* self, float amount, const MethodInfo* method);
static TakeDamage_t orig_TakeDamage = nullptr;
static void hook_TakeDamage(void* self, float amount, const MethodInfo* method) {
    if (F.godMode) return; // لا ضرر إطلاقاً
    if (orig_TakeDamage) orig_TakeDamage(self, amount, method);
}

// ---- أمثلة باتشات ذاكرة للميزات البسيطة (تُفعّل من الـ hack thread) ----
// ضع الأوفستات من dump.cs. 0 = غير مُعدّ بعد، فتُتخطى بأمان.
namespace Offsets {
    constexpr uintptr_t InfiniteAmmo = 0x0; // مثال: 0x1A2B3C
    constexpr uintptr_t RapidFire    = 0x0;
    constexpr uintptr_t NoRecoil     = 0x0;
    constexpr uintptr_t VehicleSpeed = 0x0;
    constexpr uintptr_t CameraFov    = 0x0;
}

static bool installHooks() {
    void* handle = nullptr;
    for (int i = 0; i < 60 && !handle; i++) { // انتظر libil2cpp.so حتى 60 ثانية
        handle = dlopen("libil2cpp.so", RTLD_NOLOAD | RTLD_NOW);
        if (!handle) std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    if (!handle || !il2cpp.init(handle)) { LOG("il2cpp init FAILED"); return false; }
    if (il2cpp.thread_attach) il2cpp.thread_attach(il2cpp.domain_get());

    // --- Find Position class المطلوب ---
    Il2CppClass* posClass = il2cpp.findClass(TARGET_CLASS);
    if (!posClass) { LOG("Class not found: %s (check dump.cs name/namespace)", TARGET_CLASS); return false; }
    LOG("Found class: %s", TARGET_CLASS);

    // --- Hook Position update ---
    // جرّب أسماء شائعة بالترتيب؛ أول اسم موجود في dump يُستعمل.
    const char* candidates[] = {"Update", "OnUpdate", "Execute", "UpdatePosition", "SetPosition"};
    for (const char* name : candidates) {
        const Il2CppMethod* m = il2cpp.class_get_method(posClass, name, -1);
        void* ptr = Il2CppApi::methodPointer(m);
        if (ptr) {
            if (DobbyHook(ptr, (void*)hook_PositionUpdate, (void**)&orig_PositionUpdate) == 0) {
                LOG("Hooked Position.%s @ %p", name, ptr); F.hooked = true; break;
            }
        }
    }

    // --- Hook Health / TakeDamage (God Mode) ---
    // غيّر اسم الكلاس/الدالة حسب dump.cs للعبتك:
    if (Il2CppClass* health = il2cpp.findClass("Health")) {
        const Il2CppMethod* m = il2cpp.class_get_method(health, "TakeDamage", 1);
        void* ptr = Il2CppApi::methodPointer(m);
        if (ptr) DobbyHook(ptr, (void*)hook_TakeDamage, (void**)&orig_TakeDamage);
    } else {
        LOG("Health class not set - ضع اسم كلاس الصحة من dump.cs في installHooks()");
    }
    return F.hooked.load();
}

static void hackThread() {
    installHooks();
    uintptr_t base = KittyMemory::getBaseAddress("libil2cpp.so");
    while (true) {
        // باتشات دورية خفيفة - تُكتب فقط عندما الأوفست مُعدّ والميزة مفعّلة
        if (base) {
            if (F.infiniteAmmo && Offsets::InfiniteAmmo) KittyMemory::writeValue<int>(base + Offsets::InfiniteAmmo, 9999);
            if (F.noRecoil && Offsets::NoRecoil)         KittyMemory::writeValue<float>(base + Offsets::NoRecoil, 0.f);
            if (F.vehicleSpeedOn && Offsets::VehicleSpeed) KittyMemory::writeValue<float>(base + Offsets::VehicleSpeed, F.vehicleSpeed.load());
            if (Offsets::CameraFov) KittyMemory::writeValue<float>(base + Offsets::CameraFov, F.fov.load());
            // ESP / NightVision / Skeleton: الرسم يتم في طبقة ESP منفصلة تقرأ
            // F.esp / F.espDistance / F.espBoxColor وقائمة الكيانات من الـ ECS world.
            // TODO: اربط قائمة الكيانات هنا حسب نظام لعبتك (Morpeh World/Filter).
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

// ============================================================
// JNI - يطابق NativeBridge.java
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
    jfloatArray a = env->NewFloatArray(3); float xyz[3]={F.playerPos.x,F.playerPos.y,F.playerPos.z};
    env->SetFloatArrayRegion(a,0,3,xyz); return a;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    std::thread(hackThread).detach();
    return JNI_VERSION_1_6;
}
}
