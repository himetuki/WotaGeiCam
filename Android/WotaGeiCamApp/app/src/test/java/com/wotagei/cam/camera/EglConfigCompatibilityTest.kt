package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * U01：真实构造/start → chooseConfig → createContext → pbuffer 受控失败 → 真实清理。
 * 正向证据是成功 context 的身份/版本及回收，不是故意为 false 的 ready。
 * 不模拟生产选择顺序，不证明完整 GL、驱动、编码面、实际像素或线程竞态。
 */
class EglConfigCompatibilityTest {
    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/egl-config-test").apply { mkdirs() })

    private val classes by lazy { EglConfigPlatformFixture.compile(temporary.newFolder("platform")) }

    @Test
    fun `U01_预览仅ES2配置仍必须实际建出ES2上下文`() = platform(false, {
        config("es2", renderable = 0x0004)
    }) { p ->
        contextCreated(p, "es2/2")
        assertEquals(2, p.engineField("eglVersion"))
    }

    @Test
    fun `U01_Arc仅recordable的ES2配置仍必须实际建出上下文`() = platform(true, {
        config("recordable-es2", renderable = 0x0004, recordable = true)
    }) { p -> contextCreated(p, "recordable-es2/2") }

    @Test
    fun `双版本正常时实际建ES3且Arc保留可用recordable配置`() {
        for (arc in listOf(false, true)) platform(arc, {
            config("dual", recordable = arc)
            if (arc) config("generic-dual")
        }) { p ->
            contextCreated(p, "dual/3")
            assertEquals(listOf("dual/3"), p.contextAttempts)
            if (arc) {
                assertTrue("recordable 已有解，不应无故降成 generic", p.requests.all { attributes(it)[0x3142] == 1 })
            } else assertEquals(3, p.engineField("eglVersion"))
        }
    }

    @Test
    fun `ES3创建返回NO_CONTEXT后真实回退同一配置的ES2`() {
        for (arc in listOf(false, true)) platform(arc, {
            config("dual", recordable = arc)
            script("failEs3", true)
        }) { p ->
            contextCreated(p, "dual/2")
            assertEquals(listOf("dual/3", "dual/2"), p.contextAttempts)
            if (!arc) assertEquals(2, p.engineField("eglVersion"))
        }
    }

    @Test
    fun `两个版本上下文均失败时不得继续建pbuffer或伪造就绪`() {
        for (arc in listOf(false, true)) platform(arc, {
            config("dual", recordable = arc)
            script("failEs3", true)
            script("failEs2", true)
        }) { p ->
            noContext(p)
            assertEquals(listOf("dual/3", "dual/2"), p.contextAttempts)
        }
    }

    @Test
    fun `choose返回false即使残留正count和config也不能创建上下文`() {
        for (arc in listOf(false, true)) platform(arc, {
            config("dual", recordable = arc)
            script("chooseFault", 1)
        }) { p ->
            noContext(p)
            assertEquals(emptyList<String>(), p.contextAttempts)
            assertTrue(p.events.any { it.startsWith("choose.false:") })
        }
    }

    @Test
    fun `choose返回true但num0即使残留config也不能创建上下文`() {
        for (arc in listOf(false, true)) platform(arc, {
            config("dual", recordable = arc)
            script("chooseFault", 2)
        }) { p ->
            noContext(p)
            assertEquals(emptyList<String>(), p.contextAttempts)
            assertTrue(p.events.any { it.startsWith("choose.empty:") })
        }
    }

    @Test
    fun `ES2回退不能把缺WINDOW位的配置当作可用`() {
        for (arc in listOf(false, true)) platform(arc, {
            config("no-window", renderable = 0x0004, surfaces = 0x0001, recordable = arc)
        }) { p -> noContext(p) }
    }

    @Test
    fun `ES2回退不能把缺PBUFFER位的配置当作可用`() {
        for (arc in listOf(false, true)) platform(arc, {
            config("no-pbuffer", renderable = 0x0004, surfaces = 0x0004, recordable = arc)
        }) { p -> noContext(p) }
    }

    @Test
    fun `ES2回退不能顺带取消RGBA每通道至少8位的约束`() {
        val insufficient = listOf(
            intArrayOf(7, 8, 8, 8), intArrayOf(8, 7, 8, 8),
            intArrayOf(8, 8, 7, 8), intArrayOf(8, 8, 8, 7),
        )
        for (arc in listOf(false, true)) for (rgba in insufficient) platform(arc, {
            config("insufficient-rgba", renderable = 0x0004, rgba = rgba, recordable = arc)
        }) { p -> noContext(p) }
    }

    @Test
    fun `Arc扩展拒绝recordable请求后真实选择generic双版本配置`() = platform(true, {
        config("generic")
        script("rejectRecordable", true)
    }) { p ->
        contextCreated(p, "generic/3")
        recordableFallback(p)
        assertTrue(p.events.any { it == "choose.false:recordable" })
        assertTrue("不能尝试 false 返回的毒 config", p.contextAttempts.all { it.startsWith("generic/") })
    }

    @Test
    fun `Arc_recordable匹配num0后真实选择generic双版本配置`() = platform(true, {
        config("generic")
    }) { p ->
        contextCreated(p, "generic/3")
        recordableFallback(p)
        assertTrue(p.events.any { it == "choose.empty:recordable" })
        assertTrue("不能尝试 num0 残留的毒 config", p.contextAttempts.all { it.startsWith("generic/") })
    }

    @Test
    fun `U01_Arc扩展拒绝与generic仅ES2两条降级轴可以组合`() = platform(true, {
        config("generic-es2", renderable = 0x0004)
        script("rejectRecordable", true)
    }) { p ->
        contextCreated(p, "generic-es2/2")
        recordableFallback(p)
    }

    @Test
    fun `U01_Arc_recordable无匹配与generic仅ES2两条降级轴可以组合`() = platform(true, {
        config("generic-es2", renderable = 0x0004)
    }) { p ->
        contextCreated(p, "generic-es2/2")
        recordableFallback(p)
    }

    @Test
    fun `Arc两种属性组均无配置时失败收口且不尝试残留句柄`() = platform(true, {}) { p ->
        noContext(p)
        recordableFallback(p)
        assertEquals(emptyList<String>(), p.contextAttempts)
    }

    private fun platform(
        arc: Boolean,
        configure: EglConfigPlatformFixture.() -> Unit,
        assertions: (EglConfigPlatformFixture) -> Unit,
    ) {
        EglConfigPlatformFixture(classes, javaClass.classLoader).use { p ->
            p.configure()
            assertFalse("pbuffer 故意失败：不能以 ready=false 单独作为成功证据", p.start(arc))
            assertEquals("必须真正进入生产初始化，不能只构造空壳", 1, p.number("initializeCalls"))
            assertEquals(1, p.number("getDisplayCalls"))
            assertTrue("必须真正调用生产 chooseConfig", p.requests.isNotEmpty())
            assertRequestConstraints(p)
            assertSame(p.sentinel("EGL_NO_DISPLAY"), p.engineField("eglDisplay"))
            assertSame(p.sentinel("EGL_NO_CONTEXT"), p.engineField("eglContext"))
            assertNull(p.engineField("eglConfig"))
            assertEquals(false, p.engineField("glReady"))
            assertEquals("生产初始化失败分支应终止已初始化 display", 1, p.number("terminateCalls"))
            assertEquals("创建的 context 必须在真实清理中关闭", p.created.size, p.number("destroyContextCalls"))
            assertEquals(0, p.number("liveContextCount"))
            assertEquals(0, p.number("initializedDisplayCount"))
            assertEquals(emptyList<String>(), p.violations)
            val afterInit = p.events
            p.release()
            p.release()
            assertEquals("release 幂等，不能重复销毁初始化已收回的 EGL 资源", afterInit, p.events)
            assertEquals("真实 release 必须退出调度边界", 1, p.number("quitCalls"))
            assertions(p)
        }
    }

    private fun contextCreated(p: EglConfigPlatformFixture, expected: String) {
        assertEquals("必须真正创建出目标版本，而非只打印回退日志；事件=${p.events}", listOf(expected), p.created)
        assertEquals(1, p.number("pbufferCalls"))
        val create = p.events.indexOfFirst { it.startsWith("context.created:") }
        val stop = p.events.indexOf("pbuffer.rejected")
        val destroy = p.events.indexOfFirst { it.startsWith("context.destroyed:") }
        val terminate = p.events.indexOf("display.terminated")
        assertTrue("须沿真实 init→pbuffer失败→teardown 收口：${p.events}", create >= 0 && create < stop && stop < destroy && destroy < terminate)
    }

    private fun noContext(p: EglConfigPlatformFixture) {
        assertEquals(emptyList<String>(), p.created)
        assertEquals("配置/上下文均不可用时不得继续初始化", 0, p.number("pbufferCalls"))
    }

    private fun recordableFallback(p: EglConfigPlatformFixture) {
        val requests = p.requests.map { attributes(it) }
        assertTrue("必须实际尝试 recordable 属性组", requests.any { it[0x3142] == 1 })
        assertTrue("必须实际尝试不带扩展的 generic 属性组", requests.any { 0x3142 !in it })
    }

    /** 只解析平台收到的属性，不推导生产选择/重试顺序。 */
    private fun attributes(values: List<Int>): Map<Int, Int> {
        assertEquals(0x3038, values.last())
        val result = linkedMapOf<Int, Int>()
        var i = 0
        while (i < values.size && values[i] != 0x3038) {
            assertTrue("EGL 属性必须成对", i + 1 < values.size)
            result[values[i]] = values[i + 1]
            i += 2
        }
        return result
    }

    private fun assertRequestConstraints(p: EglConfigPlatformFixture) {
        for (request in p.requests) {
            val attrs = attributes(request)
            for (color in listOf(0x3024, 0x3023, 0x3022, 0x3021)) {
                assertTrue("RGBA8 不能在回退时消失：$request", (attrs[color] ?: 0) >= 8)
            }
            assertEquals("窗口与无窗口面均须可用：$request", 0x0005, (attrs[0x3033] ?: 0) and 0x0005)
            assertTrue("必须声明 GLES 能力：$request", ((attrs[0x3040] ?: 0) and 0x0044) != 0)
        }
    }
}
