package com.wotagei.cam

import com.wotagei.cam.player.ExportPerfProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * `PERF_PROBE` 闸门守卫（B15/Y30 真机取证用的探针入口）。
 *
 * 这条闸门的要求与 `FROST_PROBE`/`COMPARE_PROBE`/`HUD_RECOMP_PROBE` 一致：
 * **只有取证变体（debugHook/probe）为 true，debug/release 一律 false**，
 * 且四个变体的声明顺序固定（debug / debugHook / probe / release）。
 * 探针本体在关态必须是纯 no-op：不记账、不起线程、不调用 Android 运行时。
 */
class PerfProbeGateTest {

    private val gradleKts: String by lazy {
        repoFile("WotaGeiCamApp/app/build.gradle.kts").readText(Charsets.UTF_8)
    }

    private fun buildFieldValues(field: String): List<String> =
        Regex("buildConfigField\\(\"boolean\", \"$field\", \"(true|false)\"\\)")
            .findAll(gradleKts).map { it.groupValues[1] }.toList()

    @Test
    fun `PERF_PROBE 四变体各一枚且与霜探针逐位同形`() {
        assertEquals(
            "PERF_PROBE 只允许在 debugHook/probe 点亮（顺序 debug/debugHook/probe/release）",
            listOf("false", "true", "true", "false"),
            buildFieldValues("PERF_PROBE")
        )
        assertEquals(
            "开/关必须与同文件其它探针同形",
            buildFieldValues("FROST_PROBE"),
            buildFieldValues("PERF_PROBE")
        )
    }

    @Test
    fun `JVM 变体的导出探针默认关且关时不留痕`() {
        assertFalse("单测跑的是 debug 变体，PERF_PROBE 必须为 false", BuildConfig.PERF_PROBE)
        assertFalse(ExportPerfProbe.enabled)
        // 关态走一遍全部入口：本地单测没有 Debug/SystemClock 实现，
        // 一旦探针在关态仍调用它们，这里会直接抛 "not mocked"。
        ExportPerfProbe.onWrite(1_000_000L)
        ExportPerfProbe.onStopStart()
        ExportPerfProbe.onStopEnd()
        assertFalse(ExportPerfProbe.enabled)
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var hops = 0
        while (dir != null && hops <= 5) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
            hops++
        }
        throw AssertionError("找不到 $rel——守卫不能在没有输入的情况下算通过")
    }
}
