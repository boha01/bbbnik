cmake_minimum_required(VERSION 3.22.1)
project("modmenu")

add_library(modmenu SHARED native-lib.cpp)

# --- Dobby (اختياري وقت البناء): ضع libdobby.a في dobby/<abi>/ ---
# إن لم توجد، يُبنى المشروع بدون ربط فعلي وستفشل رموز DobbyHook وقت الربط،
# لذلك للبناء النهائي: git clone https://github.com/jmpews/Dobby وابنِه أو حمّل release جاهزاً.
if(EXISTS ${CMAKE_SOURCE_DIR}/dobby/${ANDROID_ABI}/libdobby.a)
    add_library(dobby STATIC IMPORTED)
    set_target_properties(dobby PROPERTIES IMPORTED_LOCATION
            ${CMAKE_SOURCE_DIR}/dobby/${ANDROID_ABI}/libdobby.a)
    target_link_libraries(modmenu dobby)
endif()

find_library(log-lib log)
target_include_directories(modmenu PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(modmenu ${log-lib} android dl)
