package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.regionOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换段腿的**双端结算接线**（2026-10-11 起，Y18 改接）。
 *
 * 历史：腿起点原先是 `@Volatile Long`，两处结算（泵线程 [CodecRecorder.settleRotateLeg] 与控制线程
 * [CodecRecorder.settleRotateLegAtStop]）各自「读-算-清-加」，读与清之间有窗口 ⇒ 同一条腿记两遍。
 * 第一版修法用 `AtomicLong.getAndSet(0L)` 把「读 + 清」做成一次 CAS，双加被结构性排除。
 *
 * Y18 暴露出这枚变量**兼职三件事**：预算起钟、丢失结算锁、腿终点。停止路径一领结算就顺手清了腿，
 * 在途泵线程的预算读数退回腿外（详见 [RotateLegLedger]）。现在是两件独立的账：
 * - 结算权：`claim()`（CAS，先到者独得），**不动**预算；
 * - 腿终点：只有泵线程 `endLeg()`/`pumpTrail()` 清；停止路径**只领不清**。
 *
 * 本守卫钉接线形状（两件事没有漂回一枚变量），行为真值表在 [RotateLegLedgerTest]。
 * 「控制线程那一笔 `rotateLoss.add(lost)` 不能删」：`rotateLoss.totalMs` 被
 * `finishWithoutTeardown` 拿去生成**用户可见**的失败结果，删了用户看到的丢失量就少算。
 */
class RotateLegSettleGuardTest {

    private val codec = codeOnly(mainSourceText("record/CodecRecorder.kt"))

    // ---- 结构体检（真身与突变体同吃） ----

    private fun legViolations(maskedCodec: String): List<String> {
        val bad = ArrayList<String>()
        val settle = bodyOf(maskedCodec, "settleRotateLeg")
        val atStop = bodyOf(maskedCodec, "settleRotateLegAtStop")

        if (!settle.contains("rotateLeg.claim()")) {
            bad.add("settleRotateLeg 必须领 [RotateLegLedger.claim]（结算权恰好一次）")
        }
        if (!settle.contains("rotateLeg.endLeg()")) {
            bad.add("settleRotateLeg 是腿终点，必须清截止时刻")
        }
        if (!atStop.contains("rotateLeg.claim()")) {
            bad.add("settleRotateLegAtStop 必须领 claim（否则这条腿的丢失量没人记）")
        }
        if (atStop.contains("rotateLeg.endLeg()")) {
            bad.add("停止路径不许清截止时刻（泵线程还卡在腿里，清了预算就回到腿外的 3s）")
        }
        if (atStop.contains("rotateLeg.reset()")) {
            bad.add("停止路径不许复位腿账（会重开领取权 + 清截止，两本账一起串）")
        }
        if (!atStop.contains("rotateLoss.add(lost)")) {
            bad.add("控制线程那一笔 rotateLoss.add(lost) 不许删（用户可见失败结果靠它）")
        }
        if (!bodyOf(maskedCodec, "beginRotateLeg").contains("rotateLeg.begin()")) {
            bad.add("beginRotateLeg 必须起钟（设不可变截止 + 重开领取权）")
        }
        if (!bodyOf(maskedCodec, "resetRotateBooks").contains("rotateLeg.reset()")) {
            bad.add("resetRotateBooks 必须复位腿账")
        }
        if (!bodyOf(maskedCodec, "pumpTrail").contains("rotateLeg.endLeg()")) {
            bad.add("泵线程异常退出也要清截止（否则 release 的每次有界调用都判停摆）")
        }
        val budget = bodyOf(maskedCodec, "rotateBudgetLeftMs")
        if (!budget.contains("rotateLeg.budgetLeftMs()")) {
            bad.add("预算必须只由 RotateLegLedger.budgetLeftMs 派生")
        }
        if (budget.contains("claim") || budget.contains("endLeg")) {
            bad.add("预算不许参与结算领取/腿终点（Y18 的串台成因）")
        }
        if (!Regex("private val rotateLeg = RotateLegLedger\\(").containsMatchIn(maskedCodec)) {
            bad.add("腿账必须住在一枚 RotateLegLedger 里（预算/领取权/终点三件事分开）")
        }
        if (!maskedCodec.contains("legBudgetMs = ROTATE_LEG_BUDGET_MS")) {
            bad.add("腿预算常量必须真接进腿账（只声明不接线）")
        }
        if (!maskedCodec.contains("idleBudgetMs = STORE_CALL_BUDGET_MS")) {
            bad.add("腿外预算常量必须真接进腿账")
        }
        if (maskedCodec.contains("rotateLegStartMs")) {
            bad.add("旧的共用起钟变量 rotateLegStartMs 不许复活（预算与结算共用的成因）")
        }
        return bad
    }

    /** 把某个函数体内的某段文本换掉（不动其它函数里的同名锚串） */
    private fun mutateIn(src: String, funName: String, from: String, to: String): String {
        val r = regionOf(src, funName)
        val body = src.substring(r.start, r.end)
        assertTrue("突变锚点没截到：$funName 里没有 $from", body.contains(from))
        return src.substring(0, r.start) + body.replace(from, to) + src.substring(r.end)
    }

    @Test
    fun `双端结算接线完整`() {
        val bad = legViolations(codec)
        assertEquals("双端结算接线发现断点：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    @Test
    fun `尺子自己能红_接线突变体必报红`() {
        assertEquals("良品接线必须先绿", 0, legViolations(codec).size)
        val mutants = listOf(
            Triple(
                "停止路径顺手清截止（Y18 缺陷形态）",
                mutateIn(codec, "settleRotateLegAtStop", "rotateLeg.claim()", "rotateLeg.claim()\n        rotateLeg.endLeg()"),
                true
            ),
            Triple(
                "停止路径退回复位腿账",
                mutateIn(codec, "settleRotateLegAtStop", "rotateLeg.claim()", "rotateLeg.reset()"),
                true
            ),
            Triple(
                "删掉控制线程那一笔 add",
                mutateIn(codec, "settleRotateLegAtStop", "rotateLoss.add(lost)", "Unit"),
                true
            ),
            Triple(
                "泵线程不再清截止",
                mutateIn(codec, "settleRotateLeg", "rotateLeg.endLeg()", "Unit"),
                true
            ),
            Triple(
                "泵线程异常退出路径不再清截止",
                mutateIn(codec, "pumpTrail", "rotateLeg.endLeg()", "Unit"),
                true
            ),
            Triple(
                "预算拿结算领取权当来源（两本账又合回一枚）",
                mutateIn(codec, "rotateBudgetLeftMs", "rotateLeg.budgetLeftMs()", "rotateLeg.claim().toLong()"),
                true
            ),
            Triple(
                "旧共用起钟变量复活",
                codec.replace(
                    "private val rotateLeg = RotateLegLedger(",
                    "private val rotateLegStartMs = 0L\n    private val rotateLeg = RotateLegLedger("
                ),
                true
            ),
            // 对照组：只改注释不许引发红（尺子不许对无害改动过敏）
            Triple(
                "对照组：只改注释",
                mutateIn(codec, "settleRotateLegAtStop", "rotateLeg.claim()", "rotateLeg.claim() // 注释"),
                false
            )
        )
        mutants.forEach { (name, mutated, shouldBeRed) ->
            val red = legViolations(mutated)
            if (shouldBeRed) {
                assertTrue("突变体「$name」必须报红（实际违规：${red.joinToString("；")}）", red.isNotEmpty())
            } else {
                assertEquals("对照组「$name」不该报红（实际违规：${red.joinToString("；")}）", 0, red.size)
            }
        }
    }
}