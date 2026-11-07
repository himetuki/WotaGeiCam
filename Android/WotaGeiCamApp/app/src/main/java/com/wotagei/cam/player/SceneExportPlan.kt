package com.wotagei.cam.player

import com.wotagei.cam.core.EchoParams
import com.wotagei.cam.core.GlowParams
import com.wotagei.cam.core.VideoDenoise
import com.wotagei.cam.core.decodeEcho
import com.wotagei.cam.core.decodeGlow
import com.wotagei.cam.core.glowRgb

/**
 * 「内置播放器内后渲染导出」的**纯决策层**（2026-10-28 用户需求）：
 * Glow 与画面降噪在导出时重渲染进成片；**RGB 曲线不在后渲染里做**——
 * 曲线是录制期的 scene layer，录制时就已烘进成片像素（用户口径，见 docs/plan/25 §11.8）。
 *
 * 这一层只做「读什么参数、编什么规格」的决定，不碰 GL/编解码，所以 JVM 能全测；
 * 真正的管线在 [SceneExportRunner]。
 *
 * ## 与实时链的口径对齐
 * - Glow：读落盘参数（`KEY_GLOW`，`decodeGlow` 缺键回出厂 = 关）；`enabled=false` 不应用。
 * - 降噪：`videoDenoise != OFF` 即应用 **GPU 双边**（离线没有 HAL 的 ISP，HAL 两档在这里只能
 *   退化成同一条双边核）；`denoiseKernelLite` 的分档语义不适用于离线（不追实时负载）。
 * - 残影（R2 第三路，2026-11-05）：读落盘参数（`KEY_ECHO`，`decodeEcho` 缺键回出厂 = 关）；
 *   `enabled=false` 不应用。离线逐帧顺序处理，反馈累积面从黑起步（片头按可用回波数合成，
 *   与实时链同一 `echoDecayOf` 换算）；**全分辨率**跑（离线不追实时负载，不降采样）。
 * - 上屏档（DisplayScale）不参与：导出的永远是全分辨率帧。
 * - 曲线：**刻意不读** `KEY_CURVE_STACK`——录制期已烘进源片，再套一次会双倍。
 */
data class SceneExportEffects(
    /** null = 不应用光晕；非 null 已 sanitize（含 enabled=true 的完整参数） */
    val glow: GlowParams?,
    /** true = 应用 GPU 双边降噪 */
    val denoise: Boolean,
    /** null = 不应用残影；非 null 已 sanitize（含 enabled=true 的完整参数）。R2 第三路 */
    val echo: EchoParams? = null
) {
    /**
     * 至少一项在作用 = 后渲染导出与无损导出才有区别；都没有时 UI 应禁用**导出钮**
     * （后渲染模式本身仍可选——那是进面板内开效果的唯一入口，见 [clipExportReady]）
     */
    val active: Boolean get() = glow != null || denoise || echo != null

    /** 真实节点用的 tint（白 = 跟随原色） */
    val glowTint: FloatArray get() = glowRgb(glow?.color ?: 0xFFFFFFFFL)
}

/**
 * 从落盘参数决定这一轮导出要应用什么。
 *
 * @param glowRaw `WotaSettings.KEY_GLOW` 原串（null/坏串 = 出厂 = 关）
 * @param videoDenoise 画面降噪当前档（HAL 两档在离线统一按 GPU 双边处理）
 */
/**
 * 导出弹层内**当场可调**的口径（2026-10-29 用户需求：后渲染导出要有配置界面，不能直接开渲）：
 * 光晕/降噪参数在弹层初始化时取当前落盘值，用户在弹层里改的只作用于**这一次导出**，
 * 不反写设置（设置页仍是全局默认值的唯一归属）。纯函数，JVM 可测。
 */
fun sceneExportEffectsOf(glow: GlowParams, denoiseOn: Boolean, echo: EchoParams): SceneExportEffects =
    SceneExportEffects(
        glow = glow.sanitize().let { if (it.enabled) it else null },
        denoise = denoiseOn,
        echo = echo.sanitize().let { if (it.enabled) it else null }
    )

fun sceneExportEffects(glowRaw: String?, videoDenoise: VideoDenoise, echoRaw: String? = null): SceneExportEffects {
    val glow = decodeGlow(glowRaw).sanitize().let { if (it.enabled) it else null }
    val echo = decodeEcho(echoRaw).sanitize().let { if (it.enabled) it else null }
    return SceneExportEffects(glow = glow, denoise = videoDenoise != VideoDenoise.OFF, echo = echo)
}

/**
 * 剪辑弹层的**导出钮**是否可点（2026-11-07 真机「后渲染点了没反应」修复的决策层）。
 *
 * - 无损恒可：重封不读效果参数；
 * - 后渲染必须至少一项效果在作用：没有效果时重编码几分钟产出的像素与无损一致，
 *   而"偷偷降级成无损"又会让用户以为效果生效了——只能拦住并讲清原因。
 *
 * 注意这条**只门导出钮、不门后渲染 chip**：chip 一旦按 [active] 置灰，冷态（光晕/降噪/残影
 * 全关）就进不了面板内的效果配置区，用户永远没法当场把效果打开（2026-11-07 真机死锁）。
 * 后渲染模式恒可选，选进来露出配置区；配好效果后 [active] 变真，导出钮才亮。
 */
fun clipExportReady(postRender: Boolean, effects: SceneExportEffects): Boolean =
    !postRender || effects.active

/** 编码帧率：源容器帧率可用就跟着源（钳 15..60），读不出按 30 */
fun sceneExportFps(sourceFps: Int): Int =
    if (sourceFps in 15..60) sourceFps else 30

/**
 * 编码码率：跟源片码率走（重编码不主动降质），源未报/异常小时按 8 Mbps 兜底。
 * 不按分辨率自造档位表——源片本身已经是用户认可的成片。
 */
fun sceneExportBitrate(sourceBitrate: Int): Int =
    if (sourceBitrate in 1_000_000..200_000_000) sourceBitrate else 8_000_000

/**
 * 输出帧的呈现时间戳（微秒）：源 PTS 减首帧基准，负值钳 0。
 * 解码器从关键帧起喂，早于入点的帧不进编码器（由 [sceneExportShouldSubmit] 判）。
 */
fun sceneExportPtsUs(sourcePtsUs: Long, basePtsUs: Long): Long =
    (sourcePtsUs - basePtsUs).coerceAtLeast(0L)

/** 这一帧该不该进编码器：源 PTS ∈ [startUs, endUs)（区间外只解码、不提交） */
fun sceneExportShouldSubmit(sourcePtsUs: Long, startUs: Long, endUs: Long): Boolean =
    sourcePtsUs >= startUs && (endUs <= startUs || sourcePtsUs < endUs)
