package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
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
 * 本守卫钉四件事：
 * 1. **形态**：stop/release 的每个 join 超时闸都走「只记账」出口，闸内不许出现任何拆除调用；
 * 2. **句柄**：`joinLoop` 只有确认线程退出才清 `loop`——超时就丢会让紧随其后的 `release()`
 *    拿到 null 直接判"已退出"，把超时闸绕过去重新拆一遍；
 * 3. **名单**：双触字段清单必须写在 KDoc 里（字段清单是这次修复的交付物之一），且与 COW 口径一致；
 * 4. **如实化（2026-10-16）**：超时结果必须带出已封段快照（`parts` 的 COW `toList()`），
 *    但**不许**读泵线程单写者的逐段账（`segSampleCounts`/`segLostMs`，宁缺勿假）。
 */
class StopTeardownSingleWriterGuardTest {

    private val codec = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    /** 桥函数（`sealedPartsResult`）住在契约文件里：快照→结果字段映射的唯一落点 */
    private val recorder = codeOnly(mainSourceText("record/Recorder.kt"))

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
        assertTrue(
            "超时结果必须经桥函数给明确失败码（空快照时它退回 RecordResult.fail，旧形态见桥的钉）",
            f.contains("sealedPartsResult(")
        )
        assertTrue("缺省失败码走换段受阻（带丢失账与环节游标，UI 侧呈现计划已有映射）", f.contains("rotateStallError("))
        assertFalse("不许走 finish（会 clear parts/segSampleCounts 并置 IDLE）", f.contains("finish("))
        assertFalse("不许清 parts", f.contains("parts.clear()"))
        assertFalse("不许置 IDLE（下一轮会与仍在跑的泵线程抢同一批字段）", f.contains("state = EngineState.IDLE"))
        assertTrue("必须带上丢失账（用户事后要能查「丢了多少」）", f.contains("rotateLoss.totalMs"))
        // 录制结果如实化（2026-10-16）：已封段只读快照必须带出；泵线程单写者的逐段账一律不读（宁缺勿假）
        assertTrue("必须取已封段只读快照（COW 的 toList，与泵线程的 add 结构性互不干扰）", f.contains("parts.toList()"))
        assertFalse("不许读泵线程单写者的逐段样本账", f.contains("segSampleCounts"))
        assertFalse("不许读泵线程单写者的逐段丢失账", f.contains("segLostMs"))
    }

    /**
     * 桥函数（`record/Recorder.kt` 的 `sealedPartsResult`）的**结构钉**：失败码构造从
     * `finishWithoutTeardown` 搬进它之后，"空快照 = 旧失败形态"与"非空 = 如实映射且不带
     * 泵线程单写者的账"必须仍成立。行为全表在 `SealedPartsResultTest`，这里只钉形状不漂。
     */
    @Test
    fun `快照到结果的桥函数不许读泵线程单写者的账`() {
        val bridge = bodyOf(recorder, "sealedPartsResult")
        assertTrue(
            "空快照必须退回旧失败形态（RecordResult.fail：无产物失败码照旧可查）",
            bridge.contains("RecordResult.fail(")
        )
        assertTrue("非空快照必须如实带分段清单", bridge.contains("parts = sealed"))
        assertTrue("非空快照必须带登记未成功数", bridge.contains("uncommitted ="))
        assertFalse(
            "桥函数不许读泵线程单写者的逐段账（快照那一刻可能正在被写，宁缺勿假）",
            bridge.contains("segVideoSamples") || bridge.contains("segLostMs")
        )
        assertFalse("桥函数不许清状态（它是控制线程在泵线程活着时调的）", bridge.contains("EngineState.IDLE"))
    }

    /**
     * 并发容器与「泵线程单写者」纪律的**一致性钉**（2026-10-16）：join 超时路径要安全带出
     * "此刻已封段"快照，`parts` 从 ArrayList 换成 CopyOnWriteArrayList；名单 KDoc 必须写明
     * 新口径（容器 + 只读快照是唯一获准的例外），且 add/clear 的写者纪律一字不动（add 只有
     * 泵线程 sealCurrent 一处；clear 只许在确认泵线程已停的收尾里）。
     */
    @Test
    fun `已封段容器必须并发安全且与双触名单一致`() {
        assertTrue(
            "parts 必须是 CopyOnWriteArrayList（ArrayList 的并发 add/clear 会丢一条已封段）",
            Regex("private val parts = CopyOnWriteArrayList<VideoSegment>\\(\\)").containsMatchIn(codec)
        )
        // 单写者：add 只有一处，且在 sealCurrent（泵线程封段唯一入口）体内
        //（锚串不带尾括号：occurrences 的词边界判定会把 `parts.add(seg` 的 `s` 当标识符续写而漏计）
        assertEquals("parts.add 必须唯一（多一处 = 多一个写者）", 1, occurrences(codec, "parts.add").size)
        assertTrue("add 必须落在 sealCurrent（泵线程封段唯一入口）里", bodyOf(codec, "sealCurrent").contains("parts.add(seg)"))
        assertEquals("parts.clear 只许出现在三处收尾", 3, occurrences(codec, "parts.clear()").size)
        for (fn in listOf("prepare", "release", "finish")) {
            assertTrue("$fn 必须清 parts（会话级容器不许跨会话残留）", bodyOf(codec, fn).contains("parts.clear()"))
        }
        // 名单 KDoc 必须与代码同口径（清单本身就是这次修复的交付物，代码换了容器而名单不换 = 假文档）
        val raw = mainSourceText("record/CodecRecorder.kt")
        val at = raw.indexOf("private fun settleRotateLegAtStop")
        assertTrue("锚点丢失：找不到 settleRotateLegAtStop", at > 0)
        val kdoc = raw.substring(maxOf(0, at - 4000), at)
        assertTrue("名单必须写清 parts 的新容器", kdoc.contains("CopyOnWriteArrayList"))
        assertTrue("名单必须写清超时路径只许 toList 快照（仍不许 add/clear）", kdoc.contains("toList()"))
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
