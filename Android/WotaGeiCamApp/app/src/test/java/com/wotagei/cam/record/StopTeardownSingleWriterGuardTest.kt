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

    /** 独立发布路：发布线程的迟到交付窄通道也住在这里（不捕获 recorder） */
    private val sealedPub = codeOnly(mainSourceText("record/SealedPublication.kt"))

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
            "超时结果必须经桥函数交回（空快照也要带身份，见桥的钉）",
            f.contains("sealedPartsResult(")
        )
        assertFalse(
            "临时快照没有真实错误时不许伪造 ROTATE_STALL（pending 不是失败）",
            f.contains("rotateStallError(")
        )
        assertTrue("临时快照必须显式标 terminal=false", f.contains("terminal = false"))
        assertTrue("临时快照必须报未落地发布/当前段数", f.contains("pendingPublications = pending"))
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
     * addPart 一处——泵线程同步封段与发布线程迟到补发共用、按段序去重；clear 只许在确认泵线程
     * 已停的收尾里）。
     */
    @Test
    fun `已封段容器必须并发安全且与双触名单一致`() {
        assertTrue(
            "parts 必须是 CopyOnWriteArrayList（ArrayList 的并发 add/clear 会丢一条已封段）",
            Regex("private val parts = CopyOnWriteArrayList<VideoSegment>\\(\\)").containsMatchIn(codec)
        )
        // 单写者：add 只有一处，且在 addPart（R06 起它是泵线程封段与发布线程迟到补发的唯一写口）
        //（锚串不带尾括号：occurrences 的词边界判定会把 `parts.add(seg` 的 `s` 当标识符续写而漏计）
        assertEquals("parts.add 必须唯一（多一处 = 多一个写者）", 1, occurrences(codec, "parts.add").size)
        assertTrue(
            "唯一写口必须落在 addPart（同步封段与迟到补发共用）",
            bodyOf(codec, "addPart").contains("parts.add(seg)")
        )
        assertTrue("泵线程封段必须经 addPart 落清单", bodyOf(codec, "sealCurrent").contains("addPart("))
        // R06：迟到段不再由发布线程写回 parts（那会钉住 recorder），而是经 SessionRelay 交付会话；
        // 收尾时由 mergedParts 把发布台账（含迟到）并进结果视图。
        assertTrue(
            "迟到发布必须经 SessionRelay 窄通道交付（不捕获 recorder）",
            bodyOf(sealedPub, "onLate").contains("RecorderDelivery.LatePart(")
        )
        val mergedAt = codec.indexOf("private fun mergedParts")
        assertTrue("锚点丢失：找不到 mergedParts", mergedAt > 0)
        assertTrue(
            "收尾视图必须并上发布台账（同步 + 迟到）",
            codec.substring(mergedAt, minOf(codec.length, mergedAt + 200))
                .contains("sealedPublisher.publishedSegments()")
        )
        assertTrue(
            "同一段不许写两遍（发布台账与同步路径是同一结果的两份引用）",
            bodyOf(codec, "addPart").contains("parts.any { it.partIndex == seg.partIndex }")
        )
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

    /**
     * R07：join 超时之后，**收尾责任必须真的移交给唯一一方**——只记账返回而没有接手方，
     * feeders/编码面会一直占着、终态永远没有出口（用户/结算只剩收工那一刻的早期快照）。
     *
     * 2026-11-20 收口：旧布尔 `teardownDeferred` 在「判活超时」与「置位」之间有一个双方都不做
     * 的窗口（泵线程读到 false 先退出）⇒ 改为 [TrailHandoff] 的两枚互斥 CAS，行为真值表在
     * `TrailHandoffTest`，这里钉接线：超时闸必须请求移交，泵检查点必须走同一枚闸。
     */
    @Test
    fun `超时闸必须把收尾责任移交给泵线程`() {
        val stop = bodyOf(codec, "stop")
        for (g in gatesIn(stop)) {
            val ret = stop.indexOf("return finishWithoutTeardown()", g)
            val branch = stop.substring(g, ret)
            assertTrue(
                "超时闸必须请求收尾移交（见 TrailHandoff：泵先越过检查点则本线程补做）",
                branch.contains("handOffTail()")
            )
        }
        val rel = bodyOf(codec, "release")
        val rg = rel.indexOf("if (!joinLoop())")
        val rret = rel.indexOf("return", rg)
        assertTrue("锚点丢失：release 没有超时闸", rg >= 0 && rret > rg)
        assertTrue(
            "release 超时闸也要移交（否则 feeders/编码面没人收）",
            rel.substring(rg, rret).contains("handOffTail()")
        )
        val trail = bodyOf(codec, "pumpTrail")
        assertTrue("泵线程必须恒停环境 feeder（owner must clean feeders）", trail.contains("stopFeeder()"))
        assertTrue("泵线程必须恒停内录 feeder", trail.contains("stopCapFeeder()"))
        assertTrue(
            "泵越过检查点且无人请求 ⇒ 宣告不接管（正常停录由控制线程 finish，再收一次会清空段清单）",
            trail.contains("trail.pumpCheckpoint()")
        )
        assertTrue("延迟收尾必须真的结算终态", trail.contains("finish(engineError)"))
        assertTrue(
            "编码面必须由泵线程释放（release 超时闸到不了那一步）",
            trail.contains("inputSurface = null") && trail.contains("s?.release()")
        )
        assertTrue("终态必须交付会话所有者（带会话键 + 真实结果）", trail.contains("RecorderDelivery.FinalResult("))
        assertTrue(
            "泵循环退出必须由 pumpEntry 的 finally 收尾（正常/Throwable 都恰好一次）",
            bodyOf(codec, "pumpEntry").contains("pumpTrail()")
        )
        assertFalse(
            "pumpLoop 不许再自己调 pumpTrail（否则 pumpTrail 抛错会双重收尾/双重交付）",
            bodyOf(codec, "pumpLoop").contains("pumpTrail()")
        )
        assertTrue(
            "泵线程必须从 pumpEntry 进（pumpLoop 抛出时收尾也不许跳）",
            bodyOf(codec, "pumpEntry").contains("pumpTrail()")
        )
        assertTrue("泵线程入口必须调用 pumpEntry", codec.contains("pumpEntry()"))
        // 控制线程补做的收尾必须和泵线程同口径（finish + 放输入面），且只由移交闸授权
        val hand = bodyOf(codec, "handOffTail")
        assertTrue("补做收尾必须先问移交闸", hand.contains("trail.controlDefer()"))
        assertTrue("补做收尾必须结算终态", hand.contains("finish(engineError)"))
        assertTrue("补做收尾必须放输入面", hand.contains("s?.release()"))
    }

    /**
     * R06：封口段的发布必须独立成路，并把**实际结果分类**交回会话（迟到 commit 成功后
     * 已发布段集合变化 ⇒ 结算键变化 ⇒ 归组/练习回程能收敛到最终集合）。
     */
    @Test
    fun `迟到发布必须经独立发布路交回会话`() {
        assertTrue("封段必须走发布路", bodyOf(codec, "sealCurrent").contains("sealedPublisher.publish("))
        // 发布线程不捕获 recorder：迟到交付经 SessionRelay 窄通道（身份 + 交付函数）
        // 用绑定方法引用而不是 lambda：lambda 会隐式捕获 this（CodecRecorder），挂住的发布线程
        // 就把 recorder/feeders/输入面/Activity 整条链一起钉住。
        assertTrue(
            "发布回调必须只捕获窄通道（sessionRelay::onLate），不许 lambda 捕获 recorder",
            codec.contains("SealedPublisher(PUBLISH_WAIT_MS, sessionRelay::onLate)")
        )
        val late = bodyOf(sealedPub, "onLate")
        assertTrue("迟到结论必须带实际发布分类", late.contains("classification = part.classification"))
        assertTrue("迟到结论必须带发布路剩余 pending 数", late.contains("pendingPublications = pendingAfter"))
        assertTrue("迟到结论必须交会话所有者", late.contains("RecorderDelivery.LatePart("))
        assertTrue(
            "收尾段视图必须并上发布台账（同步 + 迟到），否则结果与相册对不上",
            bodyOf(codec, "buildResult").contains("mergedParts()") &&
                bodyOf(codec, "finishWithoutTeardown").contains("sealedPublisher.snapshot()") &&
                bodyOf(codec, "finishWithoutTeardown").contains("visibleSegment()")
        )
    }

    /**
     * 泵线程异常收尾（R07 收口）：任何 Throwable 都必须走**唯一一次** [pumpTrail]，且每一步
     * 单独隔离——停 feeder 抛错不许跳过终态结算/输入面释放/会话交付；首错优先，不被连锁异常覆盖。
     */
    @Test
    fun `泵线程任何异常都必须走唯一收尾且保留首个真实错误`() {
        val entry = bodyOf(codec, "pumpEntry")
        assertTrue("pumpEntry 必须兜住 Throwable（Error 也不许跳过收尾）", entry.contains("catch (t: Throwable)"))
        assertTrue("pumpEntry 必须记下首个异常", entry.contains("pumpThrew"))
        assertTrue("pumpEntry 必须在 finally 里收尾（正常/异常都恰好一次）", entry.contains("finally"))
        assertTrue("pumpEntry 必须调用 pumpTrail", entry.contains("pumpTrail()"))
        assertTrue(
            "后续收尾异常不许覆盖泵循环的首错",
            entry.contains("if (pumpThrew == null) engineError = engineError ?: ")
        )

        val trail = bodyOf(codec, "pumpTrail")
        assertTrue("收尾必须逐步骤隔离（一个步骤抛错不许跳过其余）", trail.contains("fun isolated("))
        assertTrue("隔离器必须保留第一个失败", trail.contains("if (firstFailure == null)"))
        assertTrue("每个收尾步骤都必须经 isolated 调用", occurrences(trail, "isolated(").size >= 7)
        assertTrue(
            "终态结算必须真的执行 finish（不能只声明）",
            trail.contains("out = finish(engineError)")
        )
        assertTrue(
            "终态交付必须带会话身份 + 尝试序号",
            trail.contains("RecorderDelivery.FinalResult(seriesId, startedAtMs, settled, attempt = attempt)")
        )
        // 步骤名是字符串字面量（codeOnly 会抹成空白）⇒ 这几条读原文
        val raw = mainSourceText("record/CodecRecorder.kt")
        for (step in listOf("stopFeeder", "stopCapFeeder", "rotateLeg.endLeg", "finish", "releaseInputSurface", "deliverFinalResult")) {
            assertTrue("收尾步骤 $step 必须经 isolated 执行", raw.contains("isolated(\"$step\")"))
        }
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
