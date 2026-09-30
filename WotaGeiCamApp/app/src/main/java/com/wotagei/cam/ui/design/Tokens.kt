package com.wotagei.cam.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌（唯一真源）。规范见 docs/plan/11，取代 docs/plan/09 的 Fluent 体系。
 * 观感基准是用户 2026-09-27 提供的取景器参考图：暖黑底 + 深色半透明圆角卡 + 胶囊 chip + 圆形图标钮，
 * 层级靠「卡片透明度」与「极淡高光边」拉开，不靠投影。
 *
 * ⚠ #84 之后这条口径补一半：**透明度这一档在霜开着时不再是唯一的分层手段**——底板透出来的是
 * 「被离屏糊过的底层内容」（docs/plan/14 §二 A2 混合：GL 只出板、描边/高亮/内容仍归 Compose）。
 * 旧文档那四条「预览层之上禁模糊」的红线已被 14 号计划 §五作废，这里不再重复旧禁令；
 * 但**投影仍然不用**，描边仍然只用 1dp 高光边（[WotaStroke.hairline]）那一档。
 *
 * 字段名沿用上一轮（surface/layer/outline/…），只换值：这样整套色板一次切换，
 * 不必把三屏所有引用点改一遍，也不会出现半新半旧的中间态。
 */
object WotaColor {
    /** 无画面时的底：暖黑（原冷灰 0xFF0E1116 与参考图气质不符） */
    val bg = Color(0xFF12100E)
    val surface = Color(0xFF1C1917)
    val layer = Color(0xFF26221F)
    val outline = Color(0xFF3A342F)

    /** 薄荷绿：自动档与生效值，参考图里 ISO AUTO / 剩余容量都是这一支 */
    val accent = Color(0xFF8FD9A8)
    val accentDim = Color(0xFF3E6E52)
    val onAccent = Color(0xFF0C2417)
    val rec = Color(0xFFE5484D)
    val warn = Color(0xFFF5A524)

    /** 对焦框与「AF-C · 已锁定曝光」状态点的琥珀 */
    val focus = Color(0xFFE8A33D)

    val textHi = Color(0xFFF3F1EE)
    val textMid = Color(0xFFCFC9C2)
    val textLo = Color(0xFFA9A39C)

    /** 参考线：非红线一律半透明白，红线固定红 */
    val refLine = Color(0xCCFFFFFF)
    val refLineRed = Color(0xFFE53935)

    /** 弹窗/抽屉底：比常驻卡更实，保证长列表可读 */
    val scrim = Color(0xB3000000)
    val acrylicBorder = Color(0x26FFFFFF)

    /**
     * 取景器上的常驻控件卡：**74.9% 不透明**（alpha 0xBF = 191/255 ⇒ 透光只有 25.1%）暖黑。
     *
     * 这里原本写成「25% 透明」——那是把 alpha 当透明度读的反向口径（docs/plan/14 §二 顺手记下的数值
     * 事实：不动这一档，做完模糊也几乎看不出来，75% 的板把下面的霜全盖死了）。
     * 毛玻璃接上之后它只剩两个身份：
     * ① 霜**没在屏幕上**时（开关关 / DIRECT / 离屏链停用）的兜底底衬——关掉开关要能完整回到旧观感；
     * ② 霜底板的**颜色**来源（[hudFrostTintRgb] 只取它的 RGB）；透光档位另走 [frostScrimAlphaFor]
     *    那条桥，绝不允许"颜色里带一档 alpha、片元里再乘一档"这种两处相乘。
     */
    val hudScrim = Color(0xBF14120F)

    /**
     * 霜底板用的颜色：只取 [hudScrim] 的 R/G/B（0..1，顺序就是 GL `vec3 uTint` 的顺序），
     * alpha 在这里没有用处——透光由 [frostScrimAlphaFor] 单独决定。
     */
    fun hudFrostTintRgb(out: FloatArray) {
        require(out.size >= 3) { "底板色要 3 个 float，实际 ${out.size}" }
        out[0] = hudScrim.red
        out[1] = hudScrim.green
        out[2] = hudScrim.blue
    }

    /** 白描边控件（缩略图角标）上的字色：压在任何画面上都读得清 */
    val outlineInk = Color(0xFF14120F)
}

/**
 * 字阶：参考图的规律是「dim 小标签在上、粗数值在下」，三档就够
 * label 11 / value 16 / chip 13。lineHeight 一律显式写，「文本高度」设置要能缩放它。
 */
object WotaType {
    val label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    val value = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    val chip = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

    /** 快门/ISO/时长/角度等数值一律等宽，避免跳字 */
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
}

/** 4pt 栅格（参考图的卡片内边距比 8pt 更紧，留 4 的档位） */
object WotaSpace {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

object WotaShape {
    /**
     * 圆角**半径值**（dp）——同一件事的唯一真源。
     *
     * ⚠ 声明顺序有讲究：object 的属性按声明顺序初始化，所以半径必须排在下面那几枚 Shape 之前
     * （反过来写的话 `RoundedCornerShape(radiusCard)` 会在 radiusCard 还没赋值时取到 0.dp，
     * 整套卡片一次变成直角，且编译器一声不响）。
     *
     * 存在的唯一理由：毛玻璃画板走的是 GL 片元里的 rounded-rect SDF（`camera/Shaders.frostPlateFragment`），
     * 它要的是一个**像素半径**，而 `Shape` 是 Compose 侧的抽象、GL 读不到（`RoundedCornerShape` 也反解不出
     * 半径来）。所以「这张卡的圆角是多少」必须有一份能被两边同时引用的数：卡片侧用它建 Shape，
     * 画板侧用它换算 px 半径送进矩形表。
     * [WotaShape.pill] 与 `CircleShape` 没有这一档：它们的半径恒等于**短边一半**，
     * 由注册点在量到实测尺寸之后现场算（送进表的是负数哨兵，见 camera 侧的 `frostResolveRadiusPx`）。
     */
    val radiusCard: Dp = 14.dp
    val radiusLarge: Dp = 18.dp
    val radiusSmall: Dp = 8.dp
    val radiusMedium: Dp = 12.dp

    /** 胶囊：参考图顶栏读数、状态点、模式选中态全是全圆角 */
    val pill = RoundedCornerShape(percent = 50)

    /** 常驻参数卡与直方图卡（半径与上面那批令牌同源） */
    val card = RoundedCornerShape(radiusCard)
    val large = RoundedCornerShape(radiusLarge)

    /** 兼容上一轮命名，值收敛到新体系 */
    val small = RoundedCornerShape(radiusSmall)
    val medium = RoundedCornerShape(radiusMedium)
}

/**
 * 霜底板的**透光档位**（#84 步骤 2 · docs/plan/14 §二 那张定量表的落点）。
 *
 * 一张卡片能透多少光，不由观感说了算，由它承载的**最弱那级文字**说了算：
 * 合成色 = α·底板色 + (1−α)·底层内容，worst case 取最亮的背景（天空/白墙），门槛是 WCAG AA。
 * 那一节的表算出来只有两档可用：
 *
 * | alpha | 透光 | textHi | textMid | textLo |
 * |---|---|---|---|---|
 * | 0xB3 | 29.8% | 6.21 ✅ | 4.26 ⚠ | 2.80 ❌ |
 * | 0xA6 | 34.9% | 5.16 ✅ | 3.55 ❌ | 2.33 ❌ |
 *
 * 所以**档位区间是死的**（0xA6 ~ 0xB3），比 0xA6 更透就连主文字都掉出 AA；
 * 而 `textLo`（次级标签）在半透底板上根本没有达标的一档 —— 历史欠账，14 号计划 §二 明令**不要**
 * 混进 #82/#84 顺手修。它的落点就是这里：承载 `textLo` 的那几颗（右下读数块的胶囊、AE 锁那颗）**本轮
 * 不接霜**，继续用 [WotaColor.hudScrim] 的实底，而不是给它们一档不合格的透光。
 */
enum class HudInkLevel {
    /** 只有 [WotaColor.textHi]（粗数值/主标签）与自带实底的选中态（accent 自己是不透明的） */
    PRIMARY,

    /** 出现 [WotaColor.textMid]、或 `rec`/`warn` 这类没进审计表的着色 */
    SECONDARY
}

/** [HudInkLevel] 对应的底板色不透明度（0..1，透光 = 1 − 它） */
private const val FROST_ALPHA_GUARDED = 0xB3 / 255f

/** 见 [FROST_ALPHA_GUARDED]：只容得下主文字的那一档，比它更透就出 AA */
private const val FROST_ALPHA_CLEAR = 0xA6 / 255f

/**
 * 「这张板承载什么文字」→「该用哪档透光」的**桥函数**（AGENTS.md 要的那种桥：配置进、行为出）。
 *
 * 它必须真的被调用：底板注册点只许把 [HudInkLevel] 送进矩形表这条路的最后一环（见
 * `ui.design.wotaHudCard`），不许在任何地方手写一个 alpha 字面量。
 * 测试侧钉的是这条桥本身——把 PRIMARY 那一条分支删掉（两档并成一档），
 * `theClearTierIsMoreTranslucentThanTheGuardedOne` 立刻红；把两档值挪出 0xA6~0xB3 区间，
 * `bothTiersStayInsideTheApprovedTranslucencyBand` 立刻红。
 */
fun frostScrimAlphaFor(ink: HudInkLevel): Float = when (ink) {
    HudInkLevel.PRIMARY -> FROST_ALPHA_CLEAR
    HudInkLevel.SECONDARY -> FROST_ALPHA_GUARDED
}

/**
 * 描边宽度：`wotaCard` 的高光边与融合连通体的描边共用一档。
 * 提到令牌前这个 1dp 在 Widgets.kt 与 LiquidMerge.kt 各写一遍（审查 S4-2 的可选项），
 * 两处一旦分叉，卡片边与腰边就不同粗。
 */
object WotaStroke {
    val hairline: Dp = 1.dp
}

/** 动效时长：FLUENT 档沿用，参考图本身是静态图，动效按原规范不劣化即可 */
object WotaMotion {
    /** 按压反馈不跟着放长：按下必须即时，只有状态变化的收尾按用户定的 0.75 秒走 */
    const val PRESS_MS = 120

    /** FLUENT 档收尾时长：用户 2026-09-28 定「0.75 秒完成」 */
    const val COMMIT_MS = 750
    const val ENTER_MS = 750
}

/** 触控命中区下限与录制键三态尺寸（80% 缩放后的值沿用上一轮结论） */
object WotaHit {
    val min: Dp = 48.dp
    val iconButton: Dp = 38.dp
    val iconGlyph: Dp = 19.dp
    val recordTouch: Dp = 50.dp
    val recordRing: Dp = 46.dp
    val recordDot: Dp = 34.dp
    val recordStop: Dp = 18.dp
}
