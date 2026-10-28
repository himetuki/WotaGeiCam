/*
 * GL-0 渲染入口（对齐上游 38d712a 的移植前置）：
 * EGL/GLES3 上下文生命周期 + 每帧绘制（全屏三角形动画着色器）。
 * 相机帧进 GL（NativeImage/SurfaceTexture 等价）与上游着色器族从 GL-1 起移植。
 */
#ifndef WOTA_GL_RENDER_H
#define WOTA_GL_RENDER_H

#include <native_window/external_window.h>

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

/** 效果槽（**上屏专属**）：0=NONE / 1=ZEBRA / 2=PEAKING（core FrameEffect 数值）。
 *  监看叠加只上屏、绝不进成片（上游 46d8d91 双 program 槽裁决的 OHOS 对应物）：
 *  编码支路恒 NONE，曲线是创作观感、两槽都带——编码槽在 native 内部自动跟随，无独立开关。 */
void WotaGlSetEffect(int effect);

/** 曲线叠加开关（曲线 program / zebra·peaking 曲线变体） */
void WotaGlSetCurveEnabled(bool on);

/** 斑马纹参数：threshold 0..1、density 1..3（占用比/平铺档） */
void WotaGlSetZebraParams(float threshold, int density);

/** 峰值参数：strength 0..1、colorIndex 0..4（PeakingColor） */
void WotaGlSetPeakingParams(float strength, int colorIndex);

/** 直方图采样开关（RGB 曲线框背景，f31d18c）：曲线弹窗可见时开；关 = 停采清快照 */
void WotaGlSetHistogramEnabled(bool on);

/** 取直方图快照（3×64 计数 R/G/B，出参容量 ≥192；无样本返回 false）。归一在 ArkTS core/HistogramMath */
bool WotaGlGetHistogramSnapshot(int32_t* out, int capacity);

/** 毛玻璃全屏链开关（GL-3：OES→2D 拷贝→横向高斯→纵向高斯→2D 直通上屏） */
void WotaGlSetFrostEnabled(bool on);

/** 曲线 LUT：256×1 交错 RGB，768 字节（core CurveStack.bakeBytes 同一契约）；返回长度校验结果 */
bool WotaGlUploadCurveLut(const void* data, size_t length);

// ---------------------------------------------------------------- GL-4 编码面（GPU 录制）

/**
 * 把编码器输入面（OH_VideoEncoder_GetSurface 取得）挂成第二个 EGL window surface。
 * 返回 false = EGL 面创建失败（GPU 录制不可用，预览/效果不受影响）。
 */
bool WotaGlAttachEncoderWindow(OHNativeWindow* window, int width, int height);
void WotaGlSetDisplayFit(float fitX, float fitY);

/** 解绑编码面（幂等）：恢复上屏 makeCurrent 并销毁 EGL 编码 surface（不销毁 native window，归 CodecRecorder） */
void WotaGlDetachEncoder();

/**
 * 容器旋转元数据（muxer SetRotation 输入）：(sensor − display + 360) % 360。
 * 编码 UV 只送缓冲原样帧（stRotDeg、不镜像），竖屏转正靠该容器 hint（上游 orientationHint 同义）。
 */
int WotaGlEncoderRotation();

// ---------------------------------------------------------------- GL-4 光弧链在线版

/**
 * 录制期转换：mode 0=off / 1=MEND（补弧）/ 2=DROP（仅抽帧）；dstFps<=0 时拒斥回落 off；deadband 钳 0..32。
 * 仅服务起录前的参数下发（MEND 的 pass 延迟到编码面 attach 时构建）；运行期切换弧档不支持（登记）。
 */
void WotaGlSetArcConvert(int mode, int dstFps, int deadband);

/** 停录前同步冲刷 MEND 滞留帧（返回 1 = 成功发出末帧，0 = 无滞留/失败）；须在 stop 编码器之前调用 */
int WotaGlFlushArcPending();

/** 取走被抽帧位次（紧凑串 "k,n;k,n"，取走即清） */
std::string WotaGlDrainArcDrops();

/** 实测源帧率（首尾时间戳法；样本不足返回 0） */
int WotaGlMeasuredArcSrcFps();

/** 原生帧账计数（"glCallbacks,glConsumed,swapOk,swapFail,arcDropped"；不清零，调用方做差） */
std::string WotaGlGetFrameCounters();

/** 当前实际运行档（0/1/2；MEND 失败降 DROP 后为 2） */
int WotaGlGetArcEffectiveMode();

// ---------------------------------------------------------------- Glow/降噪（2026-10-28 批，上游 24 R3/25 §11）

/**
 * Glow 参数（enabled 0/1；颜色 ARGB 数值，与上游 GlowParams 同义）。
 * 形状参数只换 uniform（不重建 program）；打开动作给停用链一次干净重试。
 */
void WotaGlSetGlowParams(int enabled, float threshold, float soft, float width, float gain,
    float add, float freq, float blend, uint32_t colorA, uint32_t colorB);

/**
 * Glow 状态串：`off` 或 `on:<broken0/1>:<scale>:<inW>:<inH>:<fps>:<rtW>:<rtH>:<clamped0/1>`。
 * fps 本侧无编码帧率观测面，按上游「未知按 30」口径报 30；rt 未建时两位为 0。
 */
std::string WotaGlGetGlowStatus();

/** 画面降噪档 0/1/2（0=关 1=完整核 2=轻量核；0 时槽键归零、旧变体由下一帧回收） */
void WotaGlSetDenoiseLevel(int level);

#endif // WOTA_GL_RENDER_H
