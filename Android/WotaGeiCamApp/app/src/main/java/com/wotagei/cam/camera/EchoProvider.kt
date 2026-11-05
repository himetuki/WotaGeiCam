package com.wotagei.cam.camera

import com.wotagei.cam.core.EchoParams
import com.wotagei.cam.core.EchoStatus

/**
 * 残影（R2 高光拖影）的**引擎侧接口**——[GlowProvider] 的同构姊妹（docs/research/R2 §四）。
 *
 * 两个渲染 sink 都实现它，语义一对：
 * - [GlRenderEngine]（GPU）：`enabled` 进 program 键的 `echo` 一维 + 累积面每帧一次，
 *   拖影**烘焙进成片**；
 * - [DirectSink]（DIRECT）：相机帧不进 GL 管线，[isEchoAvailable] 恒 false（**在场但禁用**），
 *   `setEchoParams` 只存值——UI 据此给半透明禁用 + 点了给解释，与 Glow/TONE 同一条降级口径。
 *
 * # 禁止在参数变化时重画
 * `setEchoParams` **不许**触发 `requestRender()`/`dirty = true`：模式/倍数/阈值/强度都是
 * uniform 级改动，下一个相机帧生效（≤33ms）；触发重画就等于在录制中往成片里多塞一帧
 * 重复帧——更糟的是同一张 OES 帧会被累积两次，拖影凭空多叠一层（R2 §五.1 护栏）。
 */
interface EchoProvider {

    /** 写入残影参数（内部会 [EchoParams.sanitize]：域钳 + NaN 回落） */
    fun setEchoParams(params: EchoParams)

    /** 本 sink 这一刻能否产出拖影（DIRECT 恒 false；链链接失败/连续报错后也为 false） */
    fun isEchoAvailable(): Boolean

    /** 一次状态快照（UI 读数用；纯数据，无 GL 依赖） */
    fun echoStatus(): EchoStatus
}
