#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include "RemapTools.h"

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Loader", __VA_ARGS__)

static JavaVM* g_vm = nullptr;

// دالة للتحقق من وجود مكتبة في خريطة الذاكرة
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

// الخيط الخلفي الذي سينتظر بدون أن يجمد واجهة المود مينيو
static void* initThread(void* arg) {
    JNIEnv* env = nullptr;
    jclass clazz = (jclass)arg;

    LOADER_LOG("[*] Background thread started. Waiting for libil2cpp.so...");

    // 1. انتظار libil2cpp.so (بحد أقصى 60 ثانية)
    int waitCount = 0;
    while (!isLibraryLoaded("libil2cpp.so") && waitCount < 60) {
        sleep(1);
        waitCount++;
    }

    if (waitCount >= 60) {
        LOADER_LOG("[-] Timeout: libil2cpp.so not found. Aborting mod init.");
        return nullptr;
    }
    LOADER_LOG("[+] libil2cpp.so detected in memory after %d seconds", waitCount);

    // 2. انتظار إضافي لضمان تهيئة Unity بالكامل
    sleep(5);

    // 3. تحميل المكتبة الرئيسية
    void* handle = dlopen("libmodmenu.so", RTLD_NOLOAD | RTLD_NOW);
    if (!handle) {
        LOADER_LOG("[-] dlopen(libmodmenu.so) failed!");
        return nullptr;
    }

    // 4. استدعاء initNative
    typedef void (*InitFn)(JNIEnv*, jclass);
    InitFn init = (InitFn)dlsym(handle, "Java_com_bonika_modmenu_NativeBridge_initNative");
    if (init) {
        if (g_vm) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
                init(env, clazz);
                LOADER_LOG("[+] initNative called successfully");
                g_vm->DetachCurrentThread();
            } else {
                LOADER_LOG("[-] Failed to attach thread to JVM");
            }
        } else {
            LOADER_LOG("[-] JavaVM is null, cannot call initNative");
        }
    } else {
        LOADER_LOG("[-] initNative function not found in libmodmenu.so");
    }

    // 5. الإخفاء المتأخر (بعد 20 ثانية إضافية لضمان استقرار اللعبة)
    sleep(20);
    LOADER_LOG("[*] Attempting to hide libraries...");
    RemapTools::remapLibrary("libmodmenu.so");
    sleep(2);
    RemapTools::remapLibrary("libloader.so");

    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    LOADER_LOG("[*] loadModMenu called, starting background init thread");

    // بدء الخيط الخلفي فوراً والعودة (بدون انتظار)
    pthread_t t;
    pthread_create(&t, nullptr, initThread, (void*)clazz);
    pthread_detach(t);
    
    LOADER_LOG("[*] Background thread spawned, returning to Java immediately");
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    LOADER_LOG("[*] libloader JNI_OnLoad");
    return JNI_VERSION_1_6;
}
