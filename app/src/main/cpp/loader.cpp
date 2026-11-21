// ============================================================
// loader.cpp - libloader.so
// وظيفتها الوحيدة: استدعاء initNative من libmodmenu.so ثم إخفاء الاثنين.
// libmodmenu.so يتم تحميلها مسبقاً عبر System.loadLibrary في Java.
// ============================================================
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "RemapTools.h"

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Loader", __VA_ARGS__)

static void* hideLoaderThread(void*) {
    sleep(3);
    RemapTools::remapLibrary("libloader.so");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    LOADER_LOG("[*] loadModMenu called");

    // 1. libmodmenu.so تم تحميلها بالفعل عبر System.loadLibrary
    void* handle = dlopen("libmodmenu.so", RTLD_NOLOAD | RTLD_NOW);
    if (!handle) {
        LOADER_LOG("[-] libmodmenu.so not loaded!");
        return;
    }
    LOADER_LOG("[+] libmodmenu.so already loaded at %p", handle);

    // 2. استدعاء initNative من libmodmenu.so
    typedef void (*InitFn)(JNIEnv*, jclass);
    InitFn init = (InitFn)dlsym(handle, "Java_com_bonika_modmenu_NativeBridge_initNative");
    if (init) {
        init(env, clazz);
        LOADER_LOG("[+] initNative called");
    } else {
        LOADER_LOG("[!] initNative not found in libmodmenu");
    }

    // 3. انتظار قصير لضمان اكتمال التهيئة
    sleep(1);

    // 4. إخفاء libmodmenu.so
    RemapTools::remapLibrary("libmodmenu.so");

    // 5. إخفاء libloader.so في خيط منفصل
    pthread_t t;
    pthread_create(&t, nullptr, hideLoaderThread, nullptr);
    pthread_detach(t);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    LOADER_LOG("[*] libloader JNI_OnLoad");
    return JNI_VERSION_1_6;
}
