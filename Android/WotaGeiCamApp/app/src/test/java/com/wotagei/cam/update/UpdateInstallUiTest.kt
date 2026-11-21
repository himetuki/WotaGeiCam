package com.wotagei.cam.update

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import com.wotagei.cam.ui.UpdateDialogPhase
import com.wotagei.cam.ui.updateDialogPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y46：安装启动失败必须是**独立终态**，弹窗先判失败原因，再判下载完成；失败态给重试入口。
 *
 * 安装器的 `verifyArtifact` 已由 [UpdateInstallBindingTest] 做行为测试，这里钉住 UI 的接线：
 * - `UpdateSection` 收到 Rejected 时把 `done` 清掉并保存原因（不再出现 done+installFailed 并存）；
 * - 安装调用使用 Done 里的 artifact 而非旧 `file` 布尔入口；
 * - 对话框标题分支把 installFailure 放在 done 之前，并为全部拒绝原因映射文案。
 */
class UpdateInstallUiTest {

    @Test
    fun `安装失败与下载完成并存时阶段必须判失败`() {
        assertEquals(
            "installFailure 必须压过 done（旧缺陷：弹窗显示下载完成）",
            UpdateDialogPhase.INSTALL_FAILED,
            updateDialogPhase(installFailurePresent = true, measuring = false, done = true, failed = false)
        )
        assertEquals(
            "测速阶段优先于尚未完成的下载",
            UpdateDialogPhase.MEASURING,
            updateDialogPhase(installFailurePresent = false, measuring = true, done = false, failed = false)
        )
        assertEquals(
            "下载完成",
            UpdateDialogPhase.DONE,
            updateDialogPhase(installFailurePresent = false, measuring = false, done = true, failed = false)
        )
        assertEquals(
            "下载失败",
            UpdateDialogPhase.FAILED,
            updateDialogPhase(installFailurePresent = false, measuring = false, done = false, failed = true)
        )
        assertEquals(
            "下载中",
            UpdateDialogPhase.DOWNLOADING,
            updateDialogPhase(installFailurePresent = false, measuring = false, done = false, failed = false)
        )
    }

    @Test
    fun `安装失败是独立终态且优先于下载完成显示`() {
        val src = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        val dialog = flatten(bodyOf(src, "UpdateDownloadDialog"))
        val failure = dialog.indexOf("UpdateDialogPhase.INSTALL_FAILED")
        val done = dialog.indexOf("UpdateDialogPhase.DONE")
        assertTrue("对话框必须先判 installFailure：$dialog", failure >= 0 && done > failure)
        assertTrue("失败态必须提供重试入口", dialog.contains("set_update_install_retry"))
        assertTrue("失败态必须显示具体原因映射", dialog.contains("installFailureText(state.installFailure.reason)"))
        assertTrue("失败态必须保留关闭入口", dialog.contains("set_update_close"))

        val section = flatten(bodyOf(src, "UpdateSection"))
        assertTrue(
            "安装拒绝时必须清 done 并存原因（Y46：不能与下载完成并存）",
            section.contains("done = false, installFailure = result")
        )
        assertTrue(
            "安装必须用下载产出的 Done（选择绑定 + 实际摘要），不得回到按文件名的布尔入口",
            section.contains("UpdateInstaller.install(context, artifact)")
        )
        assertFalse("旧的 installFailed 布尔字段必须消失（否则又会两态并存）", src.contains("installFailed"))
    }

    @Test
    fun `安装拒绝原因全部映射为可读文案`() {
        val src = codeOnly(mainSourceText("ui/SettingsScreen.kt"))
        val mapping = flatten(bodyOf(src, "installFailureText"))
        UpdateInstaller.InstallRejection.values().forEach { reason ->
            assertTrue("拒绝原因 $reason 没有 UI 文案映射", mapping.contains("InstallRejection.$reason"))
        }
        assertTrue(
            "重试入口必须复用同一个安装动作（不改状态模型，vanilla Compose 状态驱动）",
            src.contains("onRetryInstall: () -> Unit = onInstall")
        )
    }
}