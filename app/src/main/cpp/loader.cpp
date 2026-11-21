// ============================================================
// loader.cpp - libloader.so
// ============================================================
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "RemapTools.h"

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Loader", __VA_ARGS__)

// خيط إخفاء المكتبتين - ينتظر 15 ثانية لضمان ربط جميع دوال JNI
static void* hideThread(void*) {
    sleep(15);  // انتظار 15 ثانية قبل الإخفاء
    
    // إخفاء libmodmenu.so أولاً (الأهم)
    RemapTools::remapLibrary("libmodmenu.so");
    
    // انتظار إضافي قبل إخفاء المحمل
    sleep(2);
    RemapTools::remapLibrary("libloader.so");
    
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    LOADER_LOG("[*] loadModMenu called");

    // 1. التحقق من وجود libmodmenu.so
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

    // 3. بدء خيط الإخفاء المتأخر (15 ثانية)
    pthread_t t;
    pthread_create(&t, nullptr, hideThread, nullptr);
    pthread_detach(t);
    
    LOADER_LOG("[*] Hide thread started, will hide libraries in 15 seconds");
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    LOADER_LOG("[*] libloader JNI_OnLoad");
    return JNI_VERSION_1_6;
}
