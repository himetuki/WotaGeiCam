package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「斑马纹 / 峰值对焦绝不进成片」的源码级守卫**（docs/plan/25 §5.1，双槽模型 B2）。
 *
 * 存在的理由与 [FrostEncoderGuardTest] 同一条：`drawEncoderPass` 的产物直接进成片，
 * 而"某天有人在编码 pass 里顺手用上屏槽的 program"这件事在 JVM 里只有源码级手段挡得住
 * （编译过、真机录一段才看得见）。25 号计划的裁决是：斑马/峰值是**监看叠加层**，
 * 任何情况下只上屏；成片只由「曲线 / Glow / 降噪 / 相机」决定。
 *
 * # 判据的写法（照抄霜那套）
 * - **正向锚点**先行：每条否定断言前先证明"切片切到了真身"（体内必须有
 *   `drawPass(encoderPass = true)` 之类的实现标记），否则切片切空会让所有否定断言一起假绿；
 * - **突变自证**：把 `movieProgramKey` 的 `FrameEffect.NONE` 改成 `FrameEffect.ZEBRA`，
 *   守卫必须能红（尺子自己先被量一遍）。
 * - 判定吃 `codeOnly` 之后的文本：注释与字符串被遮蔽，"注释掉实现"这种假绿当场暴露。
 */
class MonitorEffectEncoderGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }

    /** 遮蔽后的引擎源码：注释与字面量内容不再参与结构判定 */
    private val engine by lazy { KotlinSourceScan.codeOnly(engineSrc) }

    private val keySrc by lazy { KotlinSourceScan.mainSourceText("camera/ProgramKey.kt") }

    /** 遮蔽后的键源码（ProgramKey.kt 里 KDoc 大量写到 `slot`，判签名前必须遮蔽） */
    private val keyCode by lazy { KotlinSourceScan.codeOnly(keySrc) }

    /** 编码支路体内一律不许出现的监看叠加符号 */
    private val monitorSymbols = listOf(
        "displaySlot",
        "wantEffect",
        "FrameEffect.ZEBRA",
        "FrameEffect.PEAKING",
        "stripeTextureId"
    )

    // ------------------------------------------------------------------ 编码支路（正向锚点 + 否定）

    /**
     * 两条发帧进成片的路（直通 [drawEncoderPass] 与 MEND 的 [drawEncoderMended]）各自切片判定。
     * 抽成纯函数是为了给突变自证留入口（真身与突变体同吃一把尺）。
     */
    private fun encoderIssues(masked: String): List<String> {
        val bad = ArrayList<String>()
        for (fn in listOf("drawEncoderPass", "drawEncoderMended")) {
            val body = runCatching { KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, fn)) }.getOrNull()
            if (body == null) {
                bad.add("$fn 的函数体定位失败（被改名/改成表达式体？）")
                continue
            }
            if (!body.contains("drawPass(encoderPass = true)")) {
                bad.add("$fn 缺少正向锚点 drawPass(encoderPass = true)——切片八成切空了，否定断言不算数")
            }
            for (needle in monitorSymbols) {
                if (body.contains(needle)) {
                    bad.add("$fn 里出现了 $needle —— 监看叠加会进成片（红线：只上屏）")
                }
            }
        }
        return bad
    }

    @Test
    fun `编码两支路都不认识监看叠加`() {
        assertEquals("编码支路的红线被破了：\n${encoderIssues(engine).joinToString("\n")}", emptyList<String>(), encoderIssues(engine))
    }

    @Test
    fun `尺子自己能红_编码pass里塞进上屏槽必报红`() {
        assertEquals("真身必须绿", emptyList<String>(), encoderIssues(engine))
        // 突变：让 drawEncoderPass 引用上屏槽（正是"拿 display 的 program 补成片"那一步）
        val mutated = engine.replace(
            "private fun drawEncoderPass() {",
            "private fun drawEncoderPass() { val leak = displaySlot.program"
        )
        assertTrue("编码 pass 引用 displaySlot 的突变必须被守卫抓到", encoderIssues(mutated).isNotEmpty())
        // 突变：让 MEND 支路「顺手」带一个斑马常量
        val mutatedMend = engine.replace(
            "private fun drawEncoderMended(keep: Boolean): Boolean {",
            "private fun drawEncoderMended(keep: Boolean): Boolean { val e = FrameEffect.ZEBRA"
        )
        assertTrue("MEND 支路引用 ZEBRA 的突变必须被守卫抓到", encoderIssues(mutatedMend).isNotEmpty())
    }

    // ------------------------------------------------------------------ 成片键的唯一真源

    /**
     * [movieProgramKey] 的签名 + 体：签名不许有 effect 形参（防"顺手把 wantEffect 传进来"），
     * 体内必须出现 `FrameEffect.NONE`（成片键的 effect 恒 NONE）。
     */
    private fun movieKeyIssues(maskedKey: String): List<String> {
        val bad = ArrayList<String>()
        val keyFunAt = maskedKey.indexOf("fun movieProgramKey")
        if (keyFunAt < 0) return listOf("找不到 fun movieProgramKey 的声明")
        val braceAt = maskedKey.indexOf('{', keyFunAt)
        val decl = maskedKey.substring(keyFunAt, if (braceAt < 0) maskedKey.length else braceAt)
        for (p in listOf("curve", "denoise", "glow")) {
            if (!decl.contains(p)) bad.add("movieProgramKey 签名缺形参 $p（四维键要三维入参 + effect 恒 NONE）")
        }
        if (decl.contains("effect")) {
            bad.add("movieProgramKey 的签名里出现了 effect —— 成片键不许带监看维度，effect 恒 NONE 是唯一真源")
        }
        val body = runCatching { KotlinSourceScan.bodyOf(maskedKey, "movieProgramKey") }
            .getOrElse { return bad + "movieProgramKey 的函数体定位失败：${it.message}" }
        if (!body.contains("FrameEffect.NONE")) {
            bad.add("movieProgramKey 的体内必须出现 FrameEffect.NONE（成片键的 effect 恒 NONE）")
        }
        return bad
    }

    @Test
    fun `成片键的effect恒NONE且签名没有effect形参`() {
        assertEquals(
            "movieProgramKey 的红线被破了：\n${movieKeyIssues(keyCode).joinToString("\n")}",
            emptyList<String>(),
            movieKeyIssues(keyCode)
        )
    }

    @Test
    fun `尺子自己能红_movie键的effect被改成ZEBRA必报红`() {
        assertEquals("真身必须绿", emptyList<String>(), movieKeyIssues(keyCode))
        val body = KotlinSourceScan.bodyOf(keyCode, "movieProgramKey")
        val mutatedBody = body.replace("FrameEffect.NONE", "FrameEffect.ZEBRA")
        assertTrue("突变点没找到（movieProgramKey 体内没有 FrameEffect.NONE？）", mutatedBody != body)
        assertTrue(
            "把 movieProgramKey 的 NONE 改成 ZEBRA 必须被守卫抓到",
            movieKeyIssues(keyCode.replace(body, mutatedBody)).isNotEmpty()
        )
        // 另一条突变：签名里塞回 effect（成片键重新认识监看维度）
        val mutatedDecl = keyCode.replace(
            "fun movieProgramKey(curve: Boolean, denoise: Int, glow: Boolean)",
            "fun movieProgramKey(effect: FrameEffect, curve: Boolean, denoise: Int, glow: Boolean)"
        )
        assertTrue("签名加回 effect 形参的突变必须被守卫抓到", movieKeyIssues(mutatedDecl).isNotEmpty())
    }

    // ------------------------------------------------------------------ 两槽各认各的键

    @Test
    fun `drawPass按布尔选槽且只认两个槽`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "drawPass"))
        // 正向锚点：两条输出路的分叉点就是这一行；它一旦变成"读某个全局 program"就再分不开了
        assertTrue(
            "drawPass 必须按 encoderPass 选槽：if (encoderPass) movieSlot else displaySlot",
            body.contains("if (encoderPass) movieSlot else displaySlot")
        )
        // 编码 pass 的贴图坐标/输出面口径不许跟着动（FrostEncoderGuardTest 也钉着同一行）
        assertTrue("两路选纹理坐标那行必须还在", body.contains("if (encoderPass) encoderTexCoordBuffer else displayTexCoordBuffer"))
    }

    @Test
    fun `切监看与调参的入口不许碰成片槽`() {
        // 录制中可调监看（B4）的前提：这三个入口只改上屏意图，一个字节都不许碰 movie 槽——
        // 碰了就等于"切一次监看换一次成片 program"。调参（阈值/密度/强度/颜色）走 uniform，更不该碰槽
        for (fn in listOf("setEffect", "setZebraParameters", "setPeakingParameters")) {
            val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, fn))
            assertTrue("$fn 的函数体切空了（没有写入意图？）", body.isNotEmpty())
            assertFalse(
                "$fn 不许出现 movieSlot —— 监看操作只影响上屏槽，绝不许动成片 program",
                body.contains("movieSlot")
            )
            assertFalse("$fn 不许出现 movieProgramKey", body.contains("movieProgramKey"))
        }
    }

    @Test
    fun `program缓存按四维键而不是按槽`() {
        val issues = cacheKeyIssues(engine)
        assertEquals("缓存键的口径被破了：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
        // 突变：键里塞回 slot ⇒ 两槽各编一份（effect=NONE 时无法共用）
        val mutated = engine.replace(
            "LinkedHashMap<ProgramKey, GlProgram>",
            "LinkedHashMap<SlottedProgramKey, GlProgram>"
        )
        assertTrue("缓存键加 slot 维度的突变必须被守卫抓到", cacheKeyIssues(mutated).isNotEmpty())
    }

    /**
     * `programCache` 的声明必须是 `LinkedHashMap<ProgramKey, GlProgram>`：
     * `ProgramKey` 本身四维（`ProgramKeyTest` 钉着），所以这条同时挡住"把 slot 塞进缓存键"。
     */
    private fun cacheKeyIssues(masked: String): List<String> {
        val bad = ArrayList<String>()
        val at = masked.indexOf("val programCache")
        if (at < 0) return listOf("找不到 programCache 的声明（会话缓存被删了？）")
        val lineEnd = masked.indexOf('\n', at).let { if (it < 0) masked.length else it }
        val decl = masked.substring(at, lineEnd)
        if (!decl.contains("LinkedHashMap<ProgramKey, GlProgram>")) {
            bad.add("programCache 必须声明成 LinkedHashMap<ProgramKey, GlProgram>（四维键、不含 slot）：$decl")
        }
        if (decl.contains("slot", ignoreCase = true)) {
            bad.add("programCache 的键类型里出现了 slot —— slot 是键的作用域不是维度")
        }
        return bad
    }

    @Test
    fun `成片槽与上屏槽各认各的键函数`() {
        val movie = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "syncMovieSlot"))
        val display = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "syncDisplaySlot"))
        assertTrue("syncMovieSlot 必须从 movieProgramKey 取键", movie.contains("movieProgramKey("))
        assertFalse(
            "syncMovieSlot 不许用 displayProgramKey（成片槽的 effect 恒 NONE，不是「用户选什么就什么」）",
            movie.contains("displayProgramKey(")
        )
        assertTrue("syncDisplaySlot 必须从 displayProgramKey 取键", display.contains("displayProgramKey("))
    }

    @Test
    fun `失败备忘按ProgramKey记而不是全局布尔`() {
        val build = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, "buildProgram"))
        assertTrue("buildProgram 必须查/写 failedKeys（同一坏键不逐帧重试）", build.contains("failedKeys"))
        // 单槽时代的三个全局布尔必须彻底消失：它们会让 display 的坏效果连坐成片的曲线/降噪
        for (gone in listOf("curveFailed", "blacklistedEffect", "passThroughFailed")) {
            assertEquals("全局失败布尔 $gone 必须已被按 ProgramKey 的 failedKeys 取代", 0, KotlinSourceScan.occurrences(engine, gone).size)
        }
        // 会话缓存必须按四维键：键里带 slot 会让两槽各编一份（effect=NONE 时无法共用）
        val keyFields = ProgramKeyFields(keyCode)
        assertFalse("缓存键里不许出现 slot", keyFields.isSlotKeyed)
    }

    // ------------------------------------------------------------------ ComposeLayer 隔离

    @Test
    fun `绘制路径不引用ComposeLayer`() {
        // 站位线/参考线/水平仪是 Compose Canvas（结构上进不了编码器）；搬进 GL pass 即失去这层保证
        for (fn in listOf("drawPass", "drawEncoderPass")) {
            val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, fn))
            for (layer in listOf("MarkerLineOverlay", "RefLineOverlay", "AttitudeCard")) {
                assertFalse("$fn 不许引用 ComposeLayer 的 $layer", body.contains(layer))
            }
        }
    }

    /** 键的字段形状（判"四维、不含 slot"）：只读源码里的 data class 主构造参数名 */
    private class ProgramKeyFields(maskedKey: String) {
        val names: List<String>

        init {
            val at = maskedKey.indexOf("data class ProgramKey")
            val open = if (at < 0) -1 else maskedKey.indexOf('(', at)
            val close = if (open < 0) -1 else maskedKey.indexOf(')', open)
            names = if (open < 0 || close < 0) {
                emptyList()
            } else {
                maskedKey.substring(open + 1, close)
                    .split(',')
                    .map { it.substringBefore(':').trim() }
                    .filter { it.isNotEmpty() }
            }
        }

        val isSlotKeyed: Boolean get() = names.any { it.contains("slot", ignoreCase = true) }
    }
}
