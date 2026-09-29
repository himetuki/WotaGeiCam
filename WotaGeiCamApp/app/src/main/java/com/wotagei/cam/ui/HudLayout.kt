package com.wotagei.cam.ui

import com.wotagei.cam.core.CamPill
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.ui.anim.hudPerRowFor
import com.wotagei.cam.ui.anim.hudRoomDp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「编辑控件」页与录制页共用的位置模型（docs/plan/13 第 5、8 条 + 用户定的两级模型）。
 *
 * ## 两级，而不是每颗一个坐标
 * 可拖动的单位是 **5 枚容器**（[HudZone]），每枚一份 (x, y)——**底栏只有 y 一轴**（x 恒哨兵）；
 * 容器**内**的条目可以拖拽换序，也可以挪到另一枚容器。这样「所有控件都能拖」成立，同时保住两件事：
 * - 悬浮 Dock 的观感（底板仍只包住内容，不是通栏条）；
 * - 「快门中心＝可视窗口水平中心」：底栏那两枚等宽槽的算式（[com.wotagei.cam.ui.anim.MergeSlot]）
 *   一行没动，而它的 x 永远是哨兵 ⇒ 横向落位始终由 `fillMaxWidth` + 居中决定（父区域就是套了
 *   `safeDrawingPadding()` 的那块，挖孔换边时中心跟着换），用户搬的只是"已经居中的那一整块"的上下。
 *
 * 明确**不做**（留给后续批次，见 B4 交付报告）：跨容器的自由坐标（每颗一份 x,y）、改尺寸、改层级。
 * 重叠只提示不禁止。
 *
 * ## 本文件是纯 Kotlin
 * 没有 Android/Compose 类型依赖，所以 schema 编解码、默认表、越界钳制、隐藏后位置保留、换序/跨容器、
 * 底栏 y 落位全部能在 JVM 单测里真跑（`HudLayout*Test`），不需要 Robolectric。
 * 唯一的跨文件调用是 `ui.anim` 里那两条**只吃 Float 的纯函数**（`hudPerRowFor` / `hudRoomDp`），
 * 它们本来就在录制页与编辑页的调用点上共用（S3-5 同源），不带来任何 Compose 依赖。
 */

/** 条目在容器内的排布主轴：决定「拖到第几格」怎么算 */
enum class HudAxis { ROW, COLUMN, GRID }

/**
 * 可拖动的容器。[key] 是持久化里的单字母 id（改它等于改存量配置语义，只能加不能改）。
 *
 * 每枚容器的**默认位置**不写在这里，而是「原生对齐」那条分支（见 [ZonePlacement.isDefault]）：
 * 默认值＝B1–B3 定稿时的对齐方式与内边距，由组合期实测避让量算出来，
 * 硬写一串 dp 数就等于把「8dp 起始内边距」这类既有真源复制一份到持久化层，迟早分叉。
 */
enum class HudZone(val key: String, val axis: HudAxis) {
    /** 顶栏胶囊组：录制计时/状态那颗 + 画幅/容量段 */
    TOP("T", HudAxis.ROW),

    /** 左竖 Dock：创作项 */
    LEFT("L", HudAxis.COLUMN),

    /** 右竖 Dock：取景辅助与变焦/对焦/防抖 */
    RIGHT("R", HudAxis.COLUMN),

    /** 右下常驻读数块（六项第 4 条搬到录制键右侧那一块） */
    READOUT("D", HudAxis.GRID),

    /**
     * 底栏 Dock：缩略图 + 快门 + 镜头那颗。
     * 它的 y 就是第 8 条的「上栏/下栏」，**x 不进表**（恒哨兵 ⇒ 横向永远重新居中，见 [normalizedPosOf]）。
     */
    BOTTOM("B", HudAxis.ROW);

    companion object {
        val ALL: List<HudZone> = values().toList()
        fun fromKey(raw: String?): HudZone? = ALL.firstOrNull { it.key == raw }
    }
}

/**
 * 一颗可编辑控件的引用。
 *
 * 两个命名空间必须分开编码：`CamPill.ZOOM`（右竖 Dock 那颗胶囊）与 `HudItem.ZOOM`（读数块那颗读数）
 * 是**两个东西**，共用 ordinal 会让持久化串自相矛盾。所以 id = 字母前缀 + ordinal：`P<n>` / `H<n>`。
 */
data class HudEntry(val kind: HudEntryKind, val ordinal: Int) {

    /** 对应 [CamPill] 开关位；[HudEntryKind.READOUT] 一律 null */
    val pill: CamPill? get() = if (kind == HudEntryKind.PILL) CamPill.ALL.getOrNull(ordinal) else null

    /** 对应 [HudItem] 开关位；[HudEntryKind.PILL] 一律 null */
    val item: HudItem? get() = if (kind == HudEntryKind.READOUT) HudItem.ALL.getOrNull(ordinal) else null

    /** 持久化 token；[pill]/[item] 都取不到（越界 ordinal）时仍是这个串，由 [HudEntry.fromId] 拒绝 */
    val id: String get() = (if (kind == HudEntryKind.PILL) "P" else "H") + ordinal

    /** 设置页/编辑页显示的名字（走资源，不散写字面量）；ordinal 越界的坏条目返回 null */
    val labelRes: Int? get() = pill?.labelRes ?: item?.labelRes

    /** 未编辑时的归属容器 = B1–B3 定稿位置 */
    val homeZone: HudZone
        get() = when (kind) {
            HudEntryKind.READOUT -> HudZone.READOUT
            // 认枚举名而不是 ordinal：新增一颗 CamPill 时这条 when 编译不过，逼着当场定容器；
            // 按 ordinal 写死数字位则会给它一个"看起来对"的默认落位，且整表错位测不出来
            HudEntryKind.PILL -> when (pill) {
                CamPill.SIZE, CamPill.STORAGE -> HudZone.TOP            // 顶栏两段
                CamPill.LENS -> HudZone.BOTTOM                           // 底栏那颗（六项第 7 条从顶栏搬来）
                CamPill.REFLINE, CamPill.MONITOR, CamPill.CURVE, CamPill.FLASH -> HudZone.LEFT
                CamPill.LEVEL, CamPill.VOLUME, CamPill.BT,
                CamPill.ZOOM, CamPill.FOCUS, CamPill.STAB -> HudZone.RIGHT
                // 越界 ordinal：[HudLayoutTable.normalize] 会把这种坏条目丢掉，这里只需给个不抛的值
                null -> HudZone.RIGHT
            }
        }

    override fun toString(): String = id

    companion object {
        /** 全部可编辑条目 = 13 颗 CamPill + 7 颗 HudItem；顺序即持久化里的稳定顺序 */
        val ALL: List<HudEntry> =
            CamPill.ALL.map { HudEntry(HudEntryKind.PILL, it.ordinal) } +
                HudItem.ALL.map { HudEntry(HudEntryKind.READOUT, it.ordinal) }

        /** 坏串（手改 prefs、版本不符、ordinal 越界）返回 null，由调用方丢弃而不是抛 */
        fun fromId(raw: String): HudEntry? {
            if (raw.length < 2) return null
            val kind = when (raw[0]) {
                'P' -> HudEntryKind.PILL
                'H' -> HudEntryKind.READOUT
                else -> return null
            }
            val n = raw.substring(1).toIntOrNull() ?: return null
            val size = if (kind == HudEntryKind.PILL) CamPill.ALL.size else HudItem.ALL.size
            return if (n in 0 until size) HudEntry(kind, n) else null
        }

        fun of(pill: CamPill) = HudEntry(HudEntryKind.PILL, pill.ordinal)
        fun of(item: HudItem) = HudEntry(HudEntryKind.READOUT, item.ordinal)
    }
}

enum class HudEntryKind { PILL, READOUT }

/**
 * 一枚容器的位置。
 *
 * 默认态 = **两轴都是哨兵**。"一轴绝对 + 一轴哨兵"里只有**底栏**是合法状态：它的 x 恒为哨兵
 * （横向永远跟着可视窗口居中，[clampZonePos] 与 [HudLayoutTable.normalize] 两处都把它抹成哨兵），
 * 长按换栏只写 y。其余四枚容器的半吊子串只能来自手改 prefs，一律按未编辑处理（[HudLayoutTable.normalize] 会抹平）。
 *
 * 绝对值是**容器左上角相对 root 安全区左上角**的 dp 偏移，不是相对原生对齐点的偏移——
 * 存绝对值才谈得上「钳进安全区」，也才让编辑页与录制页对同一个数给出同一个视觉位置。
 */
data class ZonePlacement(val xDp: Int, val yDp: Int) {
    /** 两轴都没绝对值 = 用户没把这枚容器拖离过定稿位置（底栏的 y-only 落位**不是**默认态） */
    val isDefault: Boolean get() = xDp < 0 && yDp < 0

    companion object {
        val DEFAULT = ZonePlacement(-1, -1)
    }
}

/** 一枚容器的完整状态：位置 + 条目顺序（顺序里**含被隐藏的条目**，见 [HudLayoutTable.visibleOrderOf]） */
data class ZoneState(val pos: ZonePlacement, val order: List<HudEntry>)

/**
 * 位置表本体（版本化）。持久化键 `hud_layout`，编解码见 [encode] / [decode]。
 */
data class HudLayoutTable(val version: Int, val zones: Map<HudZone, ZoneState>) {

    fun posOf(zone: HudZone): ZonePlacement = zones[zone]?.pos ?: ZonePlacement.DEFAULT

    /** 该容器的完整顺序（含被设置页关掉的条目） */
    fun orderOf(zone: HudZone): List<HudEntry> = zones[zone]?.order ?: emptyList()

    /**
     * 录制页/编辑页真正渲染的那份顺序：[visible]（设置里开着显示的条目）过滤一遍，**顺序仍来自表**。
     *
     * 「控件被隐藏、再打开显示时位置保持用户改过的值」就落在这里 + [moveEntryTo]：
     * 隐藏不写表，所以表里一直是用户放好的那个位置。
     */
    fun visibleOrderOf(zone: HudZone, visible: Set<HudEntry>): List<HudEntry> =
        orderOf(zone).filter { it in visible }

    /**
     * 写一枚容器的绝对位置。
     *
     * 底栏那枚**只该传 y**（x 传哨兵 -1，见 [normalizedPosOf]）：写入方是录制页的长按换栏与编辑页的整枚拖动，
     * 两处都只搬上下栏，写进绝对 x 就等于把快门横向钉死。越界钳制由调用方先做（[clampZonePos] 会把
     * 底栏的 x 强制抹回哨兵，调用方就算传了真值也存不进去）。
     */
    fun withZonePos(zone: HudZone, xDp: Int, yDp: Int): HudLayoutTable {
        val cur = zones[zone] ?: ZoneState(ZonePlacement.DEFAULT, emptyList())
        return copy(zones = zones + (zone to cur.copy(pos = ZonePlacement(xDp, yDp))))
    }

    /**
     * 把条目挪到 [zone] 的第 [index] 格（同容器换序 = 同一个调用）。
     *
     * 一次完整的移动 = 从**所有**容器里摘掉它，再插进目标容器第 [index] 格（越界夹到 [0, size]）。
     * 先摘后插保证「一颗控件同时只属于一枚容器」，也让「隐藏中的条目被挪走」这条路径与
     * 可见条目走的是同一份数据（[visibleOrderOf] 只是过滤，不改表）。
     */
    fun moveEntryTo(entry: HudEntry, zone: HudZone, index: Int): HudLayoutTable {
        val orders = LinkedHashMap<HudZone, MutableList<HudEntry>>()
        HudZone.ALL.forEach { z ->
            val state = zones[z]
            val list = (state?.order ?: defaultOrderOf(z)).toMutableList()
            list.remove(entry)
            orders[z] = list
        }
        val target = orders.getValue(zone)
        target.add(index.coerceIn(0, target.size), entry)
        val nextZones = HudZone.ALL.associateWith { z ->
            ZoneState(zones[z]?.pos ?: ZonePlacement.DEFAULT, orders.getValue(z).toList())
        }
        return HudLayoutTable(version, nextZones)
    }

    /** 条目当前归属（表里没有时回 [HudEntry.homeZone]） */
    fun sourceZoneOf(entry: HudEntry): HudZone =
        HudZone.ALL.firstOrNull { entry in orderOf(it) } ?: entry.homeZone

    /** 整张表的条目（按容器分组、容器按 [HudZone.ALL] 顺序） */
    fun allEntries(): List<HudEntry> = HudZone.ALL.flatMap { orderOf(it) }

    /**
     * 补全 + 去重 + 丢坏值：
     * - 同一个 id 出现在两枚容器 → **先到先得**，后出现的丢掉（手改 prefs 造成的重复不能让一颗控件渲染两遍）；
     * - 表里完全没有的条目 → 追加到它的 [HudEntry.homeZone] 末尾（新增 CamPill 位时老配置自动补齐）；
     * - ordinal 越界的条目 → 丢弃。
     */
    fun normalize(): HudLayoutTable {
        val seen = HashSet<HudEntry>()
        val placed = LinkedHashMap<HudZone, MutableList<HudEntry>>()
        HudZone.ALL.forEach { placed[it] = mutableListOf() }
        for (zone in HudZone.ALL) {
            for (e in orderOf(zone)) {
                if (e.pill == null && e.item == null) continue
                if (seen.add(e)) placed.getValue(zone).add(e)
            }
        }
        for (e in HudEntry.ALL) {
            if (seen.add(e)) placed.getValue(e.homeZone).add(e)
        }
        val nextZones = LinkedHashMap<HudZone, ZoneState>()
        HudZone.ALL.forEach { z ->
            nextZones[z] = ZoneState(normalizedPosOf(z), placed.getValue(z).toList())
        }
        return HudLayoutTable(version, nextZones)
    }

    /**
     * 位置段的合规化（[normalize] 的其中一步，单独露出来给用例直接打）：
     * - **底栏只认 y**：绝对 x 一律抹回哨兵。落一次位就把 x 写成绝对值会把"快门跟随可视中心"冻住
     *   （90↔270 翻转与横竖换档都不再重新居中，B4 审查 S1 那条设计缺陷），所以这里连存量脏数据一起治；
     * - 其余四枚：一轴绝对、一轴哨兵的半吊子（只能来自手改 prefs）按未编辑处理，见 [ZonePlacement] 的说明。
     */
    fun normalizedPosOf(zone: HudZone): ZonePlacement {
        val raw = posOf(zone)
        return if (zone == HudZone.BOTTOM) {
            if (raw.yDp >= 0) ZonePlacement(-1, raw.yDp) else ZonePlacement.DEFAULT
        } else {
            if (raw.xDp >= 0 && raw.yDp >= 0) raw else ZonePlacement.DEFAULT
        }
    }

    /**
     * 编码：`v1;T,8,4,P11,P12;L,-1,-1,P6,P7,P8,P9;…`
     *
     * 分号分「版本段 / 五个容器段」，逗号分「x / y / id 串」；哨兵 `-1` = 该容器仍在默认位置。
     * 与 [CurveStack] 一样是一条紧凑字符串，同一个 prefs 文件、同样的 `putString + runCatching` 风格。
     */
    fun encode(): String = buildString {
        append("v").append(version)
        for (zone in HudZone.ALL) {
            append(';').append(zone.key).append(',')
            append(posOf(zone).xDp).append(',').append(posOf(zone).yDp)
            append(',')
            val order = orderOf(zone)
            order.forEachIndexed { i, e -> if (i > 0) append(','); append(e.id) }
        }
    }

    companion object {
        /** 当前 schema 版本；改格式就 +1，并在 [decode] 里对旧版本做处置 */
        const val CURRENT_VERSION = 1

        /** 未编辑过的初值：位置全默认、顺序全按 [HudEntry.homeZone] 内的枚举声明序 */
        fun default(): HudLayoutTable = HudLayoutTable(
            CURRENT_VERSION,
            HudZone.ALL.associateWith { ZoneState(ZonePlacement.DEFAULT, defaultOrderOf(it)) }
        ).normalize()

        /**
         * 默认表里每枚容器的条目顺序 = **B1–B3 定稿代码里的书写顺序**，逐个对过源码：
         * · 顶栏 `TopCapsule` 的 buildList：SIZE → STORAGE
         * · 左竖 Dock：参考线 → 屏幕监看 → RGB 曲线 → 闪光灯
         * · 右竖 Dock：姿态仪 → 音量表 → 蓝牙 → 变焦 → 对焦 → 防抖
         * · 读数块：`HudItem.typesOf(mask)` 的枚举序（快门 帧率 码率 ISO EV 白平衡 变焦）
         * · 底栏：只有镜头那颗是可编辑条目（缩略图与快门不可隐藏，不进表）
         */
        fun defaultOrderOf(zone: HudZone): List<HudEntry> = when (zone) {
            HudZone.TOP -> listOf(HudEntry.of(CamPill.SIZE), HudEntry.of(CamPill.STORAGE))
            HudZone.LEFT -> listOf(
                HudEntry.of(CamPill.REFLINE), HudEntry.of(CamPill.MONITOR),
                HudEntry.of(CamPill.CURVE), HudEntry.of(CamPill.FLASH)
            )
            HudZone.RIGHT -> listOf(
                HudEntry.of(CamPill.LEVEL), HudEntry.of(CamPill.VOLUME), HudEntry.of(CamPill.BT),
                HudEntry.of(CamPill.ZOOM), HudEntry.of(CamPill.FOCUS), HudEntry.of(CamPill.STAB)
            )
            HudZone.BOTTOM -> listOf(HudEntry.of(CamPill.LENS))
            HudZone.READOUT -> HudItem.ALL.map { HudEntry.of(it) }
        }

        /**
         * 解码：任何异常（null / 空串 / 缺段 / 未知容器 id / 版本不符 / 数字炸）都**不抛**，
         * 坏的那一段丢掉、其余保留，整张表最后过一次 [normalize]。
         * 版本号**比本工程新**时整表按默认处理：新格式老代码读不懂，硬解会写出错位置。
         */
        fun decode(raw: String?): HudLayoutTable {
            if (raw.isNullOrBlank()) return default()
            val parts = raw.split(';')
            val header = parts.firstOrNull().orEmpty()
            val version = header.removePrefix("v").toIntOrNull()
            if (version == null || version > CURRENT_VERSION) return default()
            if (version < CURRENT_VERSION) return default() // v1 是第一版，还没有旧的版要迁
            val zones = LinkedHashMap<HudZone, ZoneState>()
            for (seg in parts.drop(1)) {
                val f = seg.split(',')
                val zone = HudZone.fromKey(f.getOrNull(0)) ?: continue
                val x = f.getOrNull(1)?.toIntOrNull() ?: -1
                val y = f.getOrNull(2)?.toIntOrNull() ?: -1
                val order = f.drop(3).mapNotNull { HudEntry.fromId(it) }
                zones[zone] = ZoneState(ZonePlacement(x, y), order)
            }
            return HudLayoutTable(version, zones).normalize()
        }
    }
}

// ------------------------------------------------------------------ 越界钳制与落位（纯函数）

/**
 * 安全区与两条实测避让量，单位全 dp。
 *
 * - [width]/[height]：root 容器套上 `safeDrawingPadding()` 之后的**实测尺寸**（不是 `screenWidthDp`）。
 *   它就是位置表 (x, y) 的坐标参考：挖孔在哪条边，系统就把那条边让掉，于是这里**不需要**任何
 *   "按方向补一笔右缘让位"的字段——这里曾有 `endInsetDp`（喂的是写死的 `VisibleEndInset = 34.dp`），
 *   2026-09-29 真机复测证明那条"可视右缘 1532"是伪值、68px 让位来自会换边的挖孔，字段已删净
 *   （docs/plan/13 §九·补）。安全区本身已经把避让算进 [width]，再扣一次就是双重让位。
 * - [topAvoidDp]/[bottomAvoidDp]：顶栏与底栏的**实测**避让量（S2-1 / S2-2 C 那两路回报），
 *   不是新写的魔法数。编辑页的这两个值是它自己那条操作栏与底栏 Dock 实占带的高。
 *   ⚠ **#70 A 之后这两条轴是"按容器分别喂"的**：底栏那一排的带高只喂给 `LEFT` / `RIGHT`
 *   （它们几何上真会叠在底栏上）与"读数块退化到整排之上"那一档；`READOUT` 拿到的是
 *   [ReadoutRowPlan.bottomAvoidDp]，默认与底栏**同一条基线**（=`BottomBarOuterPadV`），
 *   所以同一帧里不同容器读到的 `bottomAvoidDp` 不再同一个数——这是有意的，别再合并回去。
 */
data class HudAreaDp(
    val width: Int,
    val height: Int,
    val topAvoidDp: Int,
    val bottomAvoidDp: Int
)

/** 一个 dp 矩形（安全区局部坐标，左上原点） */
data class HudRectDp(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

data class HudPointDp(val x: Int, val y: Int)

/**
 * 横向可放区间 `[0, 安全区宽 − 容器宽]`。安全区宽已经是"套上 `safeDrawingPadding()` 之后"的量，
 * 挖孔让掉的边在里面扣过了，所以这里不再按方向另扣一笔（旧写法扣 34dp，两个横屏姿态里必错一次）。
 * 容器比安全区还宽（120% 文本 + 极窄分屏）时上限夹成 0，也就是贴左放，**不许为负**。
 */
fun clampZoneX(area: HudAreaDp, widthDp: Int): Int =
    (area.width - widthDp).coerceAtLeast(0)

/**
 * 纵向可放区间。
 * - [HudZone.TOP] / [HudZone.BOTTOM]：整条安全区（顶栏本来贴顶、底栏可以搬到上栏，见第 8 条）；
 * - 其余三枚：`[topAvoid, height − bottomAvoid − h]` —— 这就是「钳制必须吃 topBarH / bottomBarH 实测避让量」的落点。
 *
 * 带高不够（告警条 + 满配读数把带挤没了）时退成整条安全区，**区间永不为负**，
 * 否则 `coerceIn` 会抛而整个 HUD 就没了。
 */
fun clampZoneYRange(zone: HudZone, area: HudAreaDp, heightDp: Int): IntRange {
    val band = zone != HudZone.TOP && zone != HudZone.BOTTOM
    val lo = if (band) area.topAvoidDp.coerceAtLeast(0) else 0
    val hi = if (band) area.height - area.bottomAvoidDp - heightDp else area.height - heightDp
    // 带高不够（告警条 + 满配读数把带挤没了，或容器本身比屏还高）时退成整条安全区，
    // 区间不许为负 —— `coerceIn` 遇到 lo > hi 是直接抛的，那一抛整个 HUD 就没了
    return if (hi < lo) 0..(area.height - heightDp).coerceAtLeast(0) else lo..hi
}

/**
 * 绝对位置钳进安全区（尺寸未测到时传 0，等价于「只保证左上角不越界」）。
 *
 * **底栏的 x 在这里就被抹成哨兵**：它只能上下搬（第 8 条的上栏/下栏），写入方就算把实测左缘换算成
 * 绝对 x 送进来也存不进表——那会让快门从此不再跟随可视中心（90↔270 翻转、横竖换档都不重新居中，
 * B4 审查 S1 那条）。[HudLayoutTable.normalizedPosOf] 在读表时同样抹一次，把存量脏数据一起治掉。
 */
fun clampZonePos(zone: HudZone, xDp: Int, yDp: Int, wDp: Int, hDp: Int, area: HudAreaDp): ZonePlacement {
    val yRange = clampZoneYRange(zone, area, hDp)
    val x = if (zone == HudZone.BOTTOM) -1 else xDp.coerceIn(0, clampZoneX(area, wDp))
    return ZonePlacement(x, yDp.coerceIn(yRange.first, yRange.last))
}

/**
 * 外接矩形重叠检测（重叠**只提示不禁止**，所以这里只负责「提示看得懂」：把重叠的两枚容器报出来）。
 * 返回按 [HudZone.ALL] 顺序去重后的容器名对，调用方拿它拼一句人话。
 */
fun overlappingZones(rects: Map<HudZone, HudRectDp>): List<Pair<HudZone, HudZone>> {
    val out = mutableListOf<Pair<HudZone, HudZone>>()
    val list = HudZone.ALL.filter { rects.containsKey(it) }
    for (i in list.indices) {
        for (j in i + 1 until list.size) {
            val a = rects.getValue(list[i])
            val b = rects.getValue(list[j])
            if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) {
                out += list[i] to list[j]
            }
        }
    }
    return out
}

/**
 * 落点在哪枚容器：命中**最里面**（面积最小）的那枚，都不命中返回 null（= 这次拖动不改归属）。
 * 取最小面积而不是第一个命中，是因为读数块贴在底栏 Dock 右上时会嵌套；按顺序取第一枚就会把
 * 「拖进读数块」判成「拖进底栏」。
 */
fun zoneAt(pointer: HudPointDp, rects: Map<HudZone, HudRectDp>): HudZone? =
    rects.filter { it.value.contains(pointer.x, pointer.y) }.minByOrNull { it.value.width * it.value.height }?.key

/**
 * 松手后条目该插到目标容器的第几格。
 *
 * [items] 是目标容器**当前渲染顺序**的实测矩形（安全区局部坐标，与 [pointer] 同一坐标系）。
 * - ROW / COLUMN：沿主轴数「有几颗的中心比指针更靠前」；
 * - GRID（读数块那种一行多颗、按 [HudZone] 换行）：先按上下边界把行分簇，再在指针那一行里数横向。
 *
 * 返回 0..size，越界由 [HudLayoutTable.moveEntryTo] 再夹一次。
 */
fun dropIndexFor(axis: HudAxis, items: List<HudRectDp>, pointer: HudPointDp): Int {
    if (items.isEmpty()) return 0
    return when (axis) {
        HudAxis.ROW -> items.count { (it.left + it.right) / 2 < pointer.x }
        HudAxis.COLUMN -> items.count { (it.top + it.bottom) / 2 < pointer.y }
        HudAxis.GRID -> {
            val rows = clusterRows(items)
            val row = rows.firstOrNull { pointer.y in it.first().top..it.last().bottom }
                ?: rows.minByOrNull { abs((it.first().top + it.last().bottom) / 2 - pointer.y) }
            if (row == null) items.size else {
                val beforeRows = rows.takeWhile { it !== row }.sumOf { it.size }
                beforeRows + row.count { (it.left + it.right) / 2 < pointer.x }
            }
        }
    }
}

/** 按竖直方向重叠把条目分簇成「行」（保持入参顺序，簇内也按入参顺序） */
private fun clusterRows(items: List<HudRectDp>): List<List<HudRectDp>> {
    val rows = mutableListOf<MutableList<HudRectDp>>()
    for (rect in items.sortedBy { it.top }) {
        val hit = rows.firstOrNull { row ->
            val r = row.first()
            rect.top < r.bottom && r.top < rect.bottom
        }
        (hit ?: mutableListOf<HudRectDp>().also { rows += it }).add(rect)
    }
    // 还原入参顺序，调用方要的是「按当前顺序插到哪」
    return rows.map { row -> items.filter { it in row } }.filter { it.isNotEmpty() }
}

// ------------------------------------------------------------------ 第 8 条：底栏 Dock 的上栏/下栏

/**
 * 底栏 Dock 的两档 y（用户第 8 条：长按收起两颗 → 上下拖 → 松手落「上栏 / 下栏」）。
 *
 * **下栏 = 定稿位置**（底边贴安全区底再让开 [bottomPadDp]，调用方传 `BottomBarOuterPadV`），
 * **上栏 = 下栏再上一整排**，间距一枚 [gapDp]（调用方传令牌 `WotaSpace.s`，这里不散写观感值）：
 * `上栏 = 安全区高 − 下边距 − 排高 − 排高 − gap`。全程只有实测高与令牌，没有机型数值。
 */
fun dockLowerY(area: HudAreaDp, heightDp: Int, bottomPadDp: Int): Int =
    (area.height - bottomPadDp - heightDp).coerceAtLeast(0)

fun dockUpperY(area: HudAreaDp, heightDp: Int, bottomPadDp: Int, gapDp: Int): Int =
    (dockLowerY(area, heightDp, bottomPadDp) - heightDp - gapDp).coerceAtLeast(0)

/**
 * 拖动位移 → 落在哪一栏（**只有两档**，第 8 条要的是「上栏 / 下栏」而不是自由 y）。
 *
 * [deltaYDp] 是相对下栏的竖直位移（屏幕坐标，向上为负）：往上过半程去上栏，其余一律回下栏。
 * 上档取的是 [dockUpperY]，它本身已经被安全区顶边钳过，所以拖出上边界也只会贴到能放的最上面一格。
 */
fun dockSnapY(area: HudAreaDp, heightDp: Int, bottomPadDp: Int, gapDp: Int, deltaYDp: Int): Int {
    val lower = dockLowerY(area, heightDp, bottomPadDp)
    val upper = dockUpperY(area, heightDp, bottomPadDp, gapDp)
    val pitch = lower - upper
    return if (pitch > 0 && -deltaYDp.toFloat() >= pitch / 2f) upper else lower
}

/** px → dp 的整数换算（编辑页与录制页共写位置时用，避免出现第二套换算式） */
fun pxToDp(px: Float, density: Float): Int =
    if (density <= 0f) 0 else (px / density).roundToInt()

/**
 * 右竖 Dock 的下界要让开「读数块所在的那一横带」，但**不许低于底栏那一排自己的下界**。
 *
 * #70 A 之前这条算式是 `bottomAvoidDp + readoutHeightDp`：那时读数块浮在底栏**上方**，两截让位是串联的，
 * 加在一起才对。现在两块共用底栏那一行（同一基线），是**并联**关系 ⇒ 取大而不是求和，
 * 否则右 Dock 白白多让一整排（横屏实测会多让 66dp，把最高的那颗条目挤进滚动区）。
 *
 * - [readoutBottomDp]：读数块自己的底边让位（`ReadoutRowPlan.bottomAvoidDp`）。与底栏同基线时是
 *   `BottomBarOuterPadV`（6dp）；窄窗退化到"让到整排之上"时它本身就是底栏带高，
 *   此时 `readoutBottomDp + readoutHeightDp` 又回到旧的串联算式，**退化路径一条没丢**。
 * - 读数块空了回报 0，这一截缝自己收回去（与改前同一条语义）。坏值（负高）按 0 处理。
 */
fun areaForRightDock(area: HudAreaDp, readoutHeightDp: Int, readoutBottomDp: Int): HudAreaDp =
    area.copy(
        bottomAvoidDp = maxOf(area.bottomAvoidDp, readoutBottomDp + readoutHeightDp.coerceAtLeast(0))
    )

// ------------------------------------------------------------------ #70 A：读数块与底栏那一行

/**
 * 读数块与底栏 Dock 之间那一条**底部横带**的关系，一次算清（任务 #70 A 的唯一裁决点）。
 * 两个底边档位都由 [planReadoutRow] 的入参喂进来：`bottomRowPadDp`（与底栏同基线那一档）与
 * `dockStripDp`（底栏那一排的带高，退化时用）。
 *
 * - [sharesDockRow]：true = 这一帧读数块与底栏**同基线**（两块底边停在同一个 `bottomRowPadDp`，
 *   读数块不再吃"底栏带高"那笔手算让位）。这正是用户 11:39 要的「放在和底 Dock 栏同一行」。
 * - [bottomAvoidDp]：喂给读数块那枚容器 `HudAreaDp.bottomAvoidDp` 的值。两档：
 *   同基线 = `bottomRowPadDp`；退化 = `dockStripDp`（让到整排之上）。
 * - [perRow]：一行几颗，来自 `hudPerRowFor`，喂的是 [roomWidthDp]。
 * - [roomWidthDp]：这一帧真正可用的**块内宽**（已经扣掉设计留白与块自身内边距）。
 *
 * ## 为什么必须有"退化"这一档，以及它的判据为什么长这样
 * 底栏在可视窗口里**水平居中**（`clampZonePos` 把它的 x 恒抹成哨兵，见本文件第 8 条那一段），
 * 所以它与读数块争的是同一段右半区：底栏右缘 = `安全区宽/2 + 底板宽/2`。
 * 竖屏 360dp 上底板 216dp ⇒ 那条带只剩 56dp（180 − 108 − 8 间距 − 8 留白），
 * **连一颗读数胶囊（估宽 90dp）都放不下**。
 * 那种时候只有两条路：压住底栏那颗镜头胶囊（等于挡住入口，不可接受），或让到整排之上（就是旧行为，
 * 而且此时两枚容器**几何上真会重叠**——恰好落在"让位量只许用在几何上真会重叠的那一对"这条规矩里）。
 *
 * 判据取 `hudPerRowFor(…, 右半可用宽) ≥ 2`，不是"能不能装下一颗"：
 * - `hudPerRowFor` 只在「两颗 + 行距 + 安全余量 24dp ≤ 可用宽」时才给 2 以上，
 *   所以选了这一档之后**块宽必然 ≤ 可用宽 − 24dp**，同一行不会撞底栏，这是它自带的算术保证；
 * - 给到 1 就意味着那条右半带连"两颗 + 余量"都容不下，那是"这一帧没有横行可言"的信号，
 *   此时退回整排之上并用**整幅宽**取档（竖屏回到改前的 3 颗一行，不产生新的裁字面）。
 * - 反过来若判据用"实测块宽 vs 右半带"作为**唯一**判据，就会形成 `档位→块宽→档位` 的闭环：竖屏会出现
 *   一行 3 颗（宽）↔ 一列 3 颗（窄）来回翻的**振荡**。所以估宽判据（`perRow ≥ 2`）是主判据，
 *   实测块宽只是它旁边的一道**安全网**（[readoutWidthDp] > 那条带的宽度时退回整排之上）：
 *   退化档取的整幅宽只会让块更宽 ⇒ 一旦退出去就稳在里面，不产生来回。
 */
data class ReadoutRowPlan(
    val sharesDockRow: Boolean,
    val bottomAvoidDp: Int,
    val perRow: Int,
    val roomWidthDp: Float
)

/**
 * 与底栏同一行时**整块读数**（含它自己的内边距）允许占的那一段宽度：底栏右缘 + 一枚间距 → 安全区右缘 − 设计留白。
 *
 * 与 [readoutRoomBesideDockDp] 是同一笔账的两个口径：那条给 `hudPerRowFor` 用（块**内**可用宽，
 * 已再扣掉左右各 [com.wotagei.cam.ui.anim.HudBlockPadDp]），这条给"实测块宽 ≤ 容得下吗"用。
 * 两处共用同一个表达式，不许各写一份。
 */
fun readoutStripWidthDp(safeWidthDp: Int, dockWidthDp: Int, endPadDp: Float, dockGapDp: Float): Float =
    (safeWidthDp / 2f - dockWidthDp / 2f - dockGapDp - endPadDp).coerceAtLeast(0f)

/**
 * 与底栏同一行时读数块的可用内宽 = 「底栏右缘 + 一条设计间距」到「安全区右缘 − 设计留白」这一段，
 * 再扣掉块自身左右内边距各一份（走 [hudRoomDp]，与整幅取档那条同一个表达式、同一批真源）。
 *
 * 推导：底栏居中 ⇒ 右缘在 `safeWidthDp/2 + dockWidthDp/2`；读数块右缘钉在 `safeWidthDp − endPadDp`；
 * 两者之间再留一枚 [dockGapDp]（调用方传令牌 `WotaSpace.s`）。
 * 所以 `hudRoomDp(safeWidthDp/2 − dockWidthDp/2 − dockGapDp, endPadDp)` 就是这个数（[hudRoomDp] 里
 * 还会再扣 `2 × HudBlockPadDp`，那正是块内左右各一份内边距）。
 * 极窄时整条被 [hudRoomDp] 夹到 0，不会给负数。
 */
fun readoutRoomBesideDockDp(safeWidthDp: Int, dockWidthDp: Int, endPadDp: Float, dockGapDp: Float): Float =
    hudRoomDp(safeWidthDp / 2f - dockWidthDp / 2f - dockGapDp, endPadDp)

/** [ReadoutRowPlan] 的算式本体。两页（录制页 / 编辑页）必须调这一条，不许各写一份判据（S3-5 同源） */
fun planReadoutRow(
    readoutCount: Int,
    fontScale: Float,
    safeWidthDp: Int,
    dockWidthDp: Int,
    dockStripDp: Int,
    bottomRowPadDp: Int,
    endPadDp: Float,
    dockGapDp: Float,
    readoutWidthDp: Int = 0
): ReadoutRowPlan {
    val besideRoom = readoutRoomBesideDockDp(safeWidthDp, dockWidthDp, endPadDp, dockGapDp)
    val perRowBeside = hudPerRowFor(readoutCount, fontScale, besideRoom)
    // 安全网：`hudPerRowFor` 的字宽是**算术估计**（汉字 1 em、拉丁 0.6 em，见其 KDoc），
    // 「感光度 AUTO」「曝光补偿 +1.0」这类副标签比它假设的 2 汉字宽，估宽会偏小 ⇒ 光靠估宽判断可能真压上底板。
    // 所以再用**实测块宽**兜一道：量到且已经越过那一段宽度，就退回整排之上（量不到那一帧 0 = 先按估宽走，
    // 下一帧实测接管，与 topBarH / dockStripH 同一套"两轮收敛"手法）。
    // 这条判据不会来回翻：退化档取的是整幅宽，块只会更宽（perRow 单调），一旦判 false 就稳在 false。
    val stripWidth = readoutStripWidthDp(safeWidthDp, dockWidthDp, endPadDp, dockGapDp)
    val measuredFitsBeside = readoutWidthDp <= 0 || readoutWidthDp <= stripWidth
    if (perRowBeside >= 2 && measuredFitsBeside) {
        return ReadoutRowPlan(true, bottomRowPadDp, perRowBeside, besideRoom)
    }
    val fullRoom = hudRoomDp(safeWidthDp.toFloat(), endPadDp)
    return ReadoutRowPlan(false, dockStripDp, hudPerRowFor(readoutCount, fontScale, fullRoom), fullRoom)
}

/**
 * 第 8 条进入拖拽的 gate（13 号计划：正在录的时候不能让人把底栏搬走）。
 *
 * PREPARE / START / STOPPING 三态一律拒绝——「停止录制」是取景页最高优先级的手势，而拖拽会把
 * 那一指的触摸盒整枚搬走；ERROR 不算忙（Recorder 那一侧已经复位，此时搬底栏不影响停录）。
 */
fun dockDragBlocked(status: com.wotagei.cam.camera.RecordStatus): Boolean =
    status == com.wotagei.cam.camera.RecordStatus.PREPARE ||
        status == com.wotagei.cam.camera.RecordStatus.START ||
        status == com.wotagei.cam.camera.RecordStatus.STOPPING

// ------------------------------------------------------------------ 容器内的条目渲染分组

/**
 * 把一枚容器的可见条目切成「一行一行」，五枚容器共用这一条算式（B1–B3 的高度账就靠它）。
 *
 * - [HudZone.TOP] / [HudZone.BOTTOM]：横排一行；
 * - [HudZone.LEFT]：一行一颗；
 * - [HudZone.RIGHT]：**S2-2 B 那条并排规则保留**——姿态仪与音量表在顺序里相邻时并成一行两列
 *   （省下 ≈75dp，正好把变焦/对焦从折叠线下捞回来）。顺序被用户拆开就不再并排，
 *   这是"用户可以重排"与"横屏 360dp 带高不够"两条要求唯一能同时成立的写法；
 * - [HudZone.READOUT]：按 [perRow] 分行（[com.wotagei.cam.ui.anim.hudPerRowFor] 按可用宽与字体缩放取档）。
 */
fun hudRowGroups(zone: HudZone, order: List<HudEntry>, perRow: Int): List<List<HudEntry>> = when (zone) {
    HudZone.TOP, HudZone.BOTTOM -> if (order.isEmpty()) emptyList() else listOf(order)
    HudZone.LEFT -> order.map { listOf(it) }
    HudZone.RIGHT -> {
        val groups = mutableListOf<List<HudEntry>>()
        val level = HudEntry.of(CamPill.LEVEL)
        val volume = HudEntry.of(CamPill.VOLUME)
        var i = 0
        while (i < order.size) {
            val next = order.getOrNull(i + 1)
            if (order[i] == level && next == volume) {
                groups += listOf(level, volume)
                i += 2
            } else if (order[i] == volume && next == level) {
                groups += listOf(volume, level)
                i += 2
            } else {
                groups += listOf(order[i])
                i++
            }
        }
        groups
    }
    HudZone.READOUT -> order.chunked(if (perRow < 1) 1 else perRow)
}

/**
 * 右竖 Dock 的「并排」判据（单独暴露给用例）：姿态仪与音量表**相邻**才并排。
 * 用户把音量表挪到别处、或把它关掉了（[order] 里没有它），都必须退回一行一颗。
 */
fun rightDockPacksLevelAndVolume(order: List<HudEntry>): Boolean {
    val level = order.indexOf(HudEntry.of(CamPill.LEVEL))
    val volume = order.indexOf(HudEntry.of(CamPill.VOLUME))
    return level >= 0 && volume >= 0 && kotlin.math.abs(level - volume) == 1
}
