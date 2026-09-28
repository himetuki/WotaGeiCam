package com.wotagei.cam.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.wotagei.cam.R
import com.wotagei.cam.core.CurveStack
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.RefLineType
import com.wotagei.cam.core.RenderMode
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
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaBg
import com.wotagei.cam.ui.theme.WotaRec
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.widget.TierItem
import com.wotagei.cam.ui.widget.TierPicker
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
    const val KEY_TEXT_SCALE_CAMERA = "text_scale_camera"
    const val KEY_TEXT_SCALE_SETTINGS = "text_scale_settings"
    const val KEY_TEXT_SCALE_DIALOG = "text_scale_dialog"
    const val KEY_GALLERY_COLUMNS = "gallery_columns"

    /** 手调曲线的恢复值：曲线是用户一点点拖出来的，重开进程不该丢 */
    const val KEY_CURVE_STACK = "curve_stack"

    /** 网格列数允许的档位：小屏 2 列看得清，5 列用来快速翻找 */
    val GALLERY_COLUMN_TIERS = listOf(2, 3, 4, 5)

    /** 文本高度缩放的可选区间（%），100 = 工程默认排版 */
    val TEXT_SCALE_PCTS = listOf(80, 90, 100, 110, 120)

    /** 关于页版本号，与 app/build.gradle.kts 的 versionName 同步 */
    const val APP_VERSION = "0.0.1"

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

    /** 各屏文本高度缩放系数；100% 即工程默认排版，越界值钳回区间内 */
    fun textScale(prefs: SharedPreferences, key: String): Float =
        (prefs.getInt(key, 100).coerceIn(TEXT_SCALE_PCTS.first(), TEXT_SCALE_PCTS.last()) / 100f)

    /** 网格列数：旧值不在档位里（例如手写进 prefs 的 6）一律回落到 2 */
    fun galleryColumns(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_GALLERY_COLUMNS, 2).let { if (it in GALLERY_COLUMN_TIERS) it else 2 }

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
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
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
    var hudMask by remember { mutableStateOf(WotaSettings.hudItems(prefs)) }
    var pillMask by remember { mutableIntStateOf(WotaSettings.hudPills(prefs)) }
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
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.set_back), tint = WotaText)
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
        Text(
            text = stringResource(R.string.set_default_note),
            style = MaterialTheme.typography.bodyMedium,
            color = WotaTextDim,
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
                    colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accent, checkedThumbColor = WotaColor.layer)
                )
            }
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
                color = WotaTextDim
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
                color = WotaTextDim
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
        }

        SettingGroup(stringResource(R.string.set_group_storage))
        Card {
            Text(
                text = stringResource(R.string.set_storage_note),
                style = MaterialTheme.typography.bodyMedium,
                color = WotaTextDim
            )
            Spacer(Modifier.height(6.dp))
            val storageCtx = androidx.compose.ui.platform.LocalContext.current
            // 每次组合都现读：他从系统设置页回来时这个组合会重跑，状态才不会停在旧的
            val granted = com.wotagei.cam.core.WotaStorage.hasAllFilesAccess()
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
                color = WotaTextDim
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
                    colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accent, checkedThumbColor = WotaColor.layer)
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
                    colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accent, checkedThumbColor = WotaColor.layer)
                )
            }
        }

        SettingGroup(stringResource(R.string.set_group_text))
        Card {
            Text(
                text = stringResource(R.string.set_text_note),
                style = MaterialTheme.typography.bodyMedium,
                color = WotaTextDim
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
                        color = if (row.granted) WotaAccent else WotaRec
                    )
                }
                Line()
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.set_perm_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WotaTextDim,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.set_open_system_settings),
                    style = MaterialTheme.typography.labelMedium,
                    color = WotaAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { context.openAppSettings() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        SettingGroup(stringResource(R.string.set_group_about))
        Card {
            InfoRow(stringResource(R.string.set_version), WotaSettings.APP_VERSION)
            Line()
            InfoRow(stringResource(R.string.set_package), context.packageName)
            Line()
            Text(
                text = stringResource(R.string.set_about_note),
                style = MaterialTheme.typography.bodyMedium,
                color = WotaTextDim,
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
        style = WotaType.title,
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
            .clip(WotaShape.large)
            .background(WotaColor.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        content = content
    )
}

/**
 * 多选 chip 格：借 [WotaChip] 的配色语义（选中 accent 实底 + onAccent 字、未选中 wotaCard 胶囊），
 * 但不直接用它——内部写死 WotaType.chip 会让「设置页文本高度」失效。
 *
 * 宽度**按内容自适应**，另给一个最短长度下限：3 个汉字。以前调用方用 `weight(1f)` 把每颗强行撑到
 * 半行宽，于是「网格」这种两字标签配上一条长胶囊，左右全是空底（2026-09-28 用户指出）。
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
    // 选中底色与按压缩放都走动效档：原来点一下是硬切成 accent 实底，读起来像页面卡了一下
    // 颜色动画吃不到 motion.float（那是 Float 的 spec），改用同一档的时长，保证与缩放/位移同源
    val colorSpec = tween<Color>(durationMillis = motion.durationMs)
    val fill by animateColorAsState(
        if (selected) WotaColor.accent else Color.Transparent,
        colorSpec
    )
    val labelColor by animateColorAsState(
        if (selected) WotaColor.onAccent else WotaColor.textHi,
        colorSpec
    )
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
