package com.wotagei.cam

import com.wotagei.cam.camera.FROST_BLUR_WEIGHTS
import com.wotagei.cam.camera.FROST_DOWNSCALE
import com.wotagei.cam.camera.FROST_RT_MAX_EDGE
import com.wotagei.cam.camera.FrostGeometry
import com.wotagei.cam.camera.FrostRectPx
import com.wotagei.cam.camera.FrostSnapshot
import com.wotagei.cam.camera.FrostUvRect
import com.wotagei.cam.camera.fillFrostBlurOffset
import com.wotagei.cam.camera.frostRtNeedsRebuild
import com.wotagei.cam.camera.frostRtSizeInto
import com.wotagei.cam.camera.frostScreenRadiusPx
import com.wotagei.cam.camera.frostUvOfCard
import com.wotagei.cam.camera.letterboxContentRectInPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 任务 **#84 步骤 1**（毛玻璃背板的 GPU 离屏基建）里**能被静态证明的那一层**。
 *
 * 离屏链本体（`FrostBlurChain`）跑在唯一的 GL 线程 + 唯一的 EGL 上下文里，JVM 测试碰不到 GL，
 * 所以本文件只打四类账，全部是人能手算的数：
 * 1. **等比画面矩形**（[letterboxContentRectInPx]）：那是 `GlRenderEngine.updateContentRect` 抽出来的
 *    那一半算术，两边共用一个真源 ⇒ 下面所有基线都是**算出来的**，不是我编的一组数；
 * 2. **RT 尺寸**（[frostRtSizeInto]）：降采样倍数、上限钳制、0 尺寸兜底；
 * 3. **重建判据**（[frostRtNeedsRebuild]）：每帧零分配纪律与"A/B 翻转不引起尺寸抖动"的桥函数本体
 *    ——AGENTS.md 那条"配置类纯函数必须测桥本身"就是写给这种判据的：链每帧只问这一句，
 *    所以这句错了就会退化成每帧重建 RT（帧率当场垮，且真机上只能靠数帧才发现）；
 * 4. **卡片矩形 → 采样 UV**（[frostUvOfCard]）：窗口原点差、信箱黑边裁切、不相交、纹素向外对齐、v 轴向上。
 *
 * 期望值一律写成 `整数纹素序号 / RT 边长` 的手算形式（不写死小数），因为纹素序号本身就是推导：
 * 竖屏基线 —— 面板 720×1600、density 2.0、相机 1920×1080、sensorOrientation 90、承载视图 720×1080
 * （`aspectRatio` 收过的盒子）、视图在窗口里的原点 (0, 260) ⇒ 旋转后包围盒 1080×1920 等比塞进视图
 * 得画面 608×1080（左缘 56），RT = ⌈608/4⌉ × ⌈1080/4⌉ = **152×270**；
 * 横屏基线 —— 视图 1280×720、窗口原点 (160, 0)、画面正好铺满视图 ⇒ RT = **320×180**。
 * 这两组"画面矩形"由 [letterboxContentRectInPx] 现算，不是抄来的常量。
 *
 * ⚠ 每条都注明"改坏哪一行会红"。特别地：**v 轴方向**（GL 的 v=0 是画面底边）若被实现成"顶边起"，
 * 上屏那侧会拿到上下颠倒的霜面——卡片背后糊的是画面**另一头**的颜色，位置越靠边越明显、
 * 中间几乎看不出，真机上极难归因，所以这里用竖屏/横屏两条用例把 v 与 y 的反向关系钉死。
 */
class HudFrostTest {

    /** UV 用 Float 除法算出，与实现的 Double→Float 路径差 1 ulp 很正常，断言给到 1e-6 */
    private val eps = 1e-6f

    /**
     * 生产侧刻意用 out 参数而不是返回 `Pair`（每帧路径不许装箱分配），测试侧要声明式地比期望值，
     * 所以这里包一层——测试里的分配不影响真机。
     */
    private fun rtSize(contentWidthPx: Int, contentHeightPx: Int): Pair<Int, Int> {
        val out = IntArray(2)
        return if (frostRtSizeInto(out, contentWidthPx, contentHeightPx)) out[0] to out[1] else 0 to 0
    }

    private fun assertUv(actual: FrostUvRect?, uMin: Float, uMax: Float, vMin: Float, vMax: Float) {
        assertNotNull("换算不该返回 null（这帧该有模糊）", actual)
        val uv = actual!!
        assertEquals("uMin", uMin, uv.uMin, eps)
        assertEquals("uMax", uMax, uv.uMax, eps)
        assertEquals("vMin", vMin, uv.vMin, eps)
        assertEquals("vMax", vMax, uv.vMax, eps)
    }

    // ------------------------------------------------- 竖屏/横屏基线几何（由引擎那条算术算出来）

    /**
     * 基线不写死：等比画面矩形走 [letterboxContentRectInPx]（`GlRenderEngine.updateContentRect`
     * 抽出来的那一半，两边同一个真源），RT 尺寸走 [frostRtSizeInto]，再喂给 [frostUvOfCard]。
     * 这样"我在测的算术"和"真机上跑的那段算术"不会各自漂移——写死基线的话，引擎那边改了取整
     * 本文件照样全绿，就成了我自己编的一组数。
     */
    private fun geometryFor(
        viewWidthPx: Int,
        viewHeightPx: Int,
        rotatedFrameWidthPx: Int,
        rotatedFrameHeightPx: Int,
        viewOriginXInWindowPx: Float,
        viewOriginYInWindowPx: Float
    ): FrostGeometry {
        val content = letterboxContentRectInPx(
            viewWidthPx, viewHeightPx, rotatedFrameWidthPx.toFloat(), rotatedFrameHeightPx.toFloat()
        )
        val (tw, th) = rtSize(content.widthPx.roundToInt(), content.heightPx.roundToInt())
        return FrostGeometry(content, viewOriginXInWindowPx, viewOriginYInWindowPx, tw, th)
    }

    /**
     * 竖屏：本机面板 720×1600、density 2.0、相机 1920×1080、sensorOrientation 90、display 0
     * ⇒ 旋转后包围盒 1080×1920；承载视图 720×1080（`aspectRatio` 收过的盒子）、在窗口里的原点 (0,260)
     * ⇒ 等比画面 (56,0)-(664,1080) = 608×1080，RT = 152×270。
     */
    private fun portraitGeometry() =
        geometryFor(720, 1080, 1080, 1920, viewOriginXInWindowPx = 0f, viewOriginYInWindowPx = 260f)

    /**
     * 横屏：面板 1600×720、display 90 ⇒ 包围盒就是帧本身 1920×1080；
     * 承载视图 1280×720、窗口原点 (160,0) ⇒ 画面正好铺满视图 (0,0)-(1280,720)，RT = 320×180。
     */
    private fun landscapeGeometry() =
        geometryFor(1280, 720, 1920, 1080, viewOriginXInWindowPx = 160f, viewOriginYInWindowPx = 0f)

    @Test
    fun portraitBaselineIsWhatTheHandMathSays() {
        // 手算复核两条基线本身（它们一旦被改动，下面所有 UV 用例的期望值都要重算）
        // scale = min(720/1080, 1080/1920) = 0.5625 ⇒ 1080×0.5625 = 607.5 → 608、1920×0.5625 = 1080
        // 余量 720-608 = 112 ⇒ 左右各 56
        assertEquals(FrostRectPx(56f, 0f, 664f, 1080f), letterboxContentRectInPx(720, 1080, 1080f, 1920f))
        // scale = min(1280/1920, 720/1080) = 2/3 ⇒ 正好铺满 1280×720，无黑边
        assertEquals(FrostRectPx(0f, 0f, 1280f, 720f), letterboxContentRectInPx(1280, 720, 1920f, 1080f))
        assertEquals(152 to 270, rtSize(608, 1080))
        assertEquals(320 to 180, rtSize(1280, 720))
    }

    @Test
    fun oddRemainderGoesToTheLeadingEdge() {
        // 手算：面宽 721、画面宽 608 ⇒ 余量 113 → 左 = 113/2 = 56（整数除法向零取整），右 = 721-112... 
        val rect = letterboxContentRectInPx(721, 1080, 1080f, 1920f)
        // scale = min(721/1080=0.667, 1080/1920=0.5625) = 0.5625 ⇒ drawW = 608、drawH = 1080
        // 左 = (721-608)/2 = 113/2 = 56 ⇒ 右 = 664，右边空 57：左比右多 1px 的那条规矩在这
        assertEquals(56f, rect.left, eps)
        assertEquals(664f, rect.right, eps)
        assertEquals(0f, rect.top, eps)
    }

    @Test
    fun letterboxFallsBackToTheWholeSurfaceWhenSizeIsUnknown() {
        // 会话还没报帧尺寸 ⇒ 不做信箱（与 updateContentRect 的退化分支同语义）
        assertEquals(FrostRectPx(0f, 0f, 720f, 1080f), letterboxContentRectInPx(720, 1080, 0f, 0f))
        // 面尺寸为 0（还没布局）⇒ 空矩形，让上层拿 (0,0,0,0) 判"这帧不可用"
        assertEquals(FrostRectPx(0f, 0f, 0f, 0f), letterboxContentRectInPx(0, 0, 1080f, 1920f))
        assertNull(frostUvOfCard(FrostRectPx(0f, 0f, 100f, 100f), FrostGeometry(letterboxContentRectInPx(0, 0, 1080f, 1920f), 0f, 0f, 152, 270)))
    }

    @Test
    fun screenRadiusStaysSmallerThanADockCard() {
        // 半径 ±12px（3 纹素 × N=4），Dock 卡片实测约 108×60px（54dp @ density 2）⇒ 108 > 2×12，
        // 卡片宽是模糊半径的 4.5 倍，背板看得出底层走向而不是均色
        assertEquals(12, frostScreenRadiusPx())
        assertTrue("半径吃到卡片宽度量级就说明 N 或权重表被改过头", 108 > 2 * frostScreenRadiusPx())
    }

    @Test
    fun dockCardCoversRoughlyTwentySevenByFifteenTexels() {
        // 一张 108×60 的卡片贴在画面左上角（正好落在纹素网格上）：
        // 横向 108/4 = 27 纹素、纵向 60/4 = 15 纹素 —— N=4 那条"再小就糊成一片"的账就在这两个数上
        val uv = frostUvOfCard(FrostRectPx(56f, 260f, 164f, 320f), portraitGeometry())!!
        assertEquals(27f, (uv.uMax - uv.uMin) * 152f, 1e-3f)
        assertEquals(15f, (uv.vMax - uv.vMin) * 270f, 1e-3f)
    }

    // ------------------------------------------------- 1. RT 尺寸

    @Test
    fun rtSizeDividesTheLetterboxedImageByFour() {
        // 手算：720/4=180、405/4=101.25 → 向上取整 102
        assertEquals(180 to 102, rtSize(720, 405))
        // 手算：竖屏基线 608/4=152、1080/4=270，整除无取整
        assertEquals(152 to 270, rtSize(608, 1080))
        // 手算：横屏基线 1280/4=320、720/4=180
        assertEquals(320 to 180, rtSize(1280, 720))
    }

    @Test
    fun rtSizeRoundsUpSoTheLastColumnIsNotDropped() {
        // 手算：405 → ⌈405/4⌉=102。若改成向下取整会得 101，贴画面下缘的卡片就少一行纹素
        assertEquals(1 to 102, rtSize(1, 405))
        assertEquals(1 to 1, rtSize(3, 3))
        // 1 像素的画面也得留一个纹素，否则整条链没有可写的地方
        assertEquals(1 to 1, rtSize(1, 1))
    }

    @Test
    fun rtSizeOfZeroOrNegativeContentIsNotAvailable() {
        // 预览尺寸回报为 0 帧（还没布局 / 会话没建好）必须返回 false，让上层跳过而不是硬建 1×1
        val out = IntArray(2)
        assertFalse(frostRtSizeInto(out, 0, 1080))
        assertFalse(frostRtSizeInto(out, 1920, 0))
        assertFalse(frostRtSizeInto(out, -720, 405))
        assertFalse(frostRtSizeInto(out, 0, 0))
        assertEquals(0, out[0])
        assertEquals(0, out[1])
        // 适配器口径：不可用就是 (0,0)，与"合法但很小"的 (1,1) 区分得开
        assertEquals(0 to 0, rtSize(0, 1080))
        assertEquals(1 to 1, rtSize(1, 1))
    }

    @Test
    fun rtSizeRejectsAnOversmallOutBufferInsteadOfWritingPastIt() {
        var thrown = false
        try {
            frostRtSizeInto(IntArray(1), 720, 405)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("缓冲区不够长必须抛，越界写会把相邻字段改掉", thrown)
    }

    @Test
    fun rtSizeCapsTheLongestEdgeAndKeepsTheAspect() {
        val (w, h) = rtSize(3840, 2160)
        // 手算：⌈3840/4⌉=960、⌈2160/4⌉=540；960 > 512 ⇒ k=512/960=0.53333
        //       w=round(960×0.53333)=512、h=round(540×0.53333)=round(288.0)=288
        assertEquals(512 to 288, w to h)
        // 比例不许被上限钳走形：16:9 还是 16:9（512/288 = 1.7778）
        assertEquals(16f / 9f, w.toFloat() / h.toFloat(), 1e-3f)
        assertEquals(FROST_RT_MAX_EDGE, maxOf(w, h))
        // 恰好压线的不缩：RT 宽正好等于上限
        assertEquals(512 to 128, rtSize(FROST_DOWNSCALE * FROST_RT_MAX_EDGE, 512))
    }

    @Test
    fun landscapeFullBleedImageIsNotSilentlyCapped() {
        // 本机横屏满幅 1600×720 的等比画面：⌈1600/4⌉=400、⌈720/4⌉=180，400 < 512 ⇒ 不触发上限。
        // 这条是给 FROST_RT_MAX_EDGE 定的"下限式"断言：谁把上限改小到咬住横屏，降采样倍数就会
        // 被悄悄改掉（横屏比竖屏粗），#85 的三组对照失去"两组只差一个开关"的前提
        val (w, h) = rtSize(1600, 720)
        assertEquals(400 to 180, w to h)
        assertTrue("横屏满幅必须还在 N=$FROST_DOWNSCALE 档上", maxOf(w, h) <= FROST_RT_MAX_EDGE)
    }

    // ------------------------------------------------- 2. 重建判据（零分配 / 无抖动的桥）

    @Test
    fun sameSizeNeverRebuilds() {
        // 链每帧都问这句；它返回 true 就是每帧 glDeleteTextures + glGenTextures + glTexImage2D
        assertFalse(frostRtNeedsRebuild(152, 270, 152, 270))
        assertFalse(frostRtNeedsRebuild(320, 180, 320, 180))
        // 反复问多少次都一样（幂等），这是"A/B 翻转不引起尺寸抖动"的可证部分：判据里没有开关
        repeat(20) { assertFalse(frostRtNeedsRebuild(152, 270, 152, 270)) }
    }

    @Test
    fun firstBuildAndRealSizeChangeDoRebuild() {
        assertTrue("资源还没建（0×0）却不去建，模糊永远不会出现", frostRtNeedsRebuild(0, 0, 152, 270))
        // 转屏：608×1080 → 1080×608 的 RT 是 270×152，宽高互换必须重建
        assertTrue(frostRtNeedsRebuild(152, 270, 270, 152))
        assertTrue(frostRtNeedsRebuild(152, 270, 160, 270))
    }

    @Test
    fun invalidTargetDoesNotTearDownTheChain() {
        // 尺寸临时回报为 0 不该顺手拆掉已建好的资源：拆了下一帧又建，那才是抖动
        assertFalse(frostRtNeedsRebuild(152, 270, 0, 270))
        assertFalse(frostRtNeedsRebuild(152, 270, 152, -1))
    }

    @Test
    fun snapshotEqualityFollowsSizeNotTheAbSwitch() {
        val base = FrostSnapshot(7, 152, 270, FrostRectPx(56f, 0f, 664f, 1080f))
        // 同一帧重复发布 ⇒ matches=true ⇒ 不换快照对象（每帧零分配的另一半）
        assertTrue(base.matches(7, 152, 270, 56f, 0f, 664f, 1080f))
        // 纹理换了（链重建过）必须发布新快照，否则上层拿旧 id 采样会采到已删除的纹理
        assertFalse(base.matches(8, 152, 270, 56f, 0f, 664f, 1080f))
        // RT 尺寸变了要发布
        assertFalse(base.matches(7, 270, 152, 56f, 0f, 664f, 1080f))
        // 画面矩形移动了要发布（信箱位置随视图尺寸变）
        assertFalse(base.matches(7, 152, 270, 60f, 0f, 668f, 1080f))
    }

    // ------------------------------------------------- 3. 取样位移

    @Test
    fun blurOffsetIsOneTexelAlongTheRightAxis() {
        val out = FloatArray(2)
        assertTrue(fillFrostBlurOffset(out, horizontal = true, texWidthPx = 320, texHeightPx = 180))
        assertEquals(1f / 320f, out[0], eps)
        assertEquals(0f, out[1], eps)

        assertTrue(fillFrostBlurOffset(out, horizontal = false, texWidthPx = 320, texHeightPx = 180))
        assertEquals(0f, out[0], eps)
        assertEquals(1f / 180f, out[1], eps)
    }

    @Test
    fun blurOffsetRefusesBadSizeWithoutTouchingTheBuffer() {
        val out = floatArrayOf(0.5f, 0.25f)
        assertFalse(fillFrostBlurOffset(out, horizontal = true, texWidthPx = 0, texHeightPx = 180))
        assertFalse(fillFrostBlurOffset(out, horizontal = false, texWidthPx = 320, texHeightPx = -1))
        // 返回 false 就不该写数组：写了的话上一趟的值被静默改成 0 位移，糊出一张"没糊"的图还看不出来
        assertEquals(0.5f, out[0], eps)
        assertEquals(0.25f, out[1], eps)
    }

    // ------------------------------------------------- 4. 权重表

    @Test
    fun gaussianWeightsSumToOneAcrossMirroredTaps() {
        // 手算：σ=3 ⇒ 分母 5.706454；0.17524 + 2×(0.16577+0.14032+0.10629) = 0.17524+0.82476 = 1.00000
        var sum = FROST_BLUR_WEIGHTS[0]
        for (i in 1 until FROST_BLUR_WEIGHTS.size) sum += 2f * FROST_BLUR_WEIGHTS[i]
        assertEquals("权重和偏离 1 就是整体提亮/压暗", 1f, sum, 1e-4f)
        // 单调递减（中心最重），写反了模糊会变成边缘增强
        for (i in 1 until FROST_BLUR_WEIGHTS.size) {
            assertTrue("第 $i 项必须比第 ${i - 1} 项小", FROST_BLUR_WEIGHTS[i] < FROST_BLUR_WEIGHTS[i - 1])
        }
        // 7 抽头（中心 + 3 对）：半径 ±3 纹素 = ±12 屏幕 px（N=4），与常量注释口径一致
        assertEquals(4, FROST_BLUR_WEIGHTS.size)
    }

    // ------------------------------------------------- 5. 卡片 → UV：竖屏

    @Test
    fun portraitCardFullyInsideTheImageMapsWholeTexels() {
        // 视图 px：卡片 (64,100)-(256,300)。手算纹素：
        //   左 (64-56)=8 → 8×152/608 = 2；右 (256-56)=200 → 200×152/608 = 50
        //   下 300 → 270 - 300/4 = 195；上 100 → 270 - 100/4 = 245（v 向上，画面顶 = 270）
        val card = FrostRectPx(64f, 360f, 256f, 560f) // 窗口 px = 视图 px + 原点 (0,260)
        assertUv(
            frostUvOfCard(card, portraitGeometry()),
            uMin = 2f / 152f, uMax = 50f / 152f, vMin = 195f / 270f, vMax = 245f / 270f
        )
    }

    @Test
    fun portraitCardStraddlingTheLetterboxBarIsClippedNotStretched() {
        // 视图 px 卡片 (16,40)-(128,320)：左半 56px 落在黑边里，只有 x≥56 那截能采样
        //   左 56 → (56-56)=0 → 纹素 0；右 (128-56)=72 → 72×152/608 = 18
        //   下 320 → 270-80 = 190；上 40 → 270-10 = 260
        val card = FrostRectPx(16f, 300f, 128f, 580f)
        assertUv(
            frostUvOfCard(card, portraitGeometry()),
            uMin = 0f / 152f, uMax = 18f / 152f, vMin = 190f / 270f, vMax = 260f / 270f
        )
        // 裁切必须"只裁不拉伸"：uMin 恒等于 0（画面左缘），而不是把 16..128 归一化后压满 0..1
        assertTrue("下界必须钉在采样区左缘", frostUvOfCard(card, portraitGeometry())!!.uMin == 0f)
    }

    @Test
    fun portraitCardOutsideTheImageYieldsNoFrost() {
        // 整张卡片落在左黑边里（视图 x 0..40 < 画面左缘 56）⇒ 没有可糊的底层内容
        assertNull(frostUvOfCard(FrostRectPx(0f, 260f, 40f, 360f), portraitGeometry()))
        // 视图右缘之外：x 700..760 > 画面右缘 664
        assertNull(frostUvOfCard(FrostRectPx(700f, 300f, 760f, 400f), portraitGeometry()))
        // 边界相切（右缘正好顶到画面左缘）不算相交：零面积采样区没有背板可言
        assertNull(frostUvOfCard(FrostRectPx(0f, 260f, 56f, 360f), portraitGeometry()))
    }

    @Test
    fun zeroAreaOrZeroSizeGeometryYieldsNoFrost() {
        val geo = portraitGeometry()
        // 卡片零宽 / 零高（HudLayer 回报实测尺寸前的空壳）
        assertNull(frostUvOfCard(FrostRectPx(100f, 300f, 100f, 400f), geo))
        assertNull(frostUvOfCard(FrostRectPx(100f, 300f, 200f, 300f), geo))
        // 反序矩形（拖拽中途 right<left 是会出现的）
        assertNull(frostUvOfCard(FrostRectPx(200f, 300f, 100f, 400f), geo))
        // 等比画面 0 尺寸（会话没建好那一帧）
        val noContent = geo.copy(contentRectInViewPx = FrostRectPx(0f, 0f, 0f, 0f))
        assertNull(frostUvOfCard(FrostRectPx(0f, 0f, 100f, 100f), noContent))
        // RT 还没建好（纹理 0×0）
        assertNull(frostUvOfCard(FrostRectPx(0f, 0f, 100f, 100f), geo.copy(texWidthPx = 0, texHeightPx = 0)))
    }

    @Test
    fun fullImageCardMapsTheWholeTexture() {
        // 卡片远大于画面：上下左右都被裁到画面 ⇒ UV 必须是整张 [0,1]×[0,1]
        val card = FrostRectPx(-400f, -400f, 2000f, 2400f)
        assertUv(frostUvOfCard(card, portraitGeometry()), 0f, 1f, 0f, 1f)
    }

    @Test
    fun samplingWindowExpandsOutwardToWholeTexels() {
        // 视图 px：卡片 (60,0)-(90,40)，即画面内 x 偏移 4..34、y 偏移 0..40
        //   左 4×152/608 = 1.0 → floor 1；右 34×152/608 = 8.5 → ceil 9
        //   上 0 → 270-0 = 270 → ceil 270；下 40 → 270-10 = 260 → floor 260
        // 取整方向错了（round / 向内取整）就会裁掉卡片边缘内容，本条立刻红
        val card = FrostRectPx(60f, 260f, 90f, 300f)
        assertUv(
            frostUvOfCard(card, portraitGeometry()),
            uMin = 1f / 152f, uMax = 9f / 152f, vMin = 260f / 270f, vMax = 270f / 270f
        )
    }

    @Test
    fun subTexelCardStillCoversAtLeastOneTexel() {
        // 1px 宽的卡片贴在纹素边界上：视图 x 偏移 4..5 → 1.0..1.25 → floor 1 / ceil 2 ⇒ 跨 1 个纹素
        // 纵向：视图 y 0..140 ⇒ 上 (1080-0)/4 = 270、下 (1080-140)/4 = 235
        val uv = frostUvOfCard(FrostRectPx(60f, 260f, 61f, 400f), portraitGeometry())
        assertUv(uv, uMin = 1f / 152f, uMax = 2f / 152f, vMin = 235f / 270f, vMax = 270f / 270f)
        assertTrue("采样窗口塌成零宽就是画不出背板", uv!!.uMax > uv.uMin)
        assertTrue(uv.vMax > uv.vMin)
    }

    @Test
    fun viewOriginOffsetShiftsTheMapping() {
        // 同一枚**窗口**矩形 (64,360)-(256,560)，只挪视图原点：卡片在视图里跟着平移，纹素序号必须跟着变。
        // 漏掉"减视图原点"这一步的话，两条期望值会相同，本条立刻红。
        val cardInWindow = FrostRectPx(64f, 360f, 256f, 560f)
        // 原点 (0,0) ⇒ 视图 px 就是 (64,360)-(256,560)：下 (1080-560)/4 = 130、上 (1080-360)/4 = 180
        assertUv(
            frostUvOfCard(cardInWindow, portraitGeometry().copy(viewOriginYInWindowPx = 0f)),
            uMin = 2f / 152f, uMax = 50f / 152f, vMin = 130f / 270f, vMax = 180f / 270f
        )
        // 原点 (0,260) ⇒ 视图 px (64,100)-(256,300)：下 (1080-300)/4 = 195、上 (1080-100)/4 = 245
        assertUv(
            frostUvOfCard(cardInWindow, portraitGeometry()),
            uMin = 2f / 152f, uMax = 50f / 152f, vMin = 195f / 270f, vMax = 245f / 270f
        )
    }

    @Test
    fun nonDivisibleImageWidthStillClampsToOne() {
        // 画面宽 605（605/4 → ⌈⌉=152 的 RT，比例不是整 4）：满幅卡片必须正好收到 uMax=1
        val geo = portraitGeometry().copy(contentRectInViewPx = FrostRectPx(56f, 0f, 661f, 1080f))
        val uv = frostUvOfCard(FrostRectPx(0f, 260f, 2000f, 1600f), geo)!!
        assertEquals(0f, uv.uMin, eps)
        assertEquals(1f, uv.uMax, eps)
        assertEquals(0f, uv.vMin, eps)
        assertEquals(1f, uv.vMax, eps)
        assertTrue(uv.uMin <= uv.uMax && uv.uMax <= 1f)
    }

    // ------------------------------------------------- 6. 卡片 → UV：横屏

    @Test
    fun landscapeBottomDockMapsAcrossTheWideImage() {
        // 横屏基线：视图 1280×720、原点 (160,0)、画面铺满视图、RT 320×180
        // 窗口 px 底栏 (400,640)-(1200,712) → 视图 px (240,640)-(1040,712)
        //   左 240×320/1280 = 60；右 1040×320/1280 = 260
        //   下 712 → 180 - 712/4 = 180-178 = 2；上 640 → 180-160 = 20
        assertUv(
            frostUvOfCard(FrostRectPx(400f, 640f, 1200f, 712f), landscapeGeometry()),
            uMin = 60f / 320f, uMax = 260f / 320f, vMin = 2f / 180f, vMax = 20f / 180f
        )
    }

    @Test
    fun landscapePortraitShareTheSameFormulaUnderDifferentAspect() {
        // 同一条公式在两个朝向下都要落在 [0,1] 内且 v 向上（下缘 v 小、上缘 v 大）
        val land = frostUvOfCard(FrostRectPx(160f, 0f, 1440f, 360f), landscapeGeometry())!!
        // 视图 px (0,0)-(1280,360)：整幅宽 → u 0..1；上 0 → v 180，下 360 → 180-90 = 90
        assertUv(land, 0f, 1f, 90f / 180f, 180f / 180f)
        // 竖屏同一档"上半幅"：视图 px (56,0)-(664,540) → u 0..1，v 下 = 270-135 = 135
        assertUv(
            frostUvOfCard(FrostRectPx(56f, 260f, 664f, 800f), portraitGeometry()),
            0f, 1f, 135f / 270f, 270f / 270f
        )
        assertTrue("v 轴方向约定：上缘的 v 必须大于下缘", land.vMax > land.vMin)
    }

    @Test
    fun quadTexCoordsFollowTheStripOrder() {
        val out = FloatArray(8)
        frostUvOfCard(FrostRectPx(64f, 360f, 256f, 560f), portraitGeometry())!!
            .fillQuadTexCoords(out)
        // TRIANGLE_STRIP 的四点顺序与 GlRenderEngine.positions（-1,-1 / 1,-1 / -1,1 / 1,1）同构：
        // 左下、右下、左上、右上。写反了上屏那侧会把霜面整个上下颠倒
        assertEquals(2f / 152f, out[0], eps)   // 左下 u
        assertEquals(195f / 270f, out[1], eps) // 左下 v
        assertEquals(50f / 152f, out[2], eps)  // 右下 u
        assertEquals(195f / 270f, out[3], eps) // 右下 v
        assertEquals(2f / 152f, out[4], eps)   // 左上 u
        assertEquals(245f / 270f, out[5], eps) // 左上 v
        assertEquals(50f / 152f, out[6], eps)  // 右上 u
        assertEquals(245f / 270f, out[7], eps) // 右上 v
    }

    @Test
    fun quadTexCoordsRefuseAnOversmallBuffer() {
        // 传进来的复用缓冲长度不够时必须抛，不能静默越界写（GL 线程抛异常会打断这一帧，但比写坏堆好）
        val uv = frostUvOfCard(FrostRectPx(64f, 360f, 256f, 560f), portraitGeometry())!!
        var thrown = false
        try {
            uv.fillQuadTexCoords(FloatArray(6))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("缓冲不够长必须拒绝，而不是写出去", thrown)
    }

    // ------------------------------------------------- 7. 常量口径

    @Test
    fun downscaleMatchesTheDocumentedNumbers() {
        // 本文件所有手算都按 N=4 推（纹素序号 = 画面内像素偏移 / 4）；常量一改全部期望值作废，
        // 这条的作用是把"手算前提"钉在代码里，逼改动的人回来重算，而不是让测试悄悄跟着实现走
        assertEquals(4, FROST_DOWNSCALE)
        assertEquals(512, FROST_RT_MAX_EDGE)
    }
}
