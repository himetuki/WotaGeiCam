package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.frostSpaceIsViewLocal
import com.wotagei.cam.ui.frostWindowToViewLocal
import com.wotagei.cam.ui.viewLocalOriginInto
import com.wotagei.cam.ui.viewLocalRectInto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「霜矩形在承载视图局部坐标里量」的纯算术层（`ui/FrostSpace.kt`）。
 *
 * 三类账：
 * 1. 坐标系位真源与空值回退口径（[frostSpaceIsViewLocal]、[viewLocalRectInto] 的 null 分支）；
 * 2. 手算像素档：缩放为 1（没分屏）时视图局部矩形就是卡片局部四边，期望值**手写**；
 * 3. **分屏恒等性**（本任务的核心断言）：桥函数把「祖先 0.5 缩放」反解回视图局部坐标，
 *    测试再过一遍合成器那一步 0.5 缩放，断言逐边落回卡片可视矩形；同一组数走**旧口径**
 *    （窗口位置 + 未缩尺寸）必须落空——否则这条用例没有鉴别力。
 *
 * 期望值一律手写字面量（不用被测实现反推）。视图局部两枚 LayoutCoordinates 入口在 JVM 里
 * 造不出来（要真布局），所以这里只测它们的 null 分支与桥；取四边那一层由
 * [FrostRectRegistrarGuardTest] / [WotaDockShellFrostGuardTest] 的源码守卫钉住。
 */
class FrostSpaceTest {

    @Test
    fun `坐标系位真源`() {
        assertFalse("预览盒未登记 ⇒ 窗口系", frostSpaceIsViewLocal(previewProvided = false))
        assertTrue("预览盒已登记 ⇒ 视图局部系", frostSpaceIsViewLocal(previewProvided = true))
    }

    @Test
    fun `预览盒或卡片未量到时视图局部换算一律拒绝`() {
        // 返回 false 是调用方"回退窗口系/不写表"的判据，绝不能在坐标缺失时给一个 (0,0) 假矩形
        assertFalse(viewLocalRectInto(FloatArray(4), card = null, preview = null))
        assertFalse(viewLocalOriginInto(FloatArray(2), card = null, preview = null))
    }

    /**
     * 未 attached / 跨树的防御是 Android 运行时件（要真布局才造得出 LayoutCoordinates），
     * 这里用源码守卫钉住：两枚入口都必须在 `localPositionOf` 前判 `isAttached`、并把调用包进
     * `catch (e: RuntimeException)`（跨树时 compose-ui 1.5.4 的 `localPositionOf` 抛
     * `IllegalArgumentException("layouts are not part of the same hierarchy")`，属 RuntimeException；
     * 未 attached 链上还有 NPE 分支）。**不许退回 `runCatching`**：它连 Throwable 一起吞，
     * OOM/断言错误会被静默降级成窗口系。删掉/改写任一道，这条红。
     */
    @Test
    fun `视图局部换算入口必须防未attached与跨树`() {
        val masked = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("ui/FrostSpace.kt")
        )
        listOf("viewLocalRectInto", "viewLocalOriginInto").forEach { fn ->
            val body = KotlinSourceScan.bodyOf(masked, fn)
            assertTrue("$fn 必须先判 card.isAttached（未 attached 链上有 NPE 分支）", body.contains("card.isAttached"))
            assertTrue("$fn 必须先判 preview.isAttached", body.contains("preview.isAttached"))
            assertTrue(
                "$fn 的 localPositionOf 必须包进 catch (e: RuntimeException)（跨树抛 IllegalArgumentException，" +
                    "属 RuntimeException，收敛成 false）",
                body.contains("preview.localPositionOf(") && body.contains("catch (e: RuntimeException)")
            )
            assertFalse(
                "$fn 不得退回 runCatching（它会连 OOM/断言错误这类 Throwable 一起吞，跨树以外的异常被静默降级）",
                body.contains("runCatching")
            )
            assertTrue(
                "$fn 的失败必须走一次性日志 noteHierarchyFailure（跨树是持续状态，只记一次不刷屏）",
                body.contains("noteHierarchyFailure(")
            )
        }
    }

    @Test
    fun `未缩放时视图局部矩形就是卡片局部四边`() {
        // 手算：splitScale = 1 → 反解是恒等，卡片局部 (10,20) 尺寸 100×60 ⇒ (10,20,110,80)
        val local = frostWindowToViewLocal(
            windowX = 10f, windowY = 20f, cardW = 100f, cardH = 60f,
            splitScale = 1f, splitOriginX = 0f, splitOriginY = 0f
        )
        assertArrayEquals(floatArrayOf(10f, 20f, 110f, 80f), local, 0f)
    }

    @Test
    fun `缩放非正时按恒等处理不留负数`() {
        // 0 或负的缩放没有可逆换算：退化成恒等（宁可给未缩的矩形，也不给一个除零/翻面值）
        val local = frostWindowToViewLocal(
            windowX = 10f, windowY = 20f, cardW = 100f, cardH = 60f,
            splitScale = 0f, splitOriginX = 0f, splitOriginY = 0f
        )
        assertArrayEquals(floatArrayOf(10f, 20f, 110f, 80f), local, 0f)
    }

    /**
     * 分屏恒等性：这是本任务的核心断言。
     *
     * 场景（数字手写）：分屏把整页按 0.5 缩到左半屏，变换原点取页面左缘中点 (0, 400)。
     * 卡片在页面坐标里是 (300, 200, 400, 260)。旧口径 positionInWindow 量到的位置是它经
     * `S(p) = o + (p − o)·s` 之后的值（(150, 300)），而 size 不缩（仍是 100×60）——位置缩了、
     * 宽度没缩，于是板偏大且错位。新口径在视图局部坐标里量，经合成器那一步 0.5 后逐边落回卡片。
     */
    @Test
    fun `视图局部矩形经合成器后逐边落在卡片可视矩形上`() {
        val s = 0.5f
        val ox = 0f
        val oy = 400f
        val cx = 300f
        val cy = 200f
        val w = 100f
        val h = 60f
        // 合成器那一步（与祖先那层同一个 S，只是本测试把它写在"板画出来之后"）
        fun compose(p: FloatArray) = floatArrayOf(
            ox + (p[0] - ox) * s, oy + (p[1] - oy) * s,
            ox + (p[2] - ox) * s, oy + (p[3] - oy) * s
        )
        val cardVisual = compose(floatArrayOf(cx, cy, cx + w, cy + h))
        // 旧口径量到的窗口位置（= positionInWindow 的读数）
        val winX = ox + (cx - ox) * s
        val winY = oy + (cy - oy) * s

        val newLocal = frostWindowToViewLocal(winX, winY, w, h, s, ox, oy)
        assertArrayEquals(
            "视图局部矩形经合成器后必须逐边落回卡片可视矩形",
            cardVisual, compose(newLocal), 1e-4f
        )

        // 旧口径（窗口位置 + 未缩尺寸）经同一次合成必须落空，否则本用例无鉴别力
        val oldVisual = compose(floatArrayOf(winX, winY, winX + w, winY + h))
        assertNotEquals("旧口径左缘必须对不上", cardVisual[0], oldVisual[0])
        assertNotEquals("旧口径右缘必须对不上（宽度没跟着缩的那笔账）", cardVisual[2], oldVisual[2])
    }
}
