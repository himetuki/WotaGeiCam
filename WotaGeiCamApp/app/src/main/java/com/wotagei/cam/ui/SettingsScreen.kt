package com.wotagei.cam.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.wotagei.cam.BuildConfig
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.wotagei.cam.R
import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.ArcConvertMode
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.RefLineType
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.UIOrientation
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.dialog.bitrateText
import com.wotagei.cam.ui.dialog.sampleRateText
import com.wotagei.cam.ui.dialog.shutterText
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaType
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaRec
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.widget.TierItem
import com.wotagei.cam.ui.widget.TierPicker
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
    const val KEY_DEFAULT_REF_LINES = "default_ref_lines"
    const val KEY_LEVEL_ENABLED = "level_enabled"
    const val KEY_LEVEL_BUZZ = "level_buzz_enabled"
    const val KEY_HUD_ITEMS = "hud_items"
    /** 录制页控件胶囊的显隐（#54）：与读数分开一套位掩码，默认全开 */
    const val KEY_HUD_PILLS = "hud_pills"
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
     * 强制 24/25fps 在无原生精确档设备上的录制期转换模式（2026-10-03 定版）：
     * `MEND`（默认，抽帧+取大补弧）/ `DROP`（仅抽帧）。只在帧率弹层选中 24/25 且设备无
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

    fun defaultAudioMuted(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DEFAULT_AUDIO_MUTE, false)

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

    fun hudPills(prefs: SharedPreferences) = prefs.getInt(KEY_HUD_PILLS, CamPill.DEFAULT_MASK)

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
     */
    fun applyDefaults(prefs: SharedPreferences, params: WotaParams) {
        val fps = defaultFps(prefs).coerceIn(WotaTiers.FPS.first(), WotaTiers.FPS.last())
        params.fps.value = params.fps.value.copy(value = fps)
        params.shutter.value = params.shutter.value.copy(value = defaultShutterNs(prefs).toLong())
        params.bitrate.value = defaultBitrate(prefs)
        params.sampleRate.value = defaultSampleRate(prefs)
        params.renderMode.value = defaultRenderMode(prefs)
        params.audioEnabled.value = !defaultAudioMuted(prefs)
        params.refLines.value = defaultRefLines(prefs)
        params.levelEnabled.value = levelEnabled(prefs)
        params.curve.value = curveStack(prefs)
    }

    /** 媒体库读取权限：33+ 走细分权限，以下回退旧存储权限 */
    fun mediaReadPermission(): String =
        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHudEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember(context) { WotaSettings.of(context) }
    var motionMode by remember { mutableStateOf(WotaSettings.motionMode(prefs)) }
    var defaultFps by remember { mutableStateOf(WotaSettings.defaultFps(prefs)) }
    var defaultShutter by remember { mutableStateOf(WotaSettings.defaultShutterNs(prefs)) }
    var defaultBitrate by remember { mutableStateOf(WotaSettings.defaultBitrate(prefs)) }
    var defaultSampleRate by remember { mutableStateOf(WotaSettings.defaultSampleRate(prefs)) }
    var defaultRender by remember { mutableStateOf(WotaSettings.defaultRenderMode(prefs)) }
    var defaultMuted by remember { mutableStateOf(WotaSettings.defaultAudioMuted(prefs)) }
    var defaultRefLines by remember { mutableStateOf(WotaSettings.defaultRefLines(prefs)) }
    var levelEnabled by remember { mutableStateOf(WotaSettings.levelEnabled(prefs)) }
    var levelBuzz by remember { mutableStateOf(WotaSettings.levelBuzzEnabled(prefs)) }
    // 对比播放编排节拍（r02）：越界脏值读侧已钳，这里只管展示选中档
    var compareTick by remember { mutableStateOf(WotaSettings.compareTickMs(prefs)) }
    var hudMask by remember { mutableStateOf(WotaSettings.hudItems(prefs)) }
    var pillMask by remember { mutableIntStateOf(WotaSettings.hudPills(prefs)) }
    // 毛玻璃背板（#84 步骤 2）：默认关，只有这一颗 Switch 会把它打开（没有别的写入方）
    var frostBlur by remember { mutableStateOf(WotaSettings.frostBlurEnabled(prefs)) }
    var uiOrientation by remember { mutableStateOf(WotaSettings.uiOrientation(prefs)) }
    var scaleCamera by remember { mutableStateOf(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_CAMERA)) }
    var scaleSettings by remember {
        mutableStateOf(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_SETTINGS))
    }
    var scaleDialog by remember { mutableStateOf(WotaSettings.textScale(prefs, WotaSettings.KEY_TEXT_SCALE_DIALOG)) }
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

    Column(
        modifier
            .fillMaxSize()
            .background(WotaBg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
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
            val fpsItems = WotaTiers.FPS.map { TierItem(it, "$it") }
            TierPicker(
                label = stringResource(R.string.set_default_fps),
                selected = defaultFps,
                items = fpsItems,
                format = { "$it" },
                onPick = { defaultFps = it; prefs.edit().putInt(WotaSettings.KEY_DEFAULT_FPS, it).apply() }
            )
            TierPicker(
                label = stringResource(R.string.set_default_shutter),
                selected = defaultShutter,
                items = WotaTiers.SHUTTER_DENOM.map {
                    TierItem((WotaTiers.NS_PER_SECOND / it).toInt(), "1/$it")
                },
                format = { shutterText(it.toLong()) },
                onPick = {
                    defaultShutter = it
                    prefs.edit().putInt(WotaSettings.KEY_DEFAULT_SHUTTER_NS, it).apply()
                }
            )
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
                            prefs.edit().putInt(WotaSettings.KEY_HUD_PILLS, next).apply()
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
            val granted = remember(resumeTick) { com.wotagei.cam.core.WotaStorage.hasAllFilesAccess() }
            ChipCell(
                text = stringResource(if (granted) R.string.set_storage_on else R.string.set_storage_open),
                selected = granted,
                onClick = {
                    com.wotagei.cam.core.WotaStorage.openSettings(storageCtx)
                }
            )
        }

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

        SettingGroup(stringResource(R.string.set_group_perm))
        Card {
            perms.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                        // 未授权保留 WotaRec 红作强调——需要突出的只有「未授权」
                        color = if (row.granted) WotaColor.textMid else WotaRec
                    )
                }
                Line()
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

        SettingGroup(stringResource(R.string.set_group_about))
        Card {
            // 版本号单一真源 = app/build.gradle.kts 的 appVersionName（经 BuildConfig.VERSION_NAME
            // 直通）：打新 tag 前改脚本那两行是发版必经步骤，设置页/容器/产物名因此永远一致（2026-10-03）
            InfoRow(stringResource(R.string.set_version), BuildConfig.VERSION_NAME)
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
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ 小件

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
            .padding(horizontal = 12.dp)
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

/** 权限行：`permTick` 参与重算，从系统设置返回后状态会刷新 */
private data class PermRow(@StringRes val labelRes: Int, val granted: Boolean)

private fun permissionRows(context: Context): List<PermRow> = listOf(
    PermRow(R.string.set_perm_camera, granted(context, Manifest.permission.CAMERA)),
    PermRow(R.string.set_perm_audio, granted(context, Manifest.permission.RECORD_AUDIO)),
    PermRow(R.string.set_perm_media, granted(context, WotaSettings.mediaReadPermission())),
    PermRow(R.string.set_perm_bluetooth, hasBluetoothPermission(context))
)

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
