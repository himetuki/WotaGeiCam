package com.wotagei.cam.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.anim.MotionMode
import com.wotagei.cam.ui.theme.WotaRec
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * 收藏红心的动画层（纯函数，JVM 可测）：设计稿视频时间线 → 每个元素在 t∈[0,1] 上的取值。
 *
 * 来源：Bencho "Like"（MIT，bencho.dev/licence）的红心部分。**刻意不移植数字滚动**——本工程
 * 的收藏没有计数，用户口径是"只要红心相关的动画"。
 *
 * 时间线（设计稿原值，一条主进度 820ms 统摄三个元素，避免三条动画各自为政）：
 * - **绽放环** 560ms：0% 半径 0.15 的实心圆（边框 17 = 半径，等于整块填满）→ 40% 长到 1.0 仍是实心
 *   → 75% 边框收到 2（从中间空出来变成环）→ 100% 边框 0、放大到 1.12、淡出。颜色在 40% 由
 *   红转紫（设计稿 #e5484d → #cc8ef5）。
 * - **红心** 760ms：0~28% 不可见（让环先起飞）→ 62% 弹到 1.25 → 100% 回到 1（"红心从无到有、
 *   冲过自己的尺寸再回来"）。
 * - **七对彩点** 620ms，延迟 200ms：沿各自辐条从 12px 甩到 (18 + fly×14 + far)，端点缩到 0；
 *   12% 才显形（设计稿的 opacity 爬升），侧向 ±4px、第二颗略靠后 5px。
 */
internal object LikeBurst {

    /** 主进度总时长：三元素里最长的那条（红心 760ms）+ 彩点延迟余量 */
    const val DURATION_MS = 820

    /** 彩点的相对窗口（毫秒，相对主进度起点） */
    private const val DOT_START_MS = 200f
    private const val DOT_DURATION_MS = 620f
    private const val BLOOM_MS = 560f
    private const val HEART_MS = 760f

    private fun phase(t: Float, startMs: Float, durMs: Float): Float =
        ((t * DURATION_MS - startMs) / durMs).coerceIn(0f, 1f)

    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    /** 红心缩放：0 →（28% 起）1.25 →（62% 起）1 */
    fun heartScale(t: Float): Float {
        val p = phase(t, 0f, HEART_MS)
        return when {
            p < 0.28f -> 0f
            p < 0.62f -> 1.25f * smooth((p - 0.28f) / 0.34f)
            else -> 1.25f - 0.25f * smooth((p - 0.62f) / 0.38f)
        }
    }

    /** 绽放环的缩放（0.15 → 1 → 1.12） */
    fun bloomScale(t: Float): Float {
        val p = phase(t, 0f, BLOOM_MS)
        return if (p < 0.4f) {
            0.15f + 0.85f * (p / 0.4f)
        } else {
            1f + 0.12f * smooth((p - 0.4f) / 0.6f)
        }
    }

    /**
     * 绽放环的"边框占半径"比例：1.0 = 实心圆（边框宽 = 半径），→ 2/17（设计稿 2px 边框），
     * → 0（完全空掉、只剩一圈淡出）。
     */
    fun bloomBorderFraction(t: Float): Float {
        val p = phase(t, 0f, BLOOM_MS)
        return when {
            p < 0.4f -> 1f
            p < 0.75f -> 1f - (1f - 2f / 17f) * smooth((p - 0.4f) / 0.35f)
            else -> (2f / 17f) * (1f - (p - 0.75f) / 0.25f)
        }
    }

    /** 绽放环透明度：75% 之前恒 1，之后收到 0 */
    fun bloomAlpha(t: Float): Float {
        val p = phase(t, 0f, BLOOM_MS)
        return if (p < 0.75f) 1f else (1f - (p - 0.75f) / 0.25f).coerceIn(0f, 1f)
    }

    /** 红 → 紫的混合量（0 = 设计稿红、1 = 设计稿紫）：40% 处一次跳到位 */
    fun bloomPurple(t: Float): Float = if (phase(t, 0f, BLOOM_MS) < 0.4f) 0f else 1f

    /** 彩点的进度；< 0 表示还没开始（延迟窗口）。取值范围 [-1, 1] */
    fun dotProgress(t: Float): Float {
        val raw = (t * DURATION_MS - DOT_START_MS) / DOT_DURATION_MS
        return if (raw < 0f) -1f else raw.coerceAtMost(1f)
    }

    /** 彩点沿辐条的径向距离（设计 px）：12 → 18 + fly×14 + far */
    fun dotRadiusPx(p: Float, fly: Float, farPx: Float): Float =
        if (p < 0f) 0f else 12f + (6f + fly * 14f + farPx) * p

    /** 彩点的侧向偏移（设计 px）：起点 ±4，终点 ±1.6 倍 */
    fun dotSidePx(p: Float, sidePx: Float): Float = if (p < 0f) 0f else sidePx * (1f + 0.6f * p)

    /** 彩点缩放：终点缩到 0（设计稿 scale: 0） */
    fun dotScale(p: Float): Float = if (p < 0f) 1f else 1f - p

    /** 彩点透明度：12% 前淡入，之后恒 1（设计稿 opacity 0→1 后不再变） */
    fun dotAlpha(p: Float): Float = when {
        p < 0f -> 0f
        p < 0.12f -> p / 0.12f
        else -> 1f
    }
}

// ---- 设计稿的颜色资产（本工程没有等价 token 的，就地定义；红心用工程的录制红）----

/** 设计稿的绽放紫（#cc8ef5）；工程调色板没有等价色，就地在组件内使用，不外泄 */
private val BloomPurple = Color(0xFFCC8EF5)

/** 设计稿的七色纸屑（每对两颗不同色） */
private val Confetti = listOf(
    Color(0xFFF48EA7), Color(0xFFCC8EF5), Color(0xFF8CE8C3), Color(0xFF91D2FA),
    Color(0xFFF5A524), Color(0xFFE5484D), Color(0xFF9FC7FA)
)

/** 设计稿的几何（px 值直接按 dp 用：控件尺度相近，20dp 心 / 34dp 环 / 5dp 点） */
private const val HeartGlyphDp = 24f
private const val BloomRadiusDp = 17f
private const val DotDp = 5f
private const val DotFarDp = -5f
private const val DotSideDp = 4f

/** 纸屑的飞行幅度（设计稿 burst 默认 50 → fly = 0.5） */
private const val FlyDefault = 0.5f

/**
 * 收藏红心：点按切换收藏，**收藏那一下**播一次 Bencho "Like" 的红心动画
 * （绽放环抽出 → 七对彩点甩出 → 红心从无到有弹回）；取消收藏只把心掏空，不播效果。
 *
 * 与工程纪律的对齐：
 * - 动效档 PLAIN 与系统"减少动效"档**不播**效果（只切状态）——设计稿的 reduced-motion 分支同理；
 * - 只动 translation / scale / alpha（MotionHygiene 红线），不动布局参数，颜色瞬时切换；
 * - 每次按下用 [beat] 变化重放一次（设计稿 "keyed by the press, so every like plays once"）。
 */
@Composable
fun LikeHeart(
    liked: Boolean,
    onToggle: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val motion = LocalMotion.current
    val playEffect = motion.mode != MotionMode.PLAIN && motion.durationMs != 0
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var beat by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    val pulse = remember { Animatable(1f) }

    fun press() {
        val next = !liked
        onToggle(next)
        if (!next || !playEffect) return
        beat++
        playing = true
        scope.launch {
            pulse.snapTo(0f)
            pulse.animateTo(
                1f,
                keyframes {
                    durationMillis = LikeBurst.DURATION_MS
                    0f at 0
                    1f at LikeBurst.DURATION_MS
                }
            )
            playing = false
        }
    }

    val heartTint = if (liked) WotaRec else WotaColor.textHi

    Box(
        modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button) { press() },
        contentAlignment = Alignment.Center
    ) {
        if (playing) {
            // 效果层：环 + 彩点。t 在绘制期读（Animatable.value 是快照状态）⇒ 每帧只重画这一层。
            // requiredSize 故意大于 48dp 触控盒：彩点要甩到盒子外面去（Box 默认不裁切）
            Canvas(Modifier.requiredSize(80.dp)) {
                val t = pulse.value
                val center = Offset(size.width / 2f, size.height / 2f)
                val dp = density.density

                // 绽放环：实心圆 → 环 → 淡出（边框占半径的比例按设计稿收缩）
                val bloomScale = LikeBurst.bloomScale(t)
                val borderFrac = LikeBurst.bloomBorderFraction(t)
                val alpha = LikeBurst.bloomAlpha(t)
                if (alpha > 0f && borderFrac > 0f) {
                    val radius = BloomRadiusDp * dp * bloomScale
                    val stroke = (radius * borderFrac).coerceAtLeast(0.5f)
                    val color = lerp(WotaRec, BloomPurple, LikeBurst.bloomPurple(t))
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = (radius - stroke / 2f).coerceAtLeast(0.1f),
                        center = center,
                        style = Stroke(width = stroke)
                    )
                }

                // 七对彩点：各自辐条 + 侧向错位（设计稿的 --side / --far）
                val dotP = LikeBurst.dotProgress(t)
                if (dotP >= 0f) {
                    val dotR = DotDp * dp
                    for (k in 0 until 7) {
                        val angle = Math.toRadians((k * (360.0 / 7.0) - 90.0))
                        val ux = cos(angle).toFloat()
                        val uy = sin(angle).toFloat()
                        for (pair in 0 until 2) {
                            val side = if (pair == 0) -DotSideDp else DotSideDp
                            val far = if (pair == 0) 0f else DotFarDp
                            val radial = LikeBurst.dotRadiusPx(dotP, FlyDefault, far) * dp
                            val lateral = LikeBurst.dotSidePx(dotP, side) * dp
                            val p = Offset(
                                center.x + ux * radial - uy * lateral,
                                center.y + uy * radial + ux * lateral
                            )
                            val scale = LikeBurst.dotScale(dotP)
                            val a = LikeBurst.dotAlpha(dotP)
                            if (scale > 0f && a > 0f) {
                                val idx = if (pair == 0) k else k + 3
                                drawCircle(
                                    color = Confetti[idx % Confetti.size].copy(alpha = a),
                                    radius = dotR * scale,
                                    center = p
                                )
                            }
                        }
                    }
                }
            }
        }

        Icon(
            imageVector = if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = contentDescription,
            tint = heartTint,
            modifier = Modifier
                .size(HeartGlyphDp.dp)
                .graphicsLayer {
                    val t = pulse.value
                    val s = if (playing) LikeBurst.heartScale(t) else 1f
                    scaleX = s
                    scaleY = s
                }
        )
    }
}
