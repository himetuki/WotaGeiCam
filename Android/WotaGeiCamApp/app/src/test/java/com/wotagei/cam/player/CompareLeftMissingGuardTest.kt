package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y36 回归：对比页左片查无结果时必须呈现缺失态，而不是永久转圈。
 *
 * `MediaRepo.clipById` 对不存在的 ID 会发射一次 null（单播放页已有守卫
 * [PlayerClipMissingGuardTest]）。对比页旧实现把 `collectState` 的 initial 设为 null，
 * 「还没查到」与「查过且没有」共值，null 分支恒显示 CircularProgressIndicator，用户既看不到
 * 原因也没有返回入口。这里钉三件事：
 * 1. 对比页用独立哨兵区分「加载中」与「缺失」；
 * 2. 缺失分支必须显示 player_clip_missing 文案并保留 onBack 返回；
 * 3. 正常结果才进 CompareContent（不拿 null 进内容页）。
 */
class CompareLeftMissingGuardTest {

    private val masked: String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("player/CompareScreen.kt"))

    private fun routeBody(): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked, "CompareScreen"))

    @Test
    fun `查无左片必须显示缺失态并保留返回_不许停在转圈`() {
        val body = routeBody()
        assertTrue(
            "初始态必须是独立哨兵（CompareLeftQueryPending），不能与查无结果的 null 共值",
            body.contains("collectState(CompareLeftQueryPending)")
        )
        val pendingAt = body.indexOf("state === CompareLeftQueryPending")
        val missingAt = body.indexOf("R.string.player_clip_missing")
        val contentAt = body.indexOf("CompareContent(")
        assertTrue("必须先有「还在查」的哨兵分支（守卫失去输入，不许算通过）", pendingAt >= 0)
        assertTrue(
            "查过且没有（收到 null）必须显示 player_clip_missing 缺失态，排在哨兵之后",
            missingAt > pendingAt
        )
        assertTrue(
            "缺失态必须给返回入口（不许把用户困在只有转圈的页面）",
            body.contains("onClick = onBack")
        )
        assertTrue(
            "只有拿到真实 VideoClip 才进 CompareContent（null 不得进内容页）",
            contentAt > missingAt
        )
        // 尺要能红：删掉缺失文案（退回只剩转圈）后判据翻红
        val mutant = body.replace("R.string.player_clip_missing", "R.string.ok")
        assertFalse("删缺失文案后守卫必须报红", mutant.contains("R.string.player_clip_missing"))
    }

    @Test
    fun `突变_退回null初始值形态必红`() {
        val good = routeBody()
        assertTrue("良品必须先真的绿", good.contains("collectState(CompareLeftQueryPending)"))
        val mutant = good.replace("collectState(CompareLeftQueryPending)", "collectState(null)")
        assertFalse(
            "退回 initial=null（加载与缺失不可区分）后守卫必须报红",
            mutant.contains("collectState(CompareLeftQueryPending)")
        )
    }
}