cmake_minimum_required(VERSION 3.22.1)
project("modmenu")

# C++17 is REQUIRED: Features.h / Il2CppApi.h use inline variables.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(modmenu SHARED native-lib.cpp)

# -fexceptions: the init path uses try/catch (without this flag the
#   catch blocks in native-lib.cpp do not compile under the NDK).
# -pthread: std::thread worker started from JNI_OnLoad.
target_compile_options(modmenu PRIVATE
        -fexceptions
        -frtti
        -pthread
        -Wall
        -Wno-unused-parameter)

# --- Dobby: put the REAL libdobby.a in dobby/<abi>/ before a release build ---
# If it is missing, the build still links ONLY because the GitHub workflow
# creates a stub libdobby.a whose DobbyHook returns -1; safeHook() in
# native-lib.cpp checks that return code, logs it, and installs nothing.
# A stub build will NEVER hook and will NEVER crash - but it also will
# never enable God Mode / Position. Use the real Dobby for a working menu.
if(EXISTS ${CMAKE_SOURCE_DIR}/dobby/${ANDROID_ABI}/libdobby.a)
    add_library(dobby STATIC IMPORTED)
    set_target_properties(dobby PROPERTIES IMPORTED_LOCATION
            ${CMAKE_SOURCE_DIR}/dobby/${ANDROID_ABI}/libdobby.a)
    target_link_libraries(modmenu dobby)
    message(STATUS "modmenu: linking real/stub libdobby.a for ${ANDROID_ABI}")
else()
    message(WARNING "modmenu: dobby/${ANDROID_ABI}/libdobby.a NOT found - DobbyHook will fail to link unless the workflow provides it")
endif()

find_library(log-lib log)
target_include_directories(modmenu PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(modmenu ${log-lib} android dl)
