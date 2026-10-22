package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起录链有界化收口的**接线守卫**（2026-10-12）。
 *
 * 缺陷背景：录制链曾有「MediaProvider 同步调用无界等待 ⇒ 30~45s 停摆、无错误码、计时冻结」的
 * 真机前科（RotateGuard.kt 头注），引擎换段腿因此铺满 BoundedStore（REC-08 修复）；但**起录链**
 * 上三处同类调用此前只留痕不设界：beginSession 首枚 insert、prepare 的 OutputTarget.open、
 * prepare 失败 reject 的 discard——本轮全部收进有界体系。
 *
 * 判据全部作用在 codeOnly 遮蔽后的**代码结构**上（注释与日志字面量不参与判定——那是文档，
 * 归 code review；守卫钉的是拆不回回潮的结构）。三处是把「无界死等」改成了「3s 判起录失败、
 * 快门放行」的**行为变更**，回潮（拆掉包装改回裸调用）就是把有界化做回无界（RotateGuard 护栏
 * 原话），必须让尺子当场红。
 */
class StartChainBoundedGuardTest {

    private fun masked(rel: String): String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun body(rel: String, funName: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(masked(rel), funName))

    // ------------------------------------------------------------------ beginSession（UI 起录链）

    @Test
    fun `首枚insert必须经BoundedStore_超时必须判起录失败`() {
        val begin = body("ui/CameraScreen.kt", "beginSession")
        assertTrue(
            "首枚 pending insert 必须走 BoundedStore（裸 createPending = 无界死等回潮，30-45s 卡顿前科）",
            begin.contains("BoundedStore(") && begin.contains("store.createPending(0)")
        )
        assertTrue(
            "超时分支必须 failNow(NO_OUTPUT)（有界化的意义就在'3s 明确失败可重试'，不判败 = 白包）",
            begin.contains("failNow(RecordError.NO_OUTPUT)")
        )
        // 突变自证：拆掉包装（回退裸调用）——尺子必须红。
        // ⚠ 目标串是 codeOnly+flatten 后的形态：`"WotaBeginStore"` 字面量已被遮成空格，
        // 压缩后恰为 `BoundedStore( )`——直接拿源码原文当目标串会替换不中（突变体恒等原文，假红）
        val mutated = body("ui/CameraScreen.kt", "beginSession")
            .replace("BoundedStore( ).run(STORE_CALL_BUDGET_MS) { store.createPending(0) }", "store.createPending(0)")
        assertTrue("首枚 insert 拆掉有界包装后尺子必须红", !mutated.contains("BoundedStore("))
    }

    // ------------------------------------------------------------------ CodecRecorder.prepare

    @Test
    fun `prepare的open必须走openTargetBounded_裸调全仓只剩封装体内一处`() {
        val prepare = body("record/CodecRecorder.kt", "prepare")
        assertTrue(
            "prepare 必须经 openTargetBounded（裸 OutputTarget.open = prepare 期无界等待回潮）",
            prepare.contains("openTargetBounded(sink)")
        )
        // 全文件口径：裸 open 只允许出现在 openTargetBounded 封装体内那一处
        // （换段路 openNextSegment 走的也是同一封装，r19 起）
        assertEquals(
            "CodecRecorder 里裸 OutputTarget.open(ctx…) 只许在 openTargetBounded 封装体内出现一次",
            1,
            KotlinSourceScan.occurrences(masked("record/CodecRecorder.kt"), "OutputTarget.open(ctx")
                .size
        )
        // 突变自证：prepare 回退裸调用
        val mutated = prepare.replace("openTargetBounded(sink)", "OutputTarget.open(ctx, sink)")
        assertTrue(
            "prepare 拆掉有界封装后尺子必须红",
            mutated.contains("OutputTarget.open(ctx, sink)") &&
                !mutated.contains("openTargetBounded")
        )
    }

    @Test
    fun `reject的discard必须经storeIo有界包装`() {
        val reject = body("record/CodecRecorder.kt", "reject")
        assertTrue(
            "reject 的 discard 必须经 storeIo.run（裸 store.discard = prepare 失败路径无界回潮）",
            reject.contains("storeIo.run(STORE_CALL_BUDGET_MS) { store.discard(sink) }")
        )
        // 突变自证：拆掉包装回退裸调用
        val mutated = reject.replace(
            "storeIo.run(STORE_CALL_BUDGET_MS) { store.discard(sink) }",
            "store.discard(sink)"
        )
        assertTrue("reject 拆掉有界包装后尺子必须红", !mutated.contains("storeIo.run"))
    }

    // ------------------------------------------------------------------ prepare 分步耗时打点（纯观测）

    /** 判代码结构（打点的四枚时间戳变量），不判日志字面量——codeOnly 会把字符串遮掉 */
    @Test
    fun `prepare必须留分步耗时打点结构`() {
        val prepare = body("record/CodecRecorder.kt", "prepare")
        listOf("val ptOpen =", "val ptVenc =", "val ptAudio =", "val ptMux =").forEach {
            assertTrue("prepare 缺少分步打点 $it（REG-10 起录延迟攒数据的观测面，拆了 = 延迟黑盒回潮）", prepare.contains(it))
        }
        assertTrue("打点必须汇总出 total（ptMux − pt0）", prepare.contains("ptMux - pt0"))
    }
}
