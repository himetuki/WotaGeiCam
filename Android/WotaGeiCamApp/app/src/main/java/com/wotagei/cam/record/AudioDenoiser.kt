package com.wotagei.cam.record

import android.media.audiofx.NoiseSuppressor
import com.wotagei.cam.core.AudioDenoise
import com.wotagei.cam.diag.Diag as Log

/**
 * 环境音降噪（2026-10-26 用户需求）：围绕系统 [NoiseSuppressor] 的**单一挂载点**。
 *
 * # 为什么是系统 API 而不是 RNNoise 之类
 * `NoiseSuppressor` 是 AudioEffect 框架里的标准预处理器，按 AudioRecord 的 sessionId 挂载，
 * 不引第三方库、不增加解码开销，各厂商的 audio HAL 都有实现（哪怕只是透传）。它针对的正是
 * **稳态与非稳态环境噪声**（空调、风声、人群底噪），与「录像时的环境音」这个诉求同向。
 * 神经网络降噪（RNNoise/audx 一类）效果在强噪下更好，但要引 native 依赖、且模型对**音乐**
 * 类内容的保真度未经验证——本项目录的是现场音乐，登记为后续独立评估（docs/perf-backlog.md）。
 *
 * # 生命周期
 * - 每个 feeder 一份，随 AudioRecord 建立、随 feeder 释放（[release] 幂等）；
 * - 只用**环境音**（麦克风）路：内录轨（AudioPlaybackCapture）是"别的应用正在播的声音"，
 *   对它降噪等于二次处理别人的成品混音，产品上明确不做；
 * - 创建/启用失败一律降级为"无降噪继续录"，**绝不阻断录音**（这点与 AudioFeeder 的降级哲学一致）。
 *
 * # 与音源的关系
 * 部分机型在 `VOICE_COMMUNICATION` 音源下 NoiseSuppressor 默认就已启用（HAL 自带）。本类不假设
 * 默认值：先读 `enabled`，再按需 `setEnabled(true)`，并把"实际状态"记进日志——用户排障时能
 * 一眼看出"这个开关在这台机上到底有没有生效"。
 */
internal class AudioDenoiser private constructor(
    private var suppressor: NoiseSuppressor?,
    /** 创建/启用后的实际生效状态；false = 本机不生效（UI 可据此提示） */
    val active: Boolean
) {

    /** 释放底层效果器（幂等、吞异常）。调用方在 AudioRecord release 之前调用。 */
    fun release() {
        runCatching { suppressor?.release() }
        suppressor = null
    }

    companion object {
        private const val TAG = "WotaDenoise"

        /** 不降噪的占位实现（默认关 / 内录轨 / 创建失败三路共用，调用方不必判空） */
        val INACTIVE = AudioDenoiser(null, active = false)

        /**
         * 按会话 id 建立并启用系统降噪。
         *
         * @param sessionId `AudioRecord.audioSessionId`（必须是已构造、未 release 的 AudioRecord）
         * @param mode 用户所选档位；[AudioDenoise.OFF] 直接返回 [INACTIVE] 且**不构造效果器**
         *   （不构造就不会占 Effect 槽位，这是"默认关"必须零成本的前提）
         */
        fun attach(sessionId: Int, mode: AudioDenoise): AudioDenoiser {
            if (mode == AudioDenoise.OFF) return INACTIVE
            if (sessionId == 0) {
                Log.w(TAG, "AudioRecord 未给出有效 sessionId=0，跳过降噪（继续录音）")
                return INACTIVE
            }
            val ns = try {
                NoiseSuppressor.create(sessionId)
            } catch (e: Exception) {
                // IllegalArgumentException（无效 session）/ UnsupportedOperationException（无该效果）
                // / RuntimeException（厂商实现异常）——三种都只降级，不打断录音
                Log.w(TAG, "NoiseSuppressor.create 失败：${e.javaClass.simpleName} ${e.message}")
                null
            }
            if (ns == null) {
                Log.w(TAG, "本机不提供 NoiseSuppressor（create 返回 null），按无降噪继续")
                return INACTIVE
            }
            val defaultOn = runCatching { ns.enabled }.getOrDefault(false)
            val enabled = if (defaultOn) {
                true
            } else {
                runCatching { ns.enabled = true; ns.enabled }.getOrDefault(false)
            }
            if (!enabled) {
                Log.w(TAG, "NoiseSuppressor 存在但启用失败（默认=$defaultOn），按无降噪继续")
                runCatching { ns.release() }
                return INACTIVE
            }
            Log.i(TAG, "环境音降噪已启用（默认态=$defaultOn，实际=$enabled，session=$sessionId）")
            return AudioDenoiser(ns, active = true)
        }
    }
}
