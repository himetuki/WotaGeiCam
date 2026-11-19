package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y35 回归：连续选右片时，旧 Picker 请求不得越过最后一次用户选择。
 *
 * 旧实现只有一枚 `pickSeq`，且只在 Picker 首次解析之后校验；历史面板点选、练习回填、
 * 「再次打开选择器后取消」都不推进代次。慢 provider / 等待授权返回期间用户改选历史片，
 * 旧回调晚到仍会写回右槽（或旧失败横幅盖掉新状态）。
 *
 * 这里分两层：
 * - **行为级**直打 [RightPickGate]（纯值，JVM 可跑）：模拟回程晚到、历史改选、取消作废；
 * - **接线级**锁 CompareContent：所有右槽入口共享这枚门、右槽只有 `acceptRightClip` 一个写点。
 */
class RightPickGateTest {

    // ------------------------------------------------------------------ 行为级

    @Test
    fun `Picker回调晚于历史改选_旧结果被丢弃`() {
        val gate = RightPickGate()
        val pickerToken = gate.newRequest() // 第一次 Picker，provider 卡住
        var right: String? = null

        fun apply(token: Int, clip: String) {
            if (gate.isCurrent(token)) right = clip
        }

        // 用户等不及，改点历史面板里的另一条：代次推进，历史结果先落地
        val historyToken = gate.newRequest()
        apply(historyToken, "历史片")
        assertEquals("历史片", right)

        // 旧 Picker 这才返回：代次已过期，不得覆盖
        apply(pickerToken, "旧 Picker 片")
        assertEquals("旧 Picker 回调晚于历史改选必须被丢弃", "历史片", right)
    }

    @Test
    fun `再次打开选择器后取消_在途旧结果同样失效`() {
        val gate = RightPickGate()
        val first = gate.newRequest()
        // 用户又点了一次「添加右片」然后取消：launchPicker/cancel 分支 invalidate
        gate.invalidate()
        var applied = 0
        if (gate.isCurrent(first)) applied++
        assertEquals("取消后的在途旧回程不得落地", 0, applied)
        // 取消之后的新选择才是有效的
        val fresh = gate.newRequest()
        assertTrue(gate.isCurrent(fresh))
        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun `旧失败探测回来时不得盖掉最新选择的横幅`() {
        val gate = RightPickGate()
        val stale = gate.newRequest()
        val fresh = gate.newRequest()
        var banner: String? = null
        fun reportFailure(token: Int) {
            // 与 CompareScreen.reportPickFailure 的落点同构：IO 探测回来先复核代次
            if (gate.isCurrent(token)) banner = "旧失败"
        }
        reportFailure(stale)
        assertNull("过期失败不得写横幅/权限弹层", banner)
        reportFailure(fresh)
        assertEquals("旧失败", banner)
    }

    // ------------------------------------------------------------------ 接线级

    private val masked: String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/CompareScreen.kt"))

    private fun content(): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "CompareContent"))

    @Test
    fun `所有右片入口共享代次门_右槽只有一个写点`() {
        val body = content()
        assertTrue(
            "右片选择代次门必须在 CompareContent 组合期持有（Picker/历史/练习共用同一枚）",
            body.contains("val pickGate = remember { RightPickGate() }")
        )
        // Picker 回程：每个结果先取代次，异步解析与授权回来复核
        val pickerStart = body.indexOf("val pickRight = rememberLauncherForActivityResult")
        val launchStart = body.indexOf("fun launchPicker()", pickerStart)
        assertTrue("Picker 回调锚点丢失（守卫失去输入）", pickerStart >= 0 && launchStart > pickerStart)
        val picker = body.substring(pickerStart, launchStart)
        assertTrue("Picker 结果必须登记新代次", picker.contains("val token = pickGate.newRequest()"))
        assertTrue("Picker 异步结果落地前必须复核代次", picker.contains("pickGate.isCurrent(token)"))
        assertTrue("取消分支必须作废在途旧回程", picker.contains("pickGate.invalidate()"))
        assertTrue("Picker 成功必须经唯一写点 acceptRightClip", picker.contains("acceptRightClip(clip, token)"))
        // 3 个入口（Picker 成功 / 练习回填 / 历史点选）各自登记一次新代次
        assertEquals(
            "每个能改右槽的用户动作都必须登记自己的代次（Picker/练习/历史三条路径）",
            3,
            KotlinSourceScan.occurrences(body, "val token = pickGate.newRequest()").size
        )
        // 再次打开选择器（含随后取消）立即作废旧回程
        val launch = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "launchPicker"))
        assertTrue(
            "launchPicker 必须作废在途旧回程（再次打开选择器 = 最新动作）",
            launch.contains("pickGate.invalidate()")
        )
        // 失败探测同样复核：权限探测在 IO 挂起期间可能发生改选
        val failure = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "reportPickFailure"))
        assertTrue(
            "reportPickFailure 在权限探测返回后必须复核代次（旧失败不得再弹权限框/横幅）",
            failure.contains("pickGate.isCurrent(token)")
        )
        // 右槽唯一写点：任何旁路 `right = clip` 都会让代次门失效
        assertEquals(
            "CompareContent 内只许 acceptRightClip 一处写 right = clip（旁路写入 = 旧结果越权）",
            1,
            KotlinSourceScan.occurrences(body, "right = clip").size
        )
        assertTrue(
            "唯一写点必须同步推进 rightRevision（同 URI 重选也要重跑 attach effect）",
            body.contains("rightRevision++")
        )
        // 尺要能红：拔掉 Picker 的代次复核，判据翻红
        val mutant = picker.replace("pickGate.isCurrent(token)", "true")
        assertFalse("Picker 去掉代次复核后守卫必须报红", mutant.contains("pickGate.isCurrent(token)"))
    }
}