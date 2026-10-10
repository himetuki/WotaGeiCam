package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停止路径「超时即只记账」的**单写者守卫**（S1-M3-01）。
 *
 * 缺陷形态（真机，类注 `stop()` 上写明）：`joinLoop()` 的有界等待（`LOOP_JOIN_MS=2500`）超时，
 * 而泵线程仍卡在换段腿里（腿预算 `ROTATE_LEG_BUDGET_MS=3000` 本就允许更慢）。旧代码在这之后
 * **继续** `closeSegmentEngine()`，控制线程与泵线程同时触碰同一批普通 `private var`
 * （`vCodec/aCodec/capCodec/muxer/target/currentSink/parts/feeder`，无 `@Volatile`、无锁）：
 * ① `parts` 是 ArrayList ⇒ 并发 add/clear 抛结构性异常或**丢一条已封段**；
 * ② 同一 `currentSink` 被 seal/discard 各走一次 ⇒ 双提交或"提交又被丢弃"；
 * ③ `muxer`/`codec` 双 stop/release，而 `MediaMuxer` 在 native 释放后被另一线程写样本 = 崩溃路径。
 *
 * 修法不是"加锁"（锁只能互斥**收尾**，挡不住泵线程正在 runSegment 里往 muxer 写样本），
 * 而是推广 `settleRotateLegAtStop` 既有的原则：**泵线程还活着 ⇒ 控制线程只记账，一个资源都不碰**。
 *
 * 本守卫钉住三件事：
 * 1. **形态**：stop/release 的每个 join 超时闸都走「只记账」出口，闸内不许出现任何拆除调用；
 * 2. **句柄**：`joinLoop` 只有确认线程退出才清 `loop`——超时就丢会让紧随其后的 `release()`
 *    拿到 null 直接判"已退出"，把超时闸绕过去重新拆一遍；
 * 3. **名单**：双触字段清单必须写在 KDoc 里（字段清单是这次修复的交付物之一）。
 */
class StopTeardownSingleWriterGuardTest {

    private val codec = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    /** `if (!joinLoop())` 的全部出现位置（停录路径上每一处都得是闸，不许有裸调 joinLoop） */
    private fun gatesIn(body: String): List<Int> =
        Regex("if \\(!joinLoop\\(\\)\\)").findAll(body).map { it.range.first }.toList()

    @Test
    fun `停录路径的每个超时闸都只记账不拆资源`() {
        val stop = bodyOf(codec, "stop")
        val gates = gatesIn(stop)
        assertTrue("停录路径必须至少有一处 joinLoop 超时闸（PREPARE/ERROR 与 START 两支各一）", gates.size >= 2)
        for (g in gates) {
            val ret = stop.indexOf("return finishWithoutTeardown()", g)
            assertTrue("超时闸分支必须走 finishWithoutTeardown（只记账）出口，偏移 $g", ret > g)
            val branch = stop.substring(g, ret)
            assertTrue("超时闸分支必须先结算换段腿（只记账 = 账要落）", branch.contains("settleRotateLegAtStop()"))
            assertFalse(
                "超时闸分支里不许 closeSegmentEngine（泵线程还活着，它才是 codec/muxer 的单写者）",
                branch.contains("closeSegmentEngine()")
            )
            assertFalse("超时闸分支里不许停 feeder", branch.contains("stopFeeder()"))
            assertFalse("超时闸分支里不许停内录 feeder", branch.contains("stopCapFeeder()"))
            assertFalse(
                "超时闸分支里不许 discardCurrent（同一枚 pending 被两路 seal = 双提交）",
                branch.contains("discardCurrent(")
            )
            assertFalse(
                "超时闸分支里不许 finish（它会 clear parts，与泵线程 sealCurrent 的 add 撞车）",
                branch.contains("finish(")
            )
            // 拆资源必须排在闸的**成功侧**：闸之后的第一处 closeSegmentEngine 要在该分支 return 之后
            assertTrue(
                "拆资源必须排在 join 成功侧（偏移 $g）",
                stop.indexOf("closeSegmentEngine()", g) > ret
            )
        }
    }

    @Test
    fun `释放路径同样不许在泵线程活着时拆资源`() {
        val rel = bodyOf(codec, "release")
        val g = rel.indexOf("if (!joinLoop())")
        assertTrue("release 也必须过超时闸（否则 stop 超时后 release 会把资源又拆一遍）", g >= 0)
        val ret = rel.indexOf("return", g)
        val branch = rel.substring(g, ret)
        assertFalse("release 的超时分支不许 closeSegmentEngine", branch.contains("closeSegmentEngine()"))
        assertFalse("release 的超时分支不许放输入面（GL 还可能挂着它）", branch.contains("s?.release()"))
        assertFalse("release 的超时分支不许清 parts", branch.contains("parts.clear()"))
        assertTrue("release 必须先过闸再拆", rel.indexOf("closeSegmentEngine()") > g)
    }

    @Test
    fun `超时不许丢掉泵线程句柄`() {
        val join = bodyOf(codec, "joinLoop")
        val aliveAt = join.indexOf("t.isAlive")
        val nullAt = join.indexOf("loop = null")
        assertTrue("锚点丢失：joinLoop 里没有活性判定", aliveAt >= 0)
        assertTrue("锚点丢失：joinLoop 里没有清句柄", nullAt >= 0)
        assertTrue(
            "loop = null 必须排在 isAlive 判定之后（超时就丢句柄 ⇒ 随后的 release 拿到 null 会" +
                "直接判「已退出」，把超时闸绕过去重新拆一遍）",
            nullAt > aliveAt
        )
    }

    @Test
    fun `超时结果不许走会清空段账的收尾`() {
        val f = bodyOf(codec, "finishWithoutTeardown")
        assertTrue("超时结果必须给明确失败码", f.contains("RecordResult.fail("))
        assertTrue("缺省失败码走换段受阻（带丢失账与环节游标，UI 侧 recordResultText 已有映射）", f.contains("rotateStallError("))
        assertFalse("不许走 finish（会 clear parts/segSampleCounts 并置 IDLE）", f.contains("finish("))
        assertFalse("不许清 parts", f.contains("parts.clear()"))
        assertFalse("不许置 IDLE（下一轮会与仍在跑的泵线程抢同一批字段）", f.contains("state = EngineState.IDLE"))
        assertTrue("必须带上丢失账（用户事后要能查「丢了多少」）", f.contains("rotateLoss.totalMs"))
    }

    @Test
    fun `泵线程必须仍是唯一的收尾入口`() {
        val pump = bodyOf(codec, "pumpLoop")
        assertTrue(
            "泵线程必须仍调 closeSegmentEngine（超时后由它自己收；把它摘掉就没人收尾了）",
            pump.contains("closeSegmentEngine()")
        )
        assertTrue("泵线程必须仍调 sealCurrent（它是封段的唯一执行者）", pump.contains("sealCurrent("))
    }

    @Test
    fun `双触字段清单必须写进KDoc`() {
        // KDoc 在 codeOnly 里被遮蔽成空格 ⇒ 这里读原文
        val raw = mainSourceText("record/CodecRecorder.kt")
        val at = raw.indexOf("private fun settleRotateLegAtStop")
        assertTrue("锚点丢失：找不到 settleRotateLegAtStop", at > 0)
        val kdoc = raw.substring(maxOf(0, at - 4000), at)
        for (f in listOf(
            "vCodec", "aCodec", "capCodec", "muxer", "target", "currentSink",
            "parts", "feeder", "capFeeder", "profile", "segSampleCounts", "segLostMs"
        )) {
            assertTrue("双触名单 KDoc 必须逐项列出 $f（清单本身就是这次修复的交付物）", kdoc.contains(f))
        }
    }
}
