package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线 MEND 的差分死区**接线守卫**（2026-10-17 复审 P2）。
 *
 * 背景：正常起录顺序是 `setArcConvert` → `setOutputSurface`。`setArcConvert` 执行时
 * `encoderWidth == 0`，`ArcMendPass` 不会被创建；真正创建发生在 `replaceEncoderSurface`
 * 的惰性路径。若那里不补 `setDeadband(wantArcMergeDeadband)`，ArcMendPass 取构造默认 12——
 * 运行期把 D 改成非 12 时在线 MEND 忽略、离线修复生效，"三路同规则"破产。
 * 本守卫钉住两条创建路径都带死区，删掉任一处即红。
 */
class ArcMendDeadbandWiringGuardTest {

    private val codec = codeOnly(mainSourceText("camera/GlRenderEngine.kt"))

    @Test
    fun `惰性创建ArcMendPass必须带运行期死区`() {
        val body = bodyOf(codec, "replaceEncoderSurface")
        val createAt = body.indexOf("ArcMendPass()")
        val deadbandAt = body.indexOf("setDeadband(wantArcMergeDeadband)")
        assertTrue("replaceEncoderSurface 必须惰性创建 ArcMendPass", createAt >= 0)
        assertTrue(
            "惰性创建路径必须补 setDeadband(wantArcMergeDeadband)：否则正常起录顺序下在线 MEND 恒用默认 12",
            deadbandAt >= 0
        )
        assertTrue("死区必须挂在新实例创建处（创建之后立刻设置）", deadbandAt > createAt)
    }

    @Test
    fun `setArcConvert创建路径也必须带死区`() {
        val body = bodyOf(codec, "setArcConvert")
        assertTrue("setArcConvert 的创建路径必须带 setDeadband(wantArcMergeDeadband)", body.contains("setDeadband(wantArcMergeDeadband)"))
    }
}
