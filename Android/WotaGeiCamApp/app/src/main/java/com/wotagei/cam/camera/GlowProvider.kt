package com.wotagei.cam.camera

import com.wotagei.cam.core.GlowParams
import com.wotagei.cam.core.GlowStatus

/**
 * Glow（荧光棒光晕）的**引擎侧接口**（docs/plan/24 R3 §1.3/§1.5；25 §11.3）。
 *
 * 两个渲染 sink 都实现它，但语义完全不同：
 * - [GlRenderEngine]（GPU）：参数进 program 键的 `glow` 一维 + 离屏链每帧一次，光晕**烘焙进成片**；
 * - [DirectSink]（DIRECT）：相机帧不进 GL 管线，`isGlowAvailable()` 恒 false（在场但不可用），
 *   `setGlowParams` 只存值，UI 据此给"在场但禁用 + 说明"（24 §1.6）。
 *
 * # 为什么是"拉"而不是"推"
 * UI 拿不到渲染线程，也不需要：参数写在 [setGlowParams]（任意线程）、状态由 [glowStatus] 现读快照。
 * 两边都只碰 `@Volatile` 字段，没有锁也没有回调。
 *
 * # 禁止在参数变化时重画
 * `setGlowParams` **不许**触发 `requestRender()`/`dirty = true`：光晕参数是 uniform 级改动，
 * 下一个相机帧生效（≤33ms）；触发重画就等于在录制中往成片里多塞一帧重复帧（25 §11.7 的霜先例）。
 */
interface GlowProvider {

    /**
     * 写入 Glow 参数（内部会 [GlowParams.sanitize]：域钳 + NaN 回落）。
     *
     * `enabled` 是 **program 维度**：false→true 时才做一次链的 `prepare()` 与两个槽的 `glow=1`
     * 变体预热（编译不在帧路径上）；true→false 只停跑、资源保留（再打开不重建、不闪）。
     */
    fun setGlowParams(params: GlowParams)

    /** 本 sink 这一刻能否产出光晕（DIRECT 恒 false；链链接失败/连续报错后也为 false） */
    fun isGlowAvailable(): Boolean

    /** 一次状态快照（UI 读数/排障用；纯数据，无 GL 依赖） */
    fun glowStatus(): GlowStatus
}
