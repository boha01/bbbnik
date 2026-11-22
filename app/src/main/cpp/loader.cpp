#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SysLoader", __VA_ARGS__)
static JavaVM* g_vm = nullptr;

// نستخدم اسم مكتبة يبدو أصلياً للعبة
#define FAKE_LIB_NAME "libdatastore_shared.so"
// اسم مكتبتنا الحقيقية
#define REAL_LIB_NAME "libmodmenu.so"

static void* initThread(void* arg) {
    JNIEnv* env = nullptr;
    jclass clazz = (jclass)arg;

    LOADER_LOG("[*] Waiting for il2cpp...");
    int waitCount = 0;
    FILE* fp = nullptr;
    bool found = false;
    
    // ننتظر لود محرك اللعبة
    while (waitCount < 60) {
        fp = fopen("/proc/self/maps", "r");
        if (fp) {
            char line[512];
            while (fgets(line, sizeof(line), fp)) {
                if (strstr(line, "libil2cpp.so")) { found = true; break; }
            }
            fclose(fp);
        }
        if (found) break;
        sleep(1);
        waitCount++;
    }

    if (!found) {
        LOADER_LOG("[-] il2cpp not found. Aborting.");
        return nullptr;
    }

    sleep(3); // انتظار استقرار

    // 1. فتح المكتبة المموهة (التي هي في الحقيقة libmodmenu.so)
    // نمرر المسار المطلق لضمان التحميل الصحيح
    void* handle = dlopen(FAKE_LIB_NAME, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOADER_LOG("[-] dlopen failed: %s", dlerror());
        return nullptr;
    }

    // 2. استدعاء دالة التهيئة الخاصة بك
    typedef void (*InitFn)(JNIEnv*, jclass);
    InitFn init = (InitFn)dlsym(handle, "Java_com_bonika_modmenu_NativeBridge_initNative");
    if (init) {
        if (g_vm) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
                init(env, clazz);
                LOADER_LOG("[+] initNative called successfully");
                g_vm->DetachCurrentThread();
            }
        }
    }

    // 3. إخفاء المكتبة المموهة من خرائط الذاكرة (تقنية الـ Remap)
    sleep(15); 
    LOADER_LOG("[*] Attempting to hide libraries...");
    
    // كود الإخفاء (RemapTools) الذي كتبناه سابقاً
    // (تأكد من تضمين ملف RemapTools.h في نفس المجلد)
    extern bool remapLibrary(const char* libName);
    remapLibrary(FAKE_LIB_NAME);
    
    sleep(2);
    remapLibrary("libloader.so");

    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    LOADER_LOG("[*] Starting background thread");
    pthread_t t;
    pthread_create(&t, nullptr, initThread, (void*)clazz);
    pthread_detach(t);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
