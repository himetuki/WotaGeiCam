package com.wotagei.cam

import com.wotagei.cam.camera.CameraUiState
import com.wotagei.cam.camera.DeviceStatus
import com.wotagei.cam.camera.PreviewStatus
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.ui.previewGateOpening
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览遮罩判定桥（Y60）：缺相机权限时 gate 必须直呈拒权提示，不许先走 opening 转圈。
 */
class PreviewGateLogicTest {

    @Test
    fun `缺权限时即使设备在打开档也不转圈`() {
        // 权限回调关闭后的典型残态：device 还停在 CLOSE、preview 没起来
        assertFalse(
            previewGateOpening(
                CameraUiState(
                    device = DeviceStatus.CLOSE,
                    preview = PreviewStatus.INIT,
                    needsCameraPermission = true
                )
            )
        )
        // 枚举/重开中间态同样不许盖住拒权入口
        assertFalse(
            previewGateOpening(
                CameraUiState(
                    device = DeviceStatus.OPEN_ING,
                    preview = PreviewStatus.START,
                    needsCameraPermission = true
                )
            )
        )
    }

    @Test
    fun `已授权未就绪时仍给转圈`() {
        listOf(DeviceStatus.OPEN_ING, DeviceStatus.CLOSE, DeviceStatus.OPEN_SUCCEED).forEach { device ->
            assertTrue(
                "已授权 + $device 应转圈（回场重开/会话重建的正常中间态）",
                previewGateOpening(
                    CameraUiState(device = device, preview = PreviewStatus.INIT, needsCameraPermission = false)
                )
            )
        }
    }

    @Test
    fun `真失败档不给转圈`() {
        assertFalse(
            previewGateOpening(
                CameraUiState(
                    device = DeviceStatus.OPEN_FAILED_INNER_ERROR,
                    preview = PreviewStatus.INIT,
                    needsCameraPermission = false
                )
            )
        )
        assertFalse(
            "preview 已 ERROR 时一律直呈失败（既有口径）",
            previewGateOpening(
                CameraUiState(
                    device = DeviceStatus.OPEN_ING,
                    preview = PreviewStatus.ERROR,
                    needsCameraPermission = false
                )
            )
        )
    }

    @Test
    fun `CameraScreen 的 opening 必须走同一判定`() {
        val flat = KotlinSourceScan.flatten(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
        )
        assertTrue(
            "PreviewGate 的 opening 必须由 previewGateOpening(ui) 给出，且判定里含缺权限门",
            flat.contains("opening = previewGateOpening(ui)")
        )
        assertFalse(
            "旧的内联 opening 表达式（不带权限门）不许回流",
            flat.contains("opening = ui.preview != PreviewStatus.ERROR")
        )
    }
}