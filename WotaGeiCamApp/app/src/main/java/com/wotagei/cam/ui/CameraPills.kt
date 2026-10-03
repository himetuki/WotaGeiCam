package com.wotagei.cam.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.CameraAbility
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.LensSlot
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.PeakingColor
import com.wotagei.cam.core.RefLineType
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
    PillKey.BT to "HudLayer.HudEntryItem(BT) → widget.BtChip(modifier)",
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
    onLockTip: () -> Unit,
    onUnsupported: () -> Unit,
    onFocusCenter: () -> Unit,
    onPickLens: (LensSlot) -> Unit,
    freeMb: Long,
    bt: com.wotagei.cam.bt.BtSpeakerController
) {
    val ability = slot?.ability
    when (key) {
        PillKey.SIZE -> SizePill(anchor, params, ability, recording, onClose, onLockTip, onUnsupported)
        PillKey.FPS -> FpsPill(anchor, params, ability, recording, onClose, onLockTip, onUnsupported)
        PillKey.SHUTTER -> ShutterPill(anchor, params, ability, onClose)
        PillKey.ISO -> IsoPill(anchor, params, ability, onClose)
        PillKey.EV -> EvPill(anchor, params, ability, onClose)
        PillKey.WB -> WbPill(anchor, params, ability, onClose, onUnsupported)
        PillKey.ZOOM -> ZoomPill(anchor, params, onClose)
        PillKey.FOCUS -> FocusPill(anchor, params, ability, onClose, onUnsupported, onFocusCenter)
        PillKey.BITRATE -> BitratePill(anchor, params, onClose)
        PillKey.LENS -> LensPill(anchor, slot, recording, onClose, onLockTip, onPickLens)
        PillKey.REFLINE -> RefLinePill(anchor, params, onClose)
        PillKey.MONITOR -> MonitorPill(anchor, params, gpuMode, onClose)
        PillKey.FLASH -> FlashPill(anchor, params, ability, onClose, onUnsupported)
        PillKey.STAB -> StabPill(anchor, params, ability, onClose, onUnsupported)
        PillKey.STORAGE -> StoragePill(anchor, params, freeMb, onClose)
        PillKey.BT -> BtPill(anchor, bt, onClose)
    }
}

/** AE 模式行：曝光三件套共用，手动档只在真的能手动时才给选 */
@Composable
private fun AeModeRow(params: WotaParams, ability: CameraAbility?) {
    val aeMode by params.aeMode.observed()
    val names = AeMode.values().map { stringResource(aeLabelRes(it)) }
    PillChoices(
        options = AeMode.values().mapIndexed { i, m ->
            PillOption(m.ordinal, names[i], m != AeMode.MANUAL || ability?.manualExposurePossible() == true)
        },
        selected = aeMode.ordinal,
        label = stringResource(R.string.cam_p_ae),
        onPick = { params.aeMode.value = AeMode.values()[it.value] }
    )
}

@Composable
private fun SizePill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    recording: Boolean,
    onClose: () -> Unit,
    onLockTip: () -> Unit,
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
        if (options.isEmpty()) Note(R.string.unsupported_by_device)
        PillChoices(
            options = options.mapIndexed { i, o -> PillOption(i, sizeText(o.size), o.supported) },
            selected = selected,
            columns = 2,
            onPick = { opt ->
                val target = options.getOrNull(opt.value)
                when {
                    recording -> onLockTip()
                    target == null || !target.supported -> onUnsupported()
                    else -> {
                        params.requestSize(target.size)
                        onClose()
                    }
                }
            }
        )
        // 分辨率与帧率是同一件事的两半（换分辨率后帧率档可能失效），放一个弹窗里免得来回点
        PillChoices(
            options = fpsOptions(ability, size, fps.value > WotaTiers.HIGH_SPEED_FPS),
            selected = fps.value,
            label = stringResource(R.string.cam_p_fps),
            onPick = { opt ->
                if (recording) onLockTip()
                else if (!opt.enabled) onUnsupported()
                else params.fps.value = fps.copy(value = opt.value)
            }
        )
        rangeNote?.let { NoteText(it) }
    }
}

@Composable
private fun FpsPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    recording: Boolean,
    onClose: () -> Unit,
    onLockTip: () -> Unit,
    onUnsupported: () -> Unit
) {
    val app = LocalContext.current
    val settingsPrefs = remember(app) { WotaSettings.of(app) }
    val size by params.size.observed()
    val fps by params.fps.observed()
    // 强制 24/25fps 在无原生精确档（※ 档）设备上的录制期转换模式（2026-10-03 定版）：
    // MEND=抽帧+取大补弧（弧连续）/ DROP=仅抽帧（弧在抽帧点断，用户自选）。有精确档时不显示。
    val convertActive = fps.value in WotaTiers.REQUIRED_FPS && !fps.exact
    var convertMode by remember(settingsPrefs) {
        mutableStateOf(WotaSettings.arcConvertMode(settingsPrefs))
    }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_fps)) {
        PillChoices(
            options = fpsOptions(ability, size, fps.value > WotaTiers.HIGH_SPEED_FPS),
            selected = fps.value,
            onPick = { opt ->
                if (recording) {
                    onLockTip()
                } else if (!opt.enabled) {
                    onUnsupported()
                } else {
                    params.fps.value = fps.copy(value = opt.value)
                    onClose()
                }
            }
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
                        // 录制中只看不改（与档位选择同一把锁）
                        onClick = {
                            if (!recording) {
                                convertMode = mode
                                WotaSettings.setArcConvertMode(settingsPrefs, mode)
                            }
                        },
                        modifier = Modifier.weight(1f)
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
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 帧率档：与抽屉同一套求交逻辑，`※` 表示本机只能给近似值 */
@Composable
private fun fpsOptions(ability: CameraAbility?, size: Size, highSpeed: Boolean): List<PillOption> {
    val ranges = ability?.fpsRangesFor(size, highSpeed) ?: emptyList()
    val approx = stringResource(R.string.approx_mark)
    return WotaTiers.FPS.map { tier ->
        val pick = pickFpsRange(ranges, tier)
        PillOption(tier, "$tier" + if (pick?.exact == false) approx else "", pick != null)
    }
}

@Composable
private fun ShutterPill(anchor: IntRect, params: WotaParams, ability: CameraAbility?, onClose: () -> Unit) {
    val aeMode by params.aeMode.observed()
    val shutter by params.shutter.observed()
    val size by params.size.observed()
    val fps by params.fps.observed()
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_shutter)) {
        AeModeRow(params, ability)
        if (aeMode == AeMode.MANUAL) {
            // 档位可选性吃「实际生效的 fps 上界」= pickFpsRange 的 hi，与下发/回写同一个帧周期口径；
            // 取不到（该档 fps 不可用）时退回用户档位，与 WotaParams.applyExposure 的退回一致。
            val ranges = ability?.fpsRangesFor(size, fps.value > WotaTiers.HIGH_SPEED_FPS) ?: emptyList()
            val fpsHi = pickFpsRange(ranges, fps.value)?.hi ?: fps.value
            PillChoices(
                // 强制档（1/24、1/25）：设备曝光范围没有也照样可选，标签上打 ※ 与帧率侧一致
                options = shutterItems(ability?.exposureNs, fpsHi, stringResource(R.string.approx_mark))
                    .map { PillOption(it.value, it.label, it.supported) },
                selected = shutter.value.toInt(),
                onPick = { opt ->
                    // 灰显档必须挡在参数总线之外。为什么必须查 enabled：真机实测（2026-10-02 02:29 包），
                    // 本机 24※（24fps 判非精确档、实际 fps 上界 30）下点 1/24——此前这条回调是全工程
                    // 唯一不查 enabled 就直写总线的（对照 SizePill/FpsPill），引擎 5ms 合并后
                    // clampShutterToFrame 按帧周期把 41,666,666 钳成 33,333,333，"选了 1/24 当场变 1/30"。
                    // 视觉灰显（valueColor→textLo）没有执行力；chip 的 clickable(enabled=false) 是第一道闸，
                    // 这里的守卫是第二道（对照 SizePill:target.supported / FpsPill:!opt.enabled 同一条纪律）。
                    if (!opt.enabled) return@PillChoices
                    params.shutter.value = shutter.copy(value = opt.value.toLong())
                }
            )
            val lo = shutter.range?.start?.toLong()
            val hi = shutter.range?.endInclusive?.toLong()
            if (lo != null && hi != null && hi > lo) {
                PillSlider(
                    value = shutter.value.toFloat(),
                    lo = lo.toFloat(),
                    hi = hi.toFloat(),
                    step = 1000f,
                    onValueChange = { params.shutter.value = shutter.copy(value = it.toLong()) },
                    format = { shutterText(it.toLong()) }
                )
            }
        } else {
            Note(R.string.cam_manual_exposure_only)
        }
    }
}

@Composable
private fun IsoPill(anchor: IntRect, params: WotaParams, ability: CameraAbility?, onClose: () -> Unit) {
    val aeMode by params.aeMode.observed()
    val iso by params.iso.observed()
    val lo = iso.range?.start ?: 0
    val hi = iso.range?.endInclusive ?: 0
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_iso)) {
        AeModeRow(params, ability)
        if (hi > lo) {
            PillSlider(
                value = iso.value.toFloat(),
                lo = lo.toFloat(),
                hi = hi.toFloat(),
                step = 1f,
                enabled = aeMode == AeMode.MANUAL,
                onValueChange = { params.iso.value = iso.copy(value = it.roundToInt()) },
                format = { "${it.roundToInt()}" }
            )
        } else {
            Note(R.string.unsupported_by_device)
        }
    }
}

@Composable
private fun EvPill(anchor: IntRect, params: WotaParams, ability: CameraAbility?, onClose: () -> Unit) {
    val aeMode by params.aeMode.observed()
    val ev by params.ev.observed()
    val evStep = ability?.evStep ?: 1f
    val lo = ev.range?.start ?: 0
    val hi = ev.range?.endInclusive ?: 0
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_ev)) {
        AeModeRow(params, ability)
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
                enabled = aeMode != AeMode.MANUAL,
                onValueChange = { params.ev.value = ev.copy(value = it.roundToInt()) },
                format = { evText(it.roundToInt(), evStep) }
            )
        }
    }
}

@Composable
private fun WbPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    onClose: () -> Unit,
    onUnsupported: () -> Unit
) {
    val wbMode by params.wbMode.observed()
    val kelvin by params.kelvin.observed()
    val tint by params.tint.observed()
    val names = WbPreset.values().map { stringResource(wbLabelRes(it)) }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_wb)) {
        PillChoices(
            options = WbPreset.values().mapIndexed { i, p ->
                PillOption(p.ordinal, names[i], p == WbPreset.MANUAL || ability?.awbModes?.contains(p.awbMode) != false)
            },
            selected = wbMode.ordinal,
            columns = 4,
            onPick = { opt ->
                if (opt.enabled) params.wbMode.value = WbPreset.values()[opt.value] else onUnsupported()
            }
        )
        if (wbMode == WbPreset.MANUAL) {
            PillSlider(
                value = kelvin.value.toFloat(),
                lo = (kelvin.range?.start ?: WotaTiers.KELVIN_MIN).toFloat(),
                hi = (kelvin.range?.endInclusive ?: WotaTiers.KELVIN_MAX).toFloat(),
                step = 50f,
                label = stringResource(R.string.cam_p_kelvin),
                onValueChange = { params.kelvin.value = kelvin.copy(value = it.roundToInt()) },
                format = { "${it.roundToInt()}K" }
            )
            PillSlider(
                value = tint.value.toFloat(),
                lo = (tint.range?.start ?: 0).toFloat(),
                hi = (tint.range?.endInclusive ?: 0).toFloat(),
                step = 1f,
                label = stringResource(R.string.cam_p_tint),
                onValueChange = { params.tint.value = tint.copy(value = it.roundToInt()) },
                format = { String.format(Locale.US, "%+d", it.roundToInt()) }
            )
        }
    }
}

@Composable
private fun ZoomPill(anchor: IntRect, params: WotaParams, onClose: () -> Unit) {
    val zoom by params.zoom.observed()
    val lo = zoom.range?.start ?: 1f
    val hi = zoom.range?.endInclusive ?: 1f
    // 快捷档与「点按循环」共用一份真源（审查 S3-6）：这里曾内联过一张不含 0.5× 的表，
    // 于是同一台机点按能到 0.5×、面板里却没有那颗，两处倍率梯各说各话
    val quick = zoomPanelTiers(lo, hi)
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_zoom)) {
        if (quick.isNotEmpty()) {
            PillChoices(
                options = quick.map { PillOption((it * 10).roundToInt(), fmtX(it)) },
                selected = (zoom.value * 10).roundToInt(),
                columns = 4,
                onPick = { opt ->
                    params.zoom.value = zoom.copy(value = opt.value / 10f)
                    onClose()
                }
            )
        }
        if (hi > lo) {
            PillSlider(
                value = zoom.value,
                lo = lo,
                hi = hi,
                step = 0.1f,
                onValueChange = { params.zoom.value = zoom.copy(value = it) },
                format = { fmtX(it) }
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
    onClose: () -> Unit,
    onUnsupported: () -> Unit,
    onFocusCenter: () -> Unit
) {
    val afMode by params.afMode.observed()
    val mf by params.manualFocusDiopter.observed()
    val names = AfMode.values().map { stringResource(afLabelRes(it)) }
    val lo = mf.range?.start ?: 0f
    val hi = mf.range?.endInclusive ?: 0f
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_af)) {
        PillChoices(
            options = AfMode.values().mapIndexed { i, m ->
                PillOption(m.ordinal, names[i], ability?.afModes?.contains(m.cam2Mode) != false)
            },
            selected = afMode.ordinal,
            columns = 2,
            onPick = { opt ->
                if (opt.enabled) params.afMode.value = AfMode.values()[opt.value] else onUnsupported()
            }
        )
        if (afMode == AfMode.MANUAL) {
            if (hi > lo) {
                PillSlider(
                    value = mf.value,
                    lo = lo,
                    hi = hi,
                    label = stringResource(R.string.cam_p_mf),
                    onValueChange = { params.manualFocusDiopter.value = mf.copy(value = it) },
                    format = { String.format(Locale.US, "%.2fD", it) }
                )
            } else {
                // 定焦镜头（前摄常见）落到手动档是核心的兜底；说清楚"没有可调对焦距离"，
                // 而不是泛泛一句「本机不支持」——用户分不清是在说 AF 模式还是滑杆（真机前摄截图核对）
                Note(R.string.cam_af_fixed_focus)
            }
        }
        PillChoices(
            options = listOf(PillOption(0, stringResource(R.string.cam_tap_center))),
            selected = -1,
            columns = 1,
            onPick = { onFocusCenter() }
        )
        Note(R.string.cam_tap_hint)
    }
}

@Composable
private fun BitratePill(anchor: IntRect, params: WotaParams, onClose: () -> Unit) {
    val bitrate by params.bitrate.observed()
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_bitrate)) {
        PillChoices(
            options = WotaTiers.BITRATES.map { PillOption(it / 1_000_000, bitrateText(it)) },
            selected = bitrate / 1_000_000,
            columns = 3,
            onPick = { opt ->
                params.bitrate.value = opt.value * 1_000_000
                onClose()
            }
        )
    }
}

/** 闪光灯：底栏那颗就是它的锚点，四种模式一次摊开，不再靠连点循环 */
@Composable
private fun FlashPill(
    anchor: IntRect,
    params: WotaParams,
    ability: CameraAbility?,
    onClose: () -> Unit,
    onUnsupported: () -> Unit
) {
    val flash by params.flash.observed()
    val names = Flash.values().map { stringResource(flashLabelRes(it)) }
    val available = ability?.flashAvailable == true
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_flash)) {
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
            }
        )
    }
}

/** 参考线：11 种线型多选，位掩码即时写回总线，取景器跟着变（面板时代就是边改边看） */
@Composable
private fun RefLinePill(anchor: IntRect, params: WotaParams, onClose: () -> Unit) {
    val mask by params.refLines.observed()
    val types = RefLineType.ALL
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_refline_title)) {
        PillChoices(
            options = listOf(
                PillOption(0, stringResource(R.string.cam_refline_all)),
                PillOption(1, stringResource(R.string.cam_refline_clear))
            ),
            selected = -1,
            columns = 2,
            onPick = { opt ->
                params.refLines.value = if (opt.value == 0) RefLineType.FULL_MASK else 0
            }
        )
        PillToggles(
            options = types.mapIndexed { i, t -> PillOption(i, t.label) },
            isOn = { RefLineType.isOn(mask, types[it.value]) },
            columns = 2,
            label = stringResource(R.string.cam_refline_count, RefLineType.typesOf(mask).size),
            onToggle = { opt -> types.getOrNull(opt.value)?.let { params.refLines.value = RefLineType.toggle(mask, it) } }
        )
    }
}

/** 屏幕监看：效果互斥 + 斑马纹阈值/密度 + 峰值强度/颜色。DIRECT 下效果本来不生效，整组禁用要说得清 */
@Composable
private fun MonitorPill(anchor: IntRect, params: WotaParams, gpuMode: Boolean, onClose: () -> Unit) {
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
        PillChoices(
            options = FrameEffect.values().mapIndexed { i, e -> PillOption(e.ordinal, effectNames[i]) },
            selected = effect.ordinal,
            columns = 2,
            onPick = { opt -> params.frameEffect.value = FrameEffect.values()[opt.value] }
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
                enabled = gpuMode,
                label = stringResource(R.string.cam_zebra_threshold),
                onValueChange = { params.zebraThreshold.value = zebraThresholdOfUi(it.roundToInt()) },
                format = { "${it.roundToInt()}%" }
            )
            PillChoices(
                label = stringResource(R.string.cam_zebra_density),
                options = (1..3).map { PillOption(it, densityNames.getOrElse(it - 1) { "" }) },
                selected = zebraDensity,
                columns = 3,
                onPick = { params.zebraDensity.value = it.value }
            )
        }
        if (effect == FrameEffect.PEAKING) {
            PillSlider(
                value = peakingStrength * 100f,
                lo = 5f,
                hi = 95f,
                step = 1f,
                enabled = gpuMode,
                label = stringResource(R.string.cam_peaking_strength),
                onValueChange = { params.peakingStrength.value = (it / 100f).coerceIn(0f, 1f) },
                format = { "${it.roundToInt()}%" }
            )
            PillChoices(
                label = stringResource(R.string.cam_peaking_color),
                options = PeakingColor.values().mapIndexed { i, c -> PillOption(c.ordinal, colorNames[i]) },
                selected = peakingColor.ordinal,
                columns = 3,
                onPick = { params.peakingColor.value = PeakingColor.values()[it.value] }
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
    recording: Boolean,
    onClose: () -> Unit,
    onLockTip: () -> Unit,
    onPickLens: (LensSlot) -> Unit
) {
    val app = LocalContext.current.applicationContext
    var items by remember { mutableStateOf<List<LensCandidate>>(emptyList()) }
    LaunchedEffect(app) { items = withContext(Dispatchers.IO) { readLensCandidates(app) } }
    val using = stringResource(R.string.cam_lens_using)
    val names = LensType.values().map { stringResource(lensLabelRes(it)) }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_lens)) {
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
                    if (recording) {
                        onLockTip()
                    } else {
                        items.firstOrNull { it.slot.key == row.key }?.let { onPickLens(it.slot) }
                        onClose()
                    }
                }
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
    onClose: () -> Unit,
    onUnsupported: () -> Unit
) {
    val stabilize by params.stabilize.observed()
    val names = Stabilize.values().map { stringResource(stabLabelRes(it)) }
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_p_stab)) {
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
            }
        )
    }
}

/**
 * 容量：顶栏「剩余 xxG」点开的读数浮层。
 * 容量本身没有可配项，但**能拍多久**取决于码率，所以这里同时给码率档位，
 * 让这颗胶囊点开有实际意义（§37 第 4 条：不能再拿分辨率胶囊顶替）。
 */
@Composable
private fun StoragePill(anchor: IntRect, params: WotaParams, freeMb: Long, onClose: () -> Unit) {
    val bitrate by params.bitrate.observed()
    val minutes = StorageEstimate.minutesOf(freeMb, bitrate)
    val unit = if (StorageEstimate.unitOf(minutes)) R.string.pill_hours_unit else R.string.pill_minutes_unit
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.cam_free_space_title)) {
        PillChoices(
            label = stringResource(R.string.pill_record_minutes, stringResource(unit), StorageEstimate.humanize(minutes)),
            options = WotaTiers.BITRATES.map { PillOption(it / 1_000_000, bitrateText(it)) },
            selected = bitrate / 1_000_000,
            columns = 3,
            onPick = { opt -> params.bitrate.value = opt.value * 1_000_000 }
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
        color = WotaColor.textLo
    )
}

private fun fmtX(v: Float): String = String.format(Locale.US, "%.1fx", v)

/**
 * 蓝牙音箱的就近面板（#53）：标题与「收起」由 `WotaPillPopup` 提供，
 * 正文与原来的底部抽屉版共用同一个 [BtSpeakerPanel]，所以两处行为不会分叉。
 */
@Composable
private fun BtPill(
    anchor: IntRect,
    controller: com.wotagei.cam.bt.BtSpeakerController,
    onClose: () -> Unit
) {
    WotaPillPopup(anchor, onClose, title = stringResource(R.string.bt_title)) {
        com.wotagei.cam.ui.dialog.BtSpeakerPanel(controller)
    }
}
