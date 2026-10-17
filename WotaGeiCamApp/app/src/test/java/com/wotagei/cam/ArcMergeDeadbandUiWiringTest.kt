package com.wotagei.cam

import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.widget.deadbandLadder
import com.wotagei.cam.ui.widget.nearestLadderIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补弧「并入门槛」（差分死区 D，0..32，默认 12）三处 UI 入口的接线守卫（2026-10-17）。
 *
 * 本轮最容易犯的错：UI 里图省事写 `params.arcMergeDeadband.value = v`——参数总线的 flow 变了，
 * 但 `ArcMergeDeadband.publish` 没被调用，给 GL 引擎 / 离线修复读的**只读快照**还停在旧值，
 * 于是"滑杆拖到 30，离线修复仍按 12 跑"。所以守卫分两层：
 *
 * ① **纯逻辑往返**：[WotaParams.setArcMergeDeadband] 的钳制与发布（含默认值/越界），以及
 *    界面滑杆用的梯 [deadbandLadder]（33 档、索引空间 = 值空间）；
 * ② **源码接线**：三处入口（录制页帧率弹层 / 播放器光弧弹层 / 设置页默认值）都调 setter，
 *    且这三份 UI 源码里没有一处直接写 `arcMergeDeadband.value`。判据写成吃 `String` 的纯函数，
 *    真身与合成突变体同吃一把尺（每条配「尺子自己能红」）。
 */
class ArcMergeDeadbandUiWiringTest {

    /**
     * [WotaParams] 的 setter 会发布**进程级**快照（[ArcMergeDeadband.deadband]），
     * 没有 teardown 会污染同 JVM 后续任何读默认死区的用例；每个用例后复位出厂默认。
     */
    @After
    fun resetProcessWideDeadband() {
        ArcMergeDeadband.publish(ArcMergeDeadband.DEFAULT)
    }

    private fun params() = WotaParams(CoroutineScope(Dispatchers.Unconfined))

    // ------------------------------------------------------------------ ① 纯逻辑：滑杆值 ↔ 总线值往返

    @Test
    fun `setter往返_总线与只读快照同步发布且钳到0到32`() {
        val p = params()
        assertEquals("出厂默认必须是 12（产品口径，不许改）", 12, ArcMergeDeadband.DEFAULT)
        assertEquals("总线初值 = 出厂默认", 12, p.arcMergeDeadband.value)

        // 0..32 逐档往返：总线值与只读快照必须同步
        for (v in 0..32) {
            p.setArcMergeDeadband(v)
            assertEquals("总线值应等于滑杆值", v, p.arcMergeDeadband.value)
            assertEquals("只读快照必须同轮发布（离线/GL 读的就是它）", v, ArcMergeDeadband.deadband)
        }
        // 越界钳回（滑杆梯只给 0..32，这里是手写脏数据兜底）
        p.setArcMergeDeadband(-5)
        assertEquals("下端越界钳回 0", 0, p.arcMergeDeadband.value)
        assertEquals(0, ArcMergeDeadband.deadband)
        p.setArcMergeDeadband(999)
        assertEquals("上端越界钳回 32", 32, p.arcMergeDeadband.value)
        assertEquals(32, ArcMergeDeadband.deadband)
        // clamp 与物理域一致
        assertEquals(0, ArcMergeDeadband.clamp(-1))
        assertEquals(32, ArcMergeDeadband.clamp(33))
        assertEquals(12, ArcMergeDeadband.clamp(12))
    }

    @Test
    fun `直接改flow不发布快照_快照当场分叉`() {
        // 把"绕开 setter 的后果"钉成可执行说明：flow 变了、快照没变，消费方读到旧值
        val p = params()
        p.arcMergeDeadband.value = 30 // 反面教材（三份 UI 源码里禁止出现这一写法）
        assertEquals("flow 本身确实变了", 30, p.arcMergeDeadband.value)
        assertEquals(
            "绕过 setter 后快照仍停在默认 12——引擎/离线修复读到的就是它",
            12, ArcMergeDeadband.deadband
        )
    }

    @Test
    fun `并入门槛梯33档_索引空间等于值空间`() {
        val ladder = deadbandLadder()
        assertEquals(
            "档数 = MAX-MIN+1",
            ArcMergeDeadband.MAX - ArcMergeDeadband.MIN + 1, ladder.size
        )
        assertEquals(33, ladder.size)
        assertEquals(0f, ladder.first())
        assertEquals(32f, ladder.last())
        assertEquals(
            "默认 12 恰落在索引 12（整数值梯）",
            12, nearestLadderIndex(ArcMergeDeadband.DEFAULT.toFloat(), ladder)
        )
        // 越界值吸附到端档（滑杆永远给不出域外值）
        assertEquals(0, nearestLadderIndex(-3f, ladder))
        assertEquals(32, nearestLadderIndex(99f, ladder))
    }

    // ------------------------------------------------------------------ ② 源码接线

    /** 三份 UI 源码的写入口检查：空 = 都走 setter、都没有直接写 `.value` */
    private fun sliderWriteIssues(sources: List<Pair<String, String>>): List<String> {
        val bad = ArrayList<String>()
        for ((label, src) in sources) {
            // 词边界判据（occurrences）要两侧都是非标识符字符：这里取 "setArcMergeDeadband"，
            // 前后恰好是 `.` 与 `(`，`params.` / `settingsParams.` / `gl.` 三种前缀都能命中
            if (KotlinSourceScan.occurrences(src, "setArcMergeDeadband").isEmpty()) {
                bad.add("$label 没有通过 setArcMergeDeadband 写入（快照可能不发布）")
            }
            if (KotlinSourceScan.occurrences(src, "arcMergeDeadband.value =").isNotEmpty()) {
                bad.add("$label 直接改了 arcMergeDeadband.value（绕过 setter，快照分叉）")
            }
        }
        return bad
    }

    private fun uiSources() = listOf(
        "ui/CameraPills.kt" to codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")),
        "player/PlayerScreen.kt" to codeOnly(KotlinSourceScan.mainSourceText("player/PlayerScreen.kt")),
        "ui/SettingsScreen.kt" to codeOnly(KotlinSourceScan.mainSourceText("ui/SettingsScreen.kt"))
    )

    @Test
    fun `三处入口都走总线setter且不直接写value`() {
        val issues = sliderWriteIssues(uiSources())
        assertEquals("补弧并入门槛的写入口问题：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
        // 录制页必须把总线值实时下发给 GL 引擎（照 zebra/peaking 那一支）
        val screen = codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
        assertTrue(
            "CameraScreen 必须把并入门槛下发给当前 GL 引擎",
            KotlinSourceScan.occurrences(screen, "gl.setArcMergeDeadband").isNotEmpty()
        )
        assertTrue(
            "下发的值必须来自参数总线（observed）",
            screen.contains("params.arcMergeDeadband.observed()")
        )
    }

    @Test
    fun `尺子自己能红_绕过setter直接写value必报红`() {
        val clean = uiSources()
        assertEquals("真身必须绿", emptyList<String>(), sliderWriteIssues(clean))
        // 把录制页那处 setter 换成直接改 .value（本轮最易犯的错）⇒ 同一把尺必须抓到
        val mutated = clean.map { (label, src) ->
            label to src.replace(".setArcMergeDeadband(", ".arcMergeDeadband.value = ")
        }
        assertTrue(
            "绕过 setter 的突变必须被守卫抓到",
            sliderWriteIssues(mutated).isNotEmpty()
        )
    }
}
