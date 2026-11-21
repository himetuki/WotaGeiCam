/**
 * 「内置播放器内后渲染导出」的**纯决策层**——上游 player/SceneExportPlan.kt（dcaf45b+
 * ba9f0f4+350c671 的决策面）直译。2026-10-28 用户需求：Glow 与画面降噪在导出时重渲染
 * 进成片；**RGB 曲线不在后渲染里做**——曲线是录制期 scene layer，录制时已烘进成片像素
 * （用户口径，docs/plan/25 §11.8）。
 *
 * 这一层只做「读什么参数、编什么规格」的决定，不碰 GL/编解码，纯测全表；
 * 真正的管线（解码→GL 三趟→重编码）是 native 执行器面（arc_repair 同族量级，
 * **平台面待批**——本文件先行钉死决策口径，管线批按此对拍）。
 *
 * ## 与实时链的口径对齐
 * - Glow：读落盘参数串（`decodeGlow` 缺键/坏串回出厂 = 关）；`enabled=false` 不应用。
 * - 降噪：`videoDenoise != OFF` 即应用 **GPU 双边**（离线没有 HAL 的 ISP，HAL 两档在离线
 *   只能退化成同一条双边核）；denoiseKernelLite 分档语义不适用离线（不追实时负载）。
 * - 上屏档（DisplayScale）不参与：导出的永远是全分辨率帧。
 * - 曲线：**刻意不读** curve_stack——录制期已烘进源片，再套一次会双倍。
 */
import { decodeGlow, GlowParams, glowRgb } from '../core/Glow';
import { VideoDenoise } from '../core/Denoise';

/** 一次后渲染导出要应用的特效集（null glow = 不应用；denoise true = GPU 双边） */
export class SceneExportEffects {
  /** null = 不应用光晕；非 null 已 sanitize（含 enabled=true 的完整参数） */
  glow: GlowParams | undefined;
  /** true = 应用 GPU 双边降噪 */
  denoise: boolean;

  constructor(glow: GlowParams | undefined, denoise: boolean) {
    this.glow = glow;
    this.denoise = denoise;
  }

  /** 至少一项在作用 = 后渲染导出与无损导出才有区别；都没有时 UI 应禁用该模式 */
  active(): boolean {
    return this.glow !== undefined || this.denoise;
  }

  /** 真实节点用的 tint（白 = 跟随原色） */
  glowTint(): Array<number> {
    return glowRgb(this.glow !== undefined ? this.glow.color : GLOW_WHITE);
  }
}

const GLOW_WHITE: number = 0xFFFFFFFF;

/** 弹层内当场可调的口径：光晕/降噪在弹层初始化取当前落盘值，弹层里改的只作用这一次导出 */
export function sceneExportEffectsOf(glow: GlowParams, denoiseOn: boolean): SceneExportEffects {
  const s: GlowParams = glow.sanitize();
  return new SceneExportEffects(s.enabled ? s : undefined, denoiseOn);
}

/** 从落盘参数决定这一轮导出要应用什么（glowRaw = KEY_GLOW 原串；坏串 = 出厂 = 关） */
export function sceneExportEffects(glowRaw: string | undefined,
  denoise: VideoDenoise): SceneExportEffects {
  const glow: GlowParams = decodeGlow(glowRaw).sanitize();
  return new SceneExportEffects(glow.enabled ? glow : undefined, denoise !== VideoDenoise.OFF);
}

/** 编码帧率：源容器帧率可用就跟着源（钳 15..60），读不出按 30 */
export function sceneExportFps(sourceFps: number): number {
  return sourceFps >= 15 && sourceFps <= 60 ? sourceFps : 30;
}

/**
 * 编码码率：跟源片码率走（重编码不主动降质），源未报/异常小按 8 Mbps 兜底。
 * 不按分辨率自造档位表——源片本身已经是用户认可的成片。
 */
export function sceneExportBitrate(sourceBitrate: number): number {
  return sourceBitrate >= 1_000_000 && sourceBitrate <= 200_000_000 ? sourceBitrate : 8_000_000;
}

/** 输出帧的呈现时间戳（微秒）：源 PTS 减首帧基准，负值钳 0 */
export function sceneExportPtsUs(sourcePtsUs: number, basePtsUs: number): number {
  return Math.max(0, sourcePtsUs - basePtsUs);
}

/** 这一帧该不该进编码器：源 PTS ∈ [startUs, endUs)（区间外只解码、不提交） */
export function sceneExportShouldSubmit(sourcePtsUs: number, startUs: number, endUs: number): boolean {
  return sourcePtsUs >= startUs && (endUs <= startUs || sourcePtsUs < endUs);
}
