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
 * 层级靠「卡片透明度」与「极淡高光边」拉开，不靠描边和投影。
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
     * 取景器上的常驻控件卡：25% 透明（alpha 0xBF）暖黑。
     * 保留上一轮定的 25% 透明度——参考图的卡片本身就是靠透出画面来分层的。
     */
    val hudScrim = Color(0xBF14120F)

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
    /** 胶囊：参考图顶栏读数、状态点、模式选中态全是全圆角 */
    val pill = RoundedCornerShape(percent = 50)

    /** 常驻参数卡与直方图卡 */
    val card = RoundedCornerShape(14.dp)
    val large = RoundedCornerShape(18.dp)

    /** 兼容上一轮命名，值收敛到新体系 */
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
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
