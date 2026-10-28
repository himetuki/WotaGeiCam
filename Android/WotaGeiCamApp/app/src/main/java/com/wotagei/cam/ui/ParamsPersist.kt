package com.wotagei.cam.ui

import android.content.SharedPreferences
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.DisplayScale
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.GlowParams
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.MarkerLineColor
import com.wotagei.cam.core.MarkerLines
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.decodeGlow
import com.wotagei.cam.core.encodeGlow

/**
 * 录制页参数持久化（用户反馈第 1 条；2026-10-16 用户拍板口径）：
 * 「只要在录制页改的设置都要做持久化，不随后台清理而回归默认」。
 *
 * 语义 = **键即启动档位**（上次用过就是下次启动值）——与设置页默认档共用同一套键，
 * 不新造"默认 vs 上次"两套概念（拍板 Q1：共用）。[WotaSettings] 里那 19 个新键与
 * 5 个存量键（`default_fps`/`default_shutter_ns`/`default_bitrate`/`default_ref_lines`/
 * `level_enabled`）、2026-10-26 的降噪两项、2026-10-27 的站位线两项、Glow 一份与上屏档共同构成
 * 入档白名单 [KEYS]（30 项）。
 *
 * 两条链：
 * - **冷启恢复**：[restore] 读键 → 坏值回默认 → 写总线（`copy(value=)`，不碰 range/enabled/exact，
 *   机型域钳制交给既有 [WotaParams.applyAbility]）；由 [WotaSettings.applyDefaults] 末尾调用。
 * - **落盘**：[ParamsPersistSession] 在进录制页时冻结一份"影子"（当前总线快照），只在用户动作
 *   完成点（关弹窗/长按锁 AE/退后台/滑杆松手）调 [ParamsPersistSession.flush]，
 *   且**只写与影子不同的字段**——用户在设置页刚改的值不会被录制页的陈旧总线静默覆盖。
 *
 * **排除清单**（防后人"补齐"，语义论述见 [WotaSettings] 键表 KDoc）：
 * - `renderMode`/`sampleRate`：设置页独占——录制页那两处 renderMode 写全是「※ 档自动切 GPU」的
 *   自动降级，入档会把降级静默写成用户默认；
 * - `audioEnabled`（音源）：**不持久化**（拍板 R3）——录制页音源双开关（内录/环境音）任何路径
 *   都不写 prefs、不跨进程恢复（否则开过内录的用户每次启动都要重新申请投屏授权）；
 * - `focusPoint`：瞬时点按态，冷启不应钉死对焦框；
 * - `curve`/`arc_convert_mode`：已有各自专属的直写链，避免第二写入方；
 * - `markerCount`（站位线线数）：**刻意不入档**（2026-10-27 用户裁决）——"启动时取景器能
 *   干净无线"，线数每次从 0 起步；间距与颜色照常入档（[WotaSettings.KEY_DEFAULT_MARKER_SPREAD]
 *   / [WotaSettings.KEY_DEFAULT_MARKER_COLOR]）。
 */
object ParamsPersist {

    /** 入档字段的键白名单（`ParamsPersistWiringGuardTest` 钉住"多一少一都红"）。 */
    val KEYS: List<String> = listOf(
        WotaSettings.KEY_DEFAULT_FPS,
        WotaSettings.KEY_DEFAULT_SHUTTER_NS,
        WotaSettings.KEY_DEFAULT_BITRATE,
        WotaSettings.KEY_DEFAULT_REF_LINES,
        WotaSettings.KEY_LEVEL_ENABLED,
        WotaSettings.KEY_DEFAULT_ISO,
        WotaSettings.KEY_DEFAULT_AE_MODE,
        WotaSettings.KEY_DEFAULT_EV,
        WotaSettings.KEY_DEFAULT_WB_PRESET,
        WotaSettings.KEY_DEFAULT_WB_KELVIN,
        WotaSettings.KEY_DEFAULT_WB_TINT,
        WotaSettings.KEY_DEFAULT_ZOOM,
        WotaSettings.KEY_DEFAULT_FLASH,
        WotaSettings.KEY_DEFAULT_STABILIZE,
        WotaSettings.KEY_DEFAULT_AF_MODE,
        WotaSettings.KEY_DEFAULT_MF_DIOPTER,
        WotaSettings.KEY_DEFAULT_SIZE,
        WotaSettings.KEY_DEFAULT_FRAME_EFFECT,
        WotaSettings.KEY_DEFAULT_ZEBRA_THRESHOLD,
        WotaSettings.KEY_DEFAULT_ZEBRA_DENSITY,
        WotaSettings.KEY_DEFAULT_PEAKING_STRENGTH,
        WotaSettings.KEY_DEFAULT_PEAKING_COLOR,
        WotaSettings.KEY_DEFAULT_LENS,
        WotaSettings.KEY_DEFAULT_ARC_MERGE_DEADBAND,
        WotaSettings.KEY_DENOISE_AUDIO,
        WotaSettings.KEY_DENOISE_VIDEO,
        // 2026-10-27：站位线的间距与颜色入档；**线数刻意不入档**（用户口径：启动时取景器
        // 要干净无线 ⇒ 线数恒从 0 起步，见 restore 末尾那条注释与 KeyTable 守卫）
        WotaSettings.KEY_DEFAULT_MARKER_SPREAD,
        WotaSettings.KEY_DEFAULT_MARKER_COLOR,
        // 2026-10-27：Glow 整份参数入档（复合串键，见 WotaSettings.KEY_GLOW 的注释）
        WotaSettings.KEY_GLOW,
        // 2026-10-28：色调四滑条入档（复合串键 KEY_TONE；**不**并进 KEY_CURVE_STACK——
        // 那会改曲线持久格式，老用户串会解坏，29 §4.3）
        WotaSettings.KEY_TONE,
        // 2026-10-27：上屏渲染比例（枚举名；只改窗口 pass，不重建会话）
        WotaSettings.KEY_DISPLAY_SCALE
    )

    /**
     * 冷启恢复：读键 → 坏值回默认/跳过 → 写总线。
     * 与 [WotaSettings.applyDefaults] 同风格（`ParamState.copy(value=)`）；绝不抛——
     * 手改 prefs 的脏数据最多让它落到出厂默认或产品域边界。
     */
    fun restore(prefs: SharedPreferences, params: WotaParams) {
        // ① 帧率：产品档位区间钳（与 applyDefaults 同一口径）。走 setFps 单一写入者（28 §4.4）：
        //    冷启恢复时能力快照还没建（applyAbility 未跑），行为与直写等价；留单一入口是为了
        //    「所有 fps 写入点都过 setFps」这条守卫不被这处漏成例外
        params.setFps(
            params.fps.value.copy(
                value = prefs.getInt(WotaSettings.KEY_DEFAULT_FPS, 25)
                    .coerceIn(WotaTiers.FPS.first(), WotaTiers.FPS.last())
            )
        )
        // ② 快门（ns）：>0 才套；坏值跳过 = 维持 applyDefaults 刚无条件套上的出厂默认
        //    （本函数自身不做钳制；最终设备域由后续 applyAbility 收口）
        val shutterNs = prefs.getInt(WotaSettings.KEY_DEFAULT_SHUTTER_NS, 40_000_000).toLong()
        if (shutterNs > 0) params.shutter.value = params.shutter.value.copy(value = shutterNs)
        // ③ 码率（档位域由档表管，坏值原样交给 UI/引擎；与 applyDefaults 一致不做钳）
        params.bitrate.value = prefs.getInt(WotaSettings.KEY_DEFAULT_BITRATE, 10_000_000)
        // ④ 参考线掩码（多选位掩码，无越界概念）
        params.refLines.value = prefs.getInt(WotaSettings.KEY_DEFAULT_REF_LINES, 0)
        // ⑤ 水平仪开关
        params.levelEnabled.value = prefs.getBoolean(WotaSettings.KEY_LEVEL_ENABLED, true)
        // ⑥ ISO：>0 才套
        val iso = prefs.getInt(WotaSettings.KEY_DEFAULT_ISO, 100)
        if (iso > 0) params.iso.value = params.iso.value.copy(value = iso)
        // ⑦ AE 模式：AUTO/MANUAL；LOCK 与坏串一律归 AUTO（Q5：锁是瞬时态，不跨进程）
        params.aeMode.value = persistedAe(
            enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_AE_MODE, AeMode.AUTO)
        )
        // ⑧ EV（设备域由 applyEv 钳制）
        params.ev.value = params.ev.value.copy(value = prefs.getInt(WotaSettings.KEY_DEFAULT_EV, 0))
        // ⑨ 白平衡三件套（awbModes 闸门由 applyWhiteBalance 管；色温/色调是产品滑杆域）
        params.wbMode.value = enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_WB_PRESET, WbPreset.AUTO)
        // 白平衡落地路径要可归因（2026-10-28）：恢复值 + 实际落地（HAL/画面算法）各留一行，
        // 用户报"手动白平衡没反应/发绿"时不必再靠猜
        com.wotagei.cam.diag.Diag.i(
            "WotaWb",
            "冷启恢复 wb: raw=${prefs.getString(WotaSettings.KEY_DEFAULT_WB_PRESET, null)}" +
                " -> ${params.wbMode.value.name} kelvin=${prefs.getInt(WotaSettings.KEY_DEFAULT_WB_KELVIN, 5500)}"
        )
        params.kelvin.value = params.kelvin.value.copy(value = prefs.getInt(WotaSettings.KEY_DEFAULT_WB_KELVIN, 5500))
        params.tint.value = params.tint.value.copy(value = prefs.getInt(WotaSettings.KEY_DEFAULT_WB_TINT, 0))
        // ⑩ 变焦：>0 才套
        val zoom = prefs.getFloat(WotaSettings.KEY_DEFAULT_ZOOM, 1f)
        if (zoom > 0f) params.zoom.value = params.zoom.value.copy(value = zoom)
        // ⑪ 闪光/防抖（能力闸门在 applyModeGates）
        params.flash.value = enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_FLASH, Flash.OFF)
        params.stabilize.value = enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_STABILIZE, Stabilize.OFF)
        // ⑫ AF 模式与屈光度（能否保留 MANUAL 由改口径后的 applyFocus 按能力裁决）
        params.afMode.value = enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_AF_MODE, AfMode.CONTINUOUS_VIDEO)
        val diopter = prefs.getFloat(WotaSettings.KEY_DEFAULT_MF_DIOPTER, 0f)
        params.manualFocusDiopter.value =
            params.manualFocusDiopter.value.copy(value = if (diopter < 0f) 0f else diopter)
        // ⑬ 分辨率：存的是**意图值**（requestedSize）；坏串跳过（保持 1920x1080）。
        //    生效尺寸由 applySize 按新镜头能力重落档，这里不写死第二份逻辑
        parseSize(prefs.getString(WotaSettings.KEY_DEFAULT_SIZE, null))?.let { params.requestSize(it) }
        // ⑭ 监看一族（产品域 sanity，不随机型）
        params.frameEffect.value = enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_FRAME_EFFECT, FrameEffect.NONE)
        params.zebraThreshold.value =
            prefs.getFloat(WotaSettings.KEY_DEFAULT_ZEBRA_THRESHOLD, 0.8f).coerceIn(0.6f, 1f)
        params.zebraDensity.value =
            prefs.getInt(WotaSettings.KEY_DEFAULT_ZEBRA_DENSITY, 2).coerceIn(1, 3)
        params.peakingStrength.value =
            prefs.getFloat(WotaSettings.KEY_DEFAULT_PEAKING_STRENGTH, 0.6f).coerceIn(0f, 1f)
        params.peakingColor.value = enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_PEAKING_COLOR, PeakingColor.RED)
        // ⑮ 镜头：按 LensType 名落档；找不到该档时 CameraController.pickSlot 已兜底退 WIDE
        params.lens.value = enumOrDefault(prefs, WotaSettings.KEY_DEFAULT_LENS, LensType.WIDE)
        // ⑯ 补弧差分死区：走总线 setter（同步发布给 GL/离线修复的只读快照），坏值/越界钳回 0..32
        params.setArcMergeDeadband(
            prefs.getInt(WotaSettings.KEY_DEFAULT_ARC_MERGE_DEADBAND, ArcMergeDeadband.DEFAULT)
        )
        // ⑰ 降噪两项（2026-10-26）：坏串/缺键一律回 OFF（默认关是产品口径，见 core/Denoise）
        params.audioDenoise.value = WotaSettings.audioDenoise(prefs)
        params.videoDenoise.value = WotaSettings.videoDenoise(prefs)
        // ⑱ 站位线（2026-10-27）：**只恢复间距与颜色，线数不恢复**——用户口径「启动时取景器
        //    能干净无线」，线数恒停在出厂 0（= 关），由用户当场用 +/− 调；间距越界钳回产品域，
        //    颜色坏串回黄（与 MarkerLineColor.fromOrdinal 兜底同一取向）
        params.markerSpread.value =
            MarkerLines.clampSpread(prefs.getFloat(WotaSettings.KEY_DEFAULT_MARKER_SPREAD, MarkerLines.DEFAULT_SPREAD))
        params.markerColor.value = enumOrDefault(
            prefs, WotaSettings.KEY_DEFAULT_MARKER_COLOR, MarkerLineColor.YELLOW
        )
        // ⑳ Glow：整份参数一个复合串键；坏串整份回出厂（decodeGlow 内部已 sanitize）
        params.glowParams.value = decodeGlow(prefs.getString(WotaSettings.KEY_GLOW, null))
        // ㉑ 上屏档：枚举名；坏串回原片。**不在这里推可用性**——窗口尺寸要到出帧才知道，
        //    "这一档本机可不可用"由 UI 的灰显 + 引擎的回落信号负责（读数与实际一致的口径见 24 §2.3）
        params.displayScale.value = enumOrDefault(
            prefs, WotaSettings.KEY_DISPLAY_SCALE, DisplayScale.ORIG
        )
        // ㉒ 色调（29 §4.3，2026-10-28）：复合串一个键；坏串整份回恒等（decode 内部已 sanitize）
        params.tone.value = com.wotagei.cam.core.ToneParams.decode(prefs.getString(WotaSettings.KEY_TONE, null))
    }

    /** LOCK 归一（Q5）：写 `default_ae_mode` 时 LOCK 一律存/恢复为 AUTO。 */
    internal fun persistedAe(mode: AeMode): AeMode = if (mode == AeMode.LOCK) AeMode.AUTO else mode

    /** 枚举串解析：坏串回默认（先例 `WotaSettings.motionMode`），绝不抛。 */
    private inline fun <reified T : Enum<T>> enumOrDefault(
        prefs: SharedPreferences,
        key: String,
        def: T
    ): T = prefs.getString(key, def.name)?.let { raw ->
        runCatching { enumValueOf<T>(raw) }.getOrNull()
    } ?: def

    /** 尺寸串 `"WxH"`：两段都 >0 才认，坏串返回 null（调用方跳过、保持出厂默认） */
    private fun parseSize(raw: String?): Size? {
        val parts = raw?.split('x') ?: return null
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return Size(w, h)
    }

    /** 尺寸串编解码（flush 侧调用）；与 [parseSize] 一一对应 */
    internal fun encodeSize(size: Size): String = "${size.width}x${size.height}"
}

/**
 * 持久化会话（W3 方案：会话影子 + 用户动作完成点 flush）。
 *
 * - 构造时冻结**影子** = 当前总线值（每次进入录制页组合重建；离开即销毁）；
 * - [flush] 只把"与影子不同"的字段一次 `edit()` 批量 `commit()`（低频、同步落盘——
 *   `apply()` 会被 `am force-stop` 吃掉，同 [WotaSettings.setHudLayout] 的裁决）；
 * - `flush()` 无差异时不触碰 prefs（不产生 commit、不触发任何 prefs 监听回调），返回 true；
 *   有差异时返回 `commit()` 的回执（false = 没落成盘，影子保持旧值等下次重试）。
 *
 * 影子 diff 同时封死"静默覆盖设置页"缺陷：用户在设置页改了某个键、而录制页总线还停在旧值
 * （`applyDefaultsOnce` 进程内只套一次），此后任何 flush 都不会把那个陈旧值写回。
 */
class ParamsPersistSession(private val prefs: SharedPreferences, private val params: WotaParams) {

    // ---- 影子（构造时冻结；flush 成功后才更新）----
    private var shFps = params.fps.value.value
    private var shShutter = params.shutter.value.value
    private var shIso = params.iso.value.value
    private var shAe = ParamsPersist.persistedAe(params.aeMode.value)
    private var shEv = params.ev.value.value
    private var shWb = params.wbMode.value
    private var shKelvin = params.kelvin.value.value
    private var shTint = params.tint.value.value
    private var shZoom = params.zoom.value.value
    private var shFlash = params.flash.value
    private var shStabilize = params.stabilize.value
    private var shAf = params.afMode.value
    private var shDiopter = params.manualFocusDiopter.value.value
    private var shSize = params.requestedSize
    private var shEffect = params.frameEffect.value
    private var shZebraT = params.zebraThreshold.value
    private var shZebraD = params.zebraDensity.value
    private var shPeakS = params.peakingStrength.value
    private var shPeakC = params.peakingColor.value
    private var shLens = params.lens.value
    private var shArcDeadband = params.arcMergeDeadband.value
    private var shBitrate = params.bitrate.value
    private var shDenoiseAudio = params.audioDenoise.value
    private var shDenoiseVideo = params.videoDenoise.value
    private var shMarkerSpread = params.markerSpread.value
    private var shMarkerColor = params.markerColor.value
    private var shGlow = params.glowParams.value
    private var shTone = params.tone.value
    private var shDisplayScale = params.displayScale.value
    private var shRefLines = params.refLines.value
    private var shLevel = params.levelEnabled.value

    fun flush(): Boolean {
        // 先读一遍当前总线（一次 flush 内 diff 与影子同步都吃这一份，避免中途再变）
        val fps = params.fps.value.value
        val shutter = params.shutter.value.value
        val iso = params.iso.value.value
        val ae = ParamsPersist.persistedAe(params.aeMode.value)
        val ev = params.ev.value.value
        val wb = params.wbMode.value
        val kelvin = params.kelvin.value.value
        val tint = params.tint.value.value
        val zoom = params.zoom.value.value
        val flash = params.flash.value
        val stabilize = params.stabilize.value
        val af = params.afMode.value
        val diopter = params.manualFocusDiopter.value.value
        val size = params.requestedSize
        val effect = params.frameEffect.value
        val zebraT = params.zebraThreshold.value
        val zebraD = params.zebraDensity.value
        val peakS = params.peakingStrength.value
        val peakC = params.peakingColor.value
        val lens = params.lens.value
        val arcDeadband = params.arcMergeDeadband.value
        val bitrate = params.bitrate.value
        val refLines = params.refLines.value
        val level = params.levelEnabled.value
        val denoiseAudio = params.audioDenoise.value
        val denoiseVideo = params.videoDenoise.value
        // 站位线只落间距与颜色：线数不进白名单（用户口径"启动时取景器干净无线"），
        // 所以这里**故意不读 params.markerCount**
        val markerSpread = params.markerSpread.value
        val markerColor = params.markerColor.value
        val glow = params.glowParams.value
        val tone = params.tone.value
        val displayScale = params.displayScale.value

        val editor = prefs.edit()
        var dirty = false
        if (fps != shFps) { editor.putInt(WotaSettings.KEY_DEFAULT_FPS, fps); dirty = true }
        if (shutter != shShutter) { editor.putInt(WotaSettings.KEY_DEFAULT_SHUTTER_NS, shutter.toInt()); dirty = true }
        if (bitrate != shBitrate) { editor.putInt(WotaSettings.KEY_DEFAULT_BITRATE, bitrate); dirty = true }
        if (denoiseAudio != shDenoiseAudio) { editor.putString(WotaSettings.KEY_DENOISE_AUDIO, denoiseAudio.key); dirty = true }
        if (denoiseVideo != shDenoiseVideo) { editor.putString(WotaSettings.KEY_DENOISE_VIDEO, denoiseVideo.key); dirty = true }
        if (markerSpread != shMarkerSpread) { editor.putFloat(WotaSettings.KEY_DEFAULT_MARKER_SPREAD, markerSpread); dirty = true }
        if (markerColor != shMarkerColor) { editor.putString(WotaSettings.KEY_DEFAULT_MARKER_COLOR, markerColor.name); dirty = true }
        if (glow != shGlow) { editor.putString(WotaSettings.KEY_GLOW, encodeGlow(glow)); dirty = true }
        if (tone != shTone) { editor.putString(WotaSettings.KEY_TONE, com.wotagei.cam.core.ToneParams.encode(tone)); dirty = true }
        if (displayScale != shDisplayScale) {
            editor.putString(WotaSettings.KEY_DISPLAY_SCALE, displayScale.name); dirty = true
        }
        if (refLines != shRefLines) { editor.putInt(WotaSettings.KEY_DEFAULT_REF_LINES, refLines); dirty = true }
        if (level != shLevel) { editor.putBoolean(WotaSettings.KEY_LEVEL_ENABLED, level); dirty = true }
        if (iso != shIso) { editor.putInt(WotaSettings.KEY_DEFAULT_ISO, iso); dirty = true }
        if (ae != shAe) { editor.putString(WotaSettings.KEY_DEFAULT_AE_MODE, ae.name); dirty = true }
        if (ev != shEv) { editor.putInt(WotaSettings.KEY_DEFAULT_EV, ev); dirty = true }
        if (wb != shWb) { editor.putString(WotaSettings.KEY_DEFAULT_WB_PRESET, wb.name); dirty = true }
        if (kelvin != shKelvin) { editor.putInt(WotaSettings.KEY_DEFAULT_WB_KELVIN, kelvin); dirty = true }
        if (tint != shTint) { editor.putInt(WotaSettings.KEY_DEFAULT_WB_TINT, tint); dirty = true }
        if (zoom != shZoom) { editor.putFloat(WotaSettings.KEY_DEFAULT_ZOOM, zoom); dirty = true }
        if (flash != shFlash) { editor.putString(WotaSettings.KEY_DEFAULT_FLASH, flash.name); dirty = true }
        if (stabilize != shStabilize) { editor.putString(WotaSettings.KEY_DEFAULT_STABILIZE, stabilize.name); dirty = true }
        if (af != shAf) { editor.putString(WotaSettings.KEY_DEFAULT_AF_MODE, af.name); dirty = true }
        if (diopter != shDiopter) { editor.putFloat(WotaSettings.KEY_DEFAULT_MF_DIOPTER, diopter); dirty = true }
        if (size != shSize) { editor.putString(WotaSettings.KEY_DEFAULT_SIZE, ParamsPersist.encodeSize(size)); dirty = true }
        if (effect != shEffect) { editor.putString(WotaSettings.KEY_DEFAULT_FRAME_EFFECT, effect.name); dirty = true }
        if (zebraT != shZebraT) { editor.putFloat(WotaSettings.KEY_DEFAULT_ZEBRA_THRESHOLD, zebraT); dirty = true }
        if (zebraD != shZebraD) { editor.putInt(WotaSettings.KEY_DEFAULT_ZEBRA_DENSITY, zebraD); dirty = true }
        if (peakS != shPeakS) { editor.putFloat(WotaSettings.KEY_DEFAULT_PEAKING_STRENGTH, peakS); dirty = true }
        if (peakC != shPeakC) { editor.putString(WotaSettings.KEY_DEFAULT_PEAKING_COLOR, peakC.name); dirty = true }
        if (lens != shLens) { editor.putString(WotaSettings.KEY_DEFAULT_LENS, lens.name); dirty = true }
        if (arcDeadband != shArcDeadband) {
            editor.putInt(WotaSettings.KEY_DEFAULT_ARC_MERGE_DEADBAND, arcDeadband); dirty = true
        }

        if (!dirty) return true
        val ok = editor.commit()
        if (ok) {
            // 写盘成功才把新值记入影子；失败则保持旧影子，下次 flush 重试
            shFps = fps
            shShutter = shutter
            shIso = iso
            shAe = ae
            shEv = ev
            shWb = wb
            shKelvin = kelvin
            shTint = tint
            shZoom = zoom
            shFlash = flash
            shStabilize = stabilize
            shAf = af
            shDiopter = diopter
            shSize = size
            shEffect = effect
            shZebraT = zebraT
            shZebraD = zebraD
            shPeakS = peakS
            shPeakC = peakC
            shLens = lens
            shArcDeadband = arcDeadband
            shBitrate = bitrate
            shDenoiseAudio = denoiseAudio
            shDenoiseVideo = denoiseVideo
            shMarkerSpread = markerSpread
            shMarkerColor = markerColor
            shGlow = glow
            shTone = tone
            shDisplayScale = displayScale
            shRefLines = refLines
            shLevel = level
        }
        return ok
    }
}
