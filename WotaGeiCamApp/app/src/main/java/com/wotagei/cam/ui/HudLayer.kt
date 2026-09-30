package com.wotagei.cam.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.wotagei.cam.R
import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FrameEffect
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.RenderMode
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.media.VideoThumbnail
import com.wotagei.cam.ui.anim.LiquidMerge
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.anim.MergeDebugHook
import com.wotagei.cam.ui.anim.MergeScene
import com.wotagei.cam.ui.anim.MergeSlot
import com.wotagei.cam.ui.anim.chipClicksAccepted
import com.wotagei.cam.ui.anim.chipTravelForScene
import com.wotagei.cam.ui.anim.dragOwnedByRecordKey
import com.wotagei.cam.ui.anim.mergeAnchor
import com.wotagei.cam.ui.anim.mergePlanFor
import com.wotagei.cam.ui.anim.mergeProgressWithHook
import com.wotagei.cam.ui.anim.wotaDockShell
import com.wotagei.cam.ui.anim.wotaPillHost
import com.wotagei.cam.ui.design.HudFrostCard
import com.wotagei.cam.ui.design.HudInkLevel
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaChipTier
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaDockChip
import com.wotagei.cam.ui.design.WotaHit
import com.wotagei.cam.ui.design.WotaIconButton
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.design.hudFrostInherit
import com.wotagei.cam.ui.design.hudFrostPlate
import com.wotagei.cam.ui.design.pillAnchor
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.design.wotaHudCard
import com.wotagei.cam.ui.dialog.flashLabelRes
import com.wotagei.cam.ui.dialog.wbLabelRes
import com.wotagei.cam.ui.widget.AttitudeCard
import com.wotagei.cam.ui.widget.BtChip
import kotlin.math.roundToInt

/**
 * 取景 HUD 的**唯一一条布局路**（docs/plan/13 第 5 条 + 用户定的两级模型）。
 *
 * 录制页与「编辑控件」页都从这里渲染五枚容器（[HudZone]）与其中的条目（[HudEntry]），
 * 所以「编辑页画了一份假布局、两边分叉」在结构上不可能发生：两页给的都是同一批 composable +
 * 同一份 [HudCtx]，差别只在**数据源**（编辑页读出厂默认参数，录制页读实时参数总线）与
 * **手势**（编辑页是拖拽，录制页是点按/长按）。
 *
 * 位置怎么来（[HudZoneBox]）：
 * - 表里 [ZonePlacement.isDefault]（两轴都是哨兵）→ 走 B1–B3 定稿的那条**原生对齐**分支
 *   （`CenterStart + padding(start = 8dp)`、`CenterEnd + padding(end = 8dp)`、
 *   `BottomEnd + padding(bottom = 读数块那一条带的底边)`（#70 A 之后与底栏**同一行**，那一档送下来的
 *   让位＝共用基线减掉块自己的内边距，见 [com.wotagei.cam.ui.ReadoutRowPlan]）、
 *   `BottomCenter + fillMaxWidth` 居中），
 *   8dp 是 [HudEdgePad] 那枚设计留白，**避让量全是组合期实测回报值**，
 *   贴挖孔/系统栏那一层由调用方的 `safeDrawingPadding()` 承担，本层没有按方向写死的避让常量；
 * - 用户拖过 → 走 `align(TopStart) + offset(x, y)` 的绝对定位，坐标相对 root 安全区的左上角，
 *   写进去之前先经 [clampZonePos] 钳进安全区。**底栏是唯一的例外**：它只认 y，x 永远是哨兵，
 *   所以它的绝对落位是 `fillMaxWidth` + 居中 + `offset(y)`，横向仍跟着可视窗口中心走。
 *
 * 锚点：绝对定位用的是**布局偏移**（`Modifier.offset`），于是 `Modifier.pillAnchor` 回报的窗口矩形
 * 天然跟着视觉位置走（§69 那族「浮层甩到屏幕原点」的另一半成因就是位置改了锚点没改）。
 * 编辑页拖动过程中走 [graphicsLayer] 位移（不动布局参数，四条红线之一），松手那一帧才写 offset ——
 * 一次重排，不是动画；录制页也从不在拖动手势里开浮层。
 *
 * 容器**内**的位置（任务 #74）：[HudZone.LEFT] / [HudZone.RIGHT] / [HudZone.READOUT] 三枚走
 * **固定格子**（[HudEntryGrid]，条目坐标只由 [GridCell] 与实测格长决定，见 [gridPitchPx]），
 * 顶栏与底栏仍按 [HudLayoutTable.visibleOrderOf] 的顺序紧凑排布——为什么那两枚不能上网格，
 * 逐条写在 [HudZone.isGrid] 的注释里。**每颗的锚点仍然挂在那颗自己的节点上**（[HudEntryItem] 里
 * `ctx.anchorOf(entry)` 这一处），换成格子定位没有动这条结构闸。
 */

/** 底栏那排自己的上下外边距（底板之外的 padding，同时进 [BottomBarSpaceFallback] 的算式） */
internal val BottomBarOuterPadV = 6.dp

/** 底板内、录制键之外的上下内边距（[HudBottomZone] 内层底板的 padding，同时进 [BottomBarSpaceFallback]） */
internal val DockInnerPadV = 5.dp

/**
 * 底栏带高的**首帧兜底值**（S2-2 C）：真值由底板自己回报给调用方，左右竖 Dock 与常驻读数块的下边界读那条状态。
 *
 * 不是"量出来的经验值"：录制键 [WotaHit.recordTouch] + 底板上下内边距 5×2 + 外边距 6×2 = **72dp**，
 * 三项都是这排实际用的布局输入（上面两个常量就是那两处 padding）。
 * （声明顺序有讲究：文件级属性按声明顺序初始化，被引用的两个 padding 必须排在前面。）
 */
internal val BottomBarSpaceFallback = WotaHit.recordTouch + DockInnerPadV * 2 + BottomBarOuterPadV * 2

/**
 * 贴边避让**一律交给系统**（AGENTS.md 铁律）：五枚容器都在调用方那层 `safeDrawingPadding()` 的盒子里，
 * 挖孔 / 状态栏 / 导航条由那一层按它实际所在的边自动让开，本层不再有任何按方向写死的避让常量。
 *
 * 历史（这里曾有 `VisibleEndInset = 34.dp`）：§58 把"可视右缘只到 1532"当承重事实，按方向写死了一笔
 * 右缘让位。2026-09-29 真机复测把它推翻了（docs/plan/13 §九·补）——那 68px 是**竖屏顶边居中的 64×68 挖孔**
 * （`insets=Rect(0,68-0,0)`、`boundingRect=Rect(328,0-392,68)`），竖屏让上边、`ROTATION_90` 让左边、
 * `ROTATION_270` 让右边，而 `safeDrawingPadding()` 在本机横屏实测给**左 68 / 右 0**（不是 §58 说的
 * "两边都给 0"）；"1532"本身是 uiautomator 把 app-bounds 的**宽度**当成**右边界**得到的伪值。
 * 同一枚写死方向的 `padding(end=)` 在两个横屏姿态里必错一次，所以常量已删净，不许再写第二份。
 *
 * 本层剩下的边距只有**设计留白**：右竖 Dock 与右下读数块各让一枚 [HudEdgePad]（8dp 令牌，与左竖 Dock
 * 的起始边同一枚，见 [HudZoneBox] 的原生对齐分支）。它与系统避让无关、不分方向、也不分姿态。
 */

/**
 * 顶栏避让量的**首帧兜底值**：只到第一行那排（38dp 圆形设置钮 + 上下内边距 4+2 = 44dp）。
 * 真值由 [HudTopChrome] 那层 `onSizeChanged` 回报：第二行还有能力/权限告警条约 22dp
 * （DIRECT + 斑马纹、缺麦克风、LEGACY 都会触发，是常见组合），只让 44dp 时竖 Dock 顶颗会压在它上面。
 * 这里只是量到之前的占位，别再往这里加第二段常量。
 */
internal val TopBarSpace = 44.dp

/**
 * 贴边那两枚自由边的**设计留白**（8dp 令牌，不是避让常量）：顶栏 `padding(start)`、左竖 Dock
 * `padding(start)`、右竖 Dock 与右下读数块 `padding(end)` 用的都是它，左右两边因此对称。
 * 系统避让（挖孔 / 系统栏）不在这里，由调用方那层 `safeDrawingPadding()` 承担（见文件头那条说明）。
 */
internal val HudEdgePad = WotaSpace.s

/** 顶栏胶囊组定稿的上内边距（改前是 TopBar 那行的 `padding(top = 4.dp)`） */
internal val TopTopPad = 4.dp

/** 顶栏胶囊组内部的条目间隔（改前是 TopBar 那行的 `spacedBy(6.dp)`） */
internal val TopRowGap = 6.dp

/**
 * 底栏 Dock **右槽**（镜头那颗）宽度的首帧兜底值，真值由那颗在布局期回报（见 [HudBottomZone]）。
 *
 * 宽度账（100% 字体缩放）：`WotaChip` 三字下限 = 13sp × 3 = 39dp，加左右内边距 12+12 = **63dp**；
 * 字体缩放 120% 时约 70.8dp（下限走 sp 所以跟着涨），实测值把这一档吃进来，兜底值只管第一帧——
 * §58/§73 两次"按 100% 量出来的固定宽在 120% 下不够用"就是这个坑。
 * 左槽（缩略图）不共用这个数：它有自己的 [ThumbBoxSpace]，两槽各自兜底（S2-1）。
 */
internal val DockSlotSpace = 63.dp

/**
 * 缩略图那格的边长：底栏左槽唯一的内容尺寸（HEAD 遗留的裸值 `.size(34.dp)`，S2-1 提成常量）。
 * 它同时是左槽宽度的首帧兜底——有/无素材都画在这同一枚方框里，所以素材切换不改槽宽。
 */
internal val ThumbBoxSpace = 34.dp

/**
 * 底栏里录制键的 z 序（#73 命中权交接第二刀）。
 *
 * 那两颗条目不写这个值 ⇒ 它们恒为 0f，录制键压在它们上面。绘制与命中两层都由它决定，
 * 证据与"为什么仍然不把它当唯一保证"见 [HudBottomZone] 里 RecordButton 那条注释。
 */
private const val RecordZIndex = 1f

/**
 * 底栏底板**宽度**的首帧兜底值（真值由那颗自己在布局期回报，见 [HudBottomZone] 的 `dockW`）。
 *
 * #70 A 要用它算「读数块与底栏同一行时右半还剩多宽」（`planReadoutRow` 的 `dockWidthDp` 入参）：
 * 首帧量不到底板宽，按 0 算会把右半带估得比实际宽，于是第一帧挑了一行 3 颗、第二帧实测回来才发现挤——
 * 与 [BottomBarSpaceFallback] 同一套"首帧兜底、第二帧实测接管"的手法。
 * 由 [DockSlotSpace] / [WotaHit.recordTouch] / 内边距与间距令牌（[WotaSpace.m] + [WotaSpace.s]）拼出来，
 * 算式与 [HudBottomZone] KDoc 那条「镜头开：W = 2×63 + 50 + 2×(12+8) = 216dp」是同一笔账（不是新常量）。
 * 左槽的 [ThumbBoxSpace] 不进来：底板宽取的是 `max(左, 右)` 那一档，这里按"镜头那颗占右槽"的常态估。
 */
internal val BottomDockWidthFallback = DockSlotSpace * 2 + WotaHit.recordTouch + (WotaSpace.m + WotaSpace.s) * 2

internal const val LED_COUNT = 6
internal const val MIN_DB = 20f
internal const val MAX_DB = 110f

/**
 * HUD 格 → 就近胶囊。一一对应，所以新增 [HudItem] 时这里会编译不过——
 * 故意的：逼着同时补内容宿主，避免出现「点了没反应」的格子。
 */
internal fun HudItem.pillKey(): PillKey = when (this) {
    HudItem.SHUTTER -> PillKey.SHUTTER
    HudItem.FPS -> PillKey.FPS
    HudItem.BITRATE -> PillKey.BITRATE
    HudItem.ISO -> PillKey.ISO
    HudItem.EV -> PillKey.EV
    HudItem.WB -> PillKey.WB
    HudItem.ZOOM -> PillKey.ZOOM
}

/**
 * 可编辑条目 → 就近锚点的 key（**唯一一处**这张对照表，[HudEntryItem] 挂锚点只读它）。
 *
 * 返回 null 的三类没有就近浮层，也就没有锚点：[CamPill.CURVE] 开的是整块曲线面板，
 * [CamPill.LEVEL] 与 [CamPill.VOLUME] 是**读数**不是入口（§37 第 4 条：给读数挂 onClick
 * 只会让人以为点开有配置项）。
 *
 * 这张表被 `PillAnchorRegistryTest` 用来钉「每个 [PillKey] 都必须有归属条目」：新增 PillKey
 * 忘了在这儿补分支，浮层就会按 `IntRect.Zero` 弹到屏幕原点（§69 缺陷族）。
 */
internal fun HudEntry.pillKey(): PillKey? = pill?.let { p ->
    when (p) {
        CamPill.SIZE -> PillKey.SIZE
        CamPill.STORAGE -> PillKey.STORAGE
        CamPill.LENS -> PillKey.LENS
        CamPill.ZOOM -> PillKey.ZOOM
        CamPill.FOCUS -> PillKey.FOCUS
        CamPill.STAB -> PillKey.STAB
        CamPill.BT -> PillKey.BT
        CamPill.REFLINE -> PillKey.REFLINE
        CamPill.MONITOR -> PillKey.MONITOR
        CamPill.FLASH -> PillKey.FLASH
        CamPill.CURVE -> null
        CamPill.LEVEL, CamPill.VOLUME -> null
    }
} ?: item?.pillKey()

// ------------------------------------------------------------------ 毛玻璃（#84 步骤 2）的 UI 侧控制器

/**
 * 霜的 UI 侧唯一状态机：**底板的纯色 fill 该不该让位**只看这一个值。
 *
 * 判据是 `设置开关 ∧ GL 真贴了板`（[com.wotagei.cam.camera.FrostCardTable.isPlatesDrawn]），
 * 不是"开关开着"——差别就在能不能守住「关掉要能完整回到现在的观感」这条：
 * DIRECT 模式、离屏链被坏驱动停用、相机还没出帧、窗口面被回收，这几种情况下开关可能仍是开的，
 * 但屏幕上一块板都没有。此时若让 fill 让位，卡片就变成"压在实时画面上的透明描边框"，
 * 属于 S1 级可读性事故；不让位则观感与接霜前**逐字相同**。
 *
 * ## 事件驱动，不是每帧轮询
 * - 开关本身走 `OnSharedPreferenceChangeListener`（设置页一翻就打到，零延迟，与
 *   [rememberHudLayout] 同一条手法）；
 * - 可用性只有"开着的期间"才需要盯：那是一条 200ms 的轮询（读一枚 volatile + 刷一次表头），
 *   关掉开关或宿主为 0 就 `removeCallbacks`，**不留常驻定时器**（录制页的帧率红线 08:79 吃的就是这条）。
 *   为什么不能不轮：GL 侧停用/撤报发生在渲染线程，Compose 这边没有任何 state 可订阅；
 *   引擎里也没有 share context 之类的反向通道。5Hz 读两个 volatile 的成本可以忽略，
 *   换来的是"链坏了 fill 在 200ms 内回来"而不是"永久没有底衬"。
 *
 * ## 表头为什么在这里刷
 * [refreshHeader] 送的是「组合根在窗口里的矩形 + 底板色 + 开关位」。GL 侧要把窗口坐标的卡片矩形
 * 换算成它自己的视口坐标，缺不可承载视图的原点，而那个原点由**根尺寸**（只有 View 侧量得到）与
 * **视图尺寸**（只有 GL 知道）按"`CameraScreen.previewStage` 是居中盒"反推
 * （算式与它唯一的推断量都写在 `camera/frostViewOriginInto`）。注册点写卡片矩形之前会先调这里一次，
 * 所以那一次事务里"根"与"卡片"出自同一次布局。
 */
internal object HudFrost {

    /** 轮询周期（只在开关开着时跑）：可用性翻转最多晚一个周期被看见，真机延迟未量 */
    private const val REFRESH_MS = 200L

    /** 底板 fill 让位态：只有 GL 真贴了板才 true（组合期读它，写它必须在主线程） */
    var live by mutableStateOf(false)
        private set

    /**
     * 设置开关的**意图**：开着就该往矩形表里喂卡片。
     *
     * ⚠ 这条与 [live] 必须是两个值，不能合并。曾经注册点用 `live` 当"要不要写卡片"的闸门，
     * 于是形成启动死锁：卡片要 `live` 才写 → `live` 要 GL 画过板才真 → GL 要表里有卡片才画得出。
     * 真机上的表现是开关翻了背板纹丝不动（背板区开/关两帧只有 2.0% 像素变化，
     * 而同两帧纯预览参照区自己就变了 24.5%）。分工：**[intent] 管喂表，[live] 管让位。**
     */
    var intent by mutableStateOf(false)
        private set

    private val locationInWindow = IntArray(2)
    private val tintScratch = FloatArray(3)

    private var root: View? = null
    private var prefs: SharedPreferences? = null
    private var hosts = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == WotaSettings.KEY_FROST_BLUR) evaluateEnabled()
        }

    private val tick = object : Runnable {
        override fun run() {
            if (hosts <= 0 || !intent) return
            refreshAvailability()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    /**
     * 宿主登记：五枚容器各调一次（[HudZoneBox] 里），引用计数到 0 就把定时器与监听全撤干净。
     * 编辑页复用同一批容器 composable，所以那一页也会登记一个宿主——这不是漏做：
     * 那一页的可用性同样只能由 GL 的回报说了算，没有 GL 出图就没有板，fill 不让位。
     */
    @Composable
    fun host() {
        val context = LocalContext.current
        val view = LocalView.current
        DisposableEffect(context, view) {
            attach(context.applicationContext, view)
            onDispose { detach() }
        }
    }

    private fun attach(appContext: Context, view: View) {
        val p = WotaSettings.of(appContext)
        if (hosts == 0) {
            root = view
            prefs = p
            p.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        hosts++
        evaluateEnabled()
    }

    private fun detach() {
        if (hosts <= 0) return
        hosts--
        if (hosts > 0) return
        val p = prefs
        @Suppress("DEPRECATION")
        p?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        mainHandler.removeCallbacks(tick)
        root = null
        prefs = null
        intent = false
        live = false
    }

    /** 开关意图变了：开 ⇒ 起轮询；关 ⇒ 停轮询并当场把 fill 收回来（不等下一帧） */
    private fun evaluateEnabled() {
        val p = prefs ?: return
        val next = WotaSettings.frostBlurEnabled(p)
        if (next == intent) return
        intent = next
        mainHandler.removeCallbacks(tick)
        if (next) {
            refreshAvailability()
            mainHandler.postDelayed(tick, REFRESH_MS)
        } else {
            live = false
        }
    }

    private fun refreshAvailability() {
        if (!intent) {
            if (live) live = false
            return
        }
        refreshHeader()
        val drawn = FrostCardTable.isPlatesDrawn()
        if (drawn != live) live = drawn
    }

    /**
     * 把「组合根在窗口里的矩形 + 底板色 + 开关位」写进矩形表表头（主线程）。
     *
     * 表头与卡片矩形是两次事务，`FrostCardTable` 的读方一次只认一块已发布的数组
     * （写方改私有 scratch、发布时换个 `@Volatile` 引用），所以 GL 那一侧永远读得到一整代；
     * "同一代"不等于"同一次布局"，后者靠的是注册点在写卡片前先调这里一次。
     */
    fun refreshHeader() {
        val r = root ?: return
        val p = prefs ?: return
        if (r.width <= 0 || r.height <= 0) return
        r.getLocationInWindow(locationInWindow)
        WotaColor.hudFrostTintRgb(tintScratch)
        FrostCardTable.writeHeader(
            rootLeftPx = locationInWindow[0].toFloat(),
            rootTopPx = locationInWindow[1].toFloat(),
            rootWidthPx = r.width.toFloat(),
            rootHeightPx = r.height.toFloat(),
            tintRed = tintScratch[0],
            tintGreen = tintScratch[1],
            tintBlue = tintScratch[2],
            uiEnabled = intent
        )
    }
}

/**
 * 「外面那层已经是一块玻璃了」的 ambient（#84 步骤 2）。
 *
 * 为什么走 CompositionLocal 而不是形参：五枚容器的 composable 由 `CameraScreen` 与「编辑控件」页
 * 共同调用，而那两处本轮都不许改——新增形参只能带默认值，带默认值就等于没人传，功能直接变死代码。
 * ambient 的默认值是 null（= 不在任何板上），只有 [HudDockZone] / [HudBottomZone] 在自己的内容外面
 * provide 一份 [hudFrostInherit]，里面的条目于是自动变成"吃父板的玻璃、不重复注册"。
 * 这张表也**不是**容器归属的真源：谁在哪枚容器里仍旧只由 `pillAnchorWriters` 与位置表说了算。
 */
internal val LocalHudFrostParent = compositionLocalOf<HudFrostCard?> { null }

/** 条目该用的霜身份：父板存在就吃父板，否则自己出一块板（胶囊/圆形 = 短边一半，送负数哨兵） */
internal fun hudFrostForEntry(parent: HudFrostCard?): HudFrostCard? =
    parent ?: hudFrostPlate(radiusPx = -1f, ink = HudInkLevel.SECONDARY)

/**
 * 胶囊族共用的那一份板身份（顶栏元信息壳、顶栏录制那颗、条目自己出板时都是它）。
 *
 * 提成 `val` 而不是在每处调用点现造：`HudFrostCard` 是 data class，现造就意味着每次重组一次分配，
 * 而这一枚的值永远一样（半径 = 短边一半的哨兵 + 承载 textMid 级着色的那一档）。
 */
internal val hudFrostPillPlate: HudFrostCard = hudFrostPlate(radiusPx = -1f, ink = HudInkLevel.SECONDARY)

// ------------------------------------------------------------------ 一帧 HUD 的全部输入

/**
 * 一帧 HUD 的全部输入：每颗控件的读数、开关态与动作。
 *
 * 每次重组**新建一份**（不 remember），所以值变了下面的渲染一定跟着变；两个页面各造一份的成本
 * 就是多写几行赋值，换来的是「渲染只有一条路」。
 *
 * [anchorOf] 无默认值（漏写就是编译错误），这是 §69 那条「控件有触发点但没人写锚点」的结构闸；
 * 条目 → [PillKey] 的对应只在 [HudEntry.pillKey] 一处。
 */
data class HudCtx(
    // ---- 顶栏：录制态那颗 + 元信息两段的读数
    val recording: Boolean,
    val busy: Boolean,
    val recStateLabel: String?,
    val elapsedLabel: String,
    val sizeLabel: String,
    val capacityLabel: String,
    val freeLow: Boolean,
    // ---- 各颗控件自己的标签与状态
    val zoomLabel: String,
    val focusLabel: String,
    val stabLabel: String,
    val stabActive: Boolean,
    val focusActive: Boolean,
    val refLineOn: Boolean,
    val monitorLabel: String,
    val monitorActive: Boolean,
    val curveOn: Boolean,
    val flash: Flash,
    val lensLabel: String,
    val btConnected: Boolean,
    val btVolumePct: Int,
    val levelEnabled: Boolean,
    val roll: Float,
    val pitch: Float,
    val db: Float,
    val lastUri: Uri?,
    val aeLocked: Boolean,
    /** 读数格的当前值；返回 null = 这一格此刻不适用（如 AE 手动档下的 EV），淡出、不占位 */
    val readoutValue: @Composable (HudItem) -> String?,
    /**
     * **网格节点的锚点挂点**（任务 #74，编辑页的格子吸附只有这一条数据来源）。
     *
     * 与 [anchorOf] 同一条纪律：**没有默认值**（#69 铁律）。它挂在 [HudEntryGrid] 那个 `Layout` 节点上，
     * 回报的是"格网原点 + 网格实测尺寸"，编辑页据此反解格长（[gridPitchPx] 的逆算）。
     * 给默认值 `Modifier` 的话，编辑页漏挂就静默退化成"量不到格子 ⇒ 只换顺序"，
     * 而那正是本次要修的"拖一颗动一片"的老行为，测试里也照不出漏挂。
     * 录制页不需要格子吸附，传 `{ Modifier }`（零成本：那条链上一个节点都不加）。
     */
    val gridOf: (HudZone) -> Modifier,
    /**
     * **每颗条目实测高的回报处**（任务 #75：行跨度的唯一数据来源，[HudEntryGrid] 布局期写进来）。
     *
     * 与 [gridOf] / [anchorOf] 同一条纪律：**没有默认值**（#69 铁律）。高条目吃掉几档行距是由
     * 实测高 ÷ 格距现算的（[com.wotagei.cam.ui.cellRowSpan]），量不到就退回"人人都只占一档"——
     * 而那正是本次要修的那个"行距被最高那颗顶高"的形态（换了一种走法而已），所以漏挂必须编译不过。
     *
     * 两页都给**自己那一份** `mutableStateMapOf`：写方只有 [HudEntryGrid] 一处（量到的那一帧、值真变了
     * 才写），读方是 [com.wotagei.cam.ui.HudGridPlan.cellHeightOf]（组合期读 ⇒ 挂上订阅，
     * 量到之后下一帧跨度接管，与 `dockStripH` / [gridOf] 同一套"两轮收敛"手法）。
     * 顶栏与底栏不进网格，它们那几颗永远不出现在这张表里（不是漏做）。
     */
    val entryHeights: MutableMap<HudEntry, Int>,
    // ---- 锚点与动作
    val anchorOf: (HudEntry) -> Modifier,
    val onSizeClick: () -> Unit,
    val onFreeClick: () -> Unit,
    val onRefLineClick: () -> Unit,
    val onMonitorClick: () -> Unit,
    val onFlashClick: () -> Unit,
    val onCurveClick: () -> Unit,
    val onZoomClick: () -> Unit,
    val onFocusClick: () -> Unit,
    val onStabClick: () -> Unit,
    val onBtClick: () -> Unit,
    val onLensCycle: () -> Unit,
    val onOpenLensPanel: () -> Unit,
    val onRecordClick: () -> Unit,
    val onThumbClick: () -> Unit,
    val onReadoutCycle: (HudItem) -> Unit,
    val onReadoutOpen: (HudItem) -> Unit,
    /**
     * 编辑页正被拖起来的那一颗：原位只留**空壳**（alpha 0，几何与占位不变所以容器不重排），
     * 跟着手指的那一份与「腰」由编辑页上层画。录制页恒为 null。
     */
    val hiddenEntry: HudEntry? = null
)

// ------------------------------------------------------------------ 单颗条目（五枚容器共用）

/**
 * 编辑页拖拽时给"被拖起的那一颗"的空壳 modifier：只进 [graphicsLayer] 的 alpha，
 * 不新增布局节点，所以录制页（hiddenEntry 恒 null）一分钱成本都不付。
 * 写成 `Modifier` 扩展而不是普通工厂函数（lint 的 ModifierFactoryExtensionFunction）。
 */
private fun Modifier.ghostWhileDragged(dragged: Boolean): Modifier =
    if (dragged) graphicsLayer { alpha = 0f } else this

/** 有 AUTO 档的几项：只有它们"切到手动才变蓝"，帧率/码率常年蓝着等于没有强调 */
private val HUD_AUTO_ITEMS = setOf(HudItem.SHUTTER, HudItem.ISO, HudItem.EV, HudItem.WB)

/**
 * **唯一一处**「哪枚容器的胶囊走紧凑档」的裁决（任务 #70 B）。五枚容器里只有两枚竖 Dock 收窄：
 *
 * - [HudZone.LEFT] / [HudZone.RIGHT] → [WotaChipTier.Dock]：底板宽由最宽那颗撑出来，条目一窄底板跟着窄；
 * - [HudZone.BOTTOM] → [WotaChipTier.Standard]：**必须留全局档**。底栏那颗镜头胶囊的宽就是
 *   `DockSlotSpace = 63dp` 那笔槽宽账的来源，而槽宽是底板 `dockWidthPx` 的输入、#71 收拢算式 `w(0)`
 *   的起点（docs/plan/13 §14.3）——收它等于顺手改 #71；
 * - [HudZone.READOUT] → [WotaChipTier.Standard]：**也必须留全局档**。
 *   `hudPerRowFor` 里「一颗读数 = 39 + 24 + 5 + 22 = 90dp」那串常数（`ChipPaddingDp = 24`）
 *   量的就是全局档，换档等于让换行算式与真实块宽分叉，那是 §58/§73 那一族"按估宽排版然后裁字"的成因；
 * - [HudZone.TOP] → 全局档：顶栏容量段那笔窄屏阈值（272/242）按它量的。
 *
 * 姿态仪、音量表、蓝牙那三颗自绘件不走 [WotaChip]，所以这条裁决对它们没有作用（也就不参与这笔宽度账）。
 */
internal fun chipTierFor(zone: HudZone): WotaChipTier =
    if (zone == HudZone.LEFT || zone == HudZone.RIGHT) WotaChipTier.Dock else WotaChipTier.Standard

/**
 * 条目渲染里那条"按档位选入口"的路：同一个实现、两档内剂量（[WotaChip] / [WotaDockChip]）。
 * 写成一条私有 composable 而不是在六条 when 分支里各写一遍 `if (tier == …)`，是为了让"哪些胶囊可能被收窄"
 * 这件事在本文件里只有一个落点。读数那颗（`entry.item` 那条分支）永远走全局档，见 [chipTierFor] 的注释。
 *
 * [onClick] 可以为 null：那是 #73 的命中权交接在起作用（吸收期那两颗**不装点击链**），
 * 而 [WotaChip] 本来就有"`onClick == null` 就不挂 clickable"这一条，
 * 所以断链落在它已有的结构上，不需要在外面再套一层 `pointerInput` 打补丁。
 */
@Composable
private fun TierChip(
    tier: WotaChipTier,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    frost: HudFrostCard?,
    onLongClick: (() -> Unit)? = null
) {
    if (tier == WotaChipTier.Dock) {
        WotaDockChip(label, selected, modifier, onClick, onLongClick, frost = frost)
    } else {
        WotaChip(label, selected, modifier, onClick, onLongClick, frost = frost)
    }
}

/**
 * 「这一颗的动作要不要摘掉」（#73 命中权交接的唯一落点）。
 *
 * 返回 null 时 [WotaChip] 连 `clip + clickable` 那一段都不 install，点击链路在结构上断开——
 * 刻意不用 `clickable(enabled = false)`：那种写法节点还在，只是不回调，
 * 而本批要证的正是"越过空隙的那一指不会被这颗吃掉"，不能压在一个未证的框架行为上。
 */
private fun gatedClick(accepted: Boolean, onClick: () -> Unit): (() -> Unit)? =
    if (accepted) onClick else null

/**
 * 一颗可编辑控件的渲染：按条目取控件类型，按 [ctx] 取读数与动作。
 *
 * 三条刻意的约束：
 * - **锚点只在这一层挂**（`ctx.anchorOf(entry)`），不在调用方逐个控件挂：于是"某容器忘了给某颗挂锚点"
 *   这种错在结构上没有入口，13 号计划第 5 条要的「锚点落在视觉位置上」只有一个落点。
 *   「同一个 PillKey 有两个候选写入方」（变焦的 Dock 那颗与读数那颗）由调用方在这条 lambda 里裁决；
 * - 读数条目「值变 null 就淡出」保留 B2 的上一次值 + [AnimatedVisibility]，只淡出 + 微沉，
 *   不用 expandIn/shrinkOut（MotionSpec 不许在预览层做布局参数动画）；
 * - 点按与长按的分工照旧：竖 Dock 的胶囊点按开就近面板（没有点按循环的语义），
 *   底栏镜头那颗点按循环镜头 / 长按开面板，读数条目点按循环取值 / 长按开面板。
 *
 * [tier] 只影响"控件胶囊"那一半分支的内剂量（#70 B，裁决在 [chipTierFor]，两枚竖 Dock 才收紧凑档）；
 * 读数那一半（`entry.item`）恒走全局档，因为它与 `hudPerRowFor` 的 90dp 估宽是同一笔账。
 * **没有默认值**（#69 铁律：该必传的形参给了默认值，漏挂的那处就永远吃全局档，`chipTierFor` 里改
 * 那枚竖 Dock 也测不出来）：五枚容器各自显式传 `chipTierFor(HudZone.X)`，新增调用点漏传直接编译不过。
 *
 * [clicksAccepted] 同理**没有默认值**（#73 命中权交接）：底栏 Dock 在吸收期传进来的是
 * `chipClicksAccepted(progress)`，false 时这一颗的点击/长按动作经 [gatedClick] 摘成 null，
 * [WotaChip] 连 `clip + clickable` 都不 install。其余四枚容器没有吸收态，显式传 true。
 * ⚠ 覆盖范围只有胶囊族（TierChip 与读数那颗）：`WotaIconButton` / `BtChip` / 姿态仪那三类的
 * `onClick` 现在不可空，底栏 Dock 里也不可能出现它们（[HudBottomZone] 只渲染缩略图 + 键 + LENS 那颗）；
 * 谁把它们搬进底栏，就得同轮把那几处的 `onClick` 也改成可空，否则这条闸门会漏掉那颗。
 */
@Composable
fun HudEntryItem(
    entry: HudEntry,
    ctx: HudCtx,
    modifier: Modifier = Modifier,
    tier: WotaChipTier,
    clicksAccepted: Boolean
) {
    // 锚点只在这一层挂，且只经 ctx.anchorOf 这一条路（调用方在里面做「同一 PillKey 两个候选写入方」的裁决）
    val anchor = ctx.anchorOf(entry)
    // 霜（#84 步骤 2）：外面那层已经是板 → 只让 fill、不重复注册；没有父板 → 这颗自己出板。
    // 带 secondary 标签的读数那颗由 WotaChipImpl 内部把这份身份摘掉（textLo 在透光区间没有合格档位）
    val frost = hudFrostForEntry(LocalHudFrostParent.current)
    entry.pill?.let { pill ->
        when (pill) {
            CamPill.LEVEL -> AttitudeCard(ctx.roll, ctx.pitch, ctx.levelEnabled, card = false)
            CamPill.VOLUME -> VolumeLeds(ctx.db, card = false)
            CamPill.BT -> BtChip(
                connected = ctx.btConnected,
                volumePct = ctx.btVolumePct,
                modifier = anchor.then(modifier),
                card = false,
                onClick = ctx.onBtClick
            )
            CamPill.ZOOM -> TierChip(
                tier, ctx.zoomLabel, false, anchor.then(modifier),
                gatedClick(clicksAccepted, ctx.onZoomClick), frost
            )
            CamPill.FOCUS -> TierChip(
                tier, ctx.focusLabel, ctx.focusActive, anchor.then(modifier),
                gatedClick(clicksAccepted, ctx.onFocusClick), frost
            )
            CamPill.STAB -> TierChip(
                tier, ctx.stabLabel, ctx.stabActive, anchor.then(modifier),
                gatedClick(clicksAccepted, ctx.onStabClick), frost
            )
            CamPill.REFLINE -> WotaIconButton(
                image = Icons.Filled.GridOn,
                description = stringResource(R.string.cam_p_refline),
                selected = ctx.refLineOn,
                modifier = anchor.then(modifier),
                frost = frost,
                onClick = ctx.onRefLineClick
            )
            CamPill.MONITOR -> TierChip(
                tier, ctx.monitorLabel, ctx.monitorActive, anchor.then(modifier),
                gatedClick(clicksAccepted, ctx.onMonitorClick), frost
            )
            // 曲线开的是整块面板，没有就近锚点（[HudEntry.pillKey] 返回 null）；与斑马纹同级，是创作项
            CamPill.CURVE -> TierChip(
                tier, stringResource(R.string.cam_p_curve), ctx.curveOn, anchor.then(modifier),
                gatedClick(clicksAccepted, ctx.onCurveClick), frost
            )
            CamPill.FLASH -> WotaIconButton(
                image = flashIcon(ctx.flash),
                description = stringResource(flashLabelRes(ctx.flash)),
                selected = ctx.flash != Flash.OFF,
                modifier = anchor.then(modifier),
                frost = frost,
                onClick = ctx.onFlashClick
            )
            CamPill.LENS -> TierChip(
                tier, ctx.lensLabel, false,
                // 点按循环镜头、长按开就近面板（六项第 7 条把顶栏那段并到这颗）；
                // 吸收期这两条动作一起摘掉（#73：底栏那两颗越靠后越压着录制键，谁都不许吃那一指）
                modifier = anchor.then(modifier),
                onClick = gatedClick(clicksAccepted, ctx.onLensCycle),
                frost = frost,
                onLongClick = if (clicksAccepted) ctx.onOpenLensPanel else null
            )
            CamPill.SIZE -> TopSegment(ctx.sizeLabel, WotaColor.textHi, anchor.then(modifier), ctx.onSizeClick)
            CamPill.STORAGE -> TopSegment(
                ctx.capacityLabel,
                if (ctx.freeLow) WotaColor.rec else WotaColor.textHi,
                anchor.then(modifier),
                ctx.onFreeClick
            )
        }
        return
    }
    val item = entry.item ?: return
    val motion = LocalMotion.current
    // 值变 null（如 AE 锁定时 EV 不适用）不该「啪」地消失，所以留住上一次值再淡出
    val value = ctx.readoutValue(item)
    var lastValue by remember(item) { mutableStateOf(value) }
    if (value != null) lastValue = value
    AnimatedVisibility(
        visible = value != null,
        enter = fadeIn(motion.float) + slideInVertically(motion.offset) { it / 3 },
        exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it / 3 },
        modifier = modifier
    ) {
        // 「切到手动值即变蓝」只对本来有 AUTO 档的几项成立
        val manual = item in HUD_AUTO_ITEMS && lastValue != "AUTO"
        WotaChip(
            label = lastValue.orEmpty(),
            selected = false,
            modifier = anchor,
            secondary = stringResource(item.labelRes),
            valueColor = if (manual) WotaColor.accent else null,
            onClick = gatedClick(clicksAccepted) { ctx.onReadoutCycle(item) },
            onLongClick = gatedClick(clicksAccepted) { ctx.onReadoutOpen(item) },
            // 传了也会被 WotaChipImpl 因 secondary 非空而摘掉（见那条注释）；留在这里是为了
            // "哪天把次级标签换成 textHi，只需改一处判据"这件事在代码里看得见，而不是散落成一个洞
            frost = frost
        )
    }
}

/** 顶栏元信息里的一段：文本 + 自己的就近锚点 + 点击（段间细线由 [HudTopZone] 插） */
@Composable
private fun TopSegment(label: String, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * 闪光四档图标。material-icons-extended 1.5.4 里没有 `AutoFlash`/`Torch` 这两个图标名，
 * 自动闪光用 `FlashAuto`、常亮手电用 `FlashlightOn`（语义一致且同包可解析）。
 */
internal fun flashIcon(flash: Flash): ImageVector = when (flash) {
    Flash.OFF -> Icons.Filled.FlashOff
    Flash.ON -> Icons.Filled.FlashOn
    Flash.AUTO -> Icons.Filled.FlashAuto
    Flash.TORCH -> Icons.Filled.FlashlightOn
}

/** 屏幕监看那颗的短标签：没开特效时叫「监看」，开了就叫特效名 */
internal fun effectShortRes(effect: FrameEffect): Int = when (effect) {
    FrameEffect.ZEBRA -> R.string.cam_effect_zebra
    FrameEffect.PEAKING -> R.string.cam_effect_peaking
    FrameEffect.NONE -> R.string.cam_p_monitor
}

/** 白平衡读数：手动档显示色温，其余显示预设名（预设名走资源） */
@Composable
internal fun wbShort(mode: WbPreset, kelvin: Int): String =
    if (mode == WbPreset.MANUAL) "${kelvin}K" else stringResource(wbLabelRes(mode))

// ------------------------------------------------------------------ 容器外壳（两级模型的第一级）

/**
 * 原生对齐分支里卡片在「带」内的对齐档，逐条对着改前代码：
 * 左竖 Dock 竖向居中（CenterStart + 上下让位）、右竖 Dock **顶部**对齐（S3-3：内容超出带高时
 * 居中会把上下两头都顶出去）、读数块贴带底、底栏在可视区里水平居中。
 */
private fun nativeContentAlignment(zone: HudZone): Alignment = when (zone) {
    HudZone.TOP, HudZone.LEFT -> Alignment.CenterStart
    HudZone.RIGHT -> Alignment.TopEnd
    HudZone.READOUT -> Alignment.BottomEnd
    HudZone.BOTTOM -> Alignment.Center
}

/**
 * 一枚容器可用的最大带高：竖 Dock 与读数块被顶/底避让量夹，顶栏与底栏受整条安全区夹。
 *
 * ⚠ [HudZone.READOUT] 那一枚**必须喂它自己的 area**（`ReadoutRowPlan.bottomAvoidDp` 那份，
 * 不是 `baseArea`）：#70 A 之后读数块默认与底栏同基线，喂 baseArea 会把带高少算一整排（66dp），
 * 条目就白白多滚一屏。原生对齐分支、`clampZonePos`、这条带高三处读的是同一个 `area.bottomAvoidDp`，
 * 所以只要 area 给对了，三处不会分叉。
 */
internal fun zoneBandHeight(zone: HudZone, area: HudAreaDp): Int =
    if (zone == HudZone.TOP || zone == HudZone.BOTTOM) area.height
    else (area.height - area.topAvoidDp - area.bottomAvoidDp).coerceAtLeast(0)

/**
 * 一枚容器的外壳：定稿位置走原生对齐，用户拖过的位置走绝对 `offset`。
 *
 * 三条分支（按表里的绝对轴取）：
 * - **底栏落位**（只有 y 绝对）：`align(TopStart) + fillMaxWidth + offset(y)`，横向仍由
 *   [nativeContentAlignment] 的 Center 居中 ⇒ 快门恒等于**可视窗口**水平中心，90↔270 翻转与
 *   横竖换档都会重新居中。底栏的 x 在 [clampZonePos] 与 [HudLayoutTable.normalize] 两处都被抹成哨兵，
 *   所以它不可能带绝对 x（B4 审查 S1：落一次位就把 x 冻成绝对值，此后再也不居中）。
 * - **其余容器的绝对位置**（x 与 y 都绝对）：`align(TopStart) + offset(x, y)`。
 * - **定稿位置**（两轴都是哨兵）：B1–B3 那五条原生对齐分支。贴边避让全部由调用方那层
 *   `safeDrawingPadding()` 承担，本层只剩 [HudEdgePad] 这枚设计留白。
 *
 * 两层节点：**外层是"槽位"**（原生对齐时那条带避让量的带，绝对定位时只是一个锚点），
 * **内层才是卡片本体**：[onCardRect] 回报的是它的窗口矩形，重叠提示、落点格位、拖动起点全用这一份，
 * 所以"槽位比卡片大"的原生分支不会把假尺寸喂给钳制算式。
 *
 * [shiftXPx] / [shiftYPx] 是**取位移的 getter**，只在 [graphicsLayer] 块里读：编辑页拖动过程中
 * 不动布局参数、也不让整页重组（四条红线之一 + 录制页 08:79 帧率红线），
 * 松手才把绝对坐标写进表、由那条分支换成 `offset`（一次性重排，不是动画）。
 */
@Composable
fun BoxScope.HudZoneBox(
    zone: HudZone,
    placement: ZonePlacement,
    area: HudAreaDp,
    modifier: Modifier = Modifier,
    shiftXPx: () -> Float = { 0f },
    shiftYPx: () -> Float = { 0f },
    /**
     * **顶栏原生对齐那一支的下限**（dp，安全区局部坐标；任务 #79）。只有 [HudZone.TOP] 读它，
     * 其余四枚容器一律传 0（录制页顶栏本来就该贴顶，0 = 与改前逐字同值）。
     *
     * 形参**没有默认值**（#69 铁律）：这一处给默认值已经翻过车——上一批加这个形参时十一个调用点
     * 只写了算式没传值，结果"三颗按钮能点了，顶栏那两枚又落在操作栏矩形里拖不动"，把一个缺陷换成另一个。
     * 让它编译不过才是这条防线本身；新增调用点漏传直接红在编译器上，不红在真机上。
     */
    nativeTopMinDp: Int,
    onCardRect: (IntRect) -> Unit,
    content: @Composable () -> Unit
) {
    // 霜的宿主登记（#84 步骤 2）：五枚容器各登记一次，引用计数到 0 才撤定时器与 prefs 监听。
    // 挂在这里而不是某枚具体容器的理由是：只要 HUD 在屏幕上，就总得有人盯 GL 的可用回报。
    HudFrost.host()
    val density = LocalDensity.current
    fun px(dpValue: Int): Int = with(density) { dpValue.dp.toPx() }.roundToInt()
    // 底栏是"只认 y"的那枚容器：绝对 y + 哨兵 x，横向继续走系统居中
    val bottomYOnly = zone == HudZone.BOTTOM && placement.yDp >= 0
    val absolute = !bottomYOnly && (placement.xDp >= 0 || placement.yDp >= 0)
    val slot = when {
        bottomYOnly -> Modifier.align(Alignment.TopStart).fillMaxWidth()
            .offset { IntOffset(0, px(placement.yDp)) }
        // 钳过的绝对坐标 → 布局偏移：pillAnchor 回报的窗口矩形跟着视觉位置一起走
        absolute -> Modifier.align(Alignment.TopStart)
            .offset { IntOffset(px(placement.xDp), px(placement.yDp)) }
        else -> when (zone) {
            // ↓ 这五条分支就是 B1–B3 的定稿对齐与设计留白。fillMax* 必须排在 padding 前，
            //   否则"带高"会变成盒子自己的尺寸，居中与对齐基准就漂了（§58 那族坑的又一种走法）
            HudZone.TOP -> Modifier.align(Alignment.TopStart)
                // [nativeTopMinDp]：编辑页把顶栏整条推到它自己那条操作栏之下（录制页五处传 0 ⇒ 与改前逐字同值）。
                // 不推的话这枚容器的两枚段会压在操作栏那三颗的矩形里，而那三颗现在在捕获层之上（z 序最后画），
                // 交叠的那几颗就点不到也拖不动了——带的下缘怎么量出来的见 [chromeBandBottomDp]，
                // 与 HudLayoutEditor 的「手势：一层捕获层」是同一条账的两头。
                .offset {
                    IntOffset(
                        px(HudEdgePad.value.roundToInt()),
                        px(maxOf(TopTopPad.value, nativeTopMinDp.toFloat()).roundToInt())
                    )
                }
            HudZone.LEFT -> Modifier.align(Alignment.CenterStart).fillMaxHeight()
                .padding(start = HudEdgePad, top = area.topAvoidDp.dp, bottom = area.bottomAvoidDp.dp)
            HudZone.RIGHT -> Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                .padding(end = HudEdgePad, top = area.topAvoidDp.dp, bottom = area.bottomAvoidDp.dp)
            // #70 A：读数块的 bottom 不再吃"底栏那一排的带高"。调用方喂给它的是 ReadoutRowPlan 算出来的
            // 那一条底边（与 readoutRoomBesideDockDp 同一次决策的产物），两档：
            // · 同行档（横屏恒真、竖屏右半带装得下两颗时为真）：这里的数**已经减掉本容器 Column 自己那枚
            //   `padding(HudBlockPadDp)`**（见 planReadoutRow 的"6dp 账"），所以对齐的是**胶囊可见底边**与
            //   下一行 BOTTOM 那枚底板可见底边，而不是两枚容器的外缘——否则读数会浮在底板上 6dp。
            // · 退化档（只有竖屏）：让到整排之上，容器底边压在带顶上（本容器不吃那一截减法，留缝是设计）。
            // 手算让位只用在几何上真会重叠的那一对，这条规矩是 §14.2 里用户 11:39 那句原话的落点。
            HudZone.READOUT -> Modifier.align(Alignment.BottomEnd)
                .padding(end = HudEdgePad, bottom = area.bottomAvoidDp.dp)
            // 底栏的槽位要横向铺满才谈得上「底板在可视窗口里居中」。居中基准这里不许扣任何让位量：
            // 旧版扣了 34dp（写死的右缘避让），快门就偏在可视中心左边 17dp
            HudZone.BOTTOM -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(bottom = BottomBarOuterPadV)
        }
    }
    Box(
        slot.then(modifier),
        contentAlignment = if (absolute) Alignment.TopStart else nativeContentAlignment(zone)
    ) {
        Box(
            Modifier
                .onGloballyPositioned { coords ->
                    val p = coords.positionInWindow()
                    val s = coords.size
                    onCardRect(IntRect(p.x.toInt(), p.y.toInt(), p.x.toInt() + s.width, p.y.toInt() + s.height))
                }
                .graphicsLayer {
                    // 这两个 getter 只在 layer 块里读：拖动期间每帧只让这一层重画/重定位，
                    // 不让整页重组（录制页那一路的帧率红线 08:79 同样吃这条）
                    translationX = shiftXPx()
                    translationY = shiftYPx()
                }
        ) { content() }
    }
}

// ------------------------------------------------------------------ 五枚容器的内容

/**
 * 顶栏胶囊组（[HudZone.TOP]）：录制计时/状态那颗 + 元信息那枚（画幅 | 容量，顺序由表说了算）。
 *
 * 元信息收成**一枚**胶囊（用户 2026-09-28 鸿蒙化第 1 条）：原先「广角 / 1920x1080 16:9 / 剩余 96.2G」
 * 是散在画面上的三颗，读起来像三个入口；现在共用一层 hudScrim 壳 + 高光描边，段与段之间一条细线，
 * 点各自段仍开各自的就近弹窗。六项第 7 条：镜头段整段删除，那颗入口与 `CamPill.LENS` 一起搬到底栏。
 *
 * 两枚 AnimatedVisibility 互斥（录制那颗 vs 元信息组），所以不会出现"卡中卡"；
 * 计时与状态是**读数**不是入口，所以不给它们挂 onClick（§37 第 4 条）。
 * 只淡入缩放、不展开宽度：布局一次到位，旁边几颗胶囊不会被挤。
 * §59/§74：容量段取哪一档由调用方算好送进来（窄屏判断放在拿得到根容器宽度的那一层）。
 */
@Composable
fun HudTopZone(order: List<HudEntry>, ctx: HudCtx, modifier: Modifier = Modifier) {
    if (order.isEmpty() && !ctx.recording && ctx.recStateLabel == null) return
    val motion = LocalMotion.current
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TopRowGap)
    ) {
        AnimatedVisibility(
            visible = ctx.recording || ctx.recStateLabel != null,
            enter = fadeIn(motion.float) + scaleIn(motion.float, initialScale = 0.86f),
            exit = fadeOut(motion.float) + scaleOut(motion.float, targetScale = 0.86f)
        ) {
            // 录制中顶栏只说一件事：在录、录了多久。元信息那组整组让位（鸿蒙化第 1 条）
            // 这两颗各自是一块玻璃板（顶栏没有整块底板，胶囊就是底板本身）
            if (ctx.recording) {
                WotaChip(
                    label = ctx.elapsedLabel,
                    selected = false,
                    valueColor = WotaColor.rec,
                    dot = WotaColor.rec,
                    frost = hudFrostPillPlate
                )
            } else {
                WotaChip(
                    label = ctx.recStateLabel.orEmpty(),
                    selected = false,
                    valueColor = WotaColor.warn,
                    frost = hudFrostPillPlate
                )
            }
        }
        AnimatedVisibility(
            visible = !ctx.recording && ctx.recStateLabel == null && order.isNotEmpty(),
            enter = fadeIn(motion.float) + scaleIn(motion.float, initialScale = 0.86f),
            exit = fadeOut(motion.float) + scaleOut(motion.float, targetScale = 0.86f)
        ) {
            Row(
                // 元信息那枚壳自己是一块玻璃板（里面那几段 TopSegment 不画底，所以不用往下 provide）
                Modifier.wotaHudCard(WotaShape.pill, hudFrostPillPlate).padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                order.forEachIndexed { index, entry ->
                    if (index > 0) {
                        Box(Modifier.width(1.dp).height(12.dp).background(WotaColor.outline))
                    }
                    // 顶栏恒走全局档（容量段那笔窄屏阈值 272/242 按它量的），但档位仍只有一处裁决点
                    // clicksAccepted 恒 true：吸收态是底栏 Dock 独有的（#73），顶栏那颗不飞进录制键
                    HudEntryItem(
                        entry, ctx,
                        Modifier.ghostWhileDragged(ctx.hiddenEntry == entry),
                        chipTierFor(HudZone.TOP),
                        clicksAccepted = true
                    )
                }
            }
        }
    }
}

/**
 * 网格的行距（同一枚容器横竖共用一档，与 #74 之前那两处 `spacedBy` 逐字同值）：
 * · 两枚竖 Dock = [WotaSpace.xs]（改前是 `Column`/`Row` 的 `spacedBy(WotaSpace.xs)`）；
 * · 读数块 = [HudRowGapDp]（改前是 `spacedBy(HudRowGapDp.dp)`，与 `hudStripHeightDp` 的行距同一真源）。
 *
 * **渲染层与编辑页都只读这一条**：格子的列长 = 最宽那枚格子（同组多颗时含格内那道行距，见
 * [cellContentWidthPx]）+ 这个行距（[gridPitchPx]），两处各写一份的话编辑页的吸附就会与画出来的位置
 * 错开半格（S3-5 那一族"两边判的不是同一条不等式"）。格内那几颗之间的间距用的也是这一档，没新造数。
 *
 * ⚠ #75 之后这一档在**纵向**只剩"格距的一部分"：纵向格距 = 一颗胶囊高 + 这一档（[hudGridRowPitchPx]），
 * 不再是"容器里最高那颗 + 这一档"。改这条要同时知道 [hudGridRowPitchPx] 在读它。
 */
internal fun hudGridGap(zone: HudZone): Dp =
    if (zone == HudZone.READOUT) HudRowGapDp.dp else WotaSpace.xs

/**
 * 该容器的**纵向格距**（px，任务 #75 的唯一算式入口）：一颗胶囊高 + 该容器那一道行距，
 * 全从既有令牌推（[gridRowPitchPx] → [hudChipHeightDp] + [hudGridGap]），**与格子里住了谁无关**。
 *
 * 这一条是 #74 那个回归的正解：原来纵向格长取"容器内最高那颗的实测高 + 行距"，
 * 右 Dock 那枚 ≈72dp 的姿态仪把行距顶到 ≈78dp，把 30dp 的胶囊全按 78dp 排 ⇒ 网格 ≈386dp
 * 而横屏带高只有 244dp ⇒ 溢出 142dp，对焦与防抖整颗掉到折叠线以下。
 * 现在行距恒为 ≈34dp（100% 档），高的那颗自己吃掉几档（[com.wotagei.cam.ui.cellRowSpan]），
 * 5 枚格子 7 档 = 234dp ≤ 244dp。
 *
 * 三个消费方必须读同一个数，否则"看着在一格、松手落另一格"：
 * [HudEntryGrid]（排像素）、[com.wotagei.cam.ui.HudLayoutTable.gridItems] 那条解析（推默认行、算占用，
 * 经 `HudGridPlan.rowPitchOf`）、编辑页的吸附框（经 [snapGridPitchPx]）。
 */
internal fun hudGridRowPitchPx(zone: HudZone, density: androidx.compose.ui.unit.Density): Int =
    gridRowPitchPx(density.fontScale, hudGridGap(zone).value, density.density)

/**
 * 一枚容器里的**固定格网**（任务 #74 的渲染落点，#75 加行跨度）。
 *
 * 为什么必须自己写 `Layout` 而不是 `Column`/`Row` + `spacedBy`：后者把"第几颗"当成位置来源，
 * 抽走或插入一颗就整体重排——那是本次要拆的耦合。格长、格网尺寸、每颗的落点**全部**由纯函数
 * [gridPlacementOf] 算（横向取最宽那枚**格子**的内容宽 + 行距见 [cellContentWidthPx]；
 * 纵向取传进来的 [rowPitchPx]（[hudGridRowPitchPx] 那份，与住户无关）＋高条目按实测高吃几档），
 * 本层只剩"量尺寸 → 回报实测高 → `placeRelative`"三条调用，所以：
 * - 移动任意一颗都不改变其他任何一颗的屏幕坐标（空格子就空着）；
 * - **一枚格子里可以横向住同组的多颗**（#74 后果修复：右 Dock 那对 S2-2B 并排 = 一格两颗，
 *   不再是一行两列）。格长因此由"最宽那枚格子"而不是"最宽那颗"撑出来，右 Dock 才回到单列、
 *   底板才回到 ≈94dp（这笔账逐颗列在 [HudDockZone] 的 KDoc 里）；格内左右次序 = [HudGridItem] 清单序，
 *   与编辑页的命中裁决 [entryHitIndex] 读的是同一个序；
 * - **高条目不再把别人的行距顶高**（#75）：它自己吃掉 `row .. row+span−1`，胶囊仍按 ≈34dp 排。
 *   实测高就是跨度唯一的证据来源，所以这一层必须把它回报给 [HudCtx.entryHeights]——
 *   回报的是**颗**的高（不是格子的），格子归属变了也不用清表（跨度按当前住户现算）；
 * - 字体拉到 120% 时格距自己变大（[gridRowPitchPx] 吃 `fontScale`），横向仍取实测宽，
 *   不需要任何按 100% 量出来的固定值，§58/§73 那族"固定档位在 120% 裁字"在这里没有落点；
 * - 只有一列 / 只有一行且没有高条目时总宽总高与改前 `spacedBy` 的紧凑排布**逐像素相等**
 *   （见 [gridSizePx] 那笔减法）。
 *
 * 定位只发生在**布局期**（`placeRelative`），没有位置动画、没有 `animateDpAsState`
 * （AGENTS.md 与 `Motion.kt` 的红线：预览层之上不做布局参数动画）。容器整体仍然只在
 * [HudZoneBox] 那一层承担位置。
 *
 * 网格节点自己经 [Modifier]（调用方传 `ctx.gridOf(zone)`）回报窗口矩形：编辑页由它反解**列**长
 * （[gridPitchOf]，纵向不再反解，见 [snapGridPitchPx]），不需要再量每颗条目（姿态仪与音量表那两颗
 * 本来就不报锚点，见 [HudEntryItem] 的 when 分支）。
 */
@Composable
private fun HudEntryGrid(
    items: List<HudGridItem>,
    zone: HudZone,
    rowPitchPx: Int,
    heights: MutableMap<HudEntry, Int>,
    /**
     * 这一枚容器的**默认表**格子（[HudLayoutTable.defaultGridOf] 的产物，#80）。
     * 与 [items] 的区别就一件事：它不看谁摆到了哪一格，所以格长与预留档数由它说了算——
     * 这正是"拖一颗不动别颗"里"固定原点 + 固定格长"那两项的来源。
     * **没有默认值**（#69 铁律）：漏挂的那一处会静默退回"格长取这一容器里最宽那枚格子"，
     * 于是配对一拆开所有人的 x 平移一档（真机实测的那条），编译不过才是这条防线本身。
     */
    defaultCells: Map<HudEntry, GridCell>,
    modifier: Modifier = Modifier,
    content: @Composable (HudEntry) -> Unit
) {
    val gap = hudGridGap(zone)
    Layout(content = { items.forEach { content(it.entry) } }, modifier = modifier) { measurables, constraints ->
        // 与改前 Column/Row 的宽松约束同一条：条目按自己的内容量，不被格子拉伸
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gapPx = HudSizePx(gap.roundToPx(), gap.roundToPx())
        // 整条算式（逐格住户 → 格长 → 行跨度 → 各颗落点）都在纯函数 [gridPlacementOf] 里，
        // 有手算期望值的 JVM 用例；这里只剩"把量到的尺寸喂进去、再把坐标 placeRelative 出来"
        val placed = gridPlacementOf(
            items, placeables.map { HudSizePx(it.width, it.height) }, gapPx, rowPitchPx,
            GridAnchor(zone, defaultCells)
        )
        layout(placed.size.width, placed.size.height) {
            placeables.forEachIndexed { i, placeable ->
                val at = placed.offsets[i]
                placeable.placeRelative(at.x, at.y)
            }
            // #75：实测高回报给位置表（跨度唯一的数据源）。只在**值真变了**才写，
            // 与 [pillAnchorReport] / `thumbW` 同一手法——布局期写状态会重组，值没变还照写就是每帧空转
            items.forEachIndexed { i, item ->
                val h = placeables[i].height
                if (heights[item.entry] != h) heights[item.entry] = h
            }
        }
    }
}

/**
 * 竖 Dock 的一枚（[HudZone.LEFT] / [HudZone.RIGHT]）：一枚圆角底板 + 内部按**固定格子**摆条目。
 *
 * 宽度不写死：底板宽 = 格网宽 = `列数 × (最宽那枚格子 + 行距) − 行距`（[gridPitchPx] + [gridSizePx]），
 * 所以字体 120% 时自动加宽，§58/§73 那族「按 100% 字体量出来的固定宽在 120% 下裁字」在这里不复发。
 * 条目位置来自 [HudGridItem.cell]，**与 [HudGridItem] 的先后无关**（#74：拖走一颗不动另一颗）。
 * 唯一读"清单先后"的地方是**同一枚格子内部**那几颗的左右序（#74 后果修复后新增的那一档，
 * 见 [gridPlacementOf] 第 ① 条），格与格之间仍然只看 `col/row`。
 *
 * **胶囊在本容器内走 [WotaChipTier.Dock] 紧凑档**（#70 B，档位裁决只有一处：[chipTierFor]）。
 * 收窄前后的账（汉字按 1 em、拉丁按 0.6 em 估，与 `hudPerRowFor` 同一套估算口径）：
 * · 左 Dock：r11 那档「底板约 84dp」是**全局档**下「屏幕监看」4 汉字 52 + 12+12 = 76，再包 8 得出的。
 *   紧凑档之后同那颗 52 + 8+8 = 68 → 底板 76dp；但**这一档从来不是监看在撑**——
 *   「RGB 曲线」≈53.3 文字 → 69.3 → 底板 ≈**77dp**，才是左 Dock 的真约束（监看改名后仍是它）。
 *   用户 2026-09-29 把那颗改成「监看」（26 + 16 = 42），于是：
 *   —— 曲线**开着**时底板仍 ≈77dp（改名对底板宽**无效**，要再窄得改「RGB 曲线」那条文案或换图标）；
 *   —— 曲线**关掉**时（本机 `hud_pills=7935`，差的 256 位正是 [com.wotagei.cam.core.CamPill.CURVE]）
 *      最宽颗就是改名后的「监看」：2 汉字 26 + 紧凑档左右内边距 8+8 = **42dp** → 底板 **50dp**（76 → 50；
 *      与下面那笔 94dp 同为手算，实测排在 #67）。
 *      「参考线/闪光灯」那两颗**不在这笔账里**：它们走 [WotaIconButton]，宽恒为 `WotaHit.iconButton = 38dp`
 *      （`cam_p_refline`/`cam_p_flash` 那两条文案只是 `contentDescription`，一个像素都不占横向），
 *      所以既撑不动底板也不该被算进来。
 * · 右 Dock 静置时最宽那颗是「1.0x」（31dp 文字，被 3 汉字下限撑到 39）→ 71dp 底板；
 *   紧凑档 47.2 → 底板 **55dp**（−16dp）。
 * · ⚠ **录制中右 Dock ≈94dp，这一档由"配对格"撑出来**（#70 B 那批把它列为未决项，#74 后果修复已闭合）：
 *   S2-2 B 那条「姿态仪 + 音量表并排」现在是**同一枚格子里的两个条目**（不再是一行两列，见
 *   [defaultCellsOf] 的三条收益），所以那一格的内容宽 = 姿态仪 54（46dp 天地线 + 左右内边距 4+4）
 *   + 行距 [WotaSpace.xs] 4 + 音量表 28（16dp LED + 左右内边距 6+6）= **86dp**；
 *   右 Dock 是**单列**网格 ⇒ 格网宽 = 1 × (86 + 4) − 4 = 86，底板再包 `padding(WotaSpace.xs)` 左右各 4 = **94dp**
 *   ——与 r11 真机量到的 94 对上（这笔是**手算**，本轮手机锁屏未实测，见 docs/plan/13 §15.7）。
 *   窄的那些行（变焦/对焦/防抖 47dp、蓝牙那颗）居中在 86dp 宽里，与改前 `Column` 的行为一致。
 *   历史：#74 第一版把 pitch 定成"最宽那**颗**"，那对并排于是占两列、第二列也按 54dp 算
 *   ⇒ 底板 120dp，把用户要的"更窄"做反了 26dp。要再窄只能动这两颗自绘件本身的尺寸（下一轮的事）。
 *
 * 底板只在至少有一颗要画时才组合，否则全关掉后会留一枚空壳。
 * 底板圆角用 [WotaShape.card] 而不是 pill（S3-4）：`percent = 50` 的半径取短边一半，
 * 而 `wotaCard` 第一环就是 clip，会把首尾那颗卡片的外角各削掉一截；14dp 的 card 不咬内容。
 * 姿态仪与蓝牙这两颗在 Dock 内不再自绘底（`card = false`），免得底板 + 内层卡两层 hudScrim 叠成"卡中卡"。
 *
 * ## 纵向账（#75：格距与住户无关 + 高条目吃行跨度）
 * 行距不再是"容器里最高那颗 + 一道行距"，而是 [hudGridRowPitchPx] 推出来的固定格距
 * （100% 档 = 胶囊 30dp + `WotaSpace.xs` 4dp = **34dp**；120% 档 38dp）。
 * 于是录制中右 Dock 默认那一列（本机 density 2.0，全部手算）：
 *
 * | 格（起始 row） | 跨度 | 住户与实测高 | 吃掉第几档 |
 * |---|---|---|---|
 * | (0,0) | 3 | 配对格：姿态仪 ≈72dp（天地线 46 + 上下内边距 5+5 + spacedBy 2 + `labelSmall` 行高 14）／音量表 ≈71dp | 0、1、2 |
 * | (0,3) | 1 | 蓝牙 ≈30dp | 3 |
 * | (0,4) | 1 | 变焦 ≈30dp | 4 |
 * | (0,5) | 1 | 对焦 ≈30dp | 5 |
 * | (0,6) | 1 | 防抖 ≈30dp | 6 |
 *
 * 网格高 = 7 档 × 68px − 8px = **468px = 234dp** ≤ 横屏带高 244dp（`360 − 顶栏 44 − 底栏 72`）⇒ **溢出 0**，
 * 对焦与防抖都在折叠线以上。120% 那一档：格距 76px、配对块实测 ≈150px ⇒ 跨度 2 ⇒ 6 档 = 448px = 224dp，
 * 同样不溢出。改前那一版是 5 档 × 156px = 386dp，**溢出 142dp（1.8 行）**，对焦与防抖整颗掉到带外。
 * 底板高 = 网格高 + 上下内边距 4+4 = 242dp（100%），仍在带内；两枚竖 Dock 各自算，别把这两笔相加。
 *
 * 跨度**不进表**（[GridCell] 只有 (col, row)）：它由 [HudEntryGrid] 回报的实测高现算，
 * 所以字体缩放、文案长度、条目被搬走这三件事都不需要迁移任何持久化数据。
 * 每颗的 row 仍然是**它自己那格的起始档**——跨度只决定"哪些档算被占"和"默认推导往哪推进"，
 * 不用来重排任何已经摆过的条目（[HudLayoutPairCellTest] 那几条独立性用例逐颗钉着坐标）。
 *
 * [HudZone.RIGHT] 的并排规则（S2-2 B）**保留在** [hudRowGroups] 里，但**降级成只管默认格子的分组**：
 * 姿态仪与音量表在顺序里相邻时**共用同一枚格子**（格内横着排两颗，#74 后果修复；改前是"推导成同一行的
 * 两列"，那正是把底板撑到 120dp 的那一步）。**#75 之后它省的是宽度与列数，不再是高度**：
 * 高度那笔账改由固定格距负责（上面那张表），配对块只吃它自己需要的 3 档，不再替四颗胶囊决定行距。
 * 用户把任何一颗摆过一次之后那一枚容器就整体钉住（[HudLayoutTable.pinned]），并排规则不再重排别人的位置——
 * 这是「条目可重排」与「横屏 360dp 带高不够」唯一能同时成立的写法。钉过之后配对还可以被用户拆开、
 * 也可以合回去（同组两颗共用一格不算撞格，判据在 [blockingCells]），这条比"两列各自固定"更自由。
 * 内容超出带高时靠 [verticalScroll] 取用，顶部对齐保证高频项先露脸（内容超出时居中排布
 * 会把上下两头都顶出去，没有意义）。
 */
@Composable
fun HudDockZone(
    zone: HudZone,
    items: List<HudGridItem>,
    ctx: HudCtx,
    bandHeightDp: Int,
    /** 该容器的默认表格子（#80，[HudLayoutTable.defaultGridOf]）：格长与预留档数的唯一来源，无默认值 */
    defaultCells: Map<HudEntry, GridCell>,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    // #70 B：本容器内所有"控件胶囊"统一走这一档，读数条目不受影响（见 chipTierFor 的注释）
    val tier = chipTierFor(zone)
    // #75：纵向格距与住户无关（姿态仪那枚 ≈72dp 的自绘件不许再把别人的行距顶高），
    // 它自己吃掉几档由实测高经 [com.wotagei.cam.ui.cellRowSpan] 现算
    val rowPitchPx = hudGridRowPitchPx(zone, LocalDensity.current)
    // 霜（#84 步骤 2）：底板自己注册一块玻璃，里面的条目一律改成"吃父板"——
    // 它们再各注册一块就会与父板重叠（同一条 UV 上叠两层混色，白白多画一遍），
    // 而它们的 fill 必须让位，否则 74.9% 不透明的药丸把底下的霜全盖死。
    // 半径取 WotaShape.radiusCard 那一档（与 Shape 同源，两处不会分叉）
    val plateRadiusPx = with(LocalDensity.current) { WotaShape.radiusCard.toPx() }
    val plate = remember(plateRadiusPx) { hudFrostPlate(plateRadiusPx, HudInkLevel.PRIMARY) }
    CompositionLocalProvider(LocalHudFrostParent provides hudFrostInherit) {
        Column(
            modifier
                .wotaHudCard(WotaShape.card, plate)
                .heightIn(max = bandHeightDp.dp)
                .verticalScroll(rememberScrollState())
                .padding(WotaSpace.xs),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 网格节点的矩形经 ctx.gridOf 回报给编辑页（录制页传的是空链，一个节点都不加）
            HudEntryGrid(items, zone, rowPitchPx, ctx.entryHeights, defaultCells, ctx.gridOf(zone)) { entry ->
                HudEntryItem(entry, ctx, Modifier.ghostWhileDragged(ctx.hiddenEntry == entry), tier, clicksAccepted = true)
            }
        }
    }
}

/**
 * 右下常驻读数块（[HudZone.READOUT]，六项第 4 条搬到录制键右侧）。
 *
 * **#70 A：它与底栏 Dock 是"同一条横带上的两个落位"，不是一上一下**（用户 2026-09-29 12:20 定版：
 * 「读数缩到一行两颗，横屏永远同行」——横屏 [com.wotagei.cam.ui.ReadoutRowPlan.sharesDockRow] 恒真，
 * 只有竖屏（右半带排不下两颗，或估宽说排得下而**实测块宽**越过了那条带）才整块退到底栏之上）。
 * 对齐的是**可见底边**：本容器除了 `padding(bottom=)`
 * 还吃 `.padding(HudBlockPadDp)`，所以 [com.wotagei.cam.ui.planReadoutRow] 送下来的让位是
 * `BottomBarOuterPadV − HudBlockPadDp`（本机 0dp），胶囊底边才与底板底边同高，不再浮 6dp。
 * 横向各占一半：底栏居中、读数块贴右缘，中间让开一枚 `WotaSpace.s`；可用宽与列数（横屏上限
 * `DockRowPerRowCap` = 两颗）都由那一条纯函数一次算清，本层的 [bandHeightDp] 与调用方喂给
 * [HudZoneBox] 的 `bottomAvoidDp` 出自同一次决策。
 *
 * 每格点按循环取值、长按弹自己的就近胶囊。**#74 之后不再"每行右对齐"**：改按固定格子摆
 * （[HudEntryGrid]），空格子就空着，最后一行不满时右边那段空白是用户自己留的，不是被裁了——
 * 这与"移动一颗不动另一颗"是同一件事的两面（旧的右对齐本质上是按行重新推导位置，那正是被拆掉的耦合）。
 * 一行几颗只在**没摆过**时参与默认格子推导（[defaultCellsOf] → [hudRowGroups]，档位来自 [hudPerRowFor]，
 * 横屏那一路还要过 `DockRowPerRowCap`），摆过一次就整体钉住。
 * 内边距与间隔走 `hudRoomDp` / `hudStripHeightDp` 的同源常量（S3-5）：改了这里必须同时改那两条算式。
 * 只有读数块这一枚的**列数**受可用内宽约束（编辑页落点钳在 `roomWidthDp ÷ 格宽` 那一档，
 * 见 [gridBoxOf]），因为横方向没有 `verticalScroll` 那样的取用路径。
 *
 * 参数是一颗一颗独立的悬浮胶囊，不是一整块面板，所以这里不套外层底。
 * 不进底栏那枚 Dock 的理由照旧：Dock 内左右两槽必须等宽快门才居中，读数进去会把整枚 Dock 撑到
 * 500dp 以上，横屏 800dp 宽都嫌挤、竖屏直接溢出。**读数在 Dock 之外**这条是 #70 A 的硬约束：
 * 它绝不参与 `2S + R + 2G + 2P` 那笔居中算式，也不许为了放读数去动底栏槽位。
 * 这几颗胶囊也**不走紧凑档**（[chipTierFor] 把 READOUT 钉在 [WotaChipTier.Standard]），
 * 因为 `hudPerRowFor` 的 90dp 估宽量的就是全局档。
 */
@Composable
fun HudReadoutZone(
    items: List<HudGridItem>,
    ctx: HudCtx,
    bandHeightDp: Int,
    /** 读数块的默认表格子（#80）：预留**列数**用，透明底板 ⇒ 预留不花观感钱；纵向是有意留的缺口，
     *  理由逐条写在 [GridAnchor] 的文件头。无默认值（#69 铁律） */
    defaultCells: Map<HudEntry, GridCell>,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty() && !ctx.aeLocked) return
    val motion = LocalMotion.current
    // #75：读数块的行距档是 [HudRowGapDp]（与竖 Dock 的 WotaSpace.xs 不同一档），所以格距按容器现算。
    // 这一档 100% 时 (18+12+6)×2 = 72px，与 #74 那版"实测最高那颗 60px + 行距 12px"逐字同值——
    // 读数块里每颗都是胶囊、没有异型件，所以这一批它的观感一条没变（看门狗用例钉着这一条）
    val rowPitchPx = hudGridRowPitchPx(HudZone.READOUT, LocalDensity.current)
    Column(
        modifier
            .heightIn(max = bandHeightDp.dp)
            .verticalScroll(rememberScrollState())
            .padding(HudBlockPadDp.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(HudRowGapDp.dp)
    ) {
        if (items.isNotEmpty()) {
            // 读数胶囊恒走全局档（`hudPerRowFor` 的 90dp 估宽按它量），同样只经 [chipTierFor] 一处
            HudEntryGrid(items, HudZone.READOUT, rowPitchPx, ctx.entryHeights, defaultCells, ctx.gridOf(HudZone.READOUT)) { entry ->
                HudEntryItem(
                    entry, ctx,
                    Modifier.ghostWhileDragged(ctx.hiddenEntry == entry),
                    chipTierFor(HudZone.READOUT),
                    // 读数块在 Dock 之外（#70 A 硬约束），永远不参与吸收，所以闸门恒开
                    clicksAccepted = true
                )
            }
        }
        // 长按对焦锁 AE 时这颗提示凭空出现，是最容易被当成「画面闪了一下」的硬切
        AnimatedVisibility(
            visible = ctx.aeLocked,
            enter = fadeIn(motion.float) + slideInVertically(motion.offset) { it },
            exit = fadeOut(motion.float) + slideOutVertically(motion.offset) { it }
        ) {
            Text(
                text = stringResource(R.string.cam_ae_lock),
                style = MaterialTheme.typography.labelSmall,
                color = WotaColor.warn,
                modifier = Modifier
                    .wotaCard(WotaShape.pill)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 音量表：`amplitude()` → dB 后 6 格 LED，仅录制中显示（06 文档 §4）。
 *
 * [card] 与 [AttitudeCard]、[BtChip] 同一条规则（S3-4）：单独摆的时候自带一层底，
 * 放进竖 Dock 时传 false，免得底板 + 内层底两层 hudScrim 叠成"卡中卡"。
 */
@Composable
internal fun VolumeLeds(db: Float, card: Boolean = true) {
    val lit = (((db.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / (MAX_DB - MIN_DB)) * LED_COUNT).roundToInt()
    Column(
        Modifier
            .then(if (card) Modifier.clip(RoundedCornerShape(8.dp)).background(WotaColor.hudScrim) else Modifier)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.GraphicEq,
            contentDescription = stringResource(R.string.cam_volume_meter),
            tint = WotaColor.textLo,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.height(4.dp))
        // 自顶向下画，点亮数从底部起算
        for (index in LED_COUNT downTo 1) {
            val on = index <= lit
            val color = when {
                !on -> WotaColor.outline
                index >= LED_COUNT -> WotaColor.rec
                index == LED_COUNT - 1 -> WotaColor.warn
                else -> WotaColor.accent
            }
            Box(
                Modifier
                    .padding(vertical = 1.dp)
                    .width(16.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/**
 * 顶栏那排**不可拖动**的固定件：设置入口 + 能力/权限告警条。它整块的实测高就是顶栏避让量（S2-1）。
 *
 * 为什么设置钮不跟着走：它不可隐藏（`set_pill_note` 写的「快门、缩略图与设置入口不可隐藏」），
 * 所以也不是 [HudEntry]，进不了位置表。它留原位、告警条也留原位，顶栏避让量继续吃实测值，
 * 竖 Dock 的上边界与 §59 的容量段宽度账都不受影响。
 */
@Composable
fun BoxScope.HudTopChrome(
    audioDegraded: Boolean,
    legacy: Boolean,
    deviceFailedHighFps: Boolean,
    effect: FrameEffect,
    renderMode: RenderMode,
    onSettingsClick: () -> Unit,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Column(
        modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }.value.roundToInt()) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = HudEdgePad, end = 8.dp, top = TopTopPad, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TopRowGap)
        ) {
            // 元信息那组搬进了可拖动的顶栏胶囊组，这里只剩一个占位权重把设置钮顶到右缘
            Spacer(Modifier.weight(1f))
            WotaIconButton(
                image = Icons.Filled.Settings,
                description = stringResource(R.string.cam_settings),
                onClick = onSettingsClick
            )
        }
        // 能力/权限告警条：高帧率降级 > 缺麦克风 > LEGACY > DIRECT 下特效失效
        val warnRes = when {
            deviceFailedHighFps -> R.string.cam_state_high_fps
            audioDegraded -> R.string.no_audio_record_tip
            legacy -> R.string.capability_limited_tip
            effect != FrameEffect.NONE && renderMode == RenderMode.DIRECT -> R.string.cam_direct_no_effect
            else -> null
        }
        warnRes?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = WotaColor.warn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp, end = 10.dp, top = 2.dp)
                    .wotaCard(WotaShape.pill)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ 底栏 Dock（六项第 3、8 条）

/**
 * 第 8 条的手势接缝：状态由调用方（录制页）持有，因为落位要写 `hud_layout`、
 * 跟手位移要喂给 [HudZoneBox] 的 graphicsLayer。
 *
 * [HudDockDrag.onAllowStart] 返回 false = 这次长按不许进入拖拽（录制中），调用方在里面给锁提示。
 */
data class HudDockDrag(
    val dragging: Boolean,
    val onAllowStart: () -> Boolean,
    val onDragY: (Float) -> Unit,
    val onDrop: () -> Unit,
    val onBlocked: () -> Unit
)

/**
 * 底栏 Dock（[HudZone.BOTTOM]）：缩略图 / 快门 / 镜头三件事共用一枚紧凑悬浮胶囊底板，
 * 外加录制态「水滴融入 / 细胞分裂」（第 3 条）与长按换栏（第 8 条）。
 *
 * ## 两个不变量同时成立
 * ① 底板只包住内容：`底板宽 = 2 × 槽宽 + 录制键 + 2 × (条目间距 + 底板内边距)`，
 *    槽宽 = `max(缩略图实测宽, 镜头那颗实测宽)`；材质沿用 [wotaCard] 那一套令牌（hudScrim + 顶部高光
 *    描边），不加投影。#71 第一批起这层材质由 [com.wotagei.cam.ui.anim.wotaDockShell] 在
 *    **绘制期**自绘（形状要跟着进度收拢，静态 Shape 做不到），令牌一个没换、`p = 0` 那一帧与原来同形。
 *    #84 步骤 2 又加了一档：**霜真在屏幕上时这层 fill 让位**，同一枚可见矩形注册进
 *    `camera/FrostCardTable`，由 GL 贴「霜 + 圆角 + 底板色」——描边、两颗条目、录制键一概不动，
 *    开关关掉（或 DIRECT / 链停用）就是①这一条原本的样子。旧文档那四条"预览层之上禁模糊"
 *    已被 docs/plan/14 §五作废，别再照着它们判这块玻璃是 bug。
 * ② 录制键中心恒等于**底板中心**（等宽槽是唯一的承重条件），而底板摆在**可视窗口**水平中心——
 *    居中父区域就是套了 `safeDrawingPadding()` 的那块整宽安全区（旧写法在这里再扣一笔写死的 34dp
 *    右缘避让，两个横屏姿态里都往左偏 34px，任务 #68 已删）。落位只写 y、x 恒哨兵，
 *    所以「快门中心＝可视窗口水平中心」在定稿位与用户搬过的上栏/下栏都成立；
 *    用户搬的始终是"已经居中的那一整块"的上下，这条算式本身一行没动。
 *    算式：设底板全宽 W、内边距 P、槽宽 S、录制键宽 R，内容区宽 = W − 2P = 2S + R + 2G（G 为条目间距）。
 *    录制键在内容区里由 [Alignment.Center] 定位 ⇒ 它到左内边缘 = S + G + R/2 ⇒
 *    录制键中心 = P + S + G + R/2 = (W − 2P)/2 + P = **W/2** = 底板中心。
 *    S 只由 `max(左, 右)` 决定，两颗各自显示与否只改变 S 的大小、不改变"两槽等宽"这件事。
 *    #70 A 之后右下那几颗常驻读数与这枚底板**共用底部那一条横带**（各占一半：底板居中、读数贴右缘），
 *    它们在底板之外、一行都没进上面这笔算式；反过来也不许为了塞进读数去加第三个槽或改 S——
 *    加了就等于把快门从可视中心挪走，那条不变量是本函数唯一的承重条件。
 * · 镜头开：S = max(34, 63) = 63 → W = 2×63 + 50 + 2×(12+8) = **216dp**（100% 字体缩放）
 * · 镜头关（或被编辑页挪去别的容器，两种都是 [showLens] = false，那颗不在这枚 Dock 里组合）：
 *   S = max(34, 0) = 34 → W = **158dp**
 * · 录制中保持**等宽空槽**（审查定版第 2 条）：空出来那段不裁、不缩，改它就要动动画期间的布局参数。
 *
 * ## 槽位用 `widthIn(min = slotDp)` 而不是 `width(slotDp)`（B4 修的几何缺陷）
 * 强制等宽会把槽内胶囊**压回槽宽**：120% 文本高度时 `WotaChip` 三字下限 46.8 + 24 = **70.8dp**，
 * 而第一帧的兜底槽宽只有 63dp。写 `width(63.dp)` 时那颗报不回自然宽（只会回报 63），
 * 于是 `S = max(34, 63) = 63` 永远收敛不到真值、底板停在 216dp，胶囊右内边距被挤成约 4dp
 * （文字不裁但观感偏一边）。换成 `widthIn(min =)` 之后：宽的那一颗**恰好等于 S**
 * （`S = max(左, 右)` 里的大者就是它自己的自然宽），窄的那一侧只是内边距里多一段等宽空区
 * ⇒ 两槽实测回报仍对称、不变量②同形成立，且 120% 那档底板自己长到 2×70.8 + 50 + 40 = 231.6dp。
 *
 * ## 第 3 条：录制态吸收与分裂，只作用这两颗（码率那颗不参与）
 * 进度 = `mergeProgressWithHook(plan, recording || dragging, anim, 钩子钉值)`，而那条函数只是
 * `mergeProgressOf(plan, absorbed, anim?.value)` 的一层上游包装（#73 的取证钩子；钉值为 null 时逐字等价），
 * 几何与纪律全在
 * [com.wotagei.cam.ui.anim.LiquidMerge]（B4 的拖拽动感复用同一套连通体几何，不写第二份腰公式）：
 * - **不动布局参数**：两颗的原位槽位宽度全程不变，动画只作用 translation / scale / alpha / path，
 *   所以底板既不重排也不跳动。就近锚点也不打架：进度 0 时那几项变换全是单位变换，
 *   而录制中这三颗只回锁提示、不开浮层，`pillAnchor` 回报的矩形没有任何一条路径会读到位移态
 *   （面板已开再按录制那条由 `LaunchedEffect(recording) { pop = null }` 收掉，S3-2）。
 * - **底板整枚收拢进键形**（#71 第一批第 1 件）：可见轮廓由 [com.wotagei.cam.ui.anim.DockShell] 按 p
 *   同时收缩长轴与短轴（216×60 → recordRing 46），半径恒 `min(w,h)/2` ⇒ 全程是体育场形、
 *   `w == h` 那一帧就是正圆。**没有 scaleX/scaleY**（216×60 不可能等比变圆，缩放必然把描边压扁），
 *   也没有动布局盒（`.width(dockW)` 那行是锁死的，见上面不变量①②）。反方向就是 p 反向播。
 * - **本体位移的纲 = 条目中心到键心的实测距离**（#71 第一批第 2 件，**已放开**）：`chipTravelPx(p, delta)`
 *   在 p=1 时把那颗的中心正好送到键心，进度夹 0..1 ⇒ 不穿过键心、反向过冲也不冲出原位。
 *   旧上限 `min(圆心距 × 0.16, clearance)`（实测 10.96/13.28dp）就是"只是原地淡化"的算术原因，已撤。
 *   撤它**不是**"点不到了没关系"，靠的是下面那两层命中保证；`chipAlpha` 那条淡出曲线降为**收尾配角**
 *   （只处理液滴与键重合处的残留），不再是那颗消失的手段。
 *   ⚠ 第一批只在**纯函数层**放开了这条：同一层 graphicsLayer 里 Compose 先平移再绕 pivot 缩放，
 *   `translationX` 被 `scaleX = chipScale(p)` 乘掉，按算式那颗只能走 72% 的路（p=1 还差 38px 才到键心）。
 *   第二批把位移与缩放拆成两层（外层只 translationX），于是「p=1 落到键心」第一次真的可能发生在像素上
 *   ——静态推理，真机未验（钩子 `pin=1` 一张图判）。
 * - **#71 第二批：接触颈的端点走视觉圆心，光感只靠亮缘**。颈（连通体）画在 [wotaPillHost] 里，
 *   它的端点是 [chipVisualCx] = 布局圆心 + 与那颗**同源**的那个数（[chipTravelForScene]），
 *   端点半径是 [chipVisualRadiusPx] = 布局内切圆半径 × 同一个 `chipScale`。
 *   用回 `scene.cx(...)` 就是"颈钉在原位、那颗飞走了"——布局矩形不反映那颗自己的 graphicsLayer 位移。
 *   观感那一侧这一条只走亮缘（颈是自由 Path，A2 的画板只吃「矩形 + 圆角」，罩不住它；
 *   minSdk 29 / 主测机 API 30 上 `RenderEffect` 也不可用）：同一张 Path 再描一遍
 *   径向亮缘，峰值落在录制键自身的圆周（接触圈），颜色两端都取既有令牌，笔刷在组合期 remember、
 *   每帧只 `setLocalMatrix`，于是绘制阶段仍然零分配。
 * - **#73 命中权交接（放开位移的硬前置，本批一行没削弱）**：
 *   ① 吸收期（进度 > 0）那两颗**不装点击链**——判据是纯函数 [chipClicksAccepted]，落地方式是缩略图那颗
 *     条件拼 `Modifier.clickable`、镜头那颗经 `gatedClick` 把动作摘成 null（[WotaChip] 的 `onClick == null`
 *     分支本来就不 install clip+clickable）。**不是** `clickable(enabled = false)`：那种写法节点还在。
 *     闸门值走 `derivedStateOf`，所以每帧只重算谓词、只有跨过 0 那两次翻转才重组本容器。
 *   ② 录制键 [RecordZIndex] 压在两颗之上：绘制与命中两层同时解决（证据见 RecordButton 那处注释）。
 *   ③ 底板那枚长按换栏探测器落在键上时**不接管**（[dragOwnedByRecordKey]），这一指整个留给键。
 *     ⚠ 放开位移后那颗会压进键的绘制区，"停止录制那一指还灵不灵"只有真机点得出（两层保证都在，
 *     但命中顺序的最终裁决不许靠读代码断定）。
 * - **可打断**：进度由 [animateFloatAsState] 驱动，中途反向时从**当前值**继续，不跳回起点。
 * - **PLAIN 档直接切换**：[mergePlanFor] 给 PLAIN 返回 `animated=false`，调用点**不创建**动画状态
 *   （S3-1），进度走 [mergeProgressOf] 只认开关态 0/1、位移走 [chipTravelForScene] 恒 0、绘制层第一条就
 *   return。这是"确实消失"而不是"变快"：没有动画状态，也就没有动画窗口。
 * - 12 号包写的"跟手"是**拖拽**语境的词：录制态那一路的触发是状态切换、没有指针可跟，所以那一路
 *   只有可打断；**第 8 条的换栏拖拽才是跟手那一路**（[drag]：整枚 Dock 的位移直接跟着指针）。
 *
 * ## 第 8 条：长按换栏
 * 长按这枚底板 → [HudDockDrag.onAllowStart]（录制中/收尾中返回 false 并给锁提示）→ 吸收两颗
 * （进度与录制态共用同一条 [animateFloatAsState]，所以"进入拖拽"和"开始录制"是同一段动画，
 * 不是第二套实现）→ 上下拖（[graphicsLayer] 跟手）→ 松手按 [dockSnapY] 落到上栏或下栏、
 * 写进 `hud_layout` 的底栏容器 y → 播分裂回原位（进度反向，中间态从当前值续）。
 */
@Composable
fun HudBottomZone(
    showLens: Boolean,
    ctx: HudCtx,
    drag: HudDockDrag?,
    modifier: Modifier = Modifier
) {
    val motion = LocalMotion.current
    val plan = remember(motion.mode) { mergePlanFor(motion.mode) }
    val scene = remember { MergeScene() }
    val density = LocalDensity.current
    // 吸收态判据（与已装机 r10/r11 同一条，快照那一版曾把它换成 `recording || busy || dragging`，本轮退回）：
    // **只有 START 算录制态吸收**——ctx.recording 就是 `recStatus == RecordStatus.START`（调用点唯一喂法），
    // ctx.busy（PREPARE / STOPPING）**不参与**：PREPARE 期会话还没起来，两颗该留在原位可点；
    // 一进 STOPPING 就立刻开始细胞分裂，不等收尾跑完再分裂。
    // ctx.busy 仍然有消费方（RecordButton 的转圈与顶栏状态那颗），只是不再决定吸收态。
    // 第 8 条的换栏拖拽是新增的那一档：拖拽中两颗同样处于吸收态。
    val absorbed = ctx.recording || drag?.dragging == true
    // PLAIN 档**不创建**动画状态（S3-1）：anim 为 null，连 120ms 的 tween 都不跑
    val anim = if (plan.animated) animateFloatAsState(if (absorbed) 1f else 0f, motion.float) else null
    // 进度只经 mergeProgressOf 这一条桥取（S4-1 要测的就是它）；#73 的取证钩子只是这条桥**上游**的
    // 一个可选覆盖（pinned 非 null 才顶掉动画值，null 时逐字退回原来的表达式），PLAIN 不吃钩子值
    // 由那条桥自己的 `plan.animated` 分支保证，这里不重复判断。
    val progress: () -> Float = { mergeProgressWithHook(plan, absorbed, anim?.value, MergeDebugHook.pinnedProgress) }
    // #73 第 2 件：吸收期那两颗**不装点击链**。这里读 progress 用的是 derivedStateOf，
    // 所以每帧只重算谓词、不重组——只有"跨过 0"那两次翻转才让本 composable 重组一次。
    // 不能直接 `progress() <= 0f` 写在组合期：那会让整个底栏每帧重组（08:79 帧率红线，
    // 也是本文件"进度只在 draw / graphicsLayer 阶段读"那条纪律的由来）。
    val clicksAccepted by remember(plan, absorbed, anim) {
        derivedStateOf { chipClicksAccepted(progress()) }
    }
    // 位移上限的**纲**（#71 第一批第 2 件）：条目中心到键心的这段实测距离本身，进度 1 就落到键心。
    // 旧版的 `min(圆心距 × TRAVEL_FRACTION, clearance)` 夹子（底栏实测只有 10.96/13.28dp）已撤——
    // 它就是"只是原地淡化"的算术原因。撤它靠的是两层已落地的命中保证（#73，本批一行没削弱）：
    // 吸收期那两颗不 install 点击链（chipClicksAccepted → gatedClick / 条件拼 clickable），
    // 且录制键以 RecordZIndex 在绘制与命中两层压在它们之上；**从不拿 alpha 当命中屏蔽**。
    // 为什么放开之后不需要给条目加 clip，算式见 LiquidMerge.chipTravelPx 的 KDoc（端点线性 ⇒ 只看两端）。
    // 两槽各自兜底（S2-1：以前一处 63dp 兼两槽，实测前左槽按右槽的宽度算，底板宽到 216dp 才收敛）
    val lensFallbackPx = remember(density) { with(density) { DockSlotSpace.toPx() }.roundToInt() }
    val thumbFallbackPx = remember(density) { with(density) { ThumbBoxSpace.toPx() }.roundToInt() }
    val padPx = remember(density) { with(density) { WotaSpace.s.toPx() } }
    val gapPx = remember(density) { with(density) { WotaSpace.m.toPx() } }
    val recordPx = remember(density) { with(density) { WotaHit.recordTouch.toPx() } }
    var thumbW by remember { mutableIntStateOf(thumbFallbackPx) }
    var lensW by remember { mutableIntStateOf(if (showLens) lensFallbackPx else 0) }
    // 那颗被 CamPill 关掉时不会再有布局回调，宽度必须主动归零，否则底板留在上一轮的宽度上；
    // 重新打开时先按右槽自己的兜底宽起算，少一次"从 0 长出来"的观感。
    // 左槽（缩略图）恒在树里，第一帧布局就回报实测宽 ⇒ 两槽都"首帧兜底、第二帧实测接管"
    LaunchedEffect(showLens) { lensW = if (showLens) lensFallbackPx else 0 }
    val slotW = MergeSlot.slotWidthPx(thumbW.toFloat(), lensW.toFloat())
    val slotDp = remember(slotW, density) { with(density) { slotW.toDp() } }
    val dockW = remember(slotW, recordPx, gapPx, padPx, density) {
        with(density) { MergeSlot.dockWidthPx(slotW, recordPx, gapPx, padPx).toDp() }
    }
    // 长按换栏的手势状态：只在处理器之间传递，不进快照状态（拖动手势每帧写它会白白重组）
    val dragActive = remember { booleanArrayOf(false) }
    val allowStart = rememberUpdatedState(drag?.onAllowStart)
    val blocked = rememberUpdatedState(drag?.onBlocked)
    val dragY = rememberUpdatedState(drag?.onDragY)
    val drop = rememberUpdatedState(drag?.onDrop)
    Box(
        modifier
            .width(dockW)
            // 底板轮廓改为**绘制期自绘**（#71 第一批第 1 件）：原来这里是 `wotaCard(WotaShape.pill)`，
            // 形状静态、只能"整枚底板原地淡出"。现在同一套令牌（hudScrim + acrylicBorder + hairline）
            // 在 draw 阶段按 p 画一枚居中的体育场形，长轴与短轴一起收拢到 recordRing。
            // ⚠ 上一行的 `.width(dockW)` 与下面内层三格排布、`MergeSlot.dockWidthPx` 那笔账**一行都不许动**：
            //   布局盒全程锁死 216×60，动了就重排、快门跳、不变量②（键心＝可视水平中心）当场崩。
            .wotaDockShell(WotaHit.recordRing, progress)
            // 连通体画在底板轮廓之上、两颗之下：宿主节点自己报原点与尺寸，两颗报窗口坐标，绘制时相减
            .mergeAnchor(scene, MergeScene.CANVAS)
            .wotaPillHost(scene, plan, progress)
            .then(
                // 键取 Unit：这条分支只在 drag 非空时组合，写 `drag != null` 恒为 true（编译器会点名），
                // 而回调全走 rememberUpdatedState，不需要跟着 drag 换实例重启探测器
                if (drag == null) Modifier else Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            // #73 第 3 条：底板那枚长按探测器挂在**父节点**上，zIndex 管不到父子之间，
                            // 所以手指落在录制键实测矩形里时底板**不接管**这一指（不置 dragActive、
                            // 也不给锁提示），让键自己收尾。顺带修掉今天"按住键不动 → 松手既换栏又切录制"
                            // 那一下双动作：那条路以前会进拖拽，现在根本不进。
                            // 判据是纯函数 dragOwnedByRecordKey（量不到键的矩形时返回 false，退回旧行为）。
                            if (!dragOwnedByRecordKey(scene, pos.x, pos.y)) {
                                if (allowStart.value?.invoke() == true) dragActive[0] = true
                                else blocked.value?.invoke()
                            }
                        },
                        onDrag = { change, amount ->
                            if (dragActive[0]) {
                                dragY.value?.invoke(amount.y)
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (dragActive[0]) {
                                dragActive[0] = false
                                drop.value?.invoke()
                            }
                        },
                        onDragCancel = {
                            if (dragActive[0]) {
                                dragActive[0] = false
                                drop.value?.invoke()
                            }
                        }
                    )
                }
            )
            // 这里的 vertical 与外层槽位的 bottom 就是 [BottomBarSpaceFallback] 的算式输入，
            // 改任一处等于改首帧兜底值（同源，别分叉）
            .padding(horizontal = WotaSpace.s, vertical = DockInnerPadV)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                // 见本函数 KDoc「槽位用 widthIn(min =)」那一段：强制等宽会让那颗报不回自然宽
                .widthIn(min = slotDp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .mergeAnchor(scene, MergeScene.THUMB)
                    // S2-1：左槽也回报实测宽（与右槽对称），兜底值只管第一帧之前的组合
                    .onSizeChanged { if (it.width != thumbW) thumbW = it.width }
                    // ⚠ **位移与缩放拆成两层**（#71 第二批）：同一层里 Compose 的变换顺序是
                    // 「先平移、再绕 pivot 缩放」⇒ 平移量会被 scaleX 乘掉，按算式那颗只能走 72% 的路
                    // （p=1 还差 38px 才到键心），而颈的端点吃的是**真**视觉圆心
                    // （`chipVisualCx = 布局圆心 + travel`）。拆成外层只平移、内层只 alpha/scale 之后，
                    // 外层平移落在内层缩放之外，量纲不被缩，那条算式才可能成立
                    // （静态推理，真机未验：钩子 pin=1 那张图判那颗到底落在哪儿）。
                    .graphicsLayer {
                        translationX = chipTravelForScene(scene, MergeScene.THUMB, plan, progress())
                    }
                    .graphicsLayer {
                        val p = progress()
                        alpha = LiquidMerge.chipAlpha(p)
                        val s = LiquidMerge.chipScale(p)
                        scaleX = s
                        scaleY = s
                    }
                    .size(ThumbBoxSpace)
                    .clip(WotaShape.small)
                    // #73 命中权交接：吸收期（进度 > 0）这一颗**不装点击链**，
                    // 而不是 `clickable(enabled = false)`——后者节点还在，能不能让开那一指未证。
                    // clip 与位移照旧（那是观感，与命中无关），所以断链只断点击这一件事。
                    .then(if (clicksAccepted) Modifier.clickable(onClick = ctx.onThumbClick) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                val uri = ctx.lastUri
                if (uri != null) {
                    VideoThumbnail(uri = uri, modifier = Modifier.matchParentSize(), px = 160)
                } else {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = stringResource(R.string.cam_gallery_entry),
                        tint = WotaColor.textLo,
                        modifier = Modifier.padding(6.dp).size(20.dp)
                    )
                }
            }
        }
        RecordButton(
            recording = ctx.recording,
            busy = ctx.busy,
            // #73 第 2 条：录制键在**绘制顺序**与**命中顺序**两层都要压在那两颗之上。
            // 证据（本机 compose-ui 1.5.4 的字节码，不是记忆）：`InnerNodeCoordinator.hitTestChild`
            // 遍历的是 `LayoutNode.getZSortedChildren()`，而且**从末尾往前**遍历；`performDraw`
            // 同一个表正向遍历。ZComparator 的比较键是 (zIndex, placeOrder)。
            // ⇒ 同层兄弟之间 zIndex **确实**同时决定绘制与命中（zIndex 高者先命中、后绘制＝画在上面）。
            // 但本批不把"吸收期那两根不吃这一指"押在这条上：那两条链是被 chipClicksAccepted
            // 结构性摘掉的（键在 p=1 时可点由那条 + 这条 zIndex 双保险成立），而父节点那枚长按探测器
            // 是 zIndex 管不到的另一层，见上面 dragOwnedByRecordKey 那一处。
            modifier = Modifier.align(Alignment.Center).zIndex(RecordZIndex).mergeAnchor(scene, MergeScene.RECORD),
            onClick = ctx.onRecordClick
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .widthIn(min = slotDp),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (showLens) {
                val lensEntry = HudEntry.of(CamPill.LENS)
                // 霜（#84 步骤 2）：这颗吃底板那块玻璃（`wotaDockShell` 自己在绘制期注册的是**可见**矩形，
                // 收拢动画跟得上），它自己不再注册一块；fill 让位由 ambient 决定
                CompositionLocalProvider(LocalHudFrostParent provides hudFrostInherit) {
                    HudEntryItem(
                        entry = lensEntry,
                        ctx = ctx,
                        // 底栏这颗**必须**留全局档：它的宽就是 `DockSlotSpace = 63dp` 那笔槽宽账的来源，
                        // 而槽宽是底板宽的输入、#71 收拢算式 w(0) 的起点（裁决仍只经 [chipTierFor] 一处）
                        tier = chipTierFor(HudZone.BOTTOM),
                        // #73：这颗与缩略图那颗同一条闸门（进度 > 0 就不装点击链）。
                        // 断链点在 HudEntryItem → gatedClick → WotaChip(onClick = null)，
                        // 不在这里另套一层 pointerInput：clickable/combinedClickable 是 WotaChip 内部的
                        // 条件修饰符，从它自己那条分支摘掉才是结构性断开
                        clicksAccepted = clicksAccepted,
                        // 锚点 + 实测宽 + 融合位移三件事都挂在这颗的同一个节点上
                        modifier = Modifier
                            .ghostWhileDragged(ctx.hiddenEntry == lensEntry)
                            .onSizeChanged { if (it.width != lensW) lensW = it.width }
                            .mergeAnchor(scene, MergeScene.LENS)
                            // 与缩略图那颗同一手法：**外层只管位移、内层只管 alpha 与缩放**，
                            // 免得同层里 translationX 被 scaleX 乘掉（理由见上面缩略图那处的注释）
                            .graphicsLayer {
                                translationX = chipTravelForScene(scene, MergeScene.LENS, plan, progress())
                            }
                            .graphicsLayer {
                                val p = progress()
                                alpha = LiquidMerge.chipAlpha(p)
                                val s = LiquidMerge.chipScale(p)
                                scaleX = s
                                scaleY = s
                            }
                    )
                }
            }
        }
    }
}

/** 录制键：外圈常驻，内部圆点（待机）↔ 方角块（停止），中间态用转圈 */
@Composable
private fun RecordButton(
    recording: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val motion = LocalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) motion.pressScale else 1f, motion.float)
    Box(
        modifier
            .size(WotaHit.recordTouch)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (recording && !busy) {
            // 呼吸光环：录制中这一圈缓慢涨落，余光里也能确认"还在录"；只动 scale/alpha，
            // 不碰布局参数（MotionSpec 的既有约束），所以不会把预览层挤一下
            val grow = androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(
                initialValue = 0.94f,
                targetValue = 1.14f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 1_600
                        0.94f at 0
                        1.14f at 800 with androidx.compose.animation.core.LinearOutSlowInEasing
                        0.94f at 1_600
                    }
                )
            )
            Box(
                Modifier
                    .size(WotaHit.recordRing)
                    .graphicsLayer {
                        scaleX = grow.value
                        scaleY = grow.value
                        alpha = 1f - (grow.value - 0.94f) / 0.2f * 0.72f
                    }
                    .border(2.dp, WotaColor.rec.copy(alpha = 0.5f), CircleShape)
            )
        }
        Box(
            Modifier
                .size(WotaHit.recordRing)
                .border(3.dp, if (recording) WotaColor.rec else WotaColor.textHi, CircleShape)
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(color = WotaColor.rec, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else if (recording) {
                Box(Modifier.size(WotaHit.recordStop).background(WotaColor.rec, RoundedCornerShape(5.dp)))
            } else {
                Box(Modifier.size(WotaHit.recordDot).background(WotaColor.rec, CircleShape))
            }
        }
    }
}

// ------------------------------------------------------------------ 位置表的组合期接入

/**
 * 读 `hud_layout` 并跟随它的变化：写入方（编辑页「保存」、录制页底栏换栏）走的是 `commit()`，
 * **磁盘写完成之后**这条监听才打到录制页，于是"保存后实时生效"与"强杀进程仍保得住"同时成立
 * （`apply()` 是异步落盘，改完就被 `am force-stop` 杀掉会整批丢掉，任务 #69 那族误判的根因）。
 * 页面在栈里被销毁重进时读的还是同一份 prefs，杀进程重进也在。
 *
 * 与 [WotaSettings.KEY_MOTION] 在 MainActivity 里的 `WatchMotion` 同一条手法，**不走 applyDefaultsOnce**
 * （那条链进程内只套一次，会把编辑页改动挡在门外）。
 */
@Composable
fun rememberHudLayout(prefs: android.content.SharedPreferences): HudLayoutTable {
    var table by remember(prefs) { mutableStateOf(WotaSettings.hudLayout(prefs)) }
    androidx.compose.runtime.DisposableEffect(prefs) {
        @Suppress("DEPRECATION")
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == null || key == WotaSettings.KEY_HUD_LAYOUT) table = WotaSettings.hudLayout(p)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return table
}

/** 实测矩形的高换算成 dp（IntRect? 方便"没量到 = 0"这一档写进类型里）；钳制与带高算式只读这一条 */
internal fun IntRect?.dpHeightToDp(density: androidx.compose.ui.unit.Density): Int =
    this?.let { with(density) { it.height.toDp().value.roundToInt() } } ?: 0

/** 实测矩形的宽换算成 dp；钳制算式要的容器宽只读这一条，两页不许各写一份换算 */
internal fun IntRect?.dpWidthToDp(density: androidx.compose.ui.unit.Density): Int =
    this?.let { with(density) { it.width.toDp().value.roundToInt() } } ?: 0

/**
 * 把控件的窗口矩形回报进锚点表（[PillKey] → [IntRect]，就近浮层的定位来源）。
 *
 * 只在矩形**真变了**才写——布局期写状态会重组，重组又重测，值没变还照写就是空转。
 * 录制页与编辑页各持一张表，但只有这一条写入路，所以"控件挪了位置、锚点还留在老地方"
 * 这一族（§69）在两个页面同时被封住。
 */
fun Modifier.pillAnchorReport(table: MutableMap<PillKey, IntRect>, key: PillKey): Modifier =
    pillAnchor { rect -> if (table[key] != rect) table[key] = rect }
