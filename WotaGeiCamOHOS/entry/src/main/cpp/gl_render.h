/*
 * GL-0 渲染入口（对齐上游 38d712a 的移植前置）：
 * EGL/GLES3 上下文生命周期 + 每帧绘制（全屏三角形动画着色器）。
 * 相机帧进 GL（NativeImage/SurfaceTexture 等价）与上游着色器族从 GL-1 起移植。
 */
#ifndef WOTA_GL_RENDER_H
#define WOTA_GL_RENDER_H

#include <cstdint>

/** 创建 EGL display/context/window surface 并编译试验着色器（window = OHNativeWindow*） */
bool WotaGlInit(void* window, uint64_t width, uint64_t height);

/** surface 尺寸变化：更新 viewport */
void WotaGlResize(uint64_t width, uint64_t height);

/** 每次 vsync（XComponent OnFrame）：清屏 → uTime 推进 → 绘制 → swap */
void WotaGlRender(uint64_t timestamp);

/** surface 销毁：拆除 GL 对象与 EGL 资源 */
void WotaGlDestroy();

/** 是否已就绪（未就绪时帧回调直接跳过） */
bool WotaGlReady();

#endif // WOTA_GL_RENDER_H
