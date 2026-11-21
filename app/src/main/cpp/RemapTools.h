#pragma once
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>

#define REMAP_LOG(...) __android_log_print(ANDROID_LOG_INFO, "RemapTools", __VA_ARGS__)

namespace RemapTools {

struct MapRange { uintptr_t start; uintptr_t end; };

inline bool findLibraryRange(const char* libName, MapRange& out) {
    FILE* fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;
    char line[512];
    bool found = false;
    out.start = 0; out.end = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (!strstr(line, libName)) continue;
        uintptr_t s = 0, e = 0;
        if (sscanf(line, "%lx-%lx", &s, &e) == 2) {
            if (!found || s < out.start) out.start = s;
            if (!found || e > out.end)   out.end = e;
            found = true;
        }
    }
    fclose(fp);
    return found && out.end > out.start;
}

inline bool remapLibrary(const char* libName) {
    MapRange range;
    if (!findLibraryRange(libName, range)) {
        REMAP_LOG("[-] %s: not found in maps", libName);
        return false;
    }
    size_t size = range.end - range.start;
    REMAP_LOG("[*] %s: range 0x%lx-0x%lx (size=%zu)", libName, range.start, range.end, size);

    // 1. حجز ذاكرة مجهولة جديدة (قابلة للقراءة والكتابة والتنفيذ)
    void* newMem = mmap(nullptr, size, PROT_READ | PROT_WRITE | PROT_EXEC,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (newMem == MAP_FAILED) {
        REMAP_LOG("[-] mmap failed for %s", libName);
        return false;
    }

    // 2. نسخ محتوى المكتبة
    memcpy(newMem, (void*)range.start, size);

    // 3. محاولة mremap
    void* result = mremap(newMem, size, size, MREMAP_MAYMOVE | MREMAP_FIXED, (void*)range.start);
    if (result == MAP_FAILED) {
        REMAP_LOG("[!] mremap failed, trying fallback for %s", libName);
        munmap((void*)range.start, size);
        void* fixed = mmap((void*)range.start, size, PROT_READ | PROT_WRITE | PROT_EXEC,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
        if (fixed == MAP_FAILED) {
            REMAP_LOG("[-] fallback mmap failed for %s", libName);
            munmap(newMem, size);
            return false;
        }
        memcpy(fixed, newMem, size);
        munmap(newMem, size);
    }

    // 4. IMPORTANT: لا تغير الصلاحيات إلى RX فقط - اتركها RWX
    //    لأن ART قد يحتاج إلى الكتابة على الذاكرة لربط الدوال
    // mprotect((void*)range.start, size, PROT_READ | PROT_EXEC);
    
    REMAP_LOG("[+] %s hidden successfully (RWX kept)", libName);
    return true;
}

} // namespace RemapTools
