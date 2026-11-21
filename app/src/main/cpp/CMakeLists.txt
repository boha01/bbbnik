cmake_minimum_required(VERSION 3.22.1)
project("modmenu")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- المكتبة الرئيسية ---
add_library(modmenu SHARED native-lib.cpp)
target_compile_options(modmenu PRIVATE
        -fexceptions
        -frtti
        -pthread
        -Wall
        -Wno-unused-parameter)

if(EXISTS ${CMAKE_SOURCE_DIR}/dobby/${ANDROID_ABI}/libdobby.a)
    add_library(dobby STATIC IMPORTED)
    set_target_properties(dobby PROPERTIES IMPORTED_LOCATION
            ${CMAKE_SOURCE_DIR}/dobby/${ANDROID_ABI}/libdobby.a)
    target_link_libraries(modmenu dobby)
    message(STATUS "modmenu: linking real/stub libdobby.a for ${ANDROID_ABI}")
else()
    message(WARNING "modmenu: dobby/${ANDROID_ABI}/libdobby.a NOT found")
endif()

find_library(log-lib log)
target_include_directories(modmenu PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(modmenu ${log-lib} android dl)

# --- مكتبة المحمل الجديدة ---
add_library(loader SHARED loader.cpp)
target_compile_options(loader PRIVATE
        -fexceptions
        -frtti
        -pthread
        -Wall
        -Wno-unused-parameter)
target_include_directories(loader PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(loader ${log-lib} android dl)
