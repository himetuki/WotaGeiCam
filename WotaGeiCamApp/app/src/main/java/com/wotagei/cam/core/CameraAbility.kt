package com.wotagei.cam.core

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import java.util.Locale

/** 整数能力范围；缺失能力用 CLOSED 标记，不伪造范围（02 文件第 4 节） */
data class RangeI(val lo: Int, val hi: Int) {
    fun ok() = hi > 0 && lo <= hi
    fun closed() = !ok()
    fun clamp(v: Int): Int = if (closed()) v else v.coerceIn(lo, hi)
    fun asRange(): ClosedRange<Int> = lo..hi
    override fun toString(): String = if (closed()) "closed" else "$lo..$hi"

    companion object {
        val CLOSED = RangeI(0, 0)
        fun of(r: android.util.Range<Int>?): RangeI =
            if (r == null) CLOSED else RangeI(r.lower, r.upper)

        /** 纳秒级范围（SENSOR_INFO_EXPOSURE_TIME_RANGE 是 Range<Long>）；上限远小于 Int.MAX（1/10s 钳制） */
        fun ofLong(r: android.util.Range<Long>?): RangeI =
            if (r == null) CLOSED
            else RangeI(r.lower.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                r.upper.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
    }
}

/** 浮点能力范围（变焦倍率等） */
data class RangeF(val lo: Float, val hi: Float) {
    fun ok() = hi > 0f && lo <= hi
    fun closed() = !ok()
    fun clamp(v: Float): Float = if (closed()) v else v.coerceIn(lo, hi)
    fun asRange(): ClosedFloatingPointRange<Float> = lo..hi
    override fun toString(): String = if (closed()) "closed" else "$lo..$hi"

    companion object {
        val CLOSED = RangeF(0f, 0f)
        fun of(r: android.util.Range<Float>?): RangeF =
            if (r == null) CLOSED else RangeF(r.lower, r.upper)
    }
}

/** 单个镜头的 CameraCharacteristics 一次性收全并缓存（切镜头 = 整套换挡） */
@Suppress("ArrayInDataClass") // availableSurfaceFormats 只做能力探测，不参与结构相等判断
data class CameraAbility(
    val hardwareLevel: Int,
    val sensorOrientation: Int,
    val activeArray: Rect,
    val jpegSizes: List<Size>,
    val highSpeedSizes: List<Size>,
    val videoSizes: List<Size>,
    val fpsRanges: List<RangeI>,
    val highSpeedFps: Map<Size, List<RangeI>>,
    val iso: RangeI,
    val exposureNs: RangeI,
    val evRange: RangeI,
    val evStep: Float,
    val afModes: Set<Int>,
    val awbModes: Set<Int>,
    val flashAvailable: Boolean,
    val maxRegionsAf: Int,
    val maxRegionsAe: Int,
    val minFocusDistanceDiopter: Float,
    val zoomRatioRange: RangeF?,
    val maxDigitalZoom: Float,
    val oisAvailable: Boolean,
    val eisAvailable: Boolean,
    val highSpeedCapable: Boolean,
    val availableSurfaceFormats: IntArray,
    /**
     * `ImageFormat.PRIVATE` 单表尺寸（预览/直显面专用）。
     * [videoSizes] 是 PRIVATE ∪ JPEG，直接拿去开预览流可能命中只有 JPEG 支持的档；
     * DIRECT 模式挑预览流尺寸必须用这张单表。默认空表兼容旧构造点。
     */
    val previewSizes: List<Size> = emptyList()
) {
    /** LEGACY 设备无逐帧控制/无 ZOOM_RATIO，UI 顶部要提示能力受限 */
    fun isLegacy(): Boolean = hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

    fun hasFixedFps(fps: Int): Boolean = fpsRanges.any { it.lo == fps && it.hi == fps }

    fun fixedTiers(tiers: Set<Int>): Set<Int> = tiers.filter { hasFixedFps(it) }.toSet()

    /** 手动曝光三件套里的 ISO+快门是否可用（CameraCharacteristics 未给范围即不可手动） */
    fun manualExposurePossible(): Boolean = iso.ok() && exposureNs.ok()

    /**
     * 帧率范围按尺寸分流：高速档是「尺寸 → 范围」表，
     * size 为空或该尺寸未列入高速表时，退化成所有高速尺寸的并集，仅用于档位可用性判断。
     */
    fun fpsRangesFor(size: Size?, highSpeed: Boolean): List<RangeI> {
        if (!highSpeed || !highSpeedCapable || highSpeedFps.isEmpty()) return fpsRanges
        if (size == null) return highSpeedFps.values.flatten().distinct()
        val exact = highSpeedFps.entries.firstOrNull { sizeCloseTo(it.key, size, VideoSizeCandidates.TOLERANCE_PX) }
        return exact?.value ?: emptyList()
    }

    /** 录像尺寸上下限（UI「本机支持范围 …～」文案数据源） */
    fun videoBounds(): Pair<Size?, Size?> {
        if (videoSizes.isEmpty()) return null to null
        return videoSizes.maxByOrNull { it.pixels } to videoSizes.minByOrNull { it.pixels }
    }

    /**
     * 调试用能力 dump（M1 门禁）。上下限/固定帧率档都在此打印，真机 `adb logcat -s WotaAbility` 一眼可核。
     * @param label 镜头标识（cameraId / 档位名），CameraAbility 契约里没有 id 字段所以由调用方传入
     */
    fun dump(label: String = "") {
        val tag = WotaTag.ABILITY
        val (maxSize, minSize) = videoBounds()
        Log.i(tag, "ability[$label] level=$hardwareLevel legacy=${isLegacy()} orient=${sensorOrientation}deg" +
            " activeArray=${activeArray.arrayWidth}x${activeArray.arrayHeight}")
        Log.i(tag, "ability[$label] iso=$iso exposureNs=$exposureNs ev=$evRange evStep=$evStep" +
            " minFocusD=$minFocusDistanceDiopter zoomRatio=$zoomRatioRange maxDigital=$maxDigitalZoom")
        Log.i(tag, "ability[$label] afModes=$afModes awbModes=$awbModes flash=$flashAvailable" +
            " regionsAf=$maxRegionsAf regionsAe=$maxRegionsAe ois=$oisAvailable eis=$eisAvailable")
        Log.i(tag, "ability[$label] fpsRanges=${fpsRanges.joinToString { "[${it.lo},${it.hi}]" }}" +
            " fixed24=${hasFixedFps(24)} fixed25=${hasFixedFps(25)} highSpeedCapable=$highSpeedCapable")
        Log.i(tag, "ability[$label] videoSizes=${videoSizes.size} max=${maxSize.fmt()}(${maxSize.aspect()})" +
            " min=${minSize.fmt()}(${minSize.aspect()}) jpeg=${jpegSizes.size} preview=${previewSizes.size}" +
            " highSpeedSizes=${highSpeedSizes.size}")
        Log.i(tag, "ability[$label] formats=${availableSurfaceFormats.joinToString()}")
        if (highSpeedFps.isNotEmpty()) {
            highSpeedFps.entries.take(8).forEach { (s, rs) ->
                Log.i(tag, "ability[$label] highSpeed ${s.fmt()} -> ${rs.joinToString { "[${it.lo},${it.hi}]" }}")
            }
        }
    }

    companion object {
        /**
         * 读全一个镜头的能力范围。所有范围/档位/能力开关一律来自 characteristics，无机型硬编码。
         * 单项读取失败（部分 HAL 会抛异常）时退化为 closed/空集合，不影响其余能力收集。
         * @param cameraId 仅用于日志关联（能力表本身不保存 id）
         */
        fun from(cameraId: String, cc: CameraCharacteristics): CameraAbility {
            val map = tryOrNull { cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
            if (map == null) Log.w(WotaTag.ABILITY, "ability[$cameraId] stream config map missing")
            val capabilities = tryOrNull { cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) } ?: IntArray(0)
            val highSpeedCapable =
                capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)

            val jpegSizes = sizesOf(map) { it.getOutputSizes(ImageFormat.JPEG) }
            // PRIVATE = 预览/GL/录像共用面；16:9 视频档通常只出现在此表，4:3 照片档只出现在 JPEG 表，
            // 取并集才不会把需求表里的 16:9 候选全判成不支持
            val previewSizes = sizesOf(map) { it.getOutputSizes(ImageFormat.PRIVATE) }
                .sortedWith(compareByDescending<Size> { it.pixels }.thenByDescending { it.width })
            val videoSizes = (previewSizes + jpegSizes).distinct().sortedWith(
                compareByDescending<Size> { it.pixels }.thenByDescending { it.width }.thenByDescending { it.height }
            )
            val highSpeedSizes =
                if (highSpeedCapable) sizesOf(map) { it.highSpeedVideoSizes } else emptyList()
            val highSpeedFps: Map<Size, List<RangeI>> = if (highSpeedCapable) {
                highSpeedSizes.associateWith { s ->
                    rangesOf(tryOrNull { map?.getHighSpeedVideoFpsRangesFor(s.toAndroidSize()) })
                }
            } else emptyMap()

            val zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                RangeF.of(tryOrNull { cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) })
            } else RangeF.CLOSED
            val stepRational = tryOrNull { cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) }

            return CameraAbility(
                hardwareLevel = tryOrNull { cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }
                    ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                sensorOrientation = tryOrNull { cc.get(CameraCharacteristics.SENSOR_ORIENTATION) } ?: 0,
                activeArray = tryOrNull { cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }
                    ?.toWotaRect() ?: Rect(0, 0, 0, 0),
                jpegSizes = jpegSizes.sortedByDescending { it.pixels },
                highSpeedSizes = highSpeedSizes,
                videoSizes = videoSizes,
                fpsRanges = rangesOf(tryOrNull { cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) })
                    .sortedWith(compareBy({ it.lo }, { it.hi })),
                highSpeedFps = highSpeedFps,
                iso = RangeI.of(tryOrNull { cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }),
                exposureNs = RangeI.ofLong(tryOrNull { cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }),
                evRange = RangeI.of(tryOrNull { cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) }),
                evStep = stepRational?.toFloat() ?: 0f,
                afModes = (tryOrNull { cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) } ?: IntArray(0)).toSet(),
                awbModes = (tryOrNull { cc.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) } ?: IntArray(0)).toSet(),
                flashAvailable = tryOrNull { cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } ?: false,
                maxRegionsAf = tryOrNull { cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) } ?: 0,
                maxRegionsAe = tryOrNull { cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) } ?: 0,
                minFocusDistanceDiopter = tryOrNull { cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) } ?: 0f,
                zoomRatioRange = if (zoomRatio.ok()) zoomRatio else null,
                maxDigitalZoom = tryOrNull { cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) } ?: 1f,
                oisAvailable = (tryOrNull { cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) }
                    ?: IntArray(0)).contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
                eisAvailable = (tryOrNull { cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) }
                    ?: IntArray(0)).contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
                highSpeedCapable = highSpeedCapable,
                availableSurfaceFormats = tryOrNull { map?.outputFormats } ?: IntArray(0),
                previewSizes = previewSizes
            )
        }

        private inline fun <T> tryOrNull(block: () -> T): T? = try { block() } catch (e: Throwable) { null }

        private fun sizesOf(
            map: StreamConfigurationMap?,
            block: (StreamConfigurationMap) -> Array<out AndroidSize>?
        ): List<Size> {
            if (map == null) return emptyList()
            return tryOrNull { block(map) }?.map { it.toWotaSize() } ?: emptyList()
        }

        private fun rangesOf(ranges: Array<out android.util.Range<Int>>?): List<RangeI> =
            ranges?.map { RangeI(it.lower, it.upper) } ?: emptyList()
    }
}

/** 目标帧率 → 实际写入 CONTROL_AE_TARGET_FPS_RANGE 的范围（固定 [f,f] 优先，避免 AE 漂移） */
data class FpsPick(val lo: Int, val hi: Int, val exact: Boolean)

/**
 * 快门「强制档」是否落在本机曝光范围之外 —— UI 打 `※` 的唯一判据（与下发侧的
 * `RequestApplier.clampShutterNs` 用同一个 [WotaTiers.isRequiredShutterNs]）。
 *
 * 能力缺失（[RangeI.closed]）时不算超范围：那种设备整条手动曝光都不可用，另有灰显路径，
 * 这里再打一个 `※` 只会变成噪音。
 */
fun forcedShutterOutOfRange(shutterNs: Long, exposureNs: RangeI): Boolean =
    exposureNs.ok() && WotaTiers.isRequiredShutterNs(shutterNs) &&
        shutterNs !in exposureNs.lo.toLong()..exposureNs.hi.toLong()

/**
 * 快门可用上限（纳秒）—— `WotaParams.applyExposure` 写进 [ParamState.range] 的那个数。
 *
 * 三步**取最小**（实现是 `minOf(...)` 一次求交，三步的先后顺序对结果没有影响）：
 * 1. **先把设备曝光上限抬到强制档**（1/24 = 41_666_666ns）：设备能力表里没有这两档时也要可选
 *    （与「本机没有 `[24,24]` 固定帧率范围也照样让 24fps 可选」一条对称口径）；
 * 2. 1/10s 防手抖上限；
 * 3. ≤1/帧周期——这一刀吃的是**实际生效的帧率上界** [fpsHi]，不是用户档位：25fps 的 40ms
 *    帧周期会把 1/24 挡在范围外；24fps 退化成非精确范围（如 [15,30]，上界 30）时，1/24 同样会被
 *    30fps 帧周期压回，这是刻意的（曝光长过帧周期只会丢帧，不是"设备表外"该豁免的那一刀）。
 *
 * 抽成纯函数是为了让上面这条口径**可被单测钉住**：否则它只是 `applyAbility` 里的一个副作用，
 * 谁把"抬到强制档"那一步删掉都没有用例会红（AGENTS：配置类纯函数必须配桥函数并测桥本身）。
 *
 * ⚠ 已知边界（刻意接受，记录在案）：抬上限会让 `ParamState.range` 比设备真实上限宽出一段，
 * 滑杆能落到「设备范围外、又不是那两档精确值」的空档里（1µs 粒度才够得着），这种值下发时仍会被
 * [com.wotagei.cam.camera.RequestApplier.clampShutterNs] 夹回设备上限——因为档位列表是**离散档**、
 * 而滑杆是连续域，一个闭区间表达不了"设备区间 ∪ 两个强制点"这种非连续集合。
 * 强制值请走档位胶囊（它给的是精确纳秒值，会按该值下发）。
 */
fun shutterCeilingNs(deviceMaxNs: Long, fpsHi: Int): Long {
    val raised = maxOf(deviceMaxNs, WotaTiers.REQUIRED_SHUTTER_NS.max())
    return minOf(raised, WotaTiers.SHUTTER_CAP_NS, WotaTiers.NS_PER_SECOND / fpsHi.coerceAtLeast(1))
}

/** 返回 null 表示设备没有任何范围能覆盖 target，UI 侧该档位灰显 */
fun pickFpsRange(ranges: List<RangeI>, target: Int): FpsPick? {
    if (ranges.isEmpty()) return null
    val fixed = ranges.firstOrNull { it.lo == target && it.hi == target }
    if (fixed != null) return FpsPick(fixed.lo, fixed.hi, true)
    val containing = ranges.filter { it.lo <= target && target <= it.hi }.maxByOrNull { it.hi } ?: return null
    return FpsPick(containing.lo, containing.hi, false)
}

/** 35mm 等效焦距：eq = round(43.27 / sqrt(sw²+sh²) × focal × 100) / 100；43.27 是全画幅对角线，物理常量非机型值 */
fun eqFocalMm(sensorWidthMm: Float, sensorHeightMm: Float, focalMm: Float): Float {
    if (sensorWidthMm <= 0f || sensorHeightMm <= 0f) return 0f
    val diagonal = Math.sqrt((sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm).toDouble())
    if (diagonal <= 0.0) return 0f
    return Math.round(43.27 / diagonal * focalMm * 100.0).toFloat() / 100f
}

/** 水平视场角（度），FOV_h = 2 × atan(sensorW / 2 / focal) */
fun hFovDeg(sensorWidthMm: Float, focalMm: Float): Float {
    if (sensorWidthMm <= 0f || focalMm <= 0f) return 0f
    return (2.0 * Math.atan(sensorWidthMm / 2.0 / focalMm) * 180.0 / Math.PI).toFloat()
}

/** 等效焦距 = characteristics 现算（sensor 尺寸取 SENSOR_INFO_PHYSICAL_SIZE，焦距取 LENS_INFO_AVAILABLE_FOCAL_LENGTHS 最短支） */
fun eqFocalMmOf(cc: CameraCharacteristics): Float {
    val sensor = tryOrNullStatic { cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) } ?: return 0f
    val focal = shortestFocalMm(cc) ?: return 0f
    return eqFocalMm(sensor.width, sensor.height, focal)
}

/** 水平视场角（LensSheet 展示用），同样是运行时现算，不落机型表 */
fun hFovOf(cc: CameraCharacteristics): Float {
    val sensor = tryOrNullStatic { cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) } ?: return 0f
    val focal = shortestFocalMm(cc) ?: return 0f
    return hFovDeg(sensor.width, focal)
}

internal fun shortestFocalMm(cc: CameraCharacteristics): Float? =
    (tryOrNullStatic { cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) })?.minOrNull()

private inline fun <T> tryOrNullStatic(block: () -> T): T? = try { block() } catch (e: Throwable) { null }

private fun Size?.fmt(): String = if (this == null) "none" else String.format(Locale.US, "%dx%d", width, height)
private fun Size?.aspect(): String = if (this == null) "-" else aspectOf(width, height)

/**
 * #44 定版：这颗镜头到底有没有「可改的对焦」。
 * 只有 `CONTROL_AF_MODE_OFF`（定焦）且没有手动屈光度域 → 界面上没有任何东西能调，
 * 于是对焦入口整颗隐藏，而不是摆一排灰选项骗用户点。
 */
fun adjustableFocus(afModes: Set<Int>, minFocusDistanceDiopter: Float): Boolean =
    afModes.any { it != android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF } ||
        minFocusDistanceDiopter > 0f

fun CameraAbility.hasAdjustableFocus(): Boolean = adjustableFocus(afModes, minFocusDistanceDiopter)
