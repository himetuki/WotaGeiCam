package com.wotagei.cam.core

/**
 * 降噪（2026-10-26 用户需求）：音频降噪 + 画面降噪，一枚胶囊管两件事，**默认全关**。
 *
 * # 为什么默认关
 * 音频降噪改变的是**声音本身**，画面降噪改变的是**像素本身**——两者都不是"只影响观感"的开关：
 * 降噪会把环境音的底噪（现场氛围的一部分）抹平，也会把夜空里的弱星点当噪声吃掉。
 * WOTA 艺拍摄里这两样都可能是作品的一部分，所以必须由用户显式打开。
 *
 * # 两条链路的实现选择（各取该平台适配性与效果最优的 1~3 档）
 *
 * 音频（针对录像时的环境音，[AudioDenoise]）：
 * - [AudioDenoise.OFF] 原样采集；
 * - [AudioDenoise.SYSTEM] 系统原生 `NoiseSuppressor`（按 AudioRecord 的 sessionId 挂载）。
 *   零依赖、功耗最低、所有机型都有实现（哪怕只是透传），是**默认打开时会选的那一档**。
 *   注：部分机型在 `VOICE_COMMUNICATION` 音源下默认就开着，本档显式启用/查询并记录实际状态。
 *
 * 画面（针对夜间暗部噪点，[VideoDenoise]）：
 * - [VideoDenoise.OFF] 原样出图；
 * - [VideoDenoise.HAL_FAST] 相机 HAL 的 `NOISE_REDUCTION_MODE_FAST`：硬件级、零额外功耗，
 *   适合实时视频流（Android 官方对视频流的推荐档）；
 * - [VideoDenoise.HAL_HIGH_QUALITY] HAL 的 `NOISE_REDUCTION_MODE_HIGH_QUALITY`：画质优先，
 *   高像素/高帧率下可能降帧，交给用户自己权衡；
 * - [VideoDenoise.GPU_BILATERAL] 自研 GLSL 双边滤波（边缘保持的空域降噪，专治夜间天空等
 *   暗部随机噪点）。**仅在 GPU 渲染模式下可用**：DIRECT 模式预视图不进 GL 管线，此档自动退回
 *   [VideoDenoise.HAL_FAST] 并在 UI 上说明。
 *
 * # 为什么不做多帧合成降噪
 * 多帧合成（对齐 + 融合）要求"同一场景多帧"，而 WOTA 艺拍摄是**持续运动**的长曝光光弧，
 * 帧间画面必然大幅位移，对齐失败就会拖影/糊弧。该路线登记为后续独立立项（见 docs/perf-backlog.md），
 * 不在本批实现——不是遗漏，是**运动场景下的效果不确定**。
 *
 * # 与录制引擎的关系
 * [AudioDenoise] 非 OFF 时，环境音必须走 [com.wotagei.cam.record.AudioFeeder]（即 Codec 引擎）
 * 才能挂上 NoiseSuppressor；MediaRecorder 路没有这个挂载点。引擎选型处据此把录制切到 Codec
 * （见 `Recorders.useCodecEngine`），这是产品语义下的**必要代价**，UI 上不额外提示（用户要的是降噪）。
 */
enum class AudioDenoise(val key: String, val labelRes: Int) {
    OFF("off", com.wotagei.cam.R.string.denoise_audio_off),
    SYSTEM("system", com.wotagei.cam.R.string.denoise_audio_system);

    companion object {
        val ALL: List<AudioDenoise> = values().toList()
        fun fromKey(raw: String?): AudioDenoise = ALL.firstOrNull { it.key == raw } ?: OFF
    }
}

enum class VideoDenoise(val key: String, val labelRes: Int) {
    OFF("off", com.wotagei.cam.R.string.denoise_video_off),
    HAL_FAST("hal_fast", com.wotagei.cam.R.string.denoise_video_hal_fast),
    HAL_HIGH_QUALITY("hal_hq", com.wotagei.cam.R.string.denoise_video_hal_hq),
    GPU_BILATERAL("gpu_bilateral", com.wotagei.cam.R.string.denoise_video_gpu);

    companion object {
        val ALL: List<VideoDenoise> = values().toList()
        fun fromKey(raw: String?): VideoDenoise = ALL.firstOrNull { it.key == raw } ?: OFF
    }
}

/**
 * 画面降噪的**实际生效档**（纯函数，JVM 全测）。
 *
 * 两件事在这里定型，UI 与引擎共用同一支，不许各写一份：
 * 1. DIRECT 渲染模式下 GL 管线不参与，[VideoDenoise.GPU_BILATERAL] 退回 [VideoDenoise.HAL_FAST]
 *    （不是静默失效：返回"实际档"，UI 据此显示"已退回 HAL 快速档"）；
 * 2. GPU_BILATERAL 只在 `gpuMode=true` 时成立。
 *
 * @param requested 用户在面板里选的档
 * @param gpuMode 当前渲染模式是否为 GPU
 * @return 实际生效的档；[VideoDenoise.OFF] 恒原样返回
 */
fun effectiveVideoDenoise(requested: VideoDenoise, gpuMode: Boolean): VideoDenoise =
    if (requested == VideoDenoise.GPU_BILATERAL && !gpuMode) VideoDenoise.HAL_FAST else requested

/** 是否需要把录制切到 Codec 引擎（环境音降噪的挂载前提）：音频降噪非 OFF 即需要 */
fun audioDenoiseNeedsCodec(audio: AudioDenoise): Boolean = audio != AudioDenoise.OFF

/**
 * 画面降噪**这一刻真的在降噪吗**（UI 的"生效中"标记 / HUD 胶囊选中态的唯一判据）。
 *
 * 三条口径，少一条界面就说谎：
 * 1. OFF ⇒ 没有；
 * 2. GPU 双边在**直连渲染**下退回 HAL 快速档 ⇒ **仍然算有降噪**（否则胶囊的选中态会跟着
 *    渲染模式乱闪）；HAL 档与相机请求有关、与渲染模式无关，照旧生效；
 * 3. GPU 双边在**监看叠加（斑马纹/峰值对焦）开着**时那条支路根本不存在——叠加层是判读工具，
 *    对它们降噪没有意义，引擎只在 effect=NONE 时才编双边 program（见 GlRenderEngine.buildProgram）。
 *    这时如实返回 false：胶囊不亮、面板另有一句说明（2026-10-26 修正，此前会亮着"生效中"）。
 */
fun videoDenoiseActive(
    requested: VideoDenoise,
    gpuMode: Boolean,
    monitorEffectOn: Boolean
): Boolean = when (val effective = effectiveVideoDenoise(requested, gpuMode)) {
    VideoDenoise.OFF -> false
    VideoDenoise.GPU_BILATERAL -> !monitorEffectOn
    else -> effective != VideoDenoise.OFF
}

/**
 * 该档在**相机请求侧**对应的 `NOISE_REDUCTION_MODE`；null = 这一档与"相机硬件降噪"无关。
 * [VideoDenoise.GPU_BILATERAL] 也返回 null —— 它在 HAL 侧按"不降噪"处理，由 GL 管线独占
 * （见 [noiseReductionModeToSend] 的判据 2：写过 HAL 档再切过来时要清残留，否则双重降噪）。
 */
fun halNoiseReductionMode(mode: VideoDenoise): Int? = when (mode) {
    VideoDenoise.HAL_FAST -> android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_FAST
    VideoDenoise.HAL_HIGH_QUALITY ->
        android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
    VideoDenoise.OFF, VideoDenoise.GPU_BILATERAL -> null
}

/**
 * 本次要下发的 `NOISE_REDUCTION_MODE`（**纯函数**，JVM 全测）：null = 一个键都不写。
 *
 * 三档语义，缺任何一档都会出问题：
 * 1. **HAL 档且本机声明过它** ⇒ 写它。没声明过的不写——发一个未声明的模式轻则被 HAL 忽略、
 *    重则报错，而且界面会"说开着其实没开"；
 * 2. **退出 HAL 档（关闭 / GPU 双边）且本场会话写过** ⇒ 写 `NOISE_REDUCTION_MODE_OFF` 清残留。
 *    `CaptureRequest.Builder` 每会话只建一次、跨多次下发复用，而 Camera2 **没有单键 remove**
 *    ⇒ 写过一次就一直留在请求里。不清的后果实测两条：①"先开 HAL 再关闭"关不掉（面板显示关闭、
 *    实际还在降噪）；②"先 HAL 再 GPU 双边"成了 HAL + GL **双重降噪**，与本模块"GPU 档在 HAL 侧
 *    按 OFF 处理"的设计直接矛盾。只有 OFF 也在声明表里才写（清不掉时由引擎记一条日志）；
 * 3. **从没写过就选关闭** ⇒ 不写。这档不能省：不写 = 不动厂商默认，而写 OFF 会把厂商自己开着
 *    的降噪**强关**（那是负优化）。
 *
 * @param requested 实际生效档（调用方先过 [effectiveVideoDenoise]，GPU 双边在直连下会变成 HAL 快速）
 * @param declared 本机声明的模式集合（空 = 未声明 ⇒ 一律不写）
 * @param previouslyWritten 本场会话是否已经写过该键；生命周期与 builder 一致
 *   （引擎在"每会话新建一次 builder"处复位）
 */
fun noiseReductionModeToSend(
    requested: VideoDenoise,
    declared: Set<Int>,
    previouslyWritten: Boolean
): Int? {
    if (declared.isEmpty()) return null
    val mode = halNoiseReductionMode(requested)
    if (mode != null) return if (mode in declared) mode else null
    if (!previouslyWritten) return null
    val off = android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_OFF
    return if (off in declared) off else null
}

/**
 * 画面降噪的**负载分档**（纯函数）：编码面「像素 × 帧率」超过阈值 ⇒ 用轻量核（4 邻域、5 次采样；
 * 完整核是 9 次）。4K30 / 1080p120 / 4K60 / 1080p240 这类组合自动落到轻量档。
 *
 * 为什么按这个量而不是按 GPU 型号：像素率是"每帧采样次数"的正比量，与"这张卡跑不跑得动"直接相关，
 * 且完全能从录制参数算出来——不需要真机探测、也不需要维护机型名单（名单一发新机就过时）。
 * 阈值取在 1080p60（1.24e8 像素/秒）与 1440p30（1.11e8）之上，所以常见档位只有上面那几组会降档。
 *
 * 语义边界（**自动降档 ≠ 关档**）：核变小、仍然降噪；用户选的档位、面板文案、持久化值都不变。
 * 录制开始后界面会提示一句"已按本机负载自动用轻量降噪"——所有自动降档都要让用户看得见。
 */
fun denoiseKernelLite(widthPx: Int, heightPx: Int, fps: Int): Boolean {
    if (widthPx <= 0 || heightPx <= 0 || fps <= 0) return false
    return widthPx.toLong() * heightPx.toLong() * fps.toLong() > DENOISE_FULL_KERNEL_PIXEL_RATE
}

/** 完整核（9 次采样）的负载上限：1.5e8 像素/秒（1080p60 = 1.24e8 留在完整档） */
const val DENOISE_FULL_KERNEL_PIXEL_RATE = 150_000_000L
