// ============================================================
// loader.cpp - libloader.so
// ============================================================
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include "RemapTools.h"

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Loader", __VA_ARGS__)

// دالة للتحقق من وجود مكتبة معينة في خريطة الذاكرة
static bool isLibraryLoaded(const char* libName) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;
    char line[512];
    bool found = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, libName)) { found = true; break; }
    }
    fclose(fp);
    return found;
}

// خيط الإخفاء - ينتظر 20 ثانية لضمان استقرار اللعبة
static void* hideThread(void*) {
    sleep(20); 
    LOADER_LOG("[*] Attempting to hide libraries...");
    RemapTools::remapLibrary("libmodmenu.so");
    sleep(2);
    RemapTools::remapLibrary("libloader.so");
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    LOADER_LOG("[*] loadModMenu called. Waiting for libil2cpp.so...");

    // 1. انتظار ظهور libil2cpp.so في الذاكرة (بحد أقصى 60 ثانية)
    int waitCount = 0;
    while (!isLibraryLoaded("libil2cpp.so") && waitCount < 60) {
        sleep(1);
        waitCount++;
    }

    if (waitCount >= 60) {
        LOADER_LOG("[-] Timeout: libil2cpp.so not found. Aborting mod init.");
        return;
    }
    LOADER_LOG("[+] libil2cpp.so detected in memory after %d seconds", waitCount);

    // 2. انتظار إضافي لضمان تهيئة Unity بالكامل (لتجنب NULL ptr)
    sleep(5);

    // 3. تحميل المكتبة الرئيسية
    void* handle = dlopen("libmodmenu.so", RTLD_NOLOAD | RTLD_NOW);
    if (!handle) {
        LOADER_LOG("[-] dlopen(libmodmenu.so) failed!");
        return;
    }
    LOADER_LOG("[+] libmodmenu.so loaded at %p", handle);

    // 4. استدعاء initNative
    typedef void (*InitFn)(JNIEnv*, jclass);
    InitFn init = (InitFn)dlsym(handle, "Java_com_bonika_modmenu_NativeBridge_initNative");
    if (init) {
        init(env, clazz);
        LOADER_LOG("[+] initNative called");
    } else {
        LOADER_LOG("[!] initNative not found in libmodmenu");
    }

    // 5. بدء خيط الإخفاء المتأخر (بعد 20 ثانية من الآن)
    pthread_t t;
    pthread_create(&t, nullptr, hideThread, nullptr);
    pthread_detach(t);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    LOADER_LOG("[*] libloader JNI_OnLoad");
    return JNI_VERSION_1_6;
}
