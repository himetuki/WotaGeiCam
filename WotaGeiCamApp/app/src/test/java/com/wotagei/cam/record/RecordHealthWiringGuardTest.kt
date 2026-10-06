package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 起录自检在 UI 侧的**接线守卫**（[com.wotagei.cam.ui.CameraScreen] 的 `RecordRunner`）。
 *
 * 纯函数判定再好，接线断了就白搭：自检拍子没挂 → 永不重录；丢弃没兜底 → 幽灵 pending；
 * 触顶没引用上限 → 无限重启。这里逐条钉住：
 * - `healthCheckLoop` 存在且真调了 [healthVerdict]/[retryActionOf]（不是空拍）；
 * - 无逐格信号的引擎走 [weakHealthVerdict]（不许拿三格套超窗误重录）；
 * - `abandonAndRestart` 必须丢弃被弃段的 pending（`discard`，且过 `shouldDiscardAbandonedSink`
 *   闸门——只删未进产物的那枚）并重放会话；
 * - `MAX_RESTART_ATTEMPTS` 被**函数体**引用（不存在无限重试；不锁整文件，避免 import 行喂绿）；
 * - 自检失败走明确失败（`SELF_CHECK_FAILED`），**不许**闪 ERROR：`abandonAndRestart` 体内
 *   不得出现 `RecordStatus.ERROR`（status 全程保持 START）；
 * - **停止路径不受自检影响**：`stopInternal` 体内不得出现任何自检分支（负面断言）。
 */
class RecordHealthWiringGuardTest {

    private val screenSrc by lazy { mainSourceText("ui/CameraScreen.kt") }
    private val masked by lazy { codeOnly(screenSrc) }

    @Test
    fun `healthCheckLoop 必须真调自检纯函数并按判定动作`() {
        val body = bodyOf(masked, "healthCheckLoop")
        assertTrue("锚点丢失：没截到引擎读书", body.contains("rec.health()"))
        assertTrue(
            "healthCheckLoop 必须调 healthVerdict（可观测引擎）",
            body.contains("healthVerdict(")
        )
        assertTrue(
            "healthCheckLoop 必须调 retryActionOf 决定动作",
            body.contains("retryActionOf(")
        )
        assertTrue(
            "healthCheckLoop 必须按 milestonesObservable 分流到 weakHealthVerdict",
            body.contains("milestonesObservable") && body.contains("weakHealthVerdict(")
        )
        assertTrue(
            "WAIT 必须重投自检拍（否则一次 WAIT 后永不再问）",
            body.contains("handler.postDelayed(healthCheckTask, HEALTH_TICK_MS)")
        )
        assertTrue(
            "触顶必须走明确失败 SELF_CHECK_FAILED",
            body.contains("RecordError.SELF_CHECK_FAILED")
        )
    }

    @Test
    fun `abandonAndRestart 必须丢弃被弃段并重放会话`() {
        val body = bodyOf(masked, "abandonAndRestart")
        // 复刻 stopInternal 的拆解纪律（顺序在源码里，注释遮蔽后可判的形态）
        assertTrue("停止期必须先置位（GL 侧抑制拆解伪影）", body.contains("markRecordTearingDown()"))
        assertTrue("MEND 滞留帧必须先冲刷", body.contains("flushArcPending()"))
        assertTrue("必须先停引擎再释放", body.contains("rec.stop()") && body.contains("rec.release()"))
        assertTrue(
            "必须 discard 被弃段的 sink（幽灵 pending 兜底）",
            body.contains(".discard(")
        )
        assertTrue(
            "discard 必须过 shouldDiscardAbandonedSink 闸门（sessionSink 只在 beginSession 赋值，" +
                "分段轮转后它指向已 commit 的首段，无条件删会删用户成片）",
            body.contains("shouldDiscardAbandonedSink(")
        )
        assertTrue("必须 attempt++ 记录已用机会", body.contains("attempt++"))
        assertTrue("必须重放 beginSession", body.contains("beginSession()"))
        assertFalse(
            "自动重录不许闪 ERROR（status 全程保持 START），只许 failNow 走失败",
            body.contains("RecordStatus.ERROR")
        )
    }

    @Test
    fun `重录必须复用同一套会话接线`() {
        val begin = bodyOf(masked, "beginSession")
        assertTrue("会话体必须每段必发 setArcConvert", begin.contains("setArcConvert(profile.arcConvert, profile.fps)"))
        assertTrue(
            "会话体必须下发编码面并等挂好（首录与重录同一口径）",
            begin.contains("setOutputSurface(surface, profile.width, profile.height, profile.fps)") &&
                begin.contains("awaitEncoderSurface()")
        )
        assertTrue("会话体必须重建 pending（partIndex=0 起头）", begin.contains("createPending(0)"))
        assertTrue("会话体必须在成功后才置 START", begin.contains("status.value = RecordStatus.START"))
    }

    @Test
    fun `重试上界必须被源码引用 不存在无限重试`() {
        // 只锁**函数体**（bodyOf）而非整文件 occurrences——后者会被
        // `import com.wotagei.cam.record.MAX_RESTART_ATTEMPTS` 那一行喂绿（AGENTS 点名的
        // "声明行喂绿"）：把 healthCheckLoop 里的常量换成字面量 2 时旧写法仍绿。
        // 上界本身的边界语义另由 RecordHealthTest 的桥函数用例（attempts<max）直断言。
        assertTrue(
            "healthCheckLoop 必须引用 MAX_RESTART_ATTEMPTS（不存在无限重试）",
            bodyOf(masked, "healthCheckLoop").contains("MAX_RESTART_ATTEMPTS")
        )
    }

    @Test
    fun `停止路径不受自检影响`() {
        val body = bodyOf(masked, "stopInternal")
        assertTrue("锚点丢失：切片没截到 rec.stop()", body.contains("rec.stop()"))
        // 负面断言：stop 收尾绝不能掺进自检分支（重录/自检只在录制中健康循环里发生）
        assertFalse("stopInternal 不许出现 healthCheckLoop", body.contains("healthCheckLoop"))
        assertFalse("stopInternal 不许出现 abandonAndRestart", body.contains("abandonAndRestart"))
        assertFalse("stopInternal 不许出现 healthVerdict", body.contains("healthVerdict"))
        assertFalse("stopInternal 不许出现 retryActionOf", body.contains("retryActionOf"))
    }
}
