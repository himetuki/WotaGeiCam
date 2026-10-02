package com.wotagei.cam.core

import android.graphics.ImageFormat
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import kotlin.math.abs

/** 需求表里的一档分辨率（比例沿用需求文档给的字面标签） */
data class ReqSize(val width: Int, val height: Int, val aspect: String) {
    fun toSize(): Size = Size(width, height)
    val pixels: Long get() = width.toLong() * height.toLong()
    override fun toString(): String = "${width}x$height($aspect)"
}

/**
 * 候选表（参考表，最终可选项 = 与设备表求交）：需求.md「视频录制分辨率」19 档。
 * 按宽度降序排列。
 *
 * ⚠ 这里只放**产品需求档位**，不许写死任何机型尺寸（10-01 用户纠正：项目面向所有新安卓，
 * 禁止按测试机写死参数）。10-01 用户指令的「原相机全屏分辨率」档不进本表——它在
 * [buildVideoSizeTable] 里按设备屏幕比例**运行时推导**（传 `screenAspect`），
 * 换任何机型都会推出该机自己的全屏档。
 */
object VideoSizeCandidates {
    /** 设备尺寸与需求档位的容差：HAL 常给出 4032x2268 之类与 4032x2272 差几像素的近似档 */
    const val TOLERANCE_PX = 2

    /** 「设备支持但表内没有的最高档」补几项（02 文件第 4 节第 2 步） */
    const val EXTRA_TOP_COUNT = 1

    val CANDIDATES: List<ReqSize> = listOf(
        ReqSize(7680, 4320, "16:9"),
        ReqSize(4096, 3072, "4:3"),
        ReqSize(4096, 2304, "16:9"),
        ReqSize(4080, 3072, "4:3"),
        ReqSize(4080, 3060, "4:3p"),
        ReqSize(4032, 3024, "4:3"),
        ReqSize(4032, 2272, "16:9"),
        ReqSize(4000, 3000, "4:3"),
        ReqSize(3840, 2160, "16:9"),
        ReqSize(3648, 2736, "4:3"),
        ReqSize(3440, 2448, "4:3"),
        ReqSize(3264, 2448, "4:3"),
        ReqSize(3024, 3024, "1:1"),
        ReqSize(1920, 1440, "4:3"),
        ReqSize(1920, 1080, "16:9"),
        ReqSize(1280, 720, "16:9"),
        ReqSize(1200, 720, "5:3"),
        ReqSize(960, 720, "4:3"),
        ReqSize(720, 720, "1:1")
    )
}

/** 帧周期查不到（尺寸不在设备上任何输出表里）时的哨兵值 */
const val SIZE_UNSUPPORTED_DURATION: Long = -1L

// 常用比例表按比值升序；20:9 是通用比例标签（10-01 用户指令补的全屏档在此展示），
// 只是"这个比值叫什么"的登记，不是机型参数——任何机型的同比档都会命中这条标签
private val STANDARD_RATIOS = listOf(
    "1:1" to 1.0, "5:3" to 5.0 / 3.0, "4:3" to 4.0 / 3.0, "3:2" to 3.0 / 2.0,
    "16:10" to 1.6, "16:9" to 16.0 / 9.0, "18:9" to 2.0, "20:9" to 20.0 / 9.0, "21:9" to 21.0 / 9.0
)

/**
 * 屏幕宽高比（无向，长边/短边）：10-01「原相机全屏分辨率」档的运行时推导依据。
 * 竖横屏都归一到 ≥1 的比值——录像档天生长边在前（如 1920x1080），拿无向比才对得上。
 * 传 0 或负数返回 0（= 调用方还没量到屏幕，推导自动跳过）。
 */
fun screenAspectOf(screenW: Int, screenH: Int): Double {
    if (screenW <= 0 || screenH <= 0) return 0.0
    val a = screenW.toDouble() / screenH.toDouble()
    return if (a >= 1.0) a else 1.0 / a
}

/** 比例标签现算：先匹配常用比例（±0.02），否则 gcd 约分，避免机型怪尺寸显示 unknown */
fun aspectOf(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return "-"
    val ratio = w.toDouble() / h.toDouble()
    STANDARD_RATIOS.firstOrNull { abs(it.second - ratio) <= 0.02 }?.let { return it.first }
    val g = gcd(w, h).coerceAtLeast(1)
    return "${w / g}:${h / g}"
}

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

fun sizeCloseTo(a: Size?, b: Size?, tolerancePx: Int = VideoSizeCandidates.TOLERANCE_PX): Boolean {
    if (a == null || b == null) return false
    return abs(a.width - b.width) <= tolerancePx && abs(a.height - b.height) <= tolerancePx
}

/**
 * 换镜头时把「用户要的那档」在新镜头能力表里落档。
 * 顺序：原样能给 → 同画幅比例里不超过原像素量的最大档 → 同比例里最接近的一档 →
 * 新镜头根本没有同比例档时，才退回与比例无关的最接近档。
 * 关键是**按用户要的档位求解、不按上一镜头实际生效的档位求解**，否则每换一次镜头
 * 就单向往下棘轮一次（1920x1080 → 1280x720 → 切回来再也回不去）。
 */
fun resolveSizeFor(
    want: Size,
    available: List<Size>,
    tolerancePx: Int = VideoSizeCandidates.TOLERANCE_PX
): Size? {
    if (available.isEmpty()) return null
    available.firstOrNull { sizeCloseTo(it, want, tolerancePx) }?.let { return it }
    val wantAspect = aspectOf(want.width, want.height)
    val pool = available.filter { aspectOf(it.width, it.height) == wantAspect }.ifEmpty { available }
    val target = want.pixels
    return pool.filter { it.pixels <= target }.maxByOrNull { it.pixels }
        ?: pool.minByOrNull { abs(it.pixels - target) }
        ?: available.minByOrNull { it.pixels }
}

/** 一档 UI 可展示的分辨率选项；supported=false 即需求档位本机不支持（灰显） */
data class VideoSizeOption(
    val size: Size,
    val aspect: String,
    val inRequirement: Boolean,
    val supported: Boolean,
    val feasibleFps: List<Int>,
    val minFrameDurationNs: Long,
    val highSpeed: Boolean
) {
    /** 能否跑到目标帧率：高速专用尺寸（120/240）走 highSpeed 标记，不参与 24/25 可行性判断 */
    fun canRun(fps: Int): Boolean = fps in feasibleFps

    /** 必须帧率优先展示：命中需求表 + 支持 + 能跑必须档 三者叠加成分组序 */
    val displayRank: Int
        get() = when {
            !supported -> 3
            feasibleFps.isNotEmpty() -> 0
            else -> 1
        }
}

/** 分辨率档位表：UI 顶部「本机支持范围 …～」文案数据 + 可选项列表 */
data class VideoSizeTable(
    val options: List<VideoSizeOption>,
    val maxSize: Size?,
    val minSize: Size?,
    val aspects: List<String>,
    val requiredFps: List<Int>,
    val fixedFps: List<Int>
) {
    val supported: List<VideoSizeOption> get() = options.filter { it.supported }
    val unsupported: List<VideoSizeOption> get() = options.filter { !it.supported }

    fun optionOf(size: Size, tolerancePx: Int = VideoSizeCandidates.TOLERANCE_PX): VideoSizeOption? =
        options.firstOrNull { sizeCloseTo(it.size, size, tolerancePx) }

    fun isSupported(size: Size, tolerancePx: Int = VideoSizeCandidates.TOLERANCE_PX): Boolean =
        optionOf(size, tolerancePx)?.supported == true

    /** 换挡后回填默认尺寸用：优先「支持且能跑必须帧率」的最大档 */
    fun firstUsable(): Size? {
        val usableList = options.filter { it.supported }
        return usableList.firstOrNull { it.feasibleFps.isNotEmpty() }?.size
            ?: usableList.maxByOrNull { it.size.pixels }?.size
    }
}

/**
 * 纯函数：需求候选表 × 设备输出表求交（±2 像素容差）。
 *
 * @param minDurationNs 该尺寸的最短帧周期；用 [videoSizeTable] 接 StreamConfigurationMap，
 *        单测可直接注入 lambda，不需要 Android 环境。
 * @param screenAspect 设备屏幕宽高比（[screenAspectOf] 归一值；0/null = 不推导全屏档）。
 *        10-01 用户指令「分辨率增加原相机全屏分辨率」的**机型无关**实现：
 *        在设备能力表里找与屏幕同比、像素最大的档进表——换任何机型推出的都是该机自己的
 *        全屏档，不写死任何尺寸（10-01 用户纠正：禁止按测试机写死参数）。
 *        该档与需求档同级排序（inRequirement=true，与 10-01 指令的档位地位一致）。
 */
fun buildVideoSizeTable(
    a: CameraAbility,
    minDurationNs: (Size) -> Long = { SIZE_UNSUPPORTED_DURATION },
    requiredFps: List<Int> = WotaTiers.REQUIRED_FPS.sorted(),
    tolerancePx: Int = VideoSizeCandidates.TOLERANCE_PX,
    screenAspect: Double? = null
): VideoSizeTable {
    val device = a.videoSizes
    val options = mutableListOf<VideoSizeOption>()

    for (cand in VideoSizeCandidates.CANDIDATES) {
        // 命中时以设备实际尺寸为准（录像面按它申请才不会 IllegalArgumentException）
        val match = device.firstOrNull { sizeCloseTo(it, cand.toSize(), tolerancePx) }
        options += optionOf(cand.toSize(), match, cand.aspect, true, minDurationNs, requiredFps, a, tolerancePx)
    }

    // 全屏档（10-01 指令）：屏幕同比里像素最大的设备档；已与需求档求交过的（或屏幕还没量到）
    // 就不加——防同一尺寸出两行，也防 4:3 屏上把需求表已有的最大 4:3 档重复进表
    if (screenAspect != null && screenAspect > 0.0) {
        val fullScreen = device
            .filter { it.width > 0 && it.height > 0 }
            .filter {
                val ratio = it.width.toDouble() / it.height.toDouble()
                val unoriented = if (ratio >= 1.0) ratio else 1.0 / ratio
                abs(unoriented - screenAspect) <= ASPECT_RATIO_TOLERANCE * screenAspect
            }
            .maxByOrNull { it.pixels }
        if (fullScreen != null && options.none { sizeCloseTo(it.size, fullScreen, tolerancePx) }) {
            options += optionOf(
                fullScreen, fullScreen, null, true,
                minDurationNs, requiredFps, a, tolerancePx
            )
        }
    }

    val extras = device
        .filter { d -> options.none { sizeCloseTo(d, it.size, tolerancePx) } }
        .sortedByDescending { it.pixels }
        .take(VideoSizeCandidates.EXTRA_TOP_COUNT)
    extras.forEach { options += optionOf(it, it, null, false, minDurationNs, requiredFps, a, tolerancePx) }

    val sorted = options.sortedWith(
        compareBy<VideoSizeOption> { it.displayRank }
            .thenByDescending { it.inRequirement }
            .thenByDescending { it.size.pixels }
    )

    val usable = sorted.filter { it.supported && it.feasibleFps.isNotEmpty() }
        .ifEmpty { sorted.filter { it.supported } }
    val maxSize = usable.maxByOrNull { it.size.pixels }?.size
    val minSize = usable.minByOrNull { it.size.pixels }?.size
    val supportedAspects = sorted.filter { it.supported }.map { it.aspect }.distinct()
    val orderedAspects = WotaTiers.ASPECTS.filter { it in supportedAspects } +
        supportedAspects.filter { it !in WotaTiers.ASPECTS }

    return VideoSizeTable(
        options = sorted,
        maxSize = maxSize,
        minSize = minSize,
        aspects = orderedAspects,
        requiredFps = requiredFps,
        fixedFps = requiredFps.filter { a.hasFixedFps(it) }
    )
}

private fun optionOf(
    reqSize: Size,
    deviceSize: Size?,
    reqAspect: String?,
    inRequirement: Boolean,
    minDurationNs: (Size) -> Long,
    requiredFps: List<Int>,
    a: CameraAbility,
    tolerancePx: Int
): VideoSizeOption {
    val supported = deviceSize != null
    val size = deviceSize ?: reqSize
    val duration = if (supported) minDurationNs(size) else SIZE_UNSUPPORTED_DURATION
    val feasible: List<Int> =
        if (duration > 0) requiredFps.filter { it > 0 && duration <= WotaTiers.NS_PER_SECOND / it }
        else emptyList()
    return VideoSizeOption(
        size = size,
        aspect = reqAspect ?: aspectOf(size.width, size.height),
        inRequirement = inRequirement,
        supported = supported,
        feasibleFps = feasible,
        minFrameDurationNs = duration,
        highSpeed = a.highSpeedSizes.any { sizeCloseTo(it, size, tolerancePx) }
    )
}

/** Android 适配层：把 StreamConfigurationMap 的帧周期查表接进来（02 文件第 4 节第 1 步） */
fun videoSizeTable(
    a: CameraAbility,
    map: StreamConfigurationMap,
    requiredFps: List<Int> = WotaTiers.REQUIRED_FPS.sorted(),
    screenAspect: Double? = null
): VideoSizeTable = buildVideoSizeTable(a, { s -> minFrameDurationNs(map, s) }, requiredFps, screenAspect = screenAspect)

/**
 * DIRECT 预览流的面积上限（06 文档 §6 降热：预览不必超过 1080p 档）。
 * 这是产品功耗策略常量，不是机型数值——具体挑哪一档仍由设备能力表决定。
 */
const val DIRECT_PREVIEW_MAX_PIXELS = 1920L * 1080L

/** 宽高比一致性判定容差：HAL 常给 1920x1088 / 4160x1872 这类近似档 */
private const val ASPECT_RATIO_TOLERANCE = 0.02

/**
 * 纯函数：给 DIRECT 直显挑一条预览流尺寸（不交给 HAL 自选，否则会被拉成视图比例导致失真）。
 *
 * 规则（全部基于能力表，无机型硬编码）：
 * 1. 宽高比必须与录像档一致（±2%），保证预览与录出同框；
 * 2. 面积不超过 `min(录像档, [DIRECT_PREVIEW_MAX_PIXELS])`——预览流不该比录像流还大；
 * 3. 在满足前两条的档里取「面积不小于承载视图」的最小档（够清晰又省电）；
 *    全部小于视图面积时退到最大档（此时靠等比适配加黑边，仍不失真）。
 *
 * @param candidates 该镜头的 PRIVATE 表单表尺寸（[CameraAbility.previewSizes]）
 * @param record 当前录像档尺寸
 * @param viewWidthPx 承载视图宽（px），0 表示尚未布局
 */
fun pickDirectPreviewSize(
    candidates: List<Size>,
    record: Size,
    viewWidthPx: Int,
    viewHeightPx: Int,
    maxPixels: Long = DIRECT_PREVIEW_MAX_PIXELS
): Size? {
    if (candidates.isEmpty() || record.width <= 0 || record.height <= 0) return null
    val ratio = record.width.toDouble() / record.height.toDouble()
    val sameAspect = candidates.filter {
        val r = it.width.toDouble() / it.height.toDouble()
        abs(r - ratio) <= ASPECT_RATIO_TOLERANCE * ratio
    }
    val ceiling = minOf(record.pixels, maxPixels)
    val pool = (sameAspect.ifEmpty { candidates }).filter { it.pixels in 1..ceiling }
    if (pool.isEmpty()) return null
    val viewArea = viewWidthPx.toLong() * viewHeightPx.toLong()
    // 视图还没布局（面积 0）时不能按「不小于视图」筛，否则会一路退到最小档
    if (viewArea <= 0L) return pool.maxByOrNull { it.pixels }
    return pool.filter { it.pixels >= viewArea }.minByOrNull { it.pixels } ?: pool.maxByOrNull { it.pixels }
}

/**
 * 该尺寸能跑的最短帧周期。PRIVATE 表覆盖 16:9 视频档、JPEG 表覆盖 4:3 照片档，
 * 同一尺寸在两张表里的周期可能不同，取较小值（= 最好情况）判断帧率可行性。
 */
fun minFrameDurationNs(map: StreamConfigurationMap, size: Size): Long {
    var best = SIZE_UNSUPPORTED_DURATION
    for (format in intArrayOf(ImageFormat.PRIVATE, ImageFormat.JPEG)) {
        val duration = try {
            map.getOutputMinFrameDuration(format, size.toAndroidSize())
        } catch (e: Throwable) {
            SIZE_UNSUPPORTED_DURATION
        }
        if (duration > 0 && (best <= 0 || duration < best)) best = duration
    }
    return best
}

/** 调试用：打印档位表上下限、可用比例与 24/25 固定档判定 */
fun dumpVideoSizeTable(table: VideoSizeTable) {
    val tag = WotaTag.VIDEO_SIZE
    Log.i(tag, "sizeTable options=${table.options.size} supported=${table.supported.size}" +
        " max=${table.maxSize.fmtPlain()} min=${table.minSize.fmtPlain()} aspects=${table.aspects}")
    Log.i(tag, "sizeTable requiredFps=${table.requiredFps} fixedFps=${table.fixedFps}")
    table.options.take(24).forEach {
        Log.i(tag, "size ${it.size.fmtPlain()}(${it.aspect}) supported=${it.supported}" +
            " required=${it.inRequirement} minDuration=${it.minFrameDurationNs} feasibleFps=${it.feasibleFps}" +
            " highSpeed=${it.highSpeed}")
    }
}

private fun Size?.fmtPlain(): String = if (this == null) "none" else "${width}x$height"
