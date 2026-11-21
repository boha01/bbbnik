// ============================================================
// loader.cpp - libloader.so
// وظيفتها الوحيدة: تحميل libmodmenu.so ثم إخفاء الاثنين.
// لا تحتوي على أي منطق مود.
// ============================================================
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "RemapTools.h"

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Loader", __VA_ARGS__)

// خيط إخفاء المحمل نفسه - ينتظر 3 ثوانٍ لضمان عدم استخدامه
static void* hideLoaderThread(void*) {
    sleep(3);
    RemapTools::remapLibrary("libloader.so");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    LOADER_LOG("[*] Loading libmodmenu.so...");

    // 1. تحميل المكتبة الرئيسية
    void* handle = dlopen("libmodmenu.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOADER_LOG("[-] dlopen(libmodmenu.so) failed: %s", dlerror());
        return;
    }
    LOADER_LOG("[+] libmodmenu.so loaded at %p", handle);

    // 2. استدعاء دالة التهيئة أولاً (دع المكتبة تكمل تهيئتها)
    typedef void (*InitFn)(JNIEnv*, jclass);
    InitFn init = (InitFn)dlsym(handle, "Java_com_bonika_modmenu_NativeBridge_initNative");
    if (init) {
        init(env, clazz);
        LOADER_LOG("[+] libmodmenu initNative called");
    } else {
        LOADER_LOG("[!] initNative not found in libmodmenu");
    }

    // 3. انتظار قصير لضمان اكتمال أي constructors داخلية
    sleep(1);

    // 4. الآن قم بإخفاء المكتبة الرئيسية (بعد اكتمال التهيئة)
    RemapTools::remapLibrary("libmodmenu.so");

    // 5. إخفاء المحمل نفسه في خيط منفصل
    pthread_t t;
    pthread_create(&t, nullptr, hideLoaderThread, nullptr);
    pthread_detach(t);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    LOADER_LOG("[*] libloader JNI_OnLoad");
    return JNI_VERSION_1_6;
}
