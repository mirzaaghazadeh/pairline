# Pairline's only native code: pairing with Android's Wireless debugging (D-054). The SPAKE2 and the TLS connection
# are BoringSSL's (a prefab package); these files only build small JNI bridges around them.
cmake_minimum_required(VERSION 3.22.1)
project(pairline_adb_pairing CXX)

set(CMAKE_CXX_STANDARD 17)
add_compile_options(-Wall -Werror -fvisibility=hidden -fdata-sections -ffunction-sections)
# Reproducible builds (SECURITY.md): no build paths or build IDs in the library.
add_compile_options(-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.)
add_link_options(-Wl,--gc-sections -Wl,--build-id=none)

find_package(boringssl REQUIRED CONFIG)

add_library(pairline_adb SHARED spake2_jni.cpp adb_tls_jni.cpp)
target_link_libraries(pairline_adb boringssl::ssl_static boringssl::crypto_static)

# The .comment section only names the compilers, and the NDK's crtbegin_so.o names a different clang build in the
# macOS NDK than in the Linux one, so with it a Mac can't reproduce the release (D-100). The code is the same.
if(NOT CMAKE_OBJCOPY)
  message(FATAL_ERROR "llvm-objcopy not found; it removes the .comment section for reproducible builds")
endif()
add_custom_command(TARGET pairline_adb POST_BUILD
  COMMAND "${CMAKE_OBJCOPY}" --remove-section=.comment "$<TARGET_FILE:pairline_adb>"
  VERBATIM)
