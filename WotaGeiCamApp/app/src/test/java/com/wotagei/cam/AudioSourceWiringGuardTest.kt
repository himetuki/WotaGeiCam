package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音源管理弹窗（内录体系批 2）的**源码结构守卫**。
 *
 * 行为（授权时序、状态机转移）有批 1 的 CaptureStateMachineTest 与平台守卫钉着，Compose 部分在
 * JVM 跑不起来；这里锁的是**接线红线**——这些线被"顺手重构"拆掉时编译照过、JVM 行为测试也测不到
 * （只有真机点开关才知道授权框拉不起来），所以判据全部作用在 bodyOf 摘出的函数体上：
 * - Tab 规格表必须恰好 2 项且蓝牙页整块复用 `BtSpeakerPanel`（回归红线：迁入零行为变化）；
 * - 开关→授权→回执链（enableCapture → launchCaptureConsent → onConsentResult）一条不许断；
 * - launch 异常必须有等价取消回执把 Authorizing 滚回 Idle（审查修复 P3-1 回滚闸）；
 * - controller 宿主必须是单例 `PlaybackCaptureController.get`（批 1 交接约束 P3-2）；
 * - 录制中左右开关都必须先过锁定判据（照 SizePill/LensPill 的 onLockTip 先例）；
 * - 撤销链在录制页有提示（批 1 交接 P3-3：Revoked 态给重新授权入口，弹窗小字由此提供）。
 *
 * 「尺子自己能红」一测拿同一把尺打突变体，防"删空判据照样绿"。
 */
class AudioSourceWiringGuardTest {

    private fun maskedMain(rel: String): String =
        codeOnly(KotlinSourceScan.mainSourceText(rel))

    private fun panelMain(): String = maskedMain("ui/dialog/AudioSourcePanel.kt")
    private fun screenBody(): String = bodyOf(maskedMain("ui/CameraScreen.kt"), "CameraScreen")
    /** P3-1 回滚闸（文件级 fun，不在 CameraScreen 体内）：发射授权的唯一通道 */
    private fun launchBody(): String = bodyOf(maskedMain("ui/CameraScreen.kt"), "launchCaptureConsent")

    /** 开关→授权→回执链的判据：真身与突变体都吃这一把尺（见「尺子自己能红」） */
    private fun missingConsentWires(screenBody: String, launchBody: String): List<String> =
        listOf(
            screenBody.contains("launchCaptureConsent(capture, captureConsent, showTip, app)") to
                "enableCapture 必须走带回滚闸的 launchCaptureConsent（审查修复 P3-1）",
            screenBody.contains("capture.onConsentResult(result.resultCode, result.data)") to
                "系统授权回执必须原样交 controller.onConsentResult",
            screenBody.contains("PlaybackCaptureController.get(app)") to
                "controller 宿主必须是单例 get（批 1 交接 P3-2：会话在页面外存活）",
            launchBody.contains("createConsentIntent()") to
                "开关切内录必须经 createConsentIntent（置态 Authorizing 与发射原子）",
            launchBody.contains("consentLauncher.launch(intent)") to
                "授权 intent 必须经 consent launcher 发射"
        ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    /** P3-1 回滚闸的判据：launchCaptureConsent 体内发射/try/回执三件（真身与突变体同吃） */
    private fun missingRollbackWires(launchBody: String): List<String> = listOf(
        "consentLauncher.launch(intent)" to "授权框必须经 launcher 发射",
        "try {" to "发射必须包 try（launch 抛系统异常时 Authorizing 会悬在无框死态）",
        "onConsentResult(Activity.RESULT_CANCELED" to "launch 异常必须按等价用户取消回执（回滚 Idle）"
    ).filter { (token, _) -> !launchBody.contains(token) }.map { (_, why) -> why }

    @Test
    fun `Tab规格表恰好两项且蓝牙整块在位`() {
        val tabs = bodyOf(panelMain(), "audioSourceTabs")
        // occurrences 判词边界：needle 带 "(" 会被"后随标识符"拒掉（AudioTabSpec(key…），所以取裸词
        assertEquals(
            "音源管理的内容表必须恰好 2 项（加页签=加配置，删页签要先改这条裁决）",
            2,
            occurrences(tabs, "AudioTabSpec").size
        )
        // codeOnly 把字符串字面量遮成空格：判 key 只能判 R.string 引用（代码）
        assertTrue("必须有音源选择页（audio_tab_source）", tabs.contains("audio_tab_source"))
        assertTrue("必须有蓝牙页（audio_tab_bluetooth）", tabs.contains("audio_tab_bluetooth"))
        assertTrue(
            "蓝牙页必须整块复用 BtSpeakerPanel（迁入零行为变化的回归红线）",
            tabs.contains("BtSpeakerPanel(bt)")
        )
    }

    @Test
    fun `开关到授权到回执链在位`() {
        val missing = missingConsentWires(screenBody(), launchBody())
        assertEquals(
            "授权时序链断线（真机上表现是开关切了、授权框不出现或回执没人接）：\n${missing.joinToString("\n")}",
            emptyList<String>(),
            missing
        )
        // 撤销链（批 1 交接 P3-3）：Revoked 态必须给回退提示，重新授权入口由弹窗小字承载
        val screen = screenBody()
        assertTrue(
            "撤销态必须有提示（audio_state_revoked）",
            screen.contains("CaptureState.Revoked") && screen.contains("audio_state_revoked")
        )
        // 弹窗参数链：PillHost 把 capture 与授权入口传进音频面板
        val pills = maskedMain("ui/CameraPills.kt")
        assertTrue(
            "PillHost 的 BT 分支必须接 AudioPill（锚点纪律：就近弹窗锚在触发它的那颗 chip 上）",
            pills.contains("AudioPill(anchor, capture, recording, onEnableCapture, onLockTip, bt, onClose)")
        )
        assertTrue(
            "PillHost 必须把 capture/onEnableCapture 转传给 AudioSourcePanel",
            bodyOf(pills, "AudioPill").contains("onEnableCapture = onEnableCapture") &&
                bodyOf(pills, "AudioPill").contains("capture = capture")
        )
    }

    @Test
    fun `launch异常有Authorizing回滚`() {
        val real = launchBody()
        val missing = missingRollbackWires(real)
        assertEquals(
            "P3-1 回滚闸缺件（launch 抛异常时 Authorizing 会悬死）：\n${missing.joinToString("\n")}",
            emptyList<String>(),
            missing
        )
        // 调用点唯一性：launchCaptureConsent 是发射授权的唯一入口（CameraScreen 不得绕过直接 launch）
        val screen = screenBody()
        assertEquals(
            "consentLauncher.launch 只许出现在回滚闸体内，CameraScreen 不得绕过",
            1,
            occurrences(codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")), "consentLauncher.launch").size
        )
        assertTrue("enableCapture 必须接线到回滚闸", screen.contains("launchCaptureConsent(capture, captureConsent, showTip, app)"))
        // 红绿突变：拆掉回执行，同一把尺必须立刻报红
        val mutated = real.replace("capture.onConsentResult(Activity.RESULT_CANCELED, null)", "Unit")
        assertTrue(
            "拆掉回执后判据必须报红（防守卫被删空照样绿）",
            missingRollbackWires(mutated).isNotEmpty()
        )
    }

    @Test
    fun `录制中开关锁定与状态派生判据`() {
        val tab = bodyOf(panelMain(), "AudioSourceTab")
        assertEquals(
            "左右开关都必须先过录制锁定判据（照 SizePill/LensPill 的 onLockTip 先例）",
            2,
            occurrences(tab, "recording -> onLockTip()").size
        )
        assertTrue(
            "内录开关的选中态必须派生自 controller 状态（状态真源是 controller）",
            tab.contains("captureState is CaptureState.Active")
        )
        // chip 双态并列 + 读屏语义（P3-2）：HudLayer 的 BT 位渲染 AudioChip 且把 captureActive 传进去
        val hud = maskedMain("ui/HudLayer.kt")
        val btBranch = bodyOf(hud, "HudEntryItem")
        assertTrue(
            "CamPill.BT 位必须渲染 AudioChip 并接入内录激活态",
            btBranch.contains("AudioChip(") && btBranch.contains("captureActive = ctx.captureActive")
        )
        // P3-2：chip 读屏描述必须按态拼接（内录态 + 蓝牙态进 contentDescription）
        val chip = bodyOf(maskedMain("ui/widget/AudioChip.kt"), "AudioChip")
        assertTrue(
            "AudioChip 必须拼接态语义给 TalkBack（audio_chip_capture_on）",
            chip.contains("audio_chip_capture_on") && chip.contains("contentDescription = desc")
        )
    }

    @Test
    fun `尺子自己能红`() {
        // 突变自证 1：拆掉授权发射，同一把尺必须立刻报红——否则删空判据也全绿
        val realLaunch = launchBody()
        val mutatedLaunch = realLaunch.replace("createConsentIntent()", "createConsentIntentMutated()")
        assertTrue(
            "突变体必须至少断一条链",
            missingConsentWires(screenBody(), mutatedLaunch).isNotEmpty()
        )
        // 突变自证 2：录制锁定判据拆掉后必须数不出 2 处
        val tab = bodyOf(panelMain(), "AudioSourceTab")
        val mutatedTab = tab.replace("recording -> onLockTip()", "recording -> Unit")
        assertEquals(0, occurrences(mutatedTab, "recording -> onLockTip()").size)
        // 真身上同一把尺必须绿（保证上面两条红不是尺子坏了）
        assertEquals(2, occurrences(tab, "recording -> onLockTip()").size)
        assertTrue(missingConsentWires(screenBody(), realLaunch).isEmpty())
        assertTrue(missingRollbackWires(realLaunch).isEmpty())
    }
}
