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
    const val SHUTTER_CAP_NS = 100_000_000L          // 1/10s 防手抖钳制
    const val MAX_FILE_BYTES = 3_758_096_384L        // 3.5GiB FAT32 安全上限
    const val MIN_FREE_MB = 200L                     // 录像前余量门槛
    val ASPECTS = listOf("16:9", "4:3", "1:1", "5:3", "4:3p" /* 4080x3060 类 */)

    const val NS_PER_SECOND = 1_000_000_000L
    const val HIGH_SPEED_FPS = 60                    // >60fps 分水岭：会话/防抖/分析流行为全换

    // 快门档位对应的纳秒值，UI 与快门滑杆共用一份，避免各处重复 1e9/分母
    val SHUTTER_TIER_NS = SHUTTER_DENOM.map { NS_PER_SECOND / it }

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
