package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「问题反馈」入口（`WotaGeiCam issues` 腾讯文档收集表，2026-11-23 用户指令）的源码守卫：
 *
 * 给不用 GitHub 的用户一条提交反馈的路——设置页里「关于（检查更新）」与「诊断日志」之间
 * 挂一个反馈区块，点击只把 https 链接交给系统浏览器。钉三件事：
 * 1. 链接字面量必须是腾讯文档收集表原链（换链/删链直接红）；
 * 2. 打开方式必须是 `ACTION_VIEW` 交给系统浏览器——本应用自身不发起网络请求，
 *    「网络只允许出现在 update/ 包」红线（UpdateNetworkGuardTest / DiagGuardTest）不受影响；
 * 3. 入口必须活在 [BlockFeedback] 体内且被 [BlockHost] 派发（搬丢/搬错位置红）。
 */
class FeedbackFormEntryGuardTest {

    private val raw: String by lazy { KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt") }

    /** 遮蔽版（注释与字面量内容清空）：只用于结构性断言 */
    private val masked: String by lazy { codeOnly(raw) }

    @Test
    fun `链接字面量是腾讯文档收集表原链`() {
        // 字面量断言必须打在原文上：codeOnly 会把字符串内容一并遮蔽
        assertTrue(
            "反馈表单链接被改动——改链须同步本守卫与三端口径",
            raw.contains("\"https://docs.qq.com/form/page/DT2ZCckViZHJEdmh5\"")
        )
    }

    @Test
    fun `打开方式是ACTION_VIEW交给系统浏览器`() {
        val body = bodyOf(masked, "BlockFeedback")
        assertTrue(
            "必须用 ACTION_VIEW + FEEDBACK_FORM_URL 打开（不引网络依赖）",
            body.contains("Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_FORM_URL))")
        )
        // 失败兜底：打不开时行下要有如实提示，不能静默吞掉
        assertTrue("打开失败必须显示 set_feedback_open_failed 提示", body.contains("R.string.set_feedback_open_failed"))
    }

    @Test
    fun `入口活在自己区块体内且被派发`() {
        val body = bodyOf(masked, "BlockFeedback")
        assertTrue("区块标题/行文案缺 set_group_feedback / set_feedback_open", 
            body.contains("R.string.set_group_feedback") && body.contains("R.string.set_feedback_open"))
        assertEquals("BlockHost 必须恰好派发一次 FEEDBACK 区块", 1,
            com.wotagei.cam.source.KotlinSourceScan.occurrences(masked, "SettingsBlock.FEEDBACK -> BlockFeedback()").size)
    }
}
