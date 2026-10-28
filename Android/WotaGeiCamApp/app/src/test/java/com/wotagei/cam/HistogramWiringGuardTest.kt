package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲线框背景直方图（2026-10-27）的**接线守卫**：这条链跨四个文件
 * （GL 采样 → 快照发布 → 弹窗轮询 → 背景绘制），任何一环松开都是
 * "功能在、界面无、或界面在、数据死"——编译不报、JVM 摸不到、只有真机看得见。
 * 全部走 [KotlinSourceScan] 源码钉扎（codeOnly 遮蔽后判据），尺子配突变自证。
 *
 * 语义口径（写在测试里防止实现注释被删后无处可查）：
 * - 采样口径 = **曲线输出后**的画面（drawPass 用当前 program）；
 * - 采样只发生在**弹窗可见且 GPU 渲染**时（DIRECT 无 GL 帧；不弹窗不占读回预算）；
 * - 快照发布 = 整体引用替换（读侧免锁）；
 * - 背景条形画在网格/曲线**之下**（高透明，属参考信息不是交互层）。
 */
class HistogramWiringGuardTest {

    private val gl by lazy { codeOnly(KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt")) }
    private val popup by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/dialog/CurvePopup.kt")) }
    private val screen by lazy { codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")) }

    @Test
    fun `GL侧_采样接线与生命周期完整`() {
        assertTrue(
            "drawFrame 尾部必须挂采样入口（不挂 = 直方图永远空）",
            KotlinSourceScan.occurrences(gl, "maybeSampleHistogram()").isNotEmpty()
        )
        val sample = KotlinSourceScan.bodyOf(gl, "maybeSampleHistogram")
        assertTrue("采样必须吃开关（弹窗不可见不采）", sample.contains("wantHistogram"))
        assertTrue("采样必须有拍点节流（250ms）", sample.contains("HISTOGRAM_PERIOD_MS"))
        assertTrue("必须 glReadPixels 读回", sample.contains("glReadPixels"))
        assertTrue(
            "分箱必须走 core 纯函数（散写 = 无法 JVM 测）",
            sample.contains("HistogramMath.binRgba")
        )
        assertTrue("快照必须引用替换发布", sample.contains("histogramSnapshot = bins"))
        // 生命周期：GL 资源释放路径（releaseGl）里必须回收迷你 FBO，
        // 且在 teardownEgl **之前**（删 FBO 需要现存的当前上下文）
        val rel = KotlinSourceScan.bodyOf(gl, "releaseGl")
        val hist = rel.indexOf("releaseHistogram()")
        val teardown = rel.indexOf("teardownEgl()")
        assertTrue("GL 资源释放路径（releaseGl）必须回收直方图 FBO", hist >= 0)
        assertTrue(
            "releaseHistogram 必须在 teardownEgl 之前（之后调用是空操作，上下文已销毁）",
            teardown < 0 || hist in 0 until teardown
        )
        assertTrue("弹窗开关入口必须在场", gl.contains("fun setHistogramEnabled"))
    }

    @Test
    fun `UI侧_轮询与背景绘制接线完整`() {
        assertTrue(
            "CurvePopup 必须有 histogramProvider 注入口",
            popup.contains("histogramProvider:")
        )
        assertTrue(
            "弹窗必须轮询快照进重组状态（不轮询 = 画面静止）",
            KotlinSourceScan.occurrences(popup, "histogram = histogramProvider").isNotEmpty()
        )
        val canvas = KotlinSourceScan.bodyOf(popup, "CurveCanvas")
        // 参数在签名上（bodyOf 只取花括号体），签名的可空直方图参数钉在整个文件上
        assertTrue(
            "CurveCanvas 必须接收直方图参数（默认 null = 无数据时不画）",
            popup.contains("histogram: IntArray? = null")
        )
        assertTrue("背景绘制必须走归一纯函数", canvas.contains("HistogramMath.normalizedHeights"))
        assertTrue("背景绘制只在有快照时进行", canvas.contains("if (histogram != null)"))
        // 高透明（29 §2.2 起**按通道**取数着色，master 走亮度 Y）：当前通道 tint alpha 0.30f
        //（钉字面量，改透明度必须过这里）；三色混排同绘已退场
        assertTrue("背景必须高透明（alpha 0.30f）", canvas.contains("copy(alpha = 0.30f)"))
        assertTrue("必须按通道取数组（不再三色混排）", canvas.contains("histogramBase(channel)"))
        assertTrue("必须有按通道着色的背景趋势条", canvas.contains("Brush.horizontalGradient(trendColors(channel))"))
    }

    @Test
    fun `录制页_开关与数据源接线完整`() {
        assertTrue(
            "开关必须随曲线弹窗可见性与 GPU 档联动",
            screen.contains("setHistogramEnabled(sheet == Sheet.CURVE && renderMode == RenderMode.GPU)")
        )
        assertTrue(
            "数据源必须来自当前 GL 引擎的快照",
            screen.contains("(activeSink as? GlRenderEngine)?.histogramSnapshot")
        )
    }

    @Test
    fun `尺子自己能红_摘掉采样或换掉数据源必报红`() {
        // 突变 1：GL 侧摘掉采样入口
        val glBad = gl.replace("maybeSampleHistogram()", "maybeSampleHistogramX()")
        assertTrue(
            "摘掉采样入口必须报红",
            KotlinSourceScan.occurrences(glBad, "maybeSampleHistogram()").isEmpty()
        )
        // 突变 2：录制页换掉数据源
        val screenBad = screen.replace(
            "(activeSink as? GlRenderEngine)?.histogramSnapshot", "null"
        )
        assertTrue(
            "换掉数据源必须报红",
            !screenBad.contains("(activeSink as? GlRenderEngine)?.histogramSnapshot")
        )
        // 真身必须绿（避免尺子本身失效）
        assertTrue(gl.contains("maybeSampleHistogram()"))
        assertTrue(screen.contains("(activeSink as? GlRenderEngine)?.histogramSnapshot"))
    }
}
