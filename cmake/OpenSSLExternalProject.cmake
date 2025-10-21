include(ExternalProject)

set(OPENSSL_INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}/openssl-install-prefix")

unset(OPENSSL_OS_COMPILER)
unset(OPENSSL_CONFIG_EXTRA_ARGS)
unset(OPENSSL_BUILD_ENV)
unset(OPENSSL_PATCH_CMD)

# MSYS2 路径设置
set(MSYS2_ROOT "C:/msys64")
set(MSYS2_BASH "${MSYS2_ROOT}/usr/bin/bash.exe")

if(ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(OPENSSL_OS_COMPILER "android-arm")
        set(CLANG_TARGET "armv7a-linux-androideabi${ANDROID_NATIVE_API_LEVEL}")
        set(GCC_PATTERN "armv7a-linux-androideabi-gcc")
    elseif(ANDROID_ABI STREQUAL "arm64-v8a")
        set(OPENSSL_OS_COMPILER "android-arm64")
        set(CLANG_TARGET "aarch64-linux-android${ANDROID_NATIVE_API_LEVEL}")
        set(GCC_PATTERN "aarch64-linux-android-gcc")
    elseif(ANDROID_ABI STREQUAL "x86")
        set(OPENSSL_OS_COMPILER "android-x86")
        set(CLANG_TARGET "i686-linux-android${ANDROID_NATIVE_API_LEVEL}")
        set(GCC_PATTERN "i686-linux-android-gcc")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(OPENSSL_OS_COMPILER "android-x86_64")
        set(CLANG_TARGET "x86_64-linux-android${ANDROID_NATIVE_API_LEVEL}")
        set(GCC_PATTERN "x86_64-linux-android-gcc")
    endif()
    
    set(OPENSSL_CONFIG_EXTRA_ARGS "-D__ANDROID_API__=${ANDROID_NATIVE_API_LEVEL}")
    get_filename_component(ANDROID_NDK_BIN_PATH "${CMAKE_C_COMPILER}" DIRECTORY)
    
    # 将 Windows 路径转换为 MSYS2 路径格式
    string(REPLACE "\\" "/" ANDROID_NDK_UNIX "${ANDROID_NDK}")
    string(REPLACE "\\" "/" ANDROID_NDK_BIN_PATH_UNIX "${ANDROID_NDK_BIN_PATH}")
    string(REGEX REPLACE "^([A-Za-z]):" "/\\1" ANDROID_NDK_UNIX "${ANDROID_NDK_UNIX}")
    string(REGEX REPLACE "^([A-Za-z]):" "/\\1" ANDROID_NDK_BIN_PATH_UNIX "${ANDROID_NDK_BIN_PATH_UNIX}")
    
    # 设置环境变量
    set(OPENSSL_BUILD_ENV 
        "ANDROID_NDK_HOME=${ANDROID_NDK_UNIX}"
        "ANDROID_NDK_ROOT=${ANDROID_NDK_UNIX}"
        "ANDROID_NDK=${ANDROID_NDK_UNIX}"
        "ANDROID_API=${ANDROID_NATIVE_API_LEVEL}"
        "PATH=${ANDROID_NDK_BIN_PATH_UNIX}:/usr/bin:/bin"
        "AR=llvm-ar"
        "RANLIB=llvm-ranlib"
    )
    
else()
    if(UNIX AND NOT APPLE AND CMAKE_SIZEOF_VOID_P STREQUAL "8")
        set(OPENSSL_OS_COMPILER "linux-x86_64")
    endif()
endif()

if(NOT OPENSSL_OS_COMPILER)
    message(FATAL_ERROR "Failed to match OPENSSL_OS_COMPILER")
endif()

# 检查 MSYS2 bash 是否存在
if(NOT EXISTS "${MSYS2_BASH}")
    message(FATAL_ERROR "MSYS2 bash not found at ${MSYS2_BASH}")
endif()

# 创建配置脚本
set(CONFIGURE_SCRIPT "${CMAKE_CURRENT_BINARY_DIR}/configure_openssl.sh")
file(WRITE "${CONFIGURE_SCRIPT}" "#!/bin/bash\n")
file(APPEND "${CONFIGURE_SCRIPT}" "set -e\n")
file(APPEND "${CONFIGURE_SCRIPT}" "cd \"$1\"\n")
foreach(env_var ${OPENSSL_BUILD_ENV})
    file(APPEND "${CONFIGURE_SCRIPT}" "export ${env_var}\n")
endforeach()
file(APPEND "${CONFIGURE_SCRIPT}" "perl Configure --prefix=\"$2\" no-shared ${OPENSSL_CONFIG_EXTRA_ARGS} ${OPENSSL_OS_COMPILER}\n")

# 创建构建脚本
set(BUILD_SCRIPT "${CMAKE_CURRENT_BINARY_DIR}/build_openssl.sh")
file(WRITE "${BUILD_SCRIPT}" "#!/bin/bash\n")
file(APPEND "${BUILD_SCRIPT}" "set -e\n")
file(APPEND "${BUILD_SCRIPT}" "cd \"$1\"\n")
foreach(env_var ${OPENSSL_BUILD_ENV})
    file(APPEND "${BUILD_SCRIPT}" "export ${env_var}\n")
endforeach()
file(APPEND "${BUILD_SCRIPT}" "make -j4 build_libs\n")

# 创建安装脚本
set(INSTALL_SCRIPT "${CMAKE_CURRENT_BINARY_DIR}/install_openssl.sh")
file(WRITE "${INSTALL_SCRIPT}" "#!/bin/bash\n")
file(APPEND "${INSTALL_SCRIPT}" "set -e\n")
file(APPEND "${INSTALL_SCRIPT}" "cd \"$1\"\n")
foreach(env_var ${OPENSSL_BUILD_ENV})
    file(APPEND "${INSTALL_SCRIPT}" "export ${env_var}\n")
endforeach()
file(APPEND "${INSTALL_SCRIPT}" "make install_dev\n")

ExternalProject_Add(OpenSSL-ExternalProject

    # URL file://${CMAKE_CURRENT_SOURCE_DIR}/third-party/openssl-3.5.3.tar.gz
    # URL https://www.openssl.org/source/openssl-3.5.3.tar.gz
    # 禁止下载与解压
    DOWNLOAD_COMMAND ""

    # 直接指定本地已经解压好的源码目录
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third-party/openssl-3.5.3
    INSTALL_DIR "${OPENSSL_INSTALL_DIR}"
    
    PATCH_COMMAND ${OPENSSL_PATCH_CMD}
    
    CONFIGURE_COMMAND 
        "${MSYS2_BASH}" --login -c "bash '${CONFIGURE_SCRIPT}' '<SOURCE_DIR>' '<INSTALL_DIR>'"
    
    BUILD_COMMAND 
        "${MSYS2_BASH}" --login -c "bash '${BUILD_SCRIPT}' '<SOURCE_DIR>'"
    
    INSTALL_COMMAND 
        "${MSYS2_BASH}" --login -c "bash '${INSTALL_SCRIPT}' '<SOURCE_DIR>'"
    
    BUILD_IN_SOURCE 1
)

add_library(OpenSSL_Crypto INTERFACE)
add_dependencies(OpenSSL_Crypto OpenSSL-ExternalProject)

if(${CMAKE_VERSION} VERSION_GREATER_EQUAL "3.13.0")
    target_link_directories(OpenSSL_Crypto INTERFACE "${OPENSSL_INSTALL_DIR}/lib")
else()
    link_directories("${OPENSSL_INSTALL_DIR}/lib")
endif()

target_link_libraries(OpenSSL_Crypto INTERFACE crypto)
target_include_directories(OpenSSL_Crypto INTERFACE "${OPENSSL_INSTALL_DIR}/include")