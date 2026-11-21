#pragma once
// واجهة مصغرة متوافقة مع KittyMemory (github.com/MJx0/KittyMemory)
// للاستعمال الكامل ضع ملفات KittyMemory الحقيقية هنا. الدوال الأساسية
// المستعملة في المشروع معرّفة كـ inline تعمل عبر /proc/self/maps و mprotect.
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <sys/mman.h>
#include <unistd.h>
namespace KittyMemory {
    inline uintptr_t getBaseAddress(const char* lib) {
        FILE* f = fopen("/proc/self/maps","r"); if(!f) return 0;
        char line[512]; uintptr_t base=0;
        while (fgets(line,sizeof(line),f)) if (strstr(line,lib)) { base=strtoull(line,nullptr,16); break; }
        fclose(f); return base;
    }
    inline bool memWrite(void* addr, const void* data, size_t len) {
        long page = sysconf(_SC_PAGESIZE); uintptr_t start = (uintptr_t)addr & ~(page-1);
        if (mprotect((void*)start, page, PROT_READ|PROT_WRITE|PROT_EXEC)!=0) return false;
        memcpy(addr,data,len); return true;
    }
    template<typename T> inline bool writeValue(uintptr_t addr, T v){ return addr && memWrite((void*)addr,&v,sizeof(T)); }
}
