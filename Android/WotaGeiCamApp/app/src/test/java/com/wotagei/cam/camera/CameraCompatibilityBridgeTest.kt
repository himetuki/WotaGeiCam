package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A03/A04/A05/A07：执行真实枚举、open、建会话及 repeating 回调，不复制业务决策或读源码字符串。 */
class CameraCompatibilityBridgeTest {
    @Test
    fun `A03 普通真实repeating结果增长判活心跳和帧账`() = platform { p ->
        val h = p.engine()
        val session = p.repeating(h, highSpeed = false)
        repeat(7) { p.frame(session, it.toLong()) }
        assertEquals(7L, p.call(h.engine, "frameResultCount"))
        assertEquals(7L, p.ledger())
        assertEquals(7L, p.field(h.engine, "camResultCount"))
    }

    @Test
    fun `A03 高速真实burst的健康十五秒每秒都增长判活心跳`() = platform { p ->
        val h = p.engine(fps = 120)
        val session = p.repeating(h, highSpeed = true)
        val heartbeat = ArrayList<Long>()
        repeat(15) { second ->
            p.clock((second + 1) * 1_000L)
            repeat(120) { frame -> p.frame(session, second * 120L + frame) }
            heartbeat.add(p.call(h.engine, "frameResultCount") as Long)
        }
        assertEquals("FrameLedger 活着不能替代实际给看门狗的心跳", 1_800L, p.ledger())
        assertEquals((1..15).map { it * 120L }, heartbeat)
    }

    @Test
    fun `A03 高速采样清窗口但不能清累计或制造停止后的心跳`() = platform { p ->
        val h = p.engine(fps = 120)
        val session = p.repeating(h, highSpeed = true)
        repeat(5) { p.frame(session, it.toLong()) }
        val before = p.call(h.engine, "frameResultCount")
        p.call(h.engine, "sampleCameraRate", 1_000L)
        val sampled = p.call(h.engine, "frameResultCount")
        p.clock(16_000L) // 不再投递结果：时间推进本身绝不能伪造“仍有帧”。
        assertEquals(0L, p.field(h.engine, "camResultCount"))
        assertEquals(5L, before)
        assertEquals(before, sampled)
        assertEquals(sampled, p.call(h.engine, "frameResultCount"))
    }

    @Test
    fun `A03 高速不能引入普通回调的手动WB保活和探测副作用`() = platform { p ->
        val h = p.engine(fps = 120)
        p.manualWb(h.params)
        val session = p.repeating(h, highSpeed = true)
        repeat(30) {
            p.clock((it + 1) * 500L)
            p.frame(session, it.toLong())
        }
        assertEquals(30L, p.ledger())
        assertEquals(0L, p.field(h.engine, "lastWbKeepAliveMs"))
        assertEquals(0, p.field(h.engine, "wbProbeFails"))
        assertEquals(false, p.field(h.engine, "dirty"))
        assertEquals(false, p.value(h.params, "WbHalRejected"))
    }

    @Test
    fun `A04 标准未公开物理头完整枚举后经公开逻辑头打开并下发物理路由`() = physicalRoute("20", "21")

    @Test
    fun `A04 数字探测范围内物理ID也不能误当独立openable`() = physicalRoute("2", "3")

    private fun physicalRoute(first: String, second: String) = platform { p ->
        p.publicIds("0", "1")
        p.camera("0", logical = true, physicals = listOf(first, second))
        p.camera("1", front = true)
        p.camera(first, openable = false, focal = 2f)
        p.camera(second, openable = false, focal = 8f)
        val slots = p.enumerate()
        // 既不以纯 helper 假装执行枚举，也不因旧路由错误令查找抛 NoSuchElementException 假 RED。
        val slot = slots.firstOrNull { p.physical(it) == first || p.logic(it) == first }
        assertNotNull("可读物理头必须保留其可选入口", slot)
        val h = p.engine()
        p.open(h, slot!!)
        assertEquals("实际 openCamera 必须指向公开逻辑 ID，不能试开只读物理 ID", listOf("0"), p.opened)
        assertEquals("真实 ensureSession 必须被执行", 1, p.sessions.size)
        assertEquals(listOf(first), p.outputPhysicals())
        assertEquals(listOf("preview"), p.outputNames())
        assertTrue("每颗未公开物理头都保留逻辑/物理关联", slots.any { p.logic(it) == "0" && p.physical(it) == second })
        assertFalse("characteristics 可读不是独立打开的证明", slots.any { p.logic(it) == first && p.physical(it) == null })
    }

    @Test
    fun `A04 已可独立打开的厂商数字隐藏扩展继续真实可选可开`() = platform { p ->
        p.publicIds("0", "1")
        p.camera("0")
        p.camera("1", front = true)
        p.camera("7", openable = true, focal = 2f)
        val slot = p.enumerate().firstOrNull { p.key(it) == "7" }
        assertNotNull("不能为修标准多摄而删掉厂商可用扩展", slot)
        val h = p.engine()
        p.open(h, slot!!)
        assertEquals(listOf("7"), p.opened)
        assertEquals(1, p.sessions.size)
        assertEquals(listOf<String?>(null), p.outputPhysicals())
    }

    @Test
    fun `A04 有标准物理路由和隐藏扩展时仍保留公开逻辑兜底`() = platform { p ->
        p.publicIds("0", "1")
        p.camera("0", logical = true, physicals = listOf("20"))
        p.camera("20", openable = false)
        p.camera("1", front = true)
        p.camera("7", openable = true)
        val slots = p.enumerate()
        assertTrue("未经独立打开验证的候选不能消灭公开逻辑兜底", slots.any { p.key(it) == "0" })
        assertTrue("厂商扩展仍在", slots.any { p.key(it) == "7" })
        val fallback = slots.first { p.key(it) == "0" }
        val h = p.engine()
        p.open(h, fallback)
        assertEquals(listOf("0"), p.opened)
        assertEquals(listOf<String?>(null), p.outputPhysicals())
    }

    @Test
    fun `A04 完整枚举中的物理头能力仍借逻辑AF且不污染自身曝光域`() = platform { p ->
        p.publicIds("0", "1")
        p.camera("0", logical = true, physicals = listOf("20"), af = intArrayOf(0, 1, 3))
        p.camera("1", front = true)
        p.camera("20", openable = false, af = intArrayOf(0))
        val slot = p.enumerate().firstOrNull { p.physical(it) == "20" || p.logic(it) == "20" }
        assertNotNull(slot)
        assertEquals(setOf(0, 1, 3), p.call(p.ability(slot!!), "getAfModes"))
        assertEquals(0f, p.call(p.ability(slot), "getMinFocusDistanceDiopter"))
    }

    @Test
    fun `A05 空物理集合执行完整枚举仍保留逻辑后摄`() = logicalFallback(emptyList())

    @Test
    fun `A05 读取物理集合异常执行完整枚举仍保留逻辑后摄`() = logicalFallback(emptyList(), readFails = true)

    @Test
    fun `A05 全部物理子头能力读取失败仍保留逻辑后摄`() = logicalFallback(listOf("20", "21"))

    private fun logicalFallback(physicals: List<String>, readFails: Boolean = false) = platform { p ->
        p.publicIds("0", "1")
        p.camera("0", logical = true, physicals = physicals, physicalReadFails = readFails)
        p.camera("1", front = true)
        val slots = p.enumerate()
        val logical = slots.firstOrNull { p.key(it) == "0" }
        assertNotNull("不是只测 shouldHideLogicalHead：完整枚举必须实际产出可用逻辑后摄", logical)
        val h = p.engine()
        p.open(h, logical!!)
        assertEquals(listOf("0"), p.opened)
        assertEquals(1, p.sessions.size)
    }

    @Test
    fun `A05 部分子头展开失败时可用子头与公开逻辑兜底均不丢失`() = platform { p ->
        p.publicIds("0", "1")
        p.camera("0", logical = true, physicals = listOf("20", "21"))
        p.camera("1", front = true)
        p.camera("20", openable = false)
        val slots = p.enumerate()
        assertTrue(slots.any { p.key(it) == "0" })
        assertTrue(slots.any { p.logic(it) == "0" && p.physical(it) == "20" })
        assertFalse(slots.any { p.key(it) == "21" || p.physical(it) == "21" })
    }

    @Test
    fun `A05 全部子头作为公开独立入口时允许去重且不丢可开后摄`() = platform { p ->
        p.publicIds("0", "1", "2", "3")
        p.camera("0", logical = true, physicals = listOf("2", "3"))
        p.camera("1", front = true)
        p.camera("2", focal = 2f)
        p.camera("3", focal = 8f)
        val keys = p.enumerate().map { p.key(it) }
        assertTrue(keys.containsAll(listOf("1", "2", "3")))
        assertFalse("已公开的物理头不再重复挂逻辑路由", keys.any { it.startsWith("0#") })
    }

    @Test
    fun `A07 普通DIRECT会话仍接纳分析和编码三面且不降档`() = platform { p ->
        val h = highSpeedCamera(p, fps = 30)
        p.analysis(h)
        p.extra(h)
        p.open(h, p.slot("0"))
        assertEquals(0, p.sessionType())
        assertEquals(listOf("preview", "encoder", "yuv-analysis"), p.outputNames())
        assertTrue(p.events.none { it.startsWith("downgrade:") })
        assertEquals(30, p.call(p.value(h.params, "Fps")!!, "getValue"))
    }

    @Test
    fun `A07 高速DIRECT起录前协商排除YUV只建合法双面`() = blockedTargets(gpu = false, fps = 120)

    @Test
    fun `A07 高速GPU起录前协商排除YUV且编码面仍不进相机会话`() = blockedTargets(gpu = true, fps = 120)

    @Test
    fun `A07 仅高速表覆盖50帧时同样禁分析而不是按大于60猜测`() = blockedTargets(gpu = false, fps = 50)

    @Test
    fun `A07 仅高速表覆盖60帧时同样禁分析而不是退普通`() = blockedTargets(gpu = false, fps = 60)

    private fun blockedTargets(gpu: Boolean, fps: Int) = platform { p ->
        val h = highSpeedCamera(p, fps, gpu)
        p.analysis(h)
        p.extra(h)
        p.open(h, p.slot("0"))
        assertEquals("保用户合法高速档", 1, p.sessionType())
        assertEquals(if (gpu) listOf("preview") else listOf("preview", "encoder"), p.outputNames())
        assertEquals(fps, p.call(p.value(h.params, "Fps")!!, "getValue"))
        assertTrue("不等 native 拒绝再自动退帧率", p.events.none { it.startsWith("downgrade:") })
        assertSame("禁入高速不等于销毁或摘除调用方的分析意图", h.analysis, p.field(h.engine, "analysisSurface"))
    }

    @Test
    fun `A07 高速未请求分析时保留合法会话且不产生互斥提示`() = platform { p ->
        val h = highSpeedCamera(p, fps = 120)
        p.extra(h)
        p.open(h, p.slot("0"))
        assertEquals(1, p.sessionType())
        assertEquals(listOf("preview", "encoder"), p.outputNames())
        assertFalse(h.decisions.contains("BLOCKED_HIGH_SPEED"))
    }

    @Test
    fun `A07 分析互斥结果必须在真实createCaptureSession之前同步发出`() = platform { p ->
        val h = highSpeedCamera(p, fps = 120)
        p.analysis(h)
        p.open(h, p.slot("0"))
        assertEquals("声明回调但不发事件不能假装已告知上层", listOf("BLOCKED_HIGH_SPEED"), h.decisions)
        assertTrue(p.events.indexOf("analysis:BLOCKED_HIGH_SPEED") < p.events.indexOf("create:1"))
    }

    @Test
    fun `A07 重放同一分析槽幂等且不重复弹同一互斥结果`() = platform { p ->
        val h = highSpeedCamera(p, fps = 120)
        p.analysis(h)
        p.open(h, p.slot("0"))
        val count = p.sessions.size
        repeat(3) { p.analysis(h) }
        assertEquals("同(surface,reader)重放不得重建会话", count, p.sessions.size)
        assertEquals(listOf("BLOCKED_HIGH_SPEED"), h.decisions)
    }

    @Test
    fun `A07 结构脉冲跨普通高速再回普通时同一分析槽自动恢复`() = platform { p ->
        val h = highSpeedCamera(p, fps = 30)
        p.analysis(h)
        p.open(h, p.slot("0"))
        p.pulse(h, 50)
        p.pulse(h, 30)
        assertEquals(listOf(0, 1, 0), p.sessions.map { p.sessionType(it) })
        assertEquals(listOf(listOf("preview", "yuv-analysis"), listOf("preview"),
            listOf("preview", "yuv-analysis")), p.sessions.map { p.outputNames(it) })
        assertSame(h.analysis, p.field(h.engine, "analysisSurface"))
        assertEquals(listOf("INCLUDED", "BLOCKED_HIGH_SPEED", "INCLUDED"), h.decisions)
    }

    @Test
    fun `A07 高速录制面起停均不能让仍保留的分析意图混入会话`() = platform { p ->
        val h = highSpeedCamera(p, fps = 120)
        p.analysis(h)
        p.open(h, p.slot("0"))
        p.extra(h)
        p.extra(h, enabled = false)
        assertEquals(listOf(listOf("preview"), listOf("preview", "encoder"), listOf("preview")),
            p.sessions.map { p.outputNames(it) })
        assertTrue(p.sessions.all { p.sessionType(it) == 1 })
        assertSame(h.analysis, p.field(h.engine, "analysisSurface"))
        assertEquals(listOf("BLOCKED_HIGH_SPEED"), h.decisions)
    }

    @Test
    fun `A07 普通会话的分析接纳回执与摘槽回执反映真实目标`() = platform { p ->
        val h = highSpeedCamera(p, fps = 30)
        p.analysis(h)
        p.open(h, p.slot("0"))
        p.analysis(h, enabled = false)
        assertEquals(listOf(listOf("preview", "yuv-analysis"), listOf("preview")),
            p.sessions.map { p.outputNames(it) })
        assertEquals(listOf("INCLUDED", "ABSENT"), h.decisions)
    }

    private fun highSpeedCamera(p: CameraPlatformFixture, fps: Int, gpu: Boolean = false): CameraPlatformFixture.Harness {
        p.publicIds("0")
        p.camera("0", highSpeed = true)
        return p.engine(fps, gpu)
    }
    private fun platform(block: (CameraPlatformFixture) -> Unit) {
        CameraPlatformFixture(classes, javaClass.classLoader).use(block)
    }
    companion object {
        @JvmField @ClassRule
        val temporary = TemporaryFolder(File("build/tmp/camera-compatibility-test").apply { mkdirs() })
        private val classes: File by lazy { CameraPlatformFixture.compile(temporary.newFolder("platform")) }
    }
}
