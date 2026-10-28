package com.wotagei.cam.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.wotagei.cam.BuildConfig
import com.wotagei.cam.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.wotagei.cam.R
import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.ArcConvertMode
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.RefLineType
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.UIOrientation
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.mediaReadPermissionFor
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.dialog.bitrateText
import com.wotagei.cam.ui.dialog.sampleRateText
import com.wotagei.cam.ui.design.PillSlider
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.design.WotaType
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaRec
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.widget.SliderKey
import com.wotagei.cam.ui.widget.TierItem
import com.wotagei.cam.ui.widget.TierPicker
import com.wotagei.cam.ui.widget.deadbandLadder
import com.wotagei.cam.ui.widget.decodeContinuousSliders
import com.wotagei.cam.ui.widget.encodeContinuousSliders
import com.wotagei.cam.update.ProbeResult
import com.wotagei.cam.update.ReleaseNotes
import com.wotagei.cam.update.UpdateDownloader
import com.wotagei.cam.update.UpdateInstaller
import com.wotagei.cam.update.probeLatestRelease
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import com.wotagei.cam.ui.anim.LocalMotion

/**
 * 设置页（06 文档 §1 `/settings`）：录制默认档位、动画风格、参考线默认、水平仪开关、权限状态、关于。
 * 全部落 [SharedPreferences]，键集中在 [WotaSettings]，录制页首帧前读一次套用。
 */

/** 设置持久化（键名即契约：`motion_mode`、`default_fps` 等） */
object WotaSettings {
    const val PREFS = "wota_settings"
    const val KEY_MOTION = "motion_mode"
    const val KEY_DEFAULT_FPS = "default_fps"
    const val KEY_DEFAULT_SHUTTER_NS = "default_shutter_ns"
    const val KEY_DEFAULT_BITRATE = "default_bitrate"
    const val KEY_DEFAULT_SAMPLE_RATE = "default_sample_rate"
    const val KEY_DEFAULT_RENDER = "default_render_mode"
    /** 静音录制的初始值：录制页已不再有「更多」胶囊放它，所以归到设置页（§37 第 3 条） */
    const val KEY_DEFAULT_AUDIO_MUTE = "default_audio_muted"

    /**
     * 录制页参数持久化的一套键（用户反馈第 1 条；2026-10-16 用户拍板口径）。
     *
     * 语义 = **键即启动档位**（上次用过就是下次启动值）：本组 19 键与上面 5 个存量键
     * （[KEY_DEFAULT_FPS] / [KEY_DEFAULT_SHUTTER_NS] / [KEY_DEFAULT_BITRATE] /
     * [KEY_DEFAULT_REF_LINES] / [KEY_LEVEL_ENABLED]）构成入档白名单（24 项），见 [ParamsPersist.KEYS]。
     *
     * - 恢复侧 [ParamsPersist.restore]（由 [applyDefaults] 末尾调用）读键 → 坏值回默认 → 写总线；
     * - 写入侧 [ParamsPersistSession.flush] 只在用户动作完成点（关弹窗/长按锁 AE/退后台/滑杆松手）
     *   把「本次会话真的动过」的字段批量 `commit()` 落盘。
     *
     * [KEY_DEFAULT_FPS] / [KEY_DEFAULT_SHUTTER_NS] 为**复用存量键**（R1/2026-10-16 拍板：设置页的
     * 「默认帧率」「默认快门」两行已删，改由录制页 flush 写入；键不动、恢复链不动）。
     *
     * **刻意不入档**的字段（防后人"补齐"）：
     * - `default_render_mode` / `default_sample_rate`：设置页独占——录制页对 renderMode 的两处写
     *   全是「※ 档自动切 GPU」的**自动降级**，入档会把降级静默写成用户默认；
     * - `default_audio_muted`：**音源不持久化**（R3/2026-10-16 拍板）——录制页音源双开关
     *   （内录/环境音）任何路径都不写 prefs、不跨进程恢复；冷启动内录恒为关、环境音按出厂默认
     *   进入（否则开过内录的用户每次启动都要重新申请投屏授权）。该键仍归设置页「静音录制」独占；
     * - 对焦框（瞬时点按态）、`curve` / `arc_convert_mode`（各有专属直写链，避免第二写入方）。
     */
    const val KEY_DEFAULT_ISO = "default_iso"
    const val KEY_DEFAULT_AE_MODE = "default_ae_mode"
    const val KEY_DEFAULT_EV = "default_ev"
    const val KEY_DEFAULT_WB_PRESET = "default_wb_preset"
    const val KEY_DEFAULT_WB_KELVIN = "default_wb_kelvin"
    const val KEY_DEFAULT_WB_TINT = "default_wb_tint"
    const val KEY_DEFAULT_ZOOM = "default_zoom"
    const val KEY_DEFAULT_FLASH = "default_flash"
    const val KEY_DEFAULT_STABILIZE = "default_stabilize"
    const val KEY_DEFAULT_AF_MODE = "default_af_mode"
    const val KEY_DEFAULT_MF_DIOPTER = "default_mf_diopter"
    const val KEY_DEFAULT_SIZE = "default_size"
    const val KEY_DEFAULT_FRAME_EFFECT = "default_frame_effect"
    const val KEY_DEFAULT_ZEBRA_THRESHOLD = "default_zebra_threshold"
    const val KEY_DEFAULT_ZEBRA_DENSITY = "default_zebra_density"
    const val KEY_DEFAULT_PEAKING_STRENGTH = "default_peaking_strength"
    const val KEY_DEFAULT_PEAKING_COLOR = "default_peaking_color"
    const val KEY_DEFAULT_LENS = "default_lens"

    /**
     * 补弧合并差分死区 D（8bit luma 级，0..32，默认 12）：`Δluma > D` 的整颗像素取被抽帧，
     * 否则保持保留帧。只影响整像素选择，不是绝对亮度阈值；0 = 纯整像素选择（仍不造色）。
     */
    const val KEY_DEFAULT_ARC_MERGE_DEADBAND = "default_arc_merge_deadband"

    /** 降噪两项（2026-10-26）：默认关，与相机参数同一条"录制页即改即存"纪律 */
    const val KEY_DENOISE_AUDIO = "denoise_audio"
    const val KEY_DENOISE_VIDEO = "denoise_video"

    /**
     * 站位线的**间距与颜色**（2026-10-27 用户裁决）：只持久化这两项，
     * **线数不入档**——"启动时取景器能干净无线"是用户点名的口径，所以默认线数恒为
     * [com.wotagei.cam.core.MarkerLines.DEFAULT_COUNT]（0 = 关），上次开过几条不带进下次。
     * 间距存 float（产品域 0.15..1.00，恢复侧钳制），颜色存枚举名（坏串回黄）。
     */
    const val KEY_DEFAULT_MARKER_SPREAD = "default_marker_spread"
    const val KEY_DEFAULT_MARKER_COLOR = "default_marker_color"

    /**
     * 上屏渲染比例（2026-10-27 用户需求；docs/plan/24 R3 §2.3）：存枚举名（`ORIG`/`HALF`/…），
     * 坏串回 [com.wotagei.cam.core.DisplayScale.ORIG]。**不重建相机会话**——它只改窗口 pass 的 RT 尺寸。
     */
    const val KEY_DISPLAY_SCALE = "display_scale"

    /**
     * Glow 光晕的**整份参数**（2026-10-27 用户需求；docs/plan/24 R3 §1.5）：一个复合串键，
     * `encodeGlow`/`decodeGlow` 纯函数负责往返（坏串整份回默认）。不摊成 10 个 prefs 键——
     * 那会让"参数模型加一维"变成"持久化白名单也要加一条"，与 `hud_layout` 同族的复合键口径一致。
     */
    const val KEY_GLOW = "glow_params"

    /** 诊断日志总开关（默认关，2026-10-13 用户裁决：避免不必要的空间占用；崩溃件不受它影响） */
    const val KEY_DIAG_ENABLED = "diag_enabled"

    /** 详细诊断日志（默认关）：开启后 `d/v` 级也写进日志文件，供用户配合复现时用 */
    const val KEY_DIAG_VERBOSE = "diag_verbose"
    const val KEY_DEFAULT_REF_LINES = "default_ref_lines"
    const val KEY_LEVEL_ENABLED = "level_enabled"
    const val KEY_LEVEL_BUZZ = "level_buzz_enabled"
    const val KEY_HUD_ITEMS = "hud_items"
    /** 录制页控件胶囊的显隐（#54）：与读数分开一套位掩码，默认全开 */
    const val KEY_HUD_PILLS = "hud_pills"

    /**
     * 显隐位清单的**版本号**（2026-10-26）：与 [KEY_HUD_PILLS] 一起读写。存在的意义见
     * [CamPill.MASK_SCHEMA]：缺它就没法区分"用户关掉了这颗"与"存掩码时这颗还不存在"，
     * 新加的胶囊于是对老用户默认隐藏。
     */
    const val KEY_HUD_PILLS_SCHEMA = "hud_pills_schema"
    /**
     * 录制页的默认方向（13 号计划第 6 条）：`landscape`（默认）/ `portrait`。
     * 纯 UI 设置，**不进 [applyDefaultsOnce]** —— 那条链进程内只套一次，设置页改完回录制页会被挡住，
     * 所以由 `MainActivity.applyPageMode()` 每次换页直读 prefs。
     */
    const val KEY_UI_ORIENTATION = "ui_orientation"
    /**
     * 可拖动容器的位置表（13 号计划第 5、8 条）：版本化的「容器 id → (x, y) + 容器内条目顺序」，
     * 编解码与钳制全在 [HudLayoutTable]，一条紧凑串存进同一个 prefs 文件。
     *
     * 与 `hud_items` / `ui_orientation` 同一类**纯 UI 设置**：消费方每次组合直读 prefs，
     * **不走 [applyDefaultsOnce]**（那条链进程内只套一次，会把编辑页"保存即生效"与设置页的改动挡掉）。
     * 缺键 = [HudLayoutTable.default]，也就是 B1–B3 的定稿位置；控件被隐藏再打开显示时不回默认，
     * 落点在 [HudLayoutTable.visibleOrderOf]（过滤只读表、不改表）。
     */
    const val KEY_HUD_LAYOUT = "hud_layout"
    const val KEY_TEXT_SCALE_CAMERA = "text_scale_camera"
    const val KEY_TEXT_SCALE_SETTINGS = "text_scale_settings"
    const val KEY_TEXT_SCALE_DIALOG = "text_scale_dialog"
    const val KEY_GALLERY_COLUMNS = "gallery_columns"

    /**
     * 「启动后自动亮屏」的延迟秒数（29 §5.3-①，2026-10-28）：0–10 步长 1，**0 = 关闭**
     * （不是"立即"——立即等于撤销跟随系统，产品口径里关闭就是全程跟随系统）。
     * 非 0：冷启后第一次进入取景页，延迟结束后 7 秒内线性渐变到满亮度（渐变时长是
     * [BrightnessRamp.CAMERA_BRIGHTEN_RAMP_MS]，29 §九 定版「7 秒 = 渐变时长、延迟另算」）。
     * 纯 UI 设置：`BrightnessRamp` 每次触发直读 prefs，**不走 [applyDefaultsOnce]**。
     */
    const val KEY_CAMERA_BRIGHTEN_DELAY_SEC = "camera_brighten_delay_sec"
    /**
     * 「起播渐亮」的时长（29 §5.3-②）：0.0–3.0 秒存**十分之一秒**整数（步长 5），
     * 默认 10 = 1.0 秒；0 = 关闭。单播放页首次起播时从当前窗口亮度渐变到满，离开页面恢复。
     * 纯 UI 设置，同上不走 [applyDefaultsOnce]。
     */
    const val KEY_PLAYER_BRIGHTEN_RAMP_TENTHS = "player_brighten_ramp_tenths"

    /**
     * 播放页顶栏持久收起（29 §6.3，2026-10-28）：boolean，缺省 false = 展开（保持现行为）。
     * 进页读、点按写，退出重进/冷启保持。纯 UI 设置，**不走 [applyDefaultsOnce]**。
     */
    const val KEY_PLAYER_TOP_BAR_COLLAPSED = "player_top_bar_collapsed"

    /**
     * 曲线面板**实际边长** dp（29 §1.2 F1，2026-10-28）：存 dp 不存 scale——跨屏幕/转屏时
     * baseSide 会变，存 scale 会漂移。缺省 200（= 设计档 CurveSide），读取时经
     * [curvePanelSideDp] 钳进合法域。写：把手拖拽抬手一次 + 「重置」写回 200；
     * 纯 UI 设置，**不走 [applyDefaultsOnce]**。
     */
    const val KEY_CURVE_PANEL_SIDE_DP = "curve_panel_side_dp"

    /**
     * 滑杆吸附开关（2026-10-21 用户反馈：变焦只有 0.5/1/1.5…，想调 1.2×/1.7×）。
     *
     * 存**关掉吸附**（改用连续值）的参数 id 集合，逗号分隔；缺键/空串 = 全部吸附（2026-10-17 的
     * 现行为）。纯 UI 设置：`PillSlider` 每次组成时直读 prefs，**不走 [applyDefaultsOnce]**
     * （那条链进程内只套一次，会把设置页的改动挡掉）。
     */
    const val KEY_SLIDER_CONTINUOUS = "slider_continuous"

    /**
     * 强制 24/25fps 在无原生精确档设备上的录制期转换模式（2026-10-03 定版）：
     * `MEND`（默认，抽帧+整像素选择补弧）/ `DROP`（仅抽帧）。只在帧率弹层选中 24/25 且设备无
     * 精确档时生效；设备有原生档时该控件不显示、值不参与录制。
     */
    const val KEY_ARC_CONVERT = "arc_convert_mode"

    /**
     * 取景 HUD 的毛玻璃背板（#84 步骤 2 · A2 混合）。**默认关**，且只有用户在下面那一行亲手翻过
     * 才会为 true：这条不走 [applyDefaultsOnce]、不被任何"上次记得的开"之外的路径自动打开——
     * DIRECT 模式或离屏链停用时它开着也不会有板（`HudFrost.live` 只认 GL 的真回报）。
     *
     * 消费方是 [com.wotagei.cam.ui.HudFrost]（prefs 监听 + 开着期间的低频轮询），它把这一位写进
     * `camera/FrostCardTable` 的表头，GL 每帧搬进引擎那枚门 —— 录制页拿不到引擎实例，也不该拿到。
     */
    const val KEY_FROST_BLUR = "hud_frost_blur"

    /** 手调曲线的恢复值：曲线是用户一点点拖出来的，重开进程不该丢 */
    const val KEY_CURVE_STACK = "curve_stack"

    /** 网格列数允许的档位：小屏 2 列看得清，5 列用来快速翻找 */
    val GALLERY_COLUMN_TIERS = listOf(2, 3, 4, 5)

    /**
     * 对比播放编排节拍（r02）：两轨素材域判定/纠偏/回绕的 tick 间隔。
     * 默认 200ms，允许 100–1000ms；越界值钳回区间（手写 prefs 的脏数据不炸）。
     * 消费方（CompareScreen 编排循环）每拍重读，改完下一拍生效，不走 [applyDefaultsOnce]。
     */
    const val KEY_COMPARE_TICK_MS = "compare_tick_ms"
    const val COMPARE_TICK_MIN_MS = 100
    const val COMPARE_TICK_MAX_MS = 1000
    val COMPARE_TICK_TIERS = listOf(100, 200, 500, 1000)

    /** 编排节拍：缺键 200，越界钳回 [COMPARE_TICK_MIN_MS]..[COMPARE_TICK_MAX_MS] */
    fun compareTickMs(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_COMPARE_TICK_MS, 200).coerceIn(COMPARE_TICK_MIN_MS, COMPARE_TICK_MAX_MS)

    /** 文本高度缩放的可选区间（%），100 = 工程默认排版 */
    val TEXT_SCALE_PCTS = listOf(80, 90, 100, 110, 120)

    @Volatile
    private var defaultsApplied = false

    fun of(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun motionMode(prefs: SharedPreferences): MotionMode =
        runCatching { MotionMode.valueOf(prefs.getString(KEY_MOTION, MotionMode.FLUENT.name)!!) }
            .getOrDefault(MotionMode.FLUENT)

    fun setMotionMode(prefs: SharedPreferences, mode: MotionMode) {
        prefs.edit().putString(KEY_MOTION, mode.name).apply()
    }

    /** 录制期转换模式（强制 24/25fps 无原生精确档时）：默认自动抽帧补弧 */
    fun arcConvertMode(prefs: SharedPreferences): ArcConvertMode =
        runCatching { ArcConvertMode.valueOf(prefs.getString(KEY_ARC_CONVERT, ArcConvertMode.MEND.name)!!) }
            .getOrDefault(ArcConvertMode.MEND)

    fun setArcConvertMode(prefs: SharedPreferences, mode: ArcConvertMode) {
        prefs.edit().putString(KEY_ARC_CONVERT, mode.name).apply()
    }

    fun defaultFps(prefs: SharedPreferences) = prefs.getInt(KEY_DEFAULT_FPS, 25)
    fun defaultShutterNs(prefs: SharedPreferences) = prefs.getInt(KEY_DEFAULT_SHUTTER_NS, 40_000_000)
    fun defaultBitrate(prefs: SharedPreferences) = prefs.getInt(KEY_DEFAULT_BITRATE, 10_000_000)
    fun defaultSampleRate(prefs: SharedPreferences) =
        prefs.getInt(KEY_DEFAULT_SAMPLE_RATE, WotaTiers.DEFAULT_SAMPLE_RATE)

    /** 补弧合并差分死区：缺键默认 12，手改 prefs 的越界值钳回 0..32 */
    fun arcMergeDeadband(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DEFAULT_ARC_MERGE_DEADBAND, ArcMergeDeadband.DEFAULT)
            .coerceIn(ArcMergeDeadband.MIN, ArcMergeDeadband.MAX)

    /** 音频降噪档（坏串回 OFF——降噪是"改变了声音"的开关，不认识的值一律按关处理） */
    fun audioDenoise(prefs: SharedPreferences): com.wotagei.cam.core.AudioDenoise =
        com.wotagei.cam.core.AudioDenoise.fromKey(prefs.getString(KEY_DENOISE_AUDIO, null))

    /** 画面降噪档（坏串回 OFF，同上） */
    fun videoDenoise(prefs: SharedPreferences): com.wotagei.cam.core.VideoDenoise =
        com.wotagei.cam.core.VideoDenoise.fromKey(prefs.getString(KEY_DENOISE_VIDEO, null))

    fun defaultAudioMuted(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DEFAULT_AUDIO_MUTE, false)

    /** 诊断日志总开关（见 [com.wotagei.cam.diag.Diag]）：默认关，关着时不写常规日志（崩溃件除外） */
    fun diagEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DIAG_ENABLED, false)

    fun setDiagEnabled(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY_DIAG_ENABLED, on).apply()
    }

    /** 详细诊断日志（见 [com.wotagei.cam.diag.Diag]）：默认关，只记关键事件；开启后连 d/v 一起记 */
    fun diagVerbose(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DIAG_VERBOSE, false)

    fun setDiagVerbose(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY_DIAG_VERBOSE, on).apply()
    }

    fun defaultRenderMode(prefs: SharedPreferences): RenderMode =
        if (prefs.getString(KEY_DEFAULT_RENDER, RenderMode.GPU.name) == RenderMode.DIRECT.name) {
            RenderMode.DIRECT
        } else {
            RenderMode.GPU
        }

    fun defaultRefLines(prefs: SharedPreferences) = prefs.getInt(KEY_DEFAULT_REF_LINES, 0)
    fun levelEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_LEVEL_ENABLED, true)
    fun levelBuzzEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_LEVEL_BUZZ, true)
    fun hudItems(prefs: SharedPreferences) = prefs.getInt(KEY_HUD_ITEMS, HudItem.DEFAULT_MASK)

    /**
     * 显隐掩码（读）。缺键 = 从没动过 ⇒ 全开；有键但**版本落后** ⇒ 只补上"那一版之后新加的位"
     * （[CamPill.migrateMask]），用户自己关过的一律不动。
     *
     * 这里是**纯读**（不写回）：迁移是幂等的，用户下一次在设置页动任何一颗时会把新掩码与新版本号
     * 一起落盘，那之后迁移自然停止生效——没必要在 getter 里偷偷写盘（那个副作用会散到所有调用点）。
     */
    fun hudPills(prefs: SharedPreferences): Int {
        val raw = prefs.getInt(KEY_HUD_PILLS, CamPill.DEFAULT_MASK)
        val schema = prefs.getInt(KEY_HUD_PILLS_SCHEMA, 0)
        return if (schema >= CamPill.MASK_SCHEMA) raw else CamPill.migrateMask(raw, schema)
    }

    /** 关掉刻度吸附（改用连续值）的滑杆集合：缺键 = 空集 = 全部吸附 */
    fun continuousSliders(prefs: SharedPreferences): Set<SliderKey> =
        decodeContinuousSliders(prefs.getString(KEY_SLIDER_CONTINUOUS, null))

    fun setContinuousSliders(prefs: SharedPreferences, keys: Set<SliderKey>) {
        prefs.edit().putString(KEY_SLIDER_CONTINUOUS, encodeContinuousSliders(keys)).apply()
    }

    /** 该滑杆当前是否为「连续值」模式（设置页逐个开关的读取口） */
    fun sliderContinuous(prefs: SharedPreferences, key: SliderKey): Boolean =
        key in continuousSliders(prefs)

    /** 录制页默认方向：缺键或坏串都回落到 [UIOrientation.DEFAULT]（横屏），不抛 */
    fun uiOrientation(prefs: SharedPreferences): UIOrientation =
        UIOrientation.fromPersistValue(prefs.getString(KEY_UI_ORIENTATION, UIOrientation.DEFAULT.persistValue))

    /**
     * 这里**故意用 `commit()`（同步落盘）而不是 `apply()`**：方向是"刚改完就可能被强杀"的低频设置——
     * `apply()` 只把改动排进异步落盘队列，`am force-stop` 这类强杀会连队列一起丢掉，于是"改了没生效"
     * （B4 那轮就是被这条误判成 S1 缺陷的）。低频点击，主线程这一次磁盘 IO 的代价可以接受。
     */
    @Suppress("ApplySharedPref")
    fun setUiOrientation(prefs: SharedPreferences, orientation: UIOrientation) {
        prefs.edit().putString(KEY_UI_ORIENTATION, orientation.persistValue).commit()
    }

    /** 位置表：坏串由 [HudLayoutTable.decode] 吞掉并回落默认表，不抛（与 [curveStack] 同一条纪律） */
    fun hudLayout(prefs: SharedPreferences): HudLayoutTable =
        HudLayoutTable.decode(prefs.getString(KEY_HUD_LAYOUT, null))

    /**
     * 同 [setUiOrientation]：**同步 `commit()`**。位置是用户"拖完就杀进程验一次"的东西，
     * `apply()` 的异步落盘会被 `am force-stop` 吃掉，验收时就会把"没落盘"误读成"位置没保存住"。
     *
     * @return `commit()` 的结果，也就是**这次改动是否真的写进了磁盘**。写入方必须把它当"保存回执"用：
     * 返回 false 就不许再对用户说"已保存"。低频拖动的松手/点保存，一次同步 IO 的代价可以接受。
     */
    @Suppress("ApplySharedPref")
    fun setHudLayout(prefs: SharedPreferences, table: HudLayoutTable): Boolean =
        prefs.edit().putString(KEY_HUD_LAYOUT, table.encode()).commit()

    /**
     * 重置：整张表清回默认（删键，缺键就是 [HudLayoutTable.default]）。
     * 「即时可重做」靠的是调用方留着重置前那一份串，写回 [setHudLayout] 就是撤销。
     * 与 [setHudLayout] 同理走同步 `commit()`，返回值是"这次删除是否真落盘"的回执。
     */
    @Suppress("ApplySharedPref")
    fun clearHudLayout(prefs: SharedPreferences): Boolean =
        prefs.edit().remove(KEY_HUD_LAYOUT).commit()

    /** 各屏文本高度缩放系数；100% 即工程默认排版，越界值钳回区间内 */
    fun textScale(prefs: SharedPreferences, key: String): Float =
        (prefs.getInt(key, 100).coerceIn(TEXT_SCALE_PCTS.first(), TEXT_SCALE_PCTS.last()) / 100f)

    /**
     * 「启动后自动亮屏」延迟（秒）：坏值/越界回**默认 1**（0 是合法档=关闭，不能当坏值兜底用，
     * 否则用户手改 prefs 写个 -5 反而把"关闭"当默认铺开）。默认值 = 29 §九 推荐（1s）。
     */
    fun cameraBrightenDelaySec(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_CAMERA_BRIGHTEN_DELAY_SEC, 1).coerceIn(0, 10)

    /** 「起播渐亮」时长（十分之一秒）：坏值/越界回默认 10（=1.0s，29 §五 表定） */
    fun playerBrightenRampTenths(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_PLAYER_BRIGHTEN_RAMP_TENTHS, 10).coerceIn(0, 30)

    /** 播放页顶栏收起态：缺键 = 展开。读侧不做归一（boolean 没有坏域），写入方只有点按两枚钮 */
    fun playerTopBarCollapsed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PLAYER_TOP_BAR_COLLAPSED, false)

    fun setPlayerTopBarCollapsed(prefs: SharedPreferences, collapsed: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_TOP_BAR_COLLAPSED, collapsed).apply()
    }

    /**
     * 曲线面板边长（dp）：坏值/缺键回 200（设计档），NaN/负数/超大在**读取侧**只钳下限——
     * 上限（可用区）是组合期才有的输入，由 [com.wotagei.cam.core.CurvePanelSize.clamp]
     * 在组合期带着 maxSide 二次钳。
     */
    fun curvePanelSideDp(prefs: SharedPreferences): Float {
        val v = prefs.getFloat(KEY_CURVE_PANEL_SIDE_DP, 200f)
        return if (v.isNaN() || v < 0f) 200f else v
    }

    fun setCurvePanelSideDp(prefs: SharedPreferences, sideDp: Float) {
        prefs.edit().putFloat(KEY_CURVE_PANEL_SIDE_DP, sideDp).apply()
    }

    /** 网格列数：旧值不在档位里（例如手写进 prefs 的 6）一律回落到 2 */
    fun galleryColumns(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_GALLERY_COLUMNS, 2).let { if (it in GALLERY_COLUMN_TIERS) it else 2 }

    /**
     * 毛玻璃背板开关（[KEY_FROST_BLUR]）：**缺键 = false**。
     * 这条刻意不给"跟随系统/跟随渲染模式"之类的隐式来源，默认关就是字面意义的默认关。
     */
    fun frostBlurEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_FROST_BLUR, false)

    /**
     * 写入开关。用 `apply()`：这一位的消费方是 prefs 监听（同进程内存里立刻可见），
     * 磁盘落不落与观感无关，不需要 [setHudLayout] 那种同步落盘回执。
     */
    fun setFrostBlurEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FROST_BLUR, enabled).apply()
    }

    /** 坏串由 [CurveStack.decode] 吞掉并回落恒等，不会抛 */
    fun curveStack(prefs: SharedPreferences): CurveStack =
        CurveStack.decode(prefs.getString(KEY_CURVE_STACK, null))

    fun setCurveStack(prefs: SharedPreferences, stack: CurveStack) {
        prefs.edit().putString(KEY_CURVE_STACK, stack.encode()).apply()
    }

    /**
     * 把默认档位套进参数总线。进程内只应用一次（[defaultsApplied]），
     * 之后用户在录制页的临时改动不会被重建的 CameraScreen 覆盖；能力换挡（applyAbility）仍会钳制这些值。
     */
    fun applyDefaultsOnce(ctx: Context, params: WotaParams) {
        if (defaultsApplied) return
        defaultsApplied = true
        applyDefaults(of(ctx), params)
    }

    /**
     * 套用逻辑本体，不做进程内去重：这样 JVM 单测可以用一份假 SharedPreferences
     * 直接验「设置页改过默认档 → 参数总线真的跟过去」（#20 的核心命题），
     * 不必等真机杀进程重进。
     *
     * 末尾接 [ParamsPersist.restore]（用户反馈第 1 条）：把录制页存档的 24 项键一次读回总线
     * （上面那批与它重叠的几项幂等）。此即"设置页默认档"与"录制页上次用过的值"合流的那一处——
     * 语义统一为「键即启动档位」。
     */
    fun applyDefaults(prefs: SharedPreferences, params: WotaParams) {
        val fps = defaultFps(prefs).coerceIn(WotaTiers.FPS.first(), WotaTiers.FPS.last())
        params.setFps(params.fps.value.copy(value = fps))
        params.shutter.value = params.shutter.value.copy(value = defaultShutterNs(prefs).toLong())
        params.bitrate.value = defaultBitrate(prefs)
        params.sampleRate.value = defaultSampleRate(prefs)
        params.renderMode.value = defaultRenderMode(prefs)
        params.audioEnabled.value = !defaultAudioMuted(prefs)
        params.refLines.value = defaultRefLines(prefs)
        params.levelEnabled.value = levelEnabled(prefs)
        params.curve.value = curveStack(prefs)
        ParamsPersist.restore(prefs, params)
    }

    /** 媒体库读取权限：33+ 走细分权限，以下回退旧存储权限（版本闸收在 core/MediaAccess 单一处） */
    fun mediaReadPermission(): String = mediaReadPermissionFor(android.os.Build.VERSION.SDK_INT)
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHudEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember(context) { WotaSettings.of(context) }

    // 两栏分区的宽度真源：BoxWithConstraints 的 maxWidth 是「本页可用宽度」（已扣系统栏内边距），
    // 比 LocalConfiguration.screenWidthDp 更贴近实际排版宽度；横竖屏切换会重算分区
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(WotaBg)
            .statusBarsPadding()
    ) {
        val plan = layoutPlanOf(maxWidth.value.toInt())
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.set_back), tint = WotaText)
                }
                Text(
                    text = stringResource(R.string.set_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = WotaText,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Line()
            SettingsBody(plan, prefs, onOpenHudEditor)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 设置主体：按 [SettingsLayoutPlan] 决定单栏还是两栏。**顶栏不在这里**——它跨两栏、不参与分区。
 *
 * 单栏走 [SettingsLayoutPlan.left]（此时它就是 [blocksOf] 全表，顺序 = 改造前的视觉顺序）；
 * 两栏时左右各一个 weight(1f) 的 Column，整体仍在同一个 verticalScroll 里（单滚动）。
 */
@Composable
private fun SettingsBody(
    plan: SettingsLayoutPlan,
    prefs: SharedPreferences,
    onOpenHudEditor: () -> Unit
) {
    // 两栏时卡片外距收到 6dp（左右两块各 6dp，合计仍是约 12dp 的视觉间距）；单栏维持 12dp 不动
    val cardPad = if (plan.columns == 2) 6.dp else 12.dp
    CompositionLocalProvider(LocalSettingsCardPad provides cardPad) {
        if (plan.columns == 1) {
            plan.left.forEach { BlockHost(it, prefs, onOpenHudEditor) }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    plan.left.forEach { BlockHost(it, prefs, onOpenHudEditor) }
                }
                Column(Modifier.weight(1f)) {
                    plan.right.forEach { BlockHost(it, prefs, onOpenHudEditor) }
                }
            }
        }
    }
}

/** 区块枚举 → 具名 composable 的派发口：[SettingsBody] 只认 [SettingsBlock]，具体挂载点收在这里 */
@Composable
private fun BlockHost(block: SettingsBlock, prefs: SharedPreferences, onOpenHudEditor: () -> Unit) {
    when (block) {
        SettingsBlock.DEFAULT -> BlockDefault(prefs)
        SettingsBlock.ORIENTATION -> BlockOrientation(prefs)
        SettingsBlock.MOTION -> BlockMotion(prefs)
        SettingsBlock.BRIGHTNESS -> BlockBrightness(prefs)
        SettingsBlock.HUD -> BlockHud(prefs, onOpenHudEditor)
        SettingsBlock.SLIDER -> BlockSlider(prefs)
        SettingsBlock.STORAGE -> BlockStorage()
        SettingsBlock.REFLINE -> BlockRefline(prefs)
        SettingsBlock.LEVEL -> BlockLevel(prefs)
        SettingsBlock.COMPARE -> BlockCompare(prefs)
        SettingsBlock.TEXT -> BlockText(prefs)
        SettingsBlock.PERM -> BlockPerm()
        SettingsBlock.ABOUT -> BlockAbout()
        SettingsBlock.DIAG -> BlockDiag(prefs)
    }
}

/**
 * 设置卡左右外距（单栏 12dp / 两栏 6dp）。走 CompositionLocal 传而不是给 [Card] 加必传参，
 * 是为了让 11 个 Block 里的 `Card { ... }` 调用点**逐字保持搬动前的样子**（本刀只做搬动）。
 */
private val LocalSettingsCardPad = compositionLocalOf { 12.dp }

@Composable
private fun BlockDefault(prefs: SharedPreferences) {
    // 帧率/快门两行已删（R1/2026-10-16 拍板）：这两项只在录制页改，由 ParamsPersistSession
    // 落盘到同一个 default_fps / default_shutter_ns 键（键与恢复链都没动），设置页不再重复出档
    var defaultBitrate by remember { mutableStateOf(WotaSettings.defaultBitrate(prefs)) }
    var defaultSampleRate by remember { mutableStateOf(WotaSettings.defaultSampleRate(prefs)) }
    var defaultRender by remember { mutableStateOf(WotaSettings.defaultRenderMode(prefs)) }
    var defaultMuted by remember { mutableStateOf(WotaSettings.defaultAudioMuted(prefs)) }
    var defaultDeadband by remember { mutableStateOf(WotaSettings.arcMergeDeadband(prefs)) }
    // 并入门槛要写**同一个**总线 setter（参数域钳制 + 同步发布 ArcMergeDeadband 快照都在里面）：
    // 设置页本来不持有参数总线，照 HudLayoutEditor 的惯例造一份本地总线（开机默认档照常套上），别处不读它。
    val settingsParams = remember(prefs) {
        WotaParams(CoroutineScope(Dispatchers.Unconfined)).also {
            WotaSettings.applyDefaults(prefs, it)
        }
    }

    SettingGroup(stringResource(R.string.set_group_default))
    // 本页所有 bodyMedium 说明统一升 textMid（下面各卡同）：textLo 压 surface 只有 3.82，
    // 过不了正文 4.5，Tokens 口径「正文与数值一律 textMid 以上」
    Text(
        text = stringResource(R.string.set_default_note),
        style = MaterialTheme.typography.bodyMedium,
        color = WotaColor.textMid,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Card {
        TierPicker(
            label = stringResource(R.string.set_default_bitrate),
            selected = defaultBitrate / 1_000_000,
            items = WotaTiers.BITRATES.map { TierItem(it / 1_000_000, bitrateText(it)) },
            format = { "${it}M" },
            onPick = {
                defaultBitrate = it * 1_000_000
                prefs.edit().putInt(WotaSettings.KEY_DEFAULT_BITRATE, defaultBitrate).apply()
            }
        )
        TierPicker(
            label = stringResource(R.string.set_default_sample),
            selected = defaultSampleRate,
            items = WotaTiers.SAMPLE_RATES.map { TierItem(it, sampleRateText(it)) },
            format = { sampleRateText(it) },
            onPick = {
                defaultSampleRate = it
                prefs.edit().putInt(WotaSettings.KEY_DEFAULT_SAMPLE_RATE, it).apply()
            }
        )
        val gpuLabel = stringResource(R.string.cam_render_gpu)
        val directLabel = stringResource(R.string.cam_render_direct)
        val renderNames = listOf(gpuLabel, directLabel)
        TierPicker(
            label = stringResource(R.string.set_default_render),
            selected = defaultRender.ordinal,
            items = RenderMode.values().map { TierItem(it.ordinal, renderNames.getOrElse(it.ordinal) { "" }) },
            format = { renderNames.getOrElse(it) { "" } },
            onPick = { idx ->
                val mode = if (idx == RenderMode.DIRECT.ordinal) RenderMode.DIRECT else RenderMode.GPU
                defaultRender = mode
                prefs.edit().putString(WotaSettings.KEY_DEFAULT_RENDER, mode.name).apply()
            }
        )
        // 并入门槛（补弧差分死区 D，0..32，默认 12）：冷启动默认值，与录制页/播放器共用同一个键。
        // 拖动即写总线（快照同步），松手才落盘——与录制页滑杆同一条账
        PillSlider(
            value = defaultDeadband.toFloat(),
            lo = ArcMergeDeadband.MIN.toFloat(),
            hi = ArcMergeDeadband.MAX.toFloat(),
            step = 1f,
            label = stringResource(R.string.set_default_deadband),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            onValueChange = {
                val v = it.roundToInt()
                defaultDeadband = v
                settingsParams.setArcMergeDeadband(v)
            },
            format = { "${it.roundToInt()}" },
            onValueChangeFinished = {
                prefs.edit().putInt(WotaSettings.KEY_DEFAULT_ARC_MERGE_DEADBAND, defaultDeadband).apply()
            },
            // 整数域（0..32 逐格），没有可关的吸附
            snapKey = null,
            ladder = deadbandLadder()
        )
        Text(
            text = stringResource(R.string.set_default_deadband_note),
            style = MaterialTheme.typography.labelSmall,
            color = WotaTextDim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.set_default_mute),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WotaText
                )
                Text(
                    text = stringResource(R.string.set_default_mute_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = WotaTextDim
                )
            }
            Switch(
                checked = defaultMuted,
                onCheckedChange = {
                    defaultMuted = it
                    prefs.edit().putBoolean(WotaSettings.KEY_DEFAULT_AUDIO_MUTE, it).apply()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accentActive, checkedThumbColor = WotaColor.onAccent)
            )
        }
    }
}

@Composable
private fun BlockOrientation(prefs: SharedPreferences) {
    var uiOrientation by remember { mutableStateOf(WotaSettings.uiOrientation(prefs)) }

    SettingGroup(stringResource(R.string.set_group_orientation))
    Text(
        text = stringResource(R.string.set_orientation_note),
        style = MaterialTheme.typography.bodyMedium,
        color = WotaColor.textMid,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Card {
        val landscapeLabel = stringResource(R.string.set_orientation_landscape)
        val portraitLabel = stringResource(R.string.set_orientation_portrait)
        val options = UIOrientation.ALL
        // 标签按枚举本身取，不用 ordinal 去索引定长列表（同上面动效档那条教训：加档时会显示空标签）
        val nameOf = { o: UIOrientation ->
            when (o) {
                UIOrientation.LANDSCAPE -> landscapeLabel
                UIOrientation.PORTRAIT -> portraitLabel
            }
        }
        TierPicker(
            label = stringResource(R.string.set_orientation_style),
            selected = uiOrientation.ordinal,
            items = options.map { TierItem(it.ordinal, nameOf(it)) },
            format = { nameOf(options.getOrElse(it) { UIOrientation.DEFAULT }) },
            onPick = { idx ->
                val next = options.getOrElse(idx) { UIOrientation.DEFAULT }
                uiOrientation = next
                WotaSettings.setUiOrientation(prefs, next)
            }
        )
    }
}

@Composable
private fun BlockMotion(prefs: SharedPreferences) {
    var motionMode by remember { mutableStateOf(WotaSettings.motionMode(prefs)) }

    SettingGroup(stringResource(R.string.set_group_motion))
    Card {
        val plainLabel = stringResource(R.string.set_motion_plain)
        val fluentLabel = stringResource(R.string.set_motion_fluent)
        val liquidLabel = stringResource(R.string.set_motion_liquid)
        val modes = MotionMode.values()
        // 名称按枚举本身取，不用 ordinal 去索引定长列表：加档位时曾因此显示空标签并把 FLUENT 选回 PLAIN
        val nameOf = { m: MotionMode ->
            when (m) {
                MotionMode.PLAIN -> plainLabel
                MotionMode.FLUENT -> fluentLabel
                MotionMode.LIQUID -> liquidLabel
            }
        }
        TierPicker(
            label = stringResource(R.string.set_motion_style),
            selected = motionMode.ordinal,
            items = modes.map { TierItem(it.ordinal, nameOf(it)) },
            format = { nameOf(modes.getOrElse(it) { MotionMode.FLUENT }) },
            onPick = { idx ->
                val mode = modes.getOrElse(idx) { MotionMode.FLUENT }
                motionMode = mode
                WotaSettings.setMotionMode(prefs, mode)
            },
            note = stringResource(R.string.set_motion_note)
        )
    }
}

@Composable
private fun BlockBrightness(prefs: SharedPreferences) {
    // 读侧已钳（29 §5.5），这里只管展示选中档；0 档 = 关闭（TierPicker 的 zeroValue 提供双击归零）
    var brightenDelay by remember { mutableStateOf(WotaSettings.cameraBrightenDelaySec(prefs)) }
    var playerRamp by remember { mutableStateOf(WotaSettings.playerBrightenRampTenths(prefs)) }
    // TierPicker 的 items/format 是普通 lambda（非 composable 上下文），文案先在外面取好、
    // 带参格式化走 context.getString——stringResource 只能出现在组合里
    val context = LocalContext.current
    val offLabel = stringResource(R.string.set_value_off)

    SettingGroup(stringResource(R.string.set_group_brightness))
    Card {
        // ① 启动后自动亮屏：0–10s 步长 1；0 = 关闭（全程跟随系统）
        TierPicker(
            label = stringResource(R.string.set_brighten_camera),
            selected = brightenDelay,
            items = (0..10).map { TierItem(it, if (it == 0) offLabel else "$it") },
            format = { sec ->
                if (sec == 0) offLabel else context.getString(R.string.set_brighten_seconds, sec)
            },
            onPick = {
                brightenDelay = it
                prefs.edit().putInt(WotaSettings.KEY_CAMERA_BRIGHTEN_DELAY_SEC, it).apply()
            },
            zeroValue = 0,
            note = stringResource(R.string.set_brighten_camera_note)
        )
        // ② 起播渐亮：0–3s 步长 0.5（存十分之一秒）；0 = 关闭
        TierPicker(
            label = stringResource(R.string.set_brighten_player),
            selected = playerRamp,
            items = (0..30 step 5).map { TierItem(it, if (it == 0) offLabel else "$it") },
            format = { tenths ->
                if (tenths == 0) offLabel
                else context.getString(R.string.set_brighten_seconds_tenths, tenths / 10, tenths % 10)
            },
            onPick = {
                playerRamp = it
                prefs.edit().putInt(WotaSettings.KEY_PLAYER_BRIGHTEN_RAMP_TENTHS, it).apply()
            },
            zeroValue = 0,
            note = stringResource(R.string.set_brighten_player_note)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockHud(prefs: SharedPreferences, onOpenHudEditor: () -> Unit) {
    var hudMask by remember { mutableStateOf(WotaSettings.hudItems(prefs)) }
    var pillMask by remember { mutableIntStateOf(WotaSettings.hudPills(prefs)) }
    // 毛玻璃背板（#84 步骤 2）：默认关，只有这一颗 Switch 会把它打开（没有别的写入方）
    var frostBlur by remember { mutableStateOf(WotaSettings.frostBlurEnabled(prefs)) }

    SettingGroup(stringResource(R.string.set_group_hud))
    Card {
        Text(
            text = stringResource(R.string.set_hud_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HudItem.ALL.forEach { item ->
                val on = hudMask and item.bit != 0
                ChipCell(
                    text = stringResource(item.labelRes),
                    selected = on,
                    onClick = {
                        val next = if (on) hudMask and item.bit.inv() else hudMask or item.bit
                        hudMask = next
                        prefs.edit().putInt(WotaSettings.KEY_HUD_ITEMS, next).apply()
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.set_pill_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CamPill.ALL.forEach { item ->
                val on = pillMask and item.bit != 0
                ChipCell(
                    text = stringResource(item.labelRes),
                    selected = on,
                    onClick = {
                        val next = if (on) pillMask and item.bit.inv() else pillMask or item.bit
                        pillMask = next
                        // 版本号一起落盘：之后 [hudPills] 不再做迁移（用户此刻的取舍就是最终取舍）
                        prefs.edit()
                            .putInt(WotaSettings.KEY_HUD_PILLS, next)
                            .putInt(WotaSettings.KEY_HUD_PILLS_SCHEMA, CamPill.MASK_SCHEMA)
                            .apply()
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 位置编辑入口（键 hud_layout）：只列上面开着显示的控件，入口文案必须把这条说清楚
        Text(
            text = stringResource(R.string.set_hud_editor_note),
            style = MaterialTheme.typography.labelSmall,
            color = WotaTextDim
        )
        Spacer(Modifier.height(6.dp))
        ChipCell(
            text = stringResource(R.string.set_hud_editor),
            selected = false,
            onClick = onOpenHudEditor
        )
        Spacer(Modifier.height(10.dp))
        // 毛玻璃背板开关（键 hud_frost_blur，#84 步骤 2 · A2 混合）。
        // ⚠ 本轮**唯一**没走 `res/values/strings_settings.xml` 的两条 UI 文案：那个文件不在本刀的
        // 可改清单里。下一轮把它换成 `R.string.set_frost_blur` / `set_frost_blur_note` 两个 key，
        // 与本文件其余行一致（记在这儿免得被当成"本来就该硬编码"）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "HUD 毛玻璃背板",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WotaText
                )
                Text(
                    text = "取景控件底板透出被模糊的实时画面。仅 GPU 模式生效；关掉即回到现在的观感。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WotaTextDim
                )
            }
            Switch(
                checked = frostBlur,
                onCheckedChange = {
                    frostBlur = it
                    WotaSettings.setFrostBlurEnabled(prefs, it)
                    // 顺手把这一位当场送进矩形表：设置页翻完不用等一次布局回调才带上表头，
                    // GL 在下一帧把它搬进引擎那枚门（UI 拿不到引擎实例，这是唯一一条生产路）
                    FrostCardTable.setUiEnabled(it)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accentActive, checkedThumbColor = WotaColor.onAccent)
            )
        }
    }
}

/**
 * 滑杆吸附（2026-10-21 用户反馈）：默认每根滑杆拖动必落刻度，这里可**逐个参数**改成连续值
 * （例如变焦想调 1.2×、1.7×，不用被 0.5/1/1.5/2 的档卡住）。选中 = 该参数改用连续值（不吸附）。
 *
 * 只列取值域比梯更细的 7 个参数；曝光补偿 / 斑马纹阈值 / 并入门槛本身按整数逐格取值，
 * 连续模式与梯完全等价，列出来只会让人以为开关没生效（见 [SliderKey] KDoc）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockSlider(prefs: SharedPreferences) {
    var continuous by remember { mutableStateOf(WotaSettings.continuousSliders(prefs)) }
    SettingGroup(stringResource(R.string.set_group_slider))
    Card {
        Text(
            text = stringResource(R.string.set_slider_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SliderKey.entries.forEach { key ->
                val on = key in continuous
                ChipCell(
                    text = stringResource(sliderKeyLabelRes(key)),
                    selected = on,
                    onClick = {
                        val next = if (on) continuous - key else continuous + key
                        continuous = next
                        WotaSettings.setContinuousSliders(prefs, next)
                    }
                )
            }
        }
    }
}

/** 滑杆开关的展示名：复用录制页各滑杆自己的标签，同一参数不造第二套叫法 */
@StringRes
private fun sliderKeyLabelRes(key: SliderKey): Int = when (key) {
    SliderKey.SHUTTER -> R.string.cam_p_shutter
    SliderKey.ISO -> R.string.cam_p_iso
    SliderKey.KELVIN -> R.string.cam_p_kelvin
    SliderKey.TINT -> R.string.cam_p_tint
    SliderKey.ZOOM -> R.string.cam_p_zoom
    SliderKey.DIOPTER -> R.string.cam_p_mf
    SliderKey.PEAKING -> R.string.cam_peaking_strength
}

@Composable
private fun BlockStorage() {
    SettingGroup(stringResource(R.string.set_group_storage))
    Card {
        Text(
            text = stringResource(R.string.set_storage_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        val storageCtx = androidx.compose.ui.platform.LocalContext.current
        // 从系统设置页回来时不会自动重组合，所以挂一个 ON_RESUME 计数：
        // 不这么做的话他开完权限回到设置页，这颗还写着"去系统设置打开"（状态是假的）
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        var resumeTick by remember { mutableIntStateOf(0) }
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val supported = remember { com.wotagei.cam.core.WotaStorage.supportsAllFilesAccess() }
        if (!supported) {
            // API 29 没有「所有文件访问」系统页：如实说明能力边界，不渲染一颗点了没反应的按钮
            Text(
                text = stringResource(R.string.set_storage_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = WotaTextDim
            )
        } else {
            val granted = remember(resumeTick) { com.wotagei.cam.core.WotaStorage.hasAllFilesAccess() }
            ChipCell(
                text = stringResource(if (granted) R.string.set_storage_on else R.string.set_storage_open),
                selected = granted,
                onClick = {
                    com.wotagei.cam.core.WotaStorage.openSettings(storageCtx)
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockRefline(prefs: SharedPreferences) {
    var defaultRefLines by remember { mutableStateOf(WotaSettings.defaultRefLines(prefs)) }

    SettingGroup(stringResource(R.string.set_group_refline))
    Card {
        Text(
            text = stringResource(R.string.set_refline_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        // 用 FlowRow 而不是 chunked(2)+weight(1f)：后者会把每颗强行撑到半行宽，短标签就变成"长胶囊配短字"
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RefLineType.ALL.forEach { type ->
                val on = defaultRefLines and type.bit != 0
                ChipCell(
                    text = type.label,
                    selected = on,
                    onClick = {
                        val next = if (on) defaultRefLines and type.bit.inv() else defaultRefLines or type.bit
                        defaultRefLines = next
                        prefs.edit().putInt(WotaSettings.KEY_DEFAULT_REF_LINES, next).apply()
                    }
                )
            }
        }
    }
}

@Composable
private fun BlockLevel(prefs: SharedPreferences) {
    var levelEnabled by remember { mutableStateOf(WotaSettings.levelEnabled(prefs)) }
    var levelBuzz by remember { mutableStateOf(WotaSettings.levelBuzzEnabled(prefs)) }

    SettingGroup(stringResource(R.string.set_group_level))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.set_level_enable),
                style = MaterialTheme.typography.bodyMedium,
                color = WotaText,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = levelEnabled,
                onCheckedChange = {
                    levelEnabled = it
                    prefs.edit().putBoolean(WotaSettings.KEY_LEVEL_ENABLED, it).apply()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accentActive, checkedThumbColor = WotaColor.onAccent)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.set_level_buzz),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WotaText
                )
                Text(
                    text = stringResource(R.string.set_level_buzz_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = WotaTextDim
                )
            }
            Switch(
                checked = levelBuzz,
                onCheckedChange = {
                    levelBuzz = it
                    prefs.edit().putBoolean(WotaSettings.KEY_LEVEL_BUZZ, it).apply()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accentActive, checkedThumbColor = WotaColor.onAccent)
            )
        }
    }
}

@Composable
private fun BlockCompare(prefs: SharedPreferences) {
    // 对比播放编排节拍（r02）：越界脏值读侧已钳，这里只管展示选中档
    var compareTick by remember { mutableStateOf(WotaSettings.compareTickMs(prefs)) }

    SettingGroup(stringResource(R.string.set_group_compare))
    Card {
        // 对比播放编排节拍（键 compare_tick_ms）：两轨同步/交接/回绕的检查间隔。
        // 消费方每拍重读 prefs，改完下一次检查即生效
        TierPicker(
            label = stringResource(R.string.set_compare_tick),
            selected = compareTick,
            items = WotaSettings.COMPARE_TICK_TIERS.map { TierItem(it, "$it") },
            format = { "$it ms" },
            onPick = {
                compareTick = it
                prefs.edit().putInt(WotaSettings.KEY_COMPARE_TICK_MS, it).apply()
            },
            note = stringResource(R.string.set_compare_tick_note)
        )
    }
}

@Composable
private fun BlockText(prefs: SharedPreferences) {
    var scaleCamera by remember { mutableStateOf(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)) }
    var scaleSettings by remember {
        mutableStateOf(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_SETTINGS))
    }
    var scaleDialog by remember { mutableStateOf(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_DIALOG)) }

    SettingGroup(stringResource(R.string.set_group_text))
    Card {
        Text(
            text = stringResource(R.string.set_text_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        TextScalePicker(
            label = stringResource(R.string.set_text_camera),
            scale = scaleCamera,
            onPick = { pct ->
                scaleCamera = pct / 100f
                prefs.edit().putInt(WotaSettings.KEY_TEXT_SCALE_CAMERA, pct).apply()
            }
        )
        TextScalePicker(
            label = stringResource(R.string.set_text_dialog),
            scale = scaleDialog,
            onPick = { pct ->
                scaleDialog = pct / 100f
                prefs.edit().putInt(WotaSettings.KEY_TEXT_SCALE_DIALOG, pct).apply()
            }
        )
        TextScalePicker(
            label = stringResource(R.string.set_text_settings),
            scale = scaleSettings,
            onPick = { pct ->
                scaleSettings = pct / 100f
                prefs.edit().putInt(WotaSettings.KEY_TEXT_SCALE_SETTINGS, pct).apply()
            }
        )
    }
}

@Composable
private fun BlockPerm() {
    val context = LocalContext.current
    var permTick by remember { mutableStateOf(0) }
    val perms = remember(context, permTick) { permissionRows(context) }

    // 从系统设置页返回后重新查一次权限状态
    DisposableEffect(context) {
        // 只有 ComponentActivity 才是 LifecycleOwner（android.app.Activity 没有 lifecycle 属性）
        val activity = context.findActivity() as? ComponentActivity
        val lifecycle = activity?.lifecycle
        val resume = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_RESUME) permTick++
            }
        }
        lifecycle?.addObserver(resume)
        onDispose { lifecycle?.removeObserver(resume) }
    }

    SettingGroup(stringResource(R.string.set_group_perm))
    Card {
        perms.forEachIndexed { index, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    // 子行缩进：只取既有 WotaSpace 令牌（12dp），不新造裸 dp；父行不缩进
                    .then(if (row.indent) Modifier.padding(start = WotaSpace.m) else Modifier)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(row.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WotaText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(if (row.granted) R.string.set_perm_granted else R.string.set_perm_denied),
                    style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    // 状态小字不用 accent（压 surface 4.48 < 4.5）：已授权降为 textMid 安静态，
                    // 未授权保留 WotaRec 红作强调——需要突出的只有「未授权」（子行沿用同一对两色，不新建色）
                    color = if (row.granted) WotaColor.textMid else WotaRec
                )
            }
            // 分隔线规则：下一行是子行时不插（子行紧跟父行，视觉上是一组），子行之后恢复一条——
            // 整卡节奏与加子行之前一致，「打开系统设置」行上方仍恰好一条
            if (perms.getOrNull(index + 1)?.indent != true) Line()
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.set_perm_note),
                style = MaterialTheme.typography.bodyMedium,
                color = WotaColor.textMid,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.set_open_system_settings),
                style = MaterialTheme.typography.labelMedium,
                // 可点动作小字：accent 压 surface 4.48 过不了 AA，改 textHi + 下划线（链接惯例）表达可点
                color = WotaText,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { context.openAppSettings() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun BlockAbout() {
    val context = LocalContext.current
    SettingGroup(stringResource(R.string.set_group_about))
    Card {
        // 版本号单一真源 = app/build.gradle.kts 的 appVersionName（经 BuildConfig.VERSION_NAME
        // 直通）：打新 tag 前改脚本那两行是发版必经步骤，设置页/容器/产物名因此永远一致（2026-10-03）
        InfoRow(stringResource(R.string.set_version), BuildConfig.VERSION_NAME)
        Line()
        // 「检查更新」（纯手动版）：探测/下载只由行内点击触发，不挂任何生命周期观察者
        // （UpdateNetworkGuardTest 钉住：入口必须全部活在 UpdateSection 函数体内）
        UpdateSection()
        Line()
        InfoRow(stringResource(R.string.set_package), context.packageName)
        Line()
        Text(
            text = stringResource(R.string.set_about_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textMid,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

// ------------------------------------------------------------------ 小件

/**
 * 诊断日志区块（2026-10-09 用户指令「把日志功能加上」；2026-10-13 用户裁决「总开关默认关 + 清除」）：
 * - **总开关「记录日志」**：默认关（省空间；关闭期间功能异常不落现场，崩溃件除外——崩溃取证不受开关影响）；
 * - **详细档开关**：总开关开着时才可切，默认只记 i/w/e 关键事件，复现问题时打开（连 d/v 一起记）；
 * - **清除日志**：删净当前件/轮转件/崩溃件并清内存缓冲（日志可再生，不弹确认框）；
 * - **一键导出**：把全部日志（当前件 + 轮转件 + 崩溃件）合并成一份 txt 放到公共
 *   `Documents/WotaGeiCam/`，并立刻拉起系统分享面板，用户想发给谁就发给谁；
 * - 导出走**本地文件 + FileProvider + 系统分享**，全程不联网（网络只允许出现在 update/ 包，
 *   由 `UpdateNetworkGuardTest` 与 `DiagGuardTest` 两条守卫钉住）。
 */
@Composable
private fun BlockDiag(prefs: SharedPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 总开关（默认关）在前：关着时详细档置灰不可切（层级：先能记，才谈记多细）
    var diagOn by remember { mutableStateOf(WotaSettings.diagEnabled(prefs)) }
    var verbose by remember { mutableStateOf(WotaSettings.diagVerbose(prefs)) }
    // 正在跑的动作（null = 空闲）：三颗按钮各自显示忙态，互不串台
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val ACTION_SHARE = "share"
    val ACTION_EXPORT = "export"
    val ACTION_CLEAR = "clear"

    SettingGroup(stringResource(R.string.set_group_diag))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.set_diag_enabled),
                style = MaterialTheme.typography.bodyMedium,
                color = WotaText,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = diagOn,
                onCheckedChange = {
                    diagOn = it
                    WotaSettings.setDiagEnabled(prefs, it)
                    // 运行期立即生效（日志器不用重启应用）
                    com.wotagei.cam.diag.Diag.setEnabled(it)
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = WotaColor.accentActive,
                    checkedThumbColor = WotaColor.onAccent
                )
            )
        }
        Text(
            text = stringResource(R.string.set_diag_enabled_note),
            style = MaterialTheme.typography.bodySmall,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        Line()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.set_diag_verbose),
                style = MaterialTheme.typography.bodyMedium,
                color = if (diagOn) WotaText else WotaColor.textMid,
                modifier = Modifier.weight(1f)
            )
            // 总开关关 ⇒ 详细档置灰不可切（没开着记录，"记多细"无从谈起）
            Switch(
                checked = verbose,
                onCheckedChange = {
                    verbose = it
                    WotaSettings.setDiagVerbose(prefs, it)
                    // 运行期立即生效（日志器不用重启应用）
                    com.wotagei.cam.diag.Diag.setVerbose(it)
                },
                enabled = diagOn,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = WotaColor.accentActive,
                    checkedThumbColor = WotaColor.onAccent
                )
            )
        }
        Text(
            text = stringResource(R.string.set_diag_verbose_note),
            style = MaterialTheme.typography.bodySmall,
            color = WotaColor.textMid
        )
        Spacer(Modifier.height(6.dp))
        Line()
        InfoRow(stringResource(R.string.set_diag_size), formatBytes(Diag.usedBytes()))
        Line()
        InfoRow(stringResource(R.string.set_diag_path), Diag.workingDir()?.absolutePath ?: "—")
        Spacer(Modifier.height(8.dp))
        // 三颗按钮各干一件事（用户 2026-10-09 追加："不用去文件管理器找文件，直接分享"；
        // 2026-10-13 用户裁决：补「清除日志」）：
        //   分享 = 一步直达系统分享面板（落点在应用缓存，**不受公共目录可写性影响**，点了必弹面板）；
        //   导出 = 只把文件落到公共 Documents 并显示位置（给"我想留着/自己找"的那条路）；
        //   清除 = 删净当前件/轮转件/崩溃件并清内存缓冲（日志可再生，**不弹确认框**）。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipCell(
                text = stringResource(if (busy == ACTION_SHARE) R.string.set_diag_share_busy else R.string.set_diag_share),
                selected = true,
                onClick = {
                    if (busy != null) return@ChipCell
                    busy = ACTION_SHARE
                    status = null
                    scope.launch {
                        // 读多个文件 + 写盘：必须切 IO（不许占主线程）
                        val res = withContext(Dispatchers.IO) {
                            (context.applicationContext as? Application)?.let { Diag.exportForShare(it) }
                        }
                        busy = null
                        if (res == null) {
                            status = context.getString(R.string.set_diag_share_failed)
                        } else {
                            status = context.getString(R.string.set_diag_share_done, res.file.name)
                            shareLogFile(context, res.file)
                        }
                    }
                }
            )
            ChipCell(
                text = stringResource(if (busy == ACTION_EXPORT) R.string.set_diag_export_busy else R.string.set_diag_export),
                selected = false,
                onClick = {
                    if (busy != null) return@ChipCell
                    busy = ACTION_EXPORT
                    status = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            (context.applicationContext as? Application)?.let { Diag.export(it) }
                        }
                        busy = null
                        status = if (res == null) {
                            context.getString(R.string.set_diag_export_failed)
                        } else {
                            context.getString(
                                R.string.set_diag_export_done,
                                res.file.name,
                                res.file.parent ?: ""
                            )
                        }
                    }
                }
            )
            // 清除（2026-10-13 用户裁决）：删净文件 + 清内存缓冲；清完 status 变更触发重组，
            // 上方「日志占用」InfoRow 在重组时重读 Diag.usedBytes()，数字随之归零
            ChipCell(
                text = stringResource(R.string.set_diag_clear),
                selected = false,
                onClick = {
                    if (busy != null) return@ChipCell
                    busy = ACTION_CLEAR
                    status = null
                    scope.launch {
                        withContext(Dispatchers.IO) { Diag.clear() }
                        busy = null
                        status = context.getString(R.string.set_diag_cleared)
                    }
                }
            )
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = WotaColor.accentActive,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            text = stringResource(R.string.set_diag_note),
            style = MaterialTheme.typography.bodySmall,
            color = WotaColor.textMid,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** 字节数 → 人类可读（只用于设置页显示，不做本地化差异） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 分享导出的日志：`FileProvider` content Uri + `ACTION_SEND`（与 `media/Share.kt`、
 * `update/UpdateInstaller.kt` 同一枚 provider，`file_paths` 的 `cache/share-out` 已覆盖导出目录）。
 * 走本地文件、不联网；分享目标由用户选。
 */
private fun shareLogFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "com.wotagei.cam.provider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Diag.w("WotaSettings", "分享日志失败：${it.message}") }
}

/** 一屏的「文本高度」档位：100% 即工程默认排版，各屏互不影响 */
@Composable
private fun TextScalePicker(label: String, scale: Float, onPick: (Int) -> Unit) {
    val pct = (scale * 100f).roundToInt()
    TierPicker(
        label = label,
        selected = pct,
        items = WotaSettings.TEXT_SCALE_PCTS.map { TierItem(it, "$it%", true) },
        format = { "$it%" },
        onPick = onPick
    )
}

@Composable
private fun SettingGroup(text: String) {
    Text(
        text = text,
        // 分组标题是标题不是正文，按 docs/plan/11 §2 用 title 字阶；卡内正文仍走 MaterialTheme.typography
        //
        // WCAG 大字档收口（2026-10-02）：17sp SemiBold **不构成** WCAG large text——bold 线要
        // ≥14pt（18.66px）且字重到 bold（惯例 ≥700，SemiBold 600 两头都不达标；即便宽算它 bold，
        // 17px 仍在线下 1.66px）→ 之前按 3.0 门槛放行缺依据。升 19sp Bold 进真 large 档
        // （≥18.66px bold 线；也顺带贴 HDS 标题栏 19vp，design-spec §3.4「title 17 偏小，贴 HDS 升 19–20」），
        // 行高按同一比例 22→24sp。accent 保留：accent×bg 4.86、accent×surface 4.48，均高于
        // 真 large 档的 3.0 门槛（scripts/contrast-audit.py ACCENT_PAIRS accent×bg / accent×surface）。
        // 不动 Tokens.kt 的 WotaType.title（令牌值本轮不许动；本文件已有 MonoStyle.copy 的局部派生先例）。
        style = WotaType.title.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
        color = WotaColor.accent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/** 分组卡：设置页不压在画面上，用近实底 surface 而不是 25% 透明的 hudScrim */
@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSettingsCardPad.current)
            // 接件位档：设置卡属「大卡」类（design-spec §4.2 card=24dp），不用旧的浮层级 radiusLarge
            .clip(RoundedCornerShape(WotaShape.card))
            .background(WotaColor.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        content = content
    )
}

/**
 * 多选 chip 格：借 [WotaChip] 的配色语义（选中 accentSurface 实底 + onAccent 字、未选中 wotaCard 胶囊），
 * 但不直接用它——内部写死 WotaType.chip 会让「设置页文本高度」失效。
 *
 * 宽度**按内容自适应**，另给一个最短长度下限：3 个汉字。以前调用方用 `weight(1f)` 把每颗强行撑到
 * 半行宽，于是「网格」这种两字标签配上一条长胶囊，左右全是空底（2026-09-28 用户指出）。
 *
 * 高度对齐 HDS Chip 的 28vp（component-map §三「多选格 ChipCell → ChipGroup.Multiple，高 28vp」、
 * design-spec §5.2 小件档 `ohos_id_piece_height`；Top 8 #3 点名「设置多选」同属 Chip 族）：
 * 垂直内边距 8→4dp。实账（`ui/theme/Type.kt`）：bodyMedium = 13sp / **18sp 行高** ⇒ 自然高
 * 18+2×4=**26dp，到不了 28**；差的那 2dp 是 `heightIn(min=)` 从下限托上去的，不是行高刚好凑出来的。
 * **不写死 28**——与 [WotaChipImpl] 同一条纪律用 `heightIn(min=)` 兜底：「设置页文本高度」放大到
 * 120% 时自然高 ≈29.6dp 才越过下限跟着行高长高，缩到 80% 时只剩 ≈22dp 仍由下限托住。
 * 真机实测改前 36dp（72px@2x）正是这 16dp 垂直内边距垫出来的，改后 56px。
 *
 * 两个坑：① 下限要加在 **Text** 上而不是加在带 padding 的 Box 上——加在 Box 上会被左右 24dp 内边距
 * 吃掉，实测两字标签仍是 106px，压根没被抬到三字的 135px；② 别用 `TextUnit.toDp()` 换算，那个转换
 * 会除以 density（39sp 只得到 19.5dp），下限小到等于没设。这里按 `字号 × 3 × fontScale` 直接算 dp，
 * 并且跟着「设置页文本高度」的字号走，字号变大最短宽度也变大。
 */
@Composable
private fun ChipCell(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val motion = LocalMotion.current
    val threeCharsMin = (style.fontSize.value * 3f * density.fontScale).dp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 选中底色与文字色**即时切换**（#81 第 1 条定版：只动画 alpha 与 scale，禁动画颜色）；
    // 按压缩放保留（scale 在允许档）。两枚值同帧一起换，不存在"底还在渐变、字先跳"的分叉
    // 选中底走 accentSurface：这里是 bodyMedium 白字小字的承载面，白字对 #007DFF(accent)
    // 只有 3.91:1 不过 AA 正文，对 #0A59F7 5.55:1 过（见 WotaColor.accentSurface 注）
    val fill = if (selected) WotaColor.accentSurface else Color.Transparent
    val labelColor = if (selected) WotaColor.onAccent else WotaColor.textHi
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .wotaCard(WotaShape.pill)
            .background(fill)
            .clip(WotaShape.pill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            // 高 28dp = HDS 小件档（ohos_id_piece_height，见上注）：bodyMedium 的自然高只有 26dp
            // （18sp 行高 + 2×4dp 内边距），28 是下面这道 min 托上去的，**不是**行高自己凑出来的；
            // heightIn(min=) 兜住「文本高度」缩到 80% 时自然高只剩 ≈22dp 的情形（与 WotaChipImpl 同一手法）
            .heightIn(min = 28.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier.widthIn(min = threeCharsMin),
            style = style,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(WotaColor.outline))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = WotaText, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
            color = WotaTextDim
        )
    }
}

/**
 * 权限行：`permTick` 参与重算，从系统设置返回后状态会刷新。
 *
 * [indent] = 子行标记（缩进量取既有 [WotaSpace] 令牌，见 [BlockPerm]）：子行的父行之后**不**插分隔线，
 * 子行自己之后才恢复一条，整卡的分隔线节奏与加子行之前一致。
 */
private data class PermRow(
    @StringRes val labelRes: Int,
    val granted: Boolean,
    val indent: Boolean = false
)

/**
 * 蓝牙位置子行的**唯一阈值**（纯函数，JVM 可测，见 `BtPermRowGateTest`）。
 *
 * 与 manifest 里 `ACCESS_FINE_LOCATION` 的 `android:maxSdkVersion="30"` 同档：API 31 起设备发现改走
 * `BLUETOOTH_SCAN + neverForLocation`，平台不再给 discovery 结果加位置闸门，那一档就不该出现这行。
 * 阈值只写在这一处，消费方（[permissionRows]）只认这个函数，不各自抄 `<31`/`≤30`。
 */
internal fun btLocationRowVisible(sdkInt: Int): Boolean = sdkInt < 31

/**
 * 设置页「权限状态」的行列表。
 *
 * 蓝牙行下方那枚位置子行的判据**逐字**是 `BtSpeakerController.missingPermissions()` 的 `<S` 分支
 * （同枚 `ACCESS_FINE_LOCATION`）——`7666e95` 给 `startDiscovery` 在 API 29/30 补了位置权限之后，
 * **设置页此前从未呈现这项权限**：用户在这里看到「蓝牙连接已就绪」，而系统实际声明的权限面里
 * 还多着一枚没被算进来的位置权限，两处口径不一致——这才是补这行的动机。
 * （**首启不会弹位置权限框**：`MainActivity.missingStartupPermissions()` 只申请相机/麦克风/媒体读取
 * 三项，位置权限的 `RequestMultiplePermissions` 只在蓝牙面板里由用户点按钮触发。2026-10-11 口径订正，
 * 此前本段误写成「首启又弹位置框」。）
 * **蓝牙主行的 [hasBluetoothPermission] 分毫未动**（它与 `BtSpeakerController.hasConnectPermission()`
 * 同一条规则，见下方 KDoc）。
 */
private fun permissionRows(context: Context): List<PermRow> = buildList {
    add(PermRow(R.string.set_perm_camera, granted(context, Manifest.permission.CAMERA)))
    add(PermRow(R.string.set_perm_audio, granted(context, Manifest.permission.RECORD_AUDIO)))
    // Android 14+ 的部分授权（READ_MEDIA_VISUAL_USER_SELECTED）也算已授予：用户明确选过「选择照片和视频」
    val mediaGranted = granted(context, WotaSettings.mediaReadPermission()) ||
        (android.os.Build.VERSION.SDK_INT >= 34 &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    add(PermRow(R.string.set_perm_media, mediaGranted))
    add(PermRow(R.string.set_perm_bluetooth, hasBluetoothPermission(context)))
    // 版本闸（源码守卫的字面锚）：只有 API ≤ 30 才把位置子行放进列表
    if (btLocationRowVisible(android.os.Build.VERSION.SDK_INT)) {
        add(
            PermRow(
                R.string.set_perm_bt_location,
                granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
                indent = true
            )
        )
    }
}

/**
 * 蓝牙权限行的显示判据，必须与 `BtSpeakerController.hasConnectPermission()` 同一条规则。
 *
 * `BLUETOOTH_CONNECT` 是 API 31 才有的：31 以下 `checkSelfPermission` 对这个字符串永远返回 DENIED，
 * 照直读会让设置页的蓝牙行在 API 29/30 上**永远显示未授权**，而实际那两档走的是 manifest 里
 * `maxSdkVersion="30"` 的传统 `BLUETOOTH`（安装即授予，无需运行时申请）。
 */
private fun hasBluetoothPermission(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
        granted(context, Manifest.permission.BLUETOOTH_CONNECT)

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------ 检查更新（纯手动）

/** 「检查更新」行的行内状态：Idle → Checking →（Cancelling）→ Latest/Failed（有新版转对话框流，行回 Idle） */
private enum class UpdateRowState { Idle, Checking, Cancelling, Latest, Failed }

/** 下载对话框状态（进度由 IO 线程的 onProgress 回调写入；完成/失败后换动作行） */
private data class UpdateDownloadState(
    val bytesRead: Long = 0,
    val totalBytes: Long = -1L,
    val done: Boolean = false,
    val failed: Boolean = false,
    val installFailed: Boolean = false,
    val file: java.io.File? = null,
    /** 正在给各渠道测速选路（还没开始真正下载，进度数字尚无意义） */
    val measuring: Boolean = false
)

/**
 * 「检查更新」区块（关于卡内、版本行下方）。
 *
 * **纯手动纪律**：联网探测与下载只由下面这颗行的点击触发，本函数体内不许出现任何
 * 生命周期观察者 / 重组自动触发（LaunchedEffect、DisposableEffect、LifecycleObserver…）——
 * 这是用户裁决的功能语义，UpdateNetworkGuardTest 钉住（入口必须全部活在 [UpdateSection] 体内）。
 */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rowState by remember { mutableStateOf(UpdateRowState.Idle) }
    var available by remember { mutableStateOf<ProbeResult.UpdateAvailable?>(null) }
    var download by remember { mutableStateOf<UpdateDownloadState?>(null) }
    // 探测取消探针（检查中点行 = 取消）。下载取消标志**按代隔离**（修复轮 P2）：每轮下载在
    // onDownload 里新建 AtomicBoolean 捕获进协程闭包，取消钮只操作该代标志（downloadGenFlag），
    // 行点击（检查）永不触碰下载标志——共享标志被新一轮复位会让旧下载协程复活/迟到回执弹框
    val probeCancelled = remember { AtomicBoolean(false) }
    var downloadGenFlag by remember { mutableStateOf<AtomicBoolean?>(null) }

    val statusRes = when (rowState) {
        UpdateRowState.Idle -> R.string.set_update_idle
        UpdateRowState.Checking -> R.string.set_update_checking
        UpdateRowState.Cancelling -> R.string.set_update_cancelling
        UpdateRowState.Latest -> R.string.set_update_latest
        UpdateRowState.Failed -> R.string.set_update_failed
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            // 修复轮 P2-2：检查中点行 = 取消（置位取消探针）；Cancelling 期间行不可点，
            // 等编排把剩余节点跳完回来（节点内最坏一个探测超时周期），防旧探测的回执污染新一轮
            .clickable(enabled = download == null && rowState != UpdateRowState.Cancelling) {
                if (rowState == UpdateRowState.Checking) {
                    probeCancelled.set(true)
                    rowState = UpdateRowState.Cancelling
                } else {
                    probeCancelled.set(false)
                    rowState = UpdateRowState.Checking
                    scope.launch(Dispatchers.IO) {
                        when (
                            val r = probeLatestRelease(
                                BuildConfig.VERSION_NAME,
                                // 探测专用短读超时（P2-2）：最坏 5 节点 × (10s 连接 + 10s 读) ≈ 100s，不再挂数分钟
                                { url -> UpdateDownloader.fetchJson(url, UpdateDownloader.PROBE_READ_TIMEOUT_MS) },
                                { url -> UpdateDownloader.fetchRedirectLocation(url, UpdateDownloader.PROBE_READ_TIMEOUT_MS) },
                                isCancelled = { probeCancelled.get() }
                            )
                        ) {
                            is ProbeResult.UpdateAvailable -> {
                                // 修复轮 P3-2：取消落在最后一个在飞探测请求窗口内 → 丢弃回执，
                                // 与下载回执同一纪律（取消后不许凭空弹「发现新版本」）
                                if (!probeCancelled.get()) available = r
                                rowState = UpdateRowState.Idle
                            }
                            // 修复轮 P3-2：UpToDate/Failed 同一把尺——取消后迟到的回执不写
                            // 「已是最新/检查失败」，统一回 Idle
                            ProbeResult.UpToDate ->
                                rowState = if (probeCancelled.get()) UpdateRowState.Idle else UpdateRowState.Latest
                            ProbeResult.Cancelled -> rowState = UpdateRowState.Idle
                            ProbeResult.Failed ->
                                rowState = if (probeCancelled.get()) UpdateRowState.Idle else UpdateRowState.Failed
                        }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.set_update),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaText,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(statusRes),
            style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
            // 失败用 WotaRec 红强调（与权限「未授权」同语义）；其余小字灰安静态
            color = if (rowState == UpdateRowState.Failed) WotaRec else WotaTextDim
        )
    }

    available?.let { avail ->
        UpdatePromptDialog(
            tag = avail.latestTag,
            sizeText = apkSizeText(avail.sizeBytes),
            notes = avail.notes,
            onDownload = {
                available = null
                // 修复轮 P2：取消标志按代隔离——每轮下载新建 AtomicBoolean 捕获进协程闭包，
                // 迟到的旧代回执以本代标志判生死，跨代复位从此不可能
                val genCancelled = AtomicBoolean(false)
                downloadGenFlag = genCancelled
                download = UpdateDownloadState(measuring = true)
                scope.launch(Dispatchers.IO) {
                    val outcome = UpdateDownloader.downloadApk(
                        context = context,
                        directUrl = avail.downloadUrl,
                        expectedSize = avail.sizeBytes,
                        onProgress = { read, total ->
                            // 修复轮 P3-1（末轮）：进度只许「本代仍是当前可见代」时写——
                            // 旧代迟到的进度不许碰新一代的进度对话框
                            if (downloadGenFlag === genCancelled) {
                                download = download?.copy(bytesRead = read, totalBytes = total)
                            }
                        },
                        isCancelled = { genCancelled.get() },
                        expectedSha256 = avail.sha256,
                        // 测速选路结束（开始真正下载）：同一条按代隔离纪律
                        onSpeedTested = {
                            if (downloadGenFlag === genCancelled) {
                                download = download?.copy(measuring = false)
                            }
                        }
                    )
                    // 修复轮 P3-1（末轮）：回执写回前判代——旧代（已被新一代顶替）一律整体丢弃，
                    // 不许无条件清 download（那会把新一代的对话框清掉、令其隐形下载且不可取消）
                    if (downloadGenFlag !== genCancelled) return@launch
                    when (outcome) {
                        is UpdateDownloader.DownloadOutcome.Done ->
                            // 本代已取消 → 回执丢弃（取消钮已关对话框，不许凭空弹回）
                            if (!genCancelled.get()) {
                                download = UpdateDownloadState(
                                    bytesRead = outcome.sizeBytes,
                                    totalBytes = outcome.sizeBytes,
                                    done = true,
                                    file = outcome.file
                                )
                            }
                        UpdateDownloader.DownloadOutcome.Cancelled -> download = null
                        UpdateDownloader.DownloadOutcome.Failed ->
                            // 同上：已取消的失败回执丢弃（对话框已关，别翻出来）
                            if (!genCancelled.get()) download = UpdateDownloadState(failed = true)
                    }
                }
            },
            onDismiss = { available = null }
        )
    }

    download?.let { dl ->
        UpdateDownloadDialog(
            state = dl,
            onCancel = {
                // 只操作本代下载标志（修复轮 P2：按代隔离，行点击/新一轮探测永不触碰它）
                downloadGenFlag?.set(true)
                download = null
            },
            onClose = { download = null },
            onInstall = {
                val file = dl.file
                if (file != null && UpdateInstaller.install(context, file)) {
                    download = null
                    rowState = UpdateRowState.Idle
                } else {
                    download = dl.copy(installFailed = true)
                }
            }
        )
    }
}

/**
 * 提示更新对话框：新版本号 + APK 大小 + 版本说明 + 下载/取消
 * （系统安装确认属安装器职责，这里只管到「安装」）。
 * [notes] 是 Release 说明原文（仅官方源探测才有，镜像段恒 null）；整形交给 [ReleaseNotes]，
 * 原文为空时整块说明区不出现。
 */
@Composable
private fun UpdatePromptDialog(
    tag: String,
    sizeText: String,
    notes: String?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints {
            // 说明区限高：最多占窗口高度 40%（上限 260dp）——横屏小窗 + 上百行说明也不把按钮顶出屏幕
            val notesMaxHeight = minOf(maxHeight * 0.4f, 260.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WotaShape.dialog))
                    .background(WotaColor.surface)
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.set_update_available_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = WotaText
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.set_update_available_body, tag, sizeText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WotaColor.textMid
                )
                val notesText = remember(notes) { ReleaseNotes.forDisplay(notes) }
                if (notesText != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.set_update_notes_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = WotaTextDim
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = notesText,
                        style = MaterialTheme.typography.bodySmall,
                        color = WotaColor.textMid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = notesMaxHeight)
                            .verticalScroll(rememberScrollState())
                    )
                }
                Spacer(Modifier.height(18.dp))
                UpdateDialogActions(
                    listOf(
                        stringResource(R.string.set_update_cancel) to onDismiss,
                        stringResource(R.string.set_update_download) to onDownload
                    )
                )
            }
        }
    }
}

/** 下载进度对话框：n% + 已下/总 MB + 取消；完成换「安装」，失败换「关闭」 */
@Composable
private fun UpdateDownloadDialog(
    state: UpdateDownloadState,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onInstall: () -> Unit
) {
    // 修复轮 P3-1：下载中外部点击/返回键不再触发取消（误触会丢掉整个下载），
    // onDismissRequest 置空，取消只走显式「取消」钮；完成/失败态保留外部关闭
    Dialog(onDismissRequest = { if (state.done || state.failed || state.installFailed) onClose() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WotaShape.dialog))
                .background(WotaColor.surface)
                .padding(20.dp)
        ) {
            val title = when {
                state.measuring -> stringResource(R.string.set_update_selecting_channel)
                state.done -> stringResource(R.string.set_update_ready_to_install)
                state.failed -> stringResource(R.string.set_update_download_failed)
                state.installFailed -> stringResource(R.string.set_update_install_failed)
                else -> stringResource(R.string.set_update_downloading)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (state.failed || state.installFailed) WotaRec else WotaText
            )
            Spacer(Modifier.height(10.dp))
            when {
                state.measuring -> {
                    // 测速选路：各渠道并发采样（每路至多 256KiB / 共约 1~6s），还没有可报的进度
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = WotaColor.textMid,
                        trackColor = WotaColor.surface
                    )
                    Spacer(Modifier.height(12.dp))
                    UpdateDialogActions(
                        listOf(stringResource(R.string.set_update_cancel) to onCancel)
                    )
                }
                state.done -> {
                    UpdateDialogActions(
                        listOf(stringResource(R.string.set_update_install) to onInstall)
                    )
                }
                state.failed || state.installFailed -> {
                    UpdateDialogActions(
                        listOf(stringResource(R.string.set_update_close) to onClose)
                    )
                }
                else -> {
                    val total = state.totalBytes
                    val pct = if (total > 0) {
                        ((state.bytesRead * 100) / total).toInt().coerceIn(0, 100)
                    } else {
                        null
                    }
                    Text(
                        text = if (pct != null) {
                            stringResource(
                                R.string.set_update_progress,
                                pct, downloadedMb(state.bytesRead), downloadedMb(total)
                            )
                        } else {
                            stringResource(R.string.set_update_progress_unknown, downloadedMb(state.bytesRead))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = WotaColor.textMid
                    )
                    Spacer(Modifier.height(8.dp))
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = (state.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    UpdateDialogActions(
                        listOf(stringResource(R.string.set_update_cancel) to onCancel)
                    )
                }
            }
        }
    }
}

/** 对话框动作行（右对齐；末位主操作加粗。色按设置页惯例：surface 上 accent 不过 AA，用 textHi/textMid） */
@Composable
private fun UpdateDialogActions(actions: List<Pair<String, () -> Unit>>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEachIndexed { i, (label, action) ->
            if (i > 0) Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (i == actions.lastIndex) WotaText else WotaColor.textMid,
                fontWeight = if (i == actions.lastIndex) FontWeight.Bold else null,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = action)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** 字节 → "12.3MB"（进度与提示共用一套口径） */
private fun downloadedMb(bytes: Long): String = String.format(Locale.US, "%.1fMB", bytes / 1024f / 1024f)

/** 探测给的 APK 大小 → 展示文案（段 3 探测拿不到 size，下载时才有 Content-Length） */
@Composable
private fun apkSizeText(bytes: Long?): String =
    if (bytes != null && bytes > 0) downloadedMb(bytes) else stringResource(R.string.set_update_size_unknown)
