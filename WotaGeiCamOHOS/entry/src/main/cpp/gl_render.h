/*
 * GL-0 渲染入口（对齐上游 38d712a 的移植前置）：
 * EGL/GLES3 上下文生命周期 + 每帧绘制（全屏三角形动画着色器）。
 * 相机帧进 GL（NativeImage/SurfaceTexture 等价）与上游着色器族从 GL-1 起移植。
 */
#ifndef WOTA_GL_RENDER_H
#define WOTA_GL_RENDER_H

#include <cstddef>
#include <cstdint>
#include <string>

/** 创建 EGL display/context/window surface 并编译试验着色器（window = OHNativeWindow*） */
bool WotaGlInit(void* window, uint64_t width, uint64_t height);

/** surface 尺寸变化：更新 viewport */
void WotaGlResize(uint64_t width, uint64_t height);

/** 每次 vsync（XComponent OnFrame）：拉取 NativeImage 帧 → 绘制 → swap */
void WotaGlRender(uint64_t timestamp);

/** surface 销毁：拆除 NativeImage、GL 对象与 EGL 资源 */
void WotaGlDestroy();

/** 是否已就绪（未就绪时帧回调直接跳过） */
bool WotaGlReady();

/**
 * 相机输出面 surfaceId（OH_NativeImage_GetSurfaceId 的十进制串，供 ArkTS 建 PreviewOutput）。
 * EGL/NativeImage 未就绪时返回空串（ArkTS 侧轮询）。
 */
std::string WotaGlGetInputSurfaceId();

/**
 * NativeImage 失败原因（空串 = 正常/尚未失败）：create-failed / set-listener-failed /
 * get-surface-id-failed。供 ArkTS 侧结束轮询并显示明确原因（区分"未就绪"与"创建失败"）。
 */
std::string WotaGlGetNativeImageError();

// ---------------------------------------------------------------- GL-2 外部入口（napi 调用，只写状态，渲染线程内生效）

/** 方向几何：sensorOrientation（度）/ 显示旋转（0/90/180/270）/ 前置镜像 */
void WotaGlSetDisplayGeometry(int sensorDeg, int displayDeg, bool mirrored);

/** 效果槽：0=NONE / 1=ZEBRA / 2=PEAKING（core FrameEffect 数值） */
void WotaGlSetEffect(int effect);

/** 曲线叠加开关（曲线 program / zebra·peaking 曲线变体） */
void WotaGlSetCurveEnabled(bool on);

/** 斑马纹参数：threshold 0..1、density 1..3（占用比/平铺档） */
void WotaGlSetZebraParams(float threshold, int density);

/** 峰值参数：strength 0..1、colorIndex 0..4（PeakingColor） */
void WotaGlSetPeakingParams(float strength, int colorIndex);

/** 毛玻璃全屏链开关（GL-3：OES→2D 拷贝→横向高斯→纵向高斯→2D 直通上屏） */
void WotaGlSetFrostEnabled(bool on);

/** 曲线 LUT：256×1 交错 RGB，768 字节（core CurveStack.bakeBytes 同一契约）；返回长度校验结果 */
bool WotaGlUploadCurveLut(const void* data, size_t length);

#endif // WOTA_GL_RENDER_H
