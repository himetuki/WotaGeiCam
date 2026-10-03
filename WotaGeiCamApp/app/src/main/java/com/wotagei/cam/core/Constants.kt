package com.wotagei.cam.core

// 平台类型别名：core 层只用下面的纯数据类（JVM 单测可直接构造），
// 需要把值交给 Camera2 / 系统 API 时才用别名形式转换，避免 core 里混进 android.* 语义。
typealias AndroidSize = android.util.Size

typealias AndroidRect = android.graphics.Rect

/** 画质档位用尺寸（对应契约里的 `Size`） */
data class Size(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
    fun toAndroidSize(): AndroidSize = AndroidSize(width, height)
}

fun AndroidSize.toWotaSize(): Size = Size(width, height)

/** 传感器有效像素区（对应契约里的 `Rect`），坐标语义与 android.graphics.Rect 一致 */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val arrayWidth: Int get() = right - left
    val arrayHeight: Int get() = bottom - top
    fun isEmpty(): Boolean = arrayWidth <= 0 || arrayHeight <= 0
    fun toAndroidRect(): AndroidRect = AndroidRect(left, top, right, bottom)
}

fun AndroidRect.toWotaRect(): Rect = Rect(left, top, right, bottom)

/** 产品档位表：与设备能力求交后才是 UI 可选项，交集外档位灰显（00 文件第 7 节） */
object WotaTiers {
    // 需求二：码率 5 档（bps）
    val BITRATES = listOf(5_000_000, 10_000_000, 20_000_000, 35_000_000, 50_000_000)

    // 需求二：音频采样率 3 档；默认 48k（视频工业标准，少一次重采样）
    val SAMPLE_RATES = listOf(32_000, 44_100, 48_000); const val DEFAULT_SAMPLE_RATE = 48_000

    // 需求二：帧率档位；24/25 为必须，>60 需 CONSTRAINED_HIGH_SPEED_VIDEO
    val FPS = listOf(24, 25, 30, 50, 60, 120, 240); val REQUIRED_FPS = setOf(24, 25)

    // 需求二：快门分子档位（分母即秒数），1/24 与 1/25 必须
    val SHUTTER_DENOM = listOf(24, 25, 30, 50, 60, 120, 240)

    /**
     * 「强制快门档」= 产品必须的 1/24、1/25（与 [REQUIRED_FPS] 一条对称口径）。
     *
     * 含义：设备 `SENSOR_INFO_EXPOSURE_TIME_RANGE` 里**没有**这两档时，它们照样可选、
     * 并按该值下发——是否被 HAL 接受由设备决定（超出上报范围的值 HAL 可夹回或忽略，不保证原样生效）；
     * 与「本机没有 `[24,24]` 固定帧率范围也照样让 24fps 可选」是同一套语义
     * （见 [com.wotagei.cam.core.CameraAbility] 的 `pickFpsRange` 回退支）。
     *
     * 边界：只豁免**设备曝光范围**这一刀。产品自己的两条硬规则（1/10s 防手抖、≤1/帧周期防丢帧）
     * 照旧生效——所以 25fps 下 1/24 仍不可选（帧周期容不下），24fps 退化成非精确范围（上界 30）时
     * 1/24 也会被帧周期压回不可选，24fps+1/24、25fps+1/25 这两对才是它真正生效的场景（也是产品定位里的那两对）。
     */
    val REQUIRED_SHUTTER_DENOM = setOf(24, 25)

    const val SHUTTER_CAP_NS = 100_000_000L          // 1/10s 防手抖钳制
    const val MAX_FILE_BYTES = 3_758_096_384L        // 3.5GiB FAT32 安全上限
    const val MIN_FREE_MB = 200L                     // 录像前余量门槛
    val ASPECTS = listOf("16:9", "20:9" /* 原相机全屏档，10-01 用户指令 */, "4:3", "1:1", "5:3", "4:3p" /* 4080x3060 类 */)

    const val NS_PER_SECOND = 1_000_000_000L
    const val HIGH_SPEED_FPS = 60                    // >60fps 分水岭：会话/防抖/分析流行为全换

    // 快门档位对应的纳秒值，UI 与快门滑杆共用一份，避免各处重复 1e9/分母
    val SHUTTER_TIER_NS = SHUTTER_DENOM.map { NS_PER_SECOND / it }

    /** 强制快门档的纳秒值（1/24 = 41_666_666、1/25 = 40_000_000），下发与 UI 共用一份 */
    val REQUIRED_SHUTTER_NS: Set<Long> =
        SHUTTER_DENOM.filter { it in REQUIRED_SHUTTER_DENOM }.map { NS_PER_SECOND / it }.toSet()

    /** 强制档判据的唯一出处：下发侧（跳过设备曝光范围那两刀）与 UI（打 ※）必须同源 */
    fun isRequiredShutterNs(ns: Long): Boolean = ns in REQUIRED_SHUTTER_NS

    // 色温/色调是「产品滑杆域」（02 文件第 6 节 u=(K-2000)/8000、v=(tint+50)/100），非设备能力
    const val KELVIN_MIN = 2000
    const val KELVIN_MAX = 10_000
    const val TINT_MIN = -50
    const val TINT_MAX = 50
}

/** 需求参考线 11 档，位掩码可组合 */
enum class RefLineType(val bit: Int, val label: String) {
    GRID_3X3(1, "三分法网格"), DIAGONAL(2, "对角线"), CROSSHAIR(4, "十字准线"),
    BROADCAST_SAFE(8, "广播安全框"), SAFE_AREA(16, "安全区域"), GRID(32, "网格"),
    CENTER_MARK(64, "中心标记"), HORIZON_LINE(128, "水平线"),
    RED_TOP(256, "上红线"), RED_MID(512, "中红线"), RED_BOTTOM(1024, "下红线");

    companion object {
        val ALL: List<RefLineType> = values().toList()
        val FULL_MASK: Int = ALL.fold(0) { acc, t -> acc or t.bit }

        fun typesOf(mask: Int): List<RefLineType> = ALL.filter { mask and it.bit != 0 }
        fun maskOf(types: List<RefLineType>): Int = types.fold(0) { acc, t -> acc or t.bit }

        /** 就近胶囊的多选态：单独给两个纯函数，好让 JVM 单测直接钉住"再点一次要能关回去" */
        fun isOn(mask: Int, type: RefLineType): Boolean = mask and type.bit != 0
        fun toggle(mask: Int, type: RefLineType): Int =
            if (isOn(mask, type)) mask and type.bit.inv() else mask or type.bit
    }
}

/**
 * 录制页常驻 HUD 可选项，位掩码可组合（与 [RefLineType] 同一套持久化惯例，键 `hud_items`）。
 * 默认只放快门/帧率/码率三档，其余由用户在设置页自选上屏哪几项；每格点开是自己的就近胶囊。
 */
enum class HudItem(val bit: Int, val labelRes: Int) {
    SHUTTER(1, com.wotagei.cam.R.string.cam_hud_shutter),
    FPS(2, com.wotagei.cam.R.string.cam_hud_fps),
    BITRATE(4, com.wotagei.cam.R.string.cam_hud_bitrate),
    ISO(8, com.wotagei.cam.R.string.cam_hud_iso),
    EV(16, com.wotagei.cam.R.string.cam_hud_ev),
    WB(32, com.wotagei.cam.R.string.cam_hud_wb),
    ZOOM(64, com.wotagei.cam.R.string.cam_hud_zoom);

    companion object {
        val ALL: List<HudItem> = values().toList()
        val DEFAULT_MASK: Int = SHUTTER.bit or FPS.bit or BITRATE.bit

        fun typesOf(mask: Int): List<HudItem> = ALL.filter { mask and it.bit != 0 }
        fun maskOf(types: List<HudItem>): Int = types.fold(0) { acc, t -> acc or t.bit }
    }
}

/** 需求「核心渲染模式」：GPU = 图像信号交 GPU 实时处理；DIRECT = 绕过 GPU 直连传感器与编码器 */
enum class RenderMode { GPU, DIRECT }

/**
 * 强制 24/25fps 在**无原生精确档**设备上的录制期转换模式（用户 2026-10-03 定版）。
 * 转换激活时录制强制走 GPU 渲染 + MediaCodec 引擎——MediaRecorder 面输入无法拒帧，这是硬约束。
 *
 * @property MEND 自动抽帧补弧：被抽帧以亮度取大并入前后保留帧，出片即真 24/25fps 且弧连续
 * @property DROP 仅抽帧：只丢帧不补（弧在抽帧点断，用户自选的观感）；被抽帧位次记 sidecar
 */
enum class ArcConvertMode { MEND, DROP }

/** AE 开关是枚举，手动档一次写全 ISO+快门+帧周期三件套，杜绝半手动中间态 */
enum class AeMode { AUTO, MANUAL, LOCK }

/** AF 模式到 Camera2 CONTROL_AF_MODE 的一一映射；单次与手动在 Camera2 层同义（AF_OFF + 屈光度） */
enum class AfMode(val cam2Mode: Int) {
    CONTINUOUS_VIDEO(3), CONTINUOUS_PICTURE(4), MACRO(2), MANUAL(0);

    companion object {
        /** 换挡回填优先级：录像连续优先，能力缺失时依次降级 */
        val PREFERENCE = listOf(CONTINUOUS_VIDEO, CONTINUOUS_PICTURE, MACRO, MANUAL)
        fun fromCam2(mode: Int): AfMode? = values().firstOrNull { it.cam2Mode == mode }
    }
}

/** AWB 预设，awbMode 即 CONTROL_AWB_MODE 取值；MANUAL 走色温+色调（AWB_MODE=OFF） */
enum class WbPreset(val awbMode: Int) {
    AUTO(1), INCANDESCENT(2), FLUORESCENT(3), WARM_FLUORESCENT(4), DAYLIGHT(5),
    CLOUDY_DAYLIGHT(6), TWILIGHT(7), SHADE(8), MANUAL(0);

    companion object {
        fun fromAwbMode(mode: Int): WbPreset? = values().firstOrNull { it.awbMode == mode }
    }
}

enum class Flash { OFF, ON, AUTO, TORCH }

/** UI 四选项（关闭/OIS/EIS/OIS+EIS），能力门控后才有档位 */
enum class Stabilize { OFF, OIS, EIS, OIS_EIS }

/** 单效果槽：监看类效果互斥（04 文件第 3 节，开启特效要求 renderMode == GPU） */
enum class FrameEffect { NONE, ZEBRA, PEAKING }

enum class PeakingColor { WHITE, GREEN, BLUE, ORANGE, RED }

/**
 * 点按对焦/测光落点。x/y 为「预览画面归一化坐标」[0,1]，已按 sensorOrientation 摆正；
 * 换算成 MeteringRectangle 是 camera 层的事（需 activeArray 与区域上限，/）。
 */
data class TapPoint(val x: Float, val y: Float)

/** 日志 Tag：M1 门禁要求 `adb logcat -s WotaAbility` 能抓到能力表 */
object WotaTag {
    const val ABILITY = "WotaAbility"
    const val LENS = "WotaLens"
    const val PARAMS = "WotaParams"
    const val VIDEO_SIZE = "WotaVideoSize"
}

/**
 * 录制页上「点了会弹面板」的控件胶囊（#54）。与 [HudItem] 的常驻读数分两套位掩码：
 * 读数继续走存量键 `hud_items`，控件走新键 `hud_pills`，默认全开 = 与改造前的界面完全一致。
 *
 * 快门、缩略图、设置齿轮不在这一套里：关掉它们等于把应用锁死，
 * 用户要的是「整个录制页只保留录制按钮」，不是「什么都点不了」。
 */
enum class CamPill(val bit: Int, val labelRes: Int) {
    LEVEL(1, com.wotagei.cam.R.string.cam_pill_level),
    VOLUME(2, com.wotagei.cam.R.string.cam_pill_volume),
    BT(4, com.wotagei.cam.R.string.cam_pill_bt),
    ZOOM(8, com.wotagei.cam.R.string.cam_pill_zoom),
    FOCUS(16, com.wotagei.cam.R.string.cam_pill_focus),
    STAB(32, com.wotagei.cam.R.string.cam_pill_stab),
    REFLINE(64, com.wotagei.cam.R.string.cam_pill_refline),
    MONITOR(128, com.wotagei.cam.R.string.cam_pill_monitor),
    CURVE(256, com.wotagei.cam.R.string.cam_pill_curve),
    FLASH(512, com.wotagei.cam.R.string.cam_pill_flash),
    LENS(1024, com.wotagei.cam.R.string.cam_pill_lens),
    SIZE(2048, com.wotagei.cam.R.string.cam_pill_size),
    STORAGE(4096, com.wotagei.cam.R.string.cam_pill_storage);

    companion object {
        val ALL: List<CamPill> = values().toList()
        val DEFAULT_MASK: Int = ALL.fold(0) { acc, t -> acc or t.bit }

        /** 掩码里“没置位”的那些就是要隐藏的胶囊 */
        fun hiddenOf(mask: Int): Set<CamPill> = ALL.filter { mask and it.bit == 0 }.toSet()
        fun maskOf(types: List<CamPill>): Int = types.fold(0) { acc, t -> acc or t.bit }
    }
}

/** 剩余空间读数：≥1G 走一位小数（96.2G），否则直接 MB（512M） */
fun freeSpaceShort(mb: Long): String = if (mb >= 1024L) "%.1fG".format(mb / 1024f) else "${mb}M"

/**
 * 剩余空间按**当前总码率**还能录多久：`3h18m` / `18m` / `<1m`，读不出来时给 `--m`。
 *
 * 码率一律由调用方现取（视频档 + 音频档），这里不写死任何机型数值；
 * 换算按 1 MiB = 1048576 B 与总 bps 直算，误差来源只有码率控制本身。
 */
fun recordableText(freeMb: Long, totalBitrateBps: Int): String {
    if (freeMb <= 0L || totalBitrateBps <= 0) return "--m"
    val sec = freeMb * 1_048_576L * 8L / totalBitrateBps
    return when {
        sec < 60L -> "<1m"
        sec < 3600L -> "${sec / 60L}m"
        else -> "${sec / 3600L}h${"%02d".format(sec % 3600L / 60L)}m"
    }
}

/** 顶栏容量段读数：「96.2G · 3h18m」 */
fun capacityLineText(freeMb: Long, totalBitrateBps: Int): String =
    "${freeSpaceShort(freeMb)} · ${recordableText(freeMb, totalBitrateBps)}"

/** 满档之外再退一档：只留小时「96.2G · 3h」，1 小时以内与满档同形 */
fun recordableHoursText(freeMb: Long, totalBitrateBps: Int): String {
    if (freeMb <= 0L || totalBitrateBps <= 0) return "--m"
    val sec = freeMb * 1_048_576L * 8L / totalBitrateBps
    return if (sec < 3600L) recordableText(freeMb, totalBitrateBps) else "${sec / 3600L}h"
}

/**
 * 顶栏**右端固定件**吃掉的宽度（dp）：左右内边距 8+8 + 胶囊组与设置入口的间距 6 + 圆形设置入口约 44。
 *
 * 抽取前它是顶栏那行 `fillMaxWidth` Row 的自然结果；现在录制页的两条判据都要显式用它 ——
 * 容量段取档的可用宽（`topBarRoomDp`）与胶囊组自己的布局宽度上限（`topBarMaxWidthDp`），
 * 必须是同一条账、同一个来源，否则判据会比实际盒子宽。编辑页预览也用它对齐同一条口径。
 *
 * 原本是 CameraScreen 里的 private `TopBarChromeReserveDp = 66`，编辑页又散写了一份 `66f`
 * （10-01 清魔数）：收拢到这一处，两边改预留只改这里。
 */
const val TOP_BAR_CHROME_RESERVE_DP = 66f

/**
 * 顶栏元信息卡里**与文字无关的固定件**（dp），[capacityNetRoomDp] 从可用宽里一路扣到「容量段文字
 * 真正能画的那截」。逐项对应 `ui/HudLayer.kt` 的渲染路：
 * 画幅段自身左右内边距 8+8（`TopSegment` 的 `padding(horizontal = 8.dp)`）+
 * 段间分隔线 1（`HudTopZone` 里那枚 `width(1.dp)`）+
 * 元信息卡左右内边距 2+2（`HudTopZone` 的 `padding(horizontal = 2.dp)`）+
 * 容量段自身左右内边距 8+8（同 `TopSegment`）= **37**。
 *
 * ⚠ `HudLayer.kt` 不在本轮改动域：那边改内边距，这里必须同步，否则判据与真盒子差一截。
 *
 * 口径是「画幅段 + 容量段**两段都在**」的上界：`hud_pills` 关掉画幅（SIZE）时卡里只剩容量段，
 * 这里仍扣 16+1，净宽会保守少算约 `sizeText+17`dp —— 最坏（竖屏 120% 文本）可能提前退一档，
 * 只保守不溢出（审查 P3 备案；要精确就把"画幅段是否在场"做成入参，属后续优化）。
 */
const val CAPACITY_ROOM_FIXED_CHROME_DP = 37f

/**
 * 容量段的**净可用宽**（dp）：`roomDp` 是顶栏胶囊组整条的可用宽
 * （安全区实测宽 − [TOP_BAR_CHROME_RESERVE_DP]），扣掉画幅段文案的实测宽
 * （每台机器、每个字号都不同，由调用方 TextMeasurer 量）与固定件 [CAPACITY_ROOM_FIXED_CHROME_DP]，
 * 剩下的才是容量段文字能占的宽度。
 *
 * 10-01 文本实测化改造后，取档判据不再依赖「画幅 135 + 容量 134 ≈ 274dp」那笔测试机真机账 ——
 * 那笔账里的画幅宽现在是入参，这里只剩与文字无关的加减。
 */
fun capacityNetRoomDp(roomDp: Float, sizeTextDp: Float): Float =
    roomDp - sizeTextDp - CAPACITY_ROOM_FIXED_CHROME_DP

/** 小时档文案「96.2G · 22h」（[capacityTierText] 中档那一支；调用方按同一串字量宽，不许各写一份） */
fun capacityHoursText(freeMb: Long, totalBitrateBps: Int): String =
    "${freeSpaceShort(freeMb)} · ${recordableHoursText(freeMb, totalBitrateBps)}"

/**
 * 顶栏容量段按**净可用宽**取三档（§74；**10-01 改造**：阈值不再是测试机真机字体度量常量
 * `CAPACITY_ROOM_FULL_DP=272` / `CAPACITY_ROOM_HOURS_DP=242`，那两个常量已删除）。
 *
 * 判据两侧都是实测宽：`roomDp` 由调用方经 [capacityNetRoomDp] 折算，`fullTextDp` / `hoursTextDp`
 * 由调用方用 TextMeasurer 按**现显示的同一 style** 量两段候选文案传入 —— 系统字体、OEM 字形字重、
 * 文本高度缩放怎么变，判据都跟着变，不再有「2dp 余量随字体漂移，别的机器提前退档或被省略号裁尾」。
 * 本函数仍是**纯取位**（不碰 UI、不读设备，可 JVM 单测），余量是函数内的局部量。
 *
 * @param roomDp 容量段净可用宽（[capacityNetRoomDp] 的产物，不是整枚胶囊组的宽）
 * @param fullTextDp 满档文案 [capacityLineText] 的实测宽（dp）
 * @param hoursTextDp 小时档文案 [capacityHoursText] 的实测宽（dp）
 */
fun capacityTierText(
    freeMb: Long,
    totalBitrateBps: Int,
    roomDp: Float,
    fullTextDp: Float,
    hoursTextDp: Float,
): String {
    // 2dp 余量：文案宽度是量出来的、可用宽是容器回报的，两边各有取整误差，贴着边排会来回抖
    val marginDp = 2f
    return when {
        roomDp >= fullTextDp + marginDp -> capacityLineText(freeMb, totalBitrateBps)
        roomDp >= hoursTextDp + marginDp -> capacityHoursText(freeMb, totalBitrateBps)
        else -> freeSpaceShort(freeMb)
    }
}
