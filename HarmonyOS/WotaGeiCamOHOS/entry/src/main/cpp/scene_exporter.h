/*
 * 后渲染导出离线执行器接口（docs/plan/29 后渲染批，上游 SceneExportRunner 的 OHOS 面）。
 *
 * 管线：AVDemuxer 解码（buffer 模式）→ 特效趟（双边降噪 / Glow：亮部提取+可分离高斯+合成，
 * 与 cpp/gl_render.cpp 实时链**同式 CPU 镜像**——公式逐 token 对拍，登记偏离：上游是 GL 趟，
 * 本侧离线用 CPU 同式实现，输出语义一致、性能待真机，不够再切 EGL 离屏——决策层
 * player/SceneExportPlan.ets 不受影响）→ 重编码（AVC buffer）→ AVMuxer。
 *
 * 决策口径对拍 player/SceneExportPlan.ets（已测规格，C++ 侧镜像）：
 * - 帧率 sceneExportFps（源 ∈ [15,60] 跟源、否则 30）；码率 sceneExportBitrate（跟源、
 *   域外 8M）；PTS sceneExportPtsUs（减基准钳 0）；提交 sceneExportShouldSubmit（左闭右开）；
 * - 曲线**刻意不应用**（录制期已烘进源片）；降噪任一档 = 同一条 full 双边核（σ=0.08，
 *   与实时链 kDenoiseSigmaR 同值）；Glow 读 encodeGlow 单串（缺键/坏串=出厂=关）。
 *
 * 三件配套修复（上游 09c19c9/56631bc/a0ef71d）：
 * - 音轨混入：demuxer 只 select 视频轨 + 用户选中的那一条音轨（-1 = 无音轨），其余轨
 *   从不 select——「拷贝前必须撤销全部轨选择」在本侧是「从不全选」的同义实现；
 * - 双重旋转：旋转角只写 muxer 一次（源容器级优先、视频轨兜底、归一到 90° 象限），
 *   解码/重编码面恒正向帧（与 clip_exporter 同口径）；
 * - 取消：取消是独立终态 `cancel:<detail>`，**绝不映射成 failed**（上游 a0ef71d 口径：
 *   「没有 CANCELLED 枚举值」的反面——本侧显式保留 cancel 桶，语义同源）。
 */
#pragma once

#include <string>

namespace wota {

/** 后渲染导出单例（与 ClipExporter 同款生命周期：Start 起线程 / Status 轮询 / Cancel 置旗） */
class SceneExporter {
public:
    static SceneExporter& Instance();

    /**
     * 起一轮后渲染导出。返回空串 = 已起跑；非空 = 参数错误原因（不进线程）。
     * glowSpec = core/Glow.ets encodeGlow 的单串（缺键/坏串按出厂=关处理，C++ 侧镜像解析）。
     * audioOrdinal = 源文件里音频轨的序号（-1 = 不带音轨；≥N = clamp 到最后一条）。
     */
    std::string Start(int32_t inFd, int64_t inSize, int32_t outFd,
        int64_t startMs, int64_t endMs, int32_t audioOrdinal,
        const std::string& glowSpec, int32_t denoiseOn);

    /** idle | running:<pct> | done:<id>:<out> | cancel:<detail> | failed:<reason>:<detail> */
    std::string Status();

    /** 置取消旗；收尾由任务线程自己完成（幂等） */
    void Cancel();

private:
    SceneExporter() = default;
    struct Impl;
    Impl* impl_ = nullptr;
};

} // namespace wota
