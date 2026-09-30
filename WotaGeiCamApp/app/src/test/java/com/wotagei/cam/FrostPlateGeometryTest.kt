package com.wotagei.cam

import com.wotagei.cam.camera.FrostRectPx
import com.wotagei.cam.camera.frostCoverOfSdf
import com.wotagei.cam.camera.frostPlateRectInto
import com.wotagei.cam.camera.frostResolveRadiusPx
import com.wotagei.cam.camera.frostRoundedRectSdfPx
import com.wotagei.cam.camera.frostRtSizeInto
import com.wotagei.cam.camera.frostUvInto
import com.wotagei.cam.camera.frostViewOriginInto
import com.wotagei.cam.camera.letterboxContentRectInPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A2 画板几何的手算对拍（#84 步骤 2）。
 *
 * 覆盖 [com.wotagei.cam.camera.FrostPlatePass] 每帧要跑的三条纯函数，外加 [frostUvInto] 的**标量入口**：
 * - [frostViewOriginInto]：组合根 → 承载视图原点，整条接线上唯一的推断量；
 * - [frostPlateRectInto]：窗口矩形 → NDC 中心 + NDC 半轴 + 像素半轴；
 * - [frostResolveRadiusPx] + [frostRoundedRectSdfPx] + [frostCoverOfSdf]：圆角档位 → 板上实际半径
 *   → 带符号距离 → 覆盖度（GLSL 那三行的 Kotlin 镜像）。
 *
 * `frostUvOfCard`（对象版）的手算账已经在 `HudFrostTest` 里，本文件刻意换一组**不能整除**的几何
 * （面 700×400、内容 1600×900、RT 175×99），避免和它重复同一组数。
 *
 * 为什么这些值得逐个手算：片元着色器在 JVM 里跑不了，能静态证明的只有这几条镜像；它们错一个符号，
 * 真机上的表现分别是"板整体偏半个屏幕""板缩成一半大""Y 轴上下翻""圆角把整块板判到外面（板凭空消失）"
 * ——全都不会抛异常，只会在屏幕上悄悄错。
 */
class FrostPlateGeometryTest {

    private val eps = 1e-5f

    /** 本文件自己的一组基线：面 700×400、内容 16:9 的 1600×900、视图在窗口里偏 (10, 50) */
    private val viewW = 700
    private val viewH = 400
    private val originX = 10f
    private val originY = 50f

    /** 画面 (0,3)-(700,397)、RT 175×99：手算见 [uvNormalCaseTexels] */
    private val content = FrostRectPx(0f, 3f, 700f, 397f)
    private val texW = 175
    private val texH = 99

    private fun uvInto(
        card: FrostRectPx,
        content: FrostRectPx,
        origin: Pair<Float, Float> = originX to originY,
        texW: Int,
        texH: Int
    ): FloatArray? {
        val out = FloatArray(4)
        val ok = frostUvInto(
            out = out,
            cardLeftPx = card.left,
            cardTopPx = card.top,
            cardRightPx = card.right,
            cardBottomPx = card.bottom,
            contentLeftPx = content.left,
            contentTopPx = content.top,
            contentRightPx = content.right,
            contentBottomPx = content.bottom,
            viewOriginXInWindowPx = origin.first,
            viewOriginYInWindowPx = origin.second,
            texWidthPx = texW,
            texHeightPx = texH
        )
        return if (ok) out else null
    }

    // ------------------------------------------------------- frostUvInto：一组正常

    @Test
    fun `RT 尺寸与画面矩形先对上手算`() {
        // scale = min(700/1600, 400/900) = 0.4375 ⇒ 700×393.75 → roundToInt 394
        // 左右余量 0、上下余量 6 → 上 3 下 3 ⇒ 画面 (0,3)-(700,397)
        assertEquals(content, letterboxContentRectInPx(700, 400, 1600f, 900f))
        val rt = IntArray(2)
        assertTrue(frostRtSizeInto(rt, content.widthPx.toInt(), content.heightPx.toInt()))
        // RT = ⌈700/4⌉ × ⌈394/4⌉ = 175 × 99（394/4 = 98.5 必须向上取整，向下会把最下一列纹素挤掉）
        assertEquals(texW, rt[0])
        assertEquals(texH, rt[1])
    }

    @Test
    fun `标量入口在一组不能整除的几何上给出手算纹素`() {
        // 窗口 px (152,250)-(320,340) → 视图 px (142,200)-(310,290)
        //   左纹素  floor((142-0)×175/700) = floor(35.5)            = 35   ← 向外取整
        //   右纹素  ceil ((310-0)×175/700) = ceil(77.5)             = 78   ← 向外取整
        // 左右两端**都**刻意取不能整除的位置：floor/ceil 若被互换，恰好落在纹素网格上的数
        // 看不出来（本文件第一版就是这样漏掉了"左界改成 ceil"这一记突变，已补）
        //   下纹素  floor((394-(290-3))×99/394) = floor(107×99/394) = floor(26.88) = 26
        //   上纹素  ceil ((394-(200-3))×99/394) = ceil (197×99/394) = ceil(49.5)   = 50  ← v 向上
        val uv = uvInto(FrostRectPx(152f, 250f, 320f, 340f), content, texW = texW, texH = texH)
        assertTrue("这组几何该有模糊", uv != null)
        uv!!
        assertEquals(35f / 175f, uv[0], eps) // uMin
        assertEquals(78f / 175f, uv[1], eps) // uMax
        assertEquals(26f / 99f, uv[2], eps) // vMin（GL：v=0 是画面底边）
        assertEquals(50f / 99f, uv[3], eps) // vMax
        // 纹素整数直接可读回：只写成分数会把"分母用错"这类错掩成小数误差，两头都钉
        assertEquals(35f, uv[0] * 175f, 1e-3f)
        assertEquals(78f, uv[1] * 175f, 1e-3f)
        assertEquals(26f, uv[2] * 99f, 1e-3f)
        assertEquals(50f, uv[3] * 99f, 1e-3f)
        // v 轴方向：靠画面下缘的 v 小、靠上缘的 v 大。反了就是上下颠倒贴霜（中间几乎看不出、越靠边越错）
        assertTrue("v 必须向上（下缘 ${uv[2]} < 上缘 ${uv[3]}）", uv[2] < uv[3])
    }

    // ------------------------------------------------------- frostUvInto：踩边界

    @Test
    fun `卡片完全压在信箱黑边上时没有模糊可谈`() {
        // 视图 px y 0..3 整块落在上黑边里（画面从 y=3 起）⇒ 求交后零高
        // 窗口 px = 视图 px + 原点 (10, 50)
        assertEquals(
            "整块压上黑边必须给 null（视图 y 0..3 与画面 3..397 只相切）",
            null,
            uvInto(FrostRectPx(110f, 50f, 210f, 53f), content, texW = texW, texH = texH)
        )
        // 画面右缘之外：视图 x 700..800
        assertEquals(
            "整块在画面右侧之外必须给 null",
            null,
            uvInto(FrostRectPx(710f, 150f, 810f, 250f), content, texW = texW, texH = texH)
        )
        // 画面下方之外：视图 y 397..400 是下黑边（窗口 px 再 +50）
        assertEquals(
            "整块压下黑边必须给 null",
            null,
            uvInto(FrostRectPx(110f, 447f, 210f, 450f), content, texW = texW, texH = texH)
        )
    }

    @Test
    fun `卡片只压到黑边时裁进画面而不是拉伸`() {
        // 视图 px (100,-10)-(200,5)：上出界 13px、进画面 2px ⇒ 采样区必须钉死在画面顶边
        //   左 floor(100×175/700) = 25；右 ceil(200×175/700) = 50
        //   下 floor((394-(5-3))×99/394) = floor(392×99/394) = floor(98.497) = 98
        //   上 ceil((394-(3-3))×99/394)  = ceil(99) = 99            ⇒ vMax = 1（画面顶边）
        val uv = uvInto(FrostRectPx(110f, 40f, 210f, 55f), content, texW = texW, texH = texH)
        assertTrue("压到画面内 2px 必须有模糊", uv != null)
        uv!!
        assertEquals(25f / 175f, uv[0], eps)
        assertEquals(50f / 175f, uv[1], eps)
        assertEquals(98f / 99f, uv[2], eps)
        assertEquals("压到画面顶边时上界必须正好 1.0（不许把黑边算进采样区）", 1f, uv[3], eps)
        // 反向证据：出界那截若被"归一化后拉伸"，vMax 就会超过 1、uMax 也会跑到 50/175 之外
        assertTrue("UV 越界说明被拉伸而不是裁切：${uv.toList()}", uv.all { it in 0f..1f })
    }

    @Test
    fun `退化输入返回 false 且一个字节都不写进副本`() {
        val degenerate = listOf(
            uvInto(FrostRectPx(150f, 250f, 320f, 340f), content, texW = 0, texH = texH) to "RT 宽 0",
            uvInto(FrostRectPx(150f, 250f, 320f, 340f), content, texW = texW, texH = -1) to "RT 高负",
            uvInto(FrostRectPx(150f, 250f, 320f, 340f), FrostRectPx(0f, 0f, 0f, 0f), texW = texW, texH = texH) to "画面零面积",
            uvInto(FrostRectPx(150f, 250f, 150f, 340f), content, texW = texW, texH = texH) to "卡片零宽",
            uvInto(FrostRectPx(320f, 340f, 150f, 250f), content, texW = texW, texH = texH) to "卡片反序",
            // 视图原点把卡片整块推出画面：窗口 px (150,250)-(320,340) 配原点 (1000, 0) ⇒ 视图 x 负到 -850
            uvInto(FrostRectPx(150f, 250f, 320f, 340f), content, origin = 1000f to 0f, texW = texW, texH = texH) to "被原点推出画面"
        )
        degenerate.forEach { (uv, why) -> assertEquals("$why 必须返回 false", null, uv) }
        // false 一侧不许留半份数据：调用方（FrostPlatePass）是"跳过这块板"，不是"沿用上次那组 UV"
        val out = FloatArray(4) { Float.NaN }
        assertFalse(
            frostUvInto(
                out, 150f, 250f, 320f, 340f,
                content.left, content.top, content.right, content.bottom,
                originX, originY, 0, 0
            )
        )
        assertTrue("返回 false 时不许写 out", out.all { it.isNaN() })
    }

    @Test
    fun `副本太短要炸在参数检查上而不是越界写`() {
        val thrown = runCatching {
            frostUvInto(
                FloatArray(3), 150f, 250f, 320f, 340f,
                content.left, content.top, content.right, content.bottom,
                originX, originY, texW, texH
            )
        }.exceptionOrNull()
        assertTrue("期望 IllegalArgumentException，实际 $thrown", thrown is IllegalArgumentException)
    }

    // ------------------------------------------------------- frostViewOriginInto

    @Test
    fun `视图原点就是组合根居中的那一推`() {
        val out = FloatArray(2)
        // 竖屏基线（与 HudFrostTest 同一组数）：组合根 = 720×1600 的整窗口、承载视图 720×1080
        // ⇒ 原点 (0, (1600-1080)/2) = (0, 260)。A2 全部换算的"窗口 px − 视图原点 = 视图 px"就靠这一个数
        assertTrue(frostViewOriginInto(out, 0f, 0f, 720f, 1600f, 720, 1080))
        assertEquals(0f, out[0], eps)
        assertEquals(260f, out[1], eps)
        // 根本身有偏移 + 视图比根小 ⇒ 两边都要加
        assertTrue(frostViewOriginInto(out, 40f, 100f, 1000f, 800f, 640, 480))
        assertEquals(220f, out[0], eps)
        assertEquals(260f, out[1], eps)
        // 奇数余量走 Float 除法（Compose 的居中落在半像素上），**不是**整数除法向零取整
        assertTrue(frostViewOriginInto(out, 0f, 0f, 720f, 1601f, 720, 1080))
        assertEquals(0f, out[0], eps)
        assertEquals(260.5f, out[1], eps)
        assertTrue(frostViewOriginInto(out, 0f, 0f, 721f, 1600f, 720, 1080))
        assertEquals(0.5f, out[0], eps)
        assertEquals(260f, out[1], eps)
        // 视图正好铺满根 ⇒ 原点就是根的左上角（横屏满幅预览那一档）
        assertTrue(frostViewOriginInto(out, 12f, 30f, 1600f, 720f, 1600, 720))
        assertEquals(12f, out[0], eps)
        assertEquals(30f, out[1], eps)
    }

    @Test
    fun `居中前提不成立时宁可不出原点`() {
        val out = FloatArray(2) { Float.NaN }
        // 视图比根大 ⇒ "居中的那块"这个前提已经崩了（八成是布局改了没同步这里）：负原点绝不能画出去
        assertFalse(frostViewOriginInto(out, 0f, 0f, 100f, 100f, 400, 400))
        assertTrue("前提不成立时不许写 out", out.all { it.isNaN() })
        assertFalse(frostViewOriginInto(out, 0f, 0f, 100f, 100f, 101, 100))
        // 根还没量到（0 尺寸）/ GL 还没报视图尺寸（0）/ 尺寸为负 ⇒ 同样不画
        assertFalse(frostViewOriginInto(out, 0f, 0f, 0f, 1600f, 720, 1080))
        assertFalse(frostViewOriginInto(out, 0f, 0f, 720f, 1600f, 720, 0))
        assertFalse(frostViewOriginInto(out, 0f, 0f, 720f, -1f, 720, 1080))
        // 差一字符的边界：视图恰好等于根合法（上面已测），比根大 1px 就非法
        assertTrue(frostViewOriginInto(FloatArray(2), 0f, 0f, 100f, 100f, 100, 100))
        assertFalse(frostViewOriginInto(FloatArray(2), 0f, 0f, 100f, 100f, 100, 101))
        assertTrue(
            "out 太短必须炸在参数检查上",
            runCatching { frostViewOriginInto(FloatArray(1), 0f, 0f, 100f, 100f, 10, 10) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    // ------------------------------------------------------- frostPlateRectInto

    @Test
    fun `窗口矩形换成 NDC 与像素半轴`() {
        val out = FloatArray(6)
        // 竖屏 Dock 卡片：视图 720×1080、视图在窗口里的原点 (0,260)、卡片窗口 px (56,260)-(164,320)
        // ⇒ 视图 px (56,0)-(164,60)：中心 (110,30)、108×60
        //   ndcX = 110/720×2 − 1 = −0.694444（左半屏）
        //   ndcY = 1 − 30/1080×2 = 0.944444（靠顶，Y 翻一次）
        //   半宽 = 108/720 = 0.15  ← 文档里特别警告"别写成 宽/(2·视图宽)"，那会把每块板缩成一半大
        //   半高 = 60/1080 = 0.055556
        assertTrue(frostPlateRectInto(out, 56f, 260f, 164f, 320f, 0f, 260f, 720, 1080))
        assertEquals(110f / 720f * 2f - 1f, out[0], eps)
        assertEquals(1f - 30f / 1080f * 2f, out[1], eps)
        assertEquals(0.15f, out[2], eps)
        assertEquals(60f / 1080f, out[3], eps)
        assertEquals("像素半轴宽", 54f, out[4], eps)
        assertEquals("像素半轴高", 30f, out[5], eps)
        // 方向性的两条：靠顶的板 ndcY 为正、靠左的板 ndcX 为负
        assertTrue("Y 轴必须向上（靠顶的板 ndcY>0）：${out[1]}", out[1] > 0f)
        assertTrue("X 轴向右（靠左的板 ndcX<0）：${out[0]}", out[0] < 0f)
        // 半轴摊回像素必须还原成原尺寸（NDC 全长 2 摊在视图宽上 ⇒ 半宽 × 视图宽 = 卡片宽；
        // 写成 `宽/(2·视图宽)` 的话这两条立刻差一倍，正是文档里特别警告的那个错）
        assertEquals(108f, out[2] * 720f, 1e-3f)
        assertEquals(60f, out[3] * 1080f, 1e-3f)
    }

    @Test
    fun `视图原点参与减法且退化矩形不写副本`() {
        val out = FloatArray(6)
        // 本文件的基线：视图 700×400、原点 (10,50)、卡片窗口 px (150,250)-(320,340)
        // ⇒ 视图 px (140,200)-(310,290)：中心 (225,245)、170×90
        //   ndcX = 225/700×2 − 1 = −0.357143；ndcY = 1 − 245/400×2 = −0.225（靠底 ⇒ 负）
        assertTrue(frostPlateRectInto(out, 150f, 250f, 320f, 340f, originX, originY, viewW, viewH))
        assertEquals(225f / 700f * 2f - 1f, out[0], eps)
        assertEquals(1f - 245f / 400f * 2f, out[1], eps)
        assertEquals(170f / 700f, out[2], eps)
        assertEquals(90f / 400f, out[3], eps)
        assertEquals(85f, out[4], eps)
        assertEquals(45f, out[5], eps)
        assertTrue("靠底的板 ndcY 必须为负：${out[1]}", out[1] < 0f)
        // 原点没减的话中心会偏 10/700×2 = 0.028571：换一枚零原点单独钉一次
        val noOrigin = FloatArray(6)
        assertTrue(frostPlateRectInto(noOrigin, 150f, 250f, 320f, 340f, 0f, 0f, viewW, viewH))
        assertEquals(235f / 700f * 2f - 1f, noOrigin[0], eps)
        assertEquals(out[0] + 2f * originX / viewW, noOrigin[0], 1e-4f)
        // 退化：视图尺寸非法、零宽、反序 ⇒ false 且不写 out
        val untouched = FloatArray(6) { Float.NaN }
        assertFalse(frostPlateRectInto(untouched, 150f, 250f, 320f, 340f, originX, originY, 0, 400))
        assertFalse(frostPlateRectInto(untouched, 150f, 250f, 320f, 340f, originX, originY, 700, 0))
        assertFalse(frostPlateRectInto(untouched, 320f, 340f, 150f, 250f, originX, originY, 700, 400))
        assertFalse(frostPlateRectInto(untouched, 150f, 250f, 150f, 340f, originX, originY, 700, 400))
        assertTrue("返回 false 时不许写 out", untouched.all { it.isNaN() })
        assertTrue(
            "out 太短必须炸在参数检查上",
            runCatching { frostPlateRectInto(FloatArray(5), 0f, 0f, 10f, 10f, 0f, 0f, 700, 400) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    // ------------------------------------------------------- 圆角档位与 SDF 镜像

    @Test
    fun `圆角档位落到板上的半径：0 定值胶囊与超界`() {
        // 108×60 的 Dock 卡片：短边一半 = 30
        assertEquals("半径 0 就是直角板（SDF 退化公式自己会算对，不需要分支）", 0f, frostResolveRadiusPx(108f, 60f, 0f), eps)
        assertEquals("定值档（14dp 那类令牌）原样通过", 14f, frostResolveRadiusPx(108f, 60f, 14f), eps)
        assertEquals("刚好等于半短边不用夹", 30f, frostResolveRadiusPx(108f, 60f, 30f), eps)
        assertEquals("半径 ≥ 半短边必须夹到 30", 30f, frostResolveRadiusPx(108f, 60f, 40f), eps)
        assertEquals("负数=胶囊哨兵，等于短边一半", 30f, frostResolveRadiusPx(108f, 60f, -1f), eps)
        assertEquals("负得再深也还是这一档", 30f, frostResolveRadiusPx(108f, 60f, -9999f), eps)
        // 竖着的同一张板：短边仍是 60 ⇒ 夹到 30（把 min 写成 max 这里立刻给出 54）
        assertEquals(30f, frostResolveRadiusPx(60f, 108f, 40f), eps)
        assertEquals(14f, frostResolveRadiusPx(60f, 108f, 14f), eps)
        // 退化板（拖拽中途量到 0 宽）：半径必须跟着归 0，不许留一个"比板还厚"的半径
        assertEquals(0f, frostResolveRadiusPx(0f, 60f, 14f), eps)
        assertEquals(0f, frostResolveRadiusPx(108f, 0f, 14f), eps)
        assertEquals(0f, frostResolveRadiusPx(-5f, 60f, 14f), eps)
        // 长条板（短边 → 0）：半径跟着 → 0
        assertEquals(0.2f, frostResolveRadiusPx(200f, 0.4f, 20f), eps)
    }

    @Test
    fun `SDF 镜像给出手算的带符号距离`() {
        val halfW = 54f
        val halfH = 30f
        // 108×60 的板（半轴 54×30）、r=30 ⇒ 胶囊：直边在 y=±30（|x|≤24），两端是半径 30 的半圆
        assertEquals("板心必须在内 30px", -30f, frostRoundedRectSdfPx(0f, 0f, halfW, halfH, 30f), eps)
        assertEquals("右端半圆的极点恰在轮廓上", 0f, frostRoundedRectSdfPx(54f, 0f, halfW, halfH, 30f), eps)
        assertEquals("直边中点恰在轮廓上", 0f, frostRoundedRectSdfPx(0f, 30f, halfW, halfH, 30f), eps)
        // 极端角 (54,30)：到圆心 (24,0) 的距离 hypot(30,30)=42.426 减半径 ⇒ 12.426（胶囊角在板外）
        assertEquals(
            "胶囊角的距离 = hypot(30,30) − 30",
            hypot(30f, 30f) - 30f,
            frostRoundedRectSdfPx(54f, 30f, halfW, halfH, 30f),
            1e-3f
        )
        // 直角板（r=0）：轮廓就是矩形本身
        assertEquals(-30f, frostRoundedRectSdfPx(0f, 0f, halfW, halfH, 0f), eps)
        assertEquals(0f, frostRoundedRectSdfPx(54f, 30f, halfW, halfH, 0f), eps)
        assertEquals("外面 1px 必须是 +1", 1f, frostRoundedRectSdfPx(55f, 0f, halfW, halfH, 0f), eps)
        assertEquals("里面 1px 必须是 −1", -1f, frostRoundedRectSdfPx(53f, 0f, halfW, halfH, 0f), eps)
        // 符号对称性：SDF 只认到轮廓的距离，四个象限同值（少写一个 abs() 就会有一侧算错）
        val d = frostRoundedRectSdfPx(50f, 20f, halfW, halfH, 10f)
        assertEquals(-4f, d, eps) // qx = 50-(54-10) = 6、qy = 20-(30-10) = 0 ⇒ 6 + 0 − 10
        assertEquals(d, frostRoundedRectSdfPx(-50f, 20f, halfW, halfH, 10f), eps)
        assertEquals(d, frostRoundedRectSdfPx(50f, -20f, halfW, halfH, 10f), eps)
        assertEquals(d, frostRoundedRectSdfPx(-50f, -20f, halfW, halfH, 10f), eps)
    }

    @Test
    fun `半径超过半短边时板不能凭空消失`() {
        // 这是 frostResolveRadiusPx 那条注释点名的事故：半径比板还厚 ⇒ half − r 变负 ⇒
        // length(max(q,0)) 算出正数 ⇒ 整块板被判在外面（板凭空消失）。送一个荒谬的大半径试试。
        val sdHuge = frostRoundedRectSdfPx(0f, 0f, 54f, 30f, 1000f)
        assertTrue("半径超界后板心的 SDF 必须仍为负（板还在）：$sdHuge", sdHuge < 0f)
        assertEquals(-30f, sdHuge, eps)
        // 负数哨兵（胶囊）走同一条夹路：板心同样在内
        assertEquals(-30f, frostRoundedRectSdfPx(0f, 0f, 54f, 30f, -1f), eps)
        // 覆盖度：与片元 `smoothstep(-1.0, 1.0, sd)` 同一条算式 ⇒ aaPx=1 时端点必须一个个对得上
        assertEquals("板内 10px 全不透明", 1f, frostCoverOfSdf(-10f, 1f), eps)
        assertEquals("板外 10px 全透", 0f, frostCoverOfSdf(10f, 1f), eps)
        assertEquals("smoothstep 下端 sd=−1 就是全不透明", 1f, frostCoverOfSdf(-1f, 1f), 1e-6f)
        assertEquals("smoothstep 上端 sd=+1 就是全透", 0f, frostCoverOfSdf(1f, 1f), 1e-6f)
        assertEquals("轮廓上正好一半", 0.5f, frostCoverOfSdf(0f, 1f), eps)
        assertEquals(
            "过渡带必须关于轮廓对称（GLSL 的 smoothstep 就是这个形状）",
            1f - frostCoverOfSdf(-0.5f, 1f),
            frostCoverOfSdf(0.5f, 1f),
            1e-3f
        )
        // 覆盖度必须单调不增：sd 越大越透（写成 1-t 之外的任何形式都可能反向）
        val ramp = (-3..3).map { frostCoverOfSdf(it * 0.5f, 1f) }
        assertTrue("覆盖度随 sd 单调不增：$ramp", ramp.zipWithNext().all { (a, b) -> a >= b })
        // aaPx 非法（≤0）时的保底必须与片元同口径（±1px），上一版保底 0.5px 只有显式传 1f 才等价
        assertTrue("aaPx=0 不许除零给 NaN", !frostCoverOfSdf(0f, 0f).isNaN())
        assertEquals("轮廓上仍然正好一半（保底带宽改动不该动中点）", 0.5f, frostCoverOfSdf(0f, 0f), eps)
        assertEquals("负 aaPx 同样走保底", 0.5f, frostCoverOfSdf(0f, -5f), eps)
        listOf(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f).forEach { sd ->
            assertEquals(
                "保底带宽必须逐点等于片元那条 ±1px 的算式（sd=$sd）",
                frostCoverOfSdf(sd, 1f),
                frostCoverOfSdf(sd, 0f),
                eps
            )
            assertEquals("负 aaPx 也走同一条保底（sd=$sd）", frostCoverOfSdf(sd, 1f), frostCoverOfSdf(sd, -5f), eps)
        }
        // 反向证据：上一版的 0.5px 保底在 sd=0.5 处已经走到上端（全透），换成 1px 之后不该还是 0
        assertEquals("保底 1px：sd=0.5 还在过渡带里（0.5px 那版会算成 0，那是假锋利）", 0.15625f, frostCoverOfSdf(0.5f, 0f), 1e-4f)
        assertTrue("覆盖度随 sd 单调不增（保底档也一样）", (-3..3).map { frostCoverOfSdf(it * 0.5f, 0f) }.zipWithNext().all { (a, b) -> a >= b })
    }
}
