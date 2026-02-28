
cmake_minimum_required(VERSION 3.2)

set(CMAKE_CXX_STANDARD 14)

# ============================================================
# libusb - 用于 DualSense 触觉反馈的 USB 等时传输
# Android Java USB API 不支持 isochronous transfer，
# 所以必须通过 libusb 在 native 层实现。
# 这里从源码静态编译 libusb，只编译 Linux 相关文件。
# ============================================================
set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/haptic/libusb)

if(EXISTS "${LIBUSB_DIR}/libusb/libusb.h")
    message(STATUS "Building with libusb for DualSense haptics")

    # 静态库：只编译 Android/Linux 需要的源文件
    add_library(usb1.0 STATIC
        ${LIBUSB_DIR}/libusb/core.c
        ${LIBUSB_DIR}/libusb/descriptor.c
        ${LIBUSB_DIR}/libusb/hotplug.c
        ${LIBUSB_DIR}/libusb/io.c
        ${LIBUSB_DIR}/libusb/sync.c
        ${LIBUSB_DIR}/libusb/strerror.c
        ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
        ${LIBUSB_DIR}/libusb/os/events_posix.c
        ${LIBUSB_DIR}/libusb/os/threads_posix.c
        ${LIBUSB_DIR}/libusb/os/linux_netlink.c
    )

    target_include_directories(usb1.0 PUBLIC
        ${LIBUSB_DIR}/libusb
        ${LIBUSB_DIR}/libusb/os
        ${LIBUSB_DIR}/android      # Android 专用的 config.h
    )

    target_compile_definitions(usb1.0 PRIVATE
        OS_LINUX
        HAVE_CLOCK_GETTIME
        _GNU_SOURCE
    )

    set(HAVE_LIBUSB TRUE)
else()
    message(WARNING "libusb source not found at ${LIBUSB_DIR}, haptics will be disabled")
    set(HAVE_LIBUSB FALSE)
endif()

# ============================================================
# chiaki-jni 主库（原有 + 触觉反馈）
# ============================================================
add_library(chiaki-jni SHARED
        src/main/cpp/chiaki-jni.c
        src/main/cpp/log.h
        src/main/cpp/log.c
        src/main/cpp/video-decoder.h
        src/main/cpp/video-decoder.c
        src/main/cpp/audio-decoder.h
        src/main/cpp/audio-decoder.c
        src/main/cpp/audio-output.h
        src/main/cpp/audio-output.cpp
        src/main/cpp/controller.h
        src/main/cpp/controller.c
        src/main/cpp/circular-fifo.hpp
        # DualSense 触觉反馈：libusb 等时传输
        src/main/cpp/haptic/haptic_usb.cpp)
target_link_libraries(chiaki-jni chiaki-lib)

# 让 haptic_usb.cpp 能找到 libusb 头文件
target_include_directories(chiaki-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/haptic
)

# 如果 libusb 可用，链接它并定义 HAVE_LIBUSB 宏
if(HAVE_LIBUSB)
    target_compile_definitions(chiaki-jni PRIVATE HAVE_LIBUSB)
    target_link_libraries(chiaki-jni usb1.0)
endif()

find_library(ANDROID_LIB_LOG log)
find_library(ANDROID_LIB_MEDIANDK mediandk)
find_library(ANDROID_LIB_ANDROID android)
target_link_libraries(chiaki-jni "${ANDROID_LIB_LOG}" "${ANDROID_LIB_MEDIANDK}" "${ANDROID_LIB_ANDROID}")

add_subdirectory(src/main/cpp/oboe)
target_link_libraries(chiaki-jni oboe)