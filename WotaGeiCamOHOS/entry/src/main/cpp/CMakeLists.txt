# GL-0 原生构建骨架（对齐上游 38d712a 的移植前置，非业务代码）
#
# 产物 libentry.so 与 ArkTS 侧 XComponent({ libraryname: 'entry' }) / napi nm_modname="entry" 三者必须一致。
# T2 只编 napi_init.cpp（模块注册 + hilog 打点）；T4 起追加 gl_render.cpp（EGL/GLES3 管线）。
cmake_minimum_required(VERSION 3.5.0)
project(wotageicam)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NATIVERENDER_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})

include_directories(${NATIVERENDER_ROOT_PATH})

add_library(entry SHARED napi_init.cpp gl_render.cpp)
# libace_ndk.z.so 提供 OH_NativeXComponent_*（RegisterCallback/GetXComponentSize 等，native XComponent 接口）
target_link_libraries(entry PUBLIC libace_napi.z.so libace_ndk.z.so libhilog_ndk.z.so EGL GLESv3)
