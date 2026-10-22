package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放页路由对「已消失 mediaId」的空态守卫。
 *
 * 契约：片被外部删掉/回收站清掉后 `repo.clipById(id)` 会**发射一次 null**（专用管道直接 emit
 * 可空结果，不经吞 null 的 gated），路由版 PlayerScreen 的"查过且没有"空态由此可达。
 * 历史缺陷：clipById 曾复用 gated——builder 返回 null 时零发射，null 永远到不了消费侧，
 * PlayerScreen 停在 CircularProgressIndicator 无出口。钉住两件事：
 * - clipById 体内必须有直接 `emit(queryById(id)`（零发射/`?.let { emit` 吞 null 形态都算回退）；
 * - 路由重载里必须存在「查过且没有」的空态分支（player_clip_missing 文案），且不许自动返回、
 *   不许拦截返回键（自动返回/自动重试会吃掉用户对"片没了"的感知，属产品裁决）。
 */
class PlayerClipMissingGuardTest {

    /** 路由版 PlayerScreen 是重载之一，按体内调用 clipById 点名 */
    private fun routeBody(src: String): String {
        val hits = KotlinSourceScan.regionsOf(src, "PlayerScreen")
            .map { src.substring(it.start, it.end) }
            .filter { it.contains("clipById(") }
        assertTrue("路由版 PlayerScreen（带 clipById 的那份）必须能唯一定位（守卫前提）", hits.size == 1)
        return hits[0]
    }

    @Test
    fun `clipById必须直接emit可空结果_零发射或吞null形态必须报红`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaRepo.kt"))
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "clipById"))
        assertTrue(
            "clipById 体内必须有直接 emit(queryById(id)——" +
                "查无此片要作为一次 null 下发；复用 gated（builder null 零发射）会让播放页空态不可达",
            body.contains("emit(queryById(id)")
        )
        assertFalse(
            "clipById 不得写 ?.let { emit 吞 null 形态（null 是有效结果，必须直接下发）",
            body.contains("?.let { emit")
        )
        // 突变自证：emit 塞回 ?.let 内侧（gated 的吞 null 形态），同一把尺必须报红
        val mutant = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(
                    KotlinSourceScan.mainSourceText("media/MediaRepo.kt")
                        .replace("emit(queryById(id)?.let { it.copy(", "queryById(id)?.let { emit(it.copy(")
                ),
                "clipById"
            )
        )
        assertFalse("退回吞 null 形态后守卫必须报红", mutant.contains("emit(queryById(id)"))
        assertTrue("突变体必须真的换成了吞 null 形态（替换不中 = 尺子没咬合）", mutant.contains("?.let { emit("))
    }

    @Test
    fun `查过且没有必须显示空态文案_不许停在转圈`() {
        // 空态可达的前提链：clipById 必须真的把 null 下发到消费侧（吞 null 回退 = 下面的空态分支
        // 恒不可达，页面停在转圈）——完整专测见 `clipById必须直接emit可空结果_零发射或吞null形态必须报红`
        assertTrue(
            "空态可达前提：clipById 必须直接 emit(queryById(id) 下发可空结果",
            KotlinSourceScan.bodyOf(
                KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("media/MediaRepo.kt")),
                "clipById"
            ).contains("emit(queryById(id)")
        )
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))
        val body = KotlinSourceScan.flatten(routeBody(src))
        val pendingAt = body.indexOf("ClipQueryPending")
        val missingAt = body.indexOf("R.string.player_clip_missing")
        assertTrue("必须先有「还在查」哨兵分支（守卫失去输入，不许算通过）", pendingAt >= 0)
        assertTrue(
            "查过且没有必须显示 player_clip_missing 空态（停在转圈无出口）",
            missingAt > pendingAt
        )
        // 文案资源必须真的登记进 strings_player.xml（丢了编译会红，但显式锁住防资源被挪走后守卫空转）
        assertTrue(
            "strings_player.xml 必须登记 player_clip_missing",
            stringsXml().readText(Charsets.UTF_8).contains("player_clip_missing")
        )
    }

    @Test
    fun `空态不许自动返回也不许拦截返回键`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt"))
        val body = KotlinSourceScan.flatten(routeBody(src))
        assertTrue(
            "空态不许自动返回（onBack 只许作为参数透传给内层）",
            !body.contains("onBack()")
        )
        assertTrue(
            "空态分支不许加 BackHandler（返回键保留）",
            !body.contains("BackHandler")
        )
    }

    @Test
    fun `突变自证_删空态文案必报红`() {
        val raw = KotlinSourceScan.mainSourceText("player/PlayerScreen.kt")
        val good = KotlinSourceScan.codeOnly(raw)
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", routeBody(good).contains("R.string.player_clip_missing"))
        // 突变：文案引用换掉（模拟空态分支被删/被改回转圈）——文本级突变不编译，只验尺子会红
        val mutant = KotlinSourceScan.codeOnly(raw.replace("R.string.player_clip_missing", "R.string.ok"))
        assertTrue("删空态文案后尺子必须红", !routeBody(mutant).contains("R.string.player_clip_missing"))
    }

    /** 从 PlayerScreen.kt 所在的 src/main/java 向上取同源的 res/values/strings_player.xml（文案落点，不碰共享 strings.xml） */
    private fun stringsXml(): File {
        var dir: File? = KotlinSourceScan.mainSourceFile("player/PlayerScreen.kt").parentFile
        while (dir != null && dir.name != "java") dir = dir.parentFile
        assertTrue("定位不到 src/main/java 目录（守卫失去输入）", dir != null)
        return File(File(dir!!.parentFile, "res"), "values/strings_player.xml")
    }
}
