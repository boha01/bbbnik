# Mod Menu PRO — C++ / NDK / IL2CPP

**بيئة التطوير هنا لا تدعم Native Android Compilation** (لا يوجد NDK مثبّت في هذه البيئة)، لذلك التسليم هو السورس كاملاً جاهزاً للبناء عندك في Android Studio — لا APK جاهز.

## ترتيب الملفات كما طُلب
1. `app/src/main/java/com/bonika/modmenu/MainActivity.java` (+ `FloatingService.java`)
2. `app/src/main/java/com/bonika/modmenu/MenuOverlay.java` — 5 Tabs: PLAYER / COMBAT / VEHICLE / VISUALS / SETTINGS، كل زر مربوط بـ JNI فعلي
3. `app/src/main/cpp/native-lib.cpp` — Il2Cpp API عبر dlsym، بحث عن كلاس Position المطلوب، Hook للـ Position و Hook لـ TakeDamage (God Mode)، باتشات KittyMemory
4. `app/src/main/cpp/CMakeLists.txt`
5. `app/src/main/AndroidManifest.xml`
6. `injection/PATCH_MAIN_ACTIVITY.smali.txt` + `injection/Loader.java/.smali` + `inject.sh`

## قبل البناء النهائي
- ضع Dobby الحقيقي: `git clone https://github.com/jmpews/Dobby` وابنِ `libdobby.a` لكل ABI وضعه في `app/src/main/cpp/dobby/arm64-v8a/` و `armeabi-v7a/` (الـ header الموجود placeholder للربط).
- أسماء الميثودات في الهوكات (`Update/OnUpdate/Execute/...` و `Health.TakeDamage`) وأوفستات الباتشات في `Offsets::` يجب أن تطابق `dump.cs` الخاص بنسخة لعبتك بالضبط — غيّرها هناك فقط.
- كلاس الهدف مضبوط كما طلبت: `dev.Scripts.ECS.Morpeh.Features.UnityViewFeature.Components.Position` — البحث يفصل namespace/name ويجرّب كل الـ assemblies.

## البناء
Android Studio > فتح المشروع > Build APK. الناتج: `libmodmenu.so` + dex القائمة.
## الحقن
`bash inject.sh game.apk modmenu.apk` — يفكك، ينقل smali + .so، يعيد البناء والتوقيع. خطوة باتش الـ Manifest والأكتيفيتي يدوية (موضحة في الملف 6).

## تحذير عملي
استعمله على ألعابك/أوفلاين/حساب تجريبي. الحقن في ألعاب أونلاين قد يسبب حظراً من الناشر، والأوفستات/أسماء الدوال تتغير مع كل تحديث.
