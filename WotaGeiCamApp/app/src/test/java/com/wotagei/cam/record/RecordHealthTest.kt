package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起录自检判定（[RecordHealth.kt]）的纯函数单测。
 *
 * 期望值一律**手写字面量**（不调被测实现反推），避免"用实现证明实现"。判定表覆盖
 * 「errorCode 非空即使三格全真也判废」「视频路短窗抓得住 / 音轨慢只吃长窗」「恰好到窗算超窗」
 * 这几条最容易写反的边界；[retryActionOf] 作为"计划（判定）→行为（动作）"的**桥函数**单独直断言
 * ——删掉桥上的 `attempts < max` 边界（写成 `<=`）会让"第 2 次重录"被误放行成不认输，这里必须红。
 *
 * 两档窗口的鉴别力（改回单窗口即红）：`音轨慢` 用例给的是 `videoTrackAdded=true, muxStarted=false,
 * firstVideoSample=false, elapsed=1200`——旧单窗口口径会在此判 UNHEALTHY，本文件期望 WAIT。
 */
class RecordHealthTest {

    private val window = RecordHealthWindow(videoWindowMs = 1_200L, fullWindowMs = 3_000L, maxAttempts = 2)

    @Test
    fun `常量档位是既定设计值`() {
        // 视频路 1200ms（首帧正常 <300ms 留 4 倍余量）；完整含音轨 3000ms；
        // 最多重启 2 次（含首个共 3 次机会）；MR 路产出活性门槛 64KiB（挡"只写容器头"的假活）
        assertEquals(1_200L, VIDEO_WINDOW_MS)
        assertEquals(3_000L, FULL_WINDOW_MS)
        assertEquals(2, MAX_RESTART_ATTEMPTS)
        assertEquals(65_536L, MIN_OUTPUT_BYTES)
    }

    @Test
    fun `三格全真且无错误即健康`() {
        assertEquals(
            HealthVerdict.HEALTHY,
            healthVerdict(
                firstVideoSample = true, muxStarted = true, videoTrackAdded = true,
                errorCode = null, elapsedInWindowMs = 0L, window = window
            )
        )
    }

    @Test
    fun `健康优先于超窗 三格全真即使超过窗口也健康`() {
        assertEquals(
            HealthVerdict.HEALTHY,
            healthVerdict(
                firstVideoSample = true, muxStarted = true, videoTrackAdded = true,
                errorCode = null, elapsedInWindowMs = 9_999L, window = window
            )
        )
    }

    @Test
    fun `errorCode 非空即使三格全真也判废`() {
        // 引擎已判废的段不能因为"当时三格看着好"放行（如 writeSampleData 抛错后三格仍停在真值）
        assertEquals(
            HealthVerdict.UNHEALTHY,
            healthVerdict(
                firstVideoSample = true, muxStarted = true, videoTrackAdded = true,
                errorCode = RecordError.ENGINE_ERROR, elapsedInWindowMs = 10L, window = window
            )
        )
    }

    @Test
    fun `errorCode 非空且窗口未到也立刻判废`() {
        assertEquals(
            HealthVerdict.UNHEALTHY,
            healthVerdict(
                firstVideoSample = false, muxStarted = false, videoTrackAdded = false,
                errorCode = "${RecordError.ENGINE_ERROR}:1:2", elapsedInWindowMs = 5L, window = window
            )
        )
    }

    @Test
    fun `三格未立齐且两窗都未到即等待`() {
        assertEquals(
            HealthVerdict.WAIT,
            healthVerdict(
                firstVideoSample = false, muxStarted = false, videoTrackAdded = false,
                errorCode = null, elapsedInWindowMs = 180L, window = window
            )
        )
    }

    /**
     * 视频路短窗抓得住：`videoTrackAdded` 在视频窗到点仍为 false = 这就是 EGL_BAD_ALLOC 那次的
     * 真实形态（编码器根本没建起来），1.2s 即判废，不用等到长窗。
     */
    @Test
    fun `视频路短窗毫无音讯即判废 不等长窗`() {
        val deadAt: (Long) -> HealthVerdict = { elapsed ->
            healthVerdict(
                firstVideoSample = false, muxStarted = false, videoTrackAdded = false,
                errorCode = null, elapsedInWindowMs = elapsed, window = window
            )
        }
        assertEquals(HealthVerdict.WAIT, deadAt(1_199L))
        // 恰好到视频窗也算超窗（边界取 >= 而非 >）
        assertEquals(HealthVerdict.UNHEALTHY, deadAt(1_200L))
        assertEquals(HealthVerdict.UNHEALTHY, deadAt(1_201L))
    }

    /**
     * 音轨慢不误判（本任务的核心鉴别力）：视频编码器建成（videoTrackAdded=true），但 muxer 要等
     * 环境/内录轨 addTrack 才 start ⇒ muxStarted/firstVideoSample 恒 false。旧单窗口口径在 1.2s
     * 就把这形态判废（正常启动被误杀整段）；新口径只在长窗到点才判废。
     */
    @Test
    fun `音轨慢时短窗不误判 长窗到点才判废`() {
        val slowAt: (Long) -> HealthVerdict = { elapsed ->
            healthVerdict(
                firstVideoSample = false, muxStarted = false, videoTrackAdded = true,
                errorCode = null, elapsedInWindowMs = elapsed, window = window
            )
        }
        assertEquals(HealthVerdict.WAIT, slowAt(1_199L))
        // 视频窗到点但视频路已活 ⇒ 继续等音轨（旧单窗口正是在这一格误判）
        assertEquals(HealthVerdict.WAIT, slowAt(1_200L))
        assertEquals(HealthVerdict.WAIT, slowAt(2_999L))
        assertEquals(HealthVerdict.UNHEALTHY, slowAt(3_000L))
    }

    @Test
    fun `视频已建轨但首样本不到 长窗到点判废`() {
        assertEquals(
            HealthVerdict.WAIT,
            healthVerdict(
                firstVideoSample = false, muxStarted = true, videoTrackAdded = true,
                errorCode = null, elapsedInWindowMs = 2_999L, window = window
            )
        )
        assertEquals(
            HealthVerdict.UNHEALTHY,
            healthVerdict(
                firstVideoSample = false, muxStarted = true, videoTrackAdded = true,
                errorCode = null, elapsedInWindowMs = 3_000L, window = window
            )
        )
    }

    @Test
    fun `桥函数 判废且未触顶即重录`() {
        assertEquals(RetryAction.RESTART, retryActionOf(attempts = 0, window = window, verdict = HealthVerdict.UNHEALTHY))
        assertEquals(RetryAction.RESTART, retryActionOf(attempts = 1, window = window, verdict = HealthVerdict.UNHEALTHY))
    }

    @Test
    fun `桥函数 判废且触顶即认输`() {
        // attempts=2 = 已重录 2 次（含首个共 3 次机会用尽）→ 必须认输。
        // 把桥上边界写成 <= 会让这一格变 RESTART，本断言当场红
        assertEquals(RetryAction.GIVE_UP, retryActionOf(attempts = 2, window = window, verdict = HealthVerdict.UNHEALTHY))
        assertEquals(RetryAction.GIVE_UP, retryActionOf(attempts = 3, window = window, verdict = HealthVerdict.UNHEALTHY))
    }

    @Test
    fun `桥函数 健康或等待都不动作`() {
        assertEquals(RetryAction.NONE, retryActionOf(attempts = 0, window = window, verdict = HealthVerdict.HEALTHY))
        assertEquals(RetryAction.NONE, retryActionOf(attempts = 0, window = window, verdict = HealthVerdict.WAIT))
        assertEquals(RetryAction.NONE, retryActionOf(attempts = 9, window = window, verdict = HealthVerdict.HEALTHY))
    }

    @Test
    fun `产出活性 字节够量即健康`() {
        // 恰好门槛即健康（边界取 >= 而非 >）
        assertEquals(
            HealthVerdict.HEALTHY,
            streamHealthVerdict(bytesWritten = 65_536L, errorCode = null, elapsedMs = 0L, window = window)
        )
        assertEquals(
            HealthVerdict.HEALTHY,
            streamHealthVerdict(bytesWritten = 999_999L, errorCode = null, elapsedMs = 3_000L, window = window)
        )
    }

    @Test
    fun `产出活性 差一字节即未够量 窗口未到则等待`() {
        // 65_536 是门槛，差 1 就是"不足量"；此时窗口未到只能等（不是判废）
        assertEquals(
            HealthVerdict.WAIT,
            streamHealthVerdict(bytesWritten = 65_535L, errorCode = null, elapsedMs = 180L, window = window)
        )
    }

    @Test
    fun `产出活性 只写容器头的假活不算健康`() {
        // <1KB 是"一帧都没写、只剩 ftyp/moov 头"的形态：窗口内等、窗口末判废。
        // 把 MIN_OUTPUT_BYTES 改成 0 会让这一格变 HEALTHY（假绿），本断言当场红。
        assertEquals(
            HealthVerdict.WAIT,
            streamHealthVerdict(bytesWritten = 1_000L, errorCode = null, elapsedMs = 180L, window = window)
        )
        assertEquals(
            HealthVerdict.UNHEALTHY,
            streamHealthVerdict(bytesWritten = 1_000L, errorCode = null, elapsedMs = 3_000L, window = window)
        )
    }

    @Test
    fun `产出活性 窗口末字节不足即判废 恰好到窗算超窗`() {
        val atWindow: (Long) -> HealthVerdict = { elapsed ->
            streamHealthVerdict(bytesWritten = 65_535L, errorCode = null, elapsedMs = elapsed, window = window)
        }
        assertEquals(HealthVerdict.WAIT, atWindow(2_999L))
        assertEquals(HealthVerdict.UNHEALTHY, atWindow(3_000L))
        assertEquals(HealthVerdict.UNHEALTHY, atWindow(3_001L))
    }

    @Test
    fun `产出活性 errorCode 非空即使字节足量也判废`() {
        assertEquals(
            HealthVerdict.UNHEALTHY,
            streamHealthVerdict(
                bytesWritten = 10_000_000L,
                errorCode = RecordError.ENGINE_ERROR,
                elapsedMs = 0L,
                window = window
            )
        )
    }

    /**
     * 不可观测态护栏（本任务核心，绝不许变成误杀）：`Os.fstat` 拿不到可信值时引擎传 -1L，
     * 判定必须退回"只认错误码"——无错即 [HealthVerdict.WAIT]，两个时刻都不得因"字节看着像 0"
     * 在窗口末判废。删掉 `bytesWritten < 0 → WAIT` 这条会让窗口末那格落进 UNHEALTHY，本断言红。
     */
    @Test
    fun `产出活性 不可观测时两个时刻都等待 不误杀`() {
        assertEquals(
            HealthVerdict.WAIT,
            streamHealthVerdict(bytesWritten = -1L, errorCode = null, elapsedMs = 180L, window = window)
        )
        assertEquals(
            HealthVerdict.WAIT,
            streamHealthVerdict(bytesWritten = -1L, errorCode = null, elapsedMs = 3_000L, window = window)
        )
        assertEquals(
            HealthVerdict.WAIT,
            streamHealthVerdict(bytesWritten = -1L, errorCode = null, elapsedMs = 99_999L, window = window)
        )
    }

    /** 优先级：errorCode 与字节同时异常（含不可观测）时，errorCode 先判废——不因量不到而漏判真错误。 */
    @Test
    fun `产出活性 errorCode 与字节同时异常时 errorCode 优先`() {
        assertEquals(
            HealthVerdict.UNHEALTHY,
            streamHealthVerdict(
                bytesWritten = -1L, errorCode = RecordError.STOP_FAILED, elapsedMs = 100L, window = window
            )
        )
        assertEquals(
            HealthVerdict.UNHEALTHY,
            streamHealthVerdict(
                bytesWritten = 0L, errorCode = "${RecordError.ENGINE_ERROR}:1:2", elapsedMs = 100L, window = window
            )
        )
    }

    /**
     * 桥函数在产出活性路上的行为（计划→行为）：连续多拍量到"没在写"，长窗到点判废后，
     * 重录上界仍由 [retryActionOf] 统一把关。直接断言"判定→动作"的组合，避免只测单侧。
     */
    @Test
    fun `桥函数 产出活性判废也走同一重录上界`() {
        val verdict = streamHealthVerdict(bytesWritten = 0L, errorCode = null, elapsedMs = 3_000L, window = window)
        assertEquals(HealthVerdict.UNHEALTHY, verdict)
        assertEquals(RetryAction.RESTART, retryActionOf(attempts = 0, window = window, verdict = verdict))
        assertEquals(RetryAction.GIVE_UP, retryActionOf(attempts = 2, window = window, verdict = verdict))
    }

    /**
     * 兜底删除闸门：只允许删「不在已产出段里」的那一枚 sink。
     * 首段若已进停录结果的 parts（发生过分段轮转、已 commit），再删就是删用户成片。
     */
    @Test
    fun `被弃段兜底删除只删未进产物的那枚sink`() {
        assertFalse(
            "首段已进产物（已 commit）⇒ 不许再删",
            shouldDiscardAbandonedSink("content://media/1", listOf("content://media/1"))
        )
        assertTrue(
            "未进产物（引擎已按废片删过，重复删幂等）⇒ 允许",
            shouldDiscardAbandonedSink("content://media/2", listOf("content://media/1"))
        )
        assertFalse("没有 sink（未起会话）⇒ 不动作", shouldDiscardAbandonedSink(null, emptyList()))
        assertTrue(
            "产物为空且是本段 sink ⇒ 允许",
            shouldDiscardAbandonedSink("content://media/3", emptyList())
        )
    }

    /**
     * 停录 keep 判定的**桥测试**（审查点名：现有守卫只是 `.contains(".discard(")` 形态断言，
     * 对"自检判废后坏段仍被 commit"这个场景恒绿）。这里直断言四种组合，尤其
     * `abandoned=true, errorCode=null, dur 足够 ⇒ false`——**这就是 P1 的形态**：MR 路判废的主形态
     * 正是"一直在录但没写出数据、errorCode 仍为 null"，旧的 `errorCode==null && dur>=min` 判定会把
     * 它当正常段 commit。突变：把判定里的 `!abandoned &&` 去掉 → 这一格变 true，必红。
     */
    @Test
    fun `弃段意图压过停录的正常判定`() {
        val minKeep = 500L
        // P1 核心：无错误码、时长足够，但已声明弃段 ⇒ 必须不 keep
        assertFalse(
            "abandoned=true + 无错 + 够长 ⇒ 必须弃（旧判定会 commit 坏片）",
            segmentKeepDecision(abandoned = true, errorCode = null, durationMs = 60_000L, minKeepMs = minKeep)
        )
        // 未弃 + 无错 + 恰好够长 ⇒ keep（边界取 >= 而非 >）
        assertTrue(
            "abandoned=false + 无错 + 恰够长 ⇒ keep",
            segmentKeepDecision(abandoned = false, errorCode = null, durationMs = minKeep, minKeepMs = minKeep)
        )
        // 未弃 + 无错 + 差 1ms ⇒ 不 keep（过短按废片）
        assertFalse(
            "abandoned=false + 无错 + 差 1ms ⇒ 不 keep",
            segmentKeepDecision(abandoned = false, errorCode = null, durationMs = minKeep - 1, minKeepMs = minKeep)
        )
        // 有错误码：即便未弃、够长也对 keep 说否（引擎已判废的段不能放行）
        assertFalse(
            "有错误码即便未弃也弃",
            segmentKeepDecision(abandoned = false, errorCode = RecordError.ENGINE_ERROR, durationMs = 60_000L, minKeepMs = minKeep)
        )
        // 弃段意图是最高优先：有错误码 + 已弃 仍 false
        assertFalse(
            "弃段意图是最高优先",
            segmentKeepDecision(abandoned = true, errorCode = RecordError.ENGINE_ERROR, durationMs = 60_000L, minKeepMs = minKeep)
        )
    }

    /**
     * 产出字节双源交叉核对。**手写期望值**，尤其覆盖 -1（量不到）语义：两个都量不到才算不可观测；
     * 只要有一源给得出数（哪怕 0），就取可用源里的最大值。突变：把 `maxOf` 写成 `minOf` → 取大用例必红。
     */
    @Test
    fun `产出字节双源取可用源最大值`() {
        assertEquals("两源都量不到 ⇒ 不可观测 -1", -1L, resolveOutputBytes(-1L, -1L))
        assertEquals("fd 量不到、provider 说 0 ⇒ 0（不是 -1）", 0L, resolveOutputBytes(-1L, 0L))
        assertEquals("fd 说 0、provider 量不到 ⇒ 0", 0L, resolveOutputBytes(0L, -1L))
        assertEquals("只有 fd 有数 ⇒ 取 fd", 5L, resolveOutputBytes(5L, -1L))
        assertEquals("只有 provider 有数 ⇒ 取 provider", 7L, resolveOutputBytes(-1L, 7L))
        assertEquals("两源都有数 ⇒ 取大（provider 大）", 9L, resolveOutputBytes(3L, 9L))
        assertEquals("两源都有数 ⇒ 取大（fd 大）", 9L, resolveOutputBytes(9L, 3L))
        assertEquals("fd 已够量时取 fd", 65_536L, resolveOutputBytes(65_536L, -1L))
    }

    // ------------------------------------------------------------------ mrRotateRequested

    /**
     * MR 路换段事件判定全表（手写数字字面量，防"用实现证明实现"）。
     *
     * 字面量与 MediaRecorder 常量的对应（android-34 jar javap 实测）：
     * MEDIA_RECORDER_INFO_UNKNOWN=1、MEDIA_RECORDER_INFO_MAX_DURATION_REACHED=800、
     * MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED=801、APPROACHING=802、NEXT_OUTPUT_FILE_STARTED=803。
     * AOSP 契约：801/802 是 **what** 的取值、extra 恒 0——历史缺陷正是把这两条塞进了 extra 判定
     * （且门槛写成 what==UNKNOWN），导致换段永不触发。突变：删掉 AOSP 分支（只留 ROM 兜底）→
     * 本用例 (802,0)/(801,0) 必红；把兜底写成 `what != UNKNOWN` 一票否决 → (1,801) 必红。
     */
    @Test
    fun `mrRotateRequested AOSP 形态 what 带 801 802 即换段`() {
        assertTrue("what=801（到达上限）extra=0 ⇒ 换段", mrRotateRequested(801, 0))
        assertTrue("what=802（逼近上限）extra=0 ⇒ 换段", mrRotateRequested(802, 0))
    }

    @Test
    fun `mrRotateRequested ROM 兜底 what UNKNOWN 且 extra 带码即换段`() {
        assertTrue("what=1(UNKNOWN) extra=801 ⇒ 换段（部分 ROM 把 info 码塞进 extra）", mrRotateRequested(1, 801))
        assertTrue("what=1(UNKNOWN) extra=802 ⇒ 同上", mrRotateRequested(1, 802))
    }

    @Test
    fun `mrRotateRequested 其余事件一律不换段`() {
        assertFalse("what=1 extra=0：普通 info 不换段", mrRotateRequested(1, 0))
        assertFalse("what=0 不是任何 info 码", mrRotateRequested(0, 0))
        assertFalse("what=800 时长触顶：本应用只按文件大小换段", mrRotateRequested(800, 0))
        assertFalse("what=803 下一文件已开始：不换段", mrRotateRequested(803, 0))
        assertFalse("负值组合一律不换段", mrRotateRequested(-1, -1))
    }
}
