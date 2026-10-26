package com.wotagei.cam.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.core.ArcConvertMode
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.LensSlot
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.RefLineType
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.buildVideoSizeTable
import com.wotagei.cam.core.screenAspectOf
import com.wotagei.cam.core.enumerateLenses
import com.wotagei.cam.core.hFovOf
import com.wotagei.cam.core.pickFpsRange
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.sizeCloseTo
import com.wotagei.cam.record.StorageEstimate
import com.wotagei.cam.ui.design.PillChoices
import com.wotagei.cam.ui.design.PillOption
import com.wotagei.cam.ui.design.PillRow
import com.wotagei.cam.ui.design.PillRowList
import com.wotagei.cam.ui.design.PillSlider
import com.wotagei.cam.ui.design.PillToggles
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaPillPopup
import com.wotagei.cam.ui.design.WotaType
import com.wotagei.cam.ui.widget.SliderKey
import com.wotagei.cam.ui.widget.deadbandLadder
import com.wotagei.cam.ui.widget.diopterLadder
import com.wotagei.cam.ui.widget.evLadder
import com.wotagei.cam.ui.widget.isoLadder
import com.wotagei.cam.ui.widget.kelvinLadder
import com.wotagei.cam.ui.widget.percentLadder
import com.wotagei.cam.ui.widget.shutterLadder
import com.wotagei.cam.ui.widget.tintLadder
import com.wotagei.cam.ui.widget.zoomLadder
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.dialog.afLabelRes
import com.wotagei.cam.ui.dialog.aeLabelRes
import com.wotagei.cam.ui.dialog.bitrateText
import com.wotagei.cam.ui.dialog.effectLabelRes
import com.wotagei.cam.ui.dialog.evText
import com.wotagei.cam.ui.dialog.flashLabelRes
import com.wotagei.cam.ui.dialog.lensLabelRes
import com.wotagei.cam.ui.dialog.observed
import com.wotagei.cam.ui.dialog.peakingColorLabelRes
import com.wotagei.cam.ui.dialog.shutterItems
import com.wotagei.cam.ui.dialog.shutterText
import com.wotagei.cam.ui.dialog.sizeText
import com.wotagei.cam.ui.dialog.stabLabelRes
import com.wotagei.cam.ui.dialog.wbLabelRes
import com.wotagei.cam.ui.dialog.zebraThresholdOfUi
import com.wotagei.cam.ui.dialog.zebraUiOfThreshold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 录制页就近胶囊的类别（一个 key 只管一件事）。
 *
 * 没扩 [com.wotagei.cam.core.HudItem] 是因为那一位掩码要写进 `hud_items` 持久化键，
 * 加值就等于改存量配置语义；右竖 Dock 的「对焦」本来也不属于可自定义读数。
 * 每个 key 的锚点写入方登记在 [pillAnchorWriters]，新增 key 必须同步那一行。
 */
enum class PillKey { SIZE, FPS, SHUTTER, ISO, EV, WB, ZOOM, FOCUS, BITRATE, LENS, REFLINE, MONITOR, FLASH, STAB, STORAGE, BT }

/**
 * 录制中仍可调参的白名单（A4，2026-10-17 拍板）：其余胶囊录制中可开窗但控件只读。
 * 单点定义——弹窗控件闸与守卫测试都读这一张表，别在别处再写一份。
 */
internal val RECORDING_EDITABLE_PILLS =
    setOf(PillKey.ISO, PillKey.EV, PillKey.WB, PillKey.ZOOM, PillKey.FOCUS)

/** 录制中该弹窗是否可调（非录制态一律可调，含调用方尚未拿到能力时的初始化阶段） */
internal fun pillEditableDuringRecording(key: PillKey): Boolean = key in RECORDING_EDITABLE_PILLS

/**
 * `PillKey` → 锚点写入方 登记表（审查 S2-2：把 §69「浮层甩到屏幕原点」这一族从结构上封死）。
 *
 * 每颗就近浮层都必须锚在**触发它的那颗控件**上。B4 之后这条只有一处落点：
 * [com.wotagei.cam.ui.HudEntryItem] 统一给每颗条目挂 `ctx.anchorOf(entry)`，而 `CameraScreen` 造
 * 那份 ctx 时把 [pillAnchorReport] 贴上去，在布局期把窗口矩形回报进 `CameraScreen` 的 `pillAnchors`；
 * [PillHost] 取到的矩形为空时只能按 `IntRect.Zero` 弹到左上角。B1 之前的「蓝牙」就是这一族的原型：
 * 全工程只有读取方（这里）与触发点（`onBtClick`），没有写入方，面板于是永久钉在左上角而入口在右缘
 * ——这类洞 lint 不报、编译不报，只有真机点得到。
 *
 * **新增 PillKey 的同轮义务**（与 AGENTS「新增控件的同轮义务」同一条）：
 * ① 下面这张表加一行；② [HudEntry.pillKey] 补一条条目 → key 的映射（[com.wotagei.cam.ui.HudEntryItem]
 * 只认这张表，漏了就等于那颗控件永远不报锚点）；③ [PillHost] 的 when 补内容宿主（穷尽性已强制）。
 * 忘了 ① 或 ② 都由 `PillAnchorRegistryTest` 判失败：表必须逐个 key 覆盖 [PillKey.values()]、
 * 写入方不许空串，且每个 key 都必须能在 [com.wotagei.cam.core.CamPill] / [com.wotagei.cam.core.HudItem]
 * 的条目里找到归属。
 *
 * 「编辑控件」页不在这张表里：那页不弹就近浮层，`ctx.anchorOf` 那一路被改写成**条目矩形回报**
 * （落点格位与 ghost 半径用它），页里 ghost 那份显式传空链，避免同一个 key 被 ghost 覆写。
 *
 * 表里 `→ design.WotaChip(...)` 那几行说的是"由哪件控件画"，**档位在 [com.wotagei.cam.ui.chipTierFor]
 * 一处裁决**（#70 B）：只有两枚竖 Dock 里的"控件胶囊"改走 `design.WotaDockChip` 紧凑档，
 * 底栏那颗镜头、顶栏两段、右下读数块一律还是 `design.WotaChip` 全局档——后两处的宽度账分别
 * 是 `DockSlotSpace` 与 `hudPerRowFor` 的输入，收窄会连带改掉 #71 的算式起点。
 */
val pillAnchorWriters: Map<PillKey, String> = mapOf(
    PillKey.SIZE to "HudLayer.HudEntryItem(SIZE) ← CameraScreen.ctx.anchorOf，宿主是可拖动的顶栏胶囊组",
    PillKey.STORAGE to "HudLayer.HudEntryItem(STORAGE) ← CameraScreen.ctx.anchorOf，宿主是顶栏胶囊组容量段",
    PillKey.LENS to "HudLayer.HudEntryItem(LENS) ← CameraScreen.ctx.anchorOf，宿主是底栏 Dock 那颗（六项第 7 条从顶栏搬来）",
    PillKey.ZOOM to "HudLayer.HudEntryItem(ZOOM)：右竖 Dock 那颗为主，HudReadoutZone 里的变焦读数在那颗被关掉或被挪出可见集时顶上（裁决在 CameraScreen 的 anchorOf）",
    PillKey.FOCUS to "HudLayer.HudEntryItem(FOCUS) ← 任意竖 Dock / 顶栏 / 底栏，条目被挪到哪枚就锚在哪枚",
    PillKey.STAB to "HudLayer.HudEntryItem(STAB) ← 同上，位置由 hud_layout 的条目归属决定",
    PillKey.BT to "HudLayer.HudEntryItem(BT) → widget.AudioChip(modifier)，批 2 起这颗是「音频」chip（键名 BT 沿用：原蓝牙位，位掩码按 bit 持久化不许改名）",
    PillKey.REFLINE to "HudLayer.HudEntryItem(REFLINE) → design.WotaIconButton(modifier)",
    PillKey.MONITOR to "HudLayer.HudEntryItem(MONITOR) → design.WotaChip(modifier)",
    PillKey.FLASH to "HudLayer.HudEntryItem(FLASH) → design.WotaIconButton(modifier)",
    PillKey.SHUTTER to "HudLayer.HudEntryItem(HudItem.SHUTTER) → design.WotaChip(modifier)",
    PillKey.ISO to "HudLayer.HudEntryItem(HudItem.ISO) → design.WotaChip(modifier)",
    PillKey.EV to "HudLayer.HudEntryItem(HudItem.EV) → design.WotaChip(modifier)",
    PillKey.WB to "HudLayer.HudEntryItem(HudItem.WB) → design.WotaChip(modifier)",
    PillKey.FPS to "HudLayer.HudEntryItem(HudItem.FPS) → design.WotaChip(modifier)",
    PillKey.BITRATE to "CameraScreen.BOTTOM 带 BITRATE chip（HudZoneBox 之后的 WotaChip + pillAnchorReport）→ 10-01 布局批：码率移出 READOUT 网格、Dock 左侧固定读数"
)

/**
 * 就近胶囊的内容宿主。
 *
 * 档位、范围、能力开关一律现取自 [LensSlot.ability]，并复用参数抽屉那批构造工具
 * （`shutterItems` / `buildVideoSizeTable` / `pickFpsRange`），这样不会出现
 * 「抽屉里能选、胶囊里选不到」的分裂。
 */
@Composable
@Suppress("LongParameterList")
fun PillHost(
    key: PillKey,
    anchor: IntRect,
    params: WotaParams,
    slot: LensSlot?,
    recording: Boolean,
    gpuMode: Boolean,
    onClose: () -> Unit,
    /**
     * 滑杆「松手回执」（R4/用户反馈第 1 条）：拖动中每拍只写总线，松手这一刻让调用方落盘。
     * 无默认值必传（与锚点同一条纪律）：漏挂 = 有滑杆拖完被 force-stop 丢值，编译期就该挡。
     */
    onPersist: () -> Unit,
    onUnsupported: () -> Unit,
    onFocusCenter: () -> Unit,
    /**
     * 对焦锁定态与切换回执（用户反馈第 2 条，副控制入口）。两个都无默认值必传——
     * 与锚点同一条纪律：漏挂 = 胶囊里的「锁定对焦」变假开关，编译期就该挡。
     */
    focusLocked: Boolean,
    onToggleFocusLock: () -> Unit,
    onPickLens: (LensSlot) -> Unit,
    freeMb: Long,
    bt: com.wotagei.cam.bt.BtSpeakerController,
    capture: com.wotagei.cam.record.PlaybackCaptureController,
    onEnableCapture: () -> Unit
) {
    val ability = slot?.ability
    // A4 录制中矩阵：所有弹窗都能开，只有白名单内的可调；其余控件禁用 + 顶部只读提示。
    val editable = !recording || pillEditableDuringRecording(key)
    when (key) {
        PillKey.SIZE -> SizePill(anchor, params, ability, editable, onClose, onUnsupported)
        PillKey.FPS -> FpsPill(anchor, params, ability, editable, onClose, onUnsupported, onPersist)
        PillKey.SHUTTER -> ShutterPill(anchor, params, ability, editable, onClose, onPersist)
        PillKey.ISO -> IsoPill(anchor, params, ability, editable, onClose, onPersist)
        PillKey.EV -> EvPill(anchor, params, ability, editable, onClose, onPersist)
        PillKey.WB -> WbPill(anchor, params, ability, editable, onClose, onUnsupported, onPersist)
        PillKey.ZOOM -> ZoomPill(anchor, params, editable, onClose, onPersist)
        PillKey.FOCUS -> FocusPill(
            anchor = anchor,
            params = params,
            ability = ability,
            editable = editable,
            recording = recording,
            focusLocked = focusLocked,
            onToggleFocusLock = onToggleFocusLock,
            onClose = onClose,
            onUnsupported = onUnsupported,
            onFocusCenter = onFocusCenter,
            onPersist = onPersist
        )
        PillKey.BITRATE -> BitratePill(anchor, params, editable, onClose)
        PillKey.LENS -> LensPill(anchor, slot, editable, onClose, onPickLens)
        PillKey.REFLINE -> RefLinePill(anchor, params, editable, onClose)
        PillKey.MONITOR -> MonitorPill(anchor, params, gpuMode, editable, onClose, onPersist)
        PillKey.FLASH -> FlashPill(anchor, params, ability, editable, onClose, onUnsupported)
        PillKey.STAB -> StabPill(anchor, params, ability, editable, onClose, onUnsupported)
        PillKey.STORAGE -> StoragePill(anchor, params, freeMb, editable, onClose)
        PillKey.BT -> {
            // 音源双开定版（2026-10-07）：环境音开关的真源是 params.audioEnabled（会话内生效，
            // 下次起录按它落音轨），与内录开关（controller 态）互相独立
            val ambientEnabled by params.audioEnabled.observed()
            AudioPill(
                anchor, capture, editable, onEnableCapture, bt, onClose,
                ambientEnabled = ambientEnabled,
                onToggleAmbient = { params.audioEnabled.value = !params.audioEnabled.value }
            )
        }
    }
}

/** AE 模式行：曝光三件套共用，手动档只在真的能手动时才给选；[enabled] 供录制中只读弹窗整行禁用 */
@Composable
private fun AeModeRow(params: WotaParams, ability: CameraAbility?, enabled: Boolean = true) {
    val aeMode by params.aeMode.observed()
    val names = AeMode.values().map { stringResource(aeLabelRes(it)) }
    PillChoices(
        options = AeMode.values().mapIndexed { i, m ->
            PillOption(m.ordinal, names[i], m != AeMode.MANUAL || ability?.manualExposurePossible() == true)
        },
        selected = aeMode.ordinal,
        label = stringResource(R.string.cam_p_ae),
        onPick = { params.aeMode.value = AeMode.values()[it.value] },
        enabled = enabled
    )
}

@Composable
private fun SizePill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onUnsupported: () -> Unit
) {
    val size by params.size.observed()
    val fps by params.fps.observed()
    // 全屏档（10-01 指令）的机型无关推导依据：当前窗口的屏幕比例（运行时读，不写死任何机型）；
    // 比例变了（转屏）要重建表——键里带上这两个 dp 值
    val cfg = LocalConfiguration.current
    val screenAspect = remember(cfg.screenWidthDp, cfg.screenHeightDp) {
        screenAspectOf(cfg.screenWidthDp, cfg.screenHeightDp)
    }
    val table = remember(ability, screenAspect) { ability?.let { buildVideoSizeTable(it, screenAspect = screenAspect) } }
    val options = table?.options.orEmpty()
    val selected = options.indexOfFirst { sizeCloseTo(it.size, size) }
    val bounds = ability?.videoBounds()
    val rangeNote = if (bounds?.first != null && bounds.second != null) {
        stringResource(R.string.cam_size_range, sizeText(bounds.first!!), sizeText(bounds.second!!))
    } else {
        null
    }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_size)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        if (options.isEmpty()) Note(R.string.unsupported_by_device)
        PillChoices(
            options = options.mapIndexed { i, o -> PillOption(i, sizeText(o.size), o.supported) },
            selected = selected,
            columns = 2,
            onPick = { opt ->
                val target = options.getOrNull(opt.value)
                when {
                    target == null || !target.supported -> onUnsupported()
                    else -> {
                        params.requestSize(target.size)
                        onClose()
                    }
                }
            },
            enabled = editable
        )
        // 分辨率与帧率是同一件事的两半（换分辨率后帧率档可能失效），放一个弹窗里免得来回点
        PillChoices(
            options = fpsOptions(ability, size),
            selected = fps.value,
            label = stringResource(R.string.cam_p_fps),
            onPick = { opt ->
                if (!opt.enabled) onUnsupported()
                else {
                    // 换档必须**按目标档重查**精确性（与 FpsPill 同一支口径）：裸 copy(value=)
                    // 会沿用旧档的 exact——"有原生 [25,25] 但无 [24,24]"的机型从 ※24 切到 25
                    // 会把 exact=false 带过去，arcConvertNow() 误把原生 25 当 ※ 档抽帧
                    //（白抽帧 + HUD 假 ※）
                    // 并集判定（2026-10-26）：50/60 在只进高速表的机型上（vivo V2520A）
                    // 也按高速范围给出近似档，不再因普通流无档而落回 disabled
                    val pick = pickFpsRange(ability?.fpsRangesUnion(size) ?: emptyList(), opt.value)
                    params.fps.value = pick
                        ?.let { ParamState(opt.value, it.lo..it.hi, enabled = true, exact = it.exact) }
                        ?: fps.copy(value = opt.value, enabled = false, exact = false)
                }
            },
            enabled = editable
        )
        rangeNote?.let { NoteText(it) }
    }
}

@Composable
private fun FpsPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onUnsupported: () -> Unit,
    onPersist: () -> Unit
) {
    val app = LocalContext.current
    val settingsPrefs = remember(app) { WotaSettings.of(app) }
    val size by params.size.observed()
    val fps by params.fps.observed()
    val deadband by params.arcMergeDeadband.observed()
    // 强制 24/25fps 在无原生精确档（※ 档）设备上的录制期转换模式（2026-10-03 定版）：
    // MEND=抽帧+取大补弧（弧连续）/ DROP=仅抽帧（弧在抽帧点断，用户自选）。有精确档时不显示。
    val convertActive = fps.value in WotaTiers.REQUIRED_FPS && !fps.exact
    var convertMode by remember(settingsPrefs) {
        mutableStateOf(WotaSettings.arcConvertMode(settingsPrefs))
    }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_fps)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        PillChoices(
            options = fpsOptions(ability, size),
            selected = fps.value,
            onPick = { opt ->
                if (!opt.enabled) {
                    onUnsupported()
                } else {
                    // 换档必须**按目标档重查**精确性：copy(value=) 会沿用旧档的 exact——
                    // "有原生 [25,25] 但无 [24,24]"的机型从 ※24 切到 25 会把 exact=false 带过去，
                    // 转换误激活（白抽帧）+ 假 ※。fpsOptions 的求交口径在这里复用同一支。
                    // 并集判定（2026-10-26）：50/60 在只进高速表的机型上（vivo V2520A）
                    // 也按高速范围给出近似档，不再因普通流无档而落回 disabled
                    val pick = pickFpsRange(ability?.fpsRangesUnion(size) ?: emptyList(), opt.value)
                    params.fps.value = pick
                        ?.let { ParamState(opt.value, it.lo..it.hi, enabled = true, exact = it.exact) }
                        ?: fps.copy(value = opt.value, enabled = false, exact = false)
                    // 选定 ※ 档（24/25 无精确档）即预告转换：DIRECT 渲染没有 GL 编码支路、
                    // 无法拒帧，当场切 GPU（会话重建立即可见），录制按下时的守卫只作后备
                    if (opt.value in WotaTiers.REQUIRED_FPS && params.fps.value.exact == false &&
                        params.renderMode.value == RenderMode.DIRECT
                    ) {
                        params.renderMode.value = RenderMode.GPU
                    }
                    onClose()
                }
            },
            enabled = editable
        )
        if (convertActive) {
            Text(
                stringResource(R.string.cam_fps_convert_title),
                style = MaterialTheme.typography.labelSmall,
                color = WotaTextDim,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArcConvertMode.entries.forEach { mode ->
                    WotaChip(
                        label = stringResource(
                            if (mode == ArcConvertMode.MEND) R.string.cam_fps_convert_mend
                            else R.string.cam_fps_convert_drop
                        ),
                        selected = convertMode == mode,
                        // 录制中只读（与档位选择同一把总闸）
                        enabled = editable,
                        onClick = {
                            convertMode = mode
                            WotaSettings.setArcConvertMode(settingsPrefs, mode)
                        },
                        // fill = false：与 PillChoices 同一条紧凑化裁决，不把弹窗撑到最大宽
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Text(
                stringResource(
                    if (convertMode == ArcConvertMode.MEND) R.string.cam_fps_convert_mend_note
                    else R.string.cam_fps_convert_drop_note
                ),
                style = MaterialTheme.typography.labelSmall,
                color = WotaTextDim,
                // 与 NoteText 同一条封宽账：说明句在 220dp 内折行，不顶宽弹窗
                modifier = Modifier.padding(top = 4.dp).widthIn(max = 220.dp)
            )
            // 并入门槛（补弧差分死区 D，0..32，默认 12）：只在抽帧补弧真正生效时有意义，所以挂这一栏下面。
            // 写入必须走 params.setArcMergeDeadband——直接改 `.value` 不同步发布 ArcMergeDeadband 快照，
            // 引擎/离线修复读到的还是旧值（本条的守卫见 ArcMergeDeadbandUiWiringTest）。
            PillSlider(
                value = deadband.toFloat(),
                lo = ArcMergeDeadband.MIN.toFloat(),
                hi = ArcMergeDeadband.MAX.toFloat(),
                step = 1f,
                // 录制中与档位/转换模式同一把总闸（FPS 不在录制可调白名单里）
                enabled = editable,
                label = stringResource(R.string.cam_fps_deadband),
                onValueChange = { params.setArcMergeDeadband(it.roundToInt()) },
                format = { "${it.roundToInt()}" },
                onValueChangeFinished = onPersist,
                // 整数域（0..32 逐格），没有可关的吸附
                snapKey = null,
                ladder = deadbandLadder()
            )
            NoteText(stringResource(R.string.cam_fps_deadband_note))
        }
    }
}

/** 帧率档：与抽屉同一套求交逻辑，`※` 表示本机只能给近似值 */
@Composable
private fun fpsOptions(ability: CameraAbility?, size: Size): List<PillOption> {
    // 普通∪高速并集（2026-10-26）：厂商把 50/60 只放进高速表时（vivo V2520A 普通流仅
    // [24,24]/[30,30]），按当前会话模态判定会让 50/60/120/240 永远灰显（用户报障）
    val ranges = ability?.fpsRangesUnion(size) ?: emptyList()
    val approx = stringResource(R.string.approx_mark)
    return WotaTiers.FPS.map { tier ->
        val pick = pickFpsRange(ranges, tier)
        PillOption(tier, "$tier" + if (pick?.exact == false) approx else "", pick != null)
    }
}

@Composable
private fun ShutterPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onPersist: () -> Unit
) {
    val aeMode by params.aeMode.observed()
    val shutter by params.shutter.observed()
    val size by params.size.observed()
    val fps by params.fps.observed()
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_shutter)) {
        // 快门不在录制中可调白名单（A4）：能开窗看档位，但控件全部禁用
        if (!editable) Note(R.string.cam_recording_readonly)
        AeModeRow(params, ability, enabled = editable)
        if (aeMode == AeMode.MANUAL) {
            // 档位可选性吃**帧率档位**（2026-10-04 r11/r10 口径）：下发帧周期与快门钳制都按档位
            // （可变范围 [24,30] 选 24 时 41.67ms 在合法域内、HAL 放到 24fps），选档域与下发同源；
            // 旧口径用 pick.hi=30 会把 1/24 灰显——那是 r10 之前"帧周期按 hi 钳"语义的产物。
            // 取不到（该档 fps 不可用）时退回用户档位，与 WotaParams.applyExposure 的退回一致。
            val ranges = ability?.fpsRangesFor(size, ability.needsHighSpeed(size, fps.value)) ?: emptyList()
            val fpsHi = pickFpsRange(ranges, fps.value)?.lo ?: fps.value
            // 2026-10-17 需求：快门 7 档快捷 chip 删除，档位改由刻度滑杆承担。
            // 梯只收 supported 档（被设备否决的档消失而不是灰 chip）；标签仍来自 shutterItems，
            // 所以强制档 1/24、1/25 的 ※ 保留。
            val items = shutterItems(ability?.exposureNs, fpsHi, stringResource(R.string.approx_mark))
                .filter { it.supported }
            val lo = shutter.range?.start?.toLong()
            val hi = shutter.range?.endInclusive?.toLong()
            if (lo != null && hi != null && hi > lo) {
                val ladder = shutterLadder(items.map { it.value.toFloat() }, lo.toFloat(), hi.toFloat())
                // ns 值经 Float 往返会丢低位精度（41,666,666 → 41,666,664），写回/查标签都用
                // 梯值反查原始档，避免强制档判据（isRequiredShutterNs）与 ※ 标签一起失效。
                val byTierFloat = items.associateBy { it.value.toFloat() }
                PillSlider(
                    value = shutter.value.toFloat(),
                    lo = lo.toFloat(),
                    hi = hi.toFloat(),
                    step = 1000f,
                    onValueChange = { tick ->
                        val exactNs = byTierFloat[tick]?.value?.toLong() ?: tick.toLong()
                        params.shutter.value = shutter.copy(value = exactNs)
                    },
                    format = { tick -> byTierFloat[tick]?.label ?: shutterText(tick.toLong()) },
                    enabled = editable,
                    onValueChangeFinished = onPersist,
                    snapKey = SliderKey.SHUTTER,
                    ladder = ladder
                )
            }
        } else {
            Note(R.string.cam_manual_exposure_only)
        }
    }
}

@Composable
private fun IsoPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onPersist: () -> Unit
) {
    val aeMode by params.aeMode.observed()
    val iso by params.iso.observed()
    val lo = iso.range?.start ?: 0
    val hi = iso.range?.endInclusive ?: 0
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_iso)) {
        AeModeRow(params, ability, enabled = editable)
        if (hi > lo) {
            PillSlider(
                value = iso.value.toFloat(),
                lo = lo.toFloat(),
                hi = hi.toFloat(),
                step = 1f,
                enabled = editable && aeMode == AeMode.MANUAL,
                onValueChange = { params.iso.value = iso.copy(value = it.roundToInt()) },
                format = { "${it.roundToInt()}" },
                onValueChangeFinished = onPersist,
                snapKey = SliderKey.ISO,
                ladder = isoLadder(lo, hi)
            )
        } else {
            Note(R.string.unsupported_by_device)
        }
    }
}

@Composable
private fun EvPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onPersist: () -> Unit
) {
    val aeMode by params.aeMode.observed()
    val ev by params.ev.observed()
    val evStep = ability?.evStep ?: 1f
    val lo = ev.range?.start ?: 0
    val hi = ev.range?.endInclusive ?: 0
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_ev)) {
        AeModeRow(params, ability, enabled = editable)
        if (hi <= lo) {
            Note(R.string.unsupported_by_device)
        } else if (aeMode == AeMode.MANUAL) {
            Note(R.string.cam_auto_exposure_only)
        } else {
            PillSlider(
                value = ev.value.toFloat(),
                lo = lo.toFloat(),
                hi = hi.toFloat(),
                step = 1f,
                enabled = editable && aeMode != AeMode.MANUAL,
                onValueChange = { params.ev.value = ev.copy(value = it.roundToInt()) },
                format = { evText(it.roundToInt(), evStep) },
                onValueChangeFinished = onPersist,
                // EV 是整数域（每个整数一档），连续模式与梯等价，不提供开关
                snapKey = null,
                ladder = evLadder(lo, hi)
            )
        }
    }
}

@Composable
private fun WbPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onUnsupported: () -> Unit,
    onPersist: () -> Unit
) {
    val wbMode by params.wbMode.observed()
    val kelvin by params.kelvin.observed()
    val tint by params.tint.observed()
    val names = WbPreset.values().map { stringResource(wbLabelRes(it)) }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_wb)) {
        // 白平衡属录制中可调白名单（A4）：预设行保持可选，否则手动组（色温/色调）不可达
        PillChoices(
            options = WbPreset.values().mapIndexed { i, p ->
                PillOption(p.ordinal, names[i], p == WbPreset.MANUAL || ability?.awbModes?.contains(p.awbMode) != false)
            },
            selected = wbMode.ordinal,
            columns = 4,
            onPick = { opt ->
                if (opt.enabled) params.wbMode.value = WbPreset.values()[opt.value] else onUnsupported()
            },
            enabled = editable
        )
        if (wbMode == WbPreset.MANUAL) {
            val kelvinLo = (kelvin.range?.start ?: WotaTiers.KELVIN_MIN)
            val kelvinHi = (kelvin.range?.endInclusive ?: WotaTiers.KELVIN_MAX)
            PillSlider(
                value = kelvin.value.toFloat(),
                lo = kelvinLo.toFloat(),
                hi = kelvinHi.toFloat(),
                step = 50f,
                enabled = editable,
                label = stringResource(R.string.cam_p_kelvin),
                onValueChange = { params.kelvin.value = kelvin.copy(value = it.roundToInt()) },
                format = { "${it.roundToInt()}K" },
                onValueChangeFinished = onPersist,
                snapKey = SliderKey.KELVIN,
                ladder = kelvinLadder(kelvinLo, kelvinHi)
            )
            val tintLo = (tint.range?.start ?: 0)
            val tintHi = (tint.range?.endInclusive ?: 0)
            PillSlider(
                value = tint.value.toFloat(),
                lo = tintLo.toFloat(),
                hi = tintHi.toFloat(),
                step = 1f,
                enabled = editable,
                label = stringResource(R.string.cam_p_tint),
                onValueChange = { params.tint.value = tint.copy(value = it.roundToInt()) },
                format = { String.format(Locale.US, "%+d", it.roundToInt()) },
                onValueChangeFinished = onPersist,
                snapKey = SliderKey.TINT,
                ladder = tintLadder(tintLo, tintHi)
            )
        }
    }
}

@Composable
private fun ZoomPill(
    anchor: IntRect,
    params: WotaParams,
    editable: Boolean,
    onClose: () -> Unit,
    onPersist: () -> Unit
) {
    val zoom by params.zoom.observed()
    val lo = zoom.range?.start ?: 1f
    val hi = zoom.range?.endInclusive ?: 1f
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_zoom)) {
        // 2026-10-17 需求：变焦快捷倍率 chip 删除，档位全部由刻度滑杆承担
        if (hi > lo) {
            PillSlider(
                value = zoom.value,
                lo = lo,
                hi = hi,
                step = 0.1f,
                enabled = editable,
                onValueChange = { params.zoom.value = zoom.copy(value = it) },
                format = { fmtX(it) },
                onValueChangeFinished = onPersist,
                snapKey = SliderKey.ZOOM,
                ladder = zoomLadder(lo, hi)
            )
        } else {
            Note(R.string.unsupported_by_device)
        }
    }
}

@Composable
private fun FocusPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    recording: Boolean,
    /** 对焦锁定态与切换回执（无默认值必传，见 [PillHost]） */
    focusLocked: Boolean,
    onToggleFocusLock: () -> Unit,
    onClose: () -> Unit,
    onUnsupported: () -> Unit,
    onFocusCenter: () -> Unit,
    onPersist: () -> Unit
) {
    val afMode by params.afMode.observed()
    val mf by params.manualFocusDiopter.observed()
    val names = AfMode.values().map { stringResource(afLabelRes(it)) }
    val lo = mf.range?.start ?: 0f
    val hi = mf.range?.endInclusive ?: 0f
    // 锁定以「单次自动对焦」（AF_MODE_AUTO）为载体：能力不含它的镜头锁不了——条目灰显 + 说明，
    // 不做假开关（R3a；引擎 engageFocusLock 侧还有第二道守卫）。ability 未就绪时按既有惯例
    // 给可用（与上面 AF 模式条的 `!= false` 同口径）。
    val autoFocusSupported = ability?.afModes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) != false
    val lockUsable = afMode != AfMode.MANUAL && autoFocusSupported
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_af)) {
        PillChoices(
            options = AfMode.values().mapIndexed { i, m ->
                PillOption(m.ordinal, names[i], ability?.afModes?.contains(m.cam2Mode) != false)
            },
            selected = afMode.ordinal,
            columns = 2,
            onPick = { opt ->
                when {
                    !opt.enabled -> onUnsupported()
                    else -> {
                        // 切换 AF 模式 = 隐式解锁（用户明确要求换策略，锁必须先让位；解除路径之三）
                        if (params.focusLocked.value) params.focusLocked.value = false
                        params.afMode.value = AfMode.values()[opt.value]
                    }
                }
            },
            enabled = editable
        )
        if (afMode == AfMode.MANUAL) {
            if (hi > lo) {
                PillSlider(
                    value = mf.value,
                    lo = lo,
                    hi = hi,
                    label = stringResource(R.string.cam_p_mf),
                    enabled = editable,
                    onValueChange = { params.manualFocusDiopter.value = mf.copy(value = it) },
                    format = { String.format(Locale.US, "%.2fD", it) },
                    onValueChangeFinished = onPersist,
                    snapKey = SliderKey.DIOPTER,
                    ladder = diopterLadder(lo, hi)
                )
            } else {
                // 定焦镜头（前摄常见）落到手动档是核心的兜底；说清楚"没有可调对焦距离"，
                // 而不是泛泛一句「本机不支持」——用户分不清是在说 AF 模式还是滑杆（真机前摄截图核对）
                Note(R.string.cam_af_fixed_focus)
            }
        }
        // 底部行两枚（用户反馈第 2 条）：中心点按对焦（合同不变）+ 锁定对焦/解除锁定（副控制，
        // 以最近点按处/中心对焦一次并保持；selected 反映锁态）。
        // A4：录制中未锁时锁定条目禁用（已锁则可点解除），其余对焦控件照常可用。
        PillChoices(
            options = listOf(
                PillOption(0, stringResource(R.string.cam_tap_center)),
                PillOption(
                    1,
                    stringResource(if (focusLocked) R.string.cam_focus_unlock else R.string.cam_focus_lock),
                    enabled = lockUsable && (!recording || focusLocked)
                )
            ),
            selected = if (focusLocked) 1 else -1,
            columns = 2,
            onPick = { opt ->
                when {
                    opt.value == 0 -> onFocusCenter()
                    !lockUsable -> onUnsupported()
                    else -> onToggleFocusLock()
                }
            },
            enabled = editable
        )
        when {
            afMode == AfMode.MANUAL -> Note(R.string.cam_focus_manual_locked)
            !autoFocusSupported -> Note(R.string.cam_focus_lock_unsupported)
        }
        // 只在"本来锁得上、只是录制中被拦"时给这条；手动档/能力不支持已有更准确的说明（审查 P3）
        if (recording && !focusLocked && lockUsable) Note(R.string.cam_focus_lock_recording)
        Note(R.string.cam_tap_hint)
    }
}

@Composable
private fun BitratePill(anchor: IntRect, params: WotaParams, editable: Boolean, onClose: () -> Unit) {
    val bitrate by params.bitrate.observed()
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_bitrate)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        PillChoices(
            options = WotaTiers.BITRATES.map { PillOption(it / 1_000_000, bitrateText(it)) },
            selected = bitrate / 1_000_000,
            columns = 3,
            onPick = { opt ->
                params.bitrate.value = opt.value * 1_000_000
                onClose()
            },
            enabled = editable
        )
    }
}

/** 闪光灯：底栏那颗就是它的锚点，四种模式一次摊开，不再靠连点循环 */
@Composable
private fun FlashPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onUnsupported: () -> Unit
) {
    val flash by params.flash.observed()
    val names = Flash.values().map { stringResource(flashLabelRes(it)) }
    val available = ability?.flashAvailable == true
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_flash)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        if (!available) Note(R.string.unsupported_by_device)
        PillChoices(
            options = Flash.values().mapIndexed { i, f -> PillOption(f.ordinal, names[i], available) },
            selected = flash.ordinal,
            onPick = { opt ->
                if (!opt.enabled) {
                    onUnsupported()
                } else {
                    params.flash.value = Flash.values()[opt.value]
                    onClose()
                }
            },
            enabled = editable
        )
    }
}

/** 参考线：11 种线型多选，位掩码即时写回总线，取景器跟着变（面板时代就是边改边看） */
@Composable
private fun RefLinePill(anchor: IntRect, params: WotaParams, editable: Boolean, onClose: () -> Unit) {
    val mask by params.refLines.observed()
    val types = RefLineType.ALL
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_refline_title)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        PillChoices(
            options = listOf(
                PillOption(0, stringResource(R.string.cam_refline_all)),
                PillOption(1, stringResource(R.string.cam_refline_clear))
            ),
            selected = -1,
            columns = 2,
            onPick = { opt ->
                params.refLines.value = if (opt.value == 0) RefLineType.FULL_MASK else 0
            },
            enabled = editable
        )
        PillToggles(
            options = types.mapIndexed { i, t -> PillOption(i, t.label) },
            isOn = { RefLineType.isOn(mask, types[it.value]) },
            columns = 2,
            label = stringResource(R.string.cam_refline_count, RefLineType.typesOf(mask).size),
            onToggle = { opt -> types.getOrNull(opt.value)?.let { params.refLines.value = RefLineType.toggle(mask, it) } },
            enabled = editable
        )
    }
}

/** 屏幕监看：效果互斥 + 斑马纹阈值/密度 + 峰值强度/颜色。DIRECT 下效果本来不生效，整组禁用要说得清 */
@Composable
private fun MonitorPill(
    anchor: IntRect,
    params: WotaParams,
    gpuMode: Boolean,
    editable: Boolean,
    onClose: () -> Unit,
    onPersist: () -> Unit
) {
    val effect by params.frameEffect.observed()
    val zebraThreshold by params.zebraThreshold.observed()
    val zebraDensity by params.zebraDensity.observed()
    val peakingStrength by params.peakingStrength.observed()
    val peakingColor by params.peakingColor.observed()
    val effectNames = FrameEffect.values().map { stringResource(effectLabelRes(it)) }
    val colorNames = PeakingColor.values().map { stringResource(peakingColorLabelRes(it)) }
    val densityNames = listOf(
        stringResource(R.string.cam_zebra_d1),
        stringResource(R.string.cam_zebra_d2),
        stringResource(R.string.cam_zebra_d3)
    )
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_monitor_title)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        PillChoices(
            options = FrameEffect.values().mapIndexed { i, e -> PillOption(e.ordinal, effectNames[i]) },
            selected = effect.ordinal,
            columns = 2,
            onPick = { opt -> params.frameEffect.value = FrameEffect.values()[opt.value] },
            enabled = editable
        )
        if (!gpuMode) {
            Note(R.string.cam_direct_no_effect)
        }
        if (effect == FrameEffect.ZEBRA) {
            PillSlider(
                value = zebraUiOfThreshold(zebraThreshold).toFloat(),
                lo = 80f,
                hi = 100f,
                step = 1f,
                enabled = editable && gpuMode,
                label = stringResource(R.string.cam_zebra_threshold),
                onValueChange = { params.zebraThreshold.value = zebraThresholdOfUi(it.roundToInt()) },
                format = { "${it.roundToInt()}%" },
                onValueChangeFinished = onPersist,
                // 斑马阈值是整数百分比（80..100 逐格），连续模式与梯等价
                snapKey = null,
                ladder = percentLadder(80, 100, 1)
            )
            PillChoices(
                label = stringResource(R.string.cam_zebra_density),
                options = (1..3).map { PillOption(it, densityNames.getOrElse(it - 1) { "" }) },
                selected = zebraDensity,
                columns = 3,
                onPick = { params.zebraDensity.value = it.value },
                enabled = editable
            )
        }
        if (effect == FrameEffect.PEAKING) {
            PillSlider(
                value = peakingStrength * 100f,
                lo = 5f,
                hi = 95f,
                step = 1f,
                enabled = editable && gpuMode,
                label = stringResource(R.string.cam_peaking_strength),
                onValueChange = { params.peakingStrength.value = (it / 100f).coerceIn(0f, 1f) },
                format = { "${it.roundToInt()}%" },
                onValueChangeFinished = onPersist,
                snapKey = SliderKey.PEAKING,
                ladder = percentLadder(5, 95, 5)
            )
            PillChoices(
                label = stringResource(R.string.cam_peaking_color),
                options = PeakingColor.values().mapIndexed { i, c -> PillOption(c.ordinal, colorNames[i]) },
                selected = peakingColor.ordinal,
                columns = 3,
                onPick = { params.peakingColor.value = PeakingColor.values()[it.value] },
                enabled = editable
            )
        }
    }
}

/**
 * 镜头选择：等效焦距与视场角都要读 characteristics（IPC），所以进弹窗才现取一次。
 * 取数逻辑由原 `LensSheet` 整体搬来（面板已删），选中态仍按 [LensSlot.key] 而不是档位——
 * 档位会重名（多颗同焦段后摄、多颗前摄），按档位会让几颗同时亮成「正在使用」。
 */
@Composable
private fun LensPill(
    anchor: IntRect,
    current: LensSlot?,
    editable: Boolean,
    onClose: () -> Unit,
    onPickLens: (LensSlot) -> Unit
) {
    val app = LocalContext.current.applicationContext
    var items by remember { mutableStateOf<List<LensCandidate>>(emptyList()) }
    LaunchedEffect(app) { items = withContext(Dispatchers.IO) { readLensCandidates(app) } }
    val using = stringResource(R.string.cam_lens_using)
    val names = LensType.values().map { stringResource(lensLabelRes(it)) }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_lens)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        if (items.isEmpty()) {
            Note(R.string.cam_lens_empty)
        } else {
            PillRowList(
                rows = items.map { c ->
                    val active = c.slot.key == current?.key
                    PillRow(
                        key = c.slot.key,
                        label = names[c.slot.type.ordinal] + if (active) " · $using" else "",
                        detail = lensDetail(c),
                        active = active
                    )
                },
                label = stringResource(R.string.cam_lens_tip),
                onPick = { row ->
                    items.firstOrNull { it.slot.key == row.key }?.let { onPickLens(it.slot) }
                    onClose()
                },
                enabled = editable
            )
        }
    }
}

/** 一行镜头候选：视场角要读 characteristics，读不到就只报等效焦距 */
private data class LensCandidate(val slot: LensSlot, val fovDeg: Float)

@Composable
private fun lensDetail(c: LensCandidate): String =
    if (c.fovDeg > 0f) {
        stringResource(R.string.cam_lens_fov, fmt1(c.slot.eqFocal), fmt1(c.fovDeg))
    } else {
        stringResource(R.string.cam_lens_fov_only, fmt1(c.slot.eqFocal))
    }

private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

private fun readLensCandidates(app: Context): List<LensCandidate> {
    val mgr = runCatching {
        app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }.getOrNull() ?: return emptyList()
    return runCatching { enumerateLenses(mgr) }.getOrDefault(emptyList()).map { slot ->
        val fov = runCatching {
            hFovOf(mgr.getCameraCharacteristics(slot.physicalId ?: slot.logicId))
        }.getOrDefault(0f)
        LensCandidate(slot, fov)
    }
}


/** 防抖：底栏那颗就是它的锚点；档位可用性全看本机 OIS/EIS 能力 */
@Composable
private fun StabPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    editable: Boolean,
    onClose: () -> Unit,
    onUnsupported: () -> Unit
) {
    val stabilize by params.stabilize.observed()
    val names = Stabilize.values().map { stringResource(stabLabelRes(it)) }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_stab)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        PillChoices(
            options = Stabilize.values().mapIndexed { i, m ->
                val ok = when (m) {
                    Stabilize.OFF -> true
                    Stabilize.OIS -> ability?.oisAvailable == true
                    Stabilize.EIS -> ability?.eisAvailable == true
                    Stabilize.OIS_EIS -> ability != null && ability.oisAvailable && ability.eisAvailable
                }
                PillOption(m.ordinal, names[i], ok)
            },
            selected = stabilize.ordinal,
            columns = 2,
            onPick = { opt ->
                if (!opt.enabled) {
                    onUnsupported()
                } else {
                    params.stabilize.value = Stabilize.values()[opt.value]
                    onClose()
                }
            },
            enabled = editable
        )
    }
}

/**
 * 容量：顶栏「剩余 xxG」点开的读数浮层。
 * 容量本身没有可配项，但**能拍多久**取决于码率，所以这里同时给码率档位，
 * 让这颗胶囊点开有实际意义（§37 第 4 条：不能再拿分辨率胶囊顶替）。
 */
@Composable
private fun StoragePill(anchor: IntRect, params: WotaParams, freeMb: Long, editable: Boolean, onClose: () -> Unit) {
    val bitrate by params.bitrate.observed()
    val minutes = StorageEstimate.minutesOf(freeMb, bitrate)
    val unit = if (StorageEstimate.unitOf(minutes)) R.string.pill_hours_unit else R.string.pill_minutes_unit
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_free_space_title)) {
        if (!editable) Note(R.string.cam_recording_readonly)
        PillChoices(
            label = stringResource(R.string.pill_record_minutes, stringResource(unit), StorageEstimate.humanize(minutes)),
            options = WotaTiers.BITRATES.map { PillOption(it / 1_000_000, bitrateText(it)) },
            selected = bitrate / 1_000_000,
            columns = 3,
            onPick = { opt -> params.bitrate.value = opt.value * 1_000_000 },
            enabled = editable
        )
        Note(R.string.pill_storage_note)
    }
}

@Composable
private fun Note(res: Int) {
    NoteText(stringResource(res))
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = WotaType.caption,
        color = WotaColor.textLo,
        // 封宽（2026-10-05 紧凑化）：长说明句不封宽会把弹窗顶到 400dp 上限，
        // 改成在 220dp 内折行——弹窗面积由档位行决定，说明文字只往下加行
        modifier = Modifier.widthIn(max = 220.dp)
    )
}

private fun fmtX(v: Float): String = String.format(Locale.US, "%.1fx", v)

/**
 * 「音频」音源管理就近面板（内录体系批 2）：标题与「收起」由 `WotaPillPopup` 提供，
 * 内容是音源选择 + 蓝牙双页签（蓝牙页原样复用 [com.wotagei.cam.ui.dialog.BtSpeakerPanel]，零行为变化）。
 *
 * 键名历史：触发位原是蓝牙胶囊（`PillKey.BT`/`CamPill.BT`），批 2 换「音频」chip；
 * 键名保留——位掩码按 bit 持久化在 `hud_pills`，改键名等于改存量配置语义（P5 裁决）。
 */
@Composable
private fun AudioPill(
    anchor: IntRect,
    capture: com.wotagei.cam.record.PlaybackCaptureController,
    editable: Boolean,
    onEnableCapture: () -> Unit,
    controller: com.wotagei.cam.bt.BtSpeakerController,
    onClose: () -> Unit,
    /** 环境音开关态（真源 params.audioEnabled）：与内录独立，无默认值必传（双开定版 2026-10-07） */
    ambientEnabled: Boolean,
    onToggleAmbient: () -> Unit
) {
    com.wotagei.cam.ui.dialog.AudioSourcePanel(
        anchor = anchor,
        capture = capture,
        editable = editable,
        onEnableCapture = onEnableCapture,
        bt = controller,
        ambientEnabled = ambientEnabled,
        onToggleAmbient = onToggleAmbient,
        onDismiss = onClose
    )
}
