package com.wotagei.cam.core

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import com.wotagei.cam.diag.Diag as Log
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
    val previewSizes: List<Size> = emptyList(),
    /**
     * 本机可用的 `NOISE_REDUCTION_MODE` 集合（2026-10-26 画面降噪）。
     * 空表 = HAL 未声明（[android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_NOISE_REDUCTION_MODES]
     * 缺失或不含任何有效项），此时 UI 的 HAL 两档按不可用处理——不猜、不下发未声明能力。
     */
    val noiseReductionModes: Set<Int> = emptySet(),
    /**
     * 本机可用的 `COLOR_CORRECTION_MODE` 集合（2026-10-26 反馈 #6）。
     *
     * 手动白平衡（色温/色调增益）的**契约前提**是 `TRANSFORM_MATRIX`：AWB_MODE=OFF 只关自动
     * 白平衡，增益要被采纳必须同时进入变换矩阵模式——该机 HAL 没声明 TRANSFORM_MATRIX 时
     * （华为 ALN-AL00 实测变绿+滑杆失效），系统回合退 FAST/HQ，增益被静默忽略。
     *
     * 采集走**键名匹配**而不是静态常量：`android.colorCorrection.availableModes` 这枚键在
     * 公开 Java API 里没有静态字段（android-34 jar 只有 ABERRATION_MODES 那枚），但键本身
     * 在能力表里、`getKeys()` 按名字就能拿到。空集合 = 拿不到（隐藏键被裁/LEGACY）⇒ 手动档
     * 按不可用处理——与"增益不被采纳"的实际行为一致。
     */
    val colorCorrectionModes: Set<Int> = emptySet()
) {
    /** LEGACY 设备无逐帧控制/无 ZOOM_RATIO，UI 顶部要提示能力受限 */
    fun isLegacy(): Boolean = hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

    fun hasFixedFps(fps: Int): Boolean = fpsRanges.any { it.lo == fps && it.hi == fps }

    fun fixedTiers(tiers: Set<Int>): Set<Int> = tiers.filter { hasFixedFps(it) }.toSet()

    /** 手动曝光三件套里的 ISO+快门是否可用（CameraCharacteristics 未给范围即不可手动） */
    fun manualExposurePossible(): Boolean = iso.ok() && exposureNs.ok()

    /**
     * 手动白平衡（色温/色调增益）在本机是否真能生效（2026-10-26 反馈 #6）：
     * 契约前提是 HAL 声明了 `TRANSFORM_MATRIX`。空集合 = 隐藏键拿不到（无法判定）
     * ⇒ 视为可用，保持既有行为（不因"查不到"而把用户已选的手动档弹掉）。
     */
    fun manualWhiteBalancePossible(): Boolean =
        colorCorrectionModes.isEmpty() ||
            colorCorrectionModes.contains(CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)

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

    /**
     * 档位可用性判定域：普通 ∪ 高速（按尺寸）并集——档位「能不能选」看它。
     * 厂商常把 50/60 只放进高速表（如 vivo V2520A：普通流仅 [24,24]/[30,30]，
     * 高速 1080p 给 [30,120]/[60,120]/[120,120]/[30,240]/[60,240]/[240,240]），
     * 按当前会话模态判定会让 50/60/120/240 永远灰显（2026-10-26 用户报障）。
     * 会话内的下发 / 钳制仍按单模态 [fpsRangesFor]（当前会话实际给得到的范围）。
     */
    fun fpsRangesUnion(size: Size?): List<RangeI> = fpsRangesUnion(fpsRanges, fpsRangesFor(size, true))

    /** 该帧率普通会话覆盖不了、但高速表（按尺寸）覆盖 ⇒ 需要高速会话（结构性换挡） */
    fun needsHighSpeed(size: Size?, fps: Int): Boolean =
        needsHighSpeedFps(fpsRanges, fpsRangesFor(size, true), fps)

    /**
     * 一档的**完整决议**：用哪张表、下发哪个区间、精不精确、要不要高速会话。
     * UI 的可选性/※ 标记、参数总线落档、引擎开会话与下发，四处都只认这一个出口
     * （2026-10-26 三处判据分叉那次事故的根治：各写一份就会"界面说可选、请求发的是非法值"）。
     */
    fun decideFps(size: Size?, fps: Int): FpsDecision? =
        decideFps(fpsRanges, fpsRangesFor(size, true), fps)

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
        // 画面降噪的**唯一能力依据**（2026-10-26）：0=OFF 1=FAST 2=HIGH_QUALITY 4=ZERO_SHUTTER_LAG。
        // 面板"HAL 快速/HAL 高质量"能不能选、下发会不会被 RequestApplier 拦掉，都只看这一行；
        // 空集合 = 未声明 ⇒ 两档都必须灰显（判"用户说开了却没效果"时先核这里）
        Log.i(tag, "ability[$label] noiseReductionModes=${noiseReductionModes.sorted().joinToString()}" +
            if (noiseReductionModes.isEmpty()) "（未声明 ⇒ HAL 两档不可用）" else "")
        Log.i(tag, "ability[$label] colorCorrectionModes=${colorCorrectionModes.sorted().joinToString()}" +
            if (colorCorrectionModes.contains(CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)) ""
            else "（未声明 TRANSFORM_MATRIX ⇒ 手动白平衡增益不被采纳）")
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

            // 画面降噪（2026-10-26）：HAL 声明的降噪模式是「能不能开」的唯一依据
            val noiseReductionModes =
                (tryOrNull { cc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) }
                    ?: IntArray(0)).toSet()
            // 手动白平衡的门控依据（反馈 #6）：隐藏键按名字取（公开 API 无静态字段，见字段注）
            val colorCorrectionModes = tryOrNull {
                @Suppress("UNCHECKED_CAST")
                val key = cc.keys.firstOrNull { it.name == "android.colorCorrection.availableModes" }
                    as? CameraCharacteristics.Key<IntArray>
                key?.let { cc.get(it) } ?: IntArray(0)
            }?.toSet() ?: emptySet()
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
                previewSizes = previewSizes,
                noiseReductionModes = noiseReductionModes,
                colorCorrectionModes = colorCorrectionModes
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
 * 3. ≤1/帧周期——这一刀吃的是**帧率档位** [fpsHi]（2026-10-04 r11 起与下发侧 `exposurePlan`
 *    同源：下发帧周期也按档位；形参名保留 [fpsHi] 是历史，语义 = 档位帧率）。24fps 退化成
 *    非精确范围（如 [24,30]）时档位仍是 24：41.67ms 帧周期在 [1/30, 1/24] 合法域内、HAL 会把
 *    节奏放到 24fps，强制 1/24 不再被 hi=30 压回（旧口径把长曝光观感整个压没了）。
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

/** 档位可用性判定域：普通 ∪ 高速并集。UI 档位「能不能选」与换档精确性按它判定；会话内下发 / 钳制仍按单模态范围表 */
fun fpsRangesUnion(normal: List<RangeI>, highSpeed: List<RangeI>): List<RangeI> = normal + highSpeed

/** 该帧率普通会话覆盖不了、但高速会话（按尺寸的高速范围表）覆盖 ⇒ 需要高速会话 */
fun needsHighSpeedFps(normal: List<RangeI>, highSpeed: List<RangeI>, fps: Int): Boolean =
    pickFpsRange(normal, fps) == null && pickHighSpeedFpsRange(highSpeed, fps) != null

/**
 * 高速表专用选档（2026-10-26 新增）：**只用机器声明过的区间**，绝不自己造 `[f,f]`。
 *
 * 为什么不沿用 [pickFpsRange]：它"取覆盖目标里上界最大者"，放到高速表上会挑到 `[30,240]`
 * 这种极宽区间——用户选 60、HAL 却有机会跑到 240。高速表这里分三级：
 * 1. **固定档 `[f,f]`**（`[120,120]`/`[240,240]`）⇒ 精确，原样返回；
 * 2. **下界正好等于目标**的区间（目标 60 → `[60,120]`/`[60,240]`）⇒ 最贴近目标且 HAL 只会往上，
 *    取其中上界最小者；
 * 3. 再退：覆盖目标者取上界最小者（目标 50 只有 `[30,120]`/`[30,240]` ⇒ 取 `[30,120]`）。
 *
 * 返回 null = 这张表没有任何区间覆盖目标（调用方按"本机做不到这一档"处理，**不要**退回普通表硬发）。
 *
 * 【2026-10-26 修正】旧实现是 `FpsPick(target, target, true)`——直接造 `[50,50]`。那不是机器声明过
 * 的值，送进 `createHighSpeedRequestList` 会被拒；而拒了之后又掉进没有退路的降档分支 ⇒ 取景器假死。
 */
fun pickHighSpeedFpsRange(ranges: List<RangeI>, target: Int): FpsPick? {
    if (ranges.isEmpty() || target <= 0) return null
    ranges.firstOrNull { it.lo == target && it.hi == target }?.let { return FpsPick(it.lo, it.hi, true) }
    val containing = ranges.filter { it.lo <= target && target <= it.hi }
    if (containing.isEmpty()) return null
    val chosen = containing.filter { it.lo == target }.minByOrNull { it.hi }
        ?: containing.minByOrNull { it.hi }!!
    return FpsPick(chosen.lo, chosen.hi, false)
}

/** 一档的完整决议（见 [CameraAbility.decideFps]）：下发哪个区间、走不走高速会话 */
data class FpsDecision(val pick: FpsPick, val highSpeed: Boolean)

/**
 * 一档的完整决议（纯函数，JVM 全测）：**普通表先来，普通表覆盖不了才看高速表**。
 * 返回 null = 两张表都不覆盖 ⇒ 界面灰显、总线落 `enabled=false`，不硬发。
 *
 * 这条顺序就是产品口径：机器能在普通流里跑的就别开高速会话；只有普通流给不到的档才请出高速会话。
 * 它同时回答"50/60 该不该灰"——**看机器在哪张表里声明**：声明在普通表就是普通档；只在高速表
 * 声明的（如 vivo V2520A 的 50/60）仍然可用，因为那张表正是厂商把"高帧率录像"暴露给第三方 App
 * 的途径，只是实际速率由 HAL 在区间内定 ⇒ 界面标 ※ 如实告知。
 */
fun decideFps(normal: List<RangeI>, highSpeed: List<RangeI>, fps: Int): FpsDecision? {
    if (fps <= 0) return null
    pickFpsRange(normal, fps)?.let { return FpsDecision(it, false) }
    pickHighSpeedFpsRange(highSpeed, fps)?.let { return FpsDecision(it, true) }
    return null
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
