#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <sys/mman.h> // <--- ضروري جداً لدوال mmap, mremap

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SysLoader", __VA_ARGS__)
static JavaVM* g_vm = nullptr;

// الاسم المموه للمكتبة الرئيسية (يجب أن يكون هذا اسم ملف libmodmenu.so بعد إعادة تسميته)
#define FAKE_LIB_NAME "libdatastore_shared.so"

// دالة إخفاء المكتبة (إعادة تعيين الذاكرة - Remapping)
static bool hideLib(const char* libName) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;
    char line[512];
    uintptr_t start = 0, end = 0;
    
    // البحث عن نطاق المكتبة في خرائط الذاكرة
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, libName)) {
            sscanf(line, "%lx-%lx", &start, &end);
            break;
        }
    }
    fclose(fp);
    
    if (!start || start >= end) return false;
    
    size_t size = end - start;
    void* newMem = mmap(nullptr, size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (newMem == MAP_FAILED) return false;
    
    // نسخ محتوى المكتبة إلى الذاكرة المجهولة
    memcpy(newMem, (void*)start, size);
    
    // محاولة استبدال النطاق الأصلي
    if (mremap(newMem, size, size, MREMAP_MAYMOVE | MREMAP_FIXED, (void*)start) == MAP_FAILED) {
        munmap((void*)start, size);
        void* fixed = mmap((void*)start, size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
        if (fixed != MAP_FAILED) memcpy(fixed, newMem, size);
        munmap(newMem, size);
    }
    return true;
}

// الخيط الخلفي الذي ينتظر اللعبة ثم يحمل المكتبة المخفية
static void* initThread(void* arg) {
    JNIEnv* env = nullptr;
    jclass clazz = (jclass)arg;

    LOADER_LOG("[*] Waiting for libil2cpp...");
    int waitCount = 0;
    bool found = false;
    
    // انتظار تحميل محرك اللعبة (بحد أقصى 60 ثانية)
    while (waitCount < 60) {
        FILE* fp = fopen("/proc/self/maps", "r");
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

    sleep(3); // انتظار إضافي لضمان استقرار اللعبة

    // تحميل المكتبة المموهة (libdatastore_shared.so)
    void* handle = dlopen(FAKE_LIB_NAME, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOADER_LOG("[-] dlopen %s failed: %s", FAKE_LIB_NAME, dlerror());
        return nullptr;
    }

    LOADER_LOG("[+] Loaded %s successfully", FAKE_LIB_NAME);

    // استدعاء دالة التهيئة (initNative) الموجودة في المود
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
    } else {
        LOADER_LOG("[-] initNative not found in libmodmenu");
    }

    // إخفاء المكتبات من خرائط الذاكرة بعد 15 ثانية (لتجنب الانهيار)
    sleep(15);
    LOADER_LOG("[*] Hiding libraries from memory...");
    hideLib(FAKE_LIB_NAME);
    hideLib("libloader.so");

    return nullptr;
}

// الدالة التي تستدعيها Java
extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    LOADER_LOG("[*] loadModMenu called, starting background thread");
    pthread_t t;
    pthread_create(&t, nullptr, initThread, (void*)clazz);
    pthread_detach(t);
}

// حفظ JavaVM للاستخدام في الخيوط الخلفية
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    LOADER_LOG("[*] libloader JNI_OnLoad");
    return JNI_VERSION_1_6;
}
