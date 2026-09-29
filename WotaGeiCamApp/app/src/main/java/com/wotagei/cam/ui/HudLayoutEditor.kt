package com.wotagei.cam.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.LensType
import com.wotagei.cam.core.Size
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.core.capacityTierText
import com.wotagei.cam.record.BitratePolicy
import com.wotagei.cam.record.VideoStore
import com.wotagei.cam.ui.anim.appendLiquidLink
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.design.WotaStroke
import com.wotagei.cam.ui.design.WotaType
import com.wotagei.cam.ui.design.pillAnchor
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.dialog.lensLabelRes
import com.wotagei.cam.ui.dialog.sizeText
import com.wotagei.cam.ui.dialog.wbLabelRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「编辑控件」页（docs/plan/13 第 5 条）：拖容器改位置、容器内换序、跨容器挪，保存写 `hud_layout`。
 *
 * ## 与录制页共用同一条渲染路
 * 五枚容器全走 [HudZoneBox] + [HudTopZone] / [HudDockZone] / [HudReadoutZone] / [HudBottomZone]，
 * 位置表读同一份 [HudLayoutTable]，钳制走同一条 [clampZonePos]，读数块的"底边 + 一行几颗"走同一条
 * [planReadoutRow]（#70 A），胶囊档位走同一条 [chipTierFor]（#70 B）。
 * 所以"编辑页画了个假布局、两边分叉"在结构上不可能发生。**唯一**的差别是数据源：这页没有相机、
 * 没有传感器，读数取的是**用户设定的开机默认值**（[WotaSettings.applyDefaults] 那条链，与录制页
 * 同一份 prefs）与**真实剩余空间**（`VideoStore.freeSpaceMb()`，与录制页同一个来源）。
 *
 * 蓝牙/水平姿态/音量这三颗在这页拿到的是出厂态（未连接 / 水平 / 静音），因为它们的实时数据源
 * （蓝牙控制器、加速度计、编码器振幅）都挂在录制页上。**不是假开关**：显隐判据一条没少，
 * 闪光灯（本机有无闪光）、对焦（能否调焦）、音量表（只在录制中）这三条运行时判据仍在
 * [CameraScreen] 的可见条目集里，这页能摆的只是**位置**。
 *
 * ## 手势：一层捕获层
 * 拖动是这页唯一的手势，所以整页盖一层透明捕获层（[pointerInput] + `detectDragGestures`）。
 * 捕获层排在容器之后 ⇒ z 序在上面：命中某颗条目 → 拖**条目**（原位留空壳 + 跟手的 ghost + 「腰」）；
 * 否则命中某枚容器 → 拖**整枚容器**（底栏这枚只认纵向：松手后横向弹回可视中心并给一句提示，
 * x 进不了表，见 [dropContainer]）。这样不会出现"想拖动却把参数面板点开"，也不让子控件的
 * `clickable` 与父层拖拽互相抢事件（那种组合在 Compose 里本就不可靠）。代价照实写：
 * 这页点不到控件，要改参数回录制页改。
 *
 * ## 动感复用录制那一路的连通体几何
 * 拖起来时那条「腰」就是 [appendLiquidLink]（两圆 + 两条公切贝塞尔，腰宽 = 圆心距的函数，
 * 超过阈值掐断），半径取条目与 ghost 的实测短边一半，**没有第二套腰公式**。
 * `Path` 与 [Stroke] 在组合期 [remember]，每帧只 `reset()` + 追加，零分配。
 * 红线照旧：拖动位移只进 draw 阶段与布局偏移之外的那一层（draw 阶段读快照状态只重画那一层，
 * 不重组整页），松手落位那一帧才把绝对坐标写进表 —— 一次重排，不是动画。
 *
 * ## 越界钳制与重叠
 * 钳制吃**安全区实测宽高**（套了 safeDrawingPadding() 之后那块，挖孔让掉的边已经在里面）与本层实测的
 * 两条避让量：操作栏高（对应录制页的顶栏实测高）与底栏那排实占带高，没有新写魔法数，也没有按方向写死的
 * 右缘让位量（任务 #68 删的就是它，见 docs/plan/13 §九·补）。重叠**只提示不禁止**（用户定的口径），
 * 提示把叠在一起的两枚容器点名，不静默。
 *
 * ## 保存语义
 * 改动先进内存草稿，**点「保存」才落 prefs**（录制页每次组合直读 prefs + 挂变更监听，所以立刻生效）；
 * 返回不保存就整批丢弃，文案里写明白了。「重置」两步确认（第一次只是武装，第二次才清），
 * 清完给一个**即时可重做**的「撤销」—— 靠的是重置前那一份编码串原样写回，不是内存里的手抄本。
 */
@Composable
@Suppress("LongMethod")
fun HudLayoutEditorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext }
    val prefs = remember(app) { WotaSettings.of(app) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // ---- 草稿：进页时读已保存的那张表，「保存」之前不动 prefs，所以录制页不会被半成品改到
    var draft by remember(prefs) { mutableStateOf(WotaSettings.hudLayout(prefs)) }
    var saved by remember(prefs) { mutableStateOf(draft) }
    var hint by remember { mutableStateOf<String?>(null) }
    var resetArmed by remember { mutableStateOf(false) }
    var undoRaw by remember { mutableStateOf<String?>(null) }

    // ---- 数据源：开机默认参数（不接相机、不开传感器），标签与录制页默认档同一真源
    val params = remember(prefs) {
        WotaParams(CoroutineScope(Dispatchers.Unconfined)).also { WotaSettings.applyDefaults(prefs, it) }
    }
    var freeMb by remember { mutableLongStateOf(0L) }
    LaunchedEffect(app) { freeMb = runCatching { VideoStore(app).freeSpaceMb() }.getOrDefault(0L) }

    // ---- 可见条目：只认设置里的两个开关位（13 号计划第 5 条：只有已开启显示的控件可编辑）
    val pillMask = WotaSettings.hudPills(prefs)
    val hudMask = WotaSettings.hudItems(prefs)
    val visibleEntries: Set<HudEntry> = remember(pillMask, hudMask) {
        buildSet {
            CamPill.ALL.filter { it !in CamPill.hiddenOf(pillMask) }.forEach { add(HudEntry.of(it)) }
            HudItem.typesOf(hudMask).forEach { add(HudEntry.of(it)) }
        }
    }

    // ---- 实测：安全盒尺寸与窗口原点、五枚卡片本体、每颗条目本体
    val zoneRects = remember { mutableStateMapOf<HudZone, IntRect>() }
    val entryRects = remember { mutableStateMapOf<HudEntry, IntRect>() }
    var safeW by remember(configuration.orientation) { mutableIntStateOf(configuration.screenWidthDp) }
    var safeH by remember(configuration.orientation) { mutableIntStateOf(configuration.screenHeightDp) }
    var originXPx by remember { mutableIntStateOf(0) }
    var originYPx by remember { mutableIntStateOf(0) }
    // 编辑页的"顶栏避让量"就是自己这条操作栏的实测高（与录制页读顶栏实测高同一手法）
    var chromeH by remember { mutableIntStateOf(0) }
    val bottomOuterPadDp = BottomBarOuterPadV.value.roundToInt()
    val dockCardH = zoneRects[HudZone.BOTTOM].dpHeightToDp(density)
    val dockStripH = if (dockCardH > 0) dockCardH + 2 * bottomOuterPadDp
    else BottomBarSpaceFallback.value.roundToInt()
    val hudStripH = zoneRects[HudZone.READOUT].dpHeightToDp(density)
    // 读数块实测宽：喂给 planReadoutRow 当"同一行装不装得下"的安全网（与录制页同一个入参）
    val hudStripW = zoneRects[HudZone.READOUT].dpWidthToDp(density)
    val baseArea = HudAreaDp(
        width = safeW,
        height = safeH,
        topAvoidDp = chromeH,
        bottomAvoidDp = dockStripH
    )
    // #70 A：与录制页同一条决策（planReadoutRow），两个页面各摆一份 hudRoomDp/hudPerRowFor 就是 S3-5
    // 那条"两边判的不是同一条不等式"的老坑。底栏宽取本页实测（编辑页的底栏照样是居中的那枚底板），
    // 首帧量不到同样退到 BottomDockWidthFallback。
    val dockCardW = zoneRects[HudZone.BOTTOM].dpWidthToDp(density)
    val readoutCount = draft.visibleOrderOf(HudZone.READOUT, visibleEntries).size
    val readoutPlan = remember(
        readoutCount, density.fontScale, safeW, dockCardW, dockStripH, hudStripW
    ) {
        planReadoutRow(
            readoutCount = readoutCount,
            fontScale = density.fontScale,
            safeWidthDp = safeW,
            dockWidthDp = if (dockCardW > 0) dockCardW else BottomDockWidthFallback.value.roundToInt(),
            dockStripDp = dockStripH,
            bottomRowPadDp = bottomOuterPadDp,
            endPadDp = HudEdgePad.value,
            dockGapDp = WotaSpace.s.value,
            readoutWidthDp = hudStripW
        )
    }
    val readoutArea = baseArea.copy(bottomAvoidDp = readoutPlan.bottomAvoidDp)
    // 与录制页同一条：右竖 Dock 的下界要让开读数块那一截，但同一条横带上取大不求和
    val rightArea = areaForRightDock(baseArea, hudStripH, readoutPlan.bottomAvoidDp)
    val perRow = readoutPlan.perRow

    // ---- 拖拽态。位移只在 draw 阶段与 ghost 的 offset 里用，所以是快照状态但只在绘制期读
    var dragEntry by remember { mutableStateOf<HudEntry?>(null) }
    var dragZone by remember { mutableStateOf<HudZone?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragShift by remember { mutableStateOf(Offset.Zero) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var ghostSize by remember { mutableStateOf(IntSize.Zero) }

    /** 窗口坐标 → 安全区局部坐标（位置表存的就是这一套，与录制页 [HudAreaDp] 同一参考） */
    fun local(rect: IntRect?): IntRect? = rect?.let {
        IntRect(it.left - originXPx, it.top - originYPx, it.right - originXPx, it.bottom - originYPx)
    }

    fun toDpRect(rect: IntRect?): HudRectDp? = local(rect)?.let {
        HudRectDp(
            pxToDp(it.left.toFloat(), density.density),
            pxToDp(it.top.toFloat(), density.density),
            pxToDp(it.right.toFloat(), density.density),
            pxToDp(it.bottom.toFloat(), density.density)
        )
    }

    /**
     * 每次都现算（不是组合期算好的一份快照）：拖拽处理器里的 `pointerInput` 闭包捕获的是**创建那帧**
     * 的实例，容器一挪、条目一改，快照就过期了，命中判定会指着老位置。
     */
    fun zoneRectsDp(): Map<HudZone, HudRectDp> = buildMap {
        HudZone.ALL.forEach { z -> toDpRect(zoneRects[z])?.let { put(z, it) } }
    }
    val overlaps = overlappingZones(zoneRectsDp())

    /** 落点格位：目标容器 + 第几格，两条判据都是 [HudLayout] 里的纯函数 */
    fun dropEntry() {
        val entry = dragEntry ?: return
        val pointer = HudPointDp(pxToDp(dragPointer.x, density.density), pxToDp(dragPointer.y, density.density))
        val target = zoneAt(pointer, zoneRectsDp()) ?: draft.sourceZoneOf(entry)
        val ordered = draft.visibleOrderOf(target, visibleEntries).filter { it != entry }
        val rects = ordered.mapNotNull { toDpRect(entryRects[it]) }
        draft = draft.moveEntryTo(entry, target, dropIndexFor(target.axis, rects, pointer))
    }

    /**
     * 整枚容器落位：当前实测左上角 + 累计位移 → 绝对 dp → 钳进安全区。
     *
     * **底栏只有 y 进得了表**：[clampZonePos] 把它的 x 强制抹回哨兵，松手后那枚 Dock 就弹回可视中心
     * （跟手位移只进 graphicsLayer，所以回弹是看得见的）。横向确实拖过一把时再补一句提示，
     * 别让用户以为"没生效是 bug"——判据取"横位移比竖位移大"，也就是他明显想横着搬，而不是随手一拖。
     */
    fun dropContainer(zone: HudZone) {
        val rect = local(zoneRects[zone]) ?: return
        val area = if (zone == HudZone.RIGHT) rightArea else baseArea
        val w = pxToDp(rect.width.toFloat(), density.density)
        val h = pxToDp(rect.height.toFloat(), density.density)
        val x = pxToDp(rect.left.toFloat(), density.density) + pxToDp(dragShift.x, density.density)
        val y = pxToDp(rect.top.toFloat(), density.density) + pxToDp(dragShift.y, density.density)
        val clamped = clampZonePos(zone, x, y, w, h, area)
        draft = draft.withZonePos(zone, clamped.xDp, clamped.yDp)
        if (zone == HudZone.BOTTOM && abs(dragShift.x) > abs(dragShift.y)) {
            hint = context.getString(R.string.hud_edit_bottom_x_locked)
        }
    }

    // 「镜头」那颗在底栏占不占右槽，判据与录制页同一条（设置开关 + 归属容器），见 HudBottomZone 的宽度账
    val lensEntry = HudEntry.of(CamPill.LENS)

    /** 取位移的 getter：交给 [HudZoneBox] 在 graphicsLayer 块里读，拖动期间不重组整页 */
    fun shiftXOf(zone: HudZone): () -> Float = { if (dragZone == zone) dragShift.x else 0f }
    fun shiftYOf(zone: HudZone): () -> Float = { if (dragZone == zone) dragShift.y else 0f }

    // 「腰」的绘制材料：组合期各 remember 一次，每帧只 reset + 追加（审查 S3-4 那条纪律）
    val link = remember { Path() }
    val linkEdge = remember(density) {
        Stroke(width = with(density) { WotaStroke.hairline.toPx() }, cap = StrokeCap.Round)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(WotaColor.bg)
    ) {
        // ---- 操作栏（它的实测高就是本层给钳制用的顶栏避让量）
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(start = WotaSpace.s, end = WotaSpace.s, top = TopTopPad, bottom = WotaSpace.xs)
                .onSizeChanged {
                    val h = with(density) { it.height.toDp().value.roundToInt() }
                    if (chromeH != h) chromeH = h
                },
            verticalArrangement = Arrangement.spacedBy(WotaSpace.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WotaSpace.s)
            ) {
                WotaChip(
                    label = stringResource(R.string.hud_edit_back),
                    selected = false,
                    // 未保存就退出 = 整批丢弃草稿（prefs 一个字节都没写，录制页不受影响）
                    onClick = onBack
                )
                WotaChip(
                    label = stringResource(R.string.hud_edit_save),
                    // 有未保存改动时这颗亮着，让"改了还没落盘"在视觉上有落点
                    selected = draft != saved,
                    onClick = {
                        // commit() 的返回值就是"这次真落盘了吗"：没落成不许说"已保存"
                        val ok = WotaSettings.setHudLayout(prefs, draft)
                        saved = draft
                        undoRaw = null
                        resetArmed = false
                        hint = if (ok) {
                            context.getString(R.string.hud_edit_saved)
                        } else {
                            context.getString(R.string.hud_edit_save_failed)
                        }
                    }
                )
                WotaChip(
                    label = stringResource(
                        if (resetArmed) R.string.hud_edit_reset_confirm else R.string.hud_edit_reset
                    ),
                    selected = resetArmed,
                    onClick = {
                        if (!resetArmed) {
                            resetArmed = true
                            hint = context.getString(R.string.hud_edit_reset_arm_tip)
                        } else {
                            // 重置 = 删键（缺键就是默认表）；「撤销」靠的是把重置前那一份串原样写回来
                            undoRaw = WotaSettings.hudLayout(prefs).encode()
                            val ok = WotaSettings.clearHudLayout(prefs)
                            draft = HudLayoutTable.default()
                            saved = draft
                            resetArmed = false
                            hint = if (ok) {
                                context.getString(R.string.hud_edit_reset_done)
                            } else {
                                context.getString(R.string.hud_edit_save_failed)
                            }
                        }
                    }
                )
                undoRaw?.let { raw ->
                    WotaChip(
                        label = stringResource(R.string.hud_edit_undo),
                        selected = false,
                        onClick = {
                            val back = HudLayoutTable.decode(raw)
                            val ok = WotaSettings.setHudLayout(prefs, back)
                            draft = back
                            saved = back
                            undoRaw = null
                            hint = if (ok) {
                                context.getString(R.string.hud_edit_undo_done)
                            } else {
                                context.getString(R.string.hud_edit_save_failed)
                            }
                        }
                    )
                }
            }
            Text(
                text = stringResource(R.string.hud_edit_note),
                style = WotaType.caption,
                color = WotaColor.textLo
            )
            // 重叠只提示不禁止：把叠在一起的两枚容器点名，别静默
            if (overlaps.isNotEmpty()) {
                Text(
                    // 名称这条 joinToString 的 lambda 不是 composable 上下文，所以标签走 context.getString
                    //（真源仍是那五条资源，见 zoneLabelRes）
                    text = overlaps.joinToString("；") { (a, b) ->
                        context.getString(R.string.hud_edit_overlap_pair, context.getString(zoneLabelRes(a)), context.getString(zoneLabelRes(b)))
                    },
                    style = WotaType.caption,
                    color = WotaColor.warn
                )
            }
            hint?.let {
                Text(text = it, style = WotaType.caption, color = WotaColor.accent)
            }
        }

        // ---- 安全区：位置表 (x, y) 的坐标参考，与录制页同一条（套 safeDrawing 之后那块）
        Box(
            Modifier
                .matchParentSize()
                .safeDrawingPadding()
                .onSizeChanged {
                    val w = with(density) { it.width.toDp().value.roundToInt() }
                    val h = with(density) { it.height.toDp().value.roundToInt() }
                    if (w != safeW) safeW = w
                    if (h != safeH) safeH = h
                }
                .onGloballyPositioned { coords ->
                    val p = coords.positionInWindow()
                    val nx = p.x.toInt()
                    val ny = p.y.toInt()
                    if (nx != originXPx) originXPx = nx
                    if (ny != originYPx) originYPx = ny
                }
        ) {
            // 一层极淡的描边把"可放范围"画出来，用的还是 wotaCard 那套令牌，不新造观感值
            Box(Modifier.matchParentSize().wotaCard(WotaShape.card))
            val ctx = editorCtx(
                prefs = prefs,
                params = params,
                freeMb = freeMb,
                perRow = perRow,
                entryRects = entryRects,
                capacityRoomDp = (safeW - 66f).coerceAtLeast(1f),
                hiddenEntry = dragEntry
            )
            HudZoneBox(
                zone = HudZone.TOP,
                placement = placementOf(draft, HudZone.TOP, zoneRects, density, baseArea),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.TOP),
                shiftYPx = shiftYOf(HudZone.TOP),
                onCardRect = { if (zoneRects[HudZone.TOP] != it) zoneRects[HudZone.TOP] = it }
            ) { HudTopZone(draft.visibleOrderOf(HudZone.TOP, visibleEntries), ctx) }
            HudZoneBox(
                zone = HudZone.LEFT,
                placement = placementOf(draft, HudZone.LEFT, zoneRects, density, baseArea),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.LEFT),
                shiftYPx = shiftYOf(HudZone.LEFT),
                onCardRect = { if (zoneRects[HudZone.LEFT] != it) zoneRects[HudZone.LEFT] = it }
            ) {
                HudDockZone(
                    HudZone.LEFT,
                    draft.visibleOrderOf(HudZone.LEFT, visibleEntries),
                    ctx,
                    zoneBandHeight(HudZone.LEFT, baseArea)
                )
            }
            HudZoneBox(
                zone = HudZone.RIGHT,
                placement = placementOf(draft, HudZone.RIGHT, zoneRects, density, rightArea),
                area = rightArea,
                shiftXPx = shiftXOf(HudZone.RIGHT),
                shiftYPx = shiftYOf(HudZone.RIGHT),
                onCardRect = { if (zoneRects[HudZone.RIGHT] != it) zoneRects[HudZone.RIGHT] = it }
            ) {
                HudDockZone(
                    HudZone.RIGHT,
                    draft.visibleOrderOf(HudZone.RIGHT, visibleEntries),
                    ctx,
                    zoneBandHeight(HudZone.RIGHT, rightArea)
                )
            }
            HudZoneBox(
                zone = HudZone.READOUT,
                placement = placementOf(draft, HudZone.READOUT, zoneRects, density, readoutArea),
                area = readoutArea,
                shiftXPx = shiftXOf(HudZone.READOUT),
                shiftYPx = shiftYOf(HudZone.READOUT),
                onCardRect = { if (zoneRects[HudZone.READOUT] != it) zoneRects[HudZone.READOUT] = it }
            ) {
                HudReadoutZone(
                    draft.visibleOrderOf(HudZone.READOUT, visibleEntries),
                    ctx,
                    zoneBandHeight(HudZone.READOUT, readoutArea)
                )
            }
            HudZoneBox(
                zone = HudZone.BOTTOM,
                placement = placementOf(draft, HudZone.BOTTOM, zoneRects, density, baseArea),
                area = baseArea,
                shiftXPx = shiftXOf(HudZone.BOTTOM),
                shiftYPx = shiftYOf(HudZone.BOTTOM),
                onCardRect = { if (zoneRects[HudZone.BOTTOM] != it) zoneRects[HudZone.BOTTOM] = it }
            ) {
                // drag = null：长按换栏是**录制页**的手势，这页改 y 用整枚拖动。
                // showLens 判据与录制页同一条：既看设置开关，也看那颗归属哪枚容器（挪走了这里就不占槽）
                HudBottomZone(
                    showLens = lensEntry in visibleEntries &&
                        draft.sourceZoneOf(lensEntry) == HudZone.BOTTOM,
                    ctx = ctx,
                    drag = null
                )
            }

            // ---- 「腰」+ ghost + 捕获层（z 序在容器之上，所以这页条目的点按拿不到事件）
            val dragged = dragEntry
            val sourceLocal = local(dragged?.let { entryRects[it] })
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()
                        val src = sourceLocal ?: return@drawWithContent
                        // draw 阶段读 dragStart/dragShift/ghostSize 这几个快照状态 ⇒ 只让这一层重画，
                        // 不重组整页（这是本文件唯一每帧被读的状态，其余都在松手之后才写）
                        val p = dragStart + dragShift
                        val rA = minOf(src.width, src.height) / 2f
                        val rB = minOf(ghostSize.width, ghostSize.height) / 2f
                        if (rA <= 0f || rB <= 0f) return@drawWithContent
                        link.reset()
                        val drew = link.appendLiquidLink(
                            src.left + src.width / 2f,
                            src.top + src.height / 2f,
                            rA,
                            p.x,
                            p.y,
                            rB
                        )
                        if (!drew) return@drawWithContent
                        drawPath(link, WotaColor.hudScrim)
                        drawPath(link, WotaColor.acrylicBorder, style = linkEdge)
                    }
            ) {
                if (dragged != null && sourceLocal != null) {
                    // ghost 那份不报锚点：条目矩形由原位那一份报，ghost 再报一次会让落点算式读到漂移的值
                    val ghostCtx = ctx.copy(anchorOf = { Modifier })
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .onSizeChanged { ghostSize = it }
                            .offset {
                                val p = dragStart + dragShift
                                IntOffset(
                                    (p.x - ghostSize.width / 2f).roundToInt(),
                                    (p.y - ghostSize.height / 2f).roundToInt()
                                )
                            }
                    ) {
                        // ghost 与原位那一颗必须同档位（#70 B）：从竖 Dock 拖出来的那颗在容器内是紧凑档，
                        // ghost 若走全局档会长一号，"跟手的比洞大"看着就是漂移。归属容器由表说了算
                        HudEntryItem(dragged, ghostCtx, tier = chipTierFor(draft.sourceZoneOf(dragged)))
                    }
                }
            }
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                dragStart = pos
                                dragPointer = pos
                                dragShift = Offset.Zero
                                val hit = entryRects.keys.firstOrNull { key ->
                                    local(entryRects[key])?.containsLocal(pos.x, pos.y) == true
                                }
                                if (hit != null) {
                                    dragEntry = hit
                                    dragZone = null
                                } else {
                                    dragZone = zoneAt(
                                        HudPointDp(
                                            pxToDp(pos.x, density.density),
                                            pxToDp(pos.y, density.density)
                                        ),
                                        zoneRectsDp()
                                    )
                                    dragEntry = null
                                }
                            },
                            onDrag = { change, amount ->
                                // foundation 1.5.4 的 detectDragGestures 不给 onDragEnd 终点坐标，
                                // 所以把最后一个 change 的位置留住，松手时按它算落点
                                dragPointer = change.position
                                dragShift += amount
                                change.consume()
                            },
                            onDragEnd = {
                                if (dragEntry != null) dropEntry() else dragZone?.let { dropContainer(it) }
                                dragEntry = null
                                dragZone = null
                                dragShift = Offset.Zero
                            },
                            onDragCancel = {
                                dragEntry = null
                                dragZone = null
                                dragShift = Offset.Zero
                            }
                        )
                    }
            )
        }
    }
}

/** 点在矩形内（安全区局部坐标，右/下是开区间，与 [HudRectDp.contains] 同一口径） */
private fun IntRect.containsLocal(x: Float, y: Float): Boolean =
    x >= left && x < right && y >= top && y < bottom

/** 五枚容器的中文名资源 id（重叠提示用，别露出 T/L/R/D/B 那种持久化 id） */
@androidx.annotation.StringRes
private fun zoneLabelRes(zone: HudZone): Int = when (zone) {
    HudZone.TOP -> R.string.hud_zone_top
    HudZone.LEFT -> R.string.hud_zone_left
    HudZone.RIGHT -> R.string.hud_zone_right
    HudZone.READOUT -> R.string.hud_zone_readout
    HudZone.BOTTOM -> R.string.hud_zone_bottom
}

/**
 * 表里的位置 → 这一帧用的位置。与录制页那段**同一条算式**（哨兵原样返回、拖过的先钳），
 * 抽出来只为了两个页面不各写一份钳制调用。
 */
private fun placementOf(
    table: HudLayoutTable,
    zone: HudZone,
    rects: Map<HudZone, IntRect>,
    density: Density,
    area: HudAreaDp
): ZonePlacement {
    val raw = table.posOf(zone)
    if (raw.isDefault) return raw
    val rect = rects[zone]
    val w = rect.dpWidthToDp(density)
    val h = rect.dpHeightToDp(density)
    return clampZonePos(zone, raw.xDp, raw.yDp, w, h, area)
}

/**
 * 编辑页读数底稿：把开机默认参数**一次**读全。
 *
 * 这条刻意不是 @Composable：这页的 [WotaParams] 是自己 new 的、且没有任何写入方（条目动作全是空实现），
 * 值不会变，所以既不需要挂订阅，也不该在组合期读 `StateFlow.value`
 * （lint 的 StateFlowValueCalledInComposition 拦的就是这个；为过 lint 而给这五个值挂订阅同样是错的）。
 */
private data class EditorReadout(
    val size: Size,
    val zoomLabel: String,
    val fpsLabel: String,
    val bitrateLabel: String,
    val bitrateBps: Int
)

private fun editorReadoutOf(params: WotaParams): EditorReadout = EditorReadout(
    size = params.size.value,
    zoomLabel = String.format(java.util.Locale.US, "%.1fx", params.zoom.value.value),
    fpsLabel = "${params.fps.value.value}",
    bitrateBps = params.bitrate.value,
    bitrateLabel = "${params.bitrate.value / 1_000_000}M"
)

/**
 * 编辑页的 [HudCtx]：读数取开机默认参数 + 真实剩余空间，动作一律空实现（捕获层在上面，这页点不到条目）。
 *
 * [HudCtx.anchorOf] 这一路在这页改写成「条目矩形回报」：就近浮层在这页不弹，但落点格位与 ghost 半径
 * 都要每颗条目的实测矩形，而 [HudEntryItem] 只认 `ctx.anchorOf` 这一处挂点 —— 所以复用它，
 * 不另开一条回报路（两条路迟早一边写了另一边没写）。
 *
 * 容量段按**满档**取（[capacityRoomDp] 给的是宽裕值）：这页没有录制页那 66dp 的固定预留可量，
 * 摆位置要看的是"这一段最多占多宽"，§74 三档里满档就是最宽的那一档，按最宽的摆不会挤。
 */
@Composable
private fun editorCtx(
    prefs: SharedPreferences,
    params: WotaParams,
    freeMb: Long,
    perRow: Int,
    entryRects: MutableMap<HudEntry, IntRect>,
    capacityRoomDp: Float,
    hiddenEntry: HudEntry?
): HudCtx {
    val d = remember(params) { editorReadoutOf(params) }
    val size: Size = d.size
    val zoomLabel = d.zoomLabel
    return HudCtx(
        recording = false,
        busy = false,
        recStateLabel = null,
        elapsedLabel = "00:00",
        sizeLabel = sizeText(size),
        capacityLabel = capacityTierText(
            freeMb,
            d.bitrateBps + BitratePolicy.AUDIO_BITRATE,
            capacityRoomDp
        ),
        freeLow = freeMb < WotaTiers.MIN_FREE_MB,
        zoomLabel = zoomLabel,
        focusLabel = stringResource(R.string.pill_focus),
        stabLabel = stringResource(R.string.cam_p_stab),
        stabActive = false,
        focusActive = false,
        refLineOn = false,
        monitorLabel = stringResource(R.string.cam_p_monitor),
        monitorActive = false,
        curveOn = false,
        flash = Flash.OFF,
        lensLabel = stringResource(lensLabelRes(LensType.WIDE)),
        btConnected = false,
        btVolumePct = 0,
        levelEnabled = WotaSettings.levelEnabled(prefs),
        roll = 0f,
        pitch = 0f,
        db = MIN_DB,
        lastUri = null,
        aeLocked = false,
        readoutValue = { item ->
            // 这页不接相机：曝光三件套按"开机默认 = 自动档"摆位，与录制页首帧同形
            when (item) {
                HudItem.SHUTTER -> "AUTO"
                HudItem.ISO -> "AUTO"
                HudItem.EV -> "0.0"
                HudItem.WB -> stringResource(wbLabelRes(WbPreset.AUTO))
                HudItem.ZOOM -> zoomLabel
                HudItem.FPS -> d.fpsLabel
                HudItem.BITRATE -> d.bitrateLabel
            }
        },
        readoutPerRow = perRow,
        anchorOf = { entry ->
            Modifier.pillAnchor { rect -> if (entryRects[entry] != rect) entryRects[entry] = rect }
        },
        onSizeClick = { },
        onFreeClick = { },
        onRefLineClick = { },
        onMonitorClick = { },
        onFlashClick = { },
        onCurveClick = { },
        onZoomClick = { },
        onFocusClick = { },
        onStabClick = { },
        onBtClick = { },
        onLensCycle = { },
        onOpenLensPanel = { },
        onRecordClick = { },
        onThumbClick = { },
        onReadoutCycle = { },
        onReadoutOpen = { },
        hiddenEntry = hiddenEntry
    )
}
