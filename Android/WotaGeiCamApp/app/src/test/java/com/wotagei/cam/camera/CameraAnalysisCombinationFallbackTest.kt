package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Y69 · 普通会话拒绝「预览/编码 + 分析 YUV」组合时，可选站位功能不许把整场预览拖死。
 *
 * ## 缺陷
 * 部分机型支持该 YUV 单流尺寸，却拒绝它和预览/编码面的组合。旧实现有两处叠加：
 * ① 建会话**前**就报 [AnalysisSessionDecision.INCLUDED]——接纳是设备的裁决，先报等于替平台打包票；
 * ② 普通会话 `onConfigureFailed` / 同步 IAE 走 failDevice → 自动重开，重开仍带同一个分析面
 * ⇒ 一个可选功能让整场预览/录制反复建会话失败（REOPEN_MAX 用尽后停在失败态）。
 *
 * ## 修法
 * 接纳只在 `onConfigured` 成功后确认；组合被拒时撤掉**进会话**的分析面、保留调用方意图与 reader，
 * 立即按基础输出重连一次，并把 [AnalysisSessionDecision.BLOCKED_COMBINATION] 交给站位层作暂停原因。
 * 一次挂槽只自动撤一次——基础会话再失败就回到既有 failDevice 自愈，不会形成拒绝-重试循环。
 */
class CameraAnalysisCombinationFallbackTest {

    @Test
    fun `普通会话的INCLUDED只在配置成功后确认`() = platform { p ->
        val h = regularCamera(p)
        p.analysis(h)
        p.open(h, p.slot("0"))

        assertEquals("实际必须建出带分析面的普通会话", listOf("preview", "yuv-analysis"), p.outputNames())
        assertEquals(
            "配置结果出来之前不许先报 INCLUDED（旧实现替平台打包票）",
            emptyList<String>(),
            h.decisions
        )

        p.configured()
        assertEquals("配置成功后才确认接纳", listOf("INCLUDED"), h.decisions)
    }

    @Test
    fun `同步拒绝组合时撤分析面重试基础会话并交代暂停原因`() = platform { p ->
        val h = regularCamera(p)
        p.rejectAnalysisCombination()
        p.analysis(h)
        p.open(h, p.slot("0"))

        assertTrue("桩必须真的拒绝过带分析面的组合（否则下面全是空转）", p.events.contains("reject:analysis"))
        assertEquals("被拒后只许重连一条基础会话", 1, p.sessions.size)
        assertEquals(listOf("preview"), p.outputNames())
        assertEquals(
            "站位层必须收到明确暂停原因，而不是设备失败/重开",
            listOf("BLOCKED_COMBINATION"),
            h.decisions
        )
        assertSame(
            "撤槽只是不进会话：调用方的分析意图与 surface 不许被摘掉",
            h.analysis,
            p.field(h.engine, "analysisSurface")
        )
        assertEquals("可选分析面被拒不许触发设备重开", 1, p.opened.size)
        assertFalse(
            "设备不能被一个可选输出面连带关掉",
            p.events.any { it.startsWith("device.close") }
        )

        // 基础会话配置成功也不许冒充 INCLUDED——这条会话里本来就没有分析面。
        p.configured()
        assertEquals(listOf("BLOCKED_COMBINATION"), h.decisions)
    }

    @Test
    fun `配置回调失败同样撤分析面重试且不重开设备`() = platform { p ->
        val h = regularCamera(p)
        p.analysis(h)
        p.open(h, p.slot("0"))
        assertEquals(1, p.sessions.size)

        p.configureFailed(0)

        assertEquals("失败后必须按基础输出重连一次（不再带分析面）", 2, p.sessions.size)
        assertEquals(listOf("preview"), p.outputNames())
        assertEquals(listOf("BLOCKED_COMBINATION"), h.decisions)
        assertSame(h.analysis, p.field(h.engine, "analysisSurface"))
        assertEquals("已经拿到设备，组合被拒不等于设备失效", 1, p.opened.size)
        assertEquals("不许排设备重开", 0, p.delayedCount(h))
    }

    @Test
    fun `一次挂槽只自动撤一次_基础会话再失败交回设备自愈`() = platform { p ->
        val h = regularCamera(p)
        p.rejectAnalysisCombination()
        p.analysis(h)
        p.open(h, p.slot("0"))
        assertEquals(1, p.sessions.size)

        p.configureFailed(0)

        assertEquals(
            "分析面已撤，基础会话再失败必须回到既有 failDevice 自愈（排一次重开）",
            1,
            p.delayedCount(h)
        )
        assertEquals("同一暂停原因不重复弹", listOf("BLOCKED_COMBINATION"), h.decisions)
    }

    @Test
    fun `撤面后重新挂槽会再协商一次分析组合`() = platform { p ->
        val h = regularCamera(p)
        p.rejectAnalysisCombination()
        p.analysis(h)
        p.open(h, p.slot("0"))
        assertEquals(listOf("BLOCKED_COMBINATION"), h.decisions)

        // 设备这次接受组合：用户关→开分析（显式重试）必须重新带上分析面。
        p.rejectAnalysisCombination(false)
        p.analysis(h, enabled = false)
        p.analysis(h)

        assertEquals(listOf("preview", "yuv-analysis"), p.outputNames())
        assertEquals(listOf("BLOCKED_COMBINATION", "ABSENT"), h.decisions)

        p.configured()
        assertEquals(
            "重试成功后必须恢复接纳回执",
            listOf("BLOCKED_COMBINATION", "ABSENT", "INCLUDED"),
            h.decisions
        )
    }

    private fun regularCamera(p: CameraPlatformFixture): CameraPlatformFixture.Harness {
        p.publicIds("0")
        p.camera("0")
        return p.engine()
    }

    private fun platform(block: (CameraPlatformFixture) -> Unit) {
        CameraPlatformFixture(classes, javaClass.classLoader!!).use(block)
    }

    companion object {
        @JvmField @ClassRule
        val temporary = TemporaryFolder(File("build/tmp/camera-analysis-combination-test").apply { mkdirs() })
        private val classes: File by lazy { CameraPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
