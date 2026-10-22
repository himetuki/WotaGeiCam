package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.enclosingOf
import com.wotagei.cam.source.KotlinSourceScan.allBodies
import com.wotagei.cam.source.KotlinSourceScan.regionsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **测"测源码的那把尺子"本身**（[KotlinSourceScan] 是 [FrostEncoderGuardTest] 全部判据的地基）。
 *
 * 为什么这座闸必须存在：遮蔽器一旦把函数体切歪（注释/字符串里的括号没处理干净、
 * 或把无体的接口声明当成吞掉半份文件的体），[FrostEncoderGuardTest] 里那些"某函数体内不含 X"
 * 的否定断言会**因为切到空文本而集体假绿**——那是本项目栽过的最坏一类假绿（测试全绿、红线没人守）。
 * 所以这里用手写的小段代码把三类坑逐条钉住，再拿真文件压一条全局不变式。
 */
class KotlinSourceScanTest {

    /** 三个 `${` 与 `"""` 嵌套写不进同一枚原始串，所以这段样例用普通串拼出来 */
    private val snippet: String = run {
        """
        |/** KDoc 里的假括号 { } 与 "引号" 与 [a, b) 都不算数 */
        |fun a(): Boolean {
        |    val inString = "串里的收尾 } 与 开 { 都不算数"
        |    val interp = "插值里 ${'$'}{run { 1 }} 是代码，要参与配平"
        |    // 行注释里的 } 与 { 也不算
        |    val c = '}'
        |    if (c == '}') { return true }
        |    return false
        |}
        |interface I {
        |    fun later(x: Int): Boolean
        |    fun next(x: Int): Int
        |}
        |fun b(): Int {
        |    return 1
        |}
        |""".trimMargin()
    }

    @Test
    fun `遮蔽文本等长且保留换行`() {
        val masked = codeOnly(snippet)
        assertEquals("遮蔽必须逐位等长，偏移才能回原文算行号", snippet.length, masked.length)
        assertEquals("换行数必须不变", snippet.count { it == '\n' }, masked.count { it == '\n' })
    }

    @Test
    fun `注释与字符串内容被抹平但代码字符留着`() {
        val masked = codeOnly(snippet)
        assertFalse("KDoc 内容没被遮蔽", masked.contains("KDoc"))
        assertFalse("字符串内容没被遮蔽", masked.contains("串里的收尾"))
        assertFalse("行注释内容没被遮蔽", masked.contains("行注释里的"))
        assertFalse("字符字面量内容没被遮蔽", masked.contains("'}'"))
        assertTrue("代码必须原样保留", masked.contains("return false"))
        // 插值里的真代码要留下来：否则"有人把 frost 调用写在串里"这种情况就查不出来
        assertTrue("插值内部的代码被一起抹掉了", masked.contains("run { 1 }"))
    }

    @Test
    fun `函数体切得准：不被注释里的括号带偏`() {
        val masked = codeOnly(snippet)
        val bodyA = bodyOf(masked, "a")
        assertTrue("a 的体必须切到最后一行 return false", bodyA.contains("return false"))
        // 字符字面量里的 `}` 被抹成空格后，那行仍是代码：`if (c ==    ) { return true }`
        assertTrue("if 里的成对花括号必须算进体内", bodyA.contains("if (c ==") && bodyA.contains("{ return true }"))
        assertFalse("体不能吞掉后面的 interface", bodyA.contains("interface"))
        assertFalse("体不能吞掉 b", bodyA.contains("return 1"))
        // 无体的接口声明必须判成"没有体"：否则它的"体"会一路吞到文件末尾
        assertTrue("抽象声明不该有体：later", regionsOf(masked, "later").isEmpty())
        assertTrue("抽象声明不该有体：next", regionsOf(masked, "next").isEmpty())
        val bodies = allBodies(masked)
        assertEquals("整个片段只有 a 与 b 两个带体的函数", setOf("a", "b"), bodies.map { it.first }.toSet())
        assertEquals("return false 的外层必须是 a", "a", enclosingOf(bodies, masked.indexOf("return false")))
        assertEquals("return 1 的外层必须是 b", "b", enclosingOf(bodies, masked.indexOf("return 1")))
    }

    @Test
    fun `真文件压一条全局不变式：遮蔽后不许再有一个汉字`() {
        // 本仓库的汉字只出现在注释与字符串里（标识符全是拉丁字母）。
        // 所以"遮蔽后仍有汉字"= 遮蔽器漏了某类写法 = 所有基于切片的守卫开始漏风。
        for (path in listOf("camera/GlRenderEngine.kt", "camera/FrostBlur.kt", "camera/FrostBlurChain.kt", "camera/Shaders.kt")) {
            val src = KotlinSourceScan.mainSourceText(path)
            val leftover = codeOnly(src).filter { it.code in 0x4E00..0x9FFF }
            assertEquals("$path 遮蔽后仍残留汉字（注释或串没抹干净）：${leftover.take(20)}", "", leftover)
            assertEquals("$path 遮蔽必须等长", src.length, codeOnly(src).length)
        }
    }
}
