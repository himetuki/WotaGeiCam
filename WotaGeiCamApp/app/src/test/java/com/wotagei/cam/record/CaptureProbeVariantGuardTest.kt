package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内录构造探针变体链（华为 Mate 60 Pro 鸿蒙容器内录被拒排查轮）的**纯函数单测 + 源码结构守卫**。
 *
 * 背景：该机型授权链全通（内录建链成功），但 createCaptureAudioRecord 构造 AudioRecord 时
 * audio policy 拒绝投影捕获注册（`UnsupportedOperationException: could not register audio policy`），
 * 且真机拒绝连 ADB——参数实验做不成就做进 app 里：baseline 失败后自动依次试变体并逐个留痕，
 * 用户复现一次内录开关，导出的诊断日志即可回答「哪个变体能活 / 全死」。守卫锁三件事：
 * - 变体表的顺序与标签（真机日志按「变体[N] <label>」对号入座，表乱一处结论就对不上）；
 * - createCaptureAudioRecord 体内必须真的含 4 个变体 label 字面量 + 逐变体 try-catch 留痕
 *   （表函数存在但循环没用它 = 探针空转的假实现）；
 * - 全失败时 lastInitFailure 必须汇报「已试 N 种构造」（提示参数不许退回无信息的一句）。
 *
 * 「尺子自己能红」：每条守卫配突变自证——删变体/调序/拆留痕，同一把尺立刻报红
 * （对全文做文本替换后重新取函数体再判，替换不生效 = 源码里本就没有被判据的结构，同样翻红）。
 */
class CaptureProbeVariantGuardTest {

    private val rel = "record/PlaybackCaptureController.kt"

    private fun masked(): String = codeOnly(mainSourceText(rel))

    /** 原文函数体切片：判**字面量**必须用原文（codeOnly 会把字符串字面量遮蔽成空格） */
    private fun rawBodyOf(fn: String): String {
        val region = regionOf(masked(), fn)
        return mainSourceText(rel).substring(region.start, region.end)
    }

    /**
     * 对全文做**依次**文本突变后重新取原文函数体（与 CaptureTrackLostGuardTest 的突变自证同一手法）。
     * 每步突变都要求真的改动了文本（替换不生效 = 判据空转，自证作废）。
     */
    private fun mutantRawBody(fn: String, vararg replacements: Pair<String, String>): String {
        var m = mainSourceText(rel)
        for ((old, new) in replacements) {
            assertTrue("突变前提：被替换片段必须存在于源码：$old", m.contains(old))
            m = m.replace(old, new)
            assertFalse("对照组：突变体必须真的改动了源码（$old）", m.contains(old))
        }
        val region = regionOf(codeOnly(m), fn)
        return m.substring(region.start, region.end)
    }

    // ------------------------------------------------------------------ 变体表（纯函数）

    @Test
    fun `探针变体表_顺序与标签逐字钉死`() {
        assertEquals(
            "变体表顺序与标签必须逐字等于四元表：真机日志按 变体[N] <label> 对号入座，" +
                "增删/调序/改名任何一处，导出件的结论就对不上",
            listOf("baseline", "default-buffer", "min-buffer", "media-only-config"),
            PlaybackCaptureController.captureVariantLabels()
        )
    }

    /** 判据本体：函数体里四个 label 字面量按表序单调出现（缺一个或乱序即假） */
    private fun labelsInOrder(rawBody: String): Boolean {
        var at = -1
        for (label in listOf("baseline", "default-buffer", "min-buffer", "media-only-config")) {
            val next = rawBody.indexOf(label, at + 1)
            if (next < 0) return false
            at = next
        }
        return true
    }

    @Test
    fun `突变自证_变体表删一项必红`() {
        assertTrue("对照组：真源码上判据必须为真（否则这条自证证明不了什么）", labelsInOrder(rawBodyOf("captureVariantLabels")))
        val mutant = mutantRawBody("captureVariantLabels", "\"min-buffer\",\n" to "")
        assertFalse("删掉 min-buffer 变体后判据必须报红", labelsInOrder(mutant))
    }

    @Test
    fun `突变自证_变体表调序必红`() {
        assertTrue("对照组：真源码上判据必须为真", labelsInOrder(rawBodyOf("captureVariantLabels")))
        // default-buffer 与 min-buffer 对调（三段替换：占位符防连环替换）
        val mutant = mutantRawBody(
            "captureVariantLabels",
            "\"default-buffer\"" to "\"__swap__\"",
            "\"min-buffer\"" to "\"default-buffer\"",
            "\"__swap__\"" to "\"min-buffer\""
        )
        assertFalse("调换 default-buffer 与 min-buffer 顺序后判据必须报红", labelsInOrder(mutant))
    }

    // ------------------------------------------------------------------ createCaptureAudioRecord 体内结构

    @Test
    fun `createCaptureAudioRecord体内_4个变体label字面量_逐变体try-catch留痕_全失败报已试`() {
        val maskedBody = flatten(bodyOf(masked(), "createCaptureAudioRecord"))
        val rawBody = rawBodyOf("createCaptureAudioRecord")
        // 1) 四个变体 label 字面量必须在体内（when 分派分支；只写表不写分支 = 探针空转）
        for (label in PlaybackCaptureController.captureVariantLabels()) {
            assertTrue("体内必须含变体 label 字面量 \"$label\"", rawBody.contains("\"$label\""))
        }
        // 每个变体的构造路径必须真的在体内（不是表里有、体内空转）
        assertTrue(
            "baseline 必须仍是 Builder + 存储的全 usage 捕获配置（历史行为原样保留为第 1 变体）",
            maskedBody.contains("setAudioPlaybackCaptureConfig(config)")
        )
        assertTrue(
            "default-buffer 变体必须不设缓冲让平台合成（日志如实记 platform-default，不冒充字节数）",
            rawBody.contains("\"default-buffer\" -> \"platform-default\"")
        )
        assertTrue(
            "min-buffer 变体必须把缓冲降到 minBufferSize（排除「缓冲区大小不被接受」这个变量）",
            maskedBody.contains("setBufferSizeInBytes(minBuf)")
        )
        assertTrue(
            "media-only-config 变体必须只重建 USAGE_MEDIA 一个 usage 的捕获配置",
            maskedBody.contains("addMatchingUsage(AudioAttributes.USAGE_MEDIA)")
        )
        // 2) 循环由变体表驱动（表是顺序唯一真源；循环里另写一份迟早与表分叉）
        assertTrue("循环必须迭代 captureVariantLabels()", maskedBody.contains("captureVariantLabels()"))
        // 3) 逐变体 try-catch 留痕：取表 → 循环 → try → catch 的结构序 + catch 恰一处（逐变体各吞）
        val tableAt = maskedBody.indexOf("captureVariantLabels()")
        val forAt = maskedBody.indexOf("for (i in labels.indices)")
        val tryAt = maskedBody.indexOf("try {")
        val catchAt = maskedBody.indexOf("catch (e: RuntimeException)")
        assertTrue(
            "结构序必须成立：取表 → for 循环 → try → catch（逐变体 try-catch，不是整体一把抓）",
            tableAt in 0 until forAt && forAt in 0 until tryAt && tryAt in 0 until catchAt
        )
        assertEquals(
            "体内只许一处逐变体 catch（多处 = 有变体绕过留痕）",
            1,
            occurrences(maskedBody, "catch (e: RuntimeException)").size
        )
        // 异常留痕必须带异常类名（病因判别的关键证据）
        assertTrue("逐变体异常留痕必须含异常类名", maskedBody.contains("e.javaClass.name"))
        // 尝试/成功/跳过三件留痕（字面量 ⇒ 原文判）
        for (trace in listOf("：尝试（", "：成功", "：跳过（会话不在）")) {
            assertTrue("逐变体留痕缺件：$trace", rawBody.contains(trace))
        }
        // 4) 每变体 build 后的 state 检查保留：未初始化视为该变体失败，release 后继续下一个
        assertTrue(
            "每变体必须检查 STATE_INITIALIZED（构造不抛≠能用）",
            maskedBody.contains("rec.state != AudioRecord.STATE_INITIALIZED") && maskedBody.contains("rec.release()")
        )
        // 5) 全失败：lastInitFailure 必须汇报「已试 N 种构造」（代码与字面量同串判 ⇒ 原文）
        assertTrue(
            "全失败时 lastInitFailure 必须含「已试」（用户提示与导出件要能看出试过几种构造）",
            rawBody.contains("lastInitFailure = \"系统拒绝创建内录采集器（已试")
        )
    }

    @Test
    fun `突变自证_体内删min-buffer变体分支必红`() {
        // 把 min-buffer 字面量整体改名（体内 when 分派与 buf 选择一起消失）
        val mutant = mutantRawBody("createCaptureAudioRecord", "\"min-buffer\"" to "\"smallest-buffer\"")
        assertFalse(
            "拆掉 min-buffer 分支后体内字面量判据必须报红",
            mutant.contains("\"min-buffer\"")
        )
    }

    @Test
    fun `突变自证_拆逐变体catch必红`() {
        val mutant = mutantRawBody(
            "createCaptureAudioRecord",
            "catch (e: RuntimeException)" to "catch (mutated: RuntimeException)"
        )
        assertFalse(
            "拆掉逐变体 catch 锚点后结构判据必须报红",
            mutant.contains("catch (e: RuntimeException)")
        )
    }

    @Test
    fun `突变自证_拆已试汇总必红`() {
        val mutant = mutantRawBody("createCaptureAudioRecord", "已试" to "已测")
        assertFalse(
            "拆掉「已试」汇总后判据必须报红",
            mutant.contains("lastInitFailure = \"系统拒绝创建内录采集器（已试")
        )
    }
}
