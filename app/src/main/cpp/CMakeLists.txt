cmake_minimum_required(VERSION 3.22.1)

# set(CMAKE_ANDROID_RTTI ON CACHE)

set(VCPKG_ROOT $ENV{VCPKG_ROOT})
# Report the missing variable by name; otherwise the toolchain path becomes "/scripts/...", producing an unclear configuration error.
if ("${VCPKG_ROOT}" STREQUAL "")
    message(FATAL_ERROR "Environment variable VCPKG_ROOT is not set: dependencies and the toolchain are provided through vcpkg")
endif ()
string(REPLACE "\\" "/" VCPKG_ROOT "${VCPKG_ROOT}")
set(CMAKE_TOOLCHAIN_FILE "${VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")

set(ANDROID_NDK_HOME $ENV{ANDROID_NDK_HOME})
# As above: first report the missing variable by name.
if ("${ANDROID_NDK_HOME}" STREQUAL "")
    message(FATAL_ERROR "Environment variable ANDROID_NDK_HOME is not set: the Android toolchain file chainloaded by vcpkg is located under it")
endif ()
string(REPLACE "\\" "/" ANDROID_NDK_HOME "${ANDROID_NDK_HOME}")
set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")

if (ANDROID_ABI STREQUAL "x86")
    set(VCPKG_TARGET_TRIPLET "x86-android")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(VCPKG_TARGET_TRIPLET "arm-android")
elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android")
endif ()

set(CMAKE_BUILD_TYPE RelWithDebInfo)

project("usbipdcpp_native")

set(CMAKE_CXX_STANDARD 23)

# find_package(usbipdcpp CONFIG REQUIRED COMPONENTS libusb)

# Require libusb >= 1.0.23: JNI uses libusb_wrap_sys_device to obtain busid, then closes
# the temporary handle; fd_keep semantics introduced in that version prevent closing UsbDeviceConnection's fd as well.
set(USBIPDCPP_BUILD_LIBUSB_COMPONENTS ON)
set(USBIPDCPP_BUILD_SHARED_LIBS OFF)
# This application only forwards USB devices and does not use virtual device components (used to simulate devices on PCs);
# disable them so their targets and sources do not participate in configuration.
set(USBIPDCPP_BUILD_VIRTUAL_DEVICE OFF)
add_subdirectory(usbipdcpp)

add_library(usbipdcpp_native SHARED
    usbipd_jni.cpp
    jni_log_callback.cpp
)

target_link_libraries(usbipdcpp_native
    usbipdcpp
    usbipdcpp_libusb
    android
    log
)
