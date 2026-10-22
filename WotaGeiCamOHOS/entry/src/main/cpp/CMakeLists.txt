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

add_library(entry SHARED napi_init.cpp gl_render.cpp codec_recorder.cpp clip_exporter.cpp arc_flow.cpp arc_mend_pass.cpp arc_repair.cpp)
# libace_ndk.z.so 提供 OH_NativeXComponent_*（RegisterCallback/GetXComponentSize 等，native XComponent 接口）
# libnative_image.so 提供 OH_NativeImage_*（相机帧 → GL_TEXTURE_EXTERNAL_OES）
# libnative_media_{venc,avmuxer,codecbase,core}.so：OH_VideoEncoder(surface)+OH_AVMuxer(fd, MPEG_4)（GL-4 GPU 录制）
# libnative_media_{avsource,avdemuxer}.so：OH_AVSource/OH_AVDemuxer 逐样本 demux（T20 无损剪辑）
# libnative_media_{vdec,venc,avmuxer,codecbase,core}.so：离线修复 CPU 执行器（T29：解码→合并→编码→封装）
# libnative_window.so：OH_NativeWindow_DestroyNativeWindow（编码器输入面回收）
target_link_libraries(entry PUBLIC libace_napi.z.so libace_ndk.z.so libhilog_ndk.z.so libnative_image.so
    libnative_media_venc.so libnative_media_avmuxer.so libnative_media_avsource.so
    libnative_media_avdemuxer.so libnative_media_vdec.so libnative_media_codecbase.so
    libnative_media_core.so libnative_window.so EGL GLESv3)
