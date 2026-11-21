#pragma once
// Minimal Il2Cpp API resolved at runtime via dlsym(RTLD) from libil2cpp.so
// لا توجد عناوين ثابتة - كل دالة تُجلب باسمها، فتتحمل ASLR وتحديثات كثيرة.
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <android/log.h>
#define IL_LOG(...) __android_log_print(ANDROID_LOG_INFO,"ModMenu",__VA_ARGS__)

struct Il2CppDomain; struct Il2CppAssembly; struct Il2CppImage; struct Il2CppClass; struct Il2CppMethod;
struct MethodInfo;

using il2cpp_domain_get_t = Il2CppDomain* (*)();
using il2cpp_domain_get_assemblies_t = const Il2CppAssembly** (*)(const Il2CppDomain*, size_t*);
using il2cpp_assembly_get_image_t = const Il2CppImage* (*)(const Il2CppAssembly*);
using il2cpp_class_from_name_t = Il2CppClass* (*)(const Il2CppImage*, const char*, const char*);
using il2cpp_class_get_method_from_name_t = const Il2CppMethod* (*)(Il2CppClass*, const char*, int);
using il2cpp_runtime_invoke_t = void* (*)(const Il2CppMethod*, void*, void**, void**);
using il2cpp_thread_attach_t = void* (*)(Il2CppDomain*);

struct Il2CppApi {
    il2cpp_domain_get_t domain_get = nullptr;
    il2cpp_domain_get_assemblies_t domain_get_assemblies = nullptr;
    il2cpp_assembly_get_image_t assembly_get_image = nullptr;
    il2cpp_class_from_name_t class_from_name = nullptr;
    il2cpp_class_get_method_from_name_t class_get_method = nullptr;
    il2cpp_runtime_invoke_t runtime_invoke = nullptr;
    il2cpp_thread_attach_t thread_attach = nullptr;
    bool ok = false;

    bool init(void* handle) {
        auto sym = [&](const char* n){ return dlsym(handle, n); };
        domain_get = (il2cpp_domain_get_t)sym("il2cpp_domain_get");
        domain_get_assemblies = (il2cpp_domain_get_assemblies_t)sym("il2cpp_domain_get_assemblies");
        assembly_get_image = (il2cpp_assembly_get_image_t)sym("il2cpp_assembly_get_image");
        class_from_name = (il2cpp_class_from_name_t)sym("il2cpp_class_from_name");
        class_get_method = (il2cpp_class_get_method_from_name_t)sym("il2cpp_class_get_method_from_name");
        runtime_invoke = (il2cpp_runtime_invoke_t)sym("il2cpp_runtime_invoke");
        thread_attach = (il2cpp_thread_attach_t)sym("il2cpp_thread_attach");
        ok = domain_get && domain_get_assemblies && assembly_get_image && class_from_name;
        IL_LOG("Il2CppApi init ok=%d", ok);
        return ok;
    }

    // البحث في كل الـ assemblies عن كلاس - الاسم الكامل يُفصل عند آخر نقطة: namespace.name
    Il2CppClass* findClass(const char* fullName) {
        if (!ok) return nullptr;
        const char* dot = nullptr; for (const char* p = fullName; *p; p++) if (*p=='.') dot = p;
        // ملاحظة: أسماء مثل dev.Scripts...Position الـ namespace هو كل ما قبل آخر مقطعين عملياً
        // لذلك جرّب: جرّب تقسيمات متعددة حتى ينجح class_from_name
        Il2CppDomain* domain = domain_get();
        size_t count = 0; const Il2CppAssembly** assemblies = domain_get_assemblies(domain, &count);
        char ns[256], name[128];
        // المحاولة الأساسية: آخر مقطع = اسم الكلاس، الباقي = namespace
        if (dot) {
            size_t nsLen = dot - fullName; if (nsLen >= sizeof(ns)) nsLen = sizeof(ns)-1;
            memcpy(ns, fullName, nsLen); ns[nsLen]=0;
            strncpy(name, dot+1, sizeof(name)-1); name[sizeof(name)-1]=0;
            for (size_t i=0;i<count;i++) {
                const Il2CppImage* img = assembly_get_image(assemblies[i]);
                if (auto* k = class_from_name(img, ns, name)) return k;
            }
        }
        return nullptr;
    }
    static void* methodPointer(const Il2CppMethod* m) {
        // MethodInfo في Il2Cpp يبدأ بـ methodPointer
        return m ? *(void**)m : nullptr;
    }
};
inline Il2CppApi il2cpp;
