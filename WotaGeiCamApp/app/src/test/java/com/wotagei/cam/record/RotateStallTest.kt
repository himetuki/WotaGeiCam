package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 换段腿有界化与丢帧账的**纯函数/协议**用例（2026-10-08 真机缺陷修复）。
 *
 * 现场（WIKO GAR-AN60 / Android 11）：24※ 强制档下每轮录到 1~6 段之间就停摆几十秒，
 * 段名时间戳相差 30~45s、无新文件、无错误码、计时冻结，停摆期间画面静默丢；停止时才又封一段。
 * 代码级结论：阈值触顶后泵线程要在同一条线程上串行走完"拆编码器/muxer → 关 fd → 入库
 * （查 SIZE/DATA + 清 IS_PENDING + 重试）→ 建新 pending → 开新 fd → 重建编码器"，
 * 每一步都是**无上限**的 MediaProvider/FUSE 同步调用 ⇒ 卡住即静默丢内容。
 *
 * 本文件锁三件事：判定表（超预算、丢帧折算、停摆桥）、有界执行器与交接闸的**行为协议**、
 * 以及丢帧账进 sidecar 的形状。
 */
class RotateStallTest {

    // region 判定表（手写字面量，全表）

    @Test
    fun `换段腿超预算判定表`() {
        // 边界口径与 healthVerdict 一致：恰好到预算算超（不无限等）
        assertFalse("0ms 不算超", rotateLegOverBudget(0L))
        assertFalse("良性开销内不算超", rotateLegOverBudget(600L))
        assertFalse("差 1ms 不算超", rotateLegOverBudget(2_999L))
        assertTrue("恰好到预算算超", rotateLegOverBudget(3_000L))
        assertTrue("超一点算超", rotateLegOverBudget(3_001L))
        assertTrue("真机 30s 级停摆必算超", rotateLegOverBudget(38_000L))
        assertTrue("可传自定义预算", rotateLegOverBudget(1_500L, budgetMs = 1_000L))
        assertFalse("自定义预算下未到点不算超", rotateLegOverBudget(999L, budgetMs = 1_000L))
    }

    @Test
    fun `清账预算保底表`() {
        // 收摊（关输出 fd）漏了就是句柄泄漏，腿预算已尽也要留下限
        assertEquals("预算充足：原样", 2_400L, cleanupBudgetMs(2_400L))
        assertEquals("预算为负：保底", CLEANUP_MIN_BUDGET_MS, cleanupBudgetMs(-800L))
        assertEquals("预算为 0：保底", CLEANUP_MIN_BUDGET_MS, cleanupBudgetMs(0L))
        assertEquals("刚好到下限：原样", CLEANUP_MIN_BUDGET_MS, cleanupBudgetMs(CLEANUP_MIN_BUDGET_MS))
        assertEquals("可传自定义下限", 100L, cleanupBudgetMs(10L, floorMs = 100L))
    }

    @Test
    fun `腿耗时折算丢失时长表`() {        // 良性开销（拆 muxer/入库/重建编码器本身）不算"丢失"：健康轮每段都走几百毫秒，
        // 记账若把它算成丢失，用户会看到每段都"丢了半秒"
        assertEquals("0 不丢", 0L, rotateLostMs(0L))
        assertEquals("负值不丢（时钟异常兜底）", 0L, rotateLostMs(-5L))
        assertEquals("健康腿 300ms 不丢", 0L, rotateLostMs(300L))
        assertEquals("恰好到良性开销不丢", 0L, rotateLostMs(ROTATE_BENIGN_MS))
        assertEquals("超 1ms 记 1ms", 1L, rotateLostMs(1_001L))
        assertEquals("3s 停摆记 2s", 2_000L, rotateLostMs(3_000L))
        assertEquals("38s 停摆记 37s", 37_000L, rotateLostMs(38_000L))
        assertEquals("可传自定义良性开销", 500L, rotateLostMs(1_000L, benignMs = 500L))
    }

    @Test
    fun `停止路径兜底判据收严表`() {
        // 停止时"最后一段封段"本身可能慢（大文件 commit）：不许把它误报成"丢了 N 秒"
        assertFalse("腿 3s 没到兜底线：不判失败", rotateStopStallDecision(storeTimedOut = false, legMs = 3_000L))
        assertFalse("腿 5.9s 仍在有界腿最坏耗时内：不判失败", rotateStopStallDecision(false, 5_999L))
        assertTrue("腿到有界腿最坏耗时（3s 腿 + 3s 一次清账）: 判失败", rotateStopStallDecision(false, 6_000L))
        assertTrue("入库确已撞上限：直接判失败", rotateStopStallDecision(storeTimedOut = true, legMs = 100L))
        assertTrue("两项都中：判失败", rotateStopStallDecision(true, 38_000L))
        // 收严必须真的比泵侧严：泵侧 3s 就判，兜底侧 3s 不许判（否则误报大文件封段）
        assertTrue(
            "兜底判据必须严于泵侧（同一条 3s 腿：泵侧判失败、兜底侧不判）",
            rotateStallDecision(false, 3_000L) && !rotateStopStallDecision(false, 3_000L)
        )
    }

    @Test
    fun `停摆判定桥表`() {
        // 两条路互相独立：入库超时（最直接证据）与腿耗时越界（native 调用拖长也要抓）
        assertFalse("都没越界：不判停摆", rotateStallDecision(storeTimedOut = false, legMs = 800L))
        assertTrue("入库撞上限：判停摆（哪怕腿还没到点）", rotateStallDecision(storeTimedOut = true, legMs = 800L))
        assertTrue("腿到预算：判停摆", rotateStallDecision(storeTimedOut = false, legMs = 3_000L))
        assertTrue("两条都中：判停摆", rotateStallDecision(storeTimedOut = true, legMs = 30_000L))
        assertTrue("自定义预算生效", rotateStallDecision(false, 1_200L, budgetMs = 1_000L))
        assertFalse("自定义预算下未到点", rotateStallDecision(false, 999L, budgetMs = 1_000L))
    }

    @Test
    fun `失败码带载荷且能与引擎错误码区分`() {
        val code = rotateStallError(37_000L)
        assertEquals("码形状=ROTATE_STALL:丢失ms", "ROTATE_STALL:37000", code)
        assertEquals("载荷可回读", 37_000L, rotateStallLostMs(code))
        assertNull("非本码回 null", rotateStallLostMs("ENGINE_ERROR:MUX"))
        assertNull("空回 null", rotateStallLostMs(null))
        assertNull("畸形载荷回 null", rotateStallLostMs("ROTATE_STALL:abc"))
        assertNull("无载荷回 null", rotateStallLostMs("ROTATE_STALL"))
        // 必须与 ENGINE_ERROR 前缀不重叠：recordResultText 的映射按前缀分流，
        // 重叠会让用户看到"引擎报错"而不是"丢了多久"
        assertFalse(code.startsWith(RecordError.ENGINE_ERROR))
        assertFalse(rotateStallError(0L).startsWith(RecordError.ENGINE_ERROR))
    }

    @Test
    fun `丢失秒级读数四舍五入且至少一秒`() {
        assertEquals("0ms 也至少报 1 秒（提示不许自相矛盾）", 1L, rotateStallLostSeconds(0L))
        assertEquals("400ms 进位到 1 秒", 1L, rotateStallLostSeconds(400L))
        assertEquals("500ms 进位到 1 秒", 1L, rotateStallLostSeconds(500L))
        assertEquals("501ms 进位到 1 秒", 1L, rotateStallLostSeconds(501L))
        assertEquals("1499ms 退到 1 秒", 1L, rotateStallLostSeconds(1_499L))
        assertEquals("1500ms 到 2 秒", 2L, rotateStallLostSeconds(1_500L))
        assertEquals("37s 原样", 37L, rotateStallLostSeconds(37_000L))
    }

    @Test
    fun `换段腿入库失败的重试判定表`() {
        // 一次失败就放弃 = 整场录制当场结束（真机就死在这里），所以允许在预算内再试一次
        assertTrue("首次失败、预算充足：重试", rotateRetryDecision(attempt = 1, leftMs = 2_400L))
        assertFalse("已试满上限：不再重试", rotateRetryDecision(attempt = 2, leftMs = 2_400L))
        assertFalse("再往上更不重试", rotateRetryDecision(attempt = 3, leftMs = 2_400L))
        assertFalse("预算不够一轮重试：不重试", rotateRetryDecision(1, 499L))
        assertTrue("预算恰好到门槛：重试", rotateRetryDecision(1, 500L))
        assertFalse("预算为负（腿已超支）：不重试", rotateRetryDecision(1, -200L))
        assertTrue("可传自定义上限", rotateRetryDecision(2, 2_400L, maxAttempts = 3))
        assertFalse("可传自定义门槛", rotateRetryDecision(1, 50L, floorMs = 100L))
    }

    @Test
    fun `失败码带环节游标且可回读`() {
        assertEquals("无游标：保持旧形状", "ROTATE_STALL:1200", rotateStallError(1_200L))
        assertEquals("带游标", "ROTATE_STALL:1200:INSERT", rotateStallError(1_200L, RotateStage.INSERT))
        assertEquals("丢失毫秒照旧可读（游标不吃载荷）", 1_200L, rotateStallLostMs("ROTATE_STALL:1200:INSERT"))
        assertEquals("游标可回读", "INSERT", rotateStallDetail("ROTATE_STALL:1200:INSERT"))
        assertEquals("游标可回读（OPEN）", "OPEN", rotateStallDetail("ROTATE_STALL:37:OPEN"))
        assertEquals("无游标回空串", "", rotateStallDetail("ROTATE_STALL:1200"))
        assertEquals("非本码回空串", "", rotateStallDetail("ENGINE_ERROR:MUX"))
        assertEquals("空回空串", "", rotateStallDetail(null))
        // 每个环节常量都要能进码里（防"声明了常量但没接线"）
        for (s in listOf(
            RotateStage.SEAL, RotateStage.INSERT, RotateStage.OPEN,
            RotateStage.ENCODE, RotateStage.MUX, RotateStage.START, RotateStage.ROTATE
        )) {
            assertEquals(s, rotateStallDetail(rotateStallError(1L, s)))
        }
    }

    @Test
    fun `失败账行格式可被排查者一眼对上`() {
        assertEquals(
            "t=2026-10-08_14:43:40 parts=2 dur=88s lost=1200ms code=ROTATE_STALL:1200:INSERT",
            rotateFailLedgerLine(
                stamp = "2026-10-08_14:43:40", parts = 2, durationMs = 88_400L,
                lostMs = 1_200L, code = "ROTATE_STALL:1200:INSERT"
            )
        )
        // 秒级取整、毫秒原值：时长看得懂，丢失不失真
        assertEquals(
            "t=x parts=0 dur=0s lost=0ms code=ENGINE_ERROR:ROTATE",
            rotateFailLedgerLine("x", 0, 999L, 0L, "ENGINE_ERROR:ROTATE")
        )
        // 有迟到落地的 insert 才追加 late= 字段（0 不落键，与 sidecar 同一口径）
        assertEquals(
            "t=x parts=3 dur=60s lost=0ms code=OK_WITH_LOSS late=2",
            rotateFailLedgerLine("x", 3, 60_000L, 0L, "OK_WITH_LOSS", latePending = 2L)
        )
        // 重建过本段也要留痕（用户看到"没问题"时，账上仍能看出踩过 muxer 写失败）
        assertEquals(
            "t=x parts=3 dur=60s lost=1200ms code=ENGINE_ERROR:MUX|trk=V late=1 restarts=1",
            rotateFailLedgerLine("x", 3, 60_000L, 1_200L, "ENGINE_ERROR:MUX|trk=V", 1L, 1)
        )
    }

    @Test
    fun `失败账只在真出事时落`() {
        assertTrue("换段受阻失败：落", RotateFailLog.shouldLog("ROTATE_STALL:1200:INSERT", 1_200L))
        assertTrue("引擎报错：落", RotateFailLog.shouldLog("ENGINE_ERROR:ROTATE", 0L))
        assertTrue("成功但丢过内容：落（用户要看的那条）", RotateFailLog.shouldLog(null, 2_000L))
        assertFalse("正常成功：不落", RotateFailLog.shouldLog(null, 0L))
        assertFalse("无关失败（如空间不足）：不落", RotateFailLog.shouldLog("LOW_STORAGE:10", 0L))
        assertTrue("换段受阻类判据", RotateFailLog.isRotationFailure("ROTATE_STALL:1"))
        assertTrue("引擎错误类判据", RotateFailLog.isRotationFailure("ENGINE_ERROR:REBIND"))
        assertFalse("别的码不算", RotateFailLog.isRotationFailure("NO_OUTPUT"))
        assertFalse("空不算", RotateFailLog.isRotationFailure(null))
        // 已被有界重建接住的 muxer 写失败：不判废整场，但账目里必须留痕（否则"这次没报错"=没线索）
        val recovered = muxRecoveredCode("trk=V;field=same;wb=0;vs=0")
        assertTrue("已重建标记必须可识别", RotateFailLog.isRotationFailure(recovered))
        assertTrue("已重建也要落账", RotateFailLog.shouldLog(recovered, 0L))
        assertTrue("标记里必须带快照", recovered.contains("field=same") && recovered.startsWith("MUX_RECOVERED"))
    }

    @Test
    fun `失败账追加_超上限整份重写`() {
        val dir = java.nio.file.Files.createTempDirectory("rotate-fail").toFile()
        try {
            assertTrue(RotateFailLog.append(dir, "line-1"))
            assertTrue(RotateFailLog.append(dir, "line-2"))
            val f = java.io.File(dir, RotateFailLog.FILE_NAME)
            assertEquals("逐行追加", "line-1\nline-2\n", f.readText(Charsets.UTF_8))
            // 超上限：整份重写，不许无限长个头
            f.writeText("x".repeat(20 * 1024), Charsets.UTF_8)
            assertTrue(RotateFailLog.append(dir, "line-3"))
            assertEquals("超限后只剩新行", "line-3\n", f.readText(Charsets.UTF_8))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `丢帧账只记异常丢失且能累加`() {
        val led = RotateLossLedger()
        assertEquals(0L, led.totalMs)
        assertEquals(0, led.count)
        led.add(0L)
        led.add(-3L)
        assertEquals("良性开销不计数", 0, led.count)
        assertEquals(0L, led.totalMs)
        led.add(2_000L)
        led.add(900L)
        assertEquals("累加", 2_900L, led.totalMs)
        assertEquals("只数 >0 的次数", 2, led.count)
        assertEquals("最坏一次", 2_000L, led.worstMs)
        led.add(0L)
        assertEquals("再加 0 不改账", 2_900L, led.totalMs)
        led.clear()
        assertEquals(0L, led.totalMs)
        assertEquals(0L, led.worstMs)
        assertEquals(0, led.count)
    }

    // endregion

    // region 有界执行器（行为协议）

    @Test
    fun `有界执行器_快调用拿到值_慢调用判超时`() {
        val io = BoundedStore("TestStoreIo")
        val quick = io.run(2_000L) { 7 }
        val got = when (quick) {
            is Bounded.Done -> quick.value
            Bounded.TimedOut -> -1
        }
        assertEquals("快调用必须拿到真值", 7, got)

        val slow = io.run(30L) {
            Thread.sleep(400L)
            9
        }
        assertTrue("慢调用必须判超时（不许无限等）", slow is Bounded.TimedOut)

        // 空串是合法值，不许被当成"没拿到"
        val empty = io.run(50L) { "" }
        assertEquals("", when (empty) {
            is Bounded.Done -> empty.value
            Bounded.TimedOut -> "超时"
        })
    }

    @Test
    fun `有界执行器_零预算不发调用`() {
        val io = BoundedStore("TestStoreIo")
        val ran = AtomicInteger(0)
        val r0 = io.run(0L) { ran.incrementAndGet() }
        val rNeg = io.run(-1L) { ran.incrementAndGet() }
        assertTrue(r0 is Bounded.TimedOut)
        assertTrue(rNeg is Bounded.TimedOut)
        assertEquals("预算已尽时不许再发一次无预算的调用", 0, ran.get())
    }

    @Test
    fun `有界执行器_调用里的异常照原样抛回`() {
        val io = BoundedStore("TestStoreIo")
        var thrown: Throwable? = null
        try {
            io.run(2_000L) { throw IllegalStateException("入库炸了") }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("异常必须回抛（不许被吞成超时）", thrown is IllegalStateException)
        assertEquals("入库炸了", thrown?.message)
    }

    // endregion

    // region pending 交接闸（无竞态泄漏）

    // region muxer 写失败的现场快照与有界重建（2026-10-08 第二次真机复现）

    @Test
    fun `mux失败快照必须能分辨写超停与写先于start`() {
        val same = muxFailDiagnostic(
            kind = "V", started = true, fieldSame = true, partIndex = 2, track = 1,
            size = 12_345, ptsUs = 4_567L, flags = 0, segBaseUs = 987_654L,
            writtenBytes = 0L, videoSamples = 0, restarts = 0, stopping = false, rotatePending = true
        )
        val gone = muxFailDiagnostic(
            kind = "A", started = true, fieldSame = false, partIndex = 3, track = 2,
            size = 999, ptsUs = 1L, flags = 8, segBaseUs = 5L,
            writtenBytes = 4_096L, videoSamples = 3, restarts = 1, stopping = true, rotatePending = false
        )
        // 分辨判据就在这两个字段上：field=same 不是"我们把它关了"（写超停已被排除）
        assertTrue("必须带 field=same", same.contains("field=same"))
        assertTrue("必须带 field=gone", gone.contains("field=gone"))
        assertTrue("必须带轨别", same.contains("trk=V") && gone.contains("trk=A"))
        assertTrue("必须带 start 状态（闸门是否失效）", same.contains("started=1"))
        assertTrue("必须带段号与轨号", same.contains("part=2;n=1"))
        assertTrue("必须带本段已写字节/视频样本（重建判据）", same.contains("wb=0;vs=0") && gone.contains("wb=4096;vs=3"))
        assertTrue("必须带 stopping/rotatePending（停止期余帧 vs 换段期）", gone.contains("st=1;rp=0") && same.contains("st=0;rp=1"))
        assertTrue("必须带已重建次数", gone.contains("rs=1"))
        // 失败码前缀必须保持 ENGINE_ERROR（UI 按前缀映射，不许变成新文案）
        val code = muxFailError(same)
        assertTrue("失败码必须仍是引擎错误", code.startsWith(RecordError.ENGINE_ERROR))
        assertEquals("快照可原样回读", same, muxFailDetailOf(code))
        assertEquals("非本码回空串", "", muxFailDetailOf("ENGINE_ERROR:ROTATE"))
        assertEquals("空回空串", "", muxFailDetailOf(null))
    }

    @Test
    fun `新段首笔写不进的判据与有界重建表`() {
        // 新段一笔都没写成 ⇒ 重建本段（留它没意义）；已写过再失败 ⇒ 不是这个形态，走原失败通道
        assertTrue("零样本零字节=新段刚起来", freshSegmentMuxFailure(0, 0L))
        assertFalse("写过视频样本就不算", freshSegmentMuxFailure(1, 0L))
        assertFalse("写过字节就不算", freshSegmentMuxFailure(0, 128L))
        assertTrue("首次重建允许", muxRecoverDecision(fresh = true, restarts = 0))
        assertTrue("第二次重建仍允许（上限 2）", muxRecoverDecision(fresh = true, restarts = 1))
        assertFalse("触顶后不再重建（明确失败，保持可见失败通道）", muxRecoverDecision(fresh = true, restarts = 2))
        assertFalse("非新段不重建", muxRecoverDecision(fresh = false, restarts = 0))
        assertFalse("可传自定义上限=0", muxRecoverDecision(true, 0, maxRestarts = 0))
    }

    // endregion

    @Test
    fun `交接闸_正常腿可取走pending`() {
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { dropped.add(it) }
        assertTrue("首次交给闸", h.publish("u1"))
        assertEquals("取走", "u1", h.claim())
        assertNull("取走即空", h.claim())
        assertTrue("没有垃圾回收", dropped.isEmpty())
    }

    @Test
    fun `交接闸_判停摆后晚到的pending就地回收`() {
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { dropped.add(it) }
        assertNull("先判停摆：没有已落地的那枚", h.abandon())
        assertFalse("停摆后再交上来=不上交", h.publish("late"))
        assertEquals("晚到的那枚必须被回收（否则相册留 IS_PENDING 幽灵）", listOf("late"), dropped)
    }

    @Test
    fun `交接闸_先落地再判停摆_由泵线程回收`() {
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { dropped.add(it) }
        assertTrue(h.publish("u1"))
        assertEquals("判停摆要把已落地的那枚交出来回收", "u1", h.abandon())
        assertTrue("回收由调用方做（交接闸只负责交出来）", dropped.isEmpty())
        assertNull("已交出即空", h.abandon())
    }

    @Test
    fun `交接闸_空值与复位`() {
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { dropped.add(it) }
        assertFalse("insert 失败（null）不算交接", h.publish(null))
        assertTrue(dropped.isEmpty())
        assertTrue(h.publish("u1"))
        h.reset()
        assertEquals("复位必须就地回收上一轮留在闸里的那一枚（只丢引用=幽灵）", listOf("u1"), dropped)
        assertNull("复位要清空上一腿的落地值", h.claim())
        assertTrue("复位后重新可用", h.publish("u2"))
        assertEquals("u2", h.claim())
    }

    @Test
    fun `交接闸_重试撞上迟到插入_只留最新那枚且旧的就地回收`() {
        // 真机场景：第一次尝试判超时 → 重试；此时第一次那条死线程才把行插进去。
        // 不许让两枚 pending 同时在场（旧的那枚没人管就是幽灵），也不许把新插入的那枚丢掉
        val dropped = ArrayList<String>()
        val h = OneShotHandoff<String> { dropped.add(it) }
        assertTrue("第一次尝试上交", h.publish("stale"))
        assertFalse("第二次尝试的同腿插入必须被拒（闸里已有在场的那枚）", h.publish("fresh"))
        assertEquals("被拒的那枚必须就地回收（不许留两枚）", listOf("fresh"), dropped)
        assertEquals("在场的那枚仍可取走（本腿只认一枚，可用的那枚不丢）", "stale", h.claim())
    }

    @Test
    fun `交接闸_双线程交错下恰好回收或上交一次_不漏不重`() {
        // 真机场景：泵线程等超时后判停摆，与入库线程"刚刚落地"交错。两条路径必须恰好覆盖一次——
        // 漏一次=幽灵 pending 泄漏，重一次=把用户要用的那条删掉。
        repeat(200) {
            val handed = java.util.Collections.synchronizedList(ArrayList<String>())
            val h = OneShotHandoff<String> { handed.add(it) }
            val gate = CountDownLatch(1)
            val worker = Thread {
                gate.await()
                h.publish("u")
            }
            worker.isDaemon = true
            worker.start()
            gate.countDown()
            val reclaimed = h.abandon()
            worker.join(2_000L)
            val total = handed.size + (if (reclaimed != null) 1 else 0)
            assertEquals("第 $it 轮：u 必须恰好被处置一次（回收或上交）", 1, total)
        }
    }

    // endregion

    // region 丢帧账进 sidecar

    @Test
    fun `sidecar段清单携带换段丢失且旧形状不变`() {
        val log = ArcDropLog(
            "mend", 24, listOf(1 to 1),
            segments = listOf(ArcDropSegment(0, 0, 72), ArcDropSegment(1, 72, 72, lostMs = 37_000L))
        )
        val encoded = log.encode()
        // 0 不落键（旧档案形状逐字不变）、>0 才落键
        assertTrue(encoded.contains("{\"segment\":0,\"first\":0,\"count\":72}"))
        assertTrue(encoded.contains("{\"segment\":1,\"first\":72,\"count\":72,\"lostMs\":37000}"))
        assertEquals("往返无损", log, ArcDropLog.decode(encoded))
        // 旧样例（无 lostMs 键）仍原样可解，且解出的 lostMs 恒 0
        val legacy = "{\"mode\":\"mend\",\"dstFps\":24,\"drops\":[[1,1]]," +
            "\"segments\":[{\"segment\":0,\"first\":0,\"count\":3},{\"segment\":1,\"first\":3,\"count\":9}]}"
        assertEquals(
            ArcDropLog("mend", 24, listOf(1 to 1), listOf(ArcDropSegment(0, 0, 3), ArcDropSegment(1, 3, 9, 0L))),
            ArcDropLog.decode(legacy)
        )
        // 坏载荷整单判坏（与其它字段同口径）
        assertNull(ArcDropLog.decode("{\"mode\":\"mend\",\"dstFps\":24,\"drops\":[]," +
            "\"segments\":[{\"segment\":0,\"first\":0,\"count\":3,\"lostMs\":x}]}"))
    }

    // endregion
}
