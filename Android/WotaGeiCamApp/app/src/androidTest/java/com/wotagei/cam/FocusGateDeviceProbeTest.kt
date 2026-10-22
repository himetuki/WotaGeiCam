package com.wotagei.cam

import android.app.Instrumentation
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wotagei.cam.core.adjustableFocus
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #44 的取证：本机每颗镜头到底会不会被判成「没有可改的对焦」。
 *
 * 只读 `CameraCharacteristics`，不开相机、不写任何用户数据，所以锁屏也能跑（`am instrument` 不需要屏幕）。
 * 结论走 `sendStatus`（本机 ROM 会丢应用自己的 Log 标签，logcat 不可靠，见 CameraEnumTest）。
 */
@RunWith(AndroidJUnit4::class)
class FocusGateDeviceProbeTest {

    private val instr: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun line(text: String) {
        instr.sendStatus(android.app.Activity.RESULT_OK, Bundle().apply { putString("wota", text) })
    }

    @Test
    fun reportAdjustableFocusPerLens() {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        assertTrue("一颗镜头都没枚举到", ids.isNotEmpty())
        line("镜头数=${ids.size}")
        ids.forEach { id ->
            val cc = manager.getCameraCharacteristics(id)
            val afModes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
            val diopter = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val facing = when (cc.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                else -> "external"
            }
            line(
                "id=$id $facing afModes=${afModes.map { afName(it) }} minFocusD=$diopter " +
                    "→ 对焦入口=${if (adjustableFocus(afModes, diopter)) "显示" else "隐藏"}"
            )
        }
    }

    /** 只解出我们关心的几个模式，其余打数字，避免表不全时误判 */
    private fun afName(mode: Int): String = when (mode) {
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "mode$mode"
    }
}
