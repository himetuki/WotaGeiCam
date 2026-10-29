package com.wotagei.cam.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Range
import android.util.Rational
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect as WotaRect
import com.wotagei.cam.core.Size as WotaSize
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.pickFpsRange
import com.wotagei.cam.core.pickHighSpeedFpsRange
import kotlin.math.roundToInt

/**
 * 参数 → CaptureRequest.Builder 的纯函数层。
 *
 * 设计约束：
 * - 范围/档位一律来自 [CameraAbility]（运行时 characteristics），本文件不出现机型硬编码数值；
 *   产品域常量（快门上限、色温域、高速分水岭、1e9）统一取 `core/WotaTiers`，不在此重复定义；
 * - 与 Android 无耦合的计算拆成纯函数（入参用 core 的纯数据类），可直接跑 JVM 单测；
 * - 快门/fps/ISO 的钳制落在相机层而不是 UI 层。
 */
object RequestApplier {

    /**
     * 手动白平衡的**中性色温**（D55）：该档位下三通道增益完全相等 ⇒ 不加任何偏色。
     *
     * 必须是 5500 而不是"色温域的中点"（2000..10000 的中点是 6000）：5500 是用户与行业共同的
     * "日光参考"，把零偏色放在 6000 会让所有标称值整体偏冷一档（详见 [kelvinTintToRgb255]）。
     */
    private const val KELVIN_NEUTRAL = 5500

    /** 对焦/测光框在传感器坐标系下固定 1000×1000，权重取最大 */
    private const val REGION_HALF = 1000

    /** 3840×2160 及以上带宽下 EIS 不下发（门控 2） */
    private const val EIS_BLOCK_LONG_EDGE = 3840
    private const val EIS_BLOCK_SHORT_EDGE = 2160

    /** tint 轴幅度（0–255 域）；与色温轴三锚点插值合成 双轴插值 */
    private const val TINT_SPAN_RGB = 38f
    private const val TINT_SPAN_GREEN = 26f

    /** 闪光方案：AE_MODE 语义 + 常亮灯开关（官方语义，不做反向映射） */
    enum class FlashAe { ON, ON_ALWAYS_FLASH, ON_AUTO_FLASH }

    data class FlashPlan(val ae: FlashAe, val torch: Boolean)

    /**
     * 防抖方案。null = 该键不下发（能力没给，强设会让 HAL 行为未定义， 门控 1）；
     * 非 null = 显式写 ON/OFF —— 关闭也要写值，「不写」等于交给 HAL 自主决定。
     */
    data class StabilizePlan(val oisOn: Boolean?, val eisOn: Boolean?)

    /** 下发所需的设备侧快照，由 [stateOf] 从能力一次性算出；切镜头时整套重建 */
    data class State(
        val activeArray: WotaRect,
        val isoMin: Int,
        val isoMax: Int,
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
        val evMinSteps: Int,
        val evMaxSteps: Int,
        val fpsRanges: List<RangeI>,
        val maxRegionsAf: Int,
        val maxRegionsAe: Int,
        val manualFocusMaxDiopter: Float,
        val zoomMin: Float,
        val zoomMax: Float,
        val useZoomRatio: Boolean,
        val flashAvailable: Boolean,
        val oisAvailable: Boolean,
        val eisAvailable: Boolean,
        val highSpeed: Boolean,
        val eisBlockedBySize: Boolean,
        /** HAL 声明可用的 NOISE_REDUCTION_MODE 集合（2026-10-26 画面降噪）；空 = 不下发该键 */
        val noiseReductionModes: Set<Int> = emptySet()
    ) {
        /** 能力缺失时 RangeI 是 closed(0..0)，不能拿它去 coerce，否则会把 ISO 压成 0 */
        fun clampIso(value: Int): Int = if (isoMin in 1..isoMax) value.coerceIn(isoMin, isoMax) else value

        fun clampEv(steps: Int): Int =
            if (evMaxSteps >= evMinSteps) steps.coerceIn(evMinSteps, evMaxSteps) else 0
    }

    /** 一帧请求的参数快照（值已由引擎钳制到 State 范围内） */
    data class Snapshot(
        val aeMode: AeMode,
        val iso: Int,
        val shutterNs: Long,
        val evSteps: Int,
        val fps: FpsPick?,
        val afMode: AfMode,
        /** 点按对焦期间的临时 CONTROL_AF_MODE（AUTO）；null 表示沿用 afMode */
        val afModeOverride: Int?,
        val focusDiopter: Float,
        val zoom: Float,
        val wbPreset: WbPreset,
        val kelvin: Int,
        val tint: Int,
        /**
         * true = 手动白平衡改由**画面算法**落地（本机 HAL 不采纳 COLOR_CORRECTION_GAINS）。
         * 此时请求侧必须**保持自动白平衡**：一旦发 AWB_MODE_OFF 又没有增益，画面就只剩传感器
         * 原色（WIKO 真机：整屏发绿，四档色温毫无差别）。
         */
        val wbByGl: Boolean = false,
        /**
         * 手动档要随增益一起下发的 `COLOR_CORRECTION_TRANSFORM` **种子**：引擎在自动档从结果里
         * 抓的 HAL 最近一次回报的 CCM（行优先 9 元素；null = 从没观测到，比如 LEGACY/桥接层剥键）。
         *
         * 为什么必须是种子而不能是恒等矩阵（2026-10-29 华为 ALN-AL00/卓易通 真机取证）：
         * `TRANSFORM_MATRIX` 模式的契约是"按我给的矩阵×增益算色彩"，写恒等等于把传感器
         * 原色→sRGB 的校正整个跳过——RGGB 传感器的原生响应绿通道独大，画面立刻整屏发绿，
         * 增益调什么都压不住。切手动档前 AWB 自己算的那份 CCM 就是现成的正确矩阵：
         * 冻结它、只动增益，才是"绝对色温"的语义。恒等只配当"没观测到"时的兜底
         * （这类 HAL 多半无视下发矩阵用自己内部的，行为不变）。
         */
        val wbSeedCcm9: FloatArray? = null,
        /** 闪光切换复位阶段的临时值；null 表示沿用用户设定 */
        val flashOverride: Flash?,
        val flash: Flash,
        val stabilize: Stabilize,
        /** null = 本帧不改测光区；非 null = 写入这些区域 */
        val afRegions: List<MeteringRectangle>?,
        val aeRegions: List<MeteringRectangle>?,
        /** 单次通道用 TRIGGER_START；repeating 通道用 TRIGGER_IDLE 复位 */
        val afTrigger: Int?,
        /**
         * 本次要下发的 `NOISE_REDUCTION_MODE`（2026-10-26）：**null = 一个键都不写**。
         * 取值由引擎经 `core.noiseReductionModeToSend` 决议（声明过才写、退出 HAL 档时清自己的残留、
         * 从没写过就选关闭则完全不碰）——本层只负责"非 null 就写"，策略不进这里。
         */
        val halNoiseReductionMode: Int? = null,
        /**
         * 用户选的**帧率档位**（2026-10-26）：帧周期与快门钳制的基准。
         *
         * 为什么不直接用 [fps].lo：两个选档函数都只返回**包含目标**的区间（lo ≤ 目标恒成立），
         * 而区间下界在可变高速档上会**低于**目标——vivo 的 50 → `[30,120]`，lo=30 ⇒ 帧周期
         * 33.3ms > 50fps 的 20ms，快门梯因此放出一个"引擎必定被 HAL 压回"的死档。
         * r10 那条"钳制吃范围下界"的裁决针对的是 `[24,30]` 选 24（lo==目标==24）；在 lo < 目标
         * 的档上必须回到**目标档位**才与用户意图、与 `core.shutterCeilingNs`
         * （WotaParams.applyAbility 一直按目标档算）同一条线。取 max 是防御：万一未来出现
         * lo > 目标的选档（现在不可能），仍以较长帧周期为准不炸。
         */
        val fpsTarget: Int = 0
    )

    // ---------------------------------------------------------------- 能力快照

    /**
     * 一次性收全下发所需能力：区域上限、手动对焦域、变焦域、防抖能力、4K 门禁在此定型。
     * 帧率范围表按尺寸与高帧率分流，交给 core 的 `fpsRangesFor`（高速表是「尺寸→范围」）。
     */
    fun stateOf(ability: CameraAbility, size: WotaSize, highSpeed: Boolean): State {
        val ratio = ability.zoomRatioRange
        val zoomMin = ratio?.lo ?: 1f
        val zoomMax = ratio?.hi ?: ability.maxDigitalZoom.coerceAtLeast(1f)
        val longEdge = maxOf(size.width, size.height)
        val shortEdge = minOf(size.width, size.height)
        return State(
            activeArray = ability.activeArray,
            isoMin = ability.iso.lo,
            isoMax = ability.iso.hi,
            exposureMinNs = ability.exposureNs.lo.toLong(),
            exposureMaxNs = ability.exposureNs.hi.toLong(),
            evMinSteps = ability.evRange.lo,
            evMaxSteps = ability.evRange.hi,
            fpsRanges = ability.fpsRangesFor(size, highSpeed),
            maxRegionsAf = ability.maxRegionsAf,
            maxRegionsAe = ability.maxRegionsAe,
            manualFocusMaxDiopter = ability.minFocusDistanceDiopter,
            zoomMin = minOf(zoomMin, zoomMax),
            zoomMax = maxOf(zoomMin, zoomMax),
            // ZOOM_RATIO 需 SDK≥30 且镜头给了 ratio range，否则退回 CROP_REGION
            useZoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ratio != null,
            flashAvailable = ability.flashAvailable,
            oisAvailable = ability.oisAvailable,
            eisAvailable = ability.eisAvailable,
            highSpeed = highSpeed,
            eisBlockedBySize = longEdge >= EIS_BLOCK_LONG_EDGE && shortEdge >= EIS_BLOCK_SHORT_EDGE,
            noiseReductionModes = ability.noiseReductionModes
        )
    }

    // ---------------------------------------------------------------- 纯计算（可单测）

    /**
     * 快门双钳制（三条硬规则）：≤ 1/fps（否则丢帧）、≤ 1/10s（防手抖糊片），
     * 最后夹进设备曝光时间范围；范围缺失（closed）时跳过对应那一刀。
     *
     * [forceDeviceRange] 只给「强制快门档」（1/24、1/25，见 [WotaTiers.isRequiredShutterNs]）用：
     * 设备 `SENSOR_INFO_EXPOSURE_TIME_RANGE` 里没有这两个值时，也照样按该值下发——是否被 HAL
     * 接受由设备决定（Camera2 契约里超出上报范围的值由 HAL 自行夹回或忽略，不保证原样生效），
     * 与 24/25fps 在无固定范围设备上仍可选的强制语义对称。
     * **只豁免设备范围那两刀**：1/10s 与 1/帧周期两条产品规则照旧生效（否则 25fps 下 1/24
     * 会给出比帧周期还长的曝光，直接把时间戳打乱）。
     */
    fun clampShutterNs(
        shutterNs: Long,
        fps: Int,
        exposureMinNs: Long,
        exposureMaxNs: Long,
        forceDeviceRange: Boolean = false
    ): Long {
        val safeFps = fps.coerceAtLeast(1)
        val perFrame = WotaTiers.NS_PER_SECOND / safeFps
        var value = minOf(shutterNs, perFrame, WotaTiers.SHUTTER_CAP_NS)
        if (!forceDeviceRange) {
            if (exposureMaxNs > 0) value = minOf(value, exposureMaxNs)
            if (exposureMinNs > 0) value = maxOf(value, exposureMinNs)
        }
        return if (value <= 0L) 1L else value
    }

    /** 手动曝光必须同帧给出的帧周期：1e9/fps */
    fun frameDurationNs(fps: Int): Long = WotaTiers.NS_PER_SECOND / fps.coerceAtLeast(1)

    /**
     * fps 档位 → 实际写入 CONTROL_AE_TARGET_FPS_RANGE 的范围。
     *
     * 两张表各用各的选法，**两条都不许自己造值**：
     * - 普通表（`highSpeed=false`）：[pickFpsRange] —— 固定 [f,f] 优先，其次"包含 f 且上界最大"的可变
     *   范围并标 exact=false（UI 打「※」）。普通会话里上界大一点没坏处，反而给 HAL 留了自动曝光余量。
     * - 高速表（`highSpeed=true`）：[pickHighSpeedFpsRange] —— 固定档优先，其次"下界正好等于目标"，
     *   再其次"覆盖目标里上界最小"，**绝不返回机器没声明过的 `[f,f]`**。
     *   【2026-10-26 修正】旧实现 `FpsPick(target, target, true)` 就是自己造值：50/60 首次被路由进
     *   高速会话后，`[50,50]` 不在声明表里 ⇒ `createHighSpeedRequestList` 拒收 ⇒ 掉进无退路的降档
     *   分支（取景器假死）。
     *
     * 返回 null 表示**这张表**没有任何区间覆盖 target，由引擎按"本机做不到这一档"降级/上报，
     * 而不是静默跑另一个帧率（那会让 params.fps 与成片 fps 不一致）。
     */
    fun pickFps(ranges: List<RangeI>, target: Int, highSpeed: Boolean): FpsPick? {
        if (target <= 0) return null
        return if (highSpeed) pickHighSpeedFpsRange(ranges, target) else pickFpsRange(ranges, target)
    }

    /**
     * 色温 + tint → 0–255 域 RGB（双轴插值）。
     *
     * **中性锚点 = [KELVIN_NEUTRAL] = 5500K**（D55，标准"日光"参考）：该色温下三通道增益完全相等，
     * 即"选了 5500 就不该有任何偏色"。色温轴以它为分界做两段线性插值，三枚锚点（2000/5500/10000）
     * 对应 1.0–3.0 增益域的两端与中点。
     *
     * 2026-10-28 订正（手动白平衡"偏冷"的模型侧根因）：旧实现的锚点写在 **6000K**，
     * 于是用户按"5500 是日光、应当中性"的常识去选、拿到的却是 **+11.5% 蓝增益**（5000K 更到 +17.8%，
     * 4000K +37.6%）——那不是 HAL 的问题，是模型把零偏色的位置整体挪高了一档。
     * 现在 5500K 恰为恒等，两侧再按同一套增益端点展开。
     *
     * tint 轴：以中点为 0 的 ±偏移，同向抬/压 R、B，反向调整 G。
     * 方向遵循白平衡语义：预设越低（假定光源越暖）补蓝越多，预设越高补红越多。
     */
    fun kelvinTintToRgb255(kelvin: Int, tint: Int): FloatArray {
        val k = kelvin.coerceIn(WotaTiers.KELVIN_MIN, WotaTiers.KELVIN_MAX)
        // u=0 ↔ KELVIN_MIN、u=0.5 ↔ KELVIN_NEUTRAL、u=1 ↔ KELVIN_MAX：两段各自线性，
        // 保证中性点精确落在 5500 而不是两端的中点（6000）
        val u = if (k <= KELVIN_NEUTRAL) {
            0.5f * (k - WotaTiers.KELVIN_MIN).toFloat() / (KELVIN_NEUTRAL - WotaTiers.KELVIN_MIN)
        } else {
            0.5f + 0.5f * (k - KELVIN_NEUTRAL).toFloat() / (WotaTiers.KELVIN_MAX - KELVIN_NEUTRAL)
        }
        val v = ((tint - WotaTiers.TINT_MIN).toFloat() /
            (WotaTiers.TINT_MAX - WotaTiers.TINT_MIN)).coerceIn(0f, 1f)
        val r = lerp3(u, 0.298f, 0.549f, 1f) * 255f
        val g = lerp3(u, 0.502f, 0.549f, 0.502f) * 255f
        val b = lerp3(u, 1f, 0.549f, 0.298f) * 255f
        val t = (v - 0.5f) * 2f                       // −1 偏绿 / +1 偏品红
        return floatArrayOf(
            (r + t * TINT_SPAN_RGB).coerceIn(0f, 255f),
            (g - t * TINT_SPAN_GREEN).coerceIn(0f, 255f),
            (b + t * TINT_SPAN_RGB).coerceIn(0f, 255f)
        )
    }

    /** 0–255 → 增益 1.0–3.0（线性映射，不自己构造色度矩阵） */
    fun gain255ToUnit(x: Float): Float = 1f + (x / 255f) * 2f

    /**
     * 由 0–255 RGB 组 RggbChannelVector。
     *
     * **两个绿通道都拿满额增益**（[RggbChannelVector] 的 greenEven/greenOdd 是两条绿通道各自的增益，
     * 不是"一条绿增益拆成两半"）。2026-10-28 订正：旧的 `g/2, g/2` 把绿增益砍半 ⇒ 在按规范实现
     * 的 HAL 上等效于"R、B 各抬一档、G 不动"，成片/预览整体偏品红发冷；同一份代码的另一侧
     * [manualGains] 报给遥测的却是不砍半的 G，两处对不上，[com.wotagei.cam.core.WbTelemetry.gainsAdopted]
     * 的"采纳"判据因此在真正支持手动白平衡的机器上也永远判不采纳。
     */
    fun rggbFromRgb255(rgb: FloatArray): RggbChannelVector {
        val r = gain255ToUnit(rgb[0])
        val g = gain255ToUnit(rgb[1])
        val b = gain255ToUnit(rgb[2])
        return RggbChannelVector(r, g, g, b)
    }

    /**
     * 手动档增益的**纯口径**（下发与遥测同源，28 §2.4-2 的 request 一侧）：
     * 色温 + tint → 单位域 [R,G,B]（= [rggbFromRgb255] 摆进 RggbChannelVector 的那三个数；
     * 绿通道两半同值，这里只需要一条）。不构造 android 类型是因为构造器在 JVM 单测里起不来。
     */
    fun manualGains(kelvin: Int, tint: Int): FloatArray {
        val rgb = kelvinTintToRgb255(kelvin, tint)
        return floatArrayOf(gain255ToUnit(rgb[0]), gain255ToUnit(rgb[1]), gain255ToUnit(rgb[2]))
    }

    /**
     * 归一化点按坐标 → 传感器坐标的 1000×1000 框（换算式：x' = x×arrayW − 半框宽，再 clamp）。
     * TapPoint 的 x/y 已是 [0,1] 且按 sensorOrientation 摆正，所以这里不再除预览尺寸。
     * 返回 left/top/right/bottom。
     */
    fun meteringBox(arrayWidth: Int, arrayHeight: Int, normX: Float, normY: Float): IntArray {
        val left = (normX * arrayWidth - REGION_HALF).roundToInt()
            .coerceIn(0, maxOf(0, arrayWidth - REGION_HALF))
        val top = (normY * arrayHeight - REGION_HALF).roundToInt()
            .coerceIn(0, maxOf(0, arrayHeight - REGION_HALF))
        return intArrayOf(left, top, left + REGION_HALF, top + REGION_HALF)
    }

    /** 全幅测光框（点按超时后恢复用，对应  U0()） */
    fun fullFrameBox(arrayWidth: Int, arrayHeight: Int): IntArray =
        intArrayOf(0, 0, arrayWidth, arrayHeight)

    /** box(left,top,right,bottom) → Camera2 需要的 MeteringRectangle（构造器入参是 x,y,width,height,weight） */
    fun meteringRect(box: IntArray): MeteringRectangle = MeteringRectangle(
        box[0], box[1], box[2] - box[0], box[3] - box[1], MeteringRectangle.METERING_WEIGHT_MAX
    )

    /** CROP_REGION 中心裁切公式：z=1 即全幅。返回 left/top/right/bottom */
    fun cropBox(arrayWidth: Int, arrayHeight: Int, zoom: Float, zoomMin: Float, zoomMax: Float): IntArray {
        val z = zoom.coerceIn(zoomMin, zoomMax).coerceAtLeast(1f)
        val halfW = arrayWidth / (2f * z)
        val halfH = arrayHeight / (2f * z)
        val left = (arrayWidth / 2f - halfW).roundToInt().coerceIn(0, arrayWidth)
        val top = (arrayHeight / 2f - halfH).roundToInt().coerceIn(0, arrayHeight)
        val right = (arrayWidth / 2f + halfW).roundToInt().coerceIn(left + 1, arrayWidth)
        val bottom = (arrayHeight / 2f + halfH).roundToInt().coerceIn(top + 1, arrayHeight)
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * 闪光灯映射按 **Android 官方语义**：ON→ON_ALWAYS_FLASH（补光常闪）、AUTO→ON_AUTO_FLASH（自动预闪）、
     * TORCH→AE_MODE=ON + FLASH_MODE=TORCH。常见实现里有反向映射的写法，此处不做。
     * 手动曝光（AE_MODE=OFF）下无法用 AE 驱动的闪光模式，退化为 FLASH_MODE=OFF，torch 仍保留。
     */
    fun flashPlan(flash: Flash, flashAvailable: Boolean, manualAe: Boolean): FlashPlan {
        if (!flashAvailable) return FlashPlan(FlashAe.ON, false)
        if (manualAe) return FlashPlan(FlashAe.ON, flash == Flash.TORCH)
        return when (flash) {
            Flash.OFF -> FlashPlan(FlashAe.ON, false)
            Flash.ON -> FlashPlan(FlashAe.ON_ALWAYS_FLASH, false)
            Flash.AUTO -> FlashPlan(FlashAe.ON_AUTO_FLASH, false)
            Flash.TORCH -> FlashPlan(FlashAe.ON, true)
        }
    }

    /**
     * 防抖三门控：能力门控 / 4K 禁 EIS / 高帧率会话整体不下发。
     * 「显式关闭写 OFF 而不是不写」体现为返回 false 而不是 null。
     */
    fun stabilizePlan(
        stabilize: Stabilize,
        oisAvailable: Boolean,
        eisAvailable: Boolean,
        eisBlockedBySize: Boolean,
        highSpeed: Boolean
    ): StabilizePlan {
        if (highSpeed) return StabilizePlan(null, null)
        val wantsOis = stabilize == Stabilize.OIS || stabilize == Stabilize.OIS_EIS
        val wantsEis = stabilize == Stabilize.EIS || stabilize == Stabilize.OIS_EIS
        val ois = if (oisAvailable) wantsOis else null
        // 4K 下即使用户选了 EIS 也不下发该键（带宽不足，HAL 会静默降帧）
        val eis = if (eisAvailable && !(wantsEis && eisBlockedBySize)) wantsEis else null
        return StabilizePlan(ois, eis)
    }

    /**
     * 行优先 9 元素 → [ColorSpaceTransform]；非法输入（null/长度不对/非有限值）返回 null，
     * 调用方据此退到 [identityColorTransform]。构造器收的是一维行主序 9 元素 Rational 数组。
     * 浮点按 1e6 定点化：CCM 元素域 ±8 左右、HAL 侧本身就是有理数近似，1e6 的精度绰绰有余
     * （`Rational` 会自动约分）。
     */
    fun colorTransformFrom9(a: FloatArray?): ColorSpaceTransform? {
        if (a == null || a.size != 9 || a.any { !it.isFinite() }) return null
        // CCM 元素域 ±8 左右，×1e6 远在 Int 域内；Rational 自动约分
        val scale = 1_000_000
        val rationals = Array(9) { i -> Rational((a[i] * scale).roundToInt(), scale) }
        return ColorSpaceTransform(rationals)
    }

    /**
     * 单位色度矩阵：声明 TRANSFORM_MATRIX 模式但不注入矩阵，色彩完全交给 gains。
     * 2026-10-29 起只作「从没观测到 HAL 回报 CCM」时的兜底——恒等矩阵在按规范实现的 HAL 上
     * 会跳过传感器→sRGB 校正（整屏发绿，见 [RequestApplier.Snapshot.wbSeedCcm9]）。
     */
    fun identityColorTransform(): ColorSpaceTransform {
        val one = Rational(1, 1)
        val zero = Rational(0, 1)
        // 构造器收的是一维行主序 9 元素 Rational 数组，不是 3×3 二维数组
        return ColorSpaceTransform(arrayOf(one, zero, zero, zero, one, zero, zero, zero, one))
    }

    /** 手动色温档（写 gains 的分支）；core 的 WbPreset.MANUAL 即 AWB_MODE=OFF */
    fun isManualWhiteBalance(preset: WbPreset): Boolean = preset == WbPreset.MANUAL

    /** ：单次与手动在 Camera2 层同义 = AF_MODE_OFF + 屈光度 */
    fun writesManualFocusDiopter(mode: AfMode): Boolean = mode.cam2Mode == CaptureRequest.CONTROL_AF_MODE_OFF

    // ---------------------------------------------------------------- 主入口

    /**
     * 把一帧参数快照写进 builder。调用方负责选择 repeating 或单次通道。
     * 约定：一组语义一次写全（手动曝光三件套、色温四件套、防抖两键），不留半状态。
     */
    fun apply(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        applyFps(builder, p.fps)
        applyExposure(builder, state, p)
        applyAutoFocus(builder, state, p)
        applyZoom(builder, state, p.zoom)
        applyWhiteBalance(builder, p)
        applyFlash(builder, state, p)
        applyStabilize(builder, state, p.stabilize)
        applyNoiseReduction(builder, p.halNoiseReductionMode)
        applyMeteringRegions(builder, state, p)
    }

    /**
     * 画面降噪（2026-10-26）：把引擎决议好的 `NOISE_REDUCTION_MODE` 写进请求；**null 就一个键都不写**。
     *
     * 策略（写哪个值、什么时候要写 OFF 清残留）全在 `core.noiseReductionModeToSend` 一处，
     * 本函数只做"非 null 即写"这一件事：这样策略能被 JVM 用例直接钉住，而"是否真的写进请求"这一层
     * 保持薄到不会再出错。
     *
     * 提醒后来人一条 Camera2 事实：`CaptureRequest.Builder` **每会话只建一次、跨多次下发复用**，
     * 而且**没有单键 remove** —— 所以"不写"不等于"清掉上一次写的值"，那件事必须显式写 OFF
     * （这正是 2026-10-26 修掉的缺陷：面板显示关闭、实际还在降噪）。
     */
    private fun applyNoiseReduction(builder: CaptureRequest.Builder, mode: Int?) {
        if (mode == null) return
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, mode)
    }

    private fun applyFps(builder: CaptureRequest.Builder, pick: FpsPick?) {
        if (pick == null) return
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(pick.lo, pick.hi))
    }

    /**
     * 曝光组一次下发的完整键值计划（AE on/off 两档键集的**并集**，每个键都有确定值）。
     *
     * 为什么不按档分流各写各的（旧实现）：CaptureRequest.Builder 只有 set、没有单键 remove，
     * 且引擎的 builder 每会话只建一次、跨 apply 复用——分流写法翻到 MANUAL 的那个请求仍带着
     * 上次 auto 档写入的 AE_EXPOSURE_COMPENSATION（旧值），反向循环后 auto 请求残留 SENSOR
     * 三键，下发的全是「混合键集请求」（EV 闪退诊断 §2 / 审查 P2）。键消不掉，只能让**值恒定
     * 且自洽**：每键每次都按当前快照与当前档语义重算。
     */
    data class ExposurePlan(
        /** AE 锁独立于 AE_MODE（锁定不改 AE_MODE 取值） */
        val aeLock: Boolean,
        /** manual → AE_MODE_OFF；否则 ON（闪光分支会按闪光语义再覆写成 ON_* 变体） */
        val aeMode: Int,
        /** manual → 0（AE OFF 下补偿无语义，非零值是矛盾信息）；auto → 钳制后的用户步数（EV 用户值不丢，在 params.ev） */
        val aeComp: Int,
        /**
         * SENSOR 三键（2026-10-26 反馈 #1 方案A）：**null = 本帧不写该键**（仅 AE 自动档）。
         * 旧设计"auto 档也写三键的确定值"依赖官方契约"HAL 在 AE_ON 下忽略它们"——部分 MTK HAL
         * 不守约，把三键当钳制采纳，与连续对焦搜索互相拉扯 ⇒ 预览与**成片**曝光闪动
         * （vivo V2302A 实锤：连续档成片闪、手动档稳定）。
         */
        val sensitivity: Int?,
        val exposureNs: Long?,
        val frameDurationNs: Long?
    )

    /**
     * 曝光组键值计划（纯函数，可直接 JVM 单测）。
     * SENSOR 三键只在 manual 档产出（null = 不写）；auto 档的残值清理由引擎侧"AE 翻转即重建
     * repeating builder"承担（Camera2 无删键，整 builder 重建后键集天然自洽）——这是 2026-10-26
     * 对旧设计"auto 也写三键确定值"的推翻：官方契约"HAL 在 AE_ON 下忽略它们"被部分 MTK HAL
     * 打破，三键与 AF 搜索互相拉扯把闪动编码进了成片（vivo V2302A 实锤）。
     */
    fun exposurePlan(state: State, p: Snapshot): ExposurePlan {
        val manual = p.aeMode == AeMode.MANUAL
        // 帧周期/快门钳制的基准 = **用户档位**（2026-10-26 修正，见 Snapshot.fpsTarget 的说明）：
        // r10 的"吃范围下界"在 lo==目标 的普通档上与档位相等（[24,30] 选 24 ⇒ 24），行为不变；
        // 在可变高速档上（vivo 50 → [30,120]，lo=30 < 目标 50）必须回到目标，否则帧周期 33.3ms
        // 超过 50fps 的 20ms，快门梯放出一个 HAL 必定压回的死档。
        // 兜底链：两个来源都拿不到（异常态）才退 HIGH_SPEED_FPS——与旧 fallback 一致。
        val fps = maxOf(p.fpsTarget, p.fps?.lo ?: 0).takeIf { it > 0 } ?: WotaTiers.HIGH_SPEED_FPS
        // SENSOR 三键只在手动档产出（AE_OFF 下它们被尊重，值有意义）；
        // 自动档 null = 不写。残值由引擎在 AE 翻转时**整 builder 重建**解决
        //（Camera2 无单键 remove，"不写"清不掉上一档写的值——见 Camera2Engine.applyRepeatingNow）。
        val sensitivity: Int?
        val exposureNs: Long?
        val frameDurationNs: Long?
        if (manual) {
            sensitivity = state.clampIso(p.iso)
            exposureNs = clampShutterNs(
                p.shutterNs, fps, state.exposureMinNs, state.exposureMaxNs,
                // 强制快门档（1/24、1/25）：设备曝光范围容不下也按该值下发
                forceDeviceRange = WotaTiers.isRequiredShutterNs(p.shutterNs)
            )
            frameDurationNs = frameDurationNs(fps)
        } else {
            sensitivity = null
            exposureNs = null
            frameDurationNs = null
        }
        return ExposurePlan(
            aeLock = !manual && p.aeMode == AeMode.LOCK,
            aeMode = if (manual) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON,
            aeComp = if (manual) 0 else state.clampEv(p.evSteps),
            sensitivity = sensitivity,
            exposureNs = exposureNs,
            frameDurationNs = frameDurationNs
        )
    }

    private fun applyExposure(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        val plan = exposurePlan(state, p)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, plan.aeLock)
        builder.set(CaptureRequest.CONTROL_AE_MODE, plan.aeMode)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, plan.aeComp)
        // SENSOR 三键只在手动档写（null = 不写）；自动档的残值由引擎侧 AE 翻转重建 builder 清掉。
        // 这里的 `?.let` 不是"按档分支写键"的老病：三键在请求里**缺席**才是本设计的目标，
        // 分支判断吃的是 plan 的可空性而不是档位字面量（守卫的 if(manual) 禁令针对后者）
        plan.sensitivity?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        plan.exposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        plan.frameDurationNs?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
    }

    private fun applyAutoFocus(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        val mode = p.afModeOverride ?: p.afMode.cam2Mode
        builder.set(CaptureRequest.CONTROL_AF_MODE, mode)
        if (mode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            val diopter = p.focusDiopter.coerceIn(0f, state.manualFocusMaxDiopter.coerceAtLeast(0f))
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
        }
        p.afTrigger?.let { builder.set(CaptureRequest.CONTROL_AF_TRIGGER, it) }
    }

    // 走 CONTROL_ZOOM_RATIO 的前提是 state.useZoomRatio，而它在构造 State 时已由 SDK_INT >= R 把住
    // （见本文件 useZoomRatio 的赋值）；该 key 是编译期内联的字符串常量，API 29 不会执行到这里
    @android.annotation.SuppressLint("NewApi")
    private fun applyZoom(builder: CaptureRequest.Builder, state: State, zoom: Float) {
        if (state.useZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(state.zoomMin, state.zoomMax))
        } else {
            val array = state.activeArray
            val box = cropBox(array.arrayWidth, array.arrayHeight, zoom, state.zoomMin, state.zoomMax)
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(box[0], box[1], box[2], box[3]))
        }
    }

    private fun applyWhiteBalance(builder: CaptureRequest.Builder, p: Snapshot) {
        if (isManualWhiteBalance(p.wbPreset) && p.wbByGl) {
            // 画面算法兜底：留在自动白平衡（增益交给 GL 场景层乘），**绝不发 AWB_MODE_OFF**
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            return
        }
        if (isManualWhiteBalance(p.wbPreset)) {
            if (p.wbSeedCcm9 == null) {
                // 种子未到手（会话头几拍/冷启即手动）：这一拍留在自动白平衡等种子。
                // 绝不发 OFF+恒等——那正是绿幕闪现的配方（2026-10-29 ALN-AL00/卓易通 取证）。
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                return
            }
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            // 矩阵用自动档抓的 HAL 回报种子（见 Snapshot.wbSeedCcm9 的绿幕取证）；观测不到才退恒等
            builder.set(
                CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                colorTransformFrom9(p.wbSeedCcm9) ?: identityColorTransform()
            )
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                rggbFromRgb255(kelvinTintToRgb255(p.kelvin, p.tint))
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, p.wbPreset.awbMode)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        }
    }

    private fun applyFlash(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        val manual = p.aeMode == AeMode.MANUAL
        val wanted = p.flashOverride ?: p.flash
        val plan = flashPlan(wanted, state.flashAvailable, manual)
        // 手动曝光下 AE_MODE 已由曝光分支置 OFF，这里只补 FLASH_MODE
        if (!manual) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                when (plan.ae) {
                    FlashAe.ON -> CaptureRequest.CONTROL_AE_MODE_ON
                    FlashAe.ON_ALWAYS_FLASH -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    FlashAe.ON_AUTO_FLASH -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                }
            )
        }
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (plan.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    private fun applyStabilize(builder: CaptureRequest.Builder, state: State, stabilize: Stabilize) {
        val plan = stabilizePlan(
            stabilize, state.oisAvailable, state.eisAvailable,
            state.eisBlockedBySize, state.highSpeed
        )
        plan.oisOn?.let {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (it) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        plan.eisOn?.let {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (it) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
    }

    private fun applyMeteringRegions(builder: CaptureRequest.Builder, state: State, p: Snapshot) {
        // 区域数上限可能为 0，写了也不生效，直接跳过
        p.afRegions?.let {
            if (state.maxRegionsAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, it.toTypedArray())
        }
        p.aeRegions?.let {
            if (state.maxRegionsAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, it.toTypedArray())
        }
    }

    private fun lerp3(t: Float, a: Float, b: Float, c: Float): Float =
        if (t <= 0.5f) a + (b - a) * (t * 2f) else b + (c - b) * ((t - 0.5f) * 2f)
}
