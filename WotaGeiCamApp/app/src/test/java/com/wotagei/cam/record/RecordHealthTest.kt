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
        // 最多重启 2 次（含首个共 3 次机会）
        assertEquals(1_200L, VIDEO_WINDOW_MS)
        assertEquals(3_000L, FULL_WINDOW_MS)
        assertEquals(2, MAX_RESTART_ATTEMPTS)
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
    fun `弱口径只认硬错误码`() {
        assertEquals(HealthVerdict.HEALTHY, weakHealthVerdict(null))
        assertEquals(HealthVerdict.UNHEALTHY, weakHealthVerdict(RecordError.ENGINE_ERROR))
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
}
