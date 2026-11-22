#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <sys/mman.h>

#define LOADER_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SysLoader", __VA_ARGS__)
static JavaVM* g_vm = nullptr;

// الاسم المموه للمكتبة الرئيسية
#define FAKE_LIB_NAME "libdatastore_shared.so"

// دالة إخفاء المكتبة من الذاكرة
static void hideLib(const char* libName) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return;
    char line[512];
    uintptr_t start = 0, end = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, libName)) { sscanf(line, "%lx-%lx", &start, &end); break; }
    }
    fclose(fp);
    if (!start || start >= end) return;
    
    size_t size = end - start;
    void* newMem = mmap(nullptr, size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (newMem == MAP_FAILED) return;
    memcpy(newMem, (void*)start, size);
    
    if (mremap(newMem, size, size, MREMAP_MAYMOVE | MREMAP_FIXED, (void*)start) == MAP_FAILED) {
        munmap((void*)start, size);
        void* fixed = mmap((void*)start, size, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
        if (fixed != MAP_FAILED) memcpy(fixed, newMem, size);
        munmap(newMem, size);
    }
    LOADER_LOG("[+] Hid %s", libName);
}

static void* initThread(void* arg) {
    JNIEnv* env = nullptr;
    
    // انتظار استقرار اللعبة قبل تحميل المود
    sleep(5); 

    void* handle = dlopen(FAKE_LIB_NAME, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        LOADER_LOG("[-] dlopen failed: %s", dlerror());
        return nullptr;
    }
    LOADER_LOG("[+] Loaded %s", FAKE_LIB_NAME);

    // استدعاء دالة التهيئة الخاصة بالهوكات
    typedef void (*InitFn)(JNIEnv*, jclass);
    InitFn init = (InitFn)dlsym(handle, "Java_com_bonika_modmenu_NativeBridge_initNative");
    
    if (g_vm && g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK && env) {
        if (init) {
            init(env, nullptr); 
            LOADER_LOG("[+] initNative called");
        }
        g_vm->DetachCurrentThread();
    }

    // إخفاء المكتبات بعد 10 ثوانٍ من التحميل
    sleep(10);
    LOADER_LOG("[*] Hiding libraries...");
    hideLib(FAKE_LIB_NAME);
    hideLib("libloader.so");

    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bonika_modmenu_NativeBridge_loadModMenu(JNIEnv* env, jclass clazz) {
    pthread_t t;
    pthread_create(&t, nullptr, initThread, nullptr);
    pthread_detach(t);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}
