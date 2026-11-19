package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y16：停录/换段的音频 EOS 不许越过 PCM 队列；两轨统一"输入已关闭"状态。
 *
 * 缺陷形态（旧代码 `runSegment` 停止门）：
 * ```
 * if (a != null && envCh != null && !envCh.eosQueued) queueAudioEos(a, envCh)
 * if (c != null && capCh != null && !capCh.eosQueued) queueAudioEos(c, capCh)
 * ```
 * EOS 当拍直接入编码器输入，而 [AudioFeeder] 队列里可能还压着最多 ~2.7s（64 包 ≈ 42.7ms/包）
 * PCM——EOS 一入队喂码门永久关闭，这些包再也进不了编码器，段边界 `drain()` 只能记成丢弃：
 * "停止那一刻砍掉固定长度尾音"，且与"本来就没声音"在日志里不可区分。内录的两个喂码入口还缺
 * `!eosQueued` 守卫（环境支有），EOS 后仍去抢编码器输入槽。
 *
 * 本测试钉四件事：
 * 1. 纯判定真值表（排空即 EOS / 有积压先喂 / 窗口到期强发）；
 * 2. 1s 积压的逐拍模拟：EOS 必须晚于排空（尾音按可达上限排空）；
 * 3. 结构接线：停止门经 `tailEosFor`，`FORCE_EOS` 把未喂进去的包数单独记账（赋值不累加）、
 *    喂码入口首行统一查 `eosQueued`、段收尾行打印尾账；
 * 4. 突变自证：拆掉输入关闭守卫，同一把尺必须报红。
 */
class AudioTailEosGuardTest {

    private val raw = mainSourceText("record/CodecRecorder.kt")
    private val codec = codeOnly(raw)
    private val seg = flatten(bodyOf(codec, "runSegment"))

    // ------------------------------------------------------------------ 纯判定真值表

    @Test
    fun `排空即发EOS_有积压先喂_抽干到期带积压强发`() {
        assertEquals(
            "队列与手里都空 ⇒ 正常发 EOS（旧行为，尾音完整）",
            AudioEosAction.QUEUE_EOS,
            audioEosAction(pending = 0, held = false, drainExpired = false)
        )
        assertEquals(
            "队列还有包 ⇒ 本拍先不发 EOS（下一拍继续喂）",
            AudioEosAction.FEED_THEN_DEFER,
            audioEosAction(pending = 1, held = false, drainExpired = false)
        )
        assertEquals(
            "手里还攥着一包（编码器没空位）同样算未排空",
            AudioEosAction.FEED_THEN_DEFER,
            audioEosAction(pending = 0, held = true, drainExpired = false)
        )
        assertEquals(
            "队列+手里都有 ⇒ 先喂",
            AudioEosAction.FEED_THEN_DEFER,
            audioEosAction(pending = 3, held = true, drainExpired = false)
        )
        assertEquals(
            "抽干窗口到期（编码器卡死）⇒ 带积压强行 EOS，不能无限等",
            AudioEosAction.FORCE_EOS,
            audioEosAction(pending = 0, held = false, drainExpired = true)
        )
        assertEquals(
            "到期时仍有积压 ⇒ 强发并记账（弃包数由调用点写进 eosDrops）",
            AudioEosAction.FORCE_EOS,
            audioEosAction(pending = 18, held = true, drainExpired = true)
        )
    }

    /**
     * 1s 积压停录的逐拍模拟（卡片验收：积压 1s PCM 停录 → 尾音按可达上限排空或计数入账）。
     *
     * 模型与泵循环同序：每拍**先喂 1 包**（含手里那包），再问停止门的收口判定。包长 ≈ 42.7ms
     * （8KiB、48kHz/16bit/双声道），24 包 ≈ 1.03s；抽干窗口（1200ms，有推进顺延至硬上限
     * 1900ms）远大于泵线程按拍消费 24 包所需时间 ⇒ 必须全部喂完才发 EOS。
     */
    @Test
    fun `积压1s停录_每拍1包排空后才发EOS_零弃包`() {
        var pending = 24
        var held = true // 手里已攥一包（上一拍编码器没空位）
        var firstEosTick = -1
        for (tick in 0 until 400) {
            // 喂码块（排在停止块之前）：手里优先，其次队列
            if (held) held = false else if (pending > 0) pending--
            val action = audioEosAction(pending, held, drainExpired = false)
            if (action == AudioEosAction.QUEUE_EOS) {
                firstEosTick = tick
                break
            }
            assertEquals("窗口内排空前不许发 EOS（EOS 越过积压 = 尾音被砍）", AudioEosAction.FEED_THEN_DEFER, action)
        }
        assertEquals(
            "24 包 + 手里 1 包必须 24 拍（第 0 拍吃手里那包）后恰好排空，实际 $firstEosTick",
            24,
            firstEosTick
        )
    }

    // ------------------------------------------------------------------ 结构接线

    @Test
    fun `停止门必须经tailEosFor收口_不许当拍直发EOS`() {
        assertTrue(
            "环境通道停止门必须经尾音收口（带当前积压与抽干到期信号）",
            raw.contains(
                "if (a != null && envCh != null && !envCh.eosQueued) " +
                    "tailEosFor(a, envCh, \"env\", ef?.pending() ?: 0, drainTimedOut)"
            )
        )
        assertTrue(
            "内录通道停止门必须经尾音收口",
            raw.contains(
                "if (c != null && capCh != null && !capCh.eosQueued) " +
                    "tailEosFor(c, capCh, \"cap\", cf?.pending() ?: 0, drainTimedOut)"
            )
        )
        assertFalse(
            "停止门里不许再有裸 queueAudioEos(a, envCh)（当拍直发 = EOS 越过积压）",
            seg.contains("!envCh.eosQueued) queueAudioEos(a, envCh)")
        )
        assertFalse(
            "停止门里不许再有裸 queueAudioEos(c, capCh)",
            seg.contains("!capCh.eosQueued) queueAudioEos(c, capCh)")
        )
    }

    @Test
    fun `尾音收口_到期弃包单独记账且不许累加`() {
        val tail = bodyOf(codec, "tailEosFor")
        assertTrue(
            "收口必须先问纯判定（积压/到期），不许在接线里另写一套",
            tail.contains("audioEosAction(pending, ch.heldPkt != null, drainExpired)")
        )
        assertEquals(
            "弃包账只许在 FORCE_EOS 一处写（排空路径写 0 会覆盖正常路径的账吗？不会——但两处写点会漂移）",
            1,
            occurrences(tail, "ch.eosDrops =").size
        )
        assertTrue(
            "弃包数 = 队列积压 + 手里真实包（手里的哨兵不算被弃 PCM）",
            tail.contains("ch.eosDrops = pending + heldReal")
        )
        assertFalse(
            "重试路径不许累加（queueInputBuffer 失败下拍重算，+= 会重复计数）",
            tail.contains("ch.eosDrops +=")
        )
        assertEquals(
            "两条发 EOS 出口（排空 / 到期强发）都必须真的入队",
            2,
            occurrences(tail, "queueAudioEos(codec, ch)").size
        )
        assertTrue("尾账必须有渠道可见（段收尾行打印）", raw.contains("尾弃="))
        assertTrue("通道状态必须有 eosDrops 字段", codec.contains("var eosDrops = 0"))
    }

    @Test
    fun `两轨统一输入关闭状态_喂码入口首行查eosQueued`() {
        val feed = bodyOf(codec, "feedAudioInput")
        val guardAt = feed.indexOf("if (ch.eosQueued) return")
        assertTrue(
            "feedAudioInput 首部必须有输入关闭守卫（内录两个入口没有外层 !eosQueued，环境支有）",
            guardAt >= 0
        )
        assertTrue(
            "守卫必须排在取包之前（EOS 之后不许再消费队列/抢输入槽）",
            guardAt < feed.indexOf("src.poll(0)")
        )
        // 双轨仍共用同一份喂码函数（修正不许分叉成两份判定）
        assertTrue(seg.contains("feedAudioInput(a, ef, envCh)"))
        assertTrue(seg.contains("feedAudioInput(c, cf, capCh)"))
        // 突变自证：拆掉守卫（回到"内录 EOS 后仍馈入"），同一把尺必须报红
        val m = bodyOf(codec.replace("if (ch.eosQueued) return", ""), "feedAudioInput")
        assertFalse("拆掉输入关闭守卫后守卫必须报红", m.contains("if (ch.eosQueued) return"))
        assertTrue("突变体仍能取包（说明拆的正是那行守卫，而不是整段逻辑）", m.contains("src.poll(0)"))
    }
}