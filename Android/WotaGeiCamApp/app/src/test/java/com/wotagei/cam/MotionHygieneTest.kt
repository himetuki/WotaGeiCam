package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #81 第 1/2/3/4 条的**源码级守卫**（docs/plan/14 §一：定版「只动画 alpha 与 scale，禁动画布局属性
 * 与颜色」「时长用常量」「统一 S 型缓动」「支持系统减少动效」）。
 *
 * 为什么用源码扫描而不是行为用例：这四条是"代码里不许出现某类调用"的禁令，Compose 的动画
 * 在 JVM 单测里跑不起来（无 Robolectric），而行为等价类（比如"把 animateColorAsState 换成
 * 即时取值"）在 JVM 里根本不存在可断言的输出——唯一能机器判定的形态就是源码本身。
 * 判定全部作用在 [codeOnly] 遮蔽后的文本上：注释里提到禁用 API 的名字（迁移记录、KDoc）
 * 一律不算违规，否则这批迁移自己的注释就能把守卫打红。
 *
 * **测不到**的东西照实写清楚：真机上颜色硬切/即时直达的观感是否可接受（#81 验收要真机截图）、
 * `enter/interact/exit` 三档词汇表有没有被调用点真正用起来（第 6 条编排批还没做）。
 */
class MotionHygieneTest {

    /** 相对路径 → 词法遮蔽后的源码文本（只留代码字符，注释/字符串内容已抹平）。
     *  锚是 `ui/anim/Motion.kt`：往上 **3** 层才是 `com/wotagei/cam/`（anim→ui→cam），少一层就只扫了 ui/ 子树，
     *  守卫会假绿——这条坑就用例红过一次。 */
    private fun maskedSources(): List<Pair<String, String>> {
        val motionFile = KotlinSourceScan.mainSourceFile("ui/anim/Motion.kt")
        val camRoot = motionFile.parentFile?.parentFile?.parentFile
            ?: throw AssertionError("从 $motionFile 上溯不到 com/wotagei/cam 源码根")
        return camRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(camRoot).invariantSeparatorsPath to codeOnly(it.readText(Charsets.UTF_8)) }
            .toList()
    }

    /** 违规收集器：`hits(text, tokens)` 本身也被 [尺子自己能红] 钉住，防"守卫被删空照样绿" */
    private fun hits(text: String, tokens: List<String>): List<String> =
        tokens.filter { text.contains(it) }

    /** #81 第 1 条禁的 API：颜色动画与布局尺寸动画（alpha/scale 的 animateFloatAsState 不在禁列） */
    private val bannedApis = listOf(
        "animateColorAsState",
        "animateDpAsState",
        "animateIntAsState",
        "animateValueAsState",
        "animateRectAsState",
        "animateSizeAsState",
        "expandIn(",
        "shrinkOut("
    )

    @Test
    fun `颜色与布局尺寸动画已清零`() {
        val bad = maskedSources().flatMap { (path, text) ->
            hits(text, bannedApis).map { "$path 含禁用动画 API: $it" }
        }
        assertEquals("发现违禁调用（#81 第 1 条定版：只动画 alpha 与 scale）：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    @Test
    fun `时长常量只在Motion一处`() {
        // #81 第 2 条：所有时长走常量（MotionSpec / WotaMotion）⇒ `tween(` 直调只许出现在
        // ui/anim/Motion.kt 里；谁在别处 tween(240) 就是新造了一个裸时长
        val bad = maskedSources().mapNotNull { (path, text) ->
            val hit = text.contains("tween(")
            if (hit && path != "ui/anim/Motion.kt") "$path 出现 tween( 直调（时长必须走 MotionSpec）" else null
        }
        assertEquals("时长字面量逃出了 Motion.kt：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    @Test
    fun `统一缓动与减少动效闸门在场`() {
        val motion = codeOnly(KotlinSourceScan.mainSourceText("ui/anim/Motion.kt"))
        assertTrue(
            "缓动必须引用唯一的 WotaEasing（#81 第 3 条：一处定义），不许各写各的",
            motion.contains("WotaEasing")
        )
        assertFalse(
            "旧缓动 LinearOutSlowInEasing 必须已换成统一 S 型曲线",
            motion.contains("LinearOutSlowInEasing")
        )
        assertTrue(
            "减少动效必须读系统设置（#81 第 4 条）：ANIMATOR_DURATION_SCALE",
            motion.contains("ANIMATOR_DURATION_SCALE")
        )
        assertTrue(
            "减少动效必须读系统设置（#81 第 4 条）：TRANSITION_ANIMATION_SCALE",
            motion.contains("TRANSITION_ANIMATION_SCALE")
        )
    }

    @Test
    fun `尺子自己能红`() {
        // 突变自检：把禁令判据一个一个打回去，必须至少红一条——否则删空 hits/bannedApis 也全绿
        val dirty = """
            |fun f(selected: Boolean, c: Color) {
            |    val fill by animateColorAsState(if (selected) c else Color.Transparent)
            |    AnimatedVisibility(v, enter = expandIn(tween(240)))
            |}
        """.trimMargin()
        assertEquals(2, hits(codeOnly(dirty), bannedApis).size)
        // 注释里提到禁用 API 不算违规（遮蔽器负责）：迁移记录自己不能把守卫打红
        val commented = """
            |/** 原来这里走 animateColorAsState 渐变，定版后已移除 */
            |fun g() = 1
        """.trimMargin()
        assertEquals(0, hits(codeOnly(commented), bannedApis).size)
        // tween 直调的放行范围只有 Motion.kt 一个文件
        assertTrue(codeOnly("fun h() = tween(300)").contains("tween("))
    }
}
